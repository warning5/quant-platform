package com.quant.platform.auth.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;
import com.quant.platform.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将 Sa-Token 鉴权异常统一收敛为项目标准的 ApiResponse 结构，
 * 且必须返回真实的 HTTP 401/403 状态码（否则拦截器虽抛异常，HTTP 仍是 200）。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalAuthExceptionHandler {

    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleSaToken(
            SaTokenException e, HttpServletRequest request, HttpServletResponse response) {
        int status;
        String msg;
        if (e instanceof NotLoginException) {
            status = HttpStatus.UNAUTHORIZED.value();
            msg = "登录已过期或未登录";
        } else if (e instanceof NotPermissionException || e instanceof NotRoleException) {
            status = HttpStatus.FORBIDDEN.value();
            msg = "无权限访问：" + e.getMessage();
        } else {
            status = HttpStatus.FORBIDDEN.value();
            msg = e.getMessage();
        }
        // SSE / 已提交响应：无法回写 JSON body，仅设置状态码，避免 HttpMessageNotWritableException -> 500
        String accept = request.getHeader("Accept");
        boolean isStream = (accept != null && accept.contains("text/event-stream")) || response.isCommitted();
        if (isStream) {
            response.setStatus(status);
            return null;
        }
        return ResponseEntity.status(status).body(ApiResponse.error(status, msg));
    }
}
