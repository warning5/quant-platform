package com.quant.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.system.entity.SysMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 查询某用户可见的菜单（目录+菜单，不含按钮），按角色合并去重
     */
    @Select("SELECT DISTINCT m.* FROM sys_menu m " +
            "JOIN sys_role_menu rm ON rm.menu_id = m.id " +
            "JOIN sys_user_role ur ON ur.role_id = rm.role_id " +
            "WHERE ur.user_id = #{userId} AND m.menu_type IN (0,1) AND m.deleted = 0 " +
            "ORDER BY m.parent_id ASC, m.sort ASC, m.id ASC")
    List<SysMenu> selectMenusByUserId(@Param("userId") Long userId);
}
