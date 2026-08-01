package com.quant.platform.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.system.dto.CreateUserRequest;
import com.quant.platform.system.dto.ResetPasswordRequest;
import com.quant.platform.system.dto.UpdateUserRequest;
import com.quant.platform.system.entity.SysUser;
import com.quant.platform.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理（需登录 + 权限）
 */
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
@SaCheckPermission("system:user:list")
public class UserController {

    private final UserService userService;

    @GetMapping("/page")
    @SaCheckPermission("system:user:list")
    public ApiResponse<IPage<SysUser>> page(PageRequest req,
                                            @RequestParam(required = false) String username,
                                            @RequestParam(required = false) String nickname,
                                            @RequestParam(required = false) Integer status) {
        return ApiResponse.success(userService.pageUsers(req, username, nickname, status));
    }

    @PostMapping
    @SaCheckPermission("system:user:add")
    public ApiResponse<SysUser> add(@RequestBody CreateUserRequest request) {
        return ApiResponse.success(userService.createUser(request));
    }

    @PutMapping
    @SaCheckPermission("system:user:edit")
    public ApiResponse<SysUser> edit(@RequestBody UpdateUserRequest request) {
        return ApiResponse.success(userService.updateUser(request));
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:user:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reset-password")
    @SaCheckPermission("system:user:reset")
    public ApiResponse<Void> reset(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.getPassword());
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/roles")
    @SaCheckPermission("system:user:list")
    public ApiResponse<List<Long>> roles(@PathVariable Long id) {
        return ApiResponse.success(userService.getRoleIds(id));
    }

    @PostMapping("/{id}/roles")
    @SaCheckPermission("system:user:assign")
    public ApiResponse<Void> assign(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.assignRoles(id, roleIds);
        return ApiResponse.ok();
    }
}
