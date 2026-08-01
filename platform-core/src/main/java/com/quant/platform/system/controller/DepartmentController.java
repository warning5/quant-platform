package com.quant.platform.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.system.dto.DepartmentRequest;
import com.quant.platform.system.dto.DepartmentTreeVO;
import com.quant.platform.system.entity.SysDepartment;
import com.quant.platform.system.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理（需登录 + 权限）
 */
@RestController
@RequestMapping("/system/department")
@RequiredArgsConstructor
@SaCheckPermission("system:dept:list")
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping("/tree")
    @SaCheckPermission("system:dept:list")
    public ApiResponse<List<DepartmentTreeVO>> tree() {
        return ApiResponse.success(departmentService.tree());
    }

    @PostMapping
    @SaCheckPermission("system:dept:add")
    public ApiResponse<SysDepartment> add(@Valid @RequestBody DepartmentRequest req) {
        return ApiResponse.success(departmentService.create(req));
    }

    @PutMapping
    @SaCheckPermission("system:dept:edit")
    public ApiResponse<Void> edit(@Valid @RequestBody DepartmentRequest req) {
        departmentService.update(req);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:dept:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            departmentService.delete(id);
            return ApiResponse.ok();
        } catch (IllegalStateException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
