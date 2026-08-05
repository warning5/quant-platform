package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperRebalanceLogMapper extends BaseMapper<PaperRebalanceLog> {

    @Select("SELECT * FROM paper_rebalance_log WHERE paper_id = #{comboId} OR paper_id IN (SELECT id FROM paper_trading WHERE parent_id = #{comboId}) ORDER BY rebalance_date DESC, id DESC")
    List<PaperRebalanceLog> selectByComboId(@Param("comboId") Long comboId);
}
