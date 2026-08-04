package com.quant.platform.screen.service;

import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.market.domain.MarketDailyBar;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.screen.dto.ScreenRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 选股因子加工服务
 * 因子正交化（Gram-Schmidt）、行业/市值中性化、IC 动态权重求解，
 * 以及策略因子配置解析与候选池过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenFactorProcessor {

    private final ObjectMapper objectMapper;
    private final FactorIcService factorIcService;
    private final ScreenMathService mathService;
    private final ScreenDataLoader dataLoader;

    /**
     * 施密特正交化（Gram-Schmidt）
     * 对标准化后的因子值矩阵做正交化，消除因子间共线性
     * 算法：
     * 1. 提取 N 个因子 × M 只股票的矩阵（使用 rankValue）
     * 2. 按因子顺序做 Gram-Schmidt 正交化
     * 3. 将正交化后的值回写到 FactorValue.rankValue
     * 注意：结果依赖因子顺序，建议将低IC因子放在前面
     */
    public void applyOrthogonalization(Map<String, Map<String, FactorValue>> factorData,
                                        String method) {
        List<String> factorCodes = new ArrayList<>(factorData.keySet());
        int numFactors = factorCodes.size();
        if (numFactors < 2) return;

        // 找出所有因子共有的股票（取交集）
        Set<String> commonSymbols = new LinkedHashSet<>(factorData.get(factorCodes.getFirst()).keySet());
        for (int i = 1; i < numFactors; i++) {
            commonSymbols.retainAll(factorData.get(factorCodes.get(i)).keySet());
        }
        List<String> symbols = new ArrayList<>(commonSymbols);
        int numSymbols = symbols.size();

        if (numSymbols < 10) {
            log.warn("Too few common stocks ({}) for orthogonalization, skipping", numSymbols);
            return;
        }

        // 构建因子矩阵：factorMatrix[f][s] = 第f个因子在第s只股票上的标准化值
        double[][] factorMatrix = new double[numFactors][numSymbols];
        for (int f = 0; f < numFactors; f++) {
            Map<String, FactorValue> symMap = factorData.get(factorCodes.get(f));
            for (int s = 0; s < numSymbols; s++) {
                FactorValue fv = symMap.get(symbols.get(s));
                factorMatrix[f][s] = fv != null && fv.getRankValue() != null
                        ? fv.getRankValue().doubleValue() : 0.0;
            }
        }

        // Gram-Schmidt 正交化
        // orthoVectors[f] = 正交化后的第f个因子向量
        double[][] orthoVectors = new double[numFactors][numSymbols];
        for (int f = 0; f < numFactors; f++) {
            // 先复制原始向量
            System.arraycopy(factorMatrix[f], 0, orthoVectors[f], 0, numSymbols);

            // 减去在之前所有正交向量上的投影
            for (int k = 0; k < f; k++) {
                double proj = mathService.dotProduct(orthoVectors[f], orthoVectors[k])
                        / mathService.dotProduct(orthoVectors[k], orthoVectors[k]);
                for (int s = 0; s < numSymbols; s++) {
                    orthoVectors[f][s] -= proj * orthoVectors[k][s];
                }
            }

            // 归一化（保持方差，使正交化后的值分布与原始值相似）
            double norm = Math.sqrt(mathService.dotProduct(orthoVectors[f], orthoVectors[f]) / numSymbols);
            if (norm > 1e-10) {
                // 用原始因子的标准差来缩放，保持量级
                double origStd = mathService.standardDeviation(factorMatrix[f]);
                double scale = origStd / norm;
                for (int s = 0; s < numSymbols; s++) {
                    orthoVectors[f][s] *= scale;
                }
            }
        }

        // 回写到 factorData 的 rankValue
        for (int f = 0; f < numFactors; f++) {
            Map<String, FactorValue> symMap = factorData.get(factorCodes.get(f));
            for (int s = 0; s < numSymbols; s++) {
                FactorValue fv = symMap.get(symbols.get(s));
                if (fv != null) {
                    fv.setRankValue(BigDecimal.valueOf(orthoVectors[f][s]).setScale(6, RoundingMode.HALF_UP));
                }
            }
        }

        // 计算正交化前后相关性变化（用于日志）
        double avgCorrBefore = mathService.avgCorrelation(factorMatrix);
        double avgCorrAfter = mathService.avgCorrelation(orthoVectors);
        log.info("Orthogonalization ({}): {} factors × {} stocks, avg correlation: {} → {}",
                method, numFactors, numSymbols, avgCorrBefore, avgCorrAfter);
    }

    /**
     * 中性化处理
     * 在行业或市值分组内，将因子值减去组内均值，消除行业/市值偏差
     * 
     * @param filtered 原始因子值列表（与outlierProcessed一一对应）
     * @param values 极值处理后的因子值
     * @param industryMap 行业映射
     * @param marketCapMap 市值映射
     * @param method 中性化方法：INDUSTRY / MARKET_CAP / BOTH
     * @return 中性化后的因子值
     */
    public List<Double> applyNeutralization(
            List<FactorValue> filtered,
            List<Double> values,
            Map<String, String> industryMap,
            Map<String, Double> marketCapMap,
            String method) {
        
        if (values == null || values.isEmpty()) return values;
        
        // 构建 symbol -> 索引映射
        Map<String, Integer> symbolIndex = new HashMap<>();
        for (int i = 0; i < filtered.size(); i++) {
            symbolIndex.put(ScreenMathService.normalizeFactorSymbol(filtered.get(i).getSymbol()), i);
        }
        
        List<Double> result = new ArrayList<>(values);
        
        // 1. 行业中性化
        if (method.contains("INDUSTRY") || "BOTH".equalsIgnoreCase(method)) {
            // 按行业分组
            Map<String, List<Integer>> industryGroups = new HashMap<>();
            for (Map.Entry<String, Integer> entry : symbolIndex.entrySet()) {
                String industry = industryMap.get(entry.getKey());
                if (industry == null) industry = "UNKNOWN";
                industryGroups.computeIfAbsent(industry, k -> new ArrayList<>()).add(entry.getValue());
            }
            
            // 在每个行业内做均值归一
            for (List<Integer> indices : industryGroups.values()) {
                if (indices.size() < 3) continue; // 行业内股票太少，跳过
                
                double sum = 0;
                for (int idx : indices) {
                    sum += values.get(idx);
                }
                double mean = sum / indices.size();
                
                for (int idx : indices) {
                    result.set(idx, result.get(idx) - mean);
                }
            }
            
            log.debug("[Neutralization] Industry groups: {}, stocks processed: {}", 
                industryGroups.size(), symbolIndex.size());
        }
        
        // 2. 市值中性化
        if (method.contains("MARKET_CAP") || "BOTH".equalsIgnoreCase(method)) {
            // 按市值分5组
            List<Map.Entry<String, Double>> capEntries = new ArrayList<>();
            for (String sym : symbolIndex.keySet()) {
                Double cap = marketCapMap.get(sym);
                if (cap != null && cap > 0) {
                    capEntries.add(new AbstractMap.SimpleEntry<>(sym, cap));
                }
            }
            
            if (capEntries.size() >= 10) {
                // 按市值排序
                capEntries.sort(Map.Entry.comparingByValue());
                
                // 分5组
                int groupSize = Math.max(1, capEntries.size() / 5);
                Map<String, Integer> capGroup = new HashMap<>();
                for (int i = 0; i < capEntries.size(); i++) {
                    int group = Math.min(i / groupSize, 4);
                    capGroup.put(capEntries.get(i).getKey(), group);
                }
                
                // 在每个市值组内做均值归一
                for (int g = 0; g < 5; g++) {
                    final int groupId = g;
                    List<Integer> indices = capGroup.entrySet().stream()
                        .filter(e -> e.getValue() == groupId)
                        .map(e -> symbolIndex.get(e.getKey()))
                        .filter(Objects::nonNull)
                        .toList();
                    
                    if (indices.size() < 3) continue;
                    
                    double sum = 0;
                    for (int idx : indices) {
                        sum += values.get(idx);
                    }
                    double mean = sum / indices.size();
                    
                    for (int idx : indices) {
                        result.set(idx, result.get(idx) - mean);
                    }
                }
                
                log.debug("[Neutralization] Market cap groups: 5, stocks with cap data: {}", capEntries.size());
            }
        }
        
        return result;
    }

    /**
     * 获取动态权重（基于IC/IR）—— IC加权综合得分（P0需求）
     * 规则：
     * - 获取每个因子最近60天的IC序列，计算IC均值（保留正负号）
     * - 只取IC均值 > 0的因子参与加权（有正向预测能力的因子）
     * - 归一化：dynamicWeight = factorIC / sum(all positive ICs)
     * - IC <= 0的因子：权重置零
     * - IC < 0的因子：自动反转direction
     * - 所有IC均<=0时回退到等权，但IC<0的仍反转direction
     *
     * @param factors 因子配置列表
     * @param weightMode 权重模式：IC / IR
     * @param screenDate 选股日期
     * @return factorCode -> 动态权重系数
     */
    public Map<String, Double> getDynamicWeights(List<ScreenRequest.FactorWeight> factors, String weightMode, LocalDate screenDate) {
        Map<String, Double> dynamicWeights = new HashMap<>();
        Map<String, Double> icScores = new HashMap<>();

        // 1. 获取每个因子的IC/IR值
        for (ScreenRequest.FactorWeight fw : factors) {
            String fc = fw.getFactorCode();
            // 优化X：无IC历史因子回退基准——用配置权重(factor_config_json.factors[].weight)代替统一0.05，
            // 使"配置权重有话语权"（新alpha因子如EARNINGS_SURPRISE IC历史短，历史回测中也能生效）
            double cfgWeight = (fw.getWeight() != null && fw.getWeight() > 0) ? fw.getWeight() : 0.05;
            try {
                // 从 factor_ic_record 表获取最近60天的IC序列
                List<Double> icValues = factorIcService.getIcHistory(fc, screenDate, 60);
                if (icValues == null || icValues.isEmpty()) {
                    log.warn("[DynamicWeight] 因子 {} 无IC历史数据，回退到配置权重{}（替代统一0.05）", fc, cfgWeight);
                    icScores.put(fc, cfgWeight);
                    continue;
                }

                double score;
                if ("IR".equalsIgnoreCase(weightMode)) {
                    // IR = IC均值 / IC标准差（IR始终非负）
                    double avg = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    double std = Math.sqrt(icValues.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));
                    score = std > 0 ? Math.abs(avg) / std : 0;
                } else {
                    // IC模式：使用IC均值（保留正负号，用于判断方向）
                    score = icValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                }
                icScores.put(fc, score);
            } catch (Exception e) {
                log.warn("[DynamicWeight] 获取因子 {} IC数据失败: {}, 回退到配置权重{}", fc, e.getMessage(), cfgWeight);
                icScores.put(fc, cfgWeight);
            }
        }

        // 2. 计算IC>0的因子的IC之和
        double sumPositiveIc = icScores.values().stream()
            .filter(v -> v > 0)
            .mapToDouble(Double::doubleValue)
            .sum();

        if (sumPositiveIc > 0) {
            for (Map.Entry<String, Double> entry : icScores.entrySet()) {
                String fc = entry.getKey();
                double ic = entry.getValue();
                if (ic > 0) {
                    double normalized = ic / sumPositiveIc;
                    // 限制范围避免极端值
                    normalized = Math.max(0.1, Math.min(5.0, normalized));
                    dynamicWeights.put(fc, normalized);
                } else {
                    dynamicWeights.put(fc, 0.0);
                }
                // IC<0时反转direction
                if (ic < 0) {
                    for (ScreenRequest.FactorWeight fw : factors) {
                        if (fw.getFactorCode().equals(fc)) {
                            int oldDir = fw.getDirection();
                            fw.setDirection(-oldDir);
                            log.info("[DynamicWeight] 因子 {} IC为负({})，反转方向: {} -> {}", fc, ic, oldDir, fw.getDirection());
                            break;
                        }
                    }
                }
            }
        } else {
            // 所有IC均<=0，回退到等权
            log.warn("[DynamicWeight] 所有因子IC均<=0，回退到等权，IC为负的因子反转方向");
            for (Map.Entry<String, Double> entry : icScores.entrySet()) {
                String fc = entry.getKey();
                double ic = entry.getValue();
                dynamicWeights.put(fc, 1.0);
                if (ic < 0) {
                    for (ScreenRequest.FactorWeight fw : factors) {
                        if (fw.getFactorCode().equals(fc)) {
                            fw.setDirection(-fw.getDirection());
                            break;
                        }
                    }
                }
            }
        }

        log.info("[DynamicWeight] mode={} date={} weights={}", weightMode, screenDate, dynamicWeights);
        return dynamicWeights;
    }

    /**
     * 解析策略定义的 factor_config_json
     * 格式: {"factors": [{"code":"MOM20","weight":1.0,"direction":1,"filterOp":"NONE",...}, ...]}
     */
    public List<ScreenRequest.FactorWeight> parseStrategyFactorConfig(String factorConfigJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(factorConfigJson,
                    new TypeReference<>() {
                    });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> factorList = (List<Map<String, Object>>) root.get("factors");
            if (factorList == null) return Collections.emptyList();
            return factorList.stream().map(m -> {
                ScreenRequest.FactorWeight fw = new ScreenRequest.FactorWeight();
                fw.setFactorCode((String) m.get("code"));
                fw.setDirection(m.get("direction") instanceof Number
                        ? ((Number) m.get("direction")).intValue() : 1);
                fw.setWeight(m.get("weight") instanceof Number
                        ? ((Number) m.get("weight")).doubleValue() : 1.0);
                fw.setFilterOp(m.get("filterOp") != null ? (String) m.get("filterOp") : "NONE");
                fw.setFilterValue(m.get("filterValue") instanceof Number
                        ? ((Number) m.get("filterValue")).doubleValue() : null);
                return fw;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse strategy factor config: {}", factorConfigJson, e);
            return Collections.emptyList();
        }
    }

    /**
     * 应用 filterConfigJson 中的过滤条件到候选股票池
     * 支持：
     *   - excludeIndustries: List<String> 排除的行业代码列表（申万行业）
     *   - excludeMarkets: List<String> 排除的市场代码列表，如 ["BJ"] 排除北交所
     *   - minListingDays: int 最少上市天数
     *   - minMarketCap: double 最小市值(亿元)
     *   - customFilters: List<Map> 自定义因子过滤 [{factor, op, value}]
     */
    @SuppressWarnings("unchecked")
    public Set<String> applyFilterConfig(Set<String> candidates,
                                           Map<String, MarketDailyBar> barMapByCode,
                                           Map<String, Object> filterConfig,
                                           LocalDate screenDate) {
        Set<String> result = new HashSet<>(candidates);

        // 0. 市场排除（北交所 BJ: code 以 8 或 9 开头）
        Object excludeMarkets = filterConfig.get("excludeMarkets");
        if (excludeMarkets instanceof List) {
            Set<String> marketSet = new HashSet<>((List<String>) excludeMarkets);
            if (marketSet.contains("BJ")) {
                int before = result.size();
                result.removeIf(code -> code.startsWith("8") || code.startsWith("9"));
                log.info("[FilterConfig] ExcludeMarket(BJ): removed {} stocks, remaining={}",
                        before - result.size(), result.size());
            }
        }

        // 1a. 行业白名单（includeIndustries）—— 只保留属于白名单行业的股票
        Object includeIndustries = filterConfig.get("includeIndustries");
        if (includeIndustries instanceof List) {
            Set<String> includeSet = new HashSet<>((List<String>) includeIndustries);
            if (!includeSet.isEmpty()) {
                Map<String, String> codeIndustryMap = dataLoader.batchLoadIndustryInfo(new ArrayList<>(result));
                int before = result.size();
                result.removeIf(code -> {
                    String ind = codeIndustryMap.get(code);
                    if (ind == null) return true; // 无行业信息→排除
                    return includeSet.stream().noneMatch(ind::contains);
                });
                log.info("[FilterConfig] Industry include: whitelist={}, before={}, after={}",
                        includeSet.size(), before, result.size());
            }
        }

        // 1b. 行业排除
        Object excludeIndustries = filterConfig.get("excludeIndustries");
        if (excludeIndustries instanceof List) {
            Set<String> excludeSet = new HashSet<>((List<String>) excludeIndustries);
            if (!excludeSet.isEmpty()) {
                // 批量查询候选股票的行业信息
                Map<String, String> codeIndustryMap = dataLoader.batchLoadIndustryInfo(new ArrayList<>(result));
                result.removeIf(code -> excludeSet.contains(codeIndustryMap.get(code)));
                log.info("[FilterConfig] Industry exclude: removed industries={}, remaining={}",
                        excludeSet.size(), result.size());
            }
        }

        // 2. 最少上市天数
        Object minDays = filterConfig.get("minListingDays");
        if (minDays instanceof Number) {
            int minListingDays = ((Number) minDays).intValue();
            if (minListingDays > 0) {
                Map<String, LocalDate> codeListDateMap = dataLoader.batchLoadListDates(new ArrayList<>(result));
                LocalDate cutoff = screenDate.minusDays(minListingDays);
                int before = result.size();
                result.removeIf(code -> {
                    LocalDate listDate = codeListDateMap.get(code);
                    return listDate == null || listDate.isAfter(cutoff);
                });
                log.info("[FilterConfig] MinListingDays={}: removed {} stocks, remaining={}",
                        minListingDays, before - result.size(), result.size());
            }
        }

        // 3. 最小市值过滤
        Object minCap = filterConfig.get("minMarketCap");
        if (minCap instanceof Number) {
            double minMarketCap = ((Number) minCap).doubleValue();
            if (minMarketCap > 0) {
                Map<String, Double> marketCapMap = dataLoader.batchLoadMarketCap(new ArrayList<>(result), screenDate);
                int before = result.size();
                result.removeIf(code -> {
                    Double cap = marketCapMap.get(code);
                    return cap == null || cap < minMarketCap;
                });
                log.info("[FilterConfig] MinMarketCap={}: removed {} stocks, remaining={}",
                        (long) minMarketCap, before - result.size(), result.size());
            }
        }

        // 4. 自定义因子过滤（单因子条件，GT/GTE/LT/LTE/EQ）
        Object customFilters = filterConfig.get("customFilters");
        if (customFilters instanceof List) {
            List<Map<String, Object>> filters = (List<Map<String, Object>>) customFilters;
            for (Map<String, Object> cf : filters) {
                String factorCode = (String) cf.get("factor");
                String op = (String) cf.getOrDefault("op", "GT");
                double value = cf.get("value") instanceof Number ? ((Number) cf.get("value")).doubleValue() : 0;
                if (factorCode == null) continue;

                // 查询因子截面值
                Map<String, Double> factorVals = dataLoader.batchLoadFactorValues(factorCode, screenDate, new ArrayList<>(result));
                if (factorVals.isEmpty()) continue;

                int before = result.size();
                result.removeIf(code -> {
                    Double fv = factorVals.get(code);
                    if (fv == null) return true; // 无因子值，剔除
                    return !mathService.compareFactorValue(fv, op, value);
                });
                log.info("[FilterConfig] customFilter: {} {} {}, removed {}, remaining={}",
                        factorCode, op, value, before - result.size(), result.size());
            }
        }

        return result;
    }

}
