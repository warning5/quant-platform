package com.quant.platform.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.system.dto.MenuRequest;
import com.quant.platform.system.entity.SysMenu;
import com.quant.platform.system.service.MenuService;
import jakarta.validation.Valid;
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

    /** 菜单树（含按钮）—— 角色分配菜单时也需要读取，故对 system:role:assign 开放 */
    @GetMapping("/tree")
    public ApiResponse<List<SysMenu>> tree() {
        StpUtil.checkPermissionOr("system:menu:list", "system:role:assign");
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
    public ApiResponse<SysMenu> add(@Valid @RequestBody MenuRequest req) {
        menuService.create(req);
        return ApiResponse.success(new SysMenu());
    }

    @PutMapping
    @SaCheckPermission("system:menu:edit")
    public ApiResponse<SysMenu> edit(@Valid @RequestBody MenuRequest req) {
        menuService.update(req);
        return ApiResponse.success(new SysMenu());
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:menu:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        menuService.remove(id);
        return ApiResponse.ok();
    }
}
