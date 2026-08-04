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
public class MoneyFlowService {
    private final AnalysisChMapper analysisChMapper;
    private final StockAnalysisMapper stockAnalysisMapper;
    private final BidAskMapper bidAskMapper;
    private final AnalysisCommonService analysisCommon;
    public MoneyFlowSignal calcMoneyFlowSignal(String code) {
        MoneyFlowSignal signal = new MoneyFlowSignal();

        // 获取最近40个自然日数据（确保≥25个交易日，满足20日均换手率计算）
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 40);
        if (bars == null || bars.isEmpty()) {
            return signal;
        }

        // 最新一日数据
        DailyBarRow latest = bars.getLast();

        // 计算量比（当日成交量 / 5日均量）
        if (latest.getVolume() != null && bars.size() >= 6) {
            long sum5 = 0;
            for (int i = bars.size() - 6; i < bars.size() - 1; i++) {
                if (bars.get(i).getVolume() != null) {
                    sum5 += bars.get(i).getVolume();
                }
            }
            double avg5 = sum5 / 5.0;
            if (avg5 > 0) {
                signal.setVolumeRatio(BigDecimal.valueOf(latest.getVolume() / avg5));
            }
        }
        
        // 计算换手率偏离（当日换手率 - 20日平均换手率）
        // 当日换手率只要有值就设置（不依赖20日均值计算条件）
        if (latest.getTurnoverRate() != null) {
            signal.setTurnoverRate(latest.getTurnoverRate());
        }
        if (latest.getTurnoverRate() != null && bars.size() >= 21) {
            double sum20 = 0;
            int count = 0;
            for (int i = bars.size() - 21; i < bars.size() - 1; i++) {
                if (bars.get(i).getTurnoverRate() != null) {
                    sum20 += bars.get(i).getTurnoverRate().doubleValue();
                    count++;
                }
            }
            if (count > 0) {
                double avg20 = sum20 / count;
                signal.setTurnoverDeviation(
                        latest.getTurnoverRate().subtract(BigDecimal.valueOf(avg20)));
                signal.setTurnoverRate5d(BigDecimal.valueOf(avg20));
            }
        }
        
        // 判断量能状态
        if (signal.getVolumeRatio() != null) {
            double vr = signal.getVolumeRatio().doubleValue();
            if (vr >= 2.0) {
                signal.setVolumeStatus("HIGH");
            } else if (vr >= 1.2) {
                signal.setVolumeStatus("MEDIUM");
            } else {
                signal.setVolumeStatus("LOW");
            }
        }
        
