package com.quant.platform.common.enums;

import lombok.Getter;

/**
 * 受数据权限管控的资源类型，与 resource_meta.resource_type 列及业务表一一对应。
 * 新增受控资源类型时在此追加，并同步标注对应实体 @ResourceMeta。
 */
@Getter
public enum ResourceType {
    STRATEGY("STRATEGY", "strategy_definition"),
    BACKTEST("BACKTEST", "backtest_task"),
    FACTOR("FACTOR", "factor_definition"),
    PAPER_TRADING("PAPER_TRADING", "paper_trading");

    private final String code;
    private final String tableName;

    ResourceType(String code, String tableName) {
        this.code = code;
        this.tableName = tableName;
    }

    /** 按业务表名反查资源类型（用于拦截器从 Mapper 推断）。 */
    public static ResourceType fromTableName(String tableName) {
        if (tableName == null) {
            return null;
        }
        for (ResourceType t : values()) {
            if (t.tableName.equalsIgnoreCase(tableName)) {
                return t;
            }
        }
        return null;
    }

    /** 按 code 反查资源类型（用于接口入参校验）。 */
    public static ResourceType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ResourceType t : values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        return null;
    }
}
