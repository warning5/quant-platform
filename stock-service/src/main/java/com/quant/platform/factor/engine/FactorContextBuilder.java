package com.quant.platform.factor.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.mapper.FactorTestReportMapper;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.factor.service.ClickHouseFactorValueService;
import com.quant.platform.financial.entity.StockFinancialIndicator;
import com.quant.platform.financial.mapper.StockFinancialIndicatorMapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import com.quant.platform.common.enums.JobStatus;

/**
 * 因子计算 context 构建器
 * <p>从 {@link FactorComputeEngine} 拆出：为各类 alpha 因子准备横截面上下文
 * （行业动量、指数收益、融资融券、业绩预告、龙虎榜、研报）。行为与拆分前逐字一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorContextBuilder {

    private final MarketDataService marketDataService;
    private final com.quant.platform.stock.mapper.StockInfoMapper stockInfoMapper;

    /** MySQL JdbcTemplate（用于查询融资融券等MySQL数据） */
    @Autowired
    @Qualifier("jdbcTemplate")
    private org.springframework.jdbc.core.JdbcTemplate mysqlJdbcTemplate;
    /**
     * 构建 INDUSTRY_REL_MOM 所需的行业平均动量context
     * 1. 从 stock_info 获取每只股票的行业
     * 2. 从 allBarsData 计算每只股票的20日动量
     * 3. 汇总每个行业的平均动量
     * 返回 Map 包含: "industry_<code>" = 行业名, "industryAvgMom_<industry>" = Double
     */
    public Map<String, Object> buildIndustryMomContext(LocalDate date, List<String> symbols,
                                                        Map<String, List<MarketDailyBar>> allBarsData) {
        try {
            // 加载行业映射（code → industry）
            Map<String, String> industryMap = new HashMap<>();
            List<com.quant.platform.stock.entity.StockInfo> stockInfos =
                    stockInfoMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.stock.entity.StockInfo>()
                                    .in(com.quant.platform.stock.entity.StockInfo::getCode,
                                            symbols.stream().map(this::parseCode).collect(Collectors.toList()))
                                    .select(com.quant.platform.stock.entity.StockInfo::getCode,
                                            com.quant.platform.stock.entity.StockInfo::getIndustry));
            for (var info : stockInfos) {
                if (info.getIndustry() != null && !info.getIndustry().isEmpty()) {
                    industryMap.put(info.getCode(), info.getIndustry());
                }
            }

            // 计算每只股票的20日动量，并按行业汇总
            Map<String, List<Double>> industryMoms = new HashMap<>();
            Map<String, Object> context = new HashMap<>();

            for (String symbol : symbols) {
                String code = parseCode(symbol);
                String industry = industryMap.getOrDefault(code, "未知");
                context.put("industry_" + code, industry);

                List<MarketDailyBar> allBars = allBarsData.getOrDefault(symbol, List.of());
                if (allBars.size() < 21) continue;

                // 找到 date 位置
                int lo = 0, hi = allBars.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (allBars.get(mid).getTradeDate().isAfter(date)) hi = mid;
                    else lo = mid + 1;
                }
                if (lo < 21) continue;

                var latest = allBars.get(lo - 1);
                var past = allBars.get(lo - 21);
                if (past.getClose() == null || past.getClose().compareTo(BigDecimal.ZERO) == 0) continue;
                if (latest.getClose() == null) continue;

                double mom20 = latest.getClose().subtract(past.getClose())
                        .divide(past.getClose(), 8, RoundingMode.HALF_UP).doubleValue();
                industryMoms.computeIfAbsent(industry, k -> new ArrayList<>()).add(mom20);
            }

            // 计算每个行业的平均动量
            for (var entry : industryMoms.entrySet()) {
                List<Double> moms = entry.getValue();
                if (moms.size() >= 3) {
                    double avg = moms.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    context.put("industryAvgMom_" + entry.getKey(), avg);
                }
            }

            return context;
        } catch (Exception e) {
            log.warn("[INDUSTRY_REL_MOM] 构建行业context失败: date={} error={}", date, e.getMessage());
            return Map.of();
        }
    }

    /**
     * BETA_60D: 构建上证指数日收益率序列（从预加载的allBarsData或直接查询）
     * allBarsData中key "INDEX_000001" 存储预加载的指数K线
     */
    public Map<String, Object> buildIndexReturnsContext(LocalDate date,
                                                         Map<String, List<MarketDailyBar>> allBarsData) {
        try {
            List<MarketDailyBar> indexBars = null;
            if (allBarsData != null) {
                indexBars = allBarsData.get("INDEX_000001");
            }
            if (indexBars == null || indexBars.isEmpty()) {
                // 直接查询上证指数K线
                indexBars = marketDataService.getBarsInRange("000001.SH", date.minusDays(400), date);
            }
            if (indexBars == null || indexBars.isEmpty()) return Map.of();

            // 二分查找date位置
            int lo = 0, hi = indexBars.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (indexBars.get(mid).getTradeDate().isAfter(date)) hi = mid;
                else lo = mid + 1;
            }
            if (lo < 2) return Map.of();

            // 计算对数收益率（按日序排列）
            int maxReturns = Math.min(lo - 1, 250);
            double[] returns = new double[maxReturns];
            for (int i = 0; i < maxReturns; i++) {
                var curr = indexBars.get(lo - 1 - (maxReturns - 1 - i));
                var prev = indexBars.get(lo - 1 - (maxReturns - 1 - i) - 1);
                if (prev.getClose() == null || prev.getClose().compareTo(BigDecimal.ZERO) == 0
                        || curr.getClose() == null) {
                    returns[i] = 0;
                } else {
                    returns[i] = Math.log(curr.getClose().doubleValue() / prev.getClose().doubleValue());
                }
            }

            Map<String, Object> context = new HashMap<>();
            context.put("indexReturns", returns);
            return context;
        } catch (Exception e) {
            log.warn("[BETA_60D] 构建指数收益context失败: date={} error={}", date, e.getMessage());
            return Map.of();
        }
    }

    /**
     * MARGIN_BUY_RATIO: 从MySQL查询融资融券数据，计算margin_buy/margin_balance
     */
    public Map<String, Object> buildMarginContext(LocalDate date) {
        try {
            List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                    "SELECT code, margin_buy, margin_balance FROM stock_sentiment_margin_detail WHERE trade_date = ?",
                    date);
            Map<String, Double> ratioMap = new HashMap<>();
            for (var row : rows) {
                String code = (String) row.get("code");
                Object mb = row.get("margin_buy");
                Object mbl = row.get("margin_balance");
                if (code == null || mb == null || mbl == null) continue;
                double buy = Double.parseDouble(mb.toString());
                double balance = Double.parseDouble(mbl.toString());
                if (balance > 0) {
                    ratioMap.put(code, buy / balance);
                }
            }
            if (ratioMap.isEmpty()) return Map.of();
            Map<String, Object> context = new HashMap<>();
            context.put("marginBuyRatioMap", ratioMap);
            return context;
        } catch (Exception e) {
            log.warn("[MARGIN_BUY_RATIO] 构建融资融券context失败: date={} error={}", date, e.getMessage());
            return Map.of();
        }
    }

    // ===== 2026-07-25 新增 alpha 因子 context 构建 =====

    public Map<String, Object> buildEarningsContext(LocalDate date) {
        try {
            List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                    "SELECT code, net_profit_yoy, announce_date FROM stock_earnings_report " +
                            "WHERE announce_date IS NOT NULL AND announce_date <= ? AND net_profit_yoy IS NOT NULL",
                    date.toString());
            Map<String, Double> map = new HashMap<>();
            Map<String, String> latestDate = new HashMap<>();
            for (var row : rows) {
                String code = (String) row.get("code");
                Object np = row.get("net_profit_yoy");
                Object ad = row.get("announce_date");
                if (code == null || np == null || ad == null) continue;
                String adStr = ad.toString();
                Double val;
                try {
                    val = Double.parseDouble(np.toString());
                } catch (Exception e) {
                    continue;
                }
                String prev = latestDate.get(code);
                if (prev == null || adStr.compareTo(prev) > 0) {
                    latestDate.put(code, adStr);
                    map.put(code, val);
                }
            }
            if (map.isEmpty()) return Map.of();
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("earningsSurpriseMap", map);
            return ctx;
        } catch (Exception e) {
            log.warn("[EARNINGS_SURPRISE] 构建context失败: date={} error={}", date, e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> buildLhbContext(LocalDate date) {
        try {
            LocalDate start = date.minusDays(20);
            List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                    "SELECT code, SUM(net_inst_amt) AS s FROM stock_sentiment_lhb_inst " +
                            "WHERE trade_date BETWEEN ? AND ? GROUP BY code",
                    start, date);
            Map<String, Double> map = new HashMap<>();
            for (var row : rows) {
                String code = (String) row.get("code");
                Object s = row.get("s");
                if (code == null || s == null) continue;
                try {
                    map.put(code, Double.parseDouble(s.toString()));
                } catch (Exception e) { log.error("[FactorContextBuilder] 捕获到未处理异常", e); /* skip */ }
            }
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("lhbInstNetMap", map);
            return ctx;
        } catch (Exception e) {
            log.warn("[LHB_INST_NET] 构建context失败: date={} error={}", date, e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> buildResearchContext(LocalDate date) {
        try {
            LocalDate start = date.minusDays(90);
            List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                    "SELECT code, COUNT(*) AS c FROM stock_sentiment_survey " +
                            "WHERE meeting_date BETWEEN ? AND ? GROUP BY code",
                    start, date);
            Map<String, Double> map = new HashMap<>();
            for (var row : rows) {
                String code = (String) row.get("code");
                Object c = row.get("c");
                if (code == null || c == null) continue;
                try {
                    map.put(code, Double.parseDouble(c.toString()));
                } catch (Exception e) { log.error("[FactorContextBuilder] 捕获到未处理异常", e); /* skip */ }
            }
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("instResearchMap", map);
            return ctx;
        } catch (Exception e) {
            log.warn("[INST_RESEARCH] 构建context失败: date={} error={}", date, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从 symbol（如 600619.SH）中提取纯代码（如 600619）
     */
    public String parseCode(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot > 0 ? symbol.substring(0, dot) : symbol;
    }

}
