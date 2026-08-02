package com.quant.platform.system.monitor;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.system.configcenter.ConfigService;
import com.quant.platform.system.dict.DictService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统监控面板控制器
 * 路径前缀 /system/monitor，权限沿用 system 模块（system:monitor:list）。
 * 注意：与业务域「盘中监控」(/monitor) 互不冲突，本控制器聚焦平台级运行指标。
 */
@Slf4j
@RestController
@RequestMapping("/system/monitor")
public class PlatformMonitorController {

    private final MetricsCollector metricsCollector;
    private final JdbcTemplate jdbcTemplate;
    private final DictService dictService;
    private final ConfigService configService;

    public PlatformMonitorController(MetricsCollector metricsCollector, JdbcTemplate jdbcTemplate,
                                     DictService dictService, ConfigService configService) {
        this.metricsCollector = metricsCollector;
        this.jdbcTemplate = jdbcTemplate;
        this.dictService = dictService;
        this.configService = configService;
    }

    /** 概览：JVM + HTTP + ClickHouse + 任务 */
    @GetMapping("/overview")
    @SaCheckPermission("system:monitor:list")
    public ApiResponse<MetricsCollector.Overview> overview() {
        return ApiResponse.success(metricsCollector.snapshot());
    }

    /** 最近请求明细（最多 200 条，倒序） */
    @GetMapping("/http-log")
    @SaCheckPermission("system:monitor:list")
    public ApiResponse<List<Map<String, Object>>> httpLog() {
        List<Map<String, Object>> list = metricsCollector.recentRequests().stream()
                .skip(Math.max(0, metricsCollector.recentRequests().size() - 200))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ts", r.ts());
                    m.put("method", r.method());
                    m.put("path", r.path());
                    m.put("status", r.status());
                    m.put("durationMs", r.durationMs());
                    return m;
                })
                .collect(Collectors.toList());
        Collections.reverse(list);
        return ApiResponse.success(list);
    }

    /** 行为统计：页面访问分布 + 在线会话数 + 今日任务活跃度（R9，复用 R8 采集） */
    @GetMapping("/behavior")
    @SaCheckPermission("system:monitor:list")
    public ApiResponse<Map<String, Object>> behavior() {
        int onlineCount = StpUtil.searchSessionId("", 0, 1000, false).size();
        Integer todayTasks = 0;
        try {
            todayTasks = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT task_key) FROM task_run_history WHERE start_time >= CURDATE()", Integer.class);
        } catch (Exception e) {
            log.warn("[Monitor] 读取今日任务数失败: {}", e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageViews", metricsCollector.pageViewStats());
        result.put("onlineCount", onlineCount);
        result.put("todayActiveTasks", todayTasks == null ? 0 : todayTasks);
        return ApiResponse.success(result);
    }

    /** 缓存监控：dict + config 两组本地缓存的实时统计（大小/命中/未命中/命中率/最后加载时间） */
    @GetMapping("/cache")
    @SaCheckPermission("system:monitor:list")
    public ApiResponse<List<CacheStats.Snapshot>> cache() {
        return ApiResponse.success(List.of(dictService.getCacheStats(), configService.getCacheStats()));
    }

    /** 前端路由切换埋点（任意已登录用户可上报自身导航，限流由全局限流承担） */
    @PostMapping("/track")
    @SaCheckLogin
    public ApiResponse<?> track(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        if (path == null || path.isBlank()) return ApiResponse.success("ok");
        Object loginId = StpUtil.getLoginIdDefaultNull();
        String username = "-";
        if (loginId instanceof Number n) {
            try {
                username = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(nickname, username) FROM sys_user WHERE id = ?", String.class, n.longValue());
            } catch (Exception ignored) {
            }
        }
        metricsCollector.recordPageView(username, path);
        return ApiResponse.success("ok");
    }
}
