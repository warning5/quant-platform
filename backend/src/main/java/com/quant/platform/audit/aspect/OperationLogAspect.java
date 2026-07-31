package com.quant.platform.audit.aspect;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.audit.annotation.OperationLog;
import com.quant.platform.audit.entity.SysOperationLog;
import com.quant.platform.audit.mapper.SysOperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 操作审计切面
 * 拦截：① 带 @OperationLog 注解的方法（精确记录）② 带 @SaCheckPermission 的写操作(POST/PUT/DELETE)
 * 仅 @SaCheckPermission 的查询(GET)操作不记录，避免审计噪声。
 */
@Slf4j
@Aspect
@Component
@Order(1)
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SysOperationLogMapper logMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("@annotation(com.quant.platform.audit.annotation.OperationLog) || @annotation(cn.dev33.satoken.annotation.SaCheckPermission)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        OperationLog operationLog = method.getAnnotation(OperationLog.class);
        SaCheckPermission saCheckPermission = method.getAnnotation(SaCheckPermission.class);
        String httpMethod = currentRequest() != null ? currentRequest().getMethod() : "";
        boolean isWrite = "POST".equals(httpMethod) || "PUT".equals(httpMethod) || "DELETE".equals(httpMethod);
        boolean explicit = operationLog != null;

        if (!explicit && !isWrite) {
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        SysOperationLog rec = new SysOperationLog();
        fillBase(rec, method, httpMethod, explicit ? operationLog : null, saCheckPermission, pjp);

        Object result;
        try {
            result = pjp.proceed();
            rec.setResult(1);
        } catch (Throwable t) {
            rec.setResult(0);
            String msg = t.getMessage();
            rec.setErrorMsg(msg == null ? t.getClass().getSimpleName() : (msg.length() > 500 ? msg.substring(0, 500) : msg));
            throw t;
        } finally {
            rec.setDurationMs((int) (System.currentTimeMillis() - start));
            rec.setOperationTime(LocalDateTime.now());
            try {
                logMapper.insert(rec);
            } catch (Exception e) {
                log.error("[OperationLogAspect] 审计日志写入失败: {}", e.getMessage());
            }
        }
        return result;
    }

    private void fillBase(SysOperationLog rec, Method method, String httpMethod,
                          OperationLog operationLog, SaCheckPermission saCheckPermission,
                          ProceedingJoinPoint pjp) {
        try {
            if (StpUtil.isLogin()) {
                String loginId = StpUtil.getLoginIdAsString();
                rec.setUserId(Long.parseLong(loginId));
                String username = StpUtil.getSession().getString("username");
                rec.setUsername(username != null ? username : loginId);
            } else {
                rec.setUserId(0L);
                rec.setUsername("anonymous");
            }
        } catch (Exception e) {
            rec.setUserId(0L);
            rec.setUsername("anonymous");
        }

        HttpServletRequest req = currentRequest();
        rec.setIp(getClientIp(req));
        rec.setRequestUrl(req != null ? req.getRequestURI() : "");
        rec.setHttpMethod(httpMethod);
        rec.setUserAgent(req != null ? req.getHeader("User-Agent") : "");
        rec.setMethodName(method.getDeclaringClass().getName() + "#" + method.getName());

        String module = operationLog != null ? operationLog.module() : "";
        String action = operationLog != null ? operationLog.action() : "";
        String perm = resolvePermission(saCheckPermission);
        if ((module == null || module.isEmpty()) && perm != null && !perm.isEmpty()) {
            int idx = perm.lastIndexOf(':');
            module = idx > 0 ? perm.substring(0, idx) : perm;
            if (action == null || action.isEmpty()) {
                action = idx > 0 ? perm.substring(idx + 1) : "query";
            }
        }
        if (action == null || action.isEmpty()) {
            action = "POST".equals(httpMethod) ? "add" : "PUT".equals(httpMethod) ? "edit"
                    : "DELETE".equals(httpMethod) ? "delete" : "query";
        }
        if (module == null || module.isEmpty()) {
            module = "unknown";
        }
        rec.setModule(module);
        rec.setAction(action);

        boolean recordParam = operationLog == null || operationLog.recordParam();
        if (recordParam) {
            try {
                rec.setRequestParam(maskSensitive(objectMapper.writeValueAsString(pjp.getArgs())));
            } catch (Exception e) {
                rec.setRequestParam("");
            }
        } else {
            rec.setRequestParam("");
        }
    }

    private String resolvePermission(SaCheckPermission ann) {
        if (ann == null) {
            return "";
        }
        String[] vals = ann.value();
        return (vals != null && vals.length > 0) ? vals[0] : "";
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getClientIp(HttpServletRequest req) {
        if (req == null) {
            return "";
        }
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = req.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = req.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip == null ? "" : ip;
    }

    private String maskSensitive(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        return json.replaceAll("\"(password|secret|token|apikey|api_key|secretkey|privatekey|authorization)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"******\"");
    }
}
