package com.quant.platform.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.concurrent.ExecutorService;

/**
 * WebSocket 配置（用于回测实时进度推送）
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 端点（兼容旧客户端）
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        // 原生 WebSocket 端点（供前端 @stomp/stompjs 直接使用，无需 sockjs-client）
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns("*");
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
