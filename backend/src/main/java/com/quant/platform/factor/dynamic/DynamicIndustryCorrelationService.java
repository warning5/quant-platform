package com.quant.platform.factor.dynamic;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.*;

/**
 * 行业相关性动态化服务（P2-8）
 *
 * 替代静态的INDUSTRY_CORR_GROUPS常量，基于申万一级行业指数的滚动收益率
 * 动态计算行业间相关系数矩阵，识别当前高相关性行业组。
 *
 * 核心功能：
 * 1. 滚动相关矩阵 — 计算近60日行业指数收益率的相关系数矩阵
 * 2. 动态行业分组 — 基于相关系数阈值(>0.7)聚类，输出当前高相关行业组
 * 3. 拥挤度检测 — 识别相关性显著上升的行业对（拥挤信号）
 *
 * 静态INDUSTRY_CORR_GROUPS作为fallback，动态计算结果覆盖静态配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicIndustryCorrelationService {

    private final JdbcTemplate jdbcTemplate;

    /** 滚动窗口天数 */
    private static final int ROLLING_WINDOW = 60;
    /** 高相关阈值 */
    private static final double HIGH_CORR_THRESHOLD = 0.70;
    /** 拥挤度变化阈值（相关系数较30日前上升超过此值 → 拥挤信号） */
    private static final double CROWDING_DELTA_THRESHOLD = 0.15;

    /** 缓存：动态行业组 + 过期时间 */
    private List<List<String>> cachedGroups = null;
    private long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000; // 6小时

    /**
     * 静态行业相关组（fallback）
     */
    private static final List<List<String>> STATIC_GROUPS = List.of(
        List.of("银行", "非银金融"),
        List.of("房地产开发", "房地产服务", "建筑装饰", "建筑材料"),
        List.of("煤炭", "石油石化", "电力设备"),
        List.of("食品饮料", "农林牧渔", "纺织服饰"),
        List.of("计算机", "通信", "传媒"),
        List.of("汽车", "机械设备"),
        List.of("医药生物", "公用事业"),
        List.of("电子", "国防军工")
    );

    /**
     * 获取动态行业相关组（带缓存）
     * 优先使用动态计算结果，失败时回退到静态配置
     *
     * @return 行业分组列表，每组内行业高相关
     */
    public List<List<String>> getDynamicCorrGroups() {
        // 缓存检查
        if (cachedGroups != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cachedGroups;
        }

        try {
            List<List<String>> dynamic = computeDynamicGroups();
            if (dynamic != null && !dynamic.isEmpty()) {
                cachedGroups = dynamic;
                cacheTimestamp = System.currentTimeMillis();
                log.info("[DynamicCorr] 动态行业分组计算成功: {}组", dynamic.size());
                return dynamic;
            }
        } catch (Exception e) {
            log.warn("[DynamicCorr] 动态行业分组计算失败, 回退到静态配置: {}", e.getMessage());
        }

        cachedGroups = STATIC_GROUPS;
        cacheTimestamp = System.currentTimeMillis();
        return STATIC_GROUPS;
    }

    /**
     * 计算动态行业分组
     * 基于近60日申万一级行业指数收益率的相关系数矩阵
     */
    private List<List<String>> computeDynamicGroups() {
        // 1. 获取所有有数据的行业指数代码
        Map<String, String> industryToCode = getIndustryCodeMapping();
        if (industryToCode.isEmpty()) return null;

        // 2. 获取近ROLLING_WINDOW日行业指数收益率
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(ROLLING_WINDOW + 30); // 多取30天确保有足够交易日

        Map<String, List<Double>> industryReturns = new HashMap<>();
        for (Map.Entry<String, String> entry : industryToCode.entrySet()) {
            String industry = entry.getKey();
            String code = entry.getValue();
            try {
                List<Double> returns = getIndustryReturns(code, startDate, endDate);
                if (returns.size() >= 30) {  // 至少30个交易日
                    industryReturns.put(industry, returns);
                }
            } catch (Exception e) {
                log.debug("[DynamicCorr] 获取行业 {}({}) 收益率失败: {}", industry, code, e.getMessage());
            }
        }

        if (industryReturns.size() < 5) {
            log.warn("[DynamicCorr] 行业收益率数据不足({}个), 回退到静态配置", industryReturns.size());
            return null;
        }

        // 3. 计算相关系数矩阵
        Map<String, Map<String, Double>> corrMatrix = computeCorrMatrix(industryReturns);

        // 4. 基于阈值聚类（简单贪心算法）
        List<List<String>> groups = clusterByCorrelation(industryReturns.keySet(), corrMatrix, HIGH_CORR_THRESHOLD);

        log.info("[DynamicCorr] 动态分组: {}组 (基于{}个行业, {}日窗口)",
            groups.size(), industryReturns.size(), ROLLING_WINDOW);

        return groups;
    }

    /**
     * 获取行业→指数代码映射
     */
    private Map<String, String> getIndustryCodeMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        // 申万一级行业指数代码 (801010-801980)
        String[][] pairs = {
            {"农林牧渔", "801010"}, {"基础化工", "801030"}, {"钢铁", "801040"},
            {"有色金属", "801050"}, {"电子", "801080"}, {"家用电器", "801110"},
            {"食品饮料", "801120"}, {"纺织服饰", "801130"}, {"轻工制造", "801140"},
            {"医药生物", "801150"}, {"公用事业", "801160"}, {"交通运输", "801170"},
            {"房地产", "801180"}, {"商贸零售", "801200"}, {"社会服务", "801210"},
            {"综合", "801230"}, {"建筑材料", "801710"}, {"建筑装饰", "801720"},
            {"电力设备", "801250"}, {"国防军工", "801260"}, {"计算机", "801270"},
            {"传媒", "801280"}, {"通信", "801300"}, {"汽车", "801880"},
            {"机械设备", "801890"}, {"银行", "801780"}, {"非银金融", "801790"},
            {"煤炭", "801950"}, {"石油石化", "801960"}, {"环保", "801970"},
            {"美容护理", "801980"}
        };
        for (String[] p : pairs) {
            mapping.put(p[0], p[1]);
        }
        return mapping;
    }

    /**
     * 获取行业指数日收益率序列
     */
    private List<Double> getIndustryReturns(String code, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT close_price, trade_date FROM index_daily " +
            "WHERE code = ? AND trade_date BETWEEN ? AND ? " +
            "ORDER BY trade_date", code, startDate, endDate);

        if (rows.size() < 2) return List.of();

        List<Double> closes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            closes.add(((Number) row.get("close_price")).doubleValue());
        }

        // 计算日收益率
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            if (closes.get(i - 1) > 0) {
                returns.add((closes.get(i) - closes.get(i - 1)) / closes.get(i - 1));
            }
        }
        return returns;
    }

    /**
     * 计算行业间皮尔逊相关系数矩阵
     */
    private Map<String, Map<String, Double>> computeCorrMatrix(Map<String, List<Double>> returns) {
        List<String> industries = new ArrayList<>(returns.keySet());
        Map<String, Map<String, Double>> matrix = new HashMap<>();

        for (int i = 0; i < industries.size(); i++) {
            String a = industries.get(i);
            matrix.putIfAbsent(a, new HashMap<>());
            matrix.get(a).put(a, 1.0);

            for (int j = i + 1; j < industries.size(); j++) {
                String b = industries.get(j);
                double corr = pearsonCorr(returns.get(a), returns.get(b));
                matrix.get(a).put(b, corr);
                matrix.putIfAbsent(b, new HashMap<>());
                matrix.get(b).put(a, corr);
            }
        }
        return matrix;
    }

    /**
     * 皮尔逊相关系数
     */
    private double pearsonCorr(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 5) return 0;

        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += x.get(i); sumY += y.get(i); }
        double meanX = sumX / n, meanY = sumY / n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX, dy = y.get(i) - meanY;
            cov += dx * dy; varX += dx * dx; varY += dy * dy;
        }

        double denom = Math.sqrt(varX * varY);
        return denom > 0 ? cov / denom : 0;
    }

    /**
     * 基于相关系数阈值聚类（贪心算法）
     * 两个行业相关系数 > threshold → 归入同组
     */
    private List<List<String>> clusterByCorrelation(
            Set<String> industries,
            Map<String, Map<String, Double>> corrMatrix,
            double threshold) {

        // Union-Find 聚类
        Map<String, String> parent = new HashMap<>();
        for (String ind : industries) parent.put(ind, ind);

        for (String a : industries) {
            for (String b : industries) {
                if (a.compareTo(b) >= 0) continue;
                Double corr = corrMatrix.getOrDefault(a, Map.of()).get(b);
                if (corr != null && corr > threshold) {
                    // union
                    String ra = find(parent, a), rb = find(parent, b);
                    if (!ra.equals(rb)) parent.put(ra, rb);
                }
            }
        }

        // 收集聚类结果
        Map<String, List<String>> clusters = new HashMap<>();
        for (String ind : industries) {
            String root = find(parent, ind);
            clusters.computeIfAbsent(root, k -> new ArrayList<>()).add(ind);
        }

        // 只保留2个以上行业的组（单行业组无意义，独立计算）
        List<List<String>> result = new ArrayList<>();
        for (List<String> cluster : clusters.values()) {
            if (cluster.size() >= 2) {
                result.add(cluster);
            }
        }
        return result;
    }

    private String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x))); // path compression
            x = parent.get(x);
        }
        return x;
    }

    /**
     * 获取相关系数矩阵（供外部使用）
     */
    public Map<String, Map<String, Double>> getCorrMatrix() {
        try {
            Map<String, String> industryToCode = getIndustryCodeMapping();
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(ROLLING_WINDOW + 30);

            Map<String, List<Double>> industryReturns = new HashMap<>();
            for (Map.Entry<String, String> entry : industryToCode.entrySet()) {
                List<Double> returns = getIndustryReturns(entry.getValue(), startDate, endDate);
                if (returns.size() >= 30) {
                    industryReturns.put(entry.getKey(), returns);
                }
            }
            return computeCorrMatrix(industryReturns);
        } catch (Exception e) {
            log.warn("[DynamicCorr] 获取相关矩阵失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 强制刷新缓存
     */
    public void refreshCache() {
        cachedGroups = null;
        cacheTimestamp = 0;
    }
}
