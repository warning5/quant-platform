package com.quant.platform.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * 缓存配置
 *
 * 缓存策略：
 * - 主缓存(cacheManager)：通用 5min/100条
 * - stylePicks：选股策略 4h/20条
 * - factorIc：因子IC查询 30min/500条（IC计算涉及CH大批量查询，重复计算代价高）
 * - groovyScript：Groovy脚本编译 永不过期/50条（同一脚本对5000股×250日只需编译1次）
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

    /**
     * 因子IC计算缓存：30分钟过期
     * IC计算需查CH factor_value + stock_daily，单次查询涉及数千行数据。
     * 同一因子+日期+forwardDays 的结果在30分钟内不会变化（因子值日频更新）。
     *
     * 用于 @Cacheable(value = "factorIc", cacheManager = "factorIcCacheManager")
     */
    @Bean
    public CacheManager factorIcCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("factorIc");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats());
        return cacheManager;
    }

    /**
     * Groovy脚本编译缓存：永不过期
     * 脚本源码不变时，编译出的Class完全相同。5000股×250日=125万次调用，
     * 有缓存时实际只编译N个不同脚本（N=自定义因子数量，通常<50）。
     *
     * 注意：此缓存用于 ScriptedFactorEngine 手动管理，非 @Cacheable
     */
    @Bean
    public CacheManager groovyScriptCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("groovyScript");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterWrite(Long.MAX_VALUE, TimeUnit.DAYS)  // 实际永不过期
                .recordStats());
        return cacheManager;
    }
}
