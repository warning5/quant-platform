package com.quant.platform.common.exception;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.exception.RateLimitExceededException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 * <p>
 * 统一处理所有 Controller 抛出的异常，返回标准 {@link ApiResponse} 格式。
 * 异常处理优先级由 Spring 自动按异常类型最精确匹配决定。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ===== 业务异常 =====

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: {}", ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage(), ex.getData());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFoundException(ResourceNotFoundException ex) {
        return ApiResponse.error(404, ex.getMessage());
    }

    // ===== 参数校验异常 =====

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ApiResponse.error(400, "参数校验失败: " + errors);
    }

    /**
     * 处理 @Validated 路径/查询参数校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolationException(ConstraintViolationException ex) {
        String errors = ex.getConstraintViolations()
                .stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败(ConstraintViolation): {}", errors);
        return ApiResponse.error(400, "参数校验失败: " + errors);
    }

    /**
     * 处理参数类型转换失败（如 /api/users/abc 中 abc 无法转为 Long）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型转换失败: {}", ex.getMessage());
        String paramName = ex.getName();
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "未知";
        return ApiResponse.error(400, String.format("参数 '%s' 类型错误，需要 %s", paramName, requiredType));
    }

    /**
     * 处理自定义参数验证异常
     */
    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(ValidationException ex) {
        log.warn("参数验证错误: {}", ex.getMessage());
        return ApiResponse.error(400, ex.getMessage());
    }

    /**
     * 处理非法参数异常。
     * <p>
     * 项目内大量 Service 用 {@code IllegalArgumentException} 承载用户可读的参数校验提示
     * （如"不支持的回测状态: XXX"、"策略不存在"），若落到兜底 handler 会返回 500 +
     * "系统内部错误"，前端无法展示真实原因。此处统一按 400 处理并回显原始文案。
     * <p>
     * 仍打印完整堆栈到日志，便于区分"业务校验"与"JDK 内部误抛"。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        String msg = ex.getMessage();
        log.warn("非法参数: {}", msg, ex);
        return ApiResponse.error(400, msg != null && !msg.isBlank() ? msg : "请求参数不合法");
    }

    // ===== 请求格式 / 路由异常 =====

    /**
     * 请求体 JSON 格式错误或不可读
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleHttpMessageNotReadableException(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ApiResponse.error(400, "请求体格式错误，请检查 JSON 是否正确");
    }

    /**
     * 必填请求参数缺失
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParamException(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数: {}", ex.getParameterName());
        return ApiResponse.error(400, String.format("缺少必填参数: %s", ex.getParameterName()));
    }

    /**
     * HTTP 方法不支持（如对只支持 GET 的接口发 POST）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP 方法不支持: {} {}", ex.getMethod(), ex.getMessage());
        return ApiResponse.error(405, String.format("请求方法 %s 不支持", ex.getMethod()));
    }

    /**
     * 请求路径不存在（需配合 spring.mvc.throw-exception-if-no-handler-found=true）
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoHandlerFoundException(NoHandlerFoundException ex) {
        log.warn("路径不存在: {}", ex.getRequestURL());
        return ApiResponse.error(404, "请求路径不存在: " + ex.getRequestURL());
    }

    /**
     * 请求路径不存在（Spring Boot 3.2+ 静态资源兜底抛出的异常）。
     * <p>
     * 未匹配到任何 Controller 映射时，DispatcherServlet 会转交
     * ResourceHttpRequestHandler，最终抛出 {@code NoResourceFoundException}。
     * 若不单独处理会落到兜底 handler 返回 500 "系统内部错误"，掩盖真实的 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFoundException(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.warn("路径不存在: {}", ex.getResourcePath());
        return ApiResponse.error(404, "请求路径不存在: " + ex.getResourcePath());
    }

    // ===== 限流异常 =====

    /**
     * 限流触发，返回 429 和 Retry-After 响应头
     */
    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Void> handleRateLimitExceededException(RateLimitExceededException ex) {
        log.warn("触发限流: retryAfter={}s", ex.getRetryAfterSeconds());
        return ApiResponse.error(429, ex.getMessage());
    }

    // ===== 兜底异常 =====

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        // 生成唯一的错误ID，用于日志追踪（不泄露敏感信息）
        String errorId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error [ID: {}]", errorId, ex);
        return ApiResponse.error(500, "系统内部错误，请联系管理员。错误ID: " + errorId);
    }
}
