package com.quant.platform.system.configcenter;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.quant.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 参数配置中心控制器
 * 路径前缀 /system/config，权限沿用 system 模块细粒度模型（list/add/edit/delete）。
 * 该包位于 system 下，随 system 整体在 platform-core 内，业务调用方无需感知。
 */
@Slf4j
@RestController
@RequestMapping("/system/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /** 配置列表（含禁用项，管理页用） */
    @GetMapping("/list")
    @SaCheckPermission("system:config:list")
    public ApiResponse<List<SysConfig>> list() {
        return ApiResponse.success(configService.listAll());
    }

    /** 新增配置 */
    @PostMapping
    @SaCheckPermission("system:config:add")
    public ApiResponse<?> add(@RequestBody SysConfig config) {
        config.setId(null);
        configService.save(config);
        return ApiResponse.success("ok");
    }

    /** 修改配置（保存后缓存失效，下次读取回源） */
    @PutMapping
    @SaCheckPermission("system:config:edit")
    public ApiResponse<?> update(@RequestBody SysConfig config) {
        configService.save(config);
        return ApiResponse.success("ok");
    }

    /** 删除配置（软删） */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:config:delete")
    public ApiResponse<?> delete(@PathVariable Long id) {
        configService.delete(id);
        return ApiResponse.success("ok");
    }
}
