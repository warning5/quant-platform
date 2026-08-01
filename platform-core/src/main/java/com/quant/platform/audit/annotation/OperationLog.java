package com.quant.platform.audit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标记在 Controller 方法上，自动记录审计日志。
 * 若不加此注解，但方法上有 @SaCheckPermission 且为写操作(POST/PUT/DELETE)，
 * 切面也会自动记录（module/action 从 permission 推导）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    /** 功能模块，如 system:user */
    String module() default "";

    /** 操作动作，如 add/edit/delete/query */
    String action() default "";

    /** 是否记录请求参数，默认 true */
    boolean recordParam() default true;
}
