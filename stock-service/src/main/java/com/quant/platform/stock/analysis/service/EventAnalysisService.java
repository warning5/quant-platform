package com.quant.platform.stock.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.engine.chan.ChanTheoryCalculator;
import com.quant.platform.factor.engine.chan.ChanTheoryResult;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.stock.analysis.domain.*;
import com.quant.platform.stock.analysis.engine.TradingSignalEngine;
import com.quant.platform.stock.analysis.mapper.AnalysisChMapper;
import com.quant.platform.stock.analysis.mapper.BidAskMapper;
import com.quant.platform.stock.analysis.mapper.NewsMapper;
import com.quant.platform.stock.analysis.mapper.StockAnalysisMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class EventAnalysisService {
    /** CH JDBC template 注入（用于直接 SQL） */
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;
    private final AnalysisCommonService analysisCommon;
    public Map<String, Object> getLimitUpAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        // 1. 近期涨跌停记录（CH stock_sentiment_zt）
        try {
            String ztSql = """
                SELECT trade_date, zt_type, reason, close as closePrice, pct_change as changePct
                FROM stock.stock_sentiment_zt
                WHERE code = ?
                ORDER BY trade_date DESC
                LIMIT 30
                """;
            List<Map<String, Object>> ztList = clickHouseJdbcTemplate.query(ztSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tradeDate", rs.getDate("trade_date").toString());
                    m.put("ztType", rs.getString("zt_type"));
                    m.put("reason", rs.getString("reason"));
                    m.put("closePrice", rs.getBigDecimal("closePrice"));
                    m.put("changePct", rs.getBigDecimal("changePct"));
                    return m;
                }, normalized);
            result.put("records", ztList);

            // 2. 统计汇总
            String statsSql = """
                SELECT
                    countIf(zt_type = 'zt') as limitUpCount,
                    countIf(zt_type = 'dt') as limitDownCount,
                    countIf(zt_type = 'zbgc') as brokenCount,
                    MIN(trade_date) as firstDate,
                    MAX(trade_date) as lastDate
                FROM stock.stock_sentiment_zt
                WHERE code = ?
                """;
            Map<String, Object> stats = clickHouseJdbcTemplate.queryForMap(statsSql, normalized);
            result.put("stats", stats);

            // 3. 涨停原因统计
            String reasonSql = """
                SELECT reason, COUNT(*) as cnt
                FROM stock.stock_sentiment_zt
                WHERE code = ? AND zt_type = 'zt' AND reason != ''
                GROUP BY reason
                ORDER BY cnt DESC
                LIMIT 10
                """;
            List<Map<String, Object>> reasons = clickHouseJdbcTemplate.query(reasonSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reason", rs.getString("reason"));
                    m.put("count", rs.getLong("cnt"));
                    return m;
                }, normalized);
            result.put("topReasons", reasons);

        } catch (Exception e) {
            log.error("涨跌停分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }

        return result;
    }

    public Map<String, Object> getBlockTradeAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = analysisCommon.normalizeCodeForDailyCH(code);

        // 1. 近期大宗交易逐笔记录
        try {
            String btSql = """
                SELECT trade_date, seq_no, price, volume, amount, discount_rate,
                       change_pct, close_price, pct_of_float,
                       buy_branch, sell_branch
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ?
                ORDER BY trade_date DESC, seq_no
                LIMIT 50
                """;
            List<Map<String, Object>> btList = clickHouseJdbcTemplate.query(btSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tradeDate", rs.getDate("trade_date").toString());
                    m.put("seqNo", rs.getInt("seq_no"));
                    m.put("price", rs.getBigDecimal("price"));
                    m.put("volume", rs.getBigDecimal("volume"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("discountRate", rs.getBigDecimal("discount_rate"));
                    m.put("changePct", rs.getBigDecimal("change_pct"));
                    m.put("closePrice", rs.getBigDecimal("close_price"));
                    m.put("pctOfFloat", rs.getBigDecimal("pct_of_float"));
                    m.put("buyBranch", rs.getString("buy_branch"));
                    m.put("sellBranch", rs.getString("sell_branch"));
                    return m;
                }, normalized);
            result.put("records", btList);

            // 2. 统计汇总（从逐笔聚合）
            String statsSql = """
                SELECT
                    COUNT(*) as totalCount,
                    SUM(amount) as totalAmount,
                    AVG(discount_rate) as avgDiscountRate,
                    MIN(trade_date) as firstDate,
                    MAX(trade_date) as lastDate
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ?
                """;
            Map<String, Object> stats = clickHouseJdbcTemplate.queryForMap(statsSql, normalized);
            result.put("stats", stats);

            // 3. 买方营业部统计（从逐笔聚合）
            String buySql = """
                SELECT buy_branch as branch, COUNT(*) as cnt, SUM(amount) as totalAmt
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ? AND buy_branch != ''
                GROUP BY buy_branch
                ORDER BY cnt DESC
                LIMIT 10
                """;
            List<Map<String, Object>> buyBranches = clickHouseJdbcTemplate.query(buySql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("branch", rs.getString("branch"));
                    m.put("count", rs.getLong("cnt"));
                    m.put("totalAmount", rs.getBigDecimal("totalAmt"));
                    return m;
                }, normalized);
            result.put("topBuyBranches", buyBranches);

            // 4. 卖方营业部统计（从逐笔聚合）
            String sellSql = """
                SELECT sell_branch as branch, COUNT(*) as cnt, SUM(amount) as totalAmt
                FROM stock.stock_sentiment_block_trade FINAL
                WHERE code = ? AND sell_branch != ''
                GROUP BY sell_branch
                ORDER BY cnt DESC
                LIMIT 10
                """;
            List<Map<String, Object>> sellBranches = clickHouseJdbcTemplate.query(sellSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("branch", rs.getString("branch"));
                    m.put("count", rs.getLong("cnt"));
                    m.put("totalAmount", rs.getBigDecimal("totalAmt"));
                    return m;
                }, normalized);
            result.put("topSellBranches", sellBranches);

        } catch (Exception e) {
            log.error("大宗交易分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }

        return result;
    }

}
