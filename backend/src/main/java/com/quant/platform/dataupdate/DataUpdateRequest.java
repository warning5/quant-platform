package com.quant.platform.dataupdate;

import lombok.Data;

/**
 * 数据更新任务请求参数
 */
@Data
public class DataUpdateRequest {

    /**
     * 更新类型: DAILY (股票日线), INDEX (指数日线), DIVIDEND (分红除权), FINANCIAL (财务数据)
     */
    private String updateType = "DAILY";

    /**
     * 数据源: BAOSTOCK (SH/SZ), TENCENT (BJ), ALL
     */
    private String source = "ALL";

    /**
     * 市场选择: SH, SZ, BJ, ALL
     */
    private String market = "ALL";

    /**
     * 开始日期 (yyyy-MM-dd)，为空则自动检测
     */
    private String startDate;

    /**
     * 结束日期 (yyyy-MM-dd)，为空则用今天
     */
    private String endDate;

    /**
     * 是否只更新日线（不更新 stock_info）
     */
    private boolean dailyOnly = false;

    /**
     * 是否只更新 stock_info
     */
    private boolean infoOnly = false;

    /**
     * 是否断点续传
     */
    private boolean resume = false;

    /**
     * 排除 ST 股票
     */
    private boolean excludeSt = false;

    /**
     * 股票池: ALL, SH300, SZ50, ZZ500, ZZ1000, STAR50
     */
    private String stockPool = "ALL";

    /**
     * 限制数量（测试用）
     */
    private Integer limit;

    /**
     * 批次大小
     */
    private Integer batchSize;

    /**
     * 批次延迟（秒）
     */
    private Double delay;

    /**
     * 财务数据采集起始年份
     */
    private Integer yearStart;

    /**
     * 财务数据采集结束年份
     */
    private Integer yearEnd;

    /**
     * 财务数据：强制重新采集
     */
    private boolean force = false;
}
