package com.quant.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.system.entity.SysMenu;
import com.quant.platform.system.entity.SysRoleMenu;
import com.quant.platform.system.mapper.SysMenuMapper;
import com.quant.platform.system.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单 / 权限管理
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    /** 当前登录用户的菜单树（目录+菜单，按角色合并，并补全祖先目录） */
    public List<SysMenu> getUserMenuTree(Long userId) {
        List<SysMenu> flat = menuMapper.selectMenusByUserId(userId);
        // 补全祖先目录，避免部分授权时菜单树断裂
        java.util.Set<Long> ids = flat.stream().map(SysMenu::getId).collect(java.util.stream.Collectors.toSet());
        java.util.List<SysMenu> ancestors = new java.util.ArrayList<>();
        for (SysMenu m : flat) {
            Long pid = m.getParentId();
            while (pid != null && pid != 0 && !ids.contains(pid)) {
                SysMenu parent = menuMapper.selectById(pid);
                if (parent == null) {
                    break;
                }
                ancestors.add(parent);
                ids.add(pid);
                pid = parent.getParentId();
            }
        }
        flat.addAll(ancestors);
        return buildTree(flat);
    }

    /** 全部菜单树（含按钮） */
    public List<SysMenu> tree() {
        List<SysMenu> all = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getDeleted, 0)
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));
        return buildTree(all);
    }

    /** 全部菜单扁平列表（含按钮） */
    public List<SysMenu> listAll() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getDeleted, 0)
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));
    }

    public SysMenu getById(Long id) {
        return menuMapper.selectById(id);
    }

    public void create(MenuRequest req) {
        SysMenu menu = new SysMenu();
        menu.setParentId(req.getParentId());
        menu.setMenuName(req.getMenuName());
        menu.setMenuType(req.getMenuType());
        menu.setPath(req.getPath());
        menu.setComponent(req.getComponent());
        menu.setIcon(req.getIcon());
        menu.setPermission(req.getPermission());
        menu.setSort(req.getSort());
        menu.setStatus(req.getStatus());
        menu.setDeleted(0);
        menuMapper.insert(menu);
    }

    public void update(MenuRequest req) {
        if (req.getId() == null) {
            throw new IllegalArgumentException("菜单ID不能为空");
        }
        SysMenu existing = menuMapper.selectById(req.getId());
        if (existing == null) {
            throw new IllegalArgumentException("菜单不存在");
        }
        // 只复制白名单字段，避免覆盖 createTime / deleted 等系统字段
        existing.setParentId(req.getParentId());
        existing.setMenuName(req.getMenuName());
        existing.setMenuType(req.getMenuType());
        existing.setPath(req.getPath());
        existing.setComponent(req.getComponent());
        existing.setIcon(req.getIcon());
        existing.setPermission(req.getPermission());
        existing.setSort(req.getSort());
        existing.setStatus(req.getStatus());
        menuMapper.updateById(existing);
    }

    /** 递归删除菜单（含子菜单与角色关联） */
    public void remove(Long id) {
        List<SysMenu> children = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id));
        for (SysMenu child : children) {
            remove(child.getId());
        }
        menuMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
    }

    /** 某角色已分配的菜单ID列表 */
    public List<Long> getMenuIdsByRole(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
    }

    /** 为角色重新分配菜单 */
    public void assignMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.deleteByRoleId(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            List<SysRoleMenu> list = menuIds.stream().map(mid -> {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(mid);
                return rm;
            }).collect(Collectors.toList());
            roleMenuMapper.insertBatch(list);
        }
    }

    /** 把扁平列表组装成树 */
    private List<SysMenu> buildTree(List<SysMenu> flat) {
        Map<Long, SysMenu> map = new LinkedHashMap<>();
        for (SysMenu m : flat) {
            map.put(m.getId(), m);
        }
        List<SysMenu> roots = new ArrayList<>();
        for (SysMenu m : flat) {
            Long pid = m.getParentId();
            if (pid == null || pid == 0 || !map.containsKey(pid)) {
                roots.add(m);
            } else {
                SysMenu parent = map.get(pid);
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(m);
            }
        }
        return roots;
    }
}
