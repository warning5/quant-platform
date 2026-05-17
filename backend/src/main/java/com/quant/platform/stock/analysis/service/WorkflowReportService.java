package com.quant.platform.stock.analysis.service;

import com.quant.platform.stock.analysis.domain.*;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Workflow A 综合分析报告服务（档一：轻量版报告模板填充）
 *
 * 六大模块：
 * 1. 技术分析师 → AnalysisService.getOverview() 四维度评分
 * 2. 基本面分析师 → PE分位/ROE/营收增速
 * 3. 新闻分析师 → 近90天研报评级/目标价
 * 4. 情绪分析师 → 主力资金流向/北向资金
 * 5. 多空辩论 → 规则引擎（PE>50→空头；增速>20%→多头）
 * 6. 报告生成 → Markdown数据聚合 → FreeMarker → HTML
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowReportService {

    private final AnalysisService analysisService;
    private final Configuration freeMarkerConfig;

    /**
     * 生成 Workflow A 综合分析报告（数据层）
     */
    public WorkflowReport generateReport(String code) {
        log.info("生成Workflow A报告: code={}", code);
        long start = System.currentTimeMillis();

        WorkflowReport report = new WorkflowReport();
        report.setCode(code);

        // ========== 1. 技术分析师：四维度评分总览 ==========
        AnalysisOverview overview = analysisService.getOverview(code);
        report.setOverview(overview);
        report.setName(overview.getName());
        report.setTotalScore(overview.getTotalScore());
        report.setActionName(overview.getActionName());
        report.setPosition(overview.getPosition());

        // ========== 2. 基本面分析师：估值分位 + 财务指标 ==========
        try {
            Map<String, Object> valuation = analysisService.getValuationPercentile(code, 3);
            report.setValuationPercentile(valuation);
        } catch (Exception e) {
            log.warn("估值分位查询失败: code={}, error={}", code, e.getMessage());
        }

        // ========== 3. 新闻分析师：研报数据 ==========
        try {
            Map<String, Object> research = analysisService.getResearchAnalysis(code);
            report.setResearchAnalysis(research);
        } catch (Exception e) {
            log.warn("研报分析查询失败: code={}, error={}", code, e.getMessage());
        }

        // ========== 4. 情绪分析师：资金流向 + 同业对比 ==========
        try {
            Map<String, Object> peers = analysisService.getPeerComparison(code);
            report.setPeerComparison(peers);
        } catch (Exception e) {
            log.warn("同业对比查询失败: code={}, error={}", code, e.getMessage());
        }

        // ========== 5. 多空辩论：规则引擎 ==========
        evaluateBullBear(report, overview);

        // ========== 6. 综合结论 ==========
        report.setConclusion(buildReportConclusion(report, overview));

        long cost = System.currentTimeMillis() - start;
        log.info("Workflow A报告生成完成: code={}, bull={}, bear={}, cost={}ms",
                code, report.getBullCount(), report.getBearCount(), cost);
        return report;
    }

    /**
     * 生成 HTML 报告（模板渲染）
     */
    public String generateHtml(String code) throws Exception {
        WorkflowReport report = generateReport(code);

        Template template = freeMarkerConfig.getTemplate("report/workflow-a.ftl");
        Map<String, Object> model = new HashMap<>();
        model.put("report", report);

        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

    // ==================== 规则引擎 ====================

    /**
     * 根据规则生成多空论据
     */
    private void evaluateBullBear(WorkflowReport report, AnalysisOverview overview) {
        List<BullBearArgument> bullArgs = new ArrayList<>();
        List<BullBearArgument> bearArgs = new ArrayList<>();

        TechSignal tech = overview.getTechSignal();
        MoneyFlowSignal money = overview.getMoneySignal();
        FundamentalSignal fundamental = overview.getFundamentalSignal();
        SentimentSignal sentiment = overview.getSentimentSignal();
        ResearchSignal research = overview.getResearchSignal();

        // --- 技术面规则 ---
        if (tech != null) {
            if ("BUY".equals(tech.getChanSignal())) {
                bullArgs.add(new BullBearArgument("缠论买点", "技术",
                        "缠论出现买入信号（" + mapChanBuyType(tech.getChanSignal()) + "）", 5));
            }
            if ("SELL".equals(tech.getChanSignal())) {
                bearArgs.add(new BullBearArgument("缠论卖点", "技术",
                        "缠论出现卖出信号", 4));
            }
            if (Boolean.TRUE.equals(tech.getMaBullish())) {
                bullArgs.add(new BullBearArgument("均线多头", "技术",
                        "MA5>MA10>MA20>MA60，均线多头排列", 4));
            }
            if (Boolean.TRUE.equals(tech.getMacdGolden())) {
                bullArgs.add(new BullBearArgument("MACD金叉", "技术",
                        "MACD出现金叉，短期动能转强", 3));
            }
            if (tech.getRsi() != null) {
                double rsi = tech.getRsi().doubleValue();
                if (rsi < 30) {
                    bullArgs.add(new BullBearArgument("RSI超卖", "技术",
                            "RSI=" + rsi + "，处于超卖区间，存在反弹可能", 3));
                } else if (rsi > 70) {
                    bearArgs.add(new BullBearArgument("RSI超买", "技术",
                            "RSI=" + rsi + "，处于超买区间，注意回调风险", 3));
                }
            }
        }

        // --- 基本面规则 ---
        if (fundamental != null) {
            if (fundamental.getPeTtm() != null) {
                double pe = fundamental.getPeTtm().doubleValue();
                if (pe > 0 && pe < 15) {
                    bullArgs.add(new BullBearArgument("低PE估值", "基本面",
                            "PE(TTM)=" + formatDecimal(pe) + "，处于低估值区间", 4));
                } else if (pe > 50) {
                    bearArgs.add(new BullBearArgument("高PE估值", "基本面",
                            "PE(TTM)=" + formatDecimal(pe) + "，绝对估值偏高", 4));
                }
            }
            if (fundamental.getPb() != null) {
                double pb = fundamental.getPb().doubleValue();
                if (pb > 0 && pb < 1.5) {
                    bullArgs.add(new BullBearArgument("低PB估值", "基本面",
                            "PB=" + formatDecimal(pb) + "，破净风险低", 3));
                } else if (pb > 8) {
                    bearArgs.add(new BullBearArgument("高PB估值", "基本面",
                            "PB=" + formatDecimal(pb) + "，市净率偏高", 3));
                }
            }
            if (fundamental.getRoe() != null) {
                double roe = fundamental.getRoe().doubleValue();
                if (roe > 15) {
                    bullArgs.add(new BullBearArgument("高ROE", "基本面",
                            "ROE=" + formatDecimal(roe) + "%，盈利能力优秀", 4));
                } else if (roe < 5) {
                    bearArgs.add(new BullBearArgument("低ROE", "基本面",
                            "ROE=" + formatDecimal(roe) + "%，盈利能力偏弱", 3));
                }
            }
            if (fundamental.getRevenueYoy() != null) {
                double rev = fundamental.getRevenueYoy().doubleValue();
                if (rev > 20) {
                    bullArgs.add(new BullBearArgument("营收高增", "基本面",
                            "营收同比增速=" + formatDecimal(rev) + "%，成长性突出", 4));
                } else if (rev < -10) {
                    bearArgs.add(new BullBearArgument("营收下滑", "基本面",
                            "营收同比增速=" + formatDecimal(rev) + "%，增长承压", 3));
                }
            }
            if (fundamental.getNetProfitYoy() != null) {
                double profit = fundamental.getNetProfitYoy().doubleValue();
                if (profit > 30) {
                    bullArgs.add(new BullBearArgument("利润高增", "基本面",
                            "净利润同比增速=" + formatDecimal(profit) + "%，盈利爆发", 4));
                } else if (profit < -20) {
                    bearArgs.add(new BullBearArgument("利润下滑", "基本面",
                            "净利润同比增速=" + formatDecimal(profit) + "%，盈利恶化", 3));
                }
            }
            if (fundamental.getDebtRatio() != null) {
                double debt = fundamental.getDebtRatio().doubleValue();
                if (debt > 80) {
                    bearArgs.add(new BullBearArgument("高负债率", "基本面",
                            "资产负债率=" + formatDecimal(debt) + "%，财务杠杆过高", 3));
                } else if (debt < 30) {
                    bullArgs.add(new BullBearArgument("低负债率", "基本面",
                            "资产负债率=" + formatDecimal(debt) + "%，财务结构稳健", 2));
                }
            }
        }

        // --- 资金面规则 ---
        if (money != null) {
            if (money.getNetMain() != null) {
                double netMain = money.getNetMain().doubleValue();
                if (netMain > 0) {
                    bullArgs.add(new BullBearArgument("主力流入", "资金",
                            "主力净流入" + formatMoney(netMain) + "，资金积极介入", 4));
                } else if (netMain < 0) {
                    bearArgs.add(new BullBearArgument("主力流出", "资金",
                            "主力净流出" + formatMoney(Math.abs(netMain)) + "，资金撤退", 4));
                }
            }
            if (money.getVolumeRatio() != null) {
                double vr = money.getVolumeRatio().doubleValue();
                if (vr >= 2.0) {
                    bullArgs.add(new BullBearArgument("量能放大", "资金",
                            "量比=" + formatDecimal(vr) + "，成交活跃，资金关注度提升", 3));
                } else if (vr < 0.5) {
                    bearArgs.add(new BullBearArgument("量能萎缩", "资金",
                            "量比=" + formatDecimal(vr) + "，成交清淡，市场参与度低", 2));
                }
            }
        }

        // --- 事件面规则 ---
        if (sentiment != null) {
            if (Boolean.TRUE.equals(sentiment.getIsStrongStock())) {
                bullArgs.add(new BullBearArgument("强势股", "情绪",
                        "近20日涨幅>30%，处于强势状态", 3));
            }
            if (sentiment.getLimitUpDays() != null && sentiment.getLimitUpDays() > 0) {
                bullArgs.add(new BullBearArgument("涨停基因", "情绪",
                        "近20日涨停" + sentiment.getLimitUpDays() + "次，市场情绪积极", 3));
            }
            if (sentiment.getMarginChgPct() != null) {
                double margin = sentiment.getMarginChgPct().doubleValue();
                if (margin > 5) {
                    bullArgs.add(new BullBearArgument("融资加仓", "情绪",
                            "融资余额较5日前增长" + formatDecimal(margin) + "%，杠杆资金看多", 3));
                } else if (margin < -5) {
                    bearArgs.add(new BullBearArgument("融资减仓", "情绪",
                            "融资余额较5日前下降" + formatDecimal(Math.abs(margin)) + "%，杠杆资金谨慎", 3));
                }
            }
        }

        // --- 研报规则 ---
        if (research != null) {
            if (research.getResearchScore() >= 4) {
                bullArgs.add(new BullBearArgument("机构看好", "研报",
                        "最新评级为" + research.getLatestRating() + "，机构态度积极", 3));
            } else if ("减持".equals(research.getLatestRating()) || "卖出".equals(research.getLatestRating())) {
                bearArgs.add(new BullBearArgument("机构看空", "研报",
                        "最新评级为" + research.getLatestRating() + "，机构态度谨慎", 3));
            }
            if (research.getReportCount() >= 5) {
                bullArgs.add(new BullBearArgument("研报密集", "研报",
                        "近90天" + research.getReportCount() + "份研报覆盖，市场关注度高", 2));
            }
        }

        // --- 综合评分规则 ---
        if (overview.getTotalScore() != null) {
            int score = overview.getTotalScore();
            if (score >= 75) {
                bullArgs.add(new BullBearArgument("高分综合", "综合",
                        "四维度综合评分" + score + "分，整体质地优秀", 5));
            } else if (score <= 35) {
                bearArgs.add(new BullBearArgument("低分综合", "综合",
                        "四维度综合评分" + score + "分，整体质地偏弱", 4));
            }
        }

        // 按强度排序
        bullArgs.sort((a, b) -> Integer.compare(b.getStrength(), a.getStrength()));
        bearArgs.sort((a, b) -> Integer.compare(b.getStrength(), a.getStrength()));

        report.setBullArguments(bullArgs);
        report.setBearArguments(bearArgs);
    }

    /**
     * 生成报告综合结论
     */
    private String buildReportConclusion(WorkflowReport report, AnalysisOverview overview) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(report.getName()).append("(").append(report.getCode()).append(")】");
        sb.append("个股综合分析：");

        int bull = report.getBullCount();
        int bear = report.getBearCount();
        String bias = report.getBias();

        sb.append("多空对比").append(bull).append(":").append(bear).append("，整体").append(bias).append("。");

        if (overview.getTotalScore() != null) {
            sb.append("四维度评分").append(overview.getTotalScore()).append("分，");
        }
        if (overview.getActionName() != null) {
            sb.append("建议【").append(overview.getActionName()).append("】");
        }
        if (overview.getPosition() != null) {
            sb.append("，仓位建议").append(overview.getPosition()).append("%");
        }
        sb.append("。");

        // 关键多头论据摘要（取前2条）
        if (!report.getBullArguments().isEmpty()) {
            sb.append("看多因素：");
            for (int i = 0; i < Math.min(2, report.getBullArguments().size()); i++) {
                BullBearArgument arg = report.getBullArguments().get(i);
                sb.append(arg.getRule()).append("（").append(arg.getDescription()).append("）");
                if (i < Math.min(2, report.getBullArguments().size()) - 1) sb.append("、");
            }
            sb.append("。");
        }

        // 关键空头论据摘要（取前2条）
        if (!report.getBearArguments().isEmpty()) {
            sb.append("看空因素：");
            for (int i = 0; i < Math.min(2, report.getBearArguments().size()); i++) {
                BullBearArgument arg = report.getBearArguments().get(i);
                sb.append(arg.getRule()).append("（").append(arg.getDescription()).append("）");
                if (i < Math.min(2, report.getBearArguments().size()) - 1) sb.append("、");
            }
            sb.append("。");
        }

        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private String mapChanBuyType(String signal) {
        return switch (signal) {
            case "BUY" -> "买入";
            case "SELL" -> "卖出";
            case "HOLD" -> "持有";
            default -> signal;
        };
    }

    private String formatDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toString();
    }

    private String formatMoney(double value) {
        if (Math.abs(value) >= 1_0000_0000) {
            return BigDecimal.valueOf(value / 1_0000_0000).setScale(2, RoundingMode.HALF_UP) + "亿";
        } else if (Math.abs(value) >= 10000) {
            return BigDecimal.valueOf(value / 10000).setScale(2, RoundingMode.HALF_UP) + "万";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toString();
    }
}
