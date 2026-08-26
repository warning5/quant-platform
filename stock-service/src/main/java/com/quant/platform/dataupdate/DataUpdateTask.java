package com.quant.platform.dataupdate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.quant.platform.common.enums.JobStatus;
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
     * 任务状态: IDLE / RUNNING / SUCCESS / FAILED / CANCELLED
     */
    private JobStatus status = JobStatus.IDLE;

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
     * 最近心跳时间（进度/状态广播时刷新）。超过阈值无心跳视为僵尸任务，由执行服务自动回收。
     */
    @JsonIgnore
    private transient LocalDateTime lastHeartbeat = LocalDateTime.now();

    /**
     * 执行该任务的 worker 线程引用，用于检测线程是否仍存活（判断僵尸）。
     */
    @JsonIgnore
    private transient Thread workerThread;

    /**
     * 是否手动提交（UI 页面提交=true；定时/依赖调度=false）。
     * 手动任务之间互斥；定时任务并发执行，不阻塞手动提交。
     */
    @JsonIgnore
    private transient boolean manual = false;

    /** 刷新心跳时间 */
    public void touchHeartbeat() {
        this.lastHeartbeat = LocalDateTime.now();
    }

    /**
     * 当前任务关联的 Python 子进程（用于取消时精确杀进程）
     * 每个任务独立持有，解决多任务并发时共享变量被覆盖导致杀错进程的 bug
     */
    @JsonIgnore
    private transient Process process;

    /**
     * 执行历史记录 id（task_run_history.id），任务开始时写入，结束时回填状态。
     * 用于把一次执行与历史表行关联。
     */
    private Long historyId = -1L;
    /** Python 子进程 PID */
    @JsonIgnore
    private transient long processPid = -1;

    public boolean isRunning() {
        return JobStatus.RUNNING == status;
    }

    public boolean isIdle() {
        return JobStatus.IDLE == status;
    }
}
