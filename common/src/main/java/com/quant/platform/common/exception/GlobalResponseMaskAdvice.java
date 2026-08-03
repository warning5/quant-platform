package com.quant.platform.common.exception;

import com.quant.platform.common.dto.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.regex.Pattern;

/**
 * 响应体脱敏：对所有 {@link ApiResponse} 的 message 做安全过滤，
 * 移除可能泄露的 SQL 片段 / 绝对路径 / 堆栈 / 源码文件名 / IP:端口 等特征，
 * 防止异常内部信息经 API 返回前端（覆盖各业务代码显式拼接 e.getMessage() 的场景）。
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalResponseMaskAdvice implements ResponseBodyAdvice<Object> {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?<![\\w/])(?:[A-Za-z]:\\\\[\\\\/]|/)[\\\\w./-]{4,}|"        // 绝对路径 C:\... 或 /a/b/c
                    + "\\b\\w+\\.(sql|java|yml|yaml|properties|xml|class)\\b|" // 源码/配置文件名
                    + "at\\s+[\\w$.]+(?:\\.[\\w$]+)+\\(|"                    // 堆栈 at com.xxx(
                    + "Caused by:|SQLException|SQLSyntaxError|BatchUpdateException|" // 异常类名
                    + "NestedRuntimeException|\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?::\\d+)?\\b", // IP:port
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse) {
            ApiResponse<?> resp = (ApiResponse<?>) body;
            String msg = resp.getMessage();
            if (msg != null && SENSITIVE.matcher(msg).find()) {
                resp.setMessage(SENSITIVE.matcher(msg).replaceAll("***"));
            }
        }
        return body;
    }
}
