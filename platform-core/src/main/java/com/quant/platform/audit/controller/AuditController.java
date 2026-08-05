package com.quant.platform.audit.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.quant.platform.audit.entity.SysOperationLog;
import com.quant.platform.audit.service.OperationLogService;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.dto.PageRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/audit")
@RequiredArgsConstructor
@SaCheckPermission("system:audit:list")
public class AuditController {

    private final OperationLogService operationLogService;

    @GetMapping("/page")
    @SaCheckPermission("system:audit:list")
    public ApiResponse<IPage<SysOperationLog>> page(@Valid PageRequest req,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ApiResponse.success(operationLogService.page(req, username, module, action, startTime, endTime));
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:audit:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        operationLogService.delete(id);
        return ApiResponse.ok();
    }
}
