package com.quant.platform.factor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.engine.BuiltinFactors;
import com.quant.platform.factor.engine.FactorComputeEngine;
import com.quant.platform.factor.engine.ScriptedFactorEngine;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.mapper.FactorTestReportMapper;
import com.quant.platform.factor.mapper.FactorValueMapper;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.config.ClickHouseConfig;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因子管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorService {

    private final FactorDefinitionMapper factorMapper;
    private final FactorValueMapper factorValueMapper;
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final ClickHouseConfig clickHouseConfig;
    private final FactorTestReportMapper testReportMapper;
    private final FactorComputeEngine computeEngine;
    private final ScriptedFactorEngine scriptedEngine;
    private final MarketDataService marketDataService;
    private final StockInfoMapper stockInfoMapper;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /**
     * 初始化内置因子
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initBuiltinFactors() {
        List<String[]> builtins = List.of(
                // 动量因子
                new String[]{"MOM5", "5日动量", "过去5日价格涨幅", "MOMENTUM"},
                new String[]{"MOM20", "20日动量", "过去20日价格涨幅", "MOMENTUM"},
                new String[]{"MOM60", "60日动量", "过去60日价格涨幅", "MOMENTUM"},
                new String[]{"MOM120", "120日动量", "过去120日价格涨幅", "MOMENTUM"},
                new String[]{"REVERSAL5", "5日反转", "过去5日价格涨幅取反（短期反转效应）", "MOMENTUM"},
                new String[]{"PRICE_MOM_ACC", "动量加速度", "近5日动量减去近20日动量", "MOMENTUM"},
                // 波动率因子
                new String[]{"VOL5", "5日波动率", "过去5日日收益率年化标准差", "VOLATILITY"},
                new String[]{"VOL20", "20日波动率", "过去20日日收益率年化标准差", "VOLATILITY"},
                new String[]{"VOL60", "60日波动率", "过去60日日收益率年化标准差", "VOLATILITY"},
                new String[]{"VOL_RATIO", "波动率比", "短期5日波动率/长期60日波动率", "VOLATILITY"},
                // 流动性因子
                new String[]{"AMIHUD", "Amihud非流动性", "日均|收益|/成交额（对数），值越大流动性越差", "LIQUIDITY"},
                new String[]{"TURNOVER_CHANGE", "换手率变化", "近5日均换手率/前20日均换手率", "LIQUIDITY"},
                new String[]{"VOLUME_RATIO", "量比", "近5日均量/前20日均量", "LIQUIDITY"},
                // 技术因子
                new String[]{"TURN20", "20日平均换手率", "过去20日日均换手率", "TECHNICAL"},
                new String[]{"RSI5", "5日RSI", "5日相对强弱指数", "TECHNICAL"},
                new String[]{"RSI14", "14日RSI", "14日相对强弱指数", "TECHNICAL"},
                new String[]{"MACD", "MACD", "12日EMA减去26日EMA（DIF值）", "TECHNICAL"},
                new String[]{"KDJ_K", "KDJ-K值", "KDJ指标K值（9日周期）", "TECHNICAL"},
                new String[]{"ATR20", "20日ATR", "20日平均真实波幅", "TECHNICAL"},
                new String[]{"UPPER_SHADOW", "上影线比率", "(最高-收盘)/(最高-最低)，反映抛压", "TECHNICAL"},
                new String[]{"BOLL_POS", "布林带位置", "价格在20日布林带中的相对位置", "TECHNICAL"},
                new String[]{"VPCORR20", "20日量价相关性", "20日成交量与价格的Pearson相关系数", "VOLUME_PRICE"},
                // 价值因子
                new String[]{"SIZE", "市值因子", "总市值对数", "VALUE"},
                // 新增经典技术指标
                new String[]{"PSY12", "心理线", "12日内上涨天数占比×100，反映市场多空情绪", "TECHNICAL"},
                new String[]{"SRDM30", "标准化动量", "30日标准化价格动量，(当前价-30日前价)/30日价格标准差", "MOMENTUM"},
                new String[]{"BOLL_MID", "布林带中轨", "20日收盘价简单移动平均（布林带中轨）", "TECHNICAL"},
                new String[]{"MFI14", "资金流向指标", "14日资金流向指数，类RSI但引入成交量，衡量资金流入流出强度", "VOLUME_PRICE"},
                new String[]{"BBI", "多空指数", "(MA3+MA6+MA12+MA24)/4，代表多空平衡点", "TECHNICAL"},
                new String[]{"MA5", "5日均线", "5日简单移动平均，反映价格短期趋势", "TECHNICAL"},
                new String[]{"EMA5", "5日指数均线", "5日指数移动平均，对近期价格赋予更高权重", "TECHNICAL"},
                new String[]{"WR14", "14日威廉指标", "14日威廉指标，衡量超买超卖状态", "TECHNICAL"},
                new String[]{"OBV", "能量潮", "近30日OBV累积，将成交量与价格方向挂钩", "VOLUME_PRICE"},
                new String[]{"VROC12", "成交量变化率", "12日成交量变动速率百分比", "VOLUME_PRICE"},
                new String[]{"PVT", "量价趋势", "近30日PVT累积，按涨跌幅加权成交量", "VOLUME_PRICE"},
                new String[]{"PRICEOSC", "价格振荡", "(MA12-MA26)/MA26×100，长短期均线离差率", "TECHNICAL"},
                new String[]{"VR26", "成交量比率", "26日上涨日成交量/下跌日成交量×100", "VOLUME_PRICE"},
                new String[]{"BIAS6", "6日乖离率", "收盘价与6日均线的偏离程度百分比", "TECHNICAL"},
                new String[]{"VSTD10", "成交量标准差", "10日成交量标准差，衡量成交量波动幅度", "VOLUME_PRICE"},
                new String[]{"ROC12", "变动速率", "12日价格变动速率，(当前价-12日前价)/12日前价×100", "MOMENTUM"},
                new String[]{"CCI14", "顺势指标", "14日CCI，衡量价格偏离统计均值的程度", "TECHNICAL"},
                new String[]{"TRIX12", "三重指数平滑", "12日TRIX，三重EMA变化率，过滤噪音识别长期趋势", "TECHNICAL"},
                new String[]{"VMA5", "5日量均线", "5日成交量移动平均，反映成交量短期趋势", "VOLUME_PRICE"},
                new String[]{"ATR14", "14日ATR", "14日平均真实波幅，衡量市场波动性", "VOLATILITY"},
                new String[]{"MTM6", "6日动量", "当前价减6日前价，反映价格绝对动量", "MOMENTUM"},
                new String[]{"VOSC", "成交量震荡", "(成交量MA12-成交量MA26)/成交量MA26×100", "VOLUME_PRICE"},
                // 新增26个技术因子 (2026-04-16)
                new String[]{"ARBR", "人气意愿指标", "26日AR/BR比值，反映市场多空人气", "TECHNICAL"},
                new String[]{"BBIBOLL", "布林多空线", "多周期均线的标准化偏离度", "TECHNICAL"},
                new String[]{"CDP", "逆势操作", "基于前日高低收计算的逆势操作压力支撑位", "TECHNICAL"},
                new String[]{"ENV14", "14日包络线", "收盘价在MA14上下5%通道中的相对位置", "TECHNICAL"},
                new String[]{"DBCD", "异同离差乖离率", "BIAS12的多步平滑版本", "TECHNICAL"},
                new String[]{"CR", "能量指标", "26日中间价多空力量比×100", "VOLUME_PRICE"},
                new String[]{"DPO", "去势价格振荡", "收盘价减去20日前移动平均，消除趋势影响", "TECHNICAL"},
                new String[]{"WR12", "12日威廉指标", "12日威廉指标，衡量超买超卖", "TECHNICAL"},
                new String[]{"VRSI6", "6日成交量RSI", "基于成交量变化计算的RSI", "VOLUME_PRICE"},
                new String[]{"BIAS12", "12日乖离率", "收盘价与12日均线的偏离程度", "TECHNICAL"},
                new String[]{"BIAS24", "24日乖离率", "收盘价与24日均线的偏离程度", "TECHNICAL"},
                new String[]{"RCCD", "BIAS变化率", "BIAS12的5日变化率，反映乖离率趋势", "TECHNICAL"},
                new String[]{"DDI", "方向标准差", "14日价格方向差的波动性", "TECHNICAL"},
                new String[]{"CVLT", "变动率", "累积/派发比的变化率", "TECHNICAL"},
                new String[]{"VHF", "趋势清晰度", "28日价格方向一致性，值越高趋势越清晰", "TECHNICAL"},
                new String[]{"SI", "摆动指标", "10日(收盘-开盘)/(最高-最低)均值×100", "TECHNICAL"},
                new String[]{"MASS", "质量指数", "25日高低价差EMA比值之和", "VOLUME_PRICE"},
                new String[]{"SRMI9", "慢速随机指标", "9日随机指标的D值（慢速KDJ）", "TECHNICAL"},
                new String[]{"VMACD", "成交量MACD", "成交量12日EMA减26日EMA的差值", "VOLUME_PRICE"},
                new String[]{"LWR", "慢速威廉指标", "14日威廉指标（更长周期）", "TECHNICAL"},
                new String[]{"ADTM", "动态买卖气指标", "23日买卖力道差值比率", "VOLUME_PRICE"},
                new String[]{"MICD", "平滑异同平均", "ROC12的双EMA差值", "TECHNICAL"},
                new String[]{"DMA", "平行线差", "MA10减MA50的差值", "TECHNICAL"},
                new String[]{"TAPI", "成交量乘数", "当日成交量/收盘价（万元）", "VOLUME_PRICE"},
                new String[]{"MI12", "动量指标", "12日ROC的6日EMA平滑值", "MOMENTUM"},
                new String[]{"MTM_PCT", "动量百分比", "6日价格变动百分比", "MOMENTUM"},
                new String[]{"WAD", "威廉斯累积派发", "累积价格方向变动量", "VOLUME_PRICE"},
                // 新增25个财务因子 (2026-04-16)
                new String[]{"FIN_GROSS_MARGIN", "毛利率", "最近年报毛利率(%)", "FUNDAMENTAL"},
                new String[]{"FIN_NET_MARGIN", "净利率", "最近年报净利率(%)", "FUNDAMENTAL"},
                new String[]{"FIN_ROE", "净资产收益率", "最近年报ROE(%)", "FUNDAMENTAL"},
                new String[]{"FIN_ROA", "总资产收益率", "最近年报ROA(%)", "FUNDAMENTAL"},
                new String[]{"FIN_TOTAL_COST_RATIO", "营业成本率", "营业总成本/营业总收入(%)", "FUNDAMENTAL"},
                new String[]{"FIN_PERIOD_EXPENSE_RATIO", "期间费用率", "毛利率-净利率(%)", "FUNDAMENTAL"},
                new String[]{"FIN_EBIT_MARGIN", "EBIT利润率", "EBIT/营业总收入(%)", "FUNDAMENTAL"},
                new String[]{"FIN_REVENUE_YOY", "营收同比增长率", "最近年报营业收入同比增速(%)", "FUNDAMENTAL"},
                new String[]{"FIN_NET_PROFIT_YOY", "净利润同比增长率", "最近年报净利润同比增速(%)", "FUNDAMENTAL"},
                new String[]{"FIN_OPERATING_PROFIT_YOY", "营业利润同比增长率", "最近年报营业利润同比增速(%)", "FUNDAMENTAL"},
                new String[]{"FIN_TOTAL_ASSETS_YOY", "总资产同比增长率", "最近年报总资产同比增速(%)", "FUNDAMENTAL"},
                new String[]{"FIN_EPS_YOY", "每股收益同比增长率", "最近年报EPS同比增速(%)", "FUNDAMENTAL"},
                new String[]{"FIN_CURRENT_RATIO", "流动比率", "最近年报流动比率", "FUNDAMENTAL"},
                new String[]{"FIN_QUICK_RATIO", "速动比率", "最近年报速动比率", "FUNDAMENTAL"},
                new String[]{"FIN_DEBT_TO_ASSET", "资产负债率", "最近年报资产负债率(%)", "FUNDAMENTAL"},
                new String[]{"FIN_DEBT_TO_EQUITY", "产权比率", "最近年报负债/权益比率", "FUNDAMENTAL"},
                new String[]{"FIN_AR_TURNOVER", "应收账款周转率", "最近年报应收账款周转率(次)", "FUNDAMENTAL"},
                new String[]{"FIN_AR_TURNOVER_DAYS", "应收账款周转天数", "最近年报应收账款周转天数", "FUNDAMENTAL"},
                new String[]{"FIN_ASSETS_TURNOVER", "总资产周转率", "最近年报总资产周转率(次)", "FUNDAMENTAL"},
                new String[]{"FIN_INVENTORY_TURNOVER", "存货周转率", "最近年报存货周转率(次)", "FUNDAMENTAL"},
                new String[]{"FIN_INVENTORY_TURNOVER_DAYS", "存货周转天数", "最近年报存货周转天数", "FUNDAMENTAL"},
                new String[]{"FIN_CF_TO_NP", "经营现金流/净利润", "最近年报经营现金流/净利润", "FUNDAMENTAL"},
                new String[]{"FIN_CF_PER_SHARE", "每股经营现金流", "最近年报每股经营现金流(元)", "FUNDAMENTAL"},
                new String[]{"FIN_CF_TO_REVENUE", "经营现金流/营收", "最近年报经营现金流/营业总收入", "FUNDAMENTAL"},
                new String[]{"FIN_BPS", "每股净资产", "最近年报每股净资产BPS(元)", "FUNDAMENTAL"},
                // 新增质量因子 (2026-04-16)
                new String[]{"FIN_ROE_STABILITY", "ROE稳定性", "ROE/ROA比率，越低说明杠杆越低盈利越稳定", "QUALITY"},
                new String[]{"FIN_EARNINGS_QUALITY", "盈余质量", "经营现金流/净利润，越接近1利润含金量越高", "QUALITY"},
                new String[]{"FIN_GROSS_MARGIN_QUALITY", "毛利率质量", "毛利率/40%线性映射，越高说明定价权越强", "QUALITY"},
                new String[]{"FIN_OPERATING_LEVERAGE", "经营杠杆", "期间费用率/净利率，值越大固定成本占比越高", "QUALITY"},
                new String[]{"FIN_CF_QUALITY", "现金流质量", "经营现金流/营收比率，反映创现能力", "QUALITY"},
                // 新增价值因子 (2026-04-16)
                new String[]{"FIN_EARNINGS_YIELD", "盈利收益率", "ROE×净利率/10000，综合反映盈利能力", "VALUE"},
                new String[]{"FIN_BOOK_VALUE", "账面价值", "每股净资产BPS的自然对数", "VALUE"},
                new String[]{"FIN_REVENUE_QUALITY", "营收质量", "营收同比增长率×净利率/100", "VALUE"},
                // 新增情绪因子 (2026-04-16)
                new String[]{"LIMIT_UP_COUNT", "涨停次数", "近20日涨停次数（涨跌幅>=9.8%）", "SENTIMENT"},
                new String[]{"TURNOVER_ANOMALY", "换手率异常度", "近5日均换手率/60日均换手率，反映关注度突变", "SENTIMENT"},
                new String[]{"VOLUME_SURPRISE", "成交量惊喜", "近5日均量/20日均量的自然对数，反映资金涌入程度", "SENTIMENT"}
        );

        for (String[] b : builtins) {
            String code = b[0];
            if (!factorMapper.existsByFactorCode(code)) {
                FactorDefinition fd = FactorDefinition.builder()
                        .factorCode(code)
                        .factorName(b[1])
                        .description(b[2])
                        .category(FactorDefinition.FactorCategory.valueOf(b[3]))
                        .factorType(FactorDefinition.FactorType.BUILTIN)
                        .status(FactorDefinition.FactorStatus.ACTIVE)
                        .version(1)
                        .author("system")
                        .stockPool("全A")
                        .build();
                factorMapper.insert(fd);
            }
        }
        log.info("Builtin factors initialized");
    }

    /**
     * 查询因子列表（分页+搜索）
     */
    public IPage<FactorDefinition> searchFactors(String keyword, String category, String status,
                                                 int page, int size) {
        FactorDefinition.FactorCategory cat = category != null ? FactorDefinition.FactorCategory.valueOf(category) : null;
        FactorDefinition.FactorStatus st = status != null ? FactorDefinition.FactorStatus.valueOf(status) : null;

        LambdaQueryWrapper<FactorDefinition> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(keyword != null, w -> w
                .like(FactorDefinition::getFactorName, keyword)
                .or()
                .like(FactorDefinition::getFactorCode, keyword))
                .eq(cat != null, FactorDefinition::getCategory, cat)
                .eq(st != null, FactorDefinition::getStatus, st)
                .orderByDesc(FactorDefinition::getCreatedAt);

        return factorMapper.selectPage(new Page<>(page + 1, size), wrapper);
    }

    /**
     * 批量查询因子计算状态（因子值数量 + 检测报告数量 + 计算日期范围）
     */
    public Map<String, Map<String, Object>> batchGetFactorStatus(List<String> factorCodes) {
        if (factorCodes == null || factorCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new java.util.HashMap<>();

        // 1. 批量查询每个因子的值数量（逐个）
        for (String code : factorCodes) {
            long valueCount = factorValueMapper.selectCount(
                    new LambdaQueryWrapper<FactorValue>().eq(FactorValue::getFactorCode, code));
            long testCount = testReportMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorTestReport>()
                            .eq(FactorTestReport::getFactorCode, code));
            java.util.Map<String, Object> entry = new java.util.HashMap<>();
            entry.put("valueCount", valueCount);
            entry.put("testCount", testCount);
            result.put(code, entry);
        }

        // 2. 批量查询日期范围（一条 SQL，IN 子句）
        try {
            List<Map<String, Object>> dateRanges = factorValueMapper.selectDateRangeByFactorCodes(factorCodes);
            for (Map<String, Object> row : dateRanges) {
                String code = (String) row.get("factor_code");
                if (result.containsKey(code)) {
                    Object minDate = row.get("min_date");
                    Object maxDate = row.get("max_date");
                    if (minDate != null) result.get(code).put("minDate", minDate.toString());
                    if (maxDate != null) result.get(code).put("maxDate", maxDate.toString());
                }
            }
        } catch (Exception e) {
            log.warn("查询因子日期范围失败，忽略: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 删除指定因子的所有因子值
     */
    @Transactional
    public int deleteFactorValues(Long factorId) {
        FactorDefinition factor = getById(factorId);
        LambdaQueryWrapper<FactorValue> wrapper = new LambdaQueryWrapper<FactorValue>()
                .eq(FactorValue::getFactorCode, factor.getFactorCode());
        int deleted = factorValueMapper.delete(wrapper);
        log.info("Deleted {} factor values for [{}]", deleted, factor.getFactorCode());
        return deleted;
    }

    /**
     * 获取因子详情
     */
    public FactorDefinition getById(Long id) {
        FactorDefinition result = factorMapper.selectById(id);
        if (result == null) {
            throw new ResourceNotFoundException("因子", id);
        }
        return result;
    }

    public FactorDefinition getByCode(String code) {
        FactorDefinition result = factorMapper.selectOne(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getFactorCode, code));
        if (result == null) {
            throw new ResourceNotFoundException("因子代码不存在: " + code);
        }
        return result;
    }

    /**
     * 创建自定义因子
     */
    @Transactional
    public FactorDefinition createFactor(FactorDefinition factor) {
        if (factorMapper.existsByFactorCode(factor.getFactorCode())) {
            throw new BusinessException("因子代码已存在: " + factor.getFactorCode());
        }
        if (factor.getFactorType() == FactorDefinition.FactorType.SCRIPTED
                && factor.getScriptCode() != null) {
            String error = scriptedEngine.validateScript(factor.getScriptCode());
            if (error != null) {
                throw new BusinessException("Groovy脚本语法错误: " + error);
            }
        }
        factorMapper.insert(factor);
        return factor;
    }

    /**
     * 更新因子（版本+1）
     */
    @Transactional
    public FactorDefinition updateFactor(Long id, FactorDefinition update) {
        FactorDefinition existing = getById(id);
        if (existing.getFactorType() == FactorDefinition.FactorType.BUILTIN) {
            throw new BusinessException("内置因子不可修改");
        }
        existing.setFactorName(update.getFactorName());
        existing.setDescription(update.getDescription());
        existing.setCategory(update.getCategory());
        existing.setScriptCode(update.getScriptCode());
        existing.setParametersJson(update.getParametersJson());
        existing.setVersion(existing.getVersion() + 1);
        existing.setStatus(FactorDefinition.FactorStatus.DRAFT);
        factorMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除因子
     */
    @Transactional
    public void deleteFactor(Long id) {
        FactorDefinition f = getById(id);
        if (f.getFactorType() == FactorDefinition.FactorType.BUILTIN) {
            throw new BusinessException("内置因子不可删除");
        }
        factorMapper.deleteById(id);
    }

    /**
     * 激活/停用因子
     */
    @Transactional
    public FactorDefinition changeStatus(Long id, FactorDefinition.FactorStatus status) {
        FactorDefinition f = getById(id);
        f.setStatus(status);
        factorMapper.updateById(f);
        return f;
    }

    /**
     * 触发因子计算，返回 factorCode 供前端订阅 WebSocket 进度
     */
    public String triggerCompute(Long factorId, LocalDate startDate, LocalDate endDate) {
        FactorDefinition factor = getById(factorId);
        List<String> symbols = marketDataService.getAllSymbols();
        computeEngine.computeFactor(factor, startDate, endDate, symbols);
        return factor.getFactorCode();
    }

    /**
     * 批量并行触发多个因子计算（每个因子在独立线程中执行）
     * @param factorCodes 因子代码列表
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param incremental 是否增量计算（跳过已有数据的日期）
     * @return 已提交的因子代码列表
     */
    public Map<String, Object> triggerBatchCompute(List<String> factorCodes, LocalDate startDate, LocalDate endDate,
                                                     boolean incremental, boolean force) {
        List<String> submitted = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        List<String> symbols = marketDataService.getAllSymbols();

        // 广播批量计算开始事件
        messagingTemplate.convertAndSend("/topic/factor/batch-log", Map.of(
                "type", "BATCH_START",
                "totalFactors", factorCodes.size(),
                "symbolCount", symbols.size(),
                "startDate", startDate.toString(),
                "endDate", endDate.toString(),
                "incremental", incremental,
                "timestamp", java.time.LocalDateTime.now().toString()
        ));

        for (String code : factorCodes) {
            FactorDefinition factor = factorMapper.selectOne(
                    new LambdaQueryWrapper<FactorDefinition>()
                            .eq(FactorDefinition::getFactorCode, code)
                            .eq(FactorDefinition::getStatus, FactorDefinition.FactorStatus.ACTIVE));
            if (factor == null) {
                skipped.add(code + "(未找到或未激活)");
                continue;
            }

            if (incremental) {
                LocalDate latestDate = computeEngine.findLatestDate(code);
                if (!force && latestDate != null && !latestDate.isBefore(endDate)) {
                    skipped.add(code + "(已计算到" + latestDate + ")");
                    continue;
                }
                log.info("[{}] incremental: existing data up to {}, computing from {}", code, latestDate, startDate);
                try {
                    computeEngine.computeFactorIncremental(factor, startDate, endDate, symbols);
                } catch (Exception e) {
                    skipped.add(code + "(提交失败:" + e.getMessage().split("\\n")[0] + ")");
                    log.warn("[{}] 提交增量计算失败: {}", code, e.getMessage());
                    continue;
                }
            } else {
                try {
                    computeEngine.computeFactor(factor, startDate, endDate, symbols);
                } catch (Exception e) {
                    skipped.add(code + "(提交失败:" + e.getMessage().split("\\n")[0] + ")");
                    log.warn("[{}] 提交全量计算失败: {}", code, e.getMessage());
                    continue;
                }
            }
            submitted.add(code);
        }

        // 广播批量计算提交完成
        messagingTemplate.convertAndSend("/topic/factor/batch-log", Map.of(
                "type", "BATCH_SUBMITTED",
                "submitted", submitted,
                "skipped", skipped,
                "timestamp", java.time.LocalDateTime.now().toString()
        ));

        return Map.of("submitted", submitted, "skipped", skipped,
                "totalFactors", factorCodes.size(), "symbolCount", symbols.size());
    }

    /**
     * 查询因子已有数据量（返回 factorCode + count，供前端判断是否需要先触发计算）
     * 优化：先用 LIMIT 1 判断是否有数据，有数据再精确 COUNT
     */
    public Map<String, Object> getFactorValueCount(Long factorId) {
        FactorDefinition factor = getById(factorId);
        // 先用 LIMIT 1 快速判断是否有数据（避免千万行 COUNT 扫描）
        Long quickCheck = factorValueMapper.selectCount(
                new LambdaQueryWrapper<FactorValue>()
                        .eq(FactorValue::getFactorCode, factor.getFactorCode())
                        .last("LIMIT 1"));
        if (quickCheck == 0) {
            return Map.of("factorCode", factor.getFactorCode(), "count", 0L);
        }
        long count = factorValueMapper.selectCount(
                new LambdaQueryWrapper<FactorValue>()
                        .eq(FactorValue::getFactorCode, factor.getFactorCode()));
        return Map.of("factorCode", factor.getFactorCode(), "count", count);
    }

    /**
     * 聚合初始化接口：一次返回因子详情 + 测试报告列表 + 因子值数量
     * 减少前端多次 RTT 的等待时间
     */
    public Map<String, Object> getFactorInit(Long factorId) {
        FactorDefinition factor = getById(factorId);
        List<FactorTestReport> reports = testReportMapper.findByFactorCode(factor.getFactorCode());
        // 快速 COUNT：先 LIMIT 1 判断，有数据再精确统计
        Long quickCheck = factorValueMapper.selectCount(
                new LambdaQueryWrapper<FactorValue>()
                        .eq(FactorValue::getFactorCode, factor.getFactorCode())
                        .last("LIMIT 1"));
        long valueCount = quickCheck == 0 ? 0L :
                factorValueMapper.selectCount(new LambdaQueryWrapper<FactorValue>()
                        .eq(FactorValue::getFactorCode, factor.getFactorCode()));
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("factor", factor);
        result.put("reports", reports);
        result.put("valueCount", valueCount);
        return result;
    }

    /**
     * 触发因子测试
     */
    @Transactional
    public FactorTestReport triggerTest(Long factorId, LocalDate startDate, LocalDate endDate,
                                        String testName, String stockPool, String rebalanceFreq) {
        FactorDefinition factor = getById(factorId);

        FactorTestReport report = FactorTestReport.builder()
                .factorCode(factor.getFactorCode())
                .testName(testName)
                .startDate(startDate)
                .endDate(endDate)
                .stockPool(stockPool)
                .rebalanceFreq(rebalanceFreq)
                .status(FactorTestReport.TestStatus.RUNNING)
                .build();
        testReportMapper.insert(report);

        computeEngine.runFactorTest(report, factor);
        return report;
    }

    /**
     * 获取因子测试报告列表
     */
    public List<FactorTestReport> getTestReports(String factorCode) {
        return testReportMapper.findByFactorCode(factorCode);
    }

    /**
     * 获取因子测试报告详情（只读，避免乐观锁冲突）
     */
    @Transactional(readOnly = true)
    public FactorTestReport getTestReport(Long reportId) {
        FactorTestReport report = testReportMapper.selectById(reportId);
        if (report == null) {
            throw new ResourceNotFoundException("测试报告", reportId);
        }
        return report;
    }

    /**
     * 停止测试报告（将RUNNING/PENDING状态改为FAILED）
     */
    @Transactional
    public void deleteTestReport(Long reportId) {
        FactorTestReport report = testReportMapper.selectById(reportId);
        if (report == null) {
            throw new ResourceNotFoundException("测试报告", reportId);
        }

        if (report.getStatus() == FactorTestReport.TestStatus.RUNNING
            || report.getStatus() == FactorTestReport.TestStatus.PENDING) {
            // 运行中或待运行状态：标记为失败
            report.setStatus(FactorTestReport.TestStatus.FAILED);
            report.setErrorMessage("用户手动停止检测");
            testReportMapper.updateById(report);
        } else {
            // 已完成或失败状态：直接删除
            testReportMapper.deleteById(reportId);
        }
    }

    /**
     * 获取因子时间序列值
     */
    public List<FactorValue> getFactorTimeSeries(String factorCode, String symbol,
                                                 LocalDate start, LocalDate end) {
        // 优先从 ClickHouse 读取，失败时回退 MySQL
        if (clickHouseConfig.isEnabled()) {
            try {
                List<FactorValue> result = clickHouseFactorValueService
                        .findByFactorCodeAndDateRange(factorCode, start, end);
                if (!result.isEmpty()) {
                    return result.stream()
                            .filter(fv -> fv.getSymbol().equals(symbol))
                            .toList();
                }
            } catch (Exception e) {
                log.warn("[ClickHouse] 因子时间序列查询失败，回退 MySQL: {}", e.getMessage());
            }
        }
        return factorValueMapper.findByFactorCodeAndSymbol(factorCode, symbol);
    }

    /**
     * 查询该因子有数据的股票列表（带名称），支持关键词搜索，最多返回 50 条
     * 优先从 ClickHouse 读取
     */
    public List<Map<String, String>> getFactorSymbols(String factorCode, String keyword) {
        // 1. 查 factor_value 表中该因子有哪些 symbol（优先 CH）
        Set<String> factorSymbols;
        if (clickHouseConfig.isEnabled()) {
            try {
                List<FactorValue> values = clickHouseFactorValueService
                        .findByFactorCodeAndDateRange(factorCode, LocalDate.of(2000, 1, 1), LocalDate.now());
                if (!values.isEmpty()) {
                    factorSymbols = values.stream()
                            .map(FactorValue::getSymbol)
                            .collect(java.util.stream.Collectors.toSet());
                } else {
                    factorSymbols = getFactorSymbolsFromMySQL(factorCode);
                }
            } catch (Exception e) {
                log.warn("[ClickHouse] 因子股票列表查询失败，回退 MySQL: {}", e.getMessage());
                factorSymbols = getFactorSymbolsFromMySQL(factorCode);
            }
        } else {
            factorSymbols = getFactorSymbolsFromMySQL(factorCode);
        }

        if (factorSymbols.isEmpty()) {
            return List.of();
        }

        // 2. 查 stock_info 获取名称，支持关键词过滤
        LambdaQueryWrapper<StockInfo> siWrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            siWrapper.and(w -> w
                    .like(StockInfo::getCode, kw)
                    .or()
                    .like(StockInfo::getName, kw));
        }
        List<StockInfo> stocks = stockInfoMapper.selectList(siWrapper);

        // 3. 取交集，构建 code.market -> name 映射
        Map<String, String> nameMap = stocks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        s -> s.getCode() + "." + (s.getMarket() != null ? s.getMarket() : ""),
                        StockInfo::getName,
                        (a, b) -> a));

        return factorSymbols.stream()
                .filter(nameMap::containsKey)
                .limit(50)
                .map(sym -> Map.of("symbol", sym, "name", nameMap.getOrDefault(sym, "")))
                .toList();
    }

    /**
     * 从 MySQL 获取因子有数据的股票列表
     */
    private Set<String> getFactorSymbolsFromMySQL(String factorCode) {
        LambdaQueryWrapper<FactorValue> fvWrapper = new LambdaQueryWrapper<>();
        fvWrapper.select(FactorValue::getSymbol)
                .eq(FactorValue::getFactorCode, factorCode)
                .groupBy(FactorValue::getSymbol);
        return factorValueMapper.selectList(fvWrapper).stream()
                .map(FactorValue::getSymbol)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 获取某日因子截面数据（带股票名称），按因子值降序，分页返回
     * 优先从 ClickHouse 读取
     */
    public Map<String, Object> getFactorCrossSection(String factorCode, LocalDate date, int page, int size) {
        // 优先从 ClickHouse 读取
        List<FactorValue> values;
        if (clickHouseConfig.isEnabled()) {
            try {
                values = clickHouseFactorValueService.findByFactorCodeAndDate(factorCode, date);
                if (values.isEmpty()) {
                    values = factorValueMapper.findByFactorCodeAndDate(factorCode, date);
                }
            } catch (Exception e) {
                log.warn("[ClickHouse] 因子截面数据查询失败，回退 MySQL: {}", e.getMessage());
                values = factorValueMapper.findByFactorCodeAndDate(factorCode, date);
            }
        } else {
            values = factorValueMapper.findByFactorCodeAndDate(factorCode, date);
        }

        // 批量查询股票名称：从 symbol（如 000001.SZ）中提取 code，关联 stock_info
        Set<String> allSymbols = values.stream()
                .map(FactorValue::getSymbol)
                .filter(s -> s != null && !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> nameMap = new java.util.HashMap<>();
        if (!allSymbols.isEmpty()) {
            // 提取 code 部分，批量查 stock_info
            Set<String> codes = allSymbols.stream()
                    .map(s -> s.contains(".") ? s.substring(0, s.indexOf('.')) : s)
                    .collect(java.util.stream.Collectors.toSet());
            List<StockInfo> stocks = stockInfoMapper.selectList(
                    new LambdaQueryWrapper<StockInfo>().in(StockInfo::getCode, codes));
            Map<String, String> codeNameMap = stocks.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            StockInfo::getCode,
                            s -> s.getName() != null ? s.getName() : "",
                            (a, b) -> a));
            // 构建 symbol -> name 映射
            for (String sym : allSymbols) {
                String code = sym.contains(".") ? sym.substring(0, sym.indexOf('.')) : sym;
                nameMap.put(sym, codeNameMap.getOrDefault(code, ""));
            }
        }

        // 按因子值降序排序
        values.sort((a, b) -> {
            int cmp = java.util.Comparator.<java.math.BigDecimal>nullsFirst(java.util.Comparator.reverseOrder())
                    .compare(a.getFactorVal(), b.getFactorVal());
            return cmp != 0 ? cmp : a.getSymbol().compareTo(b.getSymbol());
        });

        int total = values.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> list = values.subList(fromIndex, toIndex).stream()
                .map(v -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("symbol", v.getSymbol() != null ? v.getSymbol() : "");
                    m.put("stockName", nameMap.getOrDefault(v.getSymbol(), ""));
                    m.put("value", v.getFactorVal() != null ? v.getFactorVal() : 0);
                    m.put("rankValue", v.getRankValue() != null ? v.getRankValue() : 0);
                    return m;
                })
                .toList();
        return Map.of(
                "total", total,
                "page", page,
                "size", size,
                "totalPages", (total + size - 1) / size,
                "data", list
        );
    }

    /**
     * 获取脚本模板
     */
    public String getScriptTemplate(String type) {
        return ScriptedFactorEngine.getScriptTemplate(type);
    }

    /**
     * 验证脚本语法
     */
    public Map<String, Object> validateScript(String scriptCode) {
        String error = scriptedEngine.validateScript(scriptCode);
        return Map.of("valid", error == null, "error", error != null ? error : "");
    }

    /**
     * 因子计算监控数据：各因子统计 + 全局总数
     * 使用 stale-while-revalidate 策略：缓存过期时立即返回旧数据，后台异步刷新
     * 避免慢查询阻塞请求导致超时
     */
    private volatile Map<String, Object> monitorCache;
    private volatile long monitorCacheTs = 0;
    private static final long MONITOR_CACHE_TTL = 60_000; // 延长到 60s，减少刷新频率
    private final java.util.concurrent.atomic.AtomicBoolean monitorRefreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // 后台刷新用的线程池（单线程，避免并发刷新）
    private final java.util.concurrent.ExecutorService monitorRefreshExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "monitor-cache-refresh");
                t.setDaemon(true);
                return t;
            });

    /**
     * 清除监控缓存（供 force=true 时调用）
     */
    public void clearMonitorCache() {
        monitorCache = null;
        monitorCacheTs = 0;
        log.info("Monitor cache cleared");
    }

    public Map<String, Object> getMonitorData() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = monitorCache;
        log.info("[getMonitorData] enter, cached={}, age={}ms", cached != null ? "yes" : "null", cached != null ? (now - monitorCacheTs) : -1);

        // 1. 缓存有效，直接返回（无锁，最快路径）
        if (cached != null && (now - monitorCacheTs) < MONITOR_CACHE_TTL) {
            log.info("[getMonitorData] returning cached data");
            return cached;
        }

        // 2. 缓存过期，提交后台刷新任务（不阻塞当前请求）
        boolean submitted = false;
        if (monitorRefreshing.compareAndSet(false, true)) {
            submitted = true;
            monitorRefreshExecutor.submit(() -> {
                try {
                    Map<String, Object> fresh = loadMonitorDataFromDb();
                    monitorCache = fresh;
                    monitorCacheTs = System.currentTimeMillis();
                    log.info("[MonitorRefresh] cache refreshed, totalRecords={}", fresh.get("totalRecords"));
                } catch (Exception e) {
                    log.warn("[MonitorRefresh] failed: {}", e.getMessage());
                } finally {
                    monitorRefreshing.set(false);
                }
            });
        }

        // 3. 立即返回（有缓存返回缓存，没缓存返回空数据避免阻塞）
        if (cached != null) {
            log.info("[getMonitorData] returning stale cache (refresh submitted={})", submitted);
            return cached;
        }
        // 极端情况：首次调用且刷新任务未完成，返回空数据
        log.info("[getMonitorData] NO cache, returning empty data (refresh submitted={})", submitted);
        return Map.of("totalRecords", 0L, "factors", java.util.Collections.emptyList());
    }

    /**
     * 从 DB/CH 加载监控数据（抽出为独立方法，便于维护）
     */
    private Map<String, Object> loadMonitorDataFromDb() {
        long start = System.currentTimeMillis();
        if (clickHouseConfig.isEnabled()) {
            try {
                log.info("[loadMonitorData] trying ClickHouse...");
                long t0 = System.currentTimeMillis();
                long total = clickHouseFactorValueService.selectTotalCount();
                log.info("[loadMonitorData] CH selectTotalCount cost={}ms, total={}", System.currentTimeMillis() - t0, total);
                t0 = System.currentTimeMillis();
                List<Map<String, Object>> stats = clickHouseFactorValueService.selectFactorStats();
                log.info("[loadMonitorData] CH selectFactorStats cost={}ms, size={}", System.currentTimeMillis() - t0, stats.size());
                // CH 查询失败或返回空时回退 MySQL
                if (stats == null || stats.isEmpty()) {
                    total = factorValueMapper.selectTotalCount();
                    stats = factorValueMapper.selectFactorStats();
                }
                log.info("[loadMonitorData] CH done, total cost={}ms", System.currentTimeMillis() - start);
                return Map.of("totalRecords", total, "factors", stats);
            } catch (Exception e) {
                log.warn("[ClickHouse] 监控数据查询失败，回退 MySQL: {} (cost={}ms)", e.getMessage(), System.currentTimeMillis() - start);
            }
        }
        log.info("[loadMonitorData] using MySQL...");
        long t0 = System.currentTimeMillis();
        long total = factorValueMapper.selectTotalCount();
        log.info("[loadMonitorData] MySQL selectTotalCount cost={}ms", System.currentTimeMillis() - t0);
        t0 = System.currentTimeMillis();
        List<Map<String, Object>> stats = factorValueMapper.selectFactorStats();
        log.info("[loadMonitorData] MySQL selectFactorStats cost={}ms, size={}, total cost={}ms", System.currentTimeMillis() - t0, stats.size(), System.currentTimeMillis() - start);
        return Map.of("totalRecords", total, "factors", stats);
    }
}
