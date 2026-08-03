package com.quant.platform.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

/**
 * WebSocket 配置（用于回测实时进度推送）
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] patterns = resolveAllowedOrigins();
        // SockJS 端点（兼容旧客户端）
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns)
                .withSockJS();
        // 原生 WebSocket 端点（供前端 @stomp/stompjs 直接使用，无需 sockjs-client）
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(patterns);
    }

    private String[] resolveAllowedOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            log.warn("[WebSocket] cors.allowed-origins 未配置，允许所有来源。生产环境必须配置具体域名！");
            return new String[]{"*"};
        }
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        log.info("[WebSocket] 允许的来源: {}", Arrays.toString(origins));
        return origins;
    }

    @Bean(name = "backtestTaskExecutor")
    public ThreadPoolTaskExecutor backtestTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);  // 核心10个线程
        executor.setMaxPoolSize(20);    // 繁忙时最多20个线程
        executor.setQueueCapacity(100);  // 超出线程数时进入队列排队，而非直接拒绝
        executor.setThreadNamePrefix("backtest-");
        executor.initialize();
        return executor;               // Spring 会在 destroy 时正确关闭
    }

    /**
     * 专供需要 ExecutorService 类型注入的地方使用（与 backtestTaskExecutor 同一个线程池）
     */
    @Bean(name = "backtestTaskExecutorService")
    public ExecutorService backtestTaskExecutorService(
            @Qualifier("backtestTaskExecutor") ThreadPoolTaskExecutor executor) {
        return executor.getThreadPoolExecutor();
    }
}
