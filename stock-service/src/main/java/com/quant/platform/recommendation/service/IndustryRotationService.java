package com.quant.platform.recommendation.service;

import com.quant.platform.recommendation.domain.StockRecommendation;
import com.quant.platform.screen.dto.ScreenResult;
import com.quant.platform.stock.entity.StockDaily;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 行业轮动：行业动量计算、分行业 Regime 识别、行业相关分组与分散化。
 * <p>由 RecommendationService 拆出（Phase4），方法体逐字迁移，行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndustryRotationService {

    private final ClickHouseStockService clickHouseStockService;
    private final StockInfoMapper stockInfoMapper;
    private final RecommendationQueryService queryService;
    private final com.quant.platform.factor.dynamic.DynamicIndustryCorrelationService dynamicIndustryCorrService;

    /**
     * 同行业最多推荐 N 只
     */
    private static final int MAX_SAME_INDUSTRY = 3;
    /**
     * 申万一级行业 → 指数代码映射（从 stock_info.industry 到 index_daily.code）
     */
    private static final Map<String, String> INDUSTRY_TO_SW_CODE = Map.ofEntries(
            Map.entry("农林牧渔", "801010"), Map.entry("基础化工", "801030"),
            Map.entry("钢铁", "801040"), Map.entry("有色金属", "801050"),
            Map.entry("电子", "801080"), Map.entry("家用电器", "801110"),
            Map.entry("食品饮料", "801120"), Map.entry("纺织服饰", "801130"),
            Map.entry("轻工制造", "801140"), Map.entry("医药生物", "801150"),
            Map.entry("公用事业", "801160"), Map.entry("交通运输", "801170"),
            Map.entry("房地产", "801180"), Map.entry("商贸零售", "801200"),
            Map.entry("社会服务", "801210"), Map.entry("综合", "801230"),
            Map.entry("建筑材料", "801710"), Map.entry("建筑装饰", "801720"),
            Map.entry("电力设备", "801250"), Map.entry("国防军工", "801260"),
            Map.entry("计算机", "801270"), Map.entry("传媒", "801280"),
            Map.entry("通信", "801300"), Map.entry("汽车", "801880"),
            Map.entry("机械设备", "801890"),
            // 金融/资源/环保/消费
            Map.entry("银行", "801780"), Map.entry("非银金融", "801790"),
            Map.entry("煤炭", "801950"), Map.entry("石油石化", "801960"),
            Map.entry("环保", "801970"), Map.entry("美容护理", "801980")
    );
    /**
     * 二级行业名称 → 归约到一级行业的映射（解决 IND_CORR_GROUPS 含二级行业的问题）
     */
    private static final Map<String, String> SW2_TO_SW1 = Map.ofEntries(
            Map.entry("房地产开发", "房地产"),
            Map.entry("房地产服务", "房地产"),
            Map.entry("建筑材料", "建筑材料"),
            Map.entry("建筑装饰", "建筑装饰"),
            Map.entry("证券", "非银金融"),
            Map.entry("保险", "非银金融"),
            Map.entry("信托", "非银金融"),
            Map.entry("期货", "非银金融"),
            Map.entry("银行", "银行"),
            Map.entry("煤炭", "煤炭"),
            Map.entry("石油石化", "石油石化"),
            Map.entry("电力设备", "电力设备"),
            Map.entry("食品饮料", "食品饮料"),
            Map.entry("农林牧渔", "农林牧渔"),
            Map.entry("纺织服饰", "纺织服饰"),
            Map.entry("计算机", "计算机"),
            Map.entry("通信", "通信"),
            Map.entry("传媒", "传媒"),
            Map.entry("汽车", "汽车"),
            Map.entry("机械设备", "机械设备"),
            Map.entry("医药生物", "医药生物"),
            Map.entry("公用事业", "公用事业"),
            Map.entry("国防军工", "国防军工"),
            Map.entry("电子", "电子")
    );
    /**
     * 高相关行业分组（组内股票走势相关系数 > 0.7）
     * 同组内的行业共享分散化名额
     */
    private static final List<List<String>> INDUSTRY_CORR_GROUPS = List.of(
            List.of("银行", "非银金融"),           // 金融板块
            List.of("房地产开发", "房地产服务", "建筑装饰", "建筑材料"),  // 地产链
            List.of("煤炭", "石油石化", "电力设备"),  // 能源链
            List.of("食品饮料", "农林牧渔", "纺织服饰"),  // 消费链
            List.of("计算机", "通信", "传媒"),       // TMT
            List.of("汽车", "机械设备"),           // 制造链
            List.of("医药生物", "公用事业"),        // 防御板块
            List.of("电子", "国防军工")            // 科技制造
    );

    /**
     * 批量填充 industry 和 marketCap（从 stock_info 表）
     * stockCode 格式: "600027.SH" → 去后缀查 stock_info.code = "600027"
     */
    void fillIndustryAndMarketCap(List<StockRecommendation> recs) {
        queryService.fillIndustryAndMarketCap(recs);
    }

    /**
     * 根据行业名查找所属相关组
     * P2-8: 优先使用动态行业相关分组，回退到静态INDUSTRY_CORR_GROUPS
     */
    String getCorrGroup(String industry) {
        // P2-8: 优先使用动态分组
        try {
            List<List<String>> dynamicGroups = dynamicIndustryCorrService.getDynamicCorrGroups();
            for (List<String> group : dynamicGroups) {
                if (group.contains(industry)) return group.getFirst();
            }
        } catch (Exception e) {
            log.debug("[Recommendation] P2-8 动态行业分组获取失败, 回退到静态: {}", e.getMessage());
        }
        // 回退到静态分组
        for (List<String> group : INDUSTRY_CORR_GROUPS) {
            if (group.contains(industry)) return group.getFirst();
        }
        return industry; // 不在任何组中，独立计算
    }

    /**
     * 行业分散化 (Phase 2.4, Phase A+C 升级 + P1-3)
     * <p>
     * 对排序后的推荐列表做行业去重:
     * 1. 根据行业动量动态调整同类上限(强势行业放宽,弱势行业收紧)
     * 2. 引入行业相关性分组，高相关行业共享分散化名额
     * 3. 超出部分延后处理（保留但降权标记）
     * 4. 重新排名
     *
     * @param industryMomentumMap 行业动量映射(用于动态上限)
     */
    List<StockRecommendation> diversify(List<StockRecommendation> recommendations,
                                                Map<String, IndustryMomentum> industryMomentumMap) {
        Map<String, Integer> groupCount = new LinkedHashMap<>();  // P1-3: 按相关组计数
        List<StockRecommendation> diversified = new ArrayList<>();
        List<StockRecommendation> excess = new ArrayList<>();

        for (StockRecommendation rec : recommendations) {
            String industry = rec.getIndustry() != null ? rec.getIndustry() : "UNKNOWN";
            String group = getCorrGroup(industry);  // P1-3: 获取所属相关组
            rec.setCorrGroup(group);  // 瞬态字段，供前端展示
            int count = groupCount.getOrDefault(group, 0);

            // 动态上限: 优先使用行业动量中的限制, 回退到默认3
            int limit = MAX_SAME_INDUSTRY;
            if (industryMomentumMap != null) {
                IndustryMomentum im = industryMomentumMap.get(industry);
                if (im != null) {
                    limit = im.industryDiversifyLimit;
                }
            }

            if (count < limit) {
                diversified.add(rec);
                groupCount.put(group, count + 1);  // P1-3: 按组计数
            } else {
                rec.setDiversificationDemoted(true);  // 标记降权
                excess.add(rec);
            }
        }

        // 超额股票追加到末尾
        diversified.addAll(excess);

        // 重新排名
        for (int i = 0; i < diversified.size(); i++) {
            diversified.get(i).setRankNum(i + 1);
        }

        int removed = excess.size();
        if (removed > 0) {
            log.info("[Recommendation] 行业分散化(动态+相关性分组): 移动{}只超额股票到末尾", removed);
            // 打印各组限制
            Map<String, Integer> finalCnt = new LinkedHashMap<>();
            for (StockRecommendation r : diversified) {
                String ind = r.getIndustry() != null ? r.getIndustry() : "UNKNOWN";
                String group = getCorrGroup(ind);
                finalCnt.merge(group, 1, Integer::sum);
            }
            finalCnt.forEach((grp, cnt) -> {
                // 找到该组的代表行业
                String repIndustry = grp;
                // P2-8: 优先查动态分组，回退到静态
                List<List<String>> allGroups = null;
                try { allGroups = dynamicIndustryCorrService.getDynamicCorrGroups(); } catch (Exception ignored) {
                    log.error("[IndustryRotationService] 捕获到未处理异常", ignored);
                }
                if (allGroups == null) allGroups = INDUSTRY_CORR_GROUPS;
                for (List<String> g : allGroups) {
                    if (g.getFirst().equals(grp)) {
                        repIndustry = String.join(",", g);
                        break;
                    }
                }
                IndustryMomentum im = industryMomentumMap != null ? industryMomentumMap.get(grp) : null;
                int limit = im != null ? im.industryDiversifyLimit : MAX_SAME_INDUSTRY;
                log.info("  组[{}]: 入选{}只, 上限={}, 代表行业={}",
                        grp, cnt, limit, repIndustry);
            });
        }

        return diversified;
    }

    /**
     * 计算行业动量 (Phase A+C)
     * <p>
     * 复用 AnalysisService.getSectorRanking() 的行业涨跌幅数据,
     * 结合沪深300涨跌幅计算相对强度, 用于:
     * 方案A: 动态行业分散化限制
     * 方案C: 因子融合加分
     *
     * @param regime 市场环境(含沪深300涨跌幅)
     * @return 行业 → IndustryMomentum 映射
     */
    Map<String, IndustryMomentum> computeIndustryMomentum(RegimeInfo regime, LocalDate date) {
        Map<String, IndustryMomentum> result = new LinkedHashMap<>();
        try {
            // ⚠️ 统一使用 MySQL stock_info 作为行业数据源（与 buildCodeToIndustryMap 保持一致）
            // 避免 CH stock_info 与 MySQL stock_info 行业名称不一致导致匹配失败
            // 优先使用指定日期，若为 null 则取最新交易日
            String targetDate;
            if (date != null) {
                targetDate = date.toString();
            } else {
                targetDate = clickHouseStockService.queryForString(
                        "SELECT MAX(trade_date) FROM stock.stock_daily FINAL");
            }
            log.info("[Recommendation] 行业动量: 使用日期={}", targetDate);
            if (targetDate == null || targetDate.isEmpty()) {
                log.warn("[Recommendation] 无法获取交易日，跳过行业动量计算");
                return result;
            }

            // Step 1: 从 ClickHouse 获取当日所有股票的涨跌幅
            // P2-1: 同时获取近20日涨跌幅用于行业动量计算
            LocalDate lookbackStart = date.minusDays(25);
            String sql = String.format("""
                    SELECT code, change_percent, trade_date
                    FROM stock.stock_daily FINAL
                    WHERE trade_date >= '%s' AND trade_date <= '%s'
                    """, lookbackStart, targetDate);
            List<Map<String, Object>> rows = clickHouseStockService.queryForList(sql);
            log.info("[Recommendation] 行业动量: CH stock_daily 返回 {} 行(含20日回溯)", rows != null ? rows.size() : -1);
            if (rows == null || rows.isEmpty()) {
                log.warn("[Recommendation] 行业排行数据为空");
                return result;
            }

            // Step 2: 从 MySQL 获取全量股票行业映射（与 buildCodeToIndustryMap 同源）
            List<StockInfo> allStockInfos = stockInfoMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                            .isNotNull(StockInfo::getIndustry)
                            .ne(StockInfo::getIndustry, ""));
            Map<String, String> codeToIndustry = allStockInfos.stream()
                    .filter(i -> i.getCode() != null && i.getIndustry() != null)
                    .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getIndustry, (a, b) -> a));
            log.info("[Recommendation] 行业动量: MySQL stock_info 返回 {} 条行业映射", codeToIndustry.size());

            // Step 3: 按行业汇总涨跌幅（分离当日/近5日数据，解决单日噪声）
            Map<String, List<Double>> industryDailyChanges = new LinkedHashMap<>();  // 目标日期
            Map<String, List<Double>> industryRecentChanges = new LinkedHashMap<>(); // 近5日（平滑排名）

            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                Object chgObj = row.get("change_percent");
                Object tdObj = row.get("trade_date");
                if (code == null || chgObj == null || tdObj == null) continue;
                String industry = codeToIndustry.get(code);
                if (industry == null) continue;
                double chg = chgObj instanceof Number ? ((Number) chgObj).doubleValue() : 0;
                String td = tdObj.toString();

                // 近5日数据用于平滑行业排名（避免单日极端值导致排名跳变）
                try {
                    long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.parse(td), LocalDate.parse(targetDate));
                    if (daysDiff >= 0 && daysDiff <= 4) {
                        industryRecentChanges.computeIfAbsent(industry, k -> new ArrayList<>()).add(chg);
                    }
                } catch (Exception ignored) {
                    log.error("[IndustryRotationService] 捕获到未处理异常", ignored);
                }

                // 仅目标日期用于精确当日数据
                if (td.equals(targetDate)) {
                    industryDailyChanges.computeIfAbsent(industry, k -> new ArrayList<>()).add(chg);
                }
            }

            if (industryRecentChanges.isEmpty()) {
                log.warn("[Recommendation] 行业涨跌幅汇总为空");
                return result;
            }

            // Step 4: 计算各行业平均涨跌幅（使用近5日平滑，避免单日噪声导致排名跳变）
            List<Double> allChangePcts = new ArrayList<>();
            List<Map<String, Object>> industryList = new ArrayList<>();
            for (Map.Entry<String, List<Double>> entry : industryRecentChanges.entrySet()) {
                String industry = entry.getKey();
                List<Double> changes = entry.getValue();
                if (changes.isEmpty()) continue;
                double avgChg = changes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("industry", industry);
                m.put("avgChangePct", avgChg);
                m.put("sampleCount", changes.size());
                allChangePcts.add(avgChg);
                industryList.add(m);
            }

            if (allChangePcts.isEmpty()) {
                log.warn("[Recommendation] 行业涨跌幅全部为空");
                return result;
            }

            // 打印前 3 个行业用于调试
            for (int i = 0; i < Math.min(3, industryList.size()); i++) {
                Map<String, Object> m = industryList.get(i);
                log.info("[Recommendation]   raw[{}] = {} avgChangePct={} sampleCount={}",
                        i, m.get("industry"), m.get("avgChangePct"), m.get("sampleCount"));
            }

            // 计算 z-score
            double mean = allChangePcts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = allChangePcts.stream()
                    .mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(1.0);
            double std = Math.sqrt(variance);
            if (std < 0.001) std = 0.5;

            double indexPct = regime.indexChangePct != null ? regime.indexChangePct : 0;

            for (Map<String, Object> m : industryList) {
                String industry = (String) m.get("industry");
                if (industry == null || industry.isEmpty()) continue;

                double avgChg = m.get("avgChangePct") instanceof Number
                        ? ((Number) m.get("avgChangePct")).doubleValue() : 0;
                double zScore = (avgChg - mean) / std;
                double marketRelStrength = avgChg - indexPct;

                IndustryMomentum im = new IndustryMomentum();
                im.industry = industry;
                im.avgChangePct = avgChg;
                im.relativeStrength = Math.max(-3.0, Math.min(3.0, zScore));

                // 方案A: 动态行业分散化上限
                if (zScore > 0.6) im.industryDiversifyLimit = 6;
                else if (zScore > 0.3) im.industryDiversifyLimit = 4;
                else if (zScore > -0.3) im.industryDiversifyLimit = 3;
                else if (zScore > -0.6) im.industryDiversifyLimit = 2;
                else im.industryDiversifyLimit = 1;

                // 方案C: 因子融合加分
                if (marketRelStrength > 0.5) im.fusionBonus = 0.06;
                else if (marketRelStrength > 0.2) im.fusionBonus = 0.03;
                else if (marketRelStrength > -0.2) im.fusionBonus = 0.0;
                else if (marketRelStrength > -0.5) im.fusionBonus = -0.03;
                else im.fusionBonus = -0.06;

                // Phase A: industry-level Regime
                im.industryRegime = detectIndustryRegime(industry, date, im);
                if ("BULL".equals(im.industryRegime)) {
                    im.industryDiversifyLimit = Math.min(6, im.industryDiversifyLimit + 1);
                } else if ("BEAR".equals(im.industryRegime)) {
                    im.industryDiversifyLimit = Math.max(1, im.industryDiversifyLimit - 1);
                }

                result.put(industry, im);
            }

            log.info("[Recommendation] 行业动量计算完成: {}个行业, 指数涨跌={}%, 均值={}%, 标准差={}%",
                    result.size(), String.format("%.2f", indexPct),
                    String.format("%.2f", mean), String.format("%.2f", std));

            // Top/Bottom 5
            List<IndustryMomentum> sorted = new ArrayList<>(result.values());
            sorted.sort((a, b) -> Double.compare(b.relativeStrength, a.relativeStrength));
            StringBuilder sb = new StringBuilder("强势行业: ");
            for (int i = 0; i < Math.min(5, sorted.size()); i++) {
                IndustryMomentum im = sorted.get(i);
                sb.append(String.format("%s=%.2f%%(limit=%d) ", im.industry, im.avgChangePct, im.industryDiversifyLimit));
            }
            sb.append("| 弱势行业: ");
            for (int i = Math.max(0, sorted.size() - 5); i < sorted.size(); i++) {
                IndustryMomentum im = sorted.get(i);
                sb.append(String.format("%s=%.2f%%(limit=%d) ", im.industry, im.avgChangePct, im.industryDiversifyLimit));
            }
            log.info("[Recommendation] {}", sb);

            // ── P2-1: 行业20日动量增强 ──
            // 用已获取的20日数据计算每个行业的累计动量和动量趋势
            Map<String, List<Double>> industryDailyAvg = new LinkedHashMap<>();
            Map<String, Object> dateObj2 = rows.stream().findFirst().orElse(null);
            if (dateObj2 != null && dateObj2.containsKey("trade_date")) {
                // 按日期×行业汇总平均涨跌幅
                Map<String, Map<String, List<Double>>> dateIndustryChanges = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    String code = (String) row.get("code");
                    Object chgObj = row.get("change_percent");
                    Object tdObj = row.get("trade_date");
                    if (code == null || chgObj == null || tdObj == null) continue;
                    String industry = codeToIndustry.get(code);
                    if (industry == null) continue;
                    String td = tdObj.toString();
                    double chg = chgObj instanceof Number ? ((Number) chgObj).doubleValue() : 0;
                    dateIndustryChanges
                            .computeIfAbsent(td, k -> new LinkedHashMap<>())
                            .computeIfAbsent(industry, k -> new ArrayList<>())
                            .add(chg);
                }
                // 计算每个行业每天的均值
                for (Map.Entry<String, Map<String, List<Double>>> dateEntry : dateIndustryChanges.entrySet()) {
                    for (Map.Entry<String, List<Double>> indEntry : dateEntry.getValue().entrySet()) {
                        double dailyAvg = indEntry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        industryDailyAvg
                                .computeIfAbsent(indEntry.getKey(), k -> new ArrayList<>())
                                .add(dailyAvg);
                    }
                }
            }

            // 计算动量评分和趋势
            for (Map.Entry<String, IndustryMomentum> entry : result.entrySet()) {
                String industry = entry.getKey();
                IndustryMomentum im = entry.getValue();
                List<Double> dailyAvgs = industryDailyAvg.get(industry);

                if (dailyAvgs != null && dailyAvgs.size() >= 5) {
                    // 20日动量：累计涨跌幅
                    double cumReturn = 1.0;
                    for (double d : dailyAvgs) {
                        cumReturn *= (1 + d / 100.0);
                    }
                    im.momentum20d = (cumReturn - 1.0) * 100.0;

                    // 动量趋势：比较前10日和后10日
                    int half = dailyAvgs.size() / 2;
                    double firstHalf = dailyAvgs.subList(0, half).stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double secondHalf = dailyAvgs.subList(half, dailyAvgs.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double diff = secondHalf - firstHalf;
                    im.momentumTrend = diff > 0.1 ? "ACCELERATING"
                            : diff < -0.1 ? "DECELERATING" : "FLAT";

                    // 动量综合评分（0~1）：结合当日z-score和20日动量
                    double zScoreNorm = (im.relativeStrength + 3.0) / 6.0; // 归一化到0~1
                    double momentumNorm = Math.max(0, Math.min(1, (im.momentum20d + 10) / 20.0)); // 归一化
                    im.momentumScore = 0.4 * zScoreNorm + 0.6 * momentumNorm;
                } else {
                    im.momentum20d = im.avgChangePct;
                    im.momentumTrend = "FLAT";
                    im.momentumScore = (im.relativeStrength + 3.0) / 6.0;
                }
            }

            // P2-1后：使用momentumScore重新校准fusionBonus
            // 牛市：高动量行业给奖励（动量延续）；熊市/回调：反转——高动量行业惩罚(追高易补跌)，低动量奖励(均值回归)
            boolean bearMarket = regime != null && "BEAR".equals(regime.regime);
            for (IndustryMomentum im : result.values()) {
                if (bearMarket) {
                    // 熊市反转逻辑：低动量奖励、高动量惩罚
                    if (im.momentumScore > 0.7) im.fusionBonus = -0.06;
                    else if (im.momentumScore > 0.55) im.fusionBonus = -0.03;
                    else if (im.momentumScore > 0.45) im.fusionBonus = 0.0;
                    else if (im.momentumScore > 0.3) im.fusionBonus = 0.03;
                    else im.fusionBonus = 0.06;
                } else {
                    if (im.momentumScore > 0.7) im.fusionBonus = 0.06;
                    else if (im.momentumScore > 0.55) im.fusionBonus = 0.03;
                    else if (im.momentumScore > 0.45) im.fusionBonus = 0.0;
                    else if (im.momentumScore > 0.3) im.fusionBonus = -0.03;
                    else im.fusionBonus = -0.06;
                }
            }
            log.info("[Recommendation] P2-1 行业动量增强完成，fusionBonus已按momentumScore校准 (bear={})", bearMarket);

        } catch (Exception e) {
            log.error("[Recommendation] 行业动量计算异常: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 检测单个行业的 Regime（三维：趋势 + ATR波动率 + 简化宽度）
     * <p>
     * 使用申万一级行业指数 K 线数据（index_daily 表），计算与市场级 detectRegime()
     * 相同三个维度的行业市场环境：
     * 1. 趋势：行业指数 close > MA20 > MA60 → 牛市；close < MA20 < MA60 → 熊市
     * 2. 波动率: ATR(20) / close 历史分位数 → HIGH/MEDIUM/LOW
     * 3. 行业宽度（简化）：行业内上涨股票占比 > 60% = GOOD, < 40% = POOR
     *
     * @param industryName 行业名（stock_info.industry 值）
     * @param date         评估日期
     * @param im           行业动量数据（含 avgChangePct 等信息）
     * @return Regime 字符串: BULL / BEAR / SIDEWAYS
     */
    String detectIndustryRegime(String industryName, LocalDate date, IndustryMomentum im) {
        // 1. 查找申万代码（优先直接匹配；二级行业通过 SW2_TO_SW1 归约到一级）
        String swCode = INDUSTRY_TO_SW_CODE.get(industryName);
        if (swCode == null) {
            // 二级行业 → 归约到一级
            String sw1 = SW2_TO_SW1.get(industryName);
            if (sw1 != null) {
                swCode = INDUSTRY_TO_SW_CODE.get(sw1);
            }
        }
        if (swCode == null) {
            log.debug("[Recommendation] 行业[{}]无申万代码映射，默认 SIDEWAYS", industryName);
            return "SIDEWAYS";
        }

        // 2. 获取行业指数 K 线（最近 250 天）
        LocalDate startDate = date.minusDays(250);
        try {
            List<StockDaily> bars = clickHouseStockService.getIndexDaily(swCode, startDate, date);
            if (bars == null || bars.size() < 60) {
                log.debug("[Recommendation] 行业[{}]({}) 数据不足({}条)，默认 SIDEWAYS",
                        industryName, swCode, bars != null ? bars.size() : 0);
                return "SIDEWAYS";
            }

            // 提取 close / high / low 序列
            List<Double> closes = bars.stream()
                    .map(b -> b.getClosePrice().doubleValue())
                    .collect(Collectors.toList());
            List<Double> highs = bars.stream()
                    .map(b -> b.getHighPrice().doubleValue())
                    .collect(Collectors.toList());
            List<Double> lows = bars.stream()
                    .map(b -> b.getLowPrice().doubleValue())
                    .collect(Collectors.toList());

            // ── 维度1: 趋势 ──
            double latestClose = closes.getLast();
            double ma20 = RecommendationMath.avg(closes, 20);
            double ma60 = RecommendationMath.avg(closes, 60);
            // 引入0.5%缓冲带，避免单日噪声导致Regime频繁切换
            double buffer = latestClose * 0.005;
            boolean bullishTrend = latestClose > ma20 + buffer && ma20 > ma60 + buffer;
            boolean bearishTrend = latestClose < ma20 - buffer && ma20 < ma60 - buffer;

            // ── 维度2: ATR 波动率 ──
            double atr20 = RecommendationMath.calcATR(highs, lows, closes, 20);
            // 计算 ATR 相对值: ATR / close * 100 (%)
            double atrPct = atr20 / latestClose * 100;
            String volRegime;
            if (atrPct > 3.0) {
                volRegime = "HIGH";
            } else if (atrPct < 1.5) {
                volRegime = "LOW";
            } else {
                volRegime = "MEDIUM";
            }

            // ── 维度3: 行业宽度（简化：用行业涨跌幅方向作代理） ──
            // 行业 avgChangePct > 0 视为行业宽度好
            String breadthQuality = "NEUTRAL";
            if (im != null && im.avgChangePct > 0.3) {
                breadthQuality = "GOOD";
            } else if (im != null && im.avgChangePct < -0.3) {
                breadthQuality = "POOR";
            }

            // ── 综合判断 ──
            if (bullishTrend) {
                boolean confirmed = "LOW".equals(volRegime) || "GOOD".equals(breadthQuality);
                return confirmed ? "BULL" : "SIDEWAYS";
            } else if (bearishTrend) {
                boolean confirmed = "HIGH".equals(volRegime) || "POOR".equals(breadthQuality);
                return confirmed ? "BEAR" : "SIDEWAYS";
            } else {
                return "SIDEWAYS";
            }
        } catch (Exception e) {
            log.warn("[Recommendation] 行业[{}]({}) Regime检测失败: {}", industryName, swCode, e.getMessage());
            return "SIDEWAYS";
        }
    }

    /**
     * 批量查询股票行业映射 (Phase A+C 辅助)
     */
    Map<String, String> buildCodeToIndustryMap(List<ScreenResult.StockScore> candidates) {
        Set<String> pureCodes = candidates.stream()
                .map(s -> RecommendationMath.stripSuffix(s.getSymbol()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (pureCodes.isEmpty()) return Map.of();

        List<StockInfo> infos = stockInfoMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockInfo>()
                        .in(StockInfo::getCode, pureCodes));
        return infos.stream()
                .filter(i -> i.getCode() != null && i.getIndustry() != null)
                .collect(Collectors.toMap(StockInfo::getCode, StockInfo::getIndustry, (a, b) -> a));
    }
}
