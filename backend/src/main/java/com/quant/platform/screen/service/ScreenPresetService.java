package com.quant.platform.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.screen.entity.ScreenPreset;
import com.quant.platform.screen.mapper.ScreenPresetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 因子预设组合服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenPresetService {

    private final ScreenPresetMapper presetMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询所有预设（内置+自定义）
     */
    public List<ScreenPreset> listAll() {
        return presetMapper.selectList(
                new LambdaQueryWrapper<ScreenPreset>().orderByAsc(ScreenPreset::getIsBuiltin)
                        .orderByDesc(ScreenPreset::getUpdatedAt));
    }

    /**
     * 查询内置预设
     */
    public List<ScreenPreset> listBuiltin() {
        return presetMapper.selectList(
                new LambdaQueryWrapper<ScreenPreset>().eq(ScreenPreset::getIsBuiltin, 1)
                        .orderByAsc(ScreenPreset::getId));
    }

    /**
     * 获取单个预设
     */
    public ScreenPreset getById(Long id) {
        return presetMapper.selectById(id);
    }

    /**
     * 解析预设的因子配置
     */
    public List<Map<String, Object>> parseFactorConfig(String factorConfig) {
        try {
            return objectMapper.readValue(factorConfig, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse factor config: {}", factorConfig, e);
            return Collections.emptyList();
        }
    }

    /**
     * 创建自定义预设
     */
    @Transactional
    public ScreenPreset create(ScreenPreset preset) {
        preset.setIsBuiltin(0);
        preset.setCreatedAt(LocalDateTime.now());
        preset.setUpdatedAt(LocalDateTime.now());
        presetMapper.insert(preset);
        return preset;
    }

    /**
     * 更新自定义预设
     */
    @Transactional
    public ScreenPreset update(Long id, ScreenPreset update) {
        ScreenPreset existing = presetMapper.selectById(id);
        if (existing == null) return null;
        if (existing.getIsBuiltin() == 1) return null; // 内置不可修改
        existing.setPresetName(update.getPresetName());
        existing.setDescription(update.getDescription());
        existing.setFactorConfig(update.getFactorConfig());
        existing.setUpdatedAt(LocalDateTime.now());
        presetMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除自定义预设
     */
    @Transactional
    public boolean delete(Long id) {
        ScreenPreset existing = presetMapper.selectById(id);
        if (existing == null || existing.getIsBuiltin() == 1) return false;
        return presetMapper.deleteById(id) > 0;
    }
}
