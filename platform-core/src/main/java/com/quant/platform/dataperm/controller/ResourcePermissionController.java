package com.quant.platform.dataperm.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.dataperm.service.DataPermissionService;
import com.quant.spi.ResourceOptionVO;
import com.quant.platform.dataperm.service.ResourcePermissionVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据权限配置接口（方案C 页面配置入口）。
 * 仅登录可访问；实际可操作性（owner / ADMIN）由 DataPermissionService 内部校验。
 */
@RestController
@RequestMapping("/data-permission")
@RequiredArgsConstructor
@SaCheckLogin
public class ResourcePermissionController {

    private final DataPermissionService service;

    @GetMapping("/{type}/{id}")
    public ApiResponse<ResourcePermissionVO> get(@PathVariable String type, @PathVariable Long id) {
        return ApiResponse.success(service.getPermissions(type, id, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{type}/options")
    public ApiResponse<List<ResourceOptionVO>> options(@PathVariable String type) {
        return ApiResponse.success(service.listOptions(type));
    }

    /**
     * 授权对象下拉：USER/ROLE 返回扁平列表，DEPT 返回部门树。
     */
    @GetMapping("/grantees/{granteeType}")
    public ApiResponse<Object> grantees(@PathVariable String granteeType) {
        return ApiResponse.success(service.listGrantees(granteeType));
    }

    @PutMapping("/{type}/{id}/visibility")
    public ApiResponse<Void> setVisibility(@PathVariable String type, @PathVariable Long id,
                                             @RequestBody Map<String, String> body) {
        String visibility = body.get("visibility");
        if (visibility == null) {
            throw new BusinessException("visibility 必填");
        }
        service.setVisibility(type, id, visibility, StpUtil.getLoginIdAsLong());
        return ApiResponse.ok();
    }

    @PostMapping("/{type}/{id}/shares")
    public ApiResponse<Void> addShare(@PathVariable String type, @PathVariable Long id,
                                        @RequestBody ShareReq req) {
        service.addShare(type, id, req.getGranteeType(), req.getGranteeId(),
                req.getPermLevel(), StpUtil.getLoginIdAsLong());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{type}/{id}/shares/{shareId}")
    public ApiResponse<Void> removeShare(@PathVariable String type, @PathVariable Long id,
                                           @PathVariable Long shareId) {
        service.removeShare(type, id, shareId, StpUtil.getLoginIdAsLong());
        return ApiResponse.ok();
    }

    @Data
    public static class ShareReq {
        private String granteeType;
        private Long granteeId;
        private String permLevel;
    }
}
