package com.quant.platform.dataperm.service;

import lombok.Data;

/**
 * 数据权限配置页 - 授权对象下拉选项（USER / ROLE 用）。
 */
@Data
public class GranteeOptionVO {
    private Long id;
    private String label;

    public GranteeOptionVO(Long id, String label) {
        this.id = id;
        this.label = label;
    }
}


