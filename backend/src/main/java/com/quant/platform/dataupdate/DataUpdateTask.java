package com.quant.platform.dataupdate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据更新任务状态（可序列化推送给前端）
 */
@Data
public class DataUpdateTask {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 任务状态: IDLE, RUNNING, SUCCESS, FAILED, CANCELLED
     */
    private String status = "IDLE";

    /**
     * 当前步骤描述
     */
    private String currentStep;

    /**
     * 进度 0-100
     */
    private int progress;

    /**
     * 已处理股票数
     */
    private int processedStocks;

    /**
     * 总股票数
     */
    private int totalStocks;

    /**
     * 已写入记录数
     */
    private long processedRecords;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 最近一次脚本输出的错误日志（用于自动降级判断，如 Baostock 黑名单）
     */
    @JsonIgnore
    private transient String lastScriptError;

    /**
     * 失败的股票列表（格式: "code|name|date"，用于前端悬浮展示）
     */
    private List<String> failedStocks = new ArrayList<>();

    /**
     * 请求参数快照
     */
    private DataUpdateRequest request;

    /**
     * 字段变更统计（stock_info更新时各字段变更数量），如 {"名称":15, "总市值":3210}
     */
    private Map<String, Integer> fieldChanges = new LinkedHashMap<>();

    /**
     * 内外盘数据统计（日期维度），如 {"2026-06-10":{"total":5239,"success":5239,"failed":0,"rate":"100.0%"},...}
     */
    private Map<String, Map<String, Object>> bidAskStats = new LinkedHashMap<>();

    /**
     * 创建时间
     */
    private LocalDateTime createTime = LocalDateTime.now();

    /**
     * 当前任务关联的 Python 子进程（用于取消时精确杀进程）
     * 每个任务独立持有，解决多任务并发时共享变量被覆盖导致杀错进程的 bug
     */
    @JsonIgnore
    private transient Process process;
    /** Python 子进程 PID */
    @JsonIgnore
    private transient long processPid = -1;

    public boolean isRunning() {
        return "RUNNING".equals(status);
    }

    public boolean isIdle() {
        return "IDLE".equals(status);
    }
}
