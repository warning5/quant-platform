package com.quant.platform.recommendation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 推荐服务专用线程池配置。
 * 替代 {@code RecommendationService} 中每次调用 {@code newFixedThreadPool} 的写法，
 * 改为单例共享线程池，限制个股深度分析的并发数，避免频繁创建/销毁线程池，
 * 同时防止 ClickHouse 连接池耗尽。
 */
@Configuration
public class RecommendationConfig {

    @Bean(name = "recommendationAnalysisExecutor")
    public ExecutorService recommendationAnalysisExecutor(
            @Value("${quant.recommendation.analysis-parallelism:5}") int parallelism) {
        int poolSize = Math.max(1, parallelism);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "rec-analysis");
                    t.setDaemon(true);
                    return t;
                });
        // 队列无限时不会触发拒绝，但仍设 CallerRunsPolicy 兜底，避免极端情况下任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
