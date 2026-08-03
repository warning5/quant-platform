package com.quant.platform.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * MyBatis SQL slow query interceptor.
 *
 * <p>Logs a WARN-level entry to the "SLOW_SQL" logger when a query exceeds
 * the configured threshold (default 1000ms). The interceptor hooks into
 * {@code Executor.query} and {@code Executor.update} invocations.
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger slowSqlLogger = LoggerFactory.getLogger("SLOW_SQL");

    /** Threshold in milliseconds above which a query is considered slow. */
    @Value("${slow-sql.threshold-ms:1000}")
    private long slowThresholdMs;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String statementId = ms.getId();

        long startNs = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            if (elapsedMs >= slowThresholdMs) {
                Object param = invocation.getArgs()[1];
                slowSqlLogger.warn("Slow SQL [{}ms] statement=[{}] parameter=[{}]",
                        elapsedMs, statementId, param);
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
        String threshold = properties.getProperty("slowThresholdMs");
        if (threshold != null) {
            this.slowThresholdMs = Long.parseLong(threshold.trim());
        }
    }
}
