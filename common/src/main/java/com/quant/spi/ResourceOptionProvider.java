package com.quant.spi;

import com.quant.platform.common.enums.ResourceType;

import java.util.List;

/**
 * 受控资源下拉选项 SPI（X2 解耦核心）。
 *
 * <p>dataperm 原先直接 import 各业务 Mapper（strategy/factor/backtest/paper）来拼装配置页下拉，
 * 形成"platform-core ↔ 业务包"的双向编译依赖。改为由业务模块自行实现本接口并注册为 Spring Bean，
 * dataperm 通过 {@code List<ResourceOptionProvider>} 收集，按 {@link ResourceType} 查表，
 * 从而彻底不再依赖任何业务包。新增受控资源类型时，只需在对应业务模块新增一个实现类，零改动 platform-core。</p>
 *
 * <p>约定：所有跨模块 SPI 契约接口统一放在 {@code com.quant.spi} 包（不属于 {@code com.quant.platform.**} Mapper 扫描基），
 * 避免被 MyBatis 误代理。</p>
 */
public interface ResourceOptionProvider {

    /** 本实现负责的资源类型。 */
    ResourceType supports();

    /** 该类型下的资源下拉选项（id + 中文标签）。 */
    List<ResourceOptionVO> listOptions();
}
