package com.quant.platform.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.system.dto.RoleRequest;
import com.quant.platform.system.entity.SysRole;
import com.quant.platform.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理（需登录 + 权限）
 */
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping("/page")
    @SaCheckPermission("system:role:list")
    public ApiResponse<IPage<SysRole>> page(PageRequest req,
                                            @RequestParam(required = false) String roleName,
                                            @RequestParam(required = false) String roleCode) {
        return ApiResponse.success(roleService.pageRoles(req, roleName, roleCode));
    }

    @GetMapping("/list")
    // 角色列表是用户管理表单（角色下拉框）的依赖数据：拥有角色管理权限 或 用户管理权限均可读取
    public ApiResponse<List<SysRole>> list() {
        StpUtil.checkPermissionOr("system:role:list", "system:user:list");
        return ApiResponse.success(roleService.listAll());
    }

    @PostMapping
    @SaCheckPermission("system:role:add")
    public ApiResponse<SysRole> add(@Valid @RequestBody RoleRequest request) {
        return ApiResponse.success(roleService.create(request));
    }

    @PutMapping
    @SaCheckPermission("system:role:edit")
    public ApiResponse<SysRole> edit(@Valid @RequestBody RoleRequest request) {
        return ApiResponse.success(roleService.update(request));
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:role:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/users/count")
    @SaCheckPermission("system:role:list")
    public ApiResponse<Long> userCount(@PathVariable Long id) {
        return ApiResponse.success(roleService.countUsers(id));
    }

    @GetMapping("/{id}/menus")
    @SaCheckPermission("system:role:assign")
    public ApiResponse<List<Long>> menus(@PathVariable Long id) {
        return ApiResponse.success(roleService.getMenuIds(id));
    }

    @PostMapping("/{id}/menus")
    @SaCheckPermission("system:role:assign")
    public ApiResponse<Void> assignMenus(@PathVariable Long id, @RequestBody List<Long> menuIds) {
        roleService.assignMenus(id, menuIds);
        return ApiResponse.ok();
    }
}
