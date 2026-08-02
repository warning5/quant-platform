package com.quant.platform.dataperm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.dataperm.domain.ResourceShareDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@Mapper
public interface ResourceShareMapper extends BaseMapper<ResourceShareDO> {

    @Select("SELECT * FROM resource_share WHERE resource_type=#{type} AND resource_id=#{rid} ORDER BY id")
    List<ResourceShareDO> listByResource(@Param("type") String type, @Param("rid") Long rid);

    @Delete("DELETE FROM resource_share WHERE resource_type=#{type} AND resource_id=#{rid}")
    int deleteByResource(@Param("type") String resourceType, @Param("rid") Long resourceId);
}
