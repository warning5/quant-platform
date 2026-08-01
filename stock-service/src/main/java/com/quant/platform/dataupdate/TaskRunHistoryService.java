package com.quant.platform.dataupdate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务执行历史服务
 * 封装 task_run_history 表的写入、查询与聚合统计（供监控页展示）。
 */
@Slf4j
@Service
public class TaskRunHistoryService {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TaskRunHistory> ROW_MAPPER = (rs, rowNum) -> {
        TaskRunHistory h = new TaskRunHistory();
        h.setId(rs.getLong("id"));
        h.setTaskKey(rs.getString("task_key"));
        h.setTaskName(rs.getString("task_name"));
        h.setTriggerType(rs.getString("trigger_type"));
        h.setUpdateType(rs.getString("update_type"));
        h.setUpstreamKey(rs.getString("upstream_key"));
        h.setStartTime(rs.getTimestamp("start_time").toLocalDateTime());
        Timestamp end = rs.getTimestamp("end_time");
        h.setEndTime(end != null ? end.toLocalDateTime() : null);
        h.setStatus(rs.getString("status"));
        int ec = rs.getInt("exit_code");
        h.setExitCode(rs.wasNull() ? null : ec);
        int dur = rs.getInt("duration_sec");
        h.setDurationSec(rs.wasNull() ? null : dur);
        h.setErrorMsg(rs.getString("error_msg"));
        h.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return h;
    };

    public TaskRunHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条 RUNNING 历史记录，返回自增 id（供结束时回填）
     */
    public long insertRunning(String taskKey, String taskName, String triggerType,
                              String updateType, String upstreamKey) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        String sql = "INSERT INTO task_run_history " +
            "(task_key, task_name, trigger_type, update_type, upstream_key, start_time, status, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)";
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, taskKey);
            ps.setString(2, taskName);
            ps.setString(3, triggerType);
            ps.setString(4, updateType);
            ps.setString(5, upstreamKey);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            ps.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey() != null ? keyHolder.getKey().longValue() : -1L;
    }

    /**
     * 结束一条历史记录（成功/失败/超时/部分）
     */
    public void finish(long historyId, String status, Integer exitCode, String errorMsg) {
        if (historyId <= 0) return;
        try {
            jdbcTemplate.update(
                "UPDATE task_run_history SET end_time=?, status=?, exit_code=?, error_msg=?, " +
                "duration_sec = TIMESTAMPDIFF(SECOND, start_time, ?) WHERE id=?",
                LocalDateTime.now(), status, exitCode, truncate(errorMsg, 1000),
                LocalDateTime.now(), historyId);
        } catch (Exception e) {
            log.error("[TaskRunHistory] 更新历史记录失败 id={}: {}", historyId, e.getMessage());
        }
    }

    /**
     * 将孤儿 RUNNING 记录标记为 TIMEOUT
     */
    public int markTimeoutOrphans(int olderThanMinutes) {
        try {
            return jdbcTemplate.update(
                "UPDATE task_run_history SET status='TIMEOUT', end_time=?, " +
                "error_msg='执行超时或被中断(孤儿), 已被自动标记为TIMEOUT' " +
                "WHERE status='RUNNING' AND start_time < DATE_SUB(NOW(), INTERVAL ? MINUTE)",
                LocalDateTime.now(), olderThanMinutes);
        } catch (Exception e) {
            log.error("[TaskRunHistory] 标记超时孤儿失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 分页查询历史
     */
    public Map<String, Object> list(String taskKey, String status, String triggerType,
                                    String startDate, String endDate, int page, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (taskKey != null && !taskKey.isEmpty()) { where.append(" AND task_key = ?"); params.add(taskKey); }
        if (status != null && !status.isEmpty()) { where.append(" AND status = ?"); params.add(status); }
        if (triggerType != null && !triggerType.isEmpty()) { where.append(" AND trigger_type = ?"); params.add(triggerType); }
        if (startDate != null && !startDate.isEmpty()) { where.append(" AND start_time >= ?"); params.add(startDate + " 00:00:00"); }
        if (endDate != null && !endDate.isEmpty()) { where.append(" AND start_time <= ?"); params.add(endDate + " 23:59:59"); }

        int total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM task_run_history" + where, params.toArray(), Integer.class);

        String sql = "SELECT * FROM task_run_history" + where +
            " ORDER BY start_time DESC LIMIT ? OFFSET ?";
        List<Object> qp = new ArrayList<>(params);
        qp.add(pageSize);
        qp.add((page - 1) * pageSize);
        List<TaskRunHistory> rows = jdbcTemplate.query(sql, ROW_MAPPER, qp.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("list", rows);
        return result;
    }

    /**
     * 最近失败列表
     */
    public List<TaskRunHistory> recentFailures(int limit) {
        return jdbcTemplate.query(
            "SELECT * FROM task_run_history WHERE status IN ('FAILED','TIMEOUT') " +
            "ORDER BY start_time DESC LIMIT ?",
            ROW_MAPPER, limit);
    }

    /**
     * 聚合统计（最近 days 天）：每个任务的成功率、失败次数、连续失败次数
     */
    public List<Map<String, Object>> stats(int days) {
        // 每个 task_key 的总次数与成功次数
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT task_key, task_name, " +
            "COUNT(*) AS total, " +
            "SUM(CASE WHEN status='SUCCESS' OR status='PARTIAL' THEN 1 ELSE 0 END) AS ok, " +
            "SUM(CASE WHEN status='FAILED' OR status='TIMEOUT' THEN 1 ELSE 0 END) AS fail " +
            "FROM task_run_history WHERE start_time >= DATE_SUB(NOW(), INTERVAL ? DAY) " +
            "GROUP BY task_key, task_name ORDER BY fail DESC, task_key",
            days);

        // 计算每个任务的"当前连续失败次数"（从最近往回数，直到遇到成功）
        for (Map<String, Object> r : rows) {
            String tk = (String) r.get("task_key");
            int consecutive = computeConsecutiveFailures(tk);
            r.put("consecutiveFailures", consecutive);
            int total = ((Number) r.get("total")).intValue();
            int fail = ((Number) r.get("fail")).intValue();
            double rate = total > 0 ? (1 - (double) fail / total) * 100.0 : 100.0;
            r.put("successRate", Math.round(rate * 10.0) / 10.0);
        }
        return rows;
    }

    /**
     * 计算某任务当前的连续失败次数：按 start_time 倒序取最近 20 条，直到遇到 SUCCESS/PARTIAL 为止
     */
    private int computeConsecutiveFailures(String taskKey) {
        List<String> recent = jdbcTemplate.queryForList(
            "SELECT status FROM task_run_history WHERE task_key = ? " +
            "ORDER BY start_time DESC LIMIT 20",
            String.class, taskKey);
        int count = 0;
        for (String s : recent) {
            if ("SUCCESS".equals(s) || "PARTIAL".equals(s)) break;
            if ("FAILED".equals(s) || "TIMEOUT".equals(s)) count++;
        }
        return count;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
