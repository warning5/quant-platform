package com.quant.platform.dataperm.service;

import lombok.Data;

/**
 * 数据权限配置页 - 资源下拉选项。
 */
@Data
public class ResourceOptionVO {
    private Long id;
    private String label;

    public ResourceOptionVO(Long id, String label) {
        this.id = id;
        this.label = label;
    }
}
