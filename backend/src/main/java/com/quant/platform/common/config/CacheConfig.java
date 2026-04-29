package com.quant.platform.common.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 缓存配置
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 主缓存管理器：通用缓存5分钟过期
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats());
        return cacheManager;
    }

    /**
     * 选股策略缓存：4小时过期（财务数据日频变化很小）
     * 用于 @Cacheable(value = "stylePicks", cacheManager = "stylePicksCacheManager")
     */
    @Bean
    public CacheManager stylePicksCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("stylePicks");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(20)
                .expireAfterWrite(4, TimeUnit.HOURS)
                .recordStats());
        return cacheManager;
    }
}
