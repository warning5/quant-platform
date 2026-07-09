package com.quant.platform.factor.service;

import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 因子元数据缓存服务
 * 启动时从 factor_definition 表加载所有 ACTIVE 因子的 dataFrequency，
 * 提供 isQuarterly / isFinancial 等判断方法，替代前缀+白名单硬编码。
 *
 * 新增因子时只需在 DB 中设置 dataFrequency，无需改 Java 代码。
 */
@Slf4j
@Service
public class FactorMetaCacheService {

    private final FactorDefinitionMapper factorDefinitionMapper;

    /** factorCode → dataFrequency ("DAILY" / "QUARTERLY" / null) */
    private volatile Map<String, String> frequencyMap = Collections.emptyMap();

    /** 季频因子代码集合（从 frequencyMap 推导，加速查询） */
    private volatile Set<String> quarterlyCodes = Collections.emptySet();

    /** 财务因子代码集合（category=FINANCIAL 或 QUALITY 的季频因子） */
    private volatile Set<String> financialCodes = Collections.emptySet();

    /** 日频因子代码集合（dataFrequency="DAILY"） */
    private volatile Set<String> dailyCodes = Collections.emptySet();

    public FactorMetaCacheService(FactorDefinitionMapper factorDefinitionMapper) {
        this.factorDefinitionMapper = factorDefinitionMapper;
    }

    /**
     * 启动时加载因子元数据缓存
     */
    @org.springframework.context.event.EventListener(
            classes = {org.springframework.boot.context.event.ApplicationReadyEvent.class})
    public void init() {
        refresh();
    }

    /**
     * 手动刷新缓存（因子配置变更时调用）
     */
    public synchronized void refresh() {
        Map<String, String> newFreqMap = new ConcurrentHashMap<>();
        Set<String> newQuarterlyCodes = ConcurrentHashMap.newKeySet();
        Set<String> newFinancialCodes = ConcurrentHashMap.newKeySet();
        Set<String> newDailyCodes = ConcurrentHashMap.newKeySet();

        for (FactorDefinition fd : factorDefinitionMapper.findActiveFactors()) {
            String code = fd.getFactorCode();
            String freq = fd.getDataFrequency();
            if (freq == null) freq = inferFrequency(fd);
            newFreqMap.put(code, freq);

            if ("QUARTERLY".equals(freq)) {
                newQuarterlyCodes.add(code);
            } else {
                newDailyCodes.add(code);
            }

            // 财务因子 = 季频 + (FINANCIAL 或 QUALITY 分类)
            FactorDefinition.FactorCategory cat = fd.getCategory();
            if ("QUARTERLY".equals(freq) && cat != null
                    && (cat == FactorDefinition.FactorCategory.FINANCIAL
                    || cat == FactorDefinition.FactorCategory.QUALITY)) {
                newFinancialCodes.add(code);
            }
        }

        this.frequencyMap = Collections.unmodifiableMap(newFreqMap);
        this.quarterlyCodes = Collections.unmodifiableSet(newQuarterlyCodes);
        this.financialCodes = Collections.unmodifiableSet(newFinancialCodes);
        this.dailyCodes = Collections.unmodifiableSet(newDailyCodes);

        log.info("[FactorMetaCache] 加载完成: {}个因子, {}个日频, {}个季频, {}个财务因子",
                newFreqMap.size(), newDailyCodes.size(), newQuarterlyCodes.size(), newFinancialCodes.size());
    }

    /** 判断因子是否为季频（dataFrequency="QUARTERLY"） */
    public boolean isQuarterly(String factorCode) {
        return quarterlyCodes.contains(factorCode);
    }

    /** 判断因子是否为日频（dataFrequency="DAILY"，或非季频的默认值） */
    public boolean isDaily(String factorCode) {
        return dailyCodes.contains(factorCode);
    }

    /** 判断因子是否为财务因子（季频 + FINANCIAL/QUALITY 分类） */
    public boolean isFinancial(String factorCode) {
        return financialCodes.contains(factorCode);
    }

    /** 获取因子频率（"DAILY" / "QUARTERLY" / null） */
    public String getDataFrequency(String factorCode) {
        return frequencyMap.get(factorCode);
    }

    /** 获取所有季频因子代码 */
    public Set<String> getQuarterlyCodes() {
        return quarterlyCodes;
    }

    /** 获取所有日频因子代码 */
    public Set<String> getDailyCodes() {
        return dailyCodes;
    }

    // ── 内部方法 ──

    /**
     * 当 dataFrequency 字段为 null 时，从因子前缀和分类推断频率
     * 兼容老数据：FIN_* 和非日频白名单的 VAL_* 默认季频
     */
    private String inferFrequency(FactorDefinition fd) {
        String code = fd.getFactorCode();
        FactorDefinition.FactorCategory cat = fd.getCategory();

        // 已注册日频估值因子 → DAILY
        if (code.startsWith("VAL_")) {
            switch (code) {
                case "VAL_PE_TTM", "VAL_PB", "VAL_PE_PERCENTILE",
                     "VAL_PB_PERCENTILE", "VAL_DIVIDEND_YIELD", "VAL_FCF_YIELD":
                    return "DAILY";
                default:
                    return "QUARTERLY";
            }
        }

        // FIN_* 前缀 → QUARTERLY
        if (code.startsWith("FIN_")) return "QUARTERLY";

        // QUAL_* 前缀 → QUARTERLY
        if (code.startsWith("QUAL_")) return "QUARTERLY";

        // 技术类/动量/波动率/量价 → DAILY
        if (cat == FactorDefinition.FactorCategory.TECHNICAL
                || cat == FactorDefinition.FactorCategory.MOMENTUM
                || cat == FactorDefinition.FactorCategory.VOLATILITY
                || cat == FactorDefinition.FactorCategory.VOLUME_PRICE
                || cat == FactorDefinition.FactorCategory.LIQUIDITY
                || cat == FactorDefinition.FactorCategory.SENTIMENT) {
            return "DAILY";
        }

        // 其余默认 DAILY（保守选择：不跳过非季末日期）
        return "DAILY";
    }
}
