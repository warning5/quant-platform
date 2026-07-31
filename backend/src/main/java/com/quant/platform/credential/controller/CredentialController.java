package com.quant.platform.credential.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.quant.platform.audit.annotation.OperationLog;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.credential.dto.CredentialDetail;
import com.quant.platform.credential.dto.CredentialRequest;
import com.quant.platform.credential.entity.SysCredential;
import com.quant.platform.credential.service.CredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/credential")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping("/page")
    @SaCheckPermission("system:credential:list")
    public ApiResponse<IPage<SysCredential>> page(PageRequest req,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(credentialService.page(req, category, keyword));
    }

    @PostMapping
    @SaCheckPermission("system:credential:add")
    @OperationLog(module = "system:credential", action = "add", recordParam = false)
    public ApiResponse<Void> add(@RequestBody CredentialRequest req) {
        credentialService.create(req);
        return ApiResponse.ok();
    }

    @PutMapping
    @SaCheckPermission("system:credential:edit")
    @OperationLog(module = "system:credential", action = "edit", recordParam = false)
    public ApiResponse<Void> update(@RequestBody CredentialRequest req) {
        credentialService.update(req);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:credential:delete")
    @OperationLog(module = "system:credential", action = "delete", recordParam = false)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        credentialService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}")
    @SaCheckPermission("system:credential:list")
    public ApiResponse<CredentialDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(credentialService.detail(id));
    }
}
