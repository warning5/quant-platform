package com.quant.platform.common.ratelimit;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * API 限流注解。
 * 基于令牌桶算法，按 IP + 用户标识维度限流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 时间窗口内允许的最大请求数 */
    int capacity();

    /** 时间窗口长度 */
    long duration();

    /** 时间单位 */
    TimeUnit timeUnit() default TimeUnit.MINUTES;

    /** 每个请求消耗的令牌数（默认1） */
    int tokens() default 1;

    /** 限流维度：按 IP、按用户、或按 IP+用户组合 */
    LimitType limitType() default LimitType.IP;

    enum LimitType {
        IP,           // 仅按客户端 IP
        USER,         // 仅按用户标识（需登录）
        IP_AND_USER   // IP + 用户组合（最严格）
    }
}
