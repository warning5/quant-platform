package com.quant.platform.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final Object data;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
        this.data = null;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.data = null;
    }

    public BusinessException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
