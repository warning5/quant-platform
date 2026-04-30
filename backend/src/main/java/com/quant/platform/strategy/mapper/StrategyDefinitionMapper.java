package com.quant.platform.strategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.strategy.domain.StrategyDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 策略定义Mapper
 */
@Mapper
public interface StrategyDefinitionMapper extends BaseMapper<StrategyDefinition> {

    /**
     * 检查策略代码是否存在
     */
    @Select("SELECT COUNT(*) > 0 FROM strategy_definition WHERE strategy_code = #{strategyCode}")
    boolean existsByStrategyCode(@Param("strategyCode") String strategyCode);

    /**
     * 搜索策略（支持分页）
     */
    default List<StrategyDefinition> search(String keyword, StrategyDefinition.StrategyType type,
                                            StrategyDefinition.StrategyStatus status, int offset, int limit) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StrategyDefinition>()
                .like(keyword != null, StrategyDefinition::getStrategyName, keyword)
                .or()
                .like(keyword != null, StrategyDefinition::getStrategyCode, keyword)
                .eq(type != null, StrategyDefinition::getStrategyType, type)
                .eq(status != null, StrategyDefinition::getStatus, status)
                .last("LIMIT " + offset + ", " + limit));
    }
}
