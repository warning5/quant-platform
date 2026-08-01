package com.quant.platform.system.monitor;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 系统指标采集器
 * - HTTP：作为 Servlet Filter 记录每次请求（方法/路径/状态码/耗时），环形缓冲保留最近 500 条。
 * - JVM：通过 ManagementFactory 读取堆内存 / 线程 / GC / 运行时信息。
 * - ClickHouse：可选健康检查（clickHouseJdbcTemplate 未启用时为 disabled）。
 * - 任务：复用 task_run_history 聚合运行中 / 今日失败 / 最近一次运行。
 * 数据均为进程内实时统计，无需额外存储。
 */
@Slf4j
@Component
public class MetricsCollector implements Filter {

    private final JdbcTemplate jdbcTemplate;
    @Nullable
    private final JdbcTemplate clickHouseJdbcTemplate;

    /** 最近请求环形缓冲（上限 500） */
    private final ConcurrentLinkedDeque<RequestRecord> recent = new ConcurrentLinkedDeque<>();
    /** 最近页面访问埋点（前端路由切换上报，上限 500） */
    private final ConcurrentLinkedDeque<PageView> pageViews = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 500;

    /** GC 累计值采样（用于计算最近 N 分钟的 GC 增量） */
    private final ConcurrentLinkedDeque<GcSample> gcSamples = new ConcurrentLinkedDeque<>();
    private static final int MAX_GC_SAMPLES = 200;
    private static final long GC_WINDOW_MS = 5 * 60 * 1000; // 5 分钟窗口
    record GcSample(long ts, long gcCount, long gcTimeMs) {}

    record RequestRecord(long ts, String method, String path, int status, long durationMs) {}
    record PageView(long ts, String username, String path) {}

