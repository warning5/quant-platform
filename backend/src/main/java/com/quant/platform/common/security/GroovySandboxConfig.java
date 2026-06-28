package com.quant.platform.common.security;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Groovy 脚本安全沙箱统一配置
 *
 * 提供三个层面的安全防护：
 * 1. 源码预检（preCheckScript）— 正则边界匹配，拒绝危险模式
 * 2. 编译层沙箱（SecureASTCustomizer）— 白名单 receivers + 禁止 import + 间接导入检查
 * 3. 执行超时（evaluateScriptWithTimeout / runScriptWithTimeout）— 防止死循环阻塞线程池
 *
 * 统一存放 DANGEROUS_PATTERNS / SECURE_GROOVY_CONFIG / preCheckScript / validateScript
 * 消除 BacktestEngine 和 ScriptedFactorEngine 中的重复定义
 */
@Slf4j
public class GroovySandboxConfig {

    // ─── 源码预检：危险模式（正则边界匹配，比 contains 更难绕过）─────────────────
    private static final List<Pattern> DANGEROUS_PATTERNS = compilePatterns(
            "execute\\b", "exec\\b", "Runtime", "ProcessBuilder",
            "System\\.exit", "System\\.setProperty", "System\\.getProperty",
            "new\\s+File", "FileWriter", "FileReader", "RandomAccessFile",
            "new\\s+Socket", "new\\s+URL", "HttpURLConnection",
            "Thread\\.", "ClassLoader", "Unsafe", "GroovyShell",
            "Class\\.forName", "Method\\.invoke", "Field\\.setAccessible",
            "RuntimeMXBean", "ManagementFactory"
    );

    private static final Pattern UNICODE_ESCAPE_PATTERN = Pattern.compile("\\\\u[0-9a-fA-F]{4}");

    // ─── SecureASTCustomizer 配置（编译层防护）─────────────────
    public static final CompilerConfiguration SECURE_GROOVY_CONFIG = createSecureConfig();

