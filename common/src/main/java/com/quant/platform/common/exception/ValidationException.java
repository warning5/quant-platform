package com.quant.platform.common.exception;

/**
 * 参数验证异常
 * 用于Controller层参数校验失败的场景
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
