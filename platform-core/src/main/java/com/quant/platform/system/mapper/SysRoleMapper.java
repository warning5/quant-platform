package com.quant.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.system.entity.SysRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT COUNT(*) FROM sys_user_role ur " +
            "JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 " +
            "WHERE ur.role_id = #{roleId}")
    Long selectUserCountByRoleId(@Param("roleId") Long roleId);
}
