package com.quant.platform.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.regime.MarketRegimeCalendarService;
import com.quant.platform.factor.service.FactorAnalysisService;
import com.quant.platform.factor.service.FactorCorrelationService;
import com.quant.platform.factor.service.QuarterlyFactorAnalysisService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.screen.dto.ScreenRequest;
import com.quant.platform.strategy.domain.StrategyDefinition;
import com.quant.platform.strategy.mapper.StrategyDefinitionMapper;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 因子权重解析：动态 IC 加权、权重上限、拥挤度过滤、季度/一致性 IC 校正。
 * <p>由 RecommendationService 拆出（Phase4），方法体逐字迁移，行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorWeightResolver {

    private final MarketDataService marketDataService;
    private final StrategyDefinitionMapper strategyDefinitionMapper;
    private final ObjectMapper objectMapper;
    private final FactorIcService factorIcService;
    private final FactorAnalysisService factorAnalysisService;
    private final FactorCorrelationService factorCorrelationService;
    private final QuarterlyFactorAnalysisService quarterlyFactorAnalysisService;
    private final com.quant.platform.factor.ic.mapper.FactorIcRecordMapper factorIcRecordMapper;
    private final com.quant.platform.factor.service.FactorMetaCacheService factorMetaCache;
    private final com.quant.platform.factor.mapper.FactorDefinitionMapper factorDefinitionMapper;

    /** 可选组件：regime 日历 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MarketRegimeCalendarService regimeCalendarService;

    /**
     * P1: 默认IR预筛选阈值
     * IR = |IC均值| / IC标准差，衡量因子信号的稳定性
     * 0.1: 剔除IC波动过大（不稳定）的噪声因子，保留信号稳定的因子
     * 比IC绝对值阈值更合理：IC高但波动大的因子（如VOL20 IC=0.063但IR=0.19）仍保留，
     * 而IC低且稳定的因子（如VAL_FCF_YIELD IC=0.032但IR=1.16）也不会被误杀
     */
    private static final double DEFAULT_IR_THRESHOLD = 0.1;
    /**
     * P2: 默认半衰期（交易日）
     */
    private static final int DEFAULT_HALFLIFE_DAYS = 20;
    /**
     * P3: ICW模式单因子权重上限（占比）
     * 防止强IC因子（如SIZE IC=0.052）主导排名导致策略趋同
     * 超出部分按比例重新分配给其他因子
     */
    private static final double MAX_ICW_WEIGHT_PCT = 0.35;
    /**
     * P1-4: 噪声因子|IC|阈值
     * |IC| < 此值的因子直接剔除而非反转——噪声因子的反转≠有效信号
     * 典型噪声因子: MOM5(IC=-0.03), VOLUME_RATIO(IC=-0.033) 等
     */
    private static final double NOISE_FACTOR_IC_THRESHOLD = 0.015;
    /**
     * 优化X：强制保留因子白名单。这些因子即使与簇内其他因子高相关（触发拥挤度剔除），
     * 也不被剔除，确保其权重（尤其是高IC真alpha如EARNINGS_SURPRISE）在组合中生效。
     * 背景：EARNINGS_SURPRISE 与 SIZE 相关性高(corr≥0.84)被拥挤度剔除，且无IC历史时
     * 不在 icMap 中永不当代表，导致在ICW管线被CROWDING_DROPPED，权重完全失效。
     */
    private static final Set<String> FORCE_KEEP_FACTORS = Set.of("EARNINGS_SURPRISE");
    /**
     * 沪深300指数代码
     */
    private static final String SSE300_CODE = "000300";

    /**
     * P1+P2: 动态调整因子权重（基于IC历史表现 + 衰减加权 + 预筛选 + 方向对齐）
     * <p>
     * 规则：
     * - 使用 FactorAnalysisService.quickFactorIcSnapshot() 计算衰减加权IC
     * - 预筛选：IR &lt; irThreshold 的因子被剔除（信号不稳定）
     * - 方向对齐：负IC因子自动反转direction，使用|IC|参与加权
     * - 权重分配（由 weightMode 决定）：
     * EQW  = 等权分配
     * ICW  = 按|IC|比例分配
     * OPT  = 按 1/σ²(IC) 分配（稳定性越高权重越大）
     * STATIC = 不调整（由调用方处理，不会进入此方法）
     *
     * @param factors     原始因子配置
     * @param date        选股日期
     * @param weightMode  权重模式（EQW/ICW/OPT）
     * @param diagnostics 输出参数，因子诊断信息
     * @return 调整后的因子配置
     */
    List<ScreenRequest.FactorWeight> applyDynamicFactorWeights(
            List<ScreenRequest.FactorWeight> factors, LocalDate date,
            String weightMode, List<RecommendationService.FactorDiagnostic> diagnostics) {
        if (factors == null || factors.isEmpty()) return factors;

        List<String> factorCodes = factors.stream()
                .map(ScreenRequest.FactorWeight::getFactorCode)
                .collect(Collectors.toList());

        // 当前 regime：用于白名单 regime 守卫（SIDEWAYS 下不再强制保留 EARNINGS_SURPRISE），
        // 与 ICW 过滤使用同一 regime 来源，保持一致
        String currentRegime = (regimeCalendarService != null && date != null)
                ? regimeCalendarService.getRegime(date) : "SIDEWAYS";

        // Resolve reference date
        LocalDate refDate = date != null ? date : LocalDate.now();
        LocalDate effectiveIcDate = factorIcService.getLatestCommonIcDate(factorCodes);
        if (effectiveIcDate != null && effectiveIcDate.isBefore(refDate)) {
            refDate = effectiveIcDate;
        }

        // P1+P2: 使用衰减加权IC快照
        // P2: 动态半衰期（基于市场波动率分位数自适应调整）
        int halflife = computeAdaptiveHalflife(refDate);
        Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots =
                factorAnalysisService.quickFactorIcSnapshot(
                        factorCodes, refDate, 60, DEFAULT_IR_THRESHOLD, halflife);

        // P3: 因子拥挤度检测与去重
        Set<String> crowdingDropped = applyCrowdingFilter(factorCodes, refDate, snapshots);
        // P4: 财务因子季频IC校正（返回校正数量，snapshots 被原地修改）
        int quarterlyCorrected = applyQuarterlyIcCorrection(factorCodes, refDate, snapshots);
        // P5: IC季度一致性校验（方向不稳定因子降权或剔除）
        int consistencyDropped = applyIcConsistencyCheck(factorCodes, refDate, snapshots);

        log.info("[DynamicWeight] IC快照完成: mode={}, {}个因子, IR阈值={}, 半衰={}天, 保留{}个 (拥挤度剔除{}, 一致性剔除{})",
                weightMode, factorCodes.size(), DEFAULT_IR_THRESHOLD, halflife,
                snapshots.values().stream().filter(s -> "KEPT".equals(s.status)).count(),
                crowdingDropped.size(), consistencyDropped);

        // 筛选保留的因子
        List<FactorAnalysisService.FactorIcSnapshot> keptSnapshots = snapshots.values().stream()
                .filter(s -> "KEPT".equals(s.status))
                .toList();

        // 计算|IC|总和（用于ICW权重分配）
        double sumAbsIc = keptSnapshots.stream()
                .mapToDouble(FactorAnalysisService.FactorIcSnapshot::absIc)
                .sum();

        // 计算逆方差总和（用于OPT权重分配）
        double optSum = keptSnapshots.stream()
                .mapToDouble(s -> 1.0 / Math.max(s.icStd * s.icStd, 1e-9))
                .sum();

        // 构建原始因子查找表
        Map<String, ScreenRequest.FactorWeight> originalMap = new LinkedHashMap<>();
        for (ScreenRequest.FactorWeight fw : factors) {
            originalMap.put(fw.getFactorCode(), fw);
        }

        List<ScreenRequest.FactorWeight> adjusted = new ArrayList<>();
        int keptCount = 0, droppedCount = 0, noDataCount = 0;

        for (ScreenRequest.FactorWeight fw : factors) {
            String fc = fw.getFactorCode();
            FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(fc);
            double originalWeight = fw.getWeight();
            int originalDirection = fw.getDirection();

            ScreenRequest.FactorWeight adjustedFw = new ScreenRequest.FactorWeight();
            adjustedFw.setFactorCode(fc);
            adjustedFw.setFilterOp(fw.getFilterOp());
            adjustedFw.setFilterValue(fw.getFilterValue());

            RecommendationService.FactorDiagnostic diag = new RecommendationService.FactorDiagnostic();
            diag.factorCode = fc;
            diag.originalWeight = originalWeight;

            // 优化X：白名单因子强制保留，权重=配置权重，不受拥挤度/噪声/无IC等任何剔除影响
            // 白名单 regime 守卫：SIDEWAYS 体制下不再强制保留，交由 regime-aware ICW 决定权重。
            // 否则 EARNINGS_SURPRISE 在震荡市 ~0/反向 IC 被强制 35% 权重，正是 SIDEWAYS 退步根因。
            if (FORCE_KEEP_FACTORS.contains(fc) && !"SIDEWAYS".equals(currentRegime)) {
                adjustedFw.setWeight(originalWeight);
                adjustedFw.setDirection(originalDirection);
                diag.action = "FORCE_KEEP";
                diag.adjustedWeight = originalWeight;
                diag.icMean = snap != null ? snap.icMean : 0;
                diag.reason = "白名单强制保留（权重=配置权重" + originalWeight + "）";
                log.info("[DynamicWeight] 因子 {} 白名单强制保留, 权重={}", fc, originalWeight);
                adjusted.add(adjustedFw);
                diagnostics.add(diag);
                keptCount++;
                continue;
            }

            if (snap == null || "NO_DATA".equals(snap.status)) {
                // 无IC数据，保持原样
                adjustedFw.setWeight(originalWeight);
                adjustedFw.setDirection(originalDirection);
                diag.action = "NO_DATA";
                diag.adjustedWeight = originalWeight;
                diag.icMean = 0;
                diag.reason = "无IC历史数据，保持原始配置";
                log.warn("[DynamicWeight] 因子 {} 无IC历史数据", fc);
                noDataCount++;
            } else if ("DROPPED".equals(snap.status)) {
                // IR < 阈值，剔除（信号不稳定）
                adjustedFw.setWeight(0.0);
                adjustedFw.setDirection(originalDirection);
                diag.action = "DROPPED";
                diag.icMean = snap.icMean;
                diag.adjustedWeight = 0;
                diag.reason = String.format("IR=%.4f < 阈值%.2f，信号不稳定剔除（IC=%.4f, IC_std=%.4f, 半衰=%d天）",
                        snap.ir, DEFAULT_IR_THRESHOLD, snap.icMean, snap.icStd, halflife);
                log.info("[DynamicWeight] 因子 {} IR={} < {}, 剔除 (IC={}, std={})",
                        fc, String.format("%.4f", snap.ir), DEFAULT_IR_THRESHOLD,
                        String.format("%.4f", snap.icMean), String.format("%.4f", snap.icStd));
                droppedCount++;
            } else if ("CROWDING_DROPPED".equals(snap.status) || "CONSISTENCY_DROPPED".equals(snap.status)) {
                // P3: 因子拥挤度剔除 / P5: IC一致性剔除
                adjustedFw.setWeight(0.0);
                adjustedFw.setDirection(originalDirection);
                diag.action = snap.status;
                diag.icMean = snap.icMean;
                diag.adjustedWeight = 0;
                diag.reason = snap.assessment != null ? snap.assessment : "因子被剔除";
                log.info("[DynamicWeight] 因子 {} {}: {}", fc, snap.status, diag.reason);
                droppedCount++;
            } else {
                // KEPT: 方向对齐 + |IC|加权
                double absIc = snap.absIc();

                // P1-4: 噪声因子剔除 —— |IC| < 阈值直接丢弃，不反转
                if (absIc < NOISE_FACTOR_IC_THRESHOLD) {
                    adjustedFw.setWeight(0.0);
                    adjustedFw.setDirection(originalDirection);
                    diag.action = "NOISE_DROPPED";
                    diag.icMean = snap.icMean;
                    diag.adjustedWeight = 0;
                    diag.reason = String.format(
                            "|IC|=%.4f < 噪声阈值%.3f，信号过弱剔除（IC=%.4f, IR=%.4f, 半衰=%d天）",
                            absIc, NOISE_FACTOR_IC_THRESHOLD, snap.icMean, snap.ir, halflife);
                    log.info("[DynamicWeight] 因子 {} |IC|={} < {}, 噪声剔除 (IC={}, IR={})",
                            fc, String.format("%.4f", absIc), NOISE_FACTOR_IC_THRESHOLD,
                            String.format("%.4f", snap.icMean), String.format("%.4f", snap.ir));
                    droppedCount++;
                    diagnostics.add(diag);
                    adjusted.add(adjustedFw);
                    continue;
                }

                // 方向对齐：负IC → 反转direction
                int alignedDirection = snap.icSign < 0 ? -originalDirection : originalDirection;
                adjustedFw.setDirection(alignedDirection);

                // 权重按 weightMode 分配
                double newWeight;
                String action = switch (weightMode) {
                    case "EQW" -> {
                        // 等权：保留的因子平均分配
                        newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        yield "KEPT_EQW";
                    }
                    case "OPT" -> {
                        // 逆方差：按 1/σ²(IC) 分配（稳定性越高权重越大）
                        if (optSum > 1e-9) {
                            newWeight = originalWeight * (1.0 / Math.max(snap.icStd * snap.icStd, 1e-9) / optSum);
                        } else {
                            newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        }
                        yield "KEPT_OPT";
                    }
                    default -> {
                        // ICW: |IC|加权
                        if (sumAbsIc > 1e-9) {
                            newWeight = originalWeight * (absIc / sumAbsIc);
                        } else {
                            newWeight = originalWeight / Math.max(keptSnapshots.size(), 1);
                        }
                        yield "KEPT_ICW";
                    }
                };
                adjustedFw.setWeight(newWeight);
                diag.action = action;

                diag.icMean = snap.icMean;
                diag.adjustedWeight = newWeight;
                diag.reason = String.format(
                        "%s: IC=%.4f (半衰%d天), |IC|=%.4f, 方向%s, 新权重=%.4f",
                        weightMode, snap.icMean, halflife, absIc,
                        snap.icSign < 0 ? "↓取反(对齐)" : "↑正向",
                        newWeight);
                log.info("[DynamicWeight] 因子 {} {} IC={} (|IC|={}) 方向={} 权重: {}->{}",
                        fc, weightMode, snap.icMean, absIc,
                        snap.icSign < 0 ? "取反" : "正向",
                        originalWeight, newWeight);
                keptCount++;
            }

            diagnostics.add(diag);
            adjusted.add(adjustedFw);
        }

        // P6: ICW权重上限——防止单因子主导排名导致策略趋同
        if ("ICW".equals(weightMode)) {
            applyWeightCap(adjusted, MAX_ICW_WEIGHT_PCT);
        }

        log.info("[DynamicWeight] 完成: mode={}, 保留{}/剔除{}/无数据{}, |IC|和={}, 半衰={}天",
                weightMode, keptCount, droppedCount, noDataCount, sumAbsIc, halflife);

        return adjusted;
    }

    /**
     * P6: ICW权重上限——迭代式cap & redistribute
     * 1. 计算各因子权重占比（相对于有效因子权重总和）
     * 2. 超过maxPct的因子截断为maxPct，溢出部分按比例分配给未截断因子
     * 3. 重复直到所有因子占比≤maxPct（最多N-1轮）
     */
    void applyWeightCap(List<ScreenRequest.FactorWeight> adjusted, double maxPct) {
        List<ScreenRequest.FactorWeight> active = new ArrayList<>();
        for (ScreenRequest.FactorWeight fw : adjusted) {
            if (fw.getWeight() > 0) active.add(fw);
        }
        if (active.size() <= 1) return;

        double totalWeight = active.stream().mapToDouble(ScreenRequest.FactorWeight::getWeight).sum();
        if (totalWeight <= 0) return;

        Set<String> capped = new HashSet<>();
        for (int iter = 0; iter < active.size() - 1; iter++) {
            // 1. 找出超出上限的因子
            List<ScreenRequest.FactorWeight> overLimit = new ArrayList<>();
            double uncappedSum = 0;
            for (ScreenRequest.FactorWeight fw : active) {
                if (capped.contains(fw.getFactorCode())) continue;
                double pct = fw.getWeight() / totalWeight;
                if (pct > maxPct) {
                    overLimit.add(fw);
                } else {
                    uncappedSum += fw.getWeight();
                }
            }
            if (overLimit.isEmpty()) break;

            // 2. 截断超限因子
            for (ScreenRequest.FactorWeight fw : overLimit) {
                double oldW = fw.getWeight();
                double oldPct = oldW / totalWeight;
                fw.setWeight(totalWeight * maxPct);
                capped.add(fw.getFactorCode());
                log.info("[WeightCap] 因子 {} 权重 {}->{} (占比 {}->{})",
                        fw.getFactorCode(),
                        String.format("%.4f", oldW),
                        String.format("%.4f", fw.getWeight()),
                        String.format("%.0f%%", oldPct * 100),
                        String.format("%.0f%%", maxPct * 100));
            }

            // 3. 将溢出权重按比例分配给未截断因子
            double cappedTotal = 0;
            for (ScreenRequest.FactorWeight fw : active) {
                if (capped.contains(fw.getFactorCode())) {
                    cappedTotal += fw.getWeight();
                }
            }
            double uncappedTarget = totalWeight - cappedTotal;
            if (uncappedSum > 0 && uncappedTarget > 0) {
                double scale = uncappedTarget / uncappedSum;
                for (ScreenRequest.FactorWeight fw : active) {
                    if (!capped.contains(fw.getFactorCode())) {
                        double oldW = fw.getWeight();
                        fw.setWeight(oldW * scale);
                        if (Math.abs(fw.getWeight() - oldW) > 0.001) {
                            log.debug("[WeightCap] 因子 {} 重分配 {}->{}",
                                    fw.getFactorCode(),
                                    String.format("%.4f", oldW),
                                    String.format("%.4f", fw.getWeight()));
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析策略级 weightMode（优先级：请求参数 > 策略配置 > 默认ICW）
     * 支持：EQW(等权) / ICW(IC加权) / OPT(逆方差) / STATIC(原始配置不调整)
     */
    String resolveWeightMode(Long strategyId, String requestWeightMode) {
        // 1. 请求显式指定 → 用请求的
        if (requestWeightMode != null && !requestWeightMode.isEmpty()) {
            return requestWeightMode.toUpperCase();
        }
        // 2. 策略配置了 → 用策略的
        if (strategyId != null) {
            try {
                StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
                if (strategy != null && strategy.getFactorConfigJson() != null) {
                    Object raw = objectMapper.readValue(strategy.getFactorConfigJson(), Object.class);
                    if (raw instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) raw;
                        Object wm = map.get("weightMode");
                        if (wm != null && !wm.toString().isEmpty()) {
                            return wm.toString().toUpperCase();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Recommendation] 解析策略weightMode失败: strategyId={} error={}", strategyId, e.getMessage());
            }
        }
        // 3. 默认 ICW
        return "ICW";
    }

    /**
     * 从策略 factorConfigJson 获取因子配置（全部走数据库，无硬编码兜底）
     */
    List<ScreenRequest.FactorWeight> getFactorConfig(Long strategyId) {
        if (strategyId == null) {
            throw new IllegalArgumentException("strategyId 不能为空，因子配置必须从数据库策略中获取");
        }
        StrategyDefinition strategy = strategyDefinitionMapper.selectById(strategyId);
        if (strategy == null) {
            throw new IllegalArgumentException("策略不存在: strategyId=" + strategyId);
        }
        if (strategy.getFactorConfigJson() == null || strategy.getFactorConfigJson().isEmpty()) {
            throw new IllegalStateException("策略[" + strategy.getStrategyName() + "]未配置因子权重(factorConfigJson为空)，请在策略管理中配置");
        }
        try {
            Object raw = objectMapper.readValue(strategy.getFactorConfigJson(), Object.class);
            List<Map<String, Object>> factorConfigs;
            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) raw;
                factorConfigs = list;
            } else if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> factors = (List<Map<String, Object>>) map.get("factors");
                factorConfigs = factors != null ? factors : List.of();
            } else {
                factorConfigs = List.of();
            }

            List<ScreenRequest.FactorWeight> result = new ArrayList<>();
            for (Map<String, Object> cfg : factorConfigs) {
                ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
                Object code = cfg.get("factorCode");
                if (code == null) code = cfg.get("code");
                fw.setFactorCode(code != null ? code.toString() : null);
                Object dir = cfg.get("direction");
                if (dir == null) dir = cfg.get("dir");
                fw.setDirection(dir instanceof Number ? ((Number) dir).intValue() : 1);
                Object weight = cfg.get("weight");
                fw.setWeight(weight instanceof Number ? ((Number) weight).doubleValue() : 1.0);
                Object filterOp = cfg.get("filterOp");
                if (filterOp != null) fw.setFilterOp(filterOp.toString());
                Object filterValue = cfg.get("filterValue");
                if (filterValue instanceof Number) {
                    fw.setFilterValue(((Number) filterValue).doubleValue());
                }
                result.add(fw);
            }

            // P3-11: 过滤 DEGRADED 因子（降级因子不参与选股/推荐）
            Set<String> degradedCodes = new HashSet<>();
            List<com.quant.platform.factor.domain.FactorDefinition> degradedFactors = factorDefinitionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.factor.domain.FactorDefinition>()
                    .eq(com.quant.platform.factor.domain.FactorDefinition::getStatus,
                        com.quant.platform.factor.domain.FactorDefinition.FactorStatus.DEGRADED));
            for (com.quant.platform.factor.domain.FactorDefinition df : degradedFactors) {
                degradedCodes.add(df.getFactorCode());
            }
            if (!degradedCodes.isEmpty()) {
                int before = result.size();
                result.removeIf(fw -> fw.getFactorCode() != null && degradedCodes.contains(fw.getFactorCode()));
                log.warn("[Recommendation] P3-11 过滤DEGRADED因子: {} → {} (排除: {})",
                    before, result.size(), degradedCodes);
            }

            log.info("[Recommendation] 从策略[{}]加载因子配置: {}个因子(已排除DEGRADED)", strategy.getStrategyName(), result.size());
            return result;
        } catch (IllegalArgumentException e) {
            throw e; // 直接抛出业务异常
        } catch (Exception e) {
            throw new IllegalStateException("策略因子配置解析失败 strategyId=" + strategyId + ": " + e.getMessage(), e);
        }
    }

    /**
     * P2: 动态半衰期计算
     * 基于沪深300收益率波动率分位数，调用 FactorAnalysisService.adaptiveHalflife()
     */
    int computeAdaptiveHalflife(LocalDate refDate) {
        try {
            List<com.quant.platform.market.domain.MarketDailyBar> hist =
                    marketDataService.getBarsInRange(SSE300_CODE, refDate.minusDays(60), refDate);
            if (hist == null || hist.size() < 20) {
                log.warn("[DynamicWeight] P2 沪深300历史数据不足({}), 使用默认半衰期{}天",
                        hist == null ? "null" : hist.size(), DEFAULT_HALFLIFE_DAYS);
                return DEFAULT_HALFLIFE_DAYS;
            }
            // 计算20日收益率(seq: close[t]/close[t-1] - 1)
            double[] returns = new double[hist.size() - 1];
            for (int i = 1; i < hist.size(); i++) {
                double prev = hist.get(i - 1).getClose().doubleValue();
                double curr = hist.get(i).getClose().doubleValue();
                returns[i - 1] = (prev > 0) ? (curr / prev - 1) : 0;
            }
            double vol = RecommendationMath.std(returns);
            // 波动率分位数估算（假设市场波动率中值~12%，范围5%~25%）
            double volatilityPercentile = Math.max(0, Math.min(1, (vol - 0.05) / 0.20 + 0.375));
            int halflife = com.quant.platform.factor.service.FactorAnalysisService.adaptiveHalflife(volatilityPercentile);
            log.info("[DynamicWeight] P2 动态半衰期: 20日波动率={}, 分位数~{}, 半衰期={}天",
                    vol, volatilityPercentile, halflife);
            return halflife;
        } catch (Exception e) {
            log.warn("[DynamicWeight] P2 动态半衰期计算失败: {}, 使用默认值", e.getMessage());
            return DEFAULT_HALFLIFE_DAYS;
        }
    }

    /**
     * P3: 因子拥挤度过滤
     * 调用 FactorCorrelationService.detectCrowding()，将冗余因子的 status 设为 CROWDING_DROPPED
     */
    Set<String> applyCrowdingFilter(
            List<String> factorCodes, LocalDate refDate,
            Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots) {
        Set<String> dropped = new HashSet<>();
        try {
            LocalDate startDate = refDate.minusDays(60);
            // 构建 icSnapshot Map（只传 KEPT 因子）
            Map<String, Double> icMap = new HashMap<>();
            for (Map.Entry<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> e : snapshots.entrySet()) {
                if ("KEPT".equals(e.getValue().status)) {
                    icMap.put(e.getKey(), e.getValue().icMean);
                }
            }
            List<com.quant.platform.factor.service.FactorCorrelationService.FactorCluster> clusters =
                    factorCorrelationService.detectCrowding(factorCodes, startDate, refDate, 0.70, icMap);
            for (com.quant.platform.factor.service.FactorCorrelationService.FactorCluster cluster : clusters) {
                for (String redundant : cluster.redundantFactors) {
                    // 优化X：白名单因子强制保留，跳过拥挤度剔除
                    if (FORCE_KEEP_FACTORS.contains(redundant)) {
                        log.info("[DynamicWeight] 因子 {} 在强制保留白名单，跳过拥挤度剔除 (簇代表={})", redundant, cluster.representative);
                        continue;
                    }
                    com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(redundant);
                    if (snap != null && "KEPT".equals(snap.status)) {
                        snap.status = "CROWDING_DROPPED";
                        snap.assessment = "拥挤度剔除: 与" + cluster.representative + "相关性过高(corr≥" + String.format("%.2f", cluster.maxCorrelation) + ")";
                        dropped.add(redundant);
                    }
                }
            }
            log.info("[DynamicWeight] P3 拥挤度过滤: {}个簇, 剔除{}个冗余因子", clusters.size(), dropped.size());
        } catch (Exception e) {
            log.warn("[DynamicWeight] P3 拥挤度过滤失败: {}", e.getMessage());
        }
        return dropped;
    }

    /**
     * P4: 财务因子季频IC校正
     * 对 FIN_* 前缀的因子，用季频IC替换日频IC（更符合财务数据公告节奏）
     *
     * @return 被校正的因子数量
     */
    int applyQuarterlyIcCorrection(
            List<String> factorCodes, LocalDate refDate,
            Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots) {
        int corrected = 0;
        for (String fc : factorCodes) {
            if (!factorMetaCache.isFinancial(fc)) continue;
            try {
                com.quant.platform.factor.service.QuarterlyFactorAnalysisService.QuarterlyIcResult qr =
                        quarterlyFactorAnalysisService.computeQuarterlyIc(fc, refDate.minusMonths(18), refDate, 5, true);
                if (qr != null && qr.quarterCount >= 3) {
                    com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(fc);
                    if (snap != null && Math.abs(qr.icMean) > Math.abs(snap.icMean) * 0.5) {
                        // 用季频IC替换（要求季频IC信号不能太弱）
                        double oldIc = snap.icMean;
                        snap.icMean = qr.icMean;
                        snap.icStd = qr.icStd;
                        snap.assessment = (snap.assessment != null ? snap.assessment + "; " : "") + "季频IC校正(" + String.format("%.4f", oldIc) + "→" + String.format("%.4f", qr.icMean) + ")";
                        corrected++;
                        log.info("[DynamicWeight] P4 季频IC校正: {} 日频IC={} → 季频IC={} ({}个季度)",
                                fc, oldIc, qr.icMean, qr.quarterCount);
                    }
                }
            } catch (Exception e) {
                log.debug("[DynamicWeight] P4 季频IC校正跳过: {} error={}", fc, e.getMessage());
            }
        }
        if (corrected > 0) {
            log.info("[DynamicWeight] P4 季频IC校正完成: {}/{}个财务因子已校正", corrected, factorCodes.stream().filter(fc -> factorMetaCache.isFinancial(fc)).count());
        }
        return corrected;
    }

    /**
     * P5: IC季度一致性校验
     * 检查因子近4个季度IC方向是否一致：
     *   - IC正占比 < 25%（4季度中≤1个正）→ 剔除（IC方向不稳定，无预测价值）
     *   - IC正占比 25-50% → 降权50%（方向不稳定但保留弱信号）
     *   - IC正占比 >= 50% → 正常保留
     *
     * @return 被剔除的因子数量
     */
    int applyIcConsistencyCheck(
            List<String> factorCodes, LocalDate refDate,
            Map<String, com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot> snapshots) {
        int dropped = 0;
        int penalized = 0;
        for (String fc : factorCodes) {
            com.quant.platform.factor.service.FactorAnalysisService.FactorIcSnapshot snap = snapshots.get(fc);
            if (snap == null || !"KEPT".equals(snap.status)) continue;
            try {
                // 查询近15个月的IC记录（覆盖4-5个季度）
                LocalDate startDate = refDate.minusMonths(15);
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.quant.platform.factor.ic.domain.FactorIcRecord> wrapper =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                wrapper.eq(com.quant.platform.factor.ic.domain.FactorIcRecord::getFactorCode, fc)
                       .eq(com.quant.platform.factor.ic.domain.FactorIcRecord::getForwardDays, 5)
                       .ge(com.quant.platform.factor.ic.domain.FactorIcRecord::getTradeDate, startDate)
                       .le(com.quant.platform.factor.ic.domain.FactorIcRecord::getTradeDate, refDate)
                       .orderByDesc(com.quant.platform.factor.ic.domain.FactorIcRecord::getTradeDate);
                List<com.quant.platform.factor.ic.domain.FactorIcRecord> records =
                        factorIcRecordMapper.selectList(wrapper);
                if (records == null || records.size() < 4) continue; // 数据不足跳过

                // 按季度分组，取每季度平均IC
                Map<String, List<Double>> quarterlyIc = new LinkedHashMap<>();
                for (var r : records) {
                    if (r.getTradeDate() == null || r.getIcValue() == null) continue;
                    String q = r.getTradeDate().getYear() + "-Q" + ((r.getTradeDate().getMonthValue() - 1) / 3 + 1);
                    quarterlyIc.computeIfAbsent(q, k -> new ArrayList<>()).add(r.getIcValue());
                }
                if (quarterlyIc.size() < 2) continue;

                long positiveQuarters = quarterlyIc.values().stream()
                        .mapToDouble(qs -> qs.stream().mapToDouble(d -> d).average().orElse(0))
                        .filter(avg -> avg > 0)
                        .count();
                int totalQuarters = quarterlyIc.size();
                double positiveRatio = (double) positiveQuarters / totalQuarters;

                if (positiveRatio < 0.25) {
                    // 方向极不稳定，剔除
                    snap.status = "CONSISTENCY_DROPPED";
                    snap.assessment = String.format("IC季度一致性剔除: %d/%d季度IC为正(占比%.0f%%), 方向不稳定",
                            positiveQuarters, totalQuarters, positiveRatio * 100);
                    dropped++;
                    log.info("[DynamicWeight] P5 一致性剔除: {} {}/{}季度正({:.0f}%)", fc, positiveQuarters, totalQuarters, positiveRatio * 100);
                } else if (positiveRatio < 0.50) {
                    // 方向不稳定，降权50%
                    String oldAssessment = snap.assessment;
                    snap.assessment = (oldAssessment != null ? oldAssessment + "; " : "") +
                            String.format("IC一致性降权50%%: %d/%d季度正", positiveQuarters, totalQuarters);
                    // 通过降低icMean来间接降权（ICW模式下权重∝|IC|）
                    snap.icMean *= 0.5;
                    penalized++;
                    log.info("[DynamicWeight] P5 一致性降权50%%: {} {}/{}季度正", fc, positiveQuarters, totalQuarters);
                }
            } catch (Exception e) {
                log.debug("[DynamicWeight] P5 一致性校验跳过: {} error={}", fc, e.getMessage());
            }
        }
        if (dropped > 0 || penalized > 0) {
            log.info("[DynamicWeight] P5 IC一致性校验: 剔除{}个, 降权{}个", dropped, penalized);
        }
        return dropped;
    }
}
