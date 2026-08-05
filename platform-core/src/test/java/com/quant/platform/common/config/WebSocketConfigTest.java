package com.quant.platform.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1/T3 校验：backtestTaskExecutor 的拒绝策略必须为 CallerRunsPolicy，
 * 避免队列满时默认 AbortPolicy 静默丢弃回测任务。
 */
class WebSocketConfigTest {

    @Test
    void backtestTaskExecutorUsesCallerRunsPolicy() {
        WebSocketConfig config = new WebSocketConfig();
        ThreadPoolTaskExecutor executor = config.backtestTaskExecutor();
        try {
            RejectedExecutionHandler handler = executor.getThreadPoolExecutor().getRejectedExecutionHandler();
            assertNotNull(handler, "拒绝策略不应为 null");
            assertTrue(handler instanceof ThreadPoolExecutor.CallerRunsPolicy,
                    "backtestTaskExecutor 拒绝策略应为 CallerRunsPolicy，实际为 " + handler.getClass().getName());
        } finally {
            executor.shutdown();
        }
    }
}
