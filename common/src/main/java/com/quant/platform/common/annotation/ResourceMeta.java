package com.quant.platform.common.annotation;

import com.quant.platform.common.enums.ResourceType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记在受数据权限管控的实体类上。ResourceMetaInsertInterceptor 据此在插入时
 * 自动写入 resource_meta（归属 + 默认私有可见性），DataPermissionInterceptor 据此
 * 在查询时注入可见性过滤条件。业务表结构本身零改动。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceMeta {
    ResourceType value();
}