        // 从 CH stock_sentiment_moneyflow 获取主力资金流向
        try {
            java.util.Map<String, Object> mf = analysisChMapper.selectLatestMoneyFlow(code, analysisCommon.getLatestTradeDate());
            if (mf != null) {
                if (mf.get("net_main") != null) {
                    signal.setNetMain((BigDecimal) mf.get("net_main"));
                }
                if (mf.get("net_main_pct") != null) {
                    signal.setNetMainPct((BigDecimal) mf.get("net_main_pct"));
                }
                if (mf.get("net_huge") != null) {
                    signal.setNetHuge((BigDecimal) mf.get("net_huge"));
                }
                if (mf.get("net_big") != null) {
                    signal.setNetBig((BigDecimal) mf.get("net_big"));
                }
                // 判断主力资金状态
                if (signal.getNetMain() != null) {
                    double nm = signal.getNetMain().doubleValue();
                    if (nm > 0) {
                        signal.setMainFlowStatus("INFLOW");
                    } else if (nm < 0) {
                        signal.setMainFlowStatus("OUTFLOW");
                    } else {
                        signal.setMainFlowStatus("NEUTRAL");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取资金流向数据失败: code={}, error={}", code, e.getMessage());
        }

        // 融资余额变化（所有股票，不只是蓝筹）
        try {
            BigDecimal marginChg = stockAnalysisMapper.selectMarginChangePct(code);
            if (marginChg != null) signal.setMarginChgPct(marginChg);
        } catch (Exception e) { log.debug("融资余额变化查询失败: {}", e.getMessage()); }

        // 股东人数变化（所有股票，最新一季度 change_pct）
        try {
            BigDecimal holderChg = stockAnalysisMapper.selectShareholderChangePct(code);
            if (holderChg != null) signal.setShareholderChangePct(holderChg);
        } catch (Exception e) { log.debug("股东人数变化查询失败: {}", e.getMessage()); }

        // 内外盘比（stock_bid_ask 表，每日收盘快照）
        try {
            java.util.Map<String, Object> bidAskData = bidAskMapper.selectLatestBidAsk(code);
            if (bidAskData != null && bidAskData.get("ratio") != null) {
                BigDecimal ratio = new BigDecimal(bidAskData.get("ratio").toString());
                signal.setOuterInnerRatio(ratio);
                // 趋势由 BidAskService 计算，这里直接用 ratio 近似判断
                signal.setBidAskTrend(ratio.doubleValue() > 1.2 ? "BUYER_STRONG"
                        : ratio.doubleValue() > 1.0 ? "BUYER_SLIGHT"
                        : ratio.doubleValue() >= 0.85 ? "BALANCED"
                        : "SELLER_STRONG");
            }
        } catch (Exception e) { log.debug("内外盘比查询失败: {}", e.getMessage()); }

        // 5日累计主力净流入
        try {
            java.util.Map<String, Object> mf5d = stockAnalysisMapper.selectNetMain5d(code);
            if (mf5d != null) {
                if (mf5d.get("netMain5d") != null)
                    signal.setNetMain5d((BigDecimal) mf5d.get("netMain5d"));
                if (mf5d.get("netMainPct5d") != null)
                    signal.setNetMainPct5d((BigDecimal) mf5d.get("netMainPct5d"));
            }
        } catch (Exception e) { log.debug("5日累计资金流向查询失败: {}", e.getMessage()); }

        return signal;
    }

    public Map<String, Object> getMoneyFlowHistory(String code, int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取历史资金流向
            List<Map<String, Object>> mfHistory = analysisChMapper.selectMoneyFlowHistory(code, days, analysisCommon.getLatestTradeDate());
            if (mfHistory == null || mfHistory.isEmpty()) {
                result.put("error", "无资金流向数据");
                return result;
            }

            // 2. 反转（DESC→ASC）并逐日计算评分
            Collections.reverse(mfHistory);
            List<Map<String, Object>> scoredList = new ArrayList<>();
            for (Map<String, Object> row : mfHistory) {
                Map<String, Object> scored = new LinkedHashMap<>(row);
                scored.put("moneyScore", calcDailyMoneyScore(row));
                scoredList.add(scored);
            }

            result.put("history", scoredList);
            result.put("days", scoredList.size());

            // 3. 统计汇总
            // 提取最新数据日期（reverse 后最后一条是最新的）
            Object latestDateObj = scoredList.get(scoredList.size() - 1).get("tradeDate");
            if (latestDateObj != null) {
                result.put("latestDate", latestDateObj.toString());
            }
            double avgNetMain = 0, avgPct = 0, totalScore = 0;
            int inflowDays = 0;
            for (Map<String, Object> row : scoredList) {
                Object nm = row.get("netMain");
                if (nm instanceof BigDecimal) {
                    double v = ((BigDecimal) nm).doubleValue();
                    avgNetMain += v;
                    if (v > 0) inflowDays++;
                }
                Object pct = row.get("netMainPct");
                if (pct instanceof BigDecimal) avgPct += ((BigDecimal) pct).doubleValue();
                Object sc = row.get("moneyScore");
                if (sc instanceof Number) totalScore += ((Number) sc).doubleValue();
            }
            int n = scoredList.size();
            // 转亿为单位，与前端 suffix="亿" 对齐
            result.put("avgNetMain", BigDecimal.valueOf(avgNetMain / n / 100_000_000.0).setScale(2, RoundingMode.HALF_UP));
            result.put("avgNetMainPct", BigDecimal.valueOf(avgPct / n).setScale(2, RoundingMode.HALF_UP));
            result.put("avgMoneyScore", BigDecimal.valueOf(totalScore / n).setScale(1, RoundingMode.HALF_UP));
            result.put("inflowDays", inflowDays);
            result.put("inflowRatio", Math.round((double) inflowDays / n * 10000) / 100.0);

        } catch (Exception e) {
            log.error("资金流向历史查询失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }

        return result;
    }

    public int calcDailyMoneyScore(Map<String, Object> row) {
        int score = 0;

        // 主力净流入（10分）
        Object nm = row.get("netMain");
        if (nm instanceof BigDecimal) {
            double v = ((BigDecimal) nm).doubleValue();
            if (v >= 5e8) score += 10;
            else if (v >= 1e8) score += 7;
            else if (v > 0) score += 5;
            else if (v > -1e8) score += 2;
            else if (v > -3e8) score += 1;
        }

        // 主力净流入占比（8分）
        Object pct = row.get("netMainPct");
        if (pct instanceof BigDecimal) {
            double v = ((BigDecimal) pct).doubleValue();
            if (v >= 10.0) score += 8;
            else if (v >= 5.0) score += 6;
            else if (v > 0) score += 4;
            else if (v > -5.0) score += 2;
            else if (v > -10.0) score += 1;
        }

        // 巨单净流入加分（7分 — 补充量比+换手率缺失的部分）
        Object huge = row.get("netHuge");
        if (huge instanceof BigDecimal) {
            double v = ((BigDecimal) huge).doubleValue();
            if (v >= 1e8) score += 5;
            else if (v >= 3e7) score += 3;
            else if (v > 0) score += 1;
            else if (v < -1e8) score -= 3;
            else if (v < -3e7) score -= 1;
        }

        return Math.max(0, Math.min(25, score));
    }

}
