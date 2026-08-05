package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.quant.platform.common.enums.JobStatus;
/**
 * 定时任务执行历史 / 监控告警查询接口
 */
@Slf4j
@RestController
@RequestMapping("/task-history")
@RequiredArgsConstructor
@Tag(name = "任务监控", description = "定时任务执行历史、成功率统计、失败告警配置")
@cn.dev33.satoken.annotation.SaCheckPermission("data:view")
public class TaskRunHistoryController {

    private final TaskRunHistoryService taskRunHistoryService;
    private final JdbcTemplate jdbcTemplate;
    private final com.quant.platform.notification.NotificationConfigService notificationConfigService;
    private final com.quant.platform.notification.NotificationService notificationService;

    @GetMapping("/list")
    @Operation(summary = "分页查询执行历史")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String taskKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> result = taskRunHistoryService.list(
            taskKey, status, triggerType, startDate, endDate, page, pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/stats")
    @Operation(summary = "按任务聚合统计（成功率/失败次数/连续失败）")
    public ApiResponse<List<Map<String, Object>>> stats(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(taskRunHistoryService.stats(days));
    }

    @GetMapping("/recent-failures")
    @Operation(summary = "最近失败列表")
    public ApiResponse<List<TaskRunHistory>> recentFailures(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(taskRunHistoryService.recentFailures(limit));
    }

    @GetMapping("/sla")
    @Operation(summary = "SLA 监控看板：全量任务今日执行状态 vs SLA 期望")
    public ApiResponse<List<Map<String, Object>>> slaDashboard() {
        // 以 data_schedule_config 为基准（全量任务），左连接 sla_config 获取 SLA 配置。
        // 未配置 SLA 的任务也展示（slaConfigured=0），避免看板只显示部分任务。
        // 用 AS 别名统一输出 camelCase，与前端字段命名一致。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT c.task_key AS taskKey, " +
            "COALESCE(c.task_name, c.task_key) AS taskName, " +
            "s.expected_finish_hour AS expectedFinishHour, " +
            "s.max_duration_min AS maxDurationMin, " +
            "COALESCE(s.severity, 'LOW') AS severity, " +
            "CASE WHEN s.task_key IS NULL THEN 0 ELSE 1 END AS slaConfigured, " +
            "sev.sort AS severitySort, sev.color AS severityColor " +
            "FROM data_schedule_config c " +
            "LEFT JOIN sla_config s ON s.task_key = c.task_key AND s.enabled = 1 " +
            "LEFT JOIN sys_dict_data sev ON sev.dict_type = 'SLA_SEVERITY' " +
            "   AND sev.dict_value = COALESCE(s.severity, 'LOW') AND sev.deleted = 0 AND sev.status = 1 " +
            "WHERE c.task_key <> 'GLOBAL' " +
            "ORDER BY slaConfigured DESC, COALESCE(sev.sort, 999) ASC, taskKey");
        for (Map<String, Object> r : rows) {
            String tk = (String) r.get("taskKey");
            // 今日最近一次执行（queryForList 在无线程时不抛异常，返回空列表）
            List<Map<String, Object>> lastRuns = jdbcTemplate.queryForList(
                "SELECT status, start_time, end_time, duration_sec, error_msg " +
                "FROM task_run_history WHERE task_key = ? AND start_time >= CURDATE() " +
                "ORDER BY start_time DESC LIMIT 1", tk);
            if (lastRuns.isEmpty()) {
                r.put("lastStatus", null);
                r.put("lastStartTime", null);
                r.put("lastEndTime", null);
                r.put("lastDurationSec", null);
                r.put("errorMsg", null);
            } else {
                Map<String, Object> last = lastRuns.get(0);
                r.put("lastStatus", last.get("status"));
                r.put("lastStartTime", last.get("start_time"));
                r.put("lastEndTime", last.get("end_time"));
                r.put("lastDurationSec", last.get("duration_sec"));
                r.put("errorMsg", last.get("error_msg"));
            }
            // 计算 SLA：未配置 SLA 的任务不参与达标判定（slaMet=null，前端显示"未设SLA"）
            int configured = ((Number) r.get("slaConfigured")).intValue();
            r.put("slaMet", configured == 1 ? evaluateSla(r) : null);
        }
        return ApiResponse.success(rows);
    }

    /** 评估单个任务今日 SLA 是否达标 */
    private boolean evaluateSla(Map<String, Object> r) {
        Object lastStatus = r.get("lastStatus");
        if (lastStatus == null) {
            // 今日尚未执行：若已超过期望完成时刻，则未达标
            Object efh = r.get("expectedFinishHour");
            if (efh != null) {
                int hour = ((Number) efh).intValue();
                int nowHour = java.time.LocalTime.now().getHour();
                return nowHour < hour; // 还没到截止时刻 → 暂算达标（待执行）
            }
            return true;
        }
        if (!JobStatus.SUCCESS.name().equals(lastStatus) && !JobStatus.PARTIAL.name().equals(lastStatus)) return false;
        // 检查耗时是否超 max_duration_min
        Object md = r.get("maxDurationMin");
        Object dur = r.get("lastDurationSec");
        if (md != null && dur != null) {
            int maxMin = ((Number) md).intValue();
            int durSec = ((Number) dur).intValue();
            if (durSec > maxMin * 60) return false;
        }
        // 检查是否在期望完成时刻前结束
        Object efh = r.get("expectedFinishHour");
        Object endTime = r.get("lastEndTime");
        if (efh != null && endTime != null) {
            int hour = ((Number) efh).intValue();
            java.time.LocalDateTime et = ((java.sql.Timestamp) endTime).toLocalDateTime();
            if (et.getHour() > hour) return false;
        }
        return true;
    }

    // ============ 通知配置 ============

    @GetMapping("/notification-config")
    @Operation(summary = "获取通知(告警)配置")
    public ApiResponse<com.quant.platform.notification.NotificationConfig> getNotificationConfig() {
        return ApiResponse.success(notificationConfigService.get());
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/notification-config")
    @Operation(summary = "保存通知(告警)配置")
    public ApiResponse<String> saveNotificationConfig(
            @RequestBody com.quant.platform.notification.NotificationConfig config) {
        notificationConfigService.save(config);
        return ApiResponse.success("已保存通知配置");
    }

    @cn.dev33.satoken.annotation.SaCheckPermission(value = {"data:view", "data:edit"}, mode = cn.dev33.satoken.annotation.SaMode.AND)
    @PostMapping("/notification-test")
    @Operation(summary = "发送测试告警，验证渠道配置是否正确")
    public ApiResponse<String> testNotification() {
        if (!notificationConfigService.get().isEnabled()) {
            return ApiResponse.error("通知未启用或渠道未配置，无法发送测试");
        }
        boolean sent = notificationService.testSend(
            "## 测试告警\n\n这是来自量化平台的测试消息，说明告警渠道配置正确。");
        return ApiResponse.success(sent ? "测试告警已发送，请查收" : "发送失败，请检查渠道配置");
    }
}
