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

/**
 * 个股分析服务
 * 整合四维度数据，调用规则引擎生成评分和建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(AnalysisChMapper.class)
public class AnalysisService {
    
    private final AnalysisChMapper analysisChMapper;

    private final StockAnalysisMapper stockAnalysisMapper;

    private final NewsMapper newsMapper;

    private final BidAskMapper bidAskMapper;

    private final TradingSignalEngine tradingSignalEngine;
    
    /**
     * 获取个股分析总览
     */
    public AnalysisOverview getOverview(String code) {
        log.info("开始分析个股: code={}", code);
        
        AnalysisOverview overview = new AnalysisOverview();
        overview.setCode(code);
        
        // 1. 获取股票基本信息
        java.util.Map<String, Object> stockInfo = stockAnalysisMapper.selectStockInfo(code);
        if (stockInfo != null) {
            overview.setName((String) stockInfo.get("name"));
        }

        // 1.05 判断是否大盘蓝筹（总市值 ≥ 1000亿）
        final boolean isBlueChip = stockInfo != null
                && stockInfo.get("total_market_cap") instanceof BigDecimal
                && ((BigDecimal) stockInfo.get("total_market_cap")).compareTo(new BigDecimal("100000000000")) >= 0;

        // 1.1 从 ClickHouse 获取最新价格（CH 有数据，MySQL stock_daily 为空）
        java.util.Map<String, Object> chPrice = analysisChMapper.selectLatestDailyBar(code);
        if (chPrice != null) {
            BigDecimal closePrice = (BigDecimal) chPrice.get("close_price");
            BigDecimal changePercent = (BigDecimal) chPrice.get("change_percent");
            if (closePrice != null) {
                overview.setPrice(closePrice.setScale(2, RoundingMode.HALF_UP).toString());
            }
            if (changePercent != null) {
                overview.setChangePercent(changePercent.setScale(2, RoundingMode.HALF_UP) + "%");
            }
        } else {
            // fallback 到 MySQL（如果 CH 没数据）
            if (stockInfo != null) {
                overview.setPrice(stockInfo.get("close_price") != null ? 
                        new BigDecimal(stockInfo.get("close_price").toString()).setScale(2, RoundingMode.HALF_UP).toString() : "-");
                overview.setChangePercent(stockInfo.get("change_percent") != null ?
                        new BigDecimal(stockInfo.get("change_percent").toString()).setScale(2, RoundingMode.HALF_UP) + "%" : "-");
            }
        }
        
        // 2. 技术面信号（从CH查询缠论因子）
        TechSignal techSignal = analysisChMapper.selectLatestTechSignal(code);
        normalizeChanStrings(techSignal);
        if (techSignal == null) {
            techSignal = new TechSignal();
        }
        
        // 从 stock_daily 计算补充技术指标（均线多头/MACD金叉/RSI）
        List<DailyBarRow> techBars = analysisChMapper.selectRecentDailyBars(code, 120);
        supplementTechIndicators(techSignal, techBars);
        
        // 计算支撑/阻力位（近20日最低价/最高价）
        // techBars 按 trade_date ASC 排序，最近20天取最后20条
        BigDecimal supportPrice = null;
        BigDecimal resistancePrice = null;
        if (techBars != null && !techBars.isEmpty()) {
            int start = Math.max(0, techBars.size() - 20);
            for (int i = start; i < techBars.size(); i++) {
                DailyBarRow bar = techBars.get(i);
                if (bar.getLowPrice() != null) {
                    if (supportPrice == null || bar.getLowPrice().compareTo(supportPrice) < 0) {
                        supportPrice = bar.getLowPrice();
                    }
                }
                if (bar.getHighPrice() != null) {
                    if (resistancePrice == null || bar.getHighPrice().compareTo(resistancePrice) > 0) {
                        resistancePrice = bar.getHighPrice();
                    }
                }
            }
        }

        // 计算ATR（14日平均真实波幅）
        BigDecimal atr = null;
        if (techBars != null && techBars.size() >= 14) {
            List<DailyBarRow> atrBars = techBars.subList(techBars.size() - 14, techBars.size());
            double sum = 0;
            for (int i = 1; i < atrBars.size(); i++) {
                DailyBarRow cur = atrBars.get(i);
                DailyBarRow pre = atrBars.get(i - 1);
                double tr1 = cur.getHighPrice().subtract(cur.getLowPrice()).doubleValue();
                double tr2 = Math.abs(cur.getHighPrice().subtract(pre.getClosePrice()).doubleValue());
                double tr3 = Math.abs(cur.getLowPrice().subtract(pre.getClosePrice()).doubleValue());
                sum += Math.max(tr1, Math.max(tr2, tr3));
            }
            atr = BigDecimal.valueOf(sum / 13.0);
        }

        // 计算目标价/止损价（依赖 chPrice 中的当前价）
        BigDecimal currentPrice = null;
        if (chPrice != null && chPrice.get("close_price") != null) {
            currentPrice = (BigDecimal) chPrice.get("close_price");
        }
        String targetPriceStr = null;
        String stopLossPriceStr = null;
        if (currentPrice != null) {
            // 目标价：阻力位上方5%，若无则当前价×1.10
            BigDecimal target = resistancePrice != null
                    ? resistancePrice.multiply(new BigDecimal("1.05"))
                    : currentPrice.multiply(new BigDecimal("1.10"));
            targetPriceStr = target.setScale(2, RoundingMode.HALF_UP).toString();

            // 止损价：缠论支撑位 与 ATR止损 两者取较近的
            BigDecimal atrStop = atr != null
                    ? currentPrice.subtract(atr.multiply(new BigDecimal("1.5")))
                    : currentPrice.multiply(new BigDecimal("0.90"));
            BigDecimal stopLoss = (supportPrice != null && supportPrice.compareTo(atrStop) > 0)
                    ? atrStop  // ATR止损更近（更保守）
                    : supportPrice;  // 缠论支撑更近
            stopLossPriceStr = stopLoss.setScale(2, RoundingMode.HALF_UP).toString();
        }
        
        // 3. 资金面信号（从CH计算量比/换手率）
        MoneyFlowSignal moneySignal = calcMoneyFlowSignal(code);
        
        // 3.1 量价背离检测（需同时有价格动量 + 5日累计资金流向数据）
        detectVolumePriceDivergence(techSignal, code);
        
        // 4. 事件面信号（从MySQL查询涨跌停等，强势股从CH计算）
        SentimentSignal sentimentSignal = stockAnalysisMapper.selectSentimentSignal(code);
        if (sentimentSignal == null) {
            sentimentSignal = new SentimentSignal();
        }
        // 用 CH 的 stock_daily 计算强势股（MySQL stock_daily 为空）
        BigDecimal ret20d = analysisChMapper.select20dReturn(code);
        if (ret20d != null) {
            sentimentSignal.setIsStrongStock(ret20d.doubleValue() > 30);
        }

        // 4.5 融资余额/股东人数已移至 calcMoneyFlowSignal()，此处不再重复查询

        // 4.6 大盘蓝筹补充事件面数据（龙虎榜机构净买入）
        if (isBlueChip) {
            try {
                BigDecimal lhbNet = stockAnalysisMapper.selectLhbInstitutionNet(code);
                if (lhbNet != null) sentimentSignal.setLhbInstitutionNet(lhbNet);
            } catch (Exception e) { log.debug("龙虎榜机构净买入查询失败: {}", e.getMessage()); }
        }

        // 4.7 事件面补充：机构调研热度 + 基金持仓集中度（所有股票）
        try {
            Integer rrCount = stockAnalysisMapper.selectResearchReportCount90d(code);
            if (rrCount != null) sentimentSignal.setResearchReportCount90d(rrCount);
        } catch (Exception e) { log.debug("研报数量查询失败: {}", e.getMessage()); }
        try {
            BigDecimal fhr = stockAnalysisMapper.selectFundHolderRatio(code);
            if (fhr != null) sentimentSignal.setFundHolderRatio(fhr);
        } catch (Exception e) { log.debug("基金持仓集中度查询失败: {}", e.getMessage()); }

        // 4.8 新闻事件信号（来自 stock_news 表）
        try {
            Map<String, Object> stats30d = newsMapper.selectNewsStats30d(code, 30);
            if (stats30d != null && !stats30d.isEmpty()) {
                int pos30d = ((Number) stats30d.getOrDefault("positive_30d", 0)).intValue();
                int neg30d = ((Number) stats30d.getOrDefault("negative_30d", 0)).intValue();
                int tagged30d = ((Number) stats30d.getOrDefault("tagged_30d", 0)).intValue();
                int total30d = pos30d + neg30d;
                double bias = total30d > 0 ? (double) (pos30d - neg30d) / total30d : 0.0;
                // 新闻评分（满分10分）
                int newsScore = calcNewsScore(pos30d, neg30d, tagged30d, bias);
                sentimentSignal.setNewsPositive30d(pos30d);
                sentimentSignal.setNewsNegative30d(neg30d);
                sentimentSignal.setNewsTagged30d(tagged30d);
                sentimentSignal.setNewsSentimentBias(bias);
                sentimentSignal.setNewsScore(newsScore);
            }
        } catch (Exception e) { log.debug("新闻事件信号查询失败: {}", e.getMessage()); }

        // 5. 基本面信号（从MySQL查 roe/增速等，pe/pb 从CH补充）
        FundamentalSignal fundamentalSignal = stockAnalysisMapper.selectFundamentalSignal(code);
        if (fundamentalSignal == null) {
            fundamentalSignal = new FundamentalSignal();
        }
        // 从 CH 最新日线补充 pe_ttm / pb（MySQL stock_daily 为空）
        if (chPrice != null) {
            if (chPrice.get("pe_ttm") != null) {
                fundamentalSignal.setPeTtm((BigDecimal) chPrice.get("pe_ttm"));
            }
            if (chPrice.get("pb") != null) {
                fundamentalSignal.setPb((BigDecimal) chPrice.get("pb"));
            }
        }

        // 5a. 估值历史分位（从 CH 计算后注入）
        try {
            Map<String, Object> vp = getValuationPercentile(code, 3);
            if (vp.get("pePercentile") != null) {
                fundamentalSignal.setPePercentile((BigDecimal) vp.get("pePercentile"));
            }
            if (vp.get("pbPercentile") != null) {
                fundamentalSignal.setPbPercentile((BigDecimal) vp.get("pbPercentile"));
            }
        } catch (Exception e) {
            log.debug("估值历史分位查询失败: {}", e.getMessage());
        }

        // 5b. 扣非净利润同比增速（跨年比较）
        try {
            BigDecimal deductedYoY = stockAnalysisMapper.selectDeductedNpYoY(code);
            if (deductedYoY != null) {
                fundamentalSignal.setDeductedNpYoY(deductedYoY);
            }
        } catch (Exception e) {
            log.debug("扣非净利润增速查询失败: {}", e.getMessage());
        }

        // 5.1 研报信号（机构观点）
        ResearchSignal researchSignal = stockAnalysisMapper.selectResearchSignal(code);
        if (researchSignal == null) {
            researchSignal = new ResearchSignal();
        }
        // 计算研报评分（0-5分，由最新评级映射），注入到基本面评分
        int researchScore = calcResearchScore(researchSignal.getLatestRating());
        researchSignal.setResearchScore(researchScore);
        fundamentalSignal.setResearchScore(researchScore);
        // 研报覆盖热度（近90天数量）
        fundamentalSignal.setReportCount(researchSignal.getReportCount());
        // 查询最近5条研报明细
        researchSignal.setRecentReports(stockAnalysisMapper.selectRecentResearchReports(code));
        
        // 6. 调用规则引擎评分
        TradingSignal signal = tradingSignalEngine.evaluate(
                code,
                overview.getName() != null ? overview.getName() : code,
                techSignal, moneySignal, sentimentSignal, fundamentalSignal,
                supportPrice, resistancePrice,
                isBlueChip);
        
        // 7. 填充总览
        overview.setTotalScore(signal.getTotalScore());
        overview.setAction(signal.getAction());
        overview.setActionName(signal.getActionName());
        overview.setPosition(signal.getPosition());
        overview.setTiming(signal.getTiming());
        overview.setRisks(signal.getRisks());
        overview.setReversalConditions(signal.getReversalConditions());
        overview.setScoreDetails(signal.getScoreDetails());
        // 资金面数据日期更新到 money detail 的 dataRange
        try {
            java.util.Map<String, Object> mfForDate = analysisChMapper.selectLatestMoneyFlow(code);
            if (mfForDate != null && mfForDate.get("tradeDate") != null) {
                String mfDate = mfForDate.get("tradeDate").toString();
                for (ScoreDetail detail : overview.getScoreDetails()) {
                    if ("money".equals(detail.getDimension())) {
                        detail.setDataRange("数据日期：" + mfDate);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取资金流向日期失败: code={}", code);
        }
        // 回写各维度分数到 Signal 对象
        for (ScoreDetail detail : signal.getScoreDetails()) {
            switch (detail.getDimension()) {
                case "tech" -> techSignal.setTechScore(detail.getScore());
                case "money" -> moneySignal.setMoneyScore(detail.getScore());
                case "sentiment" -> sentimentSignal.setSentimentScore(detail.getScore());
                case "fundamental" -> fundamentalSignal.setFundamentalScore(detail.getScore());
            }
        }
        overview.setTechSignal(techSignal);
        overview.setMoneySignal(moneySignal);
        overview.setSentimentSignal(sentimentSignal);
        overview.setFundamentalSignal(fundamentalSignal);
        overview.setResearchSignal(researchSignal);

        // 7.1 生成综合分析结论
        overview.setConclusion(buildConclusion(overview, signal));

        // 7.2 标记是否大盘蓝筹（供前端切换展示）
        overview.setBlueChip(isBlueChip);

        // 7.3 决策卡片：目标价 / 止损价 / 信心水平
        overview.setTargetPrice(targetPriceStr);
        overview.setStopLossPrice(stopLossPriceStr);
        overview.setConfidenceLevel(calcConfidenceLevel(fundamentalSignal, researchSignal));

        log.info("个股分析完成: code={}, totalScore={}, action={}",
                code, overview.getTotalScore(), overview.getAction());

        return overview;
    }

    /**
     * 根据四维度评分生成文字结论（含关键判断依据）
     */
    private String buildConclusion(AnalysisOverview o, TradingSignal signal) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(o.getName()).append("(").append(o.getCode()).append(")】");

        // 操作建议
        sb.append("综合评分").append(signal.getTotalScore()).append("分，");
        sb.append("建议【").append(signal.getActionName()).append("】");
        sb.append("，建议仓位").append(signal.getPosition()).append("%。");

        // 四维度：输出关键判断依据，而非笼统的"强/弱"
        if (o.getScoreDetails() != null) {
            for (var d : o.getScoreDetails()) {
                String reason = buildDimensionReason(d, o);
                sb.append(d.getDimensionName()).append("：").append(reason).append("；");
            }
        }

        // 风险提示
        if (o.getRisks() != null && !o.getRisks().isEmpty()) {
            sb.append("注意：").append(o.getRisks());
        }
        return sb.toString();
    }

    /**
     * 生成单维度的一句话判断依据（只输出有信息的指标，英文状态转中文）
     */
    private String buildDimensionReason(ScoreDetail d, AnalysisOverview o) {
        double pct = d.getMaxScore() > 0 ? (double) d.getScore() / d.getMaxScore() : 0;
        String level;
        if (pct >= 0.8) level = "强";
        else if (pct >= 0.6) level = "较强";
        else if (pct >= 0.4) level = "一般";
        else if (pct >= 0.2) level = "较弱";
        else level = "弱";

        // 取各维度最有价值的判断依据（优先取有分数的item，最多2个）
        List<String> parts = new ArrayList<>();
        if (d.getItems() != null) {
            for (var item : d.getItems()) {
                if (item.getScore() > 0 && item.getValue() != null
                        && !item.getValue().equals("-") && !item.getValue().equals("暂无数据")) {
                    parts.add(mapChinese(item.getLabel()) + mapChinese(item.getValue()));
                }
                if (parts.size() >= 2) break;
            }
            // 如果没找到有分的，取前2个非空的
            if (parts.isEmpty()) {
                for (var item : d.getItems()) {
                    if (item.getValue() != null && !item.getValue().equals("-") && !item.getValue().equals("暂无数据")) {
                        parts.add(mapChinese(item.getLabel()) + mapChinese(item.getValue()));
                    }
                    if (parts.size() >= 2) break;
                }
            }
        }
        return level + (parts.isEmpty() ? "" : "（" + String.join("，", parts) + "）");
    }

    /**
     * 将英文状态码映射为中文，普通文本原样返回
     */
    private String mapChinese(String v) {
        if (v == null) return "";
        return switch (v) {
            case "BUY" -> "买入";
            case "SELL" -> "卖出";
            case "HOLD" -> "持有";
            case "BULLISH" -> "牛市";
            case "SIDEWAYS" -> "横盘";
            case "BEARISH" -> "熊市";
            case "是" -> "是";
            case "否" -> "否";
            default -> v;
        };
    }

    /**
     * 研报评级 → 评分（0-5分）
     * 买入=5，增持=3，中性=1，减持/卖出=0
     */
    private int calcResearchScore(String rating) {
        if (rating == null || rating.isEmpty()) return 0;
        return switch (rating) {
            case "买入" -> 5;
            case "增持", "推荐", "强烈推荐" -> 3;
            case "中性", "持有" -> 1;
            default -> 0;
        };
    }
    
    /**
     * 计算资金面信号（量比、换手率偏离 + 主力资金流向）
     */
    private MoneyFlowSignal calcMoneyFlowSignal(String code) {
        MoneyFlowSignal signal = new MoneyFlowSignal();

        // 获取最近40个自然日数据（确保≥25个交易日，满足20日均换手率计算）
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 40);
        if (bars == null || bars.isEmpty()) {
            return signal;
        }

        // 最新一日数据
        DailyBarRow latest = bars.get(bars.size() - 1);

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
            java.util.Map<String, Object> mf = analysisChMapper.selectLatestMoneyFlow(code);
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
    
    /**
     * 从日线数据计算补充技术指标（均线多头/MACD金叉/RSI/BOLL/MACD动能/收益率/量价背离）
     * 优先用 factor_value 的值，缺失时从 stock_daily 行情计算
     */
    private void supplementTechIndicators(TechSignal tech, List<DailyBarRow> bars) {
        if (bars == null || bars.size() < 30) return;

        int n = bars.size();
        double[] closes = new double[n];
        double[] highs  = new double[n];
        double[] lows   = new double[n];
        double[] volumes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i]  = bars.get(i).getClosePrice().doubleValue();
            highs[i]   = bars.get(i).getHighPrice().doubleValue();
            lows[i]    = bars.get(i).getLowPrice().doubleValue();
            volumes[i] = bars.get(i).getVolume() != null
                         ? bars.get(i).getVolume().doubleValue() : 0;
        }

        // ── SMA5 单独值（用于明细展示）────────────────────────────
        if (n >= 5) {
            tech.setMa5Value(BigDecimal.valueOf(avg(closes, n - 5, n)).setScale(3, RoundingMode.HALF_UP));
        }

        // ── 近60日局部高点 / 低点 ─────────────────────────────────
        if (n >= 60) {
            double maxHigh = Double.MIN_VALUE;
            double minLow  = Double.MAX_VALUE;
            for (int i = n - 60; i < n; i++) {
                if (highs[i] > maxHigh) maxHigh = highs[i];
                if (lows[i]  < minLow)  minLow  = lows[i];
            }
            double lastClose = closes[n - 1];
            tech.setNearHigh60(BigDecimal.valueOf(maxHigh).setScale(3, RoundingMode.HALF_UP));
            tech.setNearLow60(BigDecimal.valueOf(minLow).setScale(3, RoundingMode.HALF_UP));
            if (maxHigh > lastClose) {
                tech.setNearHighPct(BigDecimal.valueOf((maxHigh - lastClose) / lastClose * 100)
                        .setScale(2, RoundingMode.HALF_UP));
            }
            if (lastClose > minLow) {
                tech.setNearLowPct(BigDecimal.valueOf((lastClose - minLow) / minLow * 100)
                        .setScale(2, RoundingMode.HALF_UP));
            }
        }

        // ── 量比：近5日均量 / 近20日均量 ─────────────────────────
        if (n >= 20) {
            double sum5 = 0, sum20 = 0;
            for (int i = n - 5;  i < n;      i++) sum5  += volumes[i];
            for (int i = n - 20; i < n;      i++) sum20 += volumes[i];
            double vr = sum20 > 0 ? (sum5 / 5.0) / (sum20 / 20.0) : 0;
            tech.setVolumeRatio(BigDecimal.valueOf(vr).setScale(2, RoundingMode.HALF_UP));
        }

        // ── SAR 抛物线转向（Parabolic SAR）───────────────────────
        // 参数：初始AF=0.02，步长0.02，上限0.2
        if (n >= 3) {
            final double AF_INIT = 0.02;
            final double AF_STEP = 0.02;
            final double AF_MAX  = 0.20;

            // 判断第一段趋势：取最近3天，若最后一根收盘 > 第一根收盘 → 初始多头
            boolean isBull = closes[n - 1] >= closes[n - 3];
            double sar = isBull ? lows[n - 3] : highs[n - 3];
            double af  = AF_INIT;
            double ep  = isBull ? highs[n - 3] : lows[n - 3]; // 极点价

            for (int i = n - 2; i < n; i++) {
                // 预测SAR
                double predSar = sar + af * (ep - sar);
                boolean currentBull = closes[i] > predSar;
                if (currentBull != isBull) {
                    // 翻转：开新空或多
                    isBull = currentBull;
                    af = AF_INIT;
                    ep = currentBull ? highs[i] : lows[i];
                    sar = currentBull ? lows[i] : highs[i];
                } else {
                    // 更新EP
                    if ((isBull && highs[i] > ep) || (!isBull && lows[i] < ep)) {
                        ep = isBull ? highs[i] : lows[i];
                        af = Math.min(AF_MAX, af + AF_STEP);
                    }
                    // SAR = 前SAR + AF*(EP - 前SAR)
                    sar = sar + af * (ep - sar);
                    // 多头SAR不能高于前一根最低；空头SAR不能低于前一根最高
                    if (isBull && i > n - 2) {
                        sar = Math.min(sar, lows[i - 1]);
                    }
                    if (!isBull && i > n - 2) {
                        sar = Math.max(sar, highs[i - 1]);
                    }
                }
            }

            double lastClose2 = closes[n - 1];
            double lastSar    = sar;
            tech.setSar(BigDecimal.valueOf(lastSar).setScale(3, RoundingMode.HALF_UP));
            tech.setSarAbovePrice(lastSar < lastClose2);  // SAR在价格下方=多头

            // 翻多/翻空：重新运行 SAR 算法到倒数第二天，比较其趋势方向 vs 当前趋势方向
            if (n >= 3) {
                // 从头算到倒数第二天（不包含今天）
                boolean isBullPrev = closes[n - 2] >= closes[n - 3];
                double sarP = isBullPrev ? lows[n - 3] : highs[n - 3];
                double afP  = AF_INIT;
                double epP  = isBullPrev ? highs[n - 3] : lows[n - 3];
                for (int i = n - 2; i < n - 1; i++) {
                    double predS = sarP + afP * (epP - sarP);
                    boolean curB = closes[i] > predS;
                    if (curB != isBullPrev) {
                        isBullPrev = curB; afP = AF_INIT;
                        epP = curB ? highs[i] : lows[i];
                        sarP = curB ? lows[i] : highs[i];
                    } else {
                        if ((isBullPrev && highs[i] > epP) || (!isBullPrev && lows[i] < epP)) {
                            epP = isBullPrev ? highs[i] : lows[i];
                            afP = Math.min(AF_MAX, afP + AF_STEP);
                        }
                        sarP = sarP + afP * (epP - sarP);
                    }
                }
                // 前一根SAR是否在收盘价下方
                boolean prevSarBelow = sarP < closes[n - 2];
                boolean currSarBelow = lastSar < lastClose2;
                tech.setSarTurnBullish(!prevSarBelow && currSarBelow);  // 前上方 → 当前下方 = 翻多
                tech.setSarTurnBearish(prevSarBelow && !currSarBelow);  // 前下方 → 当前上方 = 翻空
            }
        }
        
        // --- 均线多头：MA5 > MA10 > MA20 > MA60 ---
        if (tech.getMaBullish() == null && n >= 60) {
            double ma5 = avg(closes, n - 5, n);
            double ma10 = avg(closes, n - 10, n);
            double ma20 = avg(closes, n - 20, n);
            double ma60 = avg(closes, n - 60, n);
            tech.setMaBullish(ma5 > ma10 && ma10 > ma20 && ma20 > ma60);
        }
        
        // --- MACD金叉 + 柱值 + 动能检测 ---
        if (n >= 35) {
            double[] ema12 = calcEma(closes, 12);
            double[] ema26 = calcEma(closes, 26);
            double[] dif = new double[n];
            for (int i = 0; i < n; i++) dif[i] = ema12[i] - ema26[i];
            double[] dea = calcEma(dif, 9);
            
            // 金叉
            if (tech.getMacdGolden() == null) {
                boolean golden = dif[n - 1] > dea[n - 1] && dif[n - 2] <= dea[n - 2];
                tech.setMacdGolden(golden);
            }
            
            // MACD柱值（DIF - DEA）
            double hist = dif[n - 1] - dea[n - 1];
            double histPrev = n >= 2 ? dif[n - 2] - dea[n - 2] : hist;
            tech.setMacdHistogram(BigDecimal.valueOf(hist).setScale(4, RoundingMode.HALF_UP));
            tech.setMacdHistogramPrev(BigDecimal.valueOf(histPrev).setScale(4, RoundingMode.HALF_UP));
        }
        
        // --- RSI14 ---
        if (tech.getRsi() == null && n >= 15) {
            double rsi = calcRsi(closes, 14);
            tech.setRsi(BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP));
        }
        
        // --- BOLL轨道（20日周期，±2倍标准差）---
        if (n >= 25) {
            double ma20 = avg(closes, n - 20, n);
            double variance = 0;
            for (int i = n - 20; i < n; i++) {
                variance += (closes[i] - ma20) * (closes[i] - ma20);
            }
            double stdDev = Math.sqrt(variance / 20.0);
            double bollUpper = ma20 + 2 * stdDev;
            double bollMid = ma20;
            double bollLower = ma20 - 2 * stdDev;
            double latestClose = closes[n - 1];
            // 位置：0=下轨，1=中轨，2=上轨；>1突破上轨，<0跌破下轨
            double bandWidth = bollUpper - bollLower;
            double bollPos = bandWidth > 0 ? (latestClose - bollLower) / bandWidth : 0.5;
            
            tech.setBollUpper(BigDecimal.valueOf(bollUpper).setScale(3, RoundingMode.HALF_UP));
            tech.setBollMid(BigDecimal.valueOf(bollMid).setScale(3, RoundingMode.HALF_UP));
            tech.setBollLower(BigDecimal.valueOf(bollLower).setScale(3, RoundingMode.HALF_UP));
            tech.setBollPosition(BigDecimal.valueOf(bollPos).setScale(4, RoundingMode.HALF_UP));
            // 布林带带宽：(<5%为极度收敛，即将突破)
            double bandwidth = bollMid > 0 ? (bandWidth / bollMid) * 100 : 0;
            tech.setBollBandwidth(BigDecimal.valueOf(bandwidth).setScale(2, RoundingMode.HALF_UP));
        }
        
        // --- 收益率（用于量价背离检测）---
        if (n >= 6) {
            double ret5 = (closes[n - 1] - closes[n - 6]) / closes[n - 6];
            tech.setRet5d(BigDecimal.valueOf(ret5).setScale(4, RoundingMode.HALF_UP));
        }
        if (n >= 21) {
            double ret20 = (closes[n - 1] - closes[n - 21]) / closes[n - 21];
            tech.setRet20d(BigDecimal.valueOf(ret20).setScale(4, RoundingMode.HALF_UP));
        }

        // --- MACD死叉 + 零轴判断 ---
        if (n >= 35) {
            double[] ema12 = calcEma(closes, 12);
            double[] ema26 = calcEma(closes, 26);
            double[] dif = new double[n];
            for (int i = 0; i < n; i++) dif[i] = ema12[i] - ema26[i];
            double[] dea = calcEma(dif, 9);

            // MACD零轴：DIF > 0 表示在零轴上方
            boolean aboveZero = dif[n - 1] > 0;
            tech.setMacdAboveZero(aboveZero);

            // MACD死叉：DIF下穿DEA（前一根DIF>=DEA，当前DIF<DEA）
            if (n >= 2) {
                boolean dead = dif[n - 1] < dea[n - 1] && dif[n - 2] >= dea[n - 2];
                tech.setMacdDeadCross(dead);
            }
        }

        // --- 均线空头排列（MA5 < MA10 < MA20 < MA60）---
        if (tech.getMaBearish() == null && n >= 60) {
            double ma5 = avg(closes, n - 5, n);
            double ma10 = avg(closes, n - 10, n);
            double ma20 = avg(closes, n - 20, n);
            double ma60 = avg(closes, n - 60, n);
            tech.setMaBearish(ma5 < ma10 && ma10 < ma20 && ma20 < ma60);
        }

        // --- KDJ（9,3,3）---
        if (n >= 10) {
            // 计算 RSV（9日周期）
            double[] rsv = new double[n];
            for (int i = 9; i < n; i++) {
                double maxHigh = Double.MIN_VALUE, minLow = Double.MAX_VALUE;
                for (int j = i - 8; j <= i; j++) {
                    if (highs[j] > maxHigh) maxHigh = highs[j];
                    if (lows[j]  < minLow)  minLow  = lows[j];
                }
                double range = maxHigh - minLow;
                rsv[i] = range > 0 ? (closes[i] - minLow) / range * 100 : 50;
            }
            // 单次 EMA 推进，记录最后一天和倒数第二天的 K/D/J
            double kPrev = 50, dPrev = 50;
            double kCurr = 50, dCurr = 50;
            for (int i = 9; i < n; i++) {
                kCurr = 2.0 / 3 * kPrev + 1.0 / 3 * rsv[i];
                dCurr = 2.0 / 3 * dPrev + 1.0 / 3 * kCurr;
                if (i == n - 1) {
                    double jCurr = 3 * kCurr - 2 * dCurr;
                    tech.setKdjK(BigDecimal.valueOf(kCurr).setScale(2, RoundingMode.HALF_UP));
                    tech.setKdjD(BigDecimal.valueOf(dCurr).setScale(2, RoundingMode.HALF_UP));
                    tech.setKdjJ(BigDecimal.valueOf(jCurr).setScale(2, RoundingMode.HALF_UP));
                    // 金叉：K从下方上穿D；死叉：K从上方下穿D
                    tech.setKdjGoldenCross(kPrev <= dPrev && kCurr > dCurr);
                    tech.setKdjDeadCross(kPrev >= dPrev && kCurr < dCurr);
                }
                kPrev = kCurr;
                dPrev = dCurr;
            }
        }

        // --- WR(14) 威廉指标 ---
        if (n >= 14) {
            double maxHigh14 = Double.MIN_VALUE, minLow14 = Double.MAX_VALUE;
            for (int i = n - 14; i < n; i++) {
                if (highs[i] > maxHigh14) maxHigh14 = highs[i];
                if (lows[i] < minLow14) minLow14 = lows[i];
            }
            double range14 = maxHigh14 - minLow14;
            double wrVal = range14 > 0 ? (maxHigh14 - closes[n - 1]) / range14 * -100 : -50;
            tech.setWr(BigDecimal.valueOf(wrVal).setScale(2, RoundingMode.HALF_UP));
        }

        // --- DMI / ADX（14日）---
        if (n >= 30) {
            int period = 14;
            // 初始化 TR、+DM、-DM
            double[] trArr = new double[n - period];
            double[] plusDMArr = new double[n - period];
            double[] minusDMArr = new double[n - period];
            for (int i = period; i < n; i++) {
                double high = highs[i], low = lows[i], prevHigh = highs[i - 1], prevLow = lows[i - 1];
                double tr = Math.max(high - low, Math.max(Math.abs(high - closes[i - 1]), Math.abs(low - closes[i - 1])));
                double plusDM = Math.max(high - prevHigh, 0) > Math.max(prevLow - low, 0)
                        ? Math.max(high - prevHigh, 0) : 0;
                double minusDM = Math.max(prevLow - low, 0) > Math.max(high - prevHigh, 0)
                        ? Math.max(prevLow - low, 0) : 0;
                trArr[i - period] = tr;
                plusDMArr[i - period] = plusDM;
                minusDMArr[i - period] = minusDM;
            }

            // Wilder平滑
            int m = trArr.length;
            double smoothedTR = 0, smoothedPlusDM = 0, smoothedMinusDM = 0;
            for (int i = 0; i < period; i++) {
                smoothedTR += trArr[i];
                smoothedPlusDM += plusDMArr[i];
                smoothedMinusDM += minusDMArr[i];
            }
            double[] plusDI = new double[m];
            double[] minusDI = new double[m];
            double[] dxArr = new double[m];
            plusDI[period - 1] = smoothedTR > 0 ? smoothedPlusDM / smoothedTR * 100 : 0;
            minusDI[period - 1] = smoothedTR > 0 ? smoothedMinusDM / smoothedTR * 100 : 0;
            dxArr[period - 1] = (plusDI[period - 1] + minusDI[period - 1]) > 0
                    ? Math.abs(plusDI[period - 1] - minusDI[period - 1]) / (plusDI[period - 1] + minusDI[period - 1]) * 100 : 0;

            for (int i = period; i < m; i++) {
                smoothedTR = smoothedTR - smoothedTR / period + trArr[i];
                smoothedPlusDM = smoothedPlusDM - smoothedPlusDM / period + plusDMArr[i];
                smoothedMinusDM = smoothedMinusDM - smoothedMinusDM / period + minusDMArr[i];
                plusDI[i] = smoothedTR > 0 ? smoothedPlusDM / smoothedTR * 100 : 0;
                minusDI[i] = smoothedTR > 0 ? smoothedMinusDM / smoothedTR * 100 : 0;
                dxArr[i] = (plusDI[i] + minusDI[i]) > 0
                        ? Math.abs(plusDI[i] - minusDI[i]) / (plusDI[i] + minusDI[i]) * 100 : 0;
            }

            // ADX：DX的 Wilder 平滑
            int lastIdx = m - 1;
            double adx = 0;
            if (m > period * 2) {
                double smoothedDX = 0;
                for (int i = period; i < period + period; i++) smoothedDX += dxArr[i];
                smoothedDX /= period;
                for (int i = period + period; i < m; i++) {
                    smoothedDX = smoothedDX - smoothedDX / period + dxArr[i];
                }
                adx = smoothedDX;
                // ADX 理论范围 [0, 100]，Wilder 平滑不会超限；防御性处理 NaN/Inf/异常值
                // 注意：ADX=0 是合法值（震荡市），只过滤负数或超限值
                if (Double.isNaN(adx) || Double.isInfinite(adx) || adx < 0 || adx > 100) {
                    adx = 0; // 无效则置 0，前端不显示 ADX
                }
            }

            if (lastIdx >= period - 1) {
                tech.setDmiPlusDI(BigDecimal.valueOf(plusDI[lastIdx]).setScale(2, RoundingMode.HALF_UP));
                tech.setDmiMinusDI(BigDecimal.valueOf(minusDI[lastIdx]).setScale(2, RoundingMode.HALF_UP));
                if (adx >= 0) {
                    tech.setDmiAdx(BigDecimal.valueOf(adx).setScale(2, RoundingMode.HALF_UP));
                }
            }
        }

        // --- MA间距发散/收敛检测（满分3分）---
        // 核心：MA5 与 MA20 的间距变化反映趋势加速/衰减
        // 逻辑：短均线远离长均线（间距扩大）= 发散 = 趋势加速 = 正分
        //       短均线靠拢长均线（间距收窄）= 收敛 = 动能衰减 = 负分
        if (n >= 22) {
            // 当前 MA5 / MA20
            double ma5Curr   = avg(closes, n - 5,  n);
            double ma20Curr  = avg(closes, n - 20, n);
            // 前日 MA5 / MA20（各往前推1天）
            double ma5Prev   = avg(closes, n - 6,  n - 1);
            double ma20Prev  = avg(closes, n - 21, n - 1);

            double spacing      = ma20Curr  > 0 ? (ma5Curr  - ma20Curr)  / ma20Curr  * 100 : 0;
            double spacingPrev  = ma20Prev  > 0 ? (ma5Prev  - ma20Prev)  / ma20Prev  * 100 : 0;
            double spacingDelta = spacing - spacingPrev;  // >0=发散，<0=收敛

            tech.setMaSpacing(BigDecimal.valueOf(spacing).setScale(3, RoundingMode.HALF_UP));
            tech.setMaSpacingPrev(BigDecimal.valueOf(spacingPrev).setScale(3, RoundingMode.HALF_UP));

            // 阈值：0.5% 为显著变化（消除噪音）
            if (spacingDelta > 0.5) {
                tech.setMaDivergence("发散");  // 趋势加速
            } else if (spacingDelta < -0.5) {
                tech.setMaDivergence("收敛");  // 动能衰减
            } else {
                tech.setMaDivergence("稳定");  // 无明显变化
            }
        }
    }
    
    /**
     * 量价背离检测：价格涨但主力跑 = 高危出货；价格跌但主力进 = 低估蓄力
     * 使用5日累计主力净流入（与报告"近5日主力净流出"口径一致），阈值放宽
     * 高位背离条件：5日涨幅 >= 3% 且 5日累计主力净流入 < -5000万
     * 低位背离条件：5日跌幅 <= -3% 且 5日累计主力净流入 > 5000万
     */
    private void detectVolumePriceDivergence(TechSignal tech, String code) {
        if (tech == null) return;
        BigDecimal ret5d = tech.getRet5d();
        if (ret5d == null) return;

        double ret5 = ret5d.doubleValue();

        // 从 CH 查近5日主力净流入累计值
        double netMain5d = 0;
        List<Map<String, Object>> mfList = analysisChMapper.selectMoneyFlowHistory(code, 5);
        if (mfList != null && !mfList.isEmpty()) {
            for (Map<String, Object> mf : mfList) {
                if (mf.get("netMain") != null) {
                    netMain5d += ((Number) mf.get("netMain")).doubleValue();
                }
            }
            log.info("[量价背离] code={} ret5={}% netMain5d={}(元) mfListSize={}", code,
                    String.format("%.2f", ret5 * 100),
                    String.format("%.0f", netMain5d),
                    mfList.size());
        } else {
            log.info("[量价背离] code={} 资金流向数据为空，ret5={}%", code, String.format("%.2f", ret5 * 100));
        }

        // 近5日净流出 >= 5000万 且 5日涨幅 >= 3% = 高位背离
        if (ret5 >= 0.03 && netMain5d <= -50_000_000) {
            tech.setPriceVolumeDivergence(true);
            tech.setDivergenceType("HIGH_PRICE_MAIN_OUTFLOW");
            log.info("[量价背离] code={} 触发高位背离: ret5={}% >= 3% 且 netMain5d={}(元) <= -5000万", code, String.format("%.2f", ret5 * 100), String.format("%.0f", netMain5d));
        }
        // 近5日净流入 >= 5000万 且 5日跌幅 >= 3% = 低位背离
        else if (ret5 <= -0.03 && netMain5d >= 50_000_000) {
            tech.setPriceVolumeDivergence(true);
            tech.setDivergenceType("LOW_PRICE_MAIN_INFLOW");
            log.info("[量价背离] code={} 触发低位背离: ret5={}% <= -3% 且 netMain5d={}(元) >= 5000万", code, String.format("%.2f", ret5 * 100), String.format("%.0f", netMain5d));
        } else {
            tech.setPriceVolumeDivergence(false);
            tech.setDivergenceType(null);
            log.info("[量价背离] code={} 未触发: ret5={}%（需>=3%或<=-3%），netMain5d={}(元)（需<=-5000万或>=5000万）", code, String.format("%.2f", ret5 * 100), String.format("%.0f", netMain5d));
        }
        // 将原始计算数据存入 TechSignal，供前端 tooltip 显示不满足原因
        tech.setNetMain5d(BigDecimal.valueOf(netMain5d));
    }
    
    private double avg(double[] arr, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += arr[i];
        return sum / (to - from);
    }
    
    private double[] calcEma(double[] data, int period) {
        double[] ema = new double[data.length];
        double k = 2.0 / (period + 1);
        ema[0] = data[0];
        for (int i = 1; i < data.length; i++) {
            ema[i] = data[i] * k + ema[i - 1] * (1 - k);
        }
        return ema;
    }
    
    private double calcRsi(double[] closes, int period) {
        int n = closes.length;
        double gain = 0, loss = 0;
        for (int i = n - period; i < n; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) gain += change;
            else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    /**
     * 将 Chan 因子数据库数字值转换为评分引擎期望的字符串
     * DB 存储格式（FactorService screen 配置一致）：
     *   CHAN_PEN_DIR:  1=上升笔, -1=下降笔
     *   CHAN_TREND:    1=上涨, 0=盘整, -1=下跌
     *   CHAN_BUY_SELL: >0=买(1/2/3买), <0=卖(-1/-2/-3卖)
     *   CHAN_HUB_POS:  0.0~1.0 浮点，按阈值转为 UPPER/MIDDLE/LOWER
     */
    private void normalizeChanStrings(TechSignal tech) {
        if (tech == null) return;

        // CHAN_PEN_DIR: 前端期望数字 1/-1，但 BeanPropertyRowMapper 返回 "1.0"/"-1.0"
        // 清理为整数格式 "1"/"-1"
        if (tech.getPenDir() != null) {
            try {
                int v = (int) Math.round(Double.parseDouble(tech.getPenDir()));
                tech.setPenDir(String.valueOf(v));
            } catch (NumberFormatException ignored) {}
        }

        // CHAN_TREND: 1 → "BULLISH", 0 → "SIDEWAYS", -1 → "BEARISH"
        if (tech.getTrend() != null) {
            try {
                int v = (int) Math.round(Double.parseDouble(tech.getTrend()));
                tech.setTrend(v == 1 ? "BULLISH" : v == 0 ? "SIDEWAYS" : v == -1 ? "BEARISH" : tech.getTrend());
            } catch (NumberFormatException ignored) {}
        }

        // CHAN_BUY_SELL: >0 → "BUY", <0 → "SELL", 0 → "HOLD"
        if (tech.getChanSignal() != null) {
            try {
                double v = Double.parseDouble(tech.getChanSignal());
                tech.setChanSignal(v > 0 ? "BUY" : v < 0 ? "SELL" : "HOLD");
            } catch (NumberFormatException ignored) {}
        }

        // CHAN_HUB_POS: 浮点 0.0~1.0 → LOWER/MIDDLE/UPPER
        if (tech.getHubPos() != null) {
            try {
                double v = Double.parseDouble(tech.getHubPos());
                tech.setHubPos(v < 0.33 ? "LOWER" : v < 0.66 ? "MIDDLE" : "UPPER");
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * 获取评分规则说明
     */
    public List<TradingSignalEngine.ScoreRule> getScoreRules() {
        return tradingSignalEngine.getScoreRules();
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取个股研报完整分析（独立 Tab 用）
     * 包含：评级趋势、EPS一致预期、覆盖强度、近期研报列表
     */
    public Map<String, Object> getResearchAnalysis(String code) {
        log.info("获取研报分析: code={}", code);
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 近期研报列表（含 EPS 预测）
        List<Map<String, Object>> reports = stockAnalysisMapper.selectRecentReportsWithEps(code, 10);
        result.put("recentReports", reports != null ? reports : List.of());

        // 2. 评级趋势（近6个月，按月+评级分组）
        List<Map<String, Object>> rawRatingTrend = stockAnalysisMapper.selectRatingTrend(code);
        result.put("ratingTrend", pivotRatingTrend(rawRatingTrend));

        // 3. 研报数量月度趋势
        List<Map<String, Object>> reportTrend = stockAnalysisMapper.selectReportCountByMonth(code);
        result.put("reportTrend", reportTrend);

        // 4. 覆盖强度
        Map<String, Object> coverage = new LinkedHashMap<>();
        List<Map<String, Object>> institutions = stockAnalysisMapper.selectCoverageInstitutions(code);
        coverage.put("institutionCount", institutions.size());
        coverage.put("institutions", institutions);
        String firstDate = stockAnalysisMapper.selectFirstCoverageDate(code);
        coverage.put("firstCoverageDate", firstDate != null ? firstDate : "");
        // 近期总研报数
        int recent90d = 0;
        if (reports != null) recent90d = reports.size();
        // 从 reportTrend 汇总近6个月总数
        int total6m = 0;
        if (reportTrend != null) {
            for (Map<String, Object> m : reportTrend) {
                Object cnt = m.get("cnt");
                if (cnt instanceof Number) total6m += ((Number) cnt).intValue();
            }
        }
        coverage.put("reportCount6m", total6m);
        coverage.put("reportCount90d", recent90d);
        result.put("coverage", coverage);

        // 5. EPS 一致预期（从 eps_forecast JSON 解析聚合）
        result.put("epsForecast", calcEpsConsensus(reports));

        // 6. 最新评级 + 买入占比
        ResearchSignal signal = stockAnalysisMapper.selectResearchSignal(code);
        Map<String, Object> ratingSummary = new LinkedHashMap<>();
        ratingSummary.put("latestRating", signal != null ? signal.getLatestRating() : null);
        ratingSummary.put("reportCount", signal != null ? signal.getReportCount() : 0);
        // 计算买入+增持占比
        int buyCount = 0;
        int ratedCount = 0;
        if (reports != null) {
            for (Map<String, Object> r : reports) {
                Object rat = r.get("rating");
                if (rat != null && !"".equals(rat.toString())) {
                    ratedCount++;
                    String rt = rat.toString();
                    if ("买入".equals(rt) || "增持".equals(rt)) buyCount++;
                }
            }
        }
        double buyRatio = ratedCount > 0 ? Math.round((double) buyCount / ratedCount * 10000) / 100.0 : 0;
        ratingSummary.put("buyRatio", buyRatio);
        ratingSummary.put("ratedCount", ratedCount);
        // 评级共识描述
        if (buyRatio >= 80) ratingSummary.put("consensusDesc", "强烈看多");
        else if (buyRatio >= 60) ratingSummary.put("consensusDesc", "偏多");
        else if (buyRatio >= 40) ratingSummary.put("consensusDesc", "中性偏多");
        else if (buyRatio >= 20) ratingSummary.put("consensusDesc", "中性偏空");
        else ratingSummary.put("consensusDesc", "偏空");
        result.put("ratingSummary", ratingSummary);

        return result;
    }

    /**
     * 将原始评级趋势数据按月份 pivot 为图表友好的格式
     * 原始: [{month:"2025-11", rating:"买入", cnt:3}, {month:"2025-11", rating:"增持", cnt:2}, ...]
     * 输出: [{month:"2025-11", 买入:3, 增持:2, ...}]
     */
    private List<Map<String, Object>> pivotRatingTrend(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            String month = row.get("month") != null ? row.get("month").toString() : "";
            String rating = row.get("rating") != null ? row.get("rating").toString() : "无";
            Number cnt = (Number) row.get("cnt");

            Map<String, Object> monthData = byMonth.computeIfAbsent(month, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("month", k);
                return m;
            });
            monthData.merge(rating, cnt != null ? cnt.intValue() : 0,
                    (oldVal, newVal) -> ((Number) oldVal).intValue() + ((Number) newVal).intValue());
        }
        return new ArrayList<>(byMonth.values());
    }

    /**
     * 计算 EPS 一致预期
     * 解析多份研报的 eps_forecast JSON，按年度聚合取平均
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> calcEpsConsensus(List<Map<String, Object>> reports) {
        Map<String, Object> consensus = new LinkedHashMap<>();
        if (reports == null || reports.isEmpty()) return consensus;

        // year -> [eps_values] / [pe_values]
        Map<String, List<Double>> epsByYear = new LinkedHashMap<>();
        Map<String, List<Double>> peByYear = new LinkedHashMap<>();

        for (Map<String, Object> r : reports) {
            Object epsRaw = r.get("epsForecast");
            if (epsRaw == null || epsRaw.toString().isBlank()) continue;
            try {
                Map<String, Object> forecast = objectMapper.readValue(epsRaw.toString(),
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : forecast.entrySet()) {
                    String year = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof Map) {
                        Map<String, Object> detail = (Map<String, Object>) val;
                        Object epsObj = detail.get("eps");
                        Object peObj = detail.get("pe");
                        if (epsObj instanceof Number) {
                            epsByYear.computeIfAbsent(year, k -> new ArrayList<>())
                                    .add(((Number) epsObj).doubleValue());
                        }
                        if (peObj instanceof Number) {
                            peByYear.computeIfAbsent(year, k -> new ArrayList<>())
                                    .add(((Number) peObj).doubleValue());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 取各年份平均
        for (String year : epsByYear.keySet()) {
            List<Double> vals = epsByYear.get(year);
            double avgEps = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            avgEps = BigDecimal.valueOf(avgEps).setScale(2, RoundingMode.HALF_UP).doubleValue();
            Map<String, Object> yearData = new LinkedHashMap<>();
            yearData.put("year", year);
            yearData.put("avgEps", avgEps);
            yearData.put("sourceCount", vals.size());

            List<Double> peVals = peByYear.get(year);
            if (peVals != null && !peVals.isEmpty()) {
                double avgPe = peVals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                avgPe = BigDecimal.valueOf(avgPe).setScale(1, RoundingMode.HALF_UP).doubleValue();
                yearData.put("avgPe", avgPe);
            }
            consensus.put(year, yearData);
        }

        return consensus;
    }

    /**
     * 股票联想搜索（按代码或名称模糊匹配）
     * @param keyword 搜索关键词（代码或名称片段）
     * @return 匹配的股票列表（code, name, market）
     */
    public List<Map<String, Object>> searchStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return stockAnalysisMapper.searchStocks(keyword.trim());
    }

    /**
     * 同业对比：获取同行业股票的 PE/PB/ROE/涨跌幅/评分
     * @param code 股票代码
     * @return 行业名称 + 同业列表（含当前股高亮）
     */
    public Map<String, Object> getPeerComparison(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 获取该股票的行业
        Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
        String industry = myInfo != null ? (String) myInfo.get("industry") : null;
        if (industry == null || industry.isBlank()) {
            result.put("industry", "未知");
            result.put("peers", Collections.emptyList());
            return result;
        }
        result.put("industry", industry);

        // 2. 获取同行业所有股票的基本信息（PE/PB/市值）
        List<Map<String, Object>> peers = stockAnalysisMapper.selectIndustryPeers(industry);
        if (peers == null || peers.isEmpty()) {
            result.put("peers", Collections.emptyList());
            return result;
        }

        // 3. 补充 CH 最新价格/涨跌幅数据
        for (Map<String, Object> peer : peers) {
            String peerCode = (String) peer.get("code");
            try {
                Map<String, Object> chBar = analysisChMapper.selectLatestDailyBar(peerCode);
                if (chBar != null) {
                    peer.put("changePercent", chBar.get("change_percent"));
                    peer.put("closePrice", chBar.get("close_price"));
                }
            } catch (Exception e) {
                log.debug("获取同业价格失败: {}", peerCode);
            }
        }

        // 4. 排序：按总市值降序（大公司在前）
        peers.sort((a, b) -> {
            BigDecimal ma = a.get("total_market_cap") instanceof BigDecimal ?
                    (BigDecimal) a.get("total_market_cap") : BigDecimal.ZERO;
            BigDecimal mb = b.get("total_market_cap") instanceof BigDecimal ?
                    (BigDecimal) b.get("total_market_cap") : BigDecimal.ZERO;
            return mb.compareTo(ma);
        });

        // 5. 标记当前股票
        result.put("peers", peers);
        result.put("currentCode", code);
        return result;
    }

    /**
     * 估值历史分位：计算当前 PE/PB 在 N 年中的百分位排名
     * @param code 股票代码
     * @param years 回溯年数（默认3）
     * @return pePercentile/pbPercentile/peCurrent/pbCurrent/peHistoryCount/pbHistoryCount
     */
    public Map<String, Object> getValuationPercentile(String code, int years) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = normalizeCodeForDailyCH(code);

        try {
            // 从 CH 查询历史 PE/PB 序列（排除0值和null）
            List<BigDecimal> peHistory = new ArrayList<>();
            List<BigDecimal> pbHistory = new ArrayList<>();
            BigDecimal currentPe = null;
            BigDecimal currentPb = null;

            String sql = """
                SELECT pe_ttm, pb FROM stock.stock_daily FINAL
                WHERE code = ?
                  AND trade_date >= subtractYears(today(), ?)
                  AND pe_ttm > 0 AND pe_ttm < 10000
                  AND pb > 0 AND pb < 10000
                ORDER BY trade_date ASC
                """;
            List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("pe_ttm", rs.getBigDecimal("pe_ttm"));
                    m.put("pb", rs.getBigDecimal("pb"));
                    return m;
                }, normalized, years);

            for (Map<String, Object> row : rows) {
                BigDecimal pe = (BigDecimal) row.get("pe_ttm");
                BigDecimal pb = (BigDecimal) row.get("pb");
                if (pe != null) peHistory.add(pe);
                if (pb != null) pbHistory.add(pb);
            }

            if (!peHistory.isEmpty()) currentPe = peHistory.getLast();
            if (!pbHistory.isEmpty()) currentPb = pbHistory.getLast();

            // 计算百分位：低于当前值的占比
            double pePct = calcPercentile(peHistory, currentPe);
            double pbPct = calcPercentile(pbHistory, currentPb);

            result.put("pePercentile", Math.round(pePct * 10.0) / 10.0);
            result.put("pbPercentile", Math.round(pbPct * 10.0) / 10.0);
            result.put("peCurrent", currentPe);
            result.put("pbCurrent", currentPb);
            result.put("peHistoryCount", peHistory.size());
            result.put("pbHistoryCount", pbHistory.size());
            result.put("years", years);

            // 分位描述
            result.put("peDesc", percentileDesc(pePct));
            result.put("pbDesc", percentileDesc(pbPct));
        } catch (Exception e) {
            log.error("查询估值分位失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /** 将前端短代码转为 CH stock_daily 无后缀格式 */
    private String normalizeCodeForDailyCH(String code) {
        if (code == null) return null;
        String c = code.trim();
        if (c.contains(".")) return c.split("\\.")[0];
        return c;
    }

    private double calcPercentile(List<BigDecimal> history, BigDecimal current) {
        if (history == null || history.isEmpty() || current == null) return 0;
        int belowOrEqual = 0;
        for (BigDecimal val : history) {
            if (val != null && val.compareTo(current) <= 0) belowOrEqual++;
        }
        return (double) belowOrEqual / history.size() * 100.0;
    }

    private static String percentileDesc(double pct) {
        if (pct >= 90) return "极高估（历史90%以上）";
        if (pct >= 75) return "偏高（历史75%~90%）";
        if (pct >= 50) return "中等偏上（50%~75%）";
        if (pct >= 25) return "偏低（25%~50%）";
        if (pct >= 10) return "很低估（10%~25%）";
        return "极低估（历史10%以下）";
    }

    /** CH JDBC template 注入（用于直接 SQL） */
    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    /**
     * 行业涨跌排行 + 概念板块排行
     * 注意：MySQL stock_daily 为空表，涨跌幅从 ClickHouse 获取
     * stock_concept 表仅在 MySQL 存在，概念排行需要先从 MySQL 取股票列表再聚合 CH 行情
     */
    public Map<String, Object> getSectorRanking() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 行业排行（纯 CH 查询：stock_info + stock_daily 都在 CH）
        List<Map<String, Object>> industryList = Collections.emptyList();
        String latestTradeDate = null;
        try {
            // 先查最新交易日（避免子查询）
            latestTradeDate = clickHouseJdbcTemplate.queryForObject(
                "SELECT MAX(trade_date) FROM stock.stock_daily FINAL", String.class);
        } catch (Exception e) {
            log.error("获取最新交易日失败: {}", e.getMessage());
        }

        if (latestTradeDate != null) {
            try {
                String industrySql = String.format("""
                    SELECT
                        si.industry,
                        COUNT(*) as stockCount,
                        AVG(sd.change_percent) as avgChangePct,
                        median(sd.pe_ttm) as medianPe,
                        median(sd.pb) as medianPb
                    FROM stock_info si
                      INNER JOIN (
                        SELECT code, change_percent, pe_ttm, pb FROM stock.stock_daily FINAL
                        WHERE trade_date = '%s'
                      ) sd ON sd.code = si.code
                    WHERE si.industry IS NOT NULL AND si.industry != ''
                      AND si.market NOT IN ('BJ','北交所')
                    GROUP BY si.industry
                    ORDER BY avgChangePct DESC
                    LIMIT 30
                    """, latestTradeDate);
            industryList = clickHouseJdbcTemplate.query(industrySql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("industry", rs.getString("industry"));
                    m.put("stockCount", rs.getLong("stockCount"));
                    Object avgChg = rs.getObject("avgChangePct");
                    m.put("avgChangePct", avgChg instanceof Number ?
                            BigDecimal.valueOf(((Number) avgChg).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    Object medPe = rs.getObject("medianPe");
                    m.put("medianPe", medPe instanceof Number ?
                            BigDecimal.valueOf(((Number) medPe).doubleValue()).setScale(1, RoundingMode.HALF_UP) : null);
                    Object medPb = rs.getObject("medianPb");
                    m.put("medianPb", medPb instanceof Number ?
                            BigDecimal.valueOf(((Number) medPb).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    return m;
                });
            } catch (Exception e) {
                log.error("查询行业排行失败: error={}", e.getMessage(), e);
            }
        }

        // 在结果中返回最新交易日期（stock_concept 仅在 MySQL，但可一次 JOIN CH 聚合）
        List<Map<String, Object>> conceptList = Collections.emptyList();
        try {
            // 先获取最新交易日期
            String maxDateSql = "SELECT MAX(trade_date) as maxDate FROM stock.stock_daily FINAL";
            String maxDate = clickHouseJdbcTemplate.queryForObject(maxDateSql, String.class);

            if (maxDate != null) {
                // 从 MySQL 取概念-股票映射
                List<Map<String, Object>> concepts = stockAnalysisMapper.selectAllConcepts();
                if (concepts != null && !concepts.isEmpty()) {
                    // 收集所有涉及股票代码，构建 code→conceptName 的反向映射
                    Map<String, String> codeToConcept = new LinkedHashMap<>();
                    for (Map<String, Object> c : concepts) {
                        String cname = (String) c.get("conceptName");
                        String ccode = (String) c.get("code");
                        codeToConcept.put(ccode, cname);
                    }

                    // 一次查出所有涉及股票的涨跌幅（限最新日期）
                    Set<String> allCodes = codeToConcept.keySet();
                    List<Map<String, Object>> conceptListRaw = new ArrayList<>();

                    // CH IN 子句有长度限制，分批查询（每批500）
                    List<String> codeList = new ArrayList<>(allCodes);
                    Map<String, Map<String, Object>> codeChgMap = new HashMap<>();
                    for (int i = 0; i < codeList.size(); i += 500) {
                        List<String> batch = codeList.subList(i, Math.min(i + 500, codeList.size()));
                        String inClause = String.join("','", batch);
                        String batchSql = String.format("""
                            SELECT code, change_percent as chg, pe_ttm, pb
                            FROM stock.stock_daily FINAL
                            WHERE code IN ('%s') AND trade_date = '%s'
                            """, inClause, maxDate);
                        List<Map<String, Object>> rows = clickHouseJdbcTemplate.query(batchSql,
                            (rs, rowNum) -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("code", rs.getString("code"));
                                m.put("chg", rs.getObject("chg"));
                                m.put("pe", rs.getObject("pe_ttm"));
                                m.put("pb", rs.getObject("pb"));
                                return m;
                            });
                        for (Map<String, Object> r : rows) {
                            codeChgMap.put((String) r.get("code"), r);
                        }
                    }

                    // 按概念聚合
                    Map<String, List<Double>> conceptChgs = new LinkedHashMap<>();
                    Map<String, List<Double>> conceptPes = new LinkedHashMap<>();
                    Map<String, List<Double>> conceptPbs = new LinkedHashMap<>();
                    Map<String, Integer> conceptCounts = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : codeToConcept.entrySet()) {
                        String code = e.getKey();
                        String cname = e.getValue();
                        Map<String, Object> chgData = codeChgMap.get(code);
                        if (chgData != null) {
                            conceptChgs.computeIfAbsent(cname, k -> new ArrayList<>());
                            conceptPes.computeIfAbsent(cname, k -> new ArrayList<>());
                            conceptPbs.computeIfAbsent(cname, k -> new ArrayList<>());
                            Object chg = chgData.get("chg");
                            if (chg instanceof Number) conceptChgs.get(cname).add(((Number) chg).doubleValue());
                            Object pe = chgData.get("pe");
                            if (pe instanceof Number && ((Number) pe).doubleValue() > 0) conceptPes.get(cname).add(((Number) pe).doubleValue());
                            Object pb = chgData.get("pb");
                            if (pb instanceof Number && ((Number) pb).doubleValue() > 0) conceptPbs.get(cname).add(((Number) pb).doubleValue());
                            conceptCounts.merge(cname, 1, Integer::sum);
                        }
                    }

                    // 构建结果
                    conceptList = new ArrayList<>();
                    for (String cname : conceptChgs.keySet()) {
                        List<Double> chgs = conceptChgs.get(cname);
                        if (chgs.isEmpty()) continue;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("conceptName", cname);
                        row.put("stockCount", conceptCounts.getOrDefault(cname, chgs.size()));
                        double avgChg = chgs.stream().mapToDouble(v -> v).average().orElse(0);
                        row.put("avgChangePct", BigDecimal.valueOf(avgChg).setScale(2, RoundingMode.HALF_UP));
                        // 简易中位数（Java排序取中间值）
                        List<Double> pes = conceptPes.getOrDefault(cname, Collections.emptyList());
                        List<Double> pbs = conceptPbs.getOrDefault(cname, Collections.emptyList());
                        row.put("medianPe", pes.isEmpty() ? null : BigDecimal.valueOf(median(pes)).setScale(1, RoundingMode.HALF_UP));
                        row.put("medianPb", pbs.isEmpty() ? null : BigDecimal.valueOf(median(pbs)).setScale(2, RoundingMode.HALF_UP));
                        conceptList.add(row);
                    }
                    conceptList.sort((a, b) -> {
                        BigDecimal ma = a.get("avgChangePct") instanceof BigDecimal ? (BigDecimal) a.get("avgChangePct") : BigDecimal.ZERO;
                        BigDecimal mb = b.get("avgChangePct") instanceof BigDecimal ? (BigDecimal) b.get("avgChangePct") : BigDecimal.ZERO;
                        return mb.compareTo(ma);
                    });
                }
            }
        } catch (Exception e) {
            log.error("查询概念排行失败: error={}", e.getMessage(), e);
        }

        result.put("industry", industryList != null ? industryList : Collections.emptyList());
        result.put("concept", conceptList);
        result.put("tradeDate", latestTradeDate);
        return result;
    }

    /**
     * 行业内个股排名 — CH查询 stock_info + stock_daily
     */
    public List<Map<String, Object>> getIndustryStocks(String industry, String sortBy, String sortOrder) {
        // 白名单排序字段
        Set<String> allowedSort = Set.of("changePercent", "peTtm", "pb", "totalMarketCap", "turnoverRate");
        if (!allowedSort.contains(sortBy)) sortBy = "changePercent";
        String order = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";

        // CH字段映射
        String chSortCol = switch (sortBy) {
            case "changePercent" -> "sd.change_percent";
            case "peTtm" -> "sd.pe_ttm";
            case "pb" -> "sd.pb";
            case "totalMarketCap" -> "si.total_market_cap";
            case "turnoverRate" -> "sd.turnover_rate";
            default -> "sd.change_percent";
        };

        String sql = String.format("""
            SELECT si.code, si.name, si.industry,
                   si.total_market_cap as totalMarketCap,
                   sd.close_price as closePrice,
                   sd.change_percent as changePercent,
                   sd.pe_ttm as peTtm,
                   sd.pb as pb,
                   sd.turnover_rate as turnoverRate
            FROM stock_info si
              INNER JOIN (
                SELECT code, close_price, change_percent, pe_ttm, pb, turnover_rate
                FROM stock.stock_daily FINAL
                WHERE trade_date = (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
              ) sd ON sd.code = si.code
            WHERE si.industry = ?
              AND si.market NOT IN ('BJ','北交所')
            ORDER BY %s %s
            LIMIT 100
            """, chSortCol, order);

        return clickHouseJdbcTemplate.query(sql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString("code"));
                m.put("name", rs.getString("name"));
                m.put("industry", rs.getString("industry"));
                m.put("totalMarketCap", rs.getBigDecimal("totalMarketCap"));
                m.put("closePrice", rs.getBigDecimal("closePrice"));
                m.put("changePercent", rs.getBigDecimal("changePercent"));
                m.put("peTtm", rs.getBigDecimal("peTtm"));
                m.put("pb", rs.getBigDecimal("pb"));
                m.put("turnoverRate", rs.getBigDecimal("turnoverRate"));
                return m;
            }, industry);
    }

    /**
     * 行业关联分析：Beta暴露 + 行业联动
     * 计算个股与所属行业的 Beta、相关系数、行业涨跌联动
     */
    public Map<String, Object> getIndustryCorrelation(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = normalizeCodeForDailyCH(code);

        // 1. 获取该股票所属行业
        Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
        String industry = myInfo != null ? (String) myInfo.get("industry") : null;
        if (industry == null || industry.isBlank()) {
            result.put("error", "未找到行业信息");
            return result;
        }
        result.put("industry", industry);

        // 2. 获取近60日个股收益率序列
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 65);
        if (bars == null || bars.size() < 20) {
            result.put("error", "个股数据不足（需至少20日）");
            return result;
        }

        // 计算日收益率
        List<Double> stockReturns = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            if (bars.get(i - 1).getClosePrice() != null && bars.get(i).getClosePrice() != null) {
                double prev = bars.get(i - 1).getClosePrice().doubleValue();
                double curr = bars.get(i).getClosePrice().doubleValue();
                if (prev > 0) stockReturns.add((curr - prev) / prev);
            }
        }
        if (stockReturns.size() < 20) {
            result.put("error", "收益率数据不足");
            return result;
        }

        // 3. 获取同行业所有股票近60日收益率 → 计算行业等权平均收益率
        try {
            String industryReturnSql = """
                SELECT trade_date, AVG(change_percent) / 100 as avg_ret
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 70)
                GROUP BY trade_date
                ORDER BY trade_date
                """;
            List<Map<String, Object>> indRows = clickHouseJdbcTemplate.query(industryReturnSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("tradeDate", rs.getDate("trade_date").toLocalDate());
                    m.put("avgRet", rs.getBigDecimal("avg_ret"));
                    return m;
                }, industry);

            // 构建 industry return map
            Map<java.time.LocalDate, Double> indRetMap = new LinkedHashMap<>();
            for (Map<String, Object> r : indRows) {
                java.time.LocalDate td = (java.time.LocalDate) r.get("tradeDate");
                BigDecimal avgRet = (BigDecimal) r.get("avgRet");
                if (avgRet != null) indRetMap.put(td, avgRet.doubleValue());
            }

            // 对齐日期序列
            List<Double> alignedStock = new ArrayList<>();
            List<Double> alignedInd = new ArrayList<>();
            List<java.time.LocalDate> alignedDates = new ArrayList<>();  // 同步保存日期
            for (int i = 1; i < bars.size(); i++) {
                java.time.LocalDate td = bars.get(i).getTradeDate();
                if (td != null && indRetMap.containsKey(td) && i - 1 < stockReturns.size()) {
                    alignedStock.add(stockReturns.get(i - 1));
                    alignedInd.add(indRetMap.get(td));
                    alignedDates.add(td);  // 保存对应日期
                }
            }

            if (alignedStock.size() >= 20) {
                // 计算 Beta = Cov(stock, industry) / Var(industry)
                double[] betaCorr = calcBetaAndCorrelation(alignedStock, alignedInd);
                double beta = betaCorr[0];
                double corr = betaCorr[1];

                result.put("beta", Math.round(beta * 100.0) / 100.0);
                result.put("correlation", Math.round(corr * 100.0) / 100.0);
                result.put("sampleDays", alignedStock.size());

                // Beta 解读
                String betaDesc;
                if (beta > 1.5) betaDesc = "高Beta（>1.5），弹性大，涨跌幅放大";
                else if (beta > 1.0) betaDesc = "中高Beta，波动略大于行业";
                else if (beta > 0.7) betaDesc = "中Beta，与行业基本同步";
                else if (beta > 0.3) betaDesc = "低Beta，波动小于行业";
                else betaDesc = "极低Beta，独立行情特征";
                result.put("betaDesc", betaDesc);

                // 相关系数解读
                String corrDesc;
                if (corr > 0.7) corrDesc = "高度联动，与行业同涨同跌";
                else if (corr > 0.4) corrDesc = "中度联动，受行业影响较大";
                else if (corr > 0.2) corrDesc = "弱联动，有一定独立性";
                else corrDesc = "低联动，走势独立于行业";
                result.put("corrDesc", corrDesc);

                // 4. 近5日联动分析
                List<Map<String, Object>> recentAlign = new ArrayList<>();
                int n = alignedStock.size();
                for (int i = Math.max(0, n - 5); i < n; i++) {
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("dayIndex", i - Math.max(0, n - 5) + 1);
                    // 使用对齐时保存的日期（避免 bars 索引错位）
                    if (i < alignedDates.size()) {
                        day.put("tradeDate", alignedDates.get(i).toString());
                    }
                    day.put("stockRet", Math.round(alignedStock.get(i) * 10000.0) / 100.0);
                    day.put("industryRet", Math.round(alignedInd.get(i) * 10000.0) / 100.0);
                    // 超额收益
                    day.put("excessRet", Math.round((alignedStock.get(i) - alignedInd.get(i)) * 10000.0) / 100.0);
                    recentAlign.add(day);
                }
                result.put("recentAlignment", recentAlign);
            } else {
                result.put("error", "对齐数据不足（需至少20日）");
            }
        } catch (Exception e) {
            log.error("行业关联分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        // 5. 同行业近期涨跌分布
        try {
            String distSql = """
                SELECT
                    COUNT(*) as total,
                    countIf(change_percent > 0) as upCount,
                    countIf(change_percent < 0) as downCount,
                    countIf(change_percent = 0) as flatCount,
                    AVG(change_percent) as avgChange
                FROM stock_info si
                  INNER JOIN (
                    SELECT code, change_percent FROM stock.stock_daily FINAL
                    WHERE trade_date = (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
                  ) sd ON sd.code = si.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                """;
            Map<String, Object> dist = clickHouseJdbcTemplate.queryForMap(distSql, industry);
            result.put("industryDist", dist);
        } catch (Exception e) {
            log.debug("行业分布查询失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 计算 Beta 和相关系数
     */
    private double[] calcBetaAndCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        double meanX = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanY = y.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double covXY = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            covXY += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        covXY /= (n - 1);
        varX /= (n - 1);
        varY /= (n - 1);

        double beta = varY > 0 ? covXY / varY : 0;
        double corr = (varX > 0 && varY > 0) ? covXY / Math.sqrt(varX * varY) : 0;

        return new double[]{beta, corr};
    }

    /**
     * 涨跌停分析：历史涨停/跌停记录 + 涨停原因 + 炸板统计
     */
    public Map<String, Object> getLimitUpAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = normalizeCodeForDailyCH(code);

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

    /**
     * 大宗交易分析：逐笔明细 + 统计汇总 + 买卖营业部
     */
    public Map<String, Object> getBlockTradeAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = normalizeCodeForDailyCH(code);

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

    /**
     * 概念板块内个股排名 — MySQL取代码列表 + CH取行情
     */
    public List<Map<String, Object>> getConceptStocks(String conceptName, String sortBy, String sortOrder) {
        Set<String> allowedSort = Set.of("changePercent", "peTtm", "pb", "totalMarketCap", "turnoverRate");
        if (!allowedSort.contains(sortBy)) sortBy = "changePercent";
        String order = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";

        // 1. MySQL获取概念成分股代码列表
        List<Map<String, Object>> conceptRows = stockAnalysisMapper.selectAllConcepts();
        Set<String> codes = new TreeSet<>();
        for (Map<String, Object> r : conceptRows) {
            String cname = (String) r.get("conceptName");
            if (conceptName.equals(cname)) {
                codes.add((String) r.get("code"));
            }
        }
        if (codes.isEmpty()) return Collections.emptyList();

        // 2. CH批量查询行情
        String inClause = String.join("','", codes);
        String chSortCol = switch (sortBy) {
            case "changePercent" -> "sd.change_percent";
            case "peTtm" -> "sd.pe_ttm";
            case "pb" -> "sd.pb";
            case "totalMarketCap" -> "si.total_market_cap";
            case "turnoverRate" -> "sd.turnover_rate";
            default -> "sd.change_percent";
        };

        String sql = String.format("""
            SELECT si.code, si.name,
                   si.total_market_cap as totalMarketCap,
                   sd.close_price as closePrice,
                   sd.change_percent as changePercent,
                   sd.pe_ttm as peTtm,
                   sd.pb as pb,
                   sd.turnover_rate as turnoverRate
            FROM stock_info si
              INNER JOIN (
                SELECT code, close_price, change_percent, pe_ttm, pb, turnover_rate
                FROM stock.stock_daily FINAL
                WHERE trade_date = (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
              ) sd ON sd.code = si.code
            WHERE si.code IN ('%s')
            ORDER BY %s %s
            LIMIT 100
            """, inClause, chSortCol, order);

        return clickHouseJdbcTemplate.query(sql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString("code"));
                m.put("name", rs.getString("name"));
                m.put("totalMarketCap", rs.getBigDecimal("totalMarketCap"));
                m.put("closePrice", rs.getBigDecimal("closePrice"));
                m.put("changePercent", rs.getBigDecimal("changePercent"));
                m.put("peTtm", rs.getBigDecimal("peTtm"));
                m.put("pb", rs.getBigDecimal("pb"));
                m.put("turnoverRate", rs.getBigDecimal("turnoverRate"));
                return m;
            });
    }

    // ─── 热门行业专题 ──────────────────────────────────────────────────

    /** 热门板块定义（名称 + 图标色系） */
    private static final List<String> HOT_CONCEPTS = List.of(
        "人工智能", "半导体概念", "国产芯片", "算力/AI",
        "储能概念", "光伏概念", "新能源车", "锂电池概念", "新能源",
        "机器人概念", "人形机器人",
        "军工", "低空经济", "医疗器械概念", "创新药",
        "消费电子概念", "信创", "数字经济", "氢能源", "充电桩"
    );

    /**
     * 热门行业专题概览
     * 返回 Map，包含 tradeDate 和 sectors
     */
    public Map<String, Object> getHotSectors() {
        List<Map<String, Object>> results = new ArrayList<>();

        // 从 MySQL 获取概念→股票映射
        List<Map<String, Object>> concepts = stockAnalysisMapper.selectAllConcepts();
        Map<String, Set<String>> conceptCodes = new LinkedHashMap<>();
        for (Map<String, Object> c : concepts) {
            String cname = (String) c.get("conceptName");
            String ccode = (String) c.get("code");
            conceptCodes.computeIfAbsent(cname, k -> new TreeSet<>()).add(ccode);
        }

        // 最新交易日期
        String latestDate = getLatestTradeDate();

        for (String conceptName : HOT_CONCEPTS) {
            Set<String> codes = conceptCodes.get(conceptName);
            if (codes == null || codes.isEmpty()) continue;

            try {
                Map<String, Object> sector = new LinkedHashMap<>();
                sector.put("conceptName", conceptName);
                sector.put("stockCount", codes.size());

                // 批量查 CH：涨跌幅/PE/PB/市值
                String inClause = codes.stream()
                    .filter(s -> s.matches("\\d{6}"))
                    .collect(Collectors.joining("','", "'", "'"));
                if (inClause.length() <= 2) continue;

                String sql = String.format("""
                    SELECT
                        AVG(sd.change_percent) as avgChange,
                        median(sd.pe_ttm) as medianPe,
                        median(sd.pb) as medianPb,
                        SUM(si.total_market_cap) as totalCap
                    FROM stock.stock_daily sd FINAL
                    JOIN stock.stock_info si ON sd.code = si.code
                    WHERE sd.code IN (%s) AND sd.trade_date = '%s'
                    """, inClause, latestDate);

                clickHouseJdbcTemplate.query(sql, (rs) -> {
                    Object avgChg = rs.getObject(1);
                    sector.put("avgChange", avgChg instanceof Number ?
                        BigDecimal.valueOf(((Number) avgChg).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    Object medPe = rs.getObject(2);
                    sector.put("medianPe", medPe instanceof Number ?
                        BigDecimal.valueOf(((Number) medPe).doubleValue()).setScale(1, RoundingMode.HALF_UP) : null);
                    Object medPb = rs.getObject(3);
                    sector.put("medianPb", medPb instanceof Number ?
                        BigDecimal.valueOf(((Number) medPb).doubleValue()).setScale(2, RoundingMode.HALF_UP) : null);
                    Object totalCap = rs.getObject(4);
                    sector.put("totalMarketCap", totalCap instanceof Number ?
                        BigDecimal.valueOf(((Number) totalCap).doubleValue()).setScale(0, RoundingMode.HALF_UP) : null);
                });

                // 涨幅前3龙头
                String topSql = String.format("""
                    SELECT sd.code, si.name, sd.change_percent as chg, si.total_market_cap as cap
                    FROM stock.stock_daily sd FINAL
                    JOIN stock.stock_info si ON sd.code = si.code
                    WHERE sd.code IN (%s) AND sd.trade_date = '%s'
                    ORDER BY sd.change_percent DESC LIMIT 3
                    """, inClause, latestDate);
                List<Map<String, Object>> topStocks = clickHouseJdbcTemplate.query(topSql,
                    (rs, rowNum) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", rs.getString(1));
                        m.put("name", rs.getString(2));
                        m.put("change", rs.getBigDecimal(3));
                        return m;
                    });
                sector.put("topStocks", topStocks);

                results.add(sector);
            } catch (Exception e) {
                log.warn("热门板块聚合跳过 {}: {}", conceptName, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradeDate", latestDate);
        result.put("sectors", results);
        return result;
    }

    /**
     * 热门行业专题详情
     */
    public Map<String, Object> getHotSectorDetail(String conceptName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conceptName", conceptName);

        // 从 MySQL 获取概念下股票
        List<Map<String, Object>> concepts = stockAnalysisMapper.selectAllConcepts();
        Set<String> codes = new TreeSet<>();
        for (Map<String, Object> c : concepts) {
            if (conceptName.equals(c.get("conceptName"))) {
                String code = (String) c.get("code");
                if (code != null && code.matches("\\d{6}")) codes.add(code);
            }
        }
        result.put("stockCount", codes.size());
        if (codes.isEmpty()) {
            result.put("error", "无成分股数据");
            return result;
        }

        String latestDate = getLatestTradeDate();
        String inClause = String.join("','", codes);

        // 成分股列表（按涨跌幅排序）
        String stockSql = String.format("""
            SELECT sd.code, si.name, sd.close_price, sd.change_percent,
                   sd.pe_ttm, sd.pb, sd.turnover_rate, si.total_market_cap,
                   sd.volume
            FROM stock.stock_daily sd FINAL
            JOIN stock.stock_info si ON sd.code = si.code
            WHERE sd.code IN ('%s') AND sd.trade_date = '%s'
            ORDER BY sd.change_percent DESC
            """, inClause, latestDate);
        List<Map<String, Object>> stocks = clickHouseJdbcTemplate.query(stockSql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString(1));
                m.put("name", rs.getString(2));
                m.put("closePrice", rs.getBigDecimal(3));
                m.put("changePercent", rs.getBigDecimal(4));
                m.put("peTtm", rs.getBigDecimal(5));
                m.put("pb", rs.getBigDecimal(6));
                m.put("turnoverRate", rs.getBigDecimal(7));
                Object cap = rs.getObject(8);
                m.put("totalMarketCap", cap instanceof Number ?
                    BigDecimal.valueOf(((Number) cap).doubleValue()).setScale(0, RoundingMode.HALF_UP) : null);
                return m;
            });
        result.put("stocks", stocks);

        // 概览统计
        if (!stocks.isEmpty()) {
            double avgChg = stocks.stream()
                .filter(s -> s.get("changePercent") != null)
                .mapToDouble(s -> ((BigDecimal) s.get("changePercent")).doubleValue())
                .average().orElse(0);
            long upCount = stocks.stream()
                .filter(s -> s.get("changePercent") != null && ((BigDecimal) s.get("changePercent")).doubleValue() > 0)
                .count();
            result.put("avgChange", BigDecimal.valueOf(avgChg).setScale(2, RoundingMode.HALF_UP));
            result.put("upCount", upCount);
            result.put("downCount", stocks.size() - upCount);
        }

        // 近5日板块涨跌趋势（注意：ReplacingMergeTree 表不能直接用 OFFSET 取日期，
        // 因为 OFFSET 按物理行偏移而非逻辑日期，需用子查询 DISTINCT）
        String trendSql = String.format("""
            SELECT sd.trade_date, AVG(sd.change_percent) as avgChg
            FROM stock.stock_daily sd FINAL
            WHERE sd.code IN ('%s')
              AND sd.trade_date >= (SELECT trade_date FROM (
                  SELECT DISTINCT trade_date FROM stock.stock_daily FINAL ORDER BY trade_date DESC LIMIT 6
                ) ORDER BY trade_date ASC LIMIT 1)
              AND sd.trade_date <= (SELECT MAX(trade_date) FROM stock.stock_daily FINAL)
            GROUP BY sd.trade_date
            ORDER BY sd.trade_date
            """, inClause);
        List<Map<String, Object>> trend = clickHouseJdbcTemplate.query(trendSql,
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", rs.getString(1));
                m.put("avgChange", rs.getBigDecimal(2).setScale(2, RoundingMode.HALF_UP));
                return m;
            });
        result.put("trend", trend);

        return result;
    }

    /** 获取最新交易日期 */
    private String getLatestTradeDate() {
        List<String> dates = clickHouseJdbcTemplate.query(
            "SELECT MAX(trade_date) FROM stock.stock_daily FINAL",
            (rs, rowNum) -> rs.getString(1));
        return dates.isEmpty() ? "2026-01-01" : dates.getFirst();
    }

    private double median(List<Double> values) {
        if (values == null || values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    // ══════════════════════════════════════════════════════════════
    // P0 新增：缠论K线可视化、资金流向趋势、相对强弱
    // ══════════════════════════════════════════════════════════════

    /**
     * 缠论K线图数据（实时计算）
     * 获取近250个交易日K线 → ChanTheoryCalculator 计算 → 返回可视化数据
     */
    public Map<String, Object> getChanChart(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取 K 线数据（需要足够长，缠论至少100根才有效）
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 260);
            if (bars == null || bars.size() < 50) {
                result.put("error", "K线数据不足（需至少50个交易日）");
                return result;
            }

            // 2. 转换为 MarketDailyBar（ChanTheoryCalculator 入参）
            List<MarketDailyBar> marketBars = new ArrayList<>();
            for (DailyBarRow bar : bars) {
                if (bar.getOpenPrice() == null || bar.getClosePrice() == null
                        || bar.getHighPrice() == null || bar.getLowPrice() == null) continue;
                marketBars.add(MarketDailyBar.builder()
                        .symbol(normalizeCodeForDailyCH(code))
                        .tradeDate(bar.getTradeDate())
                        .open(bar.getOpenPrice())
                        .high(bar.getHighPrice())
                        .low(bar.getLowPrice())
                        .close(bar.getClosePrice())
                        .vol(bar.getVolume() != null ? BigDecimal.valueOf(bar.getVolume()) : null)
                        .amount(bar.getAmount())
                        .turnoverRate(bar.getTurnoverRate())
                        .build());
            }

            // 3. 缠论计算
            ChanTheoryResult chanResult = ChanTheoryCalculator.calculate(marketBars);
            if (chanResult == null) {
                result.put("error", "缠论计算失败");
                return result;
            }

            // 4. 构建 K 线数据（前端 ECharts 格式）
            List<Object> klineData = new ArrayList<>(); // [open, close, low, high, volume]
            List<String> dates = new ArrayList<>();
            for (MarketDailyBar bar : marketBars) {
                klineData.add(List.of(
                        bar.getOpen().doubleValue(),
                        bar.getClose().doubleValue(),
                        bar.getLow().doubleValue(),
                        bar.getHigh().doubleValue(),
                        bar.getVol() != null ? bar.getVol().doubleValue() : 0
                ));
                dates.add(bar.getTradeDate().toString());
            }

            // 5. 笔数据（折线图标记）
            List<Object> penLines = new ArrayList<>();
            if (chanResult.getPens() != null) {
                for (var pen : chanResult.getPens()) {
                    // 笔连接两个分型端点，方向为 UP/DOWN
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("startIndex", pen.getStartIndex());
                    p.put("endIndex", pen.getEndIndex());
                    p.put("startPrice", pen.getStartPrice());
                    p.put("endPrice", pen.getEndPrice());
                    p.put("startDate", pen.getStartDate() != null ? pen.getStartDate().toString() : null);
                    p.put("endDate", pen.getEndDate() != null ? pen.getEndDate().toString() : null);
                    p.put("direction", pen.getDirection() != null ? pen.getDirection().name() : "UNKNOWN");
                    penLines.add(p);
                }
            }

            // 6. 中枢数据（矩形区域）
            List<Object> hubZones = new ArrayList<>();
            if (chanResult.getHubs() != null) {
                for (var hub : chanResult.getHubs()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("high", hub.getHigh());
                    h.put("low", hub.getLow());
                    h.put("zz", hub.getZz());
                    h.put("startDate", hub.getStartDate() != null ? hub.getStartDate().toString() : null);
                    h.put("endDate", hub.getEndDate() != null ? hub.getEndDate().toString() : null);
                    h.put("oscillationCount", hub.getOscillationCount());
                    hubZones.add(h);
                }
            }

            // 7. 买卖点标记
            List<Object> buySellMarks = new ArrayList<>();
            if (chanResult.getBuySellPoints() != null) {
                for (var bsp : chanResult.getBuySellPoints()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", bsp.getIndex());
                    m.put("type", bsp.getBuySellType() != null ? bsp.getBuySellType().name() : "UNKNOWN");
                    m.put("value", bsp.getBuySellType() != null ? bsp.getBuySellType().getValue() : 0);
                    m.put("isBuy", bsp.getBuySellType() != null && bsp.getBuySellType().isBuy());
                    m.put("date", bsp.getDate() != null ? bsp.getDate().toString() : null);
                    m.put("price", bsp.getPrice());
                    buySellMarks.add(m);
                }
            }

            result.put("dates", dates);
            result.put("klineData", klineData);
            result.put("pens", penLines);
            result.put("hubs", hubZones);
            result.put("buySellPoints", buySellMarks);
            result.put("penCount", chanResult.getPens() != null ? chanResult.getPens().size() : 0);
            result.put("hubCount", chanResult.getHubs() != null ? chanResult.getHubs().size() : 0);
            result.put("bsPointCount", chanResult.getBuySellPoints() != null ? chanResult.getBuySellPoints().size() : 0);
            result.put("barCount", marketBars.size());

        } catch (Exception e) {
            log.error("缠论K线图计算失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 资金流向历史趋势（逐日评分）
     * 复用 TradingSignalEngine 评分规则对每日资金流向打分
     */
    public Map<String, Object> getMoneyFlowHistory(String code, int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取历史资金流向
            List<Map<String, Object>> mfHistory = analysisChMapper.selectMoneyFlowHistory(code, days);
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
            result.put("avgNetMain", BigDecimal.valueOf(avgNetMain / n).setScale(2, RoundingMode.HALF_UP));
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

    /**
     * 单日资金面评分（复用 TradingSignalEngine 权重规则）
     * 满分25分：主力净流入(10) + 占比(8) + 量比(4) + 换手率偏离(3)
     * 注意：历史数据无量比/换手率，只按主力净流入(10)+占比(8)+净流入分级(7)=25分简化
     */
    private int calcDailyMoneyScore(Map<String, Object> row) {
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

    /**
     * 相对强弱分析：个股 vs 行业等权组合的累计收益对比
     * 计算近60日的 RS Ratio（个股累计收益 / 行业累计收益）
     */
    public Map<String, Object> getRelativeStrength(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取该股票行业
            Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
            String industry = myInfo != null ? (String) myInfo.get("industry") : null;
            if (industry == null || industry.isBlank()) {
                result.put("error", "未找到行业信息");
                return result;
            }
            result.put("industry", industry);

            // 2. 获取个股近80日日线（多取确保对齐）
            List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, 85);
            if (bars == null || bars.size() < 30) {
                result.put("error", "个股数据不足（需至少30个交易日）");
                return result;
            }

            // 3. 获取行业等权日收益率序列
            String normalized = normalizeCodeForDailyCH(code);
            String indReturnSql = """
                SELECT sd.trade_date, AVG(sd.change_percent) / 100 as avg_ret
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 90)
                GROUP BY sd.trade_date
                ORDER BY sd.trade_date
                """;
            List<Map<String, Object>> indRows = clickHouseJdbcTemplate.query(indReturnSql,
                (rs, rowNum) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("tradeDate", rs.getString("trade_date"));
                    m.put("avgRet", rs.getBigDecimal("avg_ret"));
                    return m;
                }, industry);

            // 构建行业收益 map
            Map<String, Double> indRetMap = new LinkedHashMap<>();
            for (Map<String, Object> r : indRows) {
                String td = (String) r.get("tradeDate");
                BigDecimal avgRet = (BigDecimal) r.get("avgRet");
                if (avgRet != null) indRetMap.put(td, avgRet.doubleValue());
            }

            // 4. 对齐日期并计算累计收益 + RS Ratio
            double stockCumRet = 0;
            double indCumRet = 0;
            List<Map<String, Object>> series = new ArrayList<>();
            List<String> dates = new ArrayList<>();
            List<Double> stockCumList = new ArrayList<>();
            List<Double> indCumList = new ArrayList<>();
            List<Double> rsRatioList = new ArrayList<>();

            for (int i = 1; i < bars.size(); i++) {
                DailyBarRow prev = bars.get(i - 1);
                DailyBarRow curr = bars.get(i);
                if (prev.getClosePrice() == null || curr.getClosePrice() == null
                        || prev.getClosePrice().doubleValue() == 0) continue;
                if (curr.getTradeDate() == null) continue;

                double stockRet = (curr.getClosePrice().doubleValue() - prev.getClosePrice().doubleValue())
                        / prev.getClosePrice().doubleValue();
                String td = curr.getTradeDate().toString();
                Double indRet = indRetMap.get(td);
                if (indRet == null) continue;

                stockCumRet += stockRet;
                indCumRet += indRet;

                // RS Ratio: 个股累计收益 / 行业累计收益（行业为0时取0）
                double rsRatio = Math.abs(indCumRet) > 0.0001 ? stockCumRet / indCumRet : (stockCumRet > 0 ? 1.0 : (stockCumRet < 0 ? -1.0 : 0));

                Map<String, Object> day = new LinkedHashMap<>();
                day.put("tradeDate", td);
                day.put("stockRet", Math.round(stockRet * 10000.0) / 100.0);
                day.put("indRet", Math.round(indRet * 10000.0) / 100.0);
                day.put("excessRet", Math.round((stockRet - indRet) * 10000.0) / 100.0);
                day.put("stockCumRet", Math.round(stockCumRet * 10000.0) / 100.0);
                day.put("indCumRet", Math.round(indCumRet * 10000.0) / 100.0);
                day.put("rsRatio", Math.round(rsRatio * 100.0) / 100.0);
                series.add(day);

                dates.add(td);
                stockCumList.add(Math.round(stockCumRet * 10000.0) / 100.0);
                indCumList.add(Math.round(indCumRet * 10000.0) / 100.0);
                rsRatioList.add(Math.round(rsRatio * 100.0) / 100.0);
            }

            result.put("series", series);
            result.put("dates", dates);
            result.put("stockCumRet", stockCumList);
            result.put("indCumRet", indCumList);
            result.put("rsRatio", rsRatioList);
            result.put("totalDays", series.size());

            // 5. 统计汇总
            if (!series.isEmpty()) {
                double latestStockCum = stockCumList.getLast();
                double latestIndCum = indCumList.getLast();
                double latestRs = rsRatioList.getLast();
                result.put("latestStockCumRet", latestStockCum);
                result.put("latestIndCumRet", latestIndCum);
                result.put("latestExcessRet", Math.round((latestStockCum - latestIndCum) * 100.0) / 100.0);
                result.put("latestRsRatio", latestRs);

                // 超额收益为正的天数占比
                long exceedDays = series.stream()
                        .filter(d -> ((Number) d.get("excessRet")).doubleValue() > 0)
                        .count();
                result.put("exceedDays", exceedDays);
                result.put("exceedRatio", Math.round((double) exceedDays / series.size() * 10000) / 100.0);

                // RS Ratio 描述
                String rsDesc;
                if (latestRs > 1.5) rsDesc = "显著强于行业（RS>1.5）";
                else if (latestRs > 1.1) rsDesc = "明显强于行业（RS>1.1）";
                else if (latestRs > 0.9) rsDesc = "与行业同步（RS 0.9~1.1）";
                else if (latestRs > 0.5) rsDesc = "弱于行业（RS 0.5~0.9）";
                else rsDesc = "显著弱于行业（RS<0.5）";
                result.put("rsDesc", rsDesc);
            }

        } catch (Exception e) {
            log.error("相对强弱分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", "计算失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * K线数据（近N交易日，供前端图表使用）
     */
    public List<Map<String, Object>> getKLine(String code, int days) {
        List<DailyBarRow> bars = analysisChMapper.selectRecentDailyBars(code, days);
        if (bars == null || bars.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyBarRow bar : bars) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", bar.getTradeDate() != null ? bar.getTradeDate().toString() : "");
            item.put("open", bar.getOpenPrice());
            item.put("high", bar.getHighPrice());
            item.put("low", bar.getLowPrice());
            item.put("close", bar.getClosePrice());
            item.put("volume", bar.getVolume());
            item.put("changePercent", bar.getChangePercent());
            item.put("turnoverRate", bar.getTurnoverRate());
            result.add(item);
        }
        return result;
    }

    /**
     * P2 新增：个股长周期表现分析
     * 返回：YTD涨幅、相对沪深300超额收益、RS Rating（250日收益排名百分位）、行业内排名
     */
    public Map<String, Object> getStockPerformance(String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        try {
            // 1. 获取该股票行业信息
            Map<String, Object> myInfo = stockAnalysisMapper.selectStockInfo(code);
            String industry = myInfo != null ? (String) myInfo.get("industry") : null;
            result.put("industry", industry);

            // 2. 确定当年首个交易日（CH index_daily）
            String yearStartDate = getYearStartDate();
            if (yearStartDate == null) {
                result.put("error", "无法获取年度起始日期");
                return result;
            }
            result.put("yearStartDate", yearStartDate);

            // 3. 获取沪深300 YTD涨幅
            double hs300Ytd = calcIndexYtd("000300", yearStartDate);
            result.put("hs300Ytd", round2(hs300Ytd * 100));

            // 4. 获取个股YTD涨幅（从stock_daily）
            double stockYtd = calcStockYtd(code, yearStartDate);
            if (stockYtd == Double.NaN || stockYtd == Double.MAX_VALUE) {
                result.put("error", "个股数据不足");
                return result;
            }
            result.put("stockYtd", round2(stockYtd * 100));
            result.put("excessReturn", round2((stockYtd - hs300Ytd) * 100));

            // 5. RS Rating：近250日收益排名百分位（全市场）
            int rsRating = calcRsRating(code);
            result.put("rsRating", rsRating);
            result.put("rsRatingLabel", rsRatingToLabel(rsRating));

            // 6. 行业内排名（按20日涨幅）
            if (industry != null && !industry.isBlank()) {
                int indRank = calcIndustryRank(code, industry);
                int indTotal = calcIndustryTotal(industry);
                result.put("industryRank", indRank);
                result.put("industryTotal", indTotal);
                result.put("industryRankLabel", indRank + "/" + indTotal);
                result.put("industryRankPct", indTotal > 0 ? round2(indRank * 100.0 / indTotal) : null);
            }

            return result;
        } catch (Exception e) {
            log.error("个股长周期表现分析失败: code={}, error={}", code, e.getMessage(), e);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 获取当年首个交易日（以沪深300为准）
     */
    private String getYearStartDate() {
        int currentYear = java.time.LocalDate.now().getYear();
        try {
            String sql = String.format(
                "SELECT MIN(trade_date) FROM stock.index_daily WHERE code = '000300' AND trade_date >= '%d-01-01'",
                currentYear);
            Object rawDate = clickHouseJdbcTemplate.queryForObject(sql, Object.class);
            if (rawDate == null) return null;
            return rawDate instanceof LocalDate ? ((LocalDate) rawDate).toString() : rawDate.toString();
        } catch (Exception e) {
            log.warn("获取年度起始日期失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算指数YTD涨幅
     */
    private double calcIndexYtd(String indexCode, String yearStartDate) {
        try {
            String sql = String.format("""
                SELECT
                    (max(close_price) - min(close_price)) / min(close_price) as ytd
                FROM stock.index_daily
                WHERE code = '%s' AND trade_date >= '%s'
                """, indexCode, yearStartDate);
            Double ytd = clickHouseJdbcTemplate.queryForObject(sql, Double.class);
            return ytd != null ? ytd : 0.0;
        } catch (Exception e) {
            log.warn("计算指数YTD失败: code={}, {}", indexCode, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 计算个股YTD涨幅（从stock_daily）
     * 使用子查询获取首日/末日价格，避免 maxBy/minBy（ClickHouse 26.5 不支持）
     */
    private double calcStockYtd(String code, String yearStartDate) {
        String normalized = normalizeCodeForDailyCH(code);
        try {
            // 先查首日、末日两个日期
            String dateSql = String.format("""
                SELECT MIN(trade_date) as start_date, MAX(trade_date) as end_date
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= '%s'
                """, normalized, yearStartDate);
            Map<String, Object> dateRow = clickHouseJdbcTemplate.queryForMap(dateSql);
            // ClickHouse Date 类型返回 LocalDate，需要转 String
            Object startObj = dateRow.get("start_date");
            Object endObj = dateRow.get("end_date");
            String startDate = startObj instanceof LocalDate ? ((LocalDate) startObj).toString() : startObj.toString();
            String endDate = endObj instanceof LocalDate ? ((LocalDate) endObj).toString() : endObj.toString();
            if (startDate == null || endDate == null) return Double.NaN;

            // 查首日收盘价
            BigDecimal startPrice = null;
            String startSql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s' LIMIT 1",
                normalized, startDate);
            List<Map<String, Object>> startRows = clickHouseJdbcTemplate.queryForList(startSql);
            if (!startRows.isEmpty() && startRows.get(0).get("close_price") != null) {
                startPrice = new BigDecimal(startRows.get(0).get("close_price").toString());
            }
            // 查末日收盘价
            BigDecimal endPrice = null;
            String endSql = String.format(
                "SELECT close_price FROM stock.stock_daily FINAL WHERE code = '%s' AND trade_date = '%s' LIMIT 1",
                normalized, endDate);
            List<Map<String, Object>> endRows = clickHouseJdbcTemplate.queryForList(endSql);
            if (!endRows.isEmpty() && endRows.get(0).get("close_price") != null) {
                endPrice = new BigDecimal(endRows.get(0).get("close_price").toString());
            }

            if (startPrice == null || endPrice == null || startPrice.doubleValue() == 0) {
                return Double.NaN;
            }
            return endPrice.subtract(startPrice).divide(startPrice, 6, RoundingMode.HALF_UP).doubleValue();
        } catch (Exception e) {
            log.warn("计算个股YTD失败: code={}, {}", code, e.getMessage());
            return Double.NaN;
        }
    }
    /**
     * 计算RS Rating：近250日收益排名百分位（0~99）
     * 样本：全市场有≥160日数据的沪深股票
     */
    private int calcRsRating(String code) {
        String normalized = normalizeCodeForDailyCH(code);
        try {
            // 近250日个股收益率（用 argMax/argMin 取首日/末日价格，已验证有效）
            String stockSql = String.format("""
                SELECT (argMax(close_price, trade_date) - argMin(close_price, trade_date))
                       / argMin(close_price, trade_date) as ret_250d
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= subtractDays(today(), 260)
                """, normalized);
            Double stockRet = clickHouseJdbcTemplate.queryForObject(stockSql, Double.class);
            if (stockRet == null) return 0;

            // 全市场近250日收益率分布（分位数）
            String pctSql = String.format("""
                WITH stock_ret AS (
                    SELECT code,
                           (argMax(close_price, trade_date) - argMin(close_price, trade_date))
                           / argMin(close_price, trade_date) as ret
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= subtractDays(today(), 260)
                    GROUP BY code
                    HAVING min(close_price) > 0 AND count() >= 160
                )
                SELECT
                    countIf(ret > %f) as above_count,
                    count() as total_count
                FROM stock_ret
                """, stockRet);
            Map<String, Object> pctRow = clickHouseJdbcTemplate.queryForMap(pctSql);
            long above = ((Number) pctRow.get("above_count")).longValue();
            long total = ((Number) pctRow.get("total_count")).longValue();

            if (total == 0) return 0;
            // 百分位：above/total = 比该股强的股票比例 → (1 - above/total) * 99 = 排名百分位
            int rating = (int) Math.round((1.0 - (double) above / total) * 99);
            return Math.max(0, Math.min(99, rating));
        } catch (Exception e) {
            log.warn("计算RS Rating失败: code={}, {}", code, e.getMessage());
            return 0;
        }
    }

    /**
     * 计算该股在行业内的20日涨幅排名
     */
    private int calcIndustryRank(String code, String industry) {
        String normalized = normalizeCodeForDailyCH(code);
        try {
            // 先获取该股20日收益率
            String targetSql = String.format("""
                SELECT (argMax(close_price, trade_date) - min(close_price)) / min(close_price) as ret_20d
                FROM stock.stock_daily FINAL
                WHERE code = '%s' AND trade_date >= subtractDays(today(), 25)
                """, normalized);
            Double targetRet = clickHouseJdbcTemplate.queryForObject(targetSql, Double.class);
            if (targetRet == null) return 0;

            // 统计行业内收益率高于该股的股票数量
            String rankSql = """
                WITH latest AS (
                    SELECT code,
                           argMax(close_price, trade_date) as latest_close,
                           min(close_price) as min_close,
                           count() as day_count
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= subtractDays(today(), 25)
                    GROUP BY code
                    HAVING min(close_price) > 0 AND day_count >= 10
                ),
                ret20 AS (
                    SELECT l.code,
                           (l.latest_close - l.min_close) / l.min_close as ret_20d
                    FROM latest l
                    INNER JOIN stock.stock_info si ON si.code = l.code
                    WHERE si.industry = ?
                      AND si.market NOT IN ('BJ','北交所')
                )
                SELECT countIf(ret_20d > ?) + 1 as rank
                FROM ret20
                """;
            Integer rank = clickHouseJdbcTemplate.queryForObject(rankSql, Integer.class, industry, targetRet);
            return rank != null ? rank : 0;
        } catch (Exception e) {
            log.warn("计算行业内排名失败: industry={}, {}", industry, e.getMessage());
            return 0;
        }
    }

    /**
     * 计算行业内股票总数
     */
    private int calcIndustryTotal(String industry) {
        try {
            String sql = """
                SELECT COUNT(DISTINCT sd.code) as cnt
                FROM stock.stock_daily sd FINAL
                INNER JOIN stock_info si ON si.code = sd.code
                WHERE si.industry = ?
                  AND si.market NOT IN ('BJ','北交所')
                  AND sd.trade_date >= subtractDays(today(), 25)
                """;
            Integer cnt = clickHouseJdbcTemplate.queryForObject(sql, Integer.class, industry);
            return cnt != null ? cnt : 0;
        } catch (Exception e) {
            log.warn("计算行业内总数失败: industry={}, {}", industry, e.getMessage());
            return 0;
        }
    }

    private String rsRatingToLabel(int rating) {
        if (rating >= 90) return "极强（Top 10%）";
        if (rating >= 80) return "很强（Top 20%）";
        if (rating >= 70) return "较强（Top 30%）";
        if (rating >= 50) return "中等偏强";
        if (rating >= 30) return "中等偏弱";
        if (rating >= 20) return "较弱（Bottom 30%）";
        if (rating >= 10) return "很弱（Bottom 20%）";
        return "极弱（Bottom 10%）";
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 信心水平：基于数据完整性评分（低/中/高）
     * 研报覆盖 + 基本面数据完整度 + 缠论信号
     */
    private String calcConfidenceLevel(FundamentalSignal fundamental, ResearchSignal research) {
        int score = 0;
        // 有研报覆盖（reportCount是int原始类型，无法判空）
        if (research != null && research.getReportCount() > 0) score += 3;
        // PE/PB/ROE 数据完整
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null && fundamental.getPeTtm().doubleValue() > 0) score += 2;
            if (fundamental.getRoe() != null && fundamental.getRoe().doubleValue() > 0) score += 2;
            if (fundamental.getRevenueYoy() != null) score += 1;
        }
        if (score >= 6) return "高";
        if (score >= 3) return "中";
        return "低";
    }

    /**
     * 新闻面评分（满分10分，供评分引擎使用）
     * 规则：有新闻+1，利好偏多+3，利好远超风险+2，有事件标签+2，情感偏向强烈+2
     */
    private int calcNewsScore(int positive, int negative, int tagged, double sentimentBias) {
        int score = 0;
        if (positive + negative > 0) score += 1;  // 有新闻
        if (positive > negative) score += 3;       // 利好偏多
        else if (positive > 0 && negative == 0) score += 2;  // 纯利好
        if (tagged > 0) score += 2;                // 有重大事件标签
        if (sentimentBias > 0.5) score += 2;       // 强烈利好偏向
        else if (sentimentBias < -0.5) score -= 1; // 强烈风险偏向
        return Math.max(0, Math.min(10, score));
    }
}
