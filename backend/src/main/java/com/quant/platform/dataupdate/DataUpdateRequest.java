package com.quant.platform.dataupdate;

import lombok.Data;

/**
 * 数据更新任务请求参数
 */
@Data
public class DataUpdateRequest {

    /**
     * 更新类型: DAILY (股票日线), INDEX (指数日线), DIVIDEND (分红除权),
     *           FINANCIAL (财务数据), SENTIMENT (情绪数据)
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
     * 强制重新写入（跳过去重预过滤，直接INSERT覆盖）
     * 适用于：日线数据强制覆盖、财务数据强制重新采集
     */
    private boolean force = false;

    /**
     * 情绪数据：采集龙虎榜（默认 true）
     */
    private boolean fetchLhb = true;

    /**
     * 情绪数据：采集融资融券（默认 true）
     */
    private boolean fetchMargin = true;

    /**
     * 情绪数据：采集机构调研（默认 true）
     */
    private boolean fetchSurvey = true;

    /**
     * 情绪数据：采集大宗交易（默认 true）
     */
    private boolean fetchBlockTrade = true;

    /**
     * 情绪数据：采集市场活跃度（默认 true）
     */
    private boolean fetchActivity = true;

    /**
     * 情绪数据：采集涨跌停池（默认 true）
     */
    private boolean fetchZtPool = true;

    /**
     * 情绪数据：采集资金情绪代理（默认 true）
     */
    private boolean fetchMoneyflow = true;

    /**
     * 情绪数据：采集公告（默认 true）
     */
    private boolean fetchNotice = true;

    /**
     * 情绪数据：采集基金持仓（默认 true）
     */
    private boolean fetchFundHolder = true;

    /**
     * 情绪数据：采集股东人数（默认 true）
     */
    private boolean fetchShareholder = true;

    /**
     * 情绪数据：采集个股新闻+事件标签+情感（默认 true）
     */
    private boolean fetchNews = true;

    /**
     * 情绪数据：资金流向数据源
     * AKSHARE = akshare（默认，与原行为一致）
     * NEODATA = NeoData（更快，推荐）
     */
    private String moneyflowSource = "AKSHARE";

    /**
     * 研报数据：单只股票代码（为空则更新全部）
     */
    private String singleCode;
}
