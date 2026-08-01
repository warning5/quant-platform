package com.quant.platform.dataperm.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 孤儿资源扫描：业务表有、resource_meta 无的资源。
 * 用于每日对账兜底 + 历史数据迁移（补 owner=admin 的 meta 行）。
 */
@Mapper
public interface OrphanCheckMapper {

    @Select("SELECT t.id FROM strategy_definition t WHERE NOT EXISTS "
            + "(SELECT 1 FROM resource_meta m WHERE m.resource_type='STRATEGY' AND m.resource_id=t.id)")
    List<Long> strategyOrphans();

    @Select("SELECT t.id FROM factor_definition t WHERE NOT EXISTS "
            + "(SELECT 1 FROM resource_meta m WHERE m.resource_type='FACTOR' AND m.resource_id=t.id)")
    List<Long> factorOrphans();

    @Select("SELECT t.id FROM backtest_task t WHERE NOT EXISTS "
            + "(SELECT 1 FROM resource_meta m WHERE m.resource_type='BACKTEST' AND m.resource_id=t.id)")
    List<Long> backtestOrphans();

    @Select("SELECT t.id FROM paper_trading t WHERE NOT EXISTS "
            + "(SELECT 1 FROM resource_meta m WHERE m.resource_type='PAPER_TRADING' AND m.resource_id=t.id)")
    List<Long> paperOrphans();
}
