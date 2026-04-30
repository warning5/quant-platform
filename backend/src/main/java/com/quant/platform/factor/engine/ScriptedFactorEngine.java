package com.quant.platform.factor.engine;

import com.quant.platform.market.domain.MarketDailyBar;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Groovy脚本因子计算引擎
 * 支持用Groovy脚本动态定义自定义因子逻辑
 */
@Slf4j
@Component
public class ScriptedFactorEngine {

    private final CompilerConfiguration config;

    public ScriptedFactorEngine() {
        config = new CompilerConfiguration();
        config.setScriptBaseClass("groovy.lang.Script");
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
            Binding binding = new Binding();
            binding.setVariable("symbol", symbol);
            binding.setVariable("calcDate", calcDate);
            binding.setVariable("history", history);
            binding.setVariable("context", context);
            binding.setVariable("bars", history);
            // 便捷访问
            if (!history.isEmpty()) {
                binding.setVariable("bar", history.getLast());
                binding.setVariable("close", history.getLast().getClose());
                binding.setVariable("n", history.size());
            }

            GroovyShell shell = new GroovyShell(binding, config);
            Object result = shell.evaluate(scriptCode);

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
     * 验证Groovy脚本语法
     *
     * @param scriptCode Groovy脚本
     * @return null表示语法正确，否则返回错误信息
     */
    public String validateScript(String scriptCode) {
        try {
            GroovyShell shell = new GroovyShell(config);
            shell.parse(scriptCode);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
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
