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

    /** 统计仍持有 ADMIN 角色的活跃用户数（status=1 且未删除），用于"最后管理员"保护 */
    @Select("SELECT COUNT(*) FROM sys_user u " +
            "JOIN sys_user_role ur ON u.id = ur.user_id " +
            "JOIN sys_role r ON ur.role_id = r.id " +
            "WHERE r.role_code = 'ADMIN' AND u.status = 1 AND u.deleted = 0")
    Long countActiveAdminUsers();
}
