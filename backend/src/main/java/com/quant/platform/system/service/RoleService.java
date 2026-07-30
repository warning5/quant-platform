package com.quant.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.system.dto.RoleRequest;
import com.quant.platform.system.entity.SysRole;
import com.quant.platform.system.mapper.SysRoleMapper;
import com.quant.platform.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 角色管理
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final MenuService menuService;

    public IPage<SysRole> pageRoles(PageRequest req, String roleName, String roleCode) {
        Page<SysRole> page = new Page<>(req.getPage() + 1, req.getSize());
        LambdaQueryWrapper<SysRole> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(roleName)) {
            q.like(SysRole::getRoleName, roleName);
        }
        if (StringUtils.hasText(roleCode)) {
            q.like(SysRole::getRoleCode, roleCode);
        }
        q.eq(SysRole::getDeleted, 0).orderByDesc(SysRole::getCreateTime);
        return roleMapper.selectPage(page, q);
    }

    public List<SysRole> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getDeleted, 0).orderByAsc(SysRole::getRoleName));
    }

    public SysRole create(RoleRequest r) {
        if (!StringUtils.hasText(r.getRoleCode())) {
            throw new BusinessException("角色编码不能为空");
        }
        if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, r.getRoleCode()).eq(SysRole::getDeleted, 0)) > 0) {
            throw new BusinessException("角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleCode(r.getRoleCode());
        role.setRoleName(r.getRoleName());
        role.setRemark(r.getRemark());
        role.setStatus(r.getStatus() == null ? 1 : r.getStatus());
        roleMapper.insert(role);
        return role;
    }

    public SysRole update(RoleRequest r) {
        SysRole role = roleMapper.selectById(r.getId());
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        if (StringUtils.hasText(r.getRoleCode())) {
            role.setRoleCode(r.getRoleCode());
        }
        if (StringUtils.hasText(r.getRoleName())) {
            role.setRoleName(r.getRoleName());
        }
        role.setRemark(r.getRemark());
        if (r.getStatus() != null) {
            role.setStatus(r.getStatus());
        }
        roleMapper.updateById(role);
        return role;
    }

    public void delete(Long id) {
        menuService.assignMenus(id, Collections.emptyList());
        userRoleMapper.deleteByRoleId(id);
        roleMapper.deleteById(id);
    }

    public Long countUsers(Long roleId) {
        return roleMapper.selectUserCountByRoleId(roleId);
    }

    public List<Long> getMenuIds(Long roleId) {
        return menuService.getMenuIdsByRole(roleId);
    }

    public void assignMenus(Long roleId, List<Long> menuIds) {
        menuService.assignMenus(roleId, menuIds);
    }
}
