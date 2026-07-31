package com.quant.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.system.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT DISTINCT m.permission FROM sys_menu m " +
            "JOIN sys_role_menu rm ON rm.menu_id = m.id " +
            "JOIN sys_user_role ur ON ur.role_id = rm.role_id " +
            "WHERE ur.user_id = #{userId} AND m.permission IS NOT NULL AND m.permission <> '' AND m.deleted = 0")
    List<String> selectPermissionByUserId(@Param("userId") Long userId);

    @Select("SELECT DISTINCT r.role_code FROM sys_role r " +
            "JOIN sys_user_role ur ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodeByUserId(@Param("userId") Long userId);

    @Select("SELECT DISTINCT r.id FROM sys_role r " +
            "JOIN sys_user_role ur ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<Long> selectRoleIdByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE wechat_unionid = #{unionid} AND deleted = 0 LIMIT 1")
    SysUser selectByUnionid(@Param("unionid") String unionid);

    @Select("SELECT * FROM sys_user WHERE wechat_openid = #{openid} AND deleted = 0 LIMIT 1")
    SysUser selectByOpenid(@Param("openid") String openid);
}
