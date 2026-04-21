package com.quant.platform.factor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.factor.domain.FactorDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 因子定义Mapper
 */
@Mapper
public interface FactorDefinitionMapper extends BaseMapper<FactorDefinition> {

    /**
     * 检查因子代码是否存在
     */
    @Select("SELECT COUNT(*) > 0 FROM factor_definition WHERE factor_code = #{factorCode}")
    boolean existsByFactorCode(@Param("factorCode") String factorCode);

    /**
     * 根据状态查询因子列表
     */
    default List<FactorDefinition> findByStatus(FactorDefinition.FactorStatus status) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorDefinition>()
                .eq(status != null, FactorDefinition::getStatus, status));
    }

    /**
     * 查询所有激活的因子
     */
    default List<FactorDefinition> findActiveFactors() {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorDefinition>()
                .eq(FactorDefinition::getStatus, FactorDefinition.FactorStatus.ACTIVE));
    }
}
