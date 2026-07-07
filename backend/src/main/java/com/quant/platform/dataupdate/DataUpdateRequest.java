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
     * 调度任务原始 taskKey（与 updateType 不一定相同，例如 SENTIMENT_MF / SENTIMENT_OTHER）
     */
    private String taskKey;

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
     * 情绪数据：采集国债收益率（默认 true）
     */
    private boolean fetchBondYield = true;

    /**
     * 情绪数据：采集申万行业指数（默认 true）
     */
    private boolean fetchShenwanIndex = true;

    /**
     * 情绪数据：采集一致预期（同花顺，默认 true）
     */
    private boolean fetchConsensusEstimate = true;

    /**
     * 情绪数据：采集业绩快报（东方财富，默认 true）
     */
    private boolean fetchEarningsReport = true;

    /**
     * 情绪数据：采集 QVIX 中国恐慌指数（默认 true）
     * 数据来源: scripts/collect_qvix.py → ClickHouse market_sentiment
     */
    private boolean fetchQvix = true;

    /**
     * 情绪数据：资金流向数据源
     * AKSHARE = akshare（默认，与原行为一致）
     * WESTOCK = westock-data（更快，推荐）
     * EM     = 东方财富（实时全市场/历史120天，最快，推荐）
     */
    private String moneyflowSource = "AKSHARE";

    /**
     * 情绪数据：EM（东财）模式子选项
     * realtime = 实时全市场资金流向（--em-moneyflow，默认）
     * hist     = 历史120天全市场资金流向（--em-moneyflow-hist）
     */
    private String emMoneyflowMode = "realtime";

    /**
     * 研报数据：单只股票代码（为空则更新全部）
     */
    private String singleCode;

    /**
     * 情绪数据：股票代码列表（逗号分隔，为空则更新全部）
     */
    private String sentimentCodes;

    /**
     * 获取当前请求中启用的情绪数据子项中文名列表
     * 用于前端定时任务管理页面的描述展示
     */
    public java.util.List<String> getSentimentSubItems() {
        java.util.List<String> items = new java.util.ArrayList<>();
        if (isFetchLhb()) items.add("龙虎榜");
        if (isFetchMargin()) items.add("融资融券");
        if (isFetchSurvey()) items.add("机构调研");
        if (isFetchBlockTrade()) items.add("大宗交易");
        if (isFetchActivity()) items.add("市场活跃度");
        if (isFetchZtPool()) items.add("涨跌停池");
        if (isFetchMoneyflow()) items.add("资金流向");
        if (isFetchNotice()) items.add("公告");
        if (isFetchFundHolder()) items.add("基金持仓");
        if (isFetchShareholder()) items.add("股东人数");
        if (isFetchNews()) items.add("新闻");
        if (isFetchBondYield()) items.add("国债收益率");
        if (isFetchShenwanIndex()) items.add("申万行业指数");
        if (isFetchConsensusEstimate()) items.add("一致预期");
        if (isFetchEarningsReport()) items.add("业绩快报");
        if (isFetchQvix()) items.add("QVIX恐慌指数");
        return items;
    }
}
