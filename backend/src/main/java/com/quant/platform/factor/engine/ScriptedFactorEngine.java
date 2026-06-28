package com.quant.platform.factor.engine;

import com.quant.platform.market.domain.MarketDailyBar;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groovy脚本因子计算引擎（带编译缓存 + 脚本内容安全预检）
 * 安全方案：执行前预检脚本源码，拒绝包含危险模式的脚本
 * - 禁止：execute / exec / Runtime / ProcessBuilder / System.exit
 * - 禁止：File / Socket / URL / HttpURLConnection 等危险类
 * - 禁止：Thread / ClassLoader / Unsafe 等底层操作
 * - 禁止：GroovyShell 嵌套执行（防止沙箱逃逸）
 * 性能优化：同一脚本源码 → 只编译一次，复用 Script 实例
 * - 无缓存时：5000股 × 250日 = 1,250,000 次编译
 * - 有缓存后：N个不同脚本 × 1次编译 = N 次（通常 N < 50）
 */
@Slf4j
@Component
public class ScriptedFactorEngine {

    private final CompilerConfiguration config;

    /** 脚本编译缓存：scriptCodeHash → 已编译的 Script Class（线程安全，每次 createScript 创建新实例） */
    private final ConcurrentHashMap<String, Class<? extends Script>> scriptClassCache = new ConcurrentHashMap<>();

    /** 危险模式黑名单（脚本预检） */
    private static final List<String> DANGEROUS_PATTERNS = List.of(
        "execute(", "exec(", "Runtime", "ProcessBuilder",
        "System.exit", "System.setProperty", "System.getProperty",
        "new File", "FileWriter", "FileReader", "RandomAccessFile",
        "new Socket", "new URL", "HttpURLConnection",
        "Thread.", "ClassLoader", "Unsafe", "GroovyShell",
        "Class.forName", "Method.invoke", "Field.setAccessible",
        "RuntimeMXBean", "ManagementFactory"
    );

    public ScriptedFactorEngine() {
        config = createSecureConfig();
    }

    /**
     * 创建带安全沙箱的 CompilerConfiguration（SecureASTCustomizer）
     */
    private static CompilerConfiguration createSecureConfig() {
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setAllowedImports(List.of());
        secure.setAllowedStarImports(List.of());
        secure.setAllowedStaticImports(List.of());
        secure.setAllowedStaticStarImports(List.of());
        secure.setIndirectImportCheckEnabled(true);
        secure.setMethodDefinitionAllowed(false);
        secure.setClosuresAllowed(true);
        secure.setAllowedReceivers(List.of(
            Math.class.getName(),
            BigDecimal.class.getName(),
            RoundingMode.class.getName(),
            List.class.getName(),
            Map.class.getName(),
            Set.class.getName(),
            ArrayList.class.getName(),
            HashMap.class.getName(),
            LinkedHashMap.class.getName(),
            String.class.getName(),
            LocalDate.class.getName(),
            LocalDateTime.class.getName(),
            java.time.format.DateTimeFormatter.class.getName(),
            Integer.class.getName(),
            Long.class.getName(),
            Double.class.getName(),
            Float.class.getName(),
            Number.class.getName(),
            "com.quant.platform.factor.domain.FactorValue",
            "com.quant.platform.market.domain.MarketDailyBar",
            groovy.lang.Range.class.getName(),
            groovy.lang.Closure.class.getName()
        ));

        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(secure);
        cc.setScriptBaseClass("groovy.lang.Script");
        return cc;
    }

    /**
     * 预检脚本安全性，拒绝危险模式
     * @return null表示安全通过，否则返回拒绝原因
     */
    private String preCheckScript(String scriptCode) {
        for (String pattern : DANGEROUS_PATTERNS) {
            if (scriptCode.contains(pattern)) {
                return "脚本包含不允许的操作: " + pattern;
            }
        }
        // 检查是否尝试绕过字符串（十六进制/Unicode编码）
        if (scriptCode.matches(".*\\\\u[0-9a-fA-F]{4}.*")) {
            return "脚本包含 Unicode 转义，疑似绕过检测";
        }
        return null;
    }