    // ─── 执行超时专用线程池（守护线程，2线程，隔离脚本执行，防止阻塞主线程池）─────────
    private static final ExecutorService SCRIPT_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "groovy-script-exec");
        t.setDaemon(true);
        return t;
    });

    /** 因子计算脚本默认超时（秒） */
    public static final long FACTOR_TIMEOUT_SECONDS = 30;
    /** 回测脚本超时（秒） */
    public static final long BACKTEST_TIMEOUT_SECONDS = 60;

    // ──────────────────────────────────────────────────────────────
    //  私有方法
    // ──────────────────────────────────────────────────────────────

    private static List<Pattern> compilePatterns(String... patterns) {
        List<Pattern> result = new ArrayList<>(patterns.length);
        for (String p : patterns) {
            result.add(Pattern.compile(p));
        }
        return result;
    }

    private static CompilerConfiguration createSecureConfig() {
        SecureASTCustomizer secure = new SecureASTCustomizer();
        // 禁止导入任意类（白名单为空 = 禁止所有 import）
        secure.setAllowedImports(List.of());
        secure.setAllowedStarImports(List.of());
        secure.setAllowedStaticImports(List.of());
        secure.setAllowedStaticStarImports(List.of());
        // 启用间接导入检查，防止通过全限定类名绕过
        secure.setIndirectImportCheckEnabled(true);
        // 禁止方法定义（脚本只允许单一 run() 方法）
        secure.setMethodDefinitionAllowed(false);
        secure.setClosuresAllowed(true);
        // 允许的接收者类型白名单（限制脚本可调用的对象类型）
        secure.setAllowedReceivers(Arrays.asList(
                // 基础数学运算
                Math.class.getName(),
                BigDecimal.class.getName(),
                RoundingMode.class.getName(),
                // 集合操作
                List.class.getName(),
                Map.class.getName(),
                Set.class.getName(),
                ArrayList.class.getName(),
                HashMap.class.getName(),
                LinkedHashMap.class.getName(),
                // 字符串操作
                String.class.getName(),
                // 日期时间
                LocalDate.class.getName(),
                LocalDateTime.class.getName(),
                DateTimeFormatter.class.getName(),
                // 数值类型
                Integer.class.getName(),
                Long.class.getName(),
                Double.class.getName(),
                Float.class.getName(),
                Number.class.getName(),
                // 因子相关（脚本上下文绑定的类型）
                "com.quant.platform.factor.domain.FactorValue",
                "com.quant.platform.market.domain.MarketDailyBar",
                // Groovy 范围与闭包
                groovy.lang.Range.class.getName(),
                groovy.lang.Closure.class.getName()
        ));

        CompilerConfiguration config = new CompilerConfiguration();
        config.addCompilationCustomizers(secure);
        config.setScriptBaseClass("groovy.lang.Script");
        return config;
    }

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 源码预检脚本安全性，拒绝危险模式
     * 使用正则边界匹配（\b / \s），比 String.contains() 更难绕过
     *
     * @return null 表示安全通过，否则返回拒绝原因
     */
    public static String preCheckScript(String scriptCode) {
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(scriptCode).find()) {
                return "脚本包含不允许的操作: " + pattern.pattern();
            }
        }
        if (UNICODE_ESCAPE_PATTERN.matcher(scriptCode).find()) {
            return "脚本包含 Unicode 转义，疑似绕过检测";
        }
        return null;
    }

    /**
     * 验证脚本安全性 + 语法（完整校验）
     * 先做源码预检，再尝试编译，双重验证
     *
     * @return null 表示验证通过，否则返回错误信息
     */
    public static String validateScript(String scriptCode) {
        String preCheck = preCheckScript(scriptCode);
        if (preCheck != null) {
            return "安全检测未通过: " + preCheck;
        }
        try {
            GroovyShell shell = new GroovyShell(SECURE_GROOVY_CONFIG);
            shell.parse(scriptCode);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * 使用带超时的 GroovyShell 执行脚本（BacktestEngine 用）
     * 在独立守护线程池中执行，超时后 cancel（设置线程中断标志）
     *
     * @param binding          执行上下文绑定
     * @param scriptCode       脚本源码
     * @param timeoutSeconds   超时秒数
     * @return 脚本执行结果
     * @throws SecurityException    预检失败
     * @throws TimeoutException     执行超时
     * @throws RuntimeException     其他执行错误
     */
    public static Object evaluateScriptWithTimeout(Binding binding, String scriptCode, long timeoutSeconds)
            throws TimeoutException {
        // 安全预检
        String preCheck = preCheckScript(scriptCode);
        if (preCheck != null) {
            throw new SecurityException(preCheck);
        }

        GroovyShell shell = new GroovyShell(binding, SECURE_GROOVY_CONFIG);
        Future<Object> future = SCRIPT_EXECUTOR.submit(() -> shell.evaluate(scriptCode));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // 设置线程中断标志
            log.warn("Groovy script execution timed out after {}s, cancelled", timeoutSeconds);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException se) throw se;
            throw new RuntimeException("Script execution failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Script execution interrupted", e);
        }
    }

    /**
     * 使用带超时执行已编译的 Script 实例（ScriptedFactorEngine 用）
     *
     * @param scriptClass      已编译的 Script Class
     * @param binding          执行上下文绑定
     * @param timeoutSeconds   超时秒数
     * @return 脚本执行结果
     * @throws TimeoutException     执行超时
     * @throws RuntimeException     其他执行错误
     */
    public static Object runScriptWithTimeout(Class<? extends Script> scriptClass, Binding binding, long timeoutSeconds)
            throws TimeoutException {
        Script script = InvokerHelper.createScript(scriptClass, binding);

        Future<Object> future = SCRIPT_EXECUTOR.submit(() -> {
            try {
                return script.run();
            } catch (Exception e) {
                throw e;
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Groovy script execution timed out after {}s, cancelled", timeoutSeconds);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new RuntimeException("Script execution failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Script execution interrupted", e);
        }
    }

    /**
     * 计算脚本内容的 SHA-256 摘要（替代 hashCode，避免碰撞风险）
     *
     * @param scriptCode 脚本源码
     * @return SHA-256 摘要的十六进制字符串（64字符）
     */
    public static String scriptDigest(String scriptCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(scriptCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，不可能不存在
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
