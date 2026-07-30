package com.quant.platform.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.system.entity.SysMenu;
import com.quant.platform.system.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜单 / 权限管理（需登录 + 权限）
 */
@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 菜单树（含按钮） */
    @GetMapping("/tree")
    @SaCheckPermission("system:menu:list")
    public ApiResponse<List<SysMenu>> tree() {
        return ApiResponse.success(menuService.tree());
    }

    /** 扁平列表（表格用） */
    @GetMapping("/list")
    @SaCheckPermission("system:menu:list")
    public ApiResponse<List<SysMenu>> list() {
        return ApiResponse.success(menuService.listAll());
    }

    @PostMapping
    @SaCheckPermission("system:menu:add")
    public ApiResponse<SysMenu> add(@RequestBody SysMenu menu) {
        menuService.create(menu);
        return ApiResponse.success(menu);
    }

    @PutMapping
    @SaCheckPermission("system:menu:edit")
    public ApiResponse<SysMenu> edit(@RequestBody SysMenu menu) {
        menuService.update(menu);
        return ApiResponse.success(menu);
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:menu:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        menuService.remove(id);
        return ApiResponse.ok();
    }
}
