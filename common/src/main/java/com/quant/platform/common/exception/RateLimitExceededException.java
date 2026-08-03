package com.quant.platform.common.exception;

/**
 * 限流触发异常。
 * 由 RateLimitInterceptor 抛出，经 GlobalExceptionHandler 统一处理为 429 响应。
 */
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