    /**
     * 执行Groovy脚本计算因子值
     *
     * @param scriptCode Groovy脚本代码
     * @param factorCode 因子代码（用于缓存）
     * @param symbol     股票代码
     * @param calcDate   计算日期
     * @param history    历史K线数据
     * @param context    额外上下文
     * @return 因子值
     */
    public BigDecimal calculate(String scriptCode, String factorCode,
                                String symbol, LocalDate calcDate,
                                List<MarketDailyBar> history,
                                Map<String, Object> context) {
        try {
            // 0. 安全预检
            String preCheck = preCheckScript(scriptCode);
            if (preCheck != null) {
                log.warn("Script pre-check FAILED for factor [{}]: {}", factorCode, preCheck);
                return null;
            }
            // 1. 从缓存获取或编译 Script Class（线程安全）
            Class<? extends Script> scriptClass = getOrCompile(scriptCode);

            // 2. 绑定本次执行上下文（每次调用独立的 Binding + Script 实例）
            Binding binding = new Binding();
            binding.setVariable("symbol", symbol);
            binding.setVariable("calcDate", calcDate);
            binding.setVariable("history", history);
            binding.setVariable("context", context);
            binding.setVariable("bars", history);
            if (!history.isEmpty()) {
                binding.setVariable("bar", history.getLast());
                binding.setVariable("close", history.getLast().getClose());
                binding.setVariable("n", history.size());
            }

            // 3. 每次创建新 Script 实例（线程安全），绑定后执行
            Script script = org.codehaus.groovy.runtime.InvokerHelper.createScript(scriptClass, binding);
            Object result = script.run();

            return switch (result) {
                case BigDecimal bd -> bd;
                case Number num -> BigDecimal.valueOf(num.doubleValue());
                case null, default -> null;
            };
        } catch (Exception e) {
            log.warn("Script execution failed for factor [{}] symbol [{}]: {}", factorCode, symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 获取已编译的 Script Class（从缓存 or 新编译）
     * 缓存 Class 而非 Script 实例，因为 Script 实例的 Binding 不是线程安全的
     */
    private Class<? extends Script> getOrCompile(String scriptCode) {
        // 安全预检（编译前拦截）
        String preCheck = preCheckScript(scriptCode);
        if (preCheck != null) {
            throw new SecurityException("Script rejected: " + preCheck);
        }
        String key = Integer.toHexString(scriptCode.hashCode());
        return scriptClassCache.computeIfAbsent(key, k -> {
            log.debug("Compiling new Groovy script (cache miss), hash={}", k);
            GroovyShell shell = new GroovyShell(config);
            return shell.parse(scriptCode).getClass();
        });
    }

    /**
     * 验证Groovy脚本语法
     *
     * @param scriptCode Groovy脚本
     * @return null表示语法正确，否则返回错误信息
     */
    public String validateScript(String scriptCode) {
        // 先预检安全性
        String preCheck = preCheckScript(scriptCode);
        if (preCheck != null) {
            return "安全检测未通过: " + preCheck;
        }
        try {
            GroovyShell shell = new GroovyShell(config);
            shell.parse(scriptCode);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** 清空编译缓存（脚本更新后调用） */
    public void clearCache() {
        int size = scriptClassCache.size();
        scriptClassCache.clear();
        log.info("Groovy script cache cleared ({} entries)", size);
    }

    /** 返回当前缓存大小（监控用） */
    public int getCacheSize() {
        return scriptClassCache.size();
    }

    @PreDestroy
    public void destroy() {
        scriptClassCache.clear();
    }

    /**
     * 生成脚本模板
     */
    public static String getScriptTemplate(String templateType) {
        return switch (templateType) {
            case "momentum" -> """
                    // 动量因子模板
                    // history: List<MarketDailyBar> - 历史K线，时间正序
                    // bar: 最新K线
                    // n: 历史数据条数
                    
                    int period = 20  // 回看周期
                    if (n < period + 1) return null
                    
                    def latest = history[n - 1].close
                    def past = history[n - period - 1].close
                    
                    if (past == 0) return null
                    return (latest - past) / past
                    """;
            case "volatility" -> """
                    // 波动率因子模板
                    int period = 20
                    if (n < period + 1) return null
                    
                    def window = history[(n - period - 1)..(n - 1)]
                    def returns = []
                    for (int i = 1; i < window.size(); i++) {
                        def prev = window[i-1].close.doubleValue()
                        def curr = window[i].close.doubleValue()
                        if (prev > 0) returns << Math.log(curr / prev)
                    }
                    
                    def mean = returns.sum() / returns.size()
                    def variance = returns.collect { (it - mean) ** 2 }.sum() / (returns.size() - 1)
                    return Math.sqrt(variance) * Math.sqrt(252)
                    """;
            case "technical" -> """
                    // 技术因子模板
                    // 示例：5日均线与20日均线的比值
                    if (n < 20) return null
                    
                    def ma5 = history[(n-5)..(n-1)].collect { it.close }.sum() / 5
                    def ma20 = history[(n-20)..(n-1)].collect { it.close }.sum() / 20
                    
                    if (ma20 == 0) return null
                    return (ma5 - ma20) / ma20
                    """;
            default -> """
                    // 自定义因子脚本
                    // 可用变量:
                    //   history - List<MarketDailyBar> 历史K线数据（时间正序）
                    //   bar     - 最新K线
                    //   symbol  - 股票代码
                    //   calcDate - 计算日期
                    //   n       - 历史数据条数
                    
                    // 返回 null 表示无法计算当期因子值
                    // 返回 Number 类型作为因子值
                    
                    if (n < 1) return null
                    return bar.close
                    """;
        };
    }
}
