package com.quant.platform.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Bucket4j 的 API 限流拦截器。
 * 按 IP / 用户 / IP+用户 维度维护令牌桶，拒绝超频请求。
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    /** key -> Bucket */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        RateLimit annotation = hm.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            return true;
        }

        String key = resolveKey(request, annotation.limitType());
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(annotation));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(annotation.tokens());
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }

        long retryAfterSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L + 1;
        response.setHeader("X-RateLimit-Retry-After", String.valueOf(retryAfterSeconds));
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                String.format("{\"code\":429,\"message\":\"请求过于频繁，请 %d 秒后重试\"}", retryAfterSeconds)
        );
        log.warn("[RateLimit] 触发限流: method={} path={} key={} retryAfter={}s",
                hm.getMethod().getName(), request.getRequestURI(), key, retryAfterSeconds);
        return false;
    }

    private Bucket createBucket(RateLimit annotation) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(annotation.capacity())
                .refillIntervally(annotation.capacity(), java.time.Duration.ofMillis(annotation.timeUnit().toMillis(annotation.duration())))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private String resolveKey(HttpServletRequest request, RateLimit.LimitType limitType) {
        String ip = getClientIp(request);
        switch (limitType) {
            case IP -> {
                return "ip:" + ip;
            }
            case USER -> {
                // 当前系统无登录态，fallback 到 IP
                return "user:" + ip;
            }
            case IP_AND_USER -> {
                return "ip_user:" + ip;
            }
            default -> {
                return "ip:" + ip;
            }
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        String ri = request.getHeader("X-Real-IP");
        if (ri != null && !ri.isBlank()) {
            return ri.trim();
        }
        return request.getRemoteAddr();
    }
}
