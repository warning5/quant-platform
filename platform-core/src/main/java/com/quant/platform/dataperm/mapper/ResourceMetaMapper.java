package com.quant.platform.dataperm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.dataperm.domain.ResourceMetaDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * resource_meta 访问。INSERT IGNORE 保证 (resource_type, resource_id) 唯一键幂等，
 * 应对 BacktestService.createAndRun 双 insert 分支等重复写入场景。
 */
@Mapper
public interface ResourceMetaMapper extends BaseMapper<ResourceMetaDO> {

    @Insert("INSERT IGNORE INTO resource_meta(resource_type, resource_id, owner_id, owner_dept_id, visibility, created_at) "
            + "VALUES(#{type}, #{rid}, #{uid}, #{deptId}, 'PRIVATE', NOW())")
    int insertIgnore(@Param("type") String resourceType,
                     @Param("rid") Long resourceId,
                     @Param("uid") Long ownerId,
                     @Param("deptId") Long ownerDeptId);
}
