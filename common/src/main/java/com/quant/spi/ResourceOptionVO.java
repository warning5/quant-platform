package com.quant.spi;

import lombok.Data;

/**
 * 数据权限配置页 - 资源下拉选项。
 * 置于 common 模块 {@code com.quant.spi} 包（独立于 {@code com.quant.platform.**} 的 MyBatis Mapper 扫描基），
 * 避免被 {@code @MapperScan("com.quant.platform.**")} 误当成 Mapper 接口代理。
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