    public MetricsCollector(JdbcTemplate jdbcTemplate,
                            @Autowired(required = false) @Nullable JdbcTemplate clickHouseJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq)) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        int status = 0;
        try {
            chain.doFilter(request, response);
            if (response instanceof HttpServletResponse httpResp) {
                status = httpResp.getStatus();
            }
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String path = httpReq.getRequestURI();
            // 跳过静态资源与 actuator，避免噪声
            if (!path.startsWith("/api/") && !path.startsWith("/static/")) {
                // no-op，仍记录以反映真实流量
            }
            recent.addLast(new RequestRecord(System.currentTimeMillis(), httpReq.getMethod(), path, status, durationMs));
            while (recent.size() > MAX_RECENT) {
                recent.pollFirst();
            }
        }
    }

    /** JVM + HTTP + CH + 任务 概览 */
    public Overview snapshot() {
        return new Overview(jvm(), http(), clickhouse(), tasks());
    }

    public List<RequestRecord> recentRequests() {
        return new ArrayList<>(recent);
    }

    /** 前端路由切换埋点：记录页面访问 */
    public void recordPageView(String username, String path) {
        if (path == null || path.isBlank()) return;
        pageViews.addLast(new PageView(System.currentTimeMillis(), username == null ? "-" : username, path));
        while (pageViews.size() > MAX_RECENT) {
            pageViews.pollFirst();
        }
    }

    /** 页面访问分布：按路由聚合访问次数 + 最近访问时间（Top 15） */
    public List<Map<String, Object>> pageViewStats() {
        Map<String, long[]> agg = new LinkedHashMap<>(); // path -> [count, lastTs]
        for (PageView p : pageViews) {
            long[] v = agg.computeIfAbsent(p.path, k -> new long[2]);
            v[0]++;
            v[1] = p.ts;
        }
        return agg.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(15)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", e.getKey());
                    m.put("count", e.getValue()[0]);
                    m.put("lastVisit", e.getValue()[1]);
                    return m;
                }).collect(Collectors.toList());
    }

    private JvmStat jvm() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapUsed = mem.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long heapCommitted = mem.getHeapMemoryUsage().getCommitted() / (1024 * 1024);
        long heapMax = mem.getHeapMemoryUsage().getMax() / (1024 * 1024);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcTime += gc.getCollectionTime();
        }
        long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        int processors = Runtime.getRuntime().availableProcessors();

        // 记录 GC 累计值采样，并计算最近 5 分钟增量（处理服务重启计数归零）
        long now = System.currentTimeMillis();
        gcSamples.addLast(new GcSample(now, gcCount, gcTime));
        while (gcSamples.size() > MAX_GC_SAMPLES) gcSamples.pollFirst();
        long baseCount = gcCount, baseTime = gcTime;
        for (GcSample s : gcSamples) {
            if (s.ts >= now - GC_WINDOW_MS) { baseCount = s.gcCount; baseTime = s.gcTimeMs; break; }
        }
        // 基准点计数大于当前值 → 中途重启过，无法可靠计算窗口增量，回退为 0
        long gcCountRecent = baseCount > gcCount ? 0 : Math.max(0, gcCount - baseCount);
        long gcTimeMsRecent = baseTime > gcTime ? 0 : Math.max(0, gcTime - baseTime);

        return new JvmStat(heapUsed, heapCommitted, heapMax, threads.getThreadCount(),
                threads.getPeakThreadCount(), gcCount, gcTime, gcCountRecent, gcTimeMsRecent, uptimeSec, processors);
    }

    private HttpStat http() {
        long now = System.currentTimeMillis();
        long window = 60_000;
        int total = recent.size();
        int lastMinute = 0, errors = 0;
        long sum = 0;
        List<Long> durations = new ArrayList<>();
        for (RequestRecord r : recent) {
            if (now - r.ts <= window) lastMinute++;
            if (r.status >= 400) errors++;
            sum += r.durationMs;
            durations.add(r.durationMs);
        }
        durations.sort(Long::compareTo);
        long avg = total == 0 ? 0 : sum / total;
        long p95 = durations.isEmpty() ? 0 : durations.get((int) (durations.size() * 0.95));
        double qps = (double) lastMinute / 60.0;
        return new HttpStat(total, lastMinute, errors, avg, p95, Math.round(qps * 100.0) / 100.0);
    }

    private ClickHouseStat clickhouse() {
        if (clickHouseJdbcTemplate == null) {
            return new ClickHouseStat(false, false, -1, null, 0);
        }
        long start = System.nanoTime();
        try {
            clickHouseJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            String version = null;
            int tableCount = 0;
            try {
                version = clickHouseJdbcTemplate.queryForObject("SELECT version()", String.class);
            } catch (Exception ignored) { }
            try {
                tableCount = clickHouseJdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system.tables WHERE database = currentDatabase()", Integer.class);
            } catch (Exception ignored) { }
            return new ClickHouseStat(true, true, latencyMs, version, tableCount);
        } catch (Exception e) {
            return new ClickHouseStat(true, false, -1, null, 0);
        }
    }

    private TaskStat tasks() {
        try {
            Integer running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_run_history WHERE status='RUNNING'", Integer.class);
            Integer failedToday = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_run_history WHERE status='FAILED' AND start_time >= CURDATE()", Integer.class);
            Integer successToday = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_run_history WHERE status='SUCCESS' AND start_time >= CURDATE()", Integer.class);
            Integer totalToday = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_run_history WHERE start_time >= CURDATE()", Integer.class);
            TaskLast last = jdbcTemplate.queryForObject(
                    "SELECT task_key, status, start_time, end_time, duration_sec FROM task_run_history ORDER BY start_time DESC LIMIT 1",
                    (rs, i) -> new TaskLast(rs.getString("task_key"), rs.getString("status"),
                            toLdt(rs.getTimestamp("start_time")), toLdt(rs.getTimestamp("end_time")),
                            rs.getObject("duration_sec") == null ? null : rs.getInt("duration_sec")));
            List<TaskLast> recentTasks = jdbcTemplate.query(
                    "SELECT task_key, status, start_time, end_time, duration_sec FROM task_run_history ORDER BY start_time DESC LIMIT 5",
                    (rs, i) -> new TaskLast(rs.getString("task_key"), rs.getString("status"),
                            toLdt(rs.getTimestamp("start_time")), toLdt(rs.getTimestamp("end_time")),
                            rs.getObject("duration_sec") == null ? null : rs.getInt("duration_sec")));
            return new TaskStat(running == null ? 0 : running,
                    failedToday == null ? 0 : failedToday,
                    successToday == null ? 0 : successToday,
                    totalToday == null ? 0 : totalToday,
                    last, recentTasks);
        } catch (Exception e) {
            log.warn("[MetricsCollector] 读取任务指标失败: {}", e.getMessage());
            return new TaskStat(0, 0, 0, 0, null, Collections.emptyList());
        }
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ---- DTO ----
    public record Overview(JvmStat jvm, HttpStat http, ClickHouseStat clickhouse, TaskStat tasks) {}
    public record JvmStat(long heapUsedMb, long heapCommittedMb, long heapMaxMb, int threadCount, int peakThreadCount,
                          long gcCount, long gcTimeMs, long gcCountRecent, long gcTimeMsRecent, long uptimeSec, int processors) {}
    public record HttpStat(int total, int lastMinute, int errors, long avgMs, long p95Ms, double qps) {}
    public record ClickHouseStat(boolean enabled, boolean healthy, long latencyMs,
                                  String version, int tableCount) {}
    public record TaskStat(int runningCount, int failedToday, int successToday, int totalToday,
                           TaskLast last, List<TaskLast> recentTasks) {}
    public record TaskLast(String taskKey, String status, LocalDateTime startTime, LocalDateTime endTime, Integer durationSec) {}
}
