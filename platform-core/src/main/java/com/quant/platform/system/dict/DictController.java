package com.quant.platform.system.dict;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.quant.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 字典管理控制器
 * 路径前缀 /system/dict，权限沿用 system 模块的细粒度模型（list/add/edit/delete）。
 * 该包位于 system 下，后续解耦阶段（platform-core）随 system 整体迁移，无需改业务调用方。
 */
@Slf4j
@RestController
@RequestMapping("/system/dict")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    /** 字典类型列表 */
    @GetMapping("/types")
    @SaCheckPermission("system:dict:list")
    public ApiResponse<List<SysDictType>> listTypes() {
        return ApiResponse.success(dictService.listTypes());
    }

    /** 某类型下的字典项：all=true 含禁用（管理页编辑用），默认只返回启用项 */
    @GetMapping("/data")
    @SaCheckPermission("system:dict:list")
    public ApiResponse<List<SysDictData>> listData(@RequestParam String dictType,
                                                   @RequestParam(required = false, defaultValue = "false") boolean all) {
        return ApiResponse.success(all ? dictService.listDataAll(dictType) : dictService.listByType(dictType));
    }

    /** 新增字典类型 */
    @PostMapping("/type")
    @SaCheckPermission("system:dict:add")
    public ApiResponse<?> addType(@Valid @RequestBody SysDictType type) {
        dictService.saveType(type);
        return ApiResponse.success("ok");
    }

    /** 修改字典类型（dictType 为唯一键，不可改） */
    @PutMapping("/type")
    @SaCheckPermission("system:dict:edit")
    public ApiResponse<?> updateType(@Valid @RequestBody SysDictType type) {
        dictService.updateType(type);
        return ApiResponse.success("ok");
    }

    /** 新增字典项 */
    @PostMapping("/data")
    @SaCheckPermission("system:dict:add")
    public ApiResponse<?> addData(@Valid @RequestBody SysDictData data) {
        dictService.saveData(data);
        return ApiResponse.success("ok");
    }

    /** 修改字典项 */
    @PutMapping("/data")
    @SaCheckPermission("system:dict:edit")
    public ApiResponse<?> updateData(@Valid @RequestBody SysDictData data) {
        dictService.updateData(data);
        return ApiResponse.success("ok");
    }

    /** 删除字典项（软删） */
    @DeleteMapping("/data/{id}")
    @SaCheckPermission("system:dict:delete")
    public ApiResponse<?> deleteData(@PathVariable Long id) {
        dictService.deleteData(id);
        return ApiResponse.success("ok");
    }

    /** 删除字典类型（级联软删其数据项） */
    @DeleteMapping("/type/{dictType}")
    @SaCheckPermission("system:dict:delete")
    public ApiResponse<?> deleteType(@PathVariable String dictType) {
        dictService.deleteType(dictType);
        return ApiResponse.success("ok");
    }
}
