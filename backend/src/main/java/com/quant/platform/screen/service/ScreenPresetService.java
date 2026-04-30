package com.quant.platform.screen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.screen.entity.ScreenPreset;
import com.quant.platform.screen.mapper.ScreenPresetMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
     * 启动时初始化内置预设组合
     */
    @PostConstruct
    @Transactional
    public void initBuiltinPresets() {
        List<ScreenPreset> builtins = new ArrayList<>();

        // 1. 经典多因子组合
        builtins.add(buildPreset("经典多因子", "均衡配置：价值+动量+波动率+流动性+质量+成长，适合中长期持有",
                "[{\"factorCode\":\"SIZE\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"MOM20\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"AMIHUD\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_ROE\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_REVENUE_YOY\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"RSI14\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"LTE\",\"filterValue\":70}]"));

        // 2. 小盘成长组合
        builtins.add(buildPreset("小盘成长", "聚焦小市值+高成长+高ROE，适合追求高弹性的投资者",
                "[{\"factorCode\":\"SIZE\",\"direction\":-1,\"weight\":2,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_REVENUE_YOY\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":10}," +
                        "{\"factorCode\":\"FIN_ROE\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":8}," +
                        "{\"factorCode\":\"FIN_NET_PROFIT_YOY\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"MOM20\",\"direction\":1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 3. 低波动红利组合
        builtins.add(buildPreset("低波动红利", "低波动+高盈利质量，适合稳健型投资者",
                "[{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":2,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_EARNINGS_QUALITY\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0.5}," +
                        "{\"factorCode\":\"FIN_CF_TO_NP\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0.5}," +
                        "{\"factorCode\":\"FIN_BPS\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":2}," +
                        "{\"factorCode\":\"VOLUME_RATIO\",\"direction\":1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 4. 技术动量组合
        builtins.add(buildPreset("技术动量", "纯技术面选股：趋势跟踪+量价确认，适合短线交易",
                "[{\"factorCode\":\"MOM20\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"MOM60\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"RSI14\",\"direction\":-1,\"weight\":1,\"filterOp\":\"LTE\",\"filterValue\":70}," +
                        "{\"factorCode\":\"MACD\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"VOLUME_RATIO\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"BOLL_POS\",\"direction\":1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 5. 价值投资组合
        builtins.add(buildPreset("价值投资", "深度价值：低估值+高质量+高安全边际",
                "[{\"factorCode\":\"SIZE\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_EARNINGS_YIELD\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_BPS\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":3}," +
                        "{\"factorCode\":\"FIN_GROSS_MARGIN\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":20}," +
                        "{\"factorCode\":\"FIN_CF_QUALITY\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0.3}," +
                        "{\"factorCode\":\"FIN_DEBT_TO_ASSET\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"LT\",\"filterValue\":60}]"));

        // 6. 趋势突破组合
        builtins.add(buildPreset("趋势突破", "量价配合的趋势跟踪：强动量+放量确认+低波动率偏离，捕捉持续上涨个股",
                "[{\"factorCode\":\"MOM60\",\"direction\":1,\"weight\":2,\"filterOp\":\"GT\",\"filterValue\":5}," +
                        "{\"factorCode\":\"MOM20\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"VOLUME_RATIO\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":1}," +
                        "{\"factorCode\":\"BOLL_POS\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"VPCORR20\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 7. 高盈利质量组合
        builtins.add(buildPreset("高盈利质量", "聚焦盈利能力强、利润含金量高、财务健康的优质公司，适合价值成长型配置",
                "[{\"factorCode\":\"FIN_ROE\",\"direction\":1,\"weight\":2,\"filterOp\":\"GT\",\"filterValue\":10}," +
                        "{\"factorCode\":\"FIN_GROSS_MARGIN\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":25}," +
                        "{\"factorCode\":\"FIN_EARNINGS_QUALITY\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0.8}," +
                        "{\"factorCode\":\"FIN_CF_TO_NP\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0.8}," +
                        "{\"factorCode\":\"FIN_DEBT_TO_ASSET\",\"direction\":-1,\"weight\":1,\"filterOp\":\"LT\",\"filterValue\":50}," +
                        "{\"factorCode\":\"FIN_NET_MARGIN\",\"direction\":1,\"weight\":0.5,\"filterOp\":\"GT\",\"filterValue\":5}]"));

        // 8. 成长加速组合
        builtins.add(buildPreset("成长加速", "营收净利双高增长+趋势加速，捕捉处于高速成长期的公司",
                "[{\"factorCode\":\"FIN_REVENUE_YOY\",\"direction\":1,\"weight\":2,\"filterOp\":\"GT\",\"filterValue\":15}," +
                        "{\"factorCode\":\"FIN_NET_PROFIT_YOY\",\"direction\":1,\"weight\":2,\"filterOp\":\"GT\",\"filterValue\":20}," +
                        "{\"factorCode\":\"PRICE_MOM_ACC\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"FIN_ROE\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":8}," +
                        "{\"factorCode\":\"MOM20\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"SIZE\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 9. 低估反转组合
        builtins.add(buildPreset("低估反转", "超跌低估值个股的均值回归：短期弱势+基本面支撑+估值低位，适合逆向投资",
                "[{\"factorCode\":\"REVERSAL5\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"RSI14\",\"direction\":-1,\"weight\":1.5,\"filterOp\":\"LTE\",\"filterValue\":40}," +
                        "{\"factorCode\":\"FIN_BPS\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":2}," +
                        "{\"factorCode\":\"FIN_ROE\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":5}," +
                        "{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_DEBT_TO_ASSET\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"LT\",\"filterValue\":65}]"));

        // 10. 量价异动组合
        builtins.add(buildPreset("量价异动", "捕捉资金异常涌入迹象：成交量突变+量价背离修复+情绪因子，适合短线博弈",
                "[{\"factorCode\":\"VOLUME_SURPRISE\",\"direction\":1,\"weight\":2,\"filterOp\":\"GT\",\"filterValue\":0.1}," +
                        "{\"factorCode\":\"TURNOVER_ANOMALY\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":1.2}," +
                        "{\"factorCode\":\"VROC12\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"VPCORR20\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0.2}," +
                        "{\"factorCode\":\"MOM5\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"SIZE\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 11. 财务健康组合
        builtins.add(buildPreset("财务健康", "偿债能力强+营运效率高+现金流充裕，规避财务风险，适合防御型配置",
                "[{\"factorCode\":\"FIN_CURRENT_RATIO\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":1.5}," +
                        "{\"factorCode\":\"FIN_DEBT_TO_ASSET\",\"direction\":-1,\"weight\":1.5,\"filterOp\":\"LT\",\"filterValue\":50}," +
                        "{\"factorCode\":\"FIN_CF_TO_REVENUE\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0.05}," +
                        "{\"factorCode\":\"FIN_AR_TURNOVER\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_ASSETS_TURNOVER\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":0.5,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        // 12. 综合打分组合
        builtins.add(buildPreset("综合打分", "技术+基本面+情绪全覆盖：八因子均衡打分，降低单一因子的随机性",
                "[{\"factorCode\":\"FIN_ROE\",\"direction\":1,\"weight\":1.2,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_REVENUE_YOY\",\"direction\":1,\"weight\":1.2,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"MOM60\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"SIZE\",\"direction\":-1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"FIN_EARNINGS_QUALITY\",\"direction\":1,\"weight\":1,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"VOLUME_RATIO\",\"direction\":1,\"weight\":0.8,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"RSI14\",\"direction\":-1,\"weight\":0.8,\"filterOp\":\"LTE\",\"filterValue\":70}]"));

        // 13. 经典技术指标组合（参考 baostock 用户案例：均线+MACD+RSI+布林带）
        builtins.add(buildPreset("经典技术指标", "均线趋势+MACD动能+RSI超卖回避+布林带位置：经典技术分析四件套，适合中短线选股",
                "[{\"factorCode\":\"MOM20\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"MOM60\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"MACD\",\"direction\":1,\"weight\":1.5,\"filterOp\":\"NONE\",\"filterValue\":null}," +
                        "{\"factorCode\":\"RSI14\",\"direction\":-1,\"weight\":1,\"filterOp\":\"LTE\",\"filterValue\":70}," +
                        "{\"factorCode\":\"BOLL_POS\",\"direction\":1,\"weight\":1,\"filterOp\":\"GT\",\"filterValue\":0.2}," +
                        "{\"factorCode\":\"VPCORR20\",\"direction\":1,\"weight\":0.8,\"filterOp\":\"GT\",\"filterValue\":0}," +
                        "{\"factorCode\":\"VOL20\",\"direction\":-1,\"weight\":0.7,\"filterOp\":\"NONE\",\"filterValue\":null}]"));

        for (ScreenPreset preset : builtins) {
            long count = presetMapper.selectCount(
                    new LambdaQueryWrapper<ScreenPreset>()
                            .eq(ScreenPreset::getPresetName, preset.getPresetName())
                            .eq(ScreenPreset::getIsBuiltin, 1));
            if (count == 0) {
                presetMapper.insert(preset);
            }
        }
        log.info("Built-in screen presets initialized");
    }

    private ScreenPreset buildPreset(String name, String desc, String config) {
        return ScreenPreset.builder()
                .presetName(name)
                .description(desc)
                .factorConfig(config)
                .isBuiltin(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

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
