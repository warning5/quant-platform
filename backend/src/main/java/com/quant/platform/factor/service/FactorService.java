package com.quant.platform.factor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.common.exception.ResourceNotFoundException;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.domain.FactorTestReport;
import com.quant.platform.factor.domain.FactorValue;
import com.quant.platform.factor.engine.FactorComputeEngine;
import com.quant.platform.factor.engine.ScriptedFactorEngine;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.mapper.FactorTestReportMapper;
import com.quant.platform.market.domain.MarketDailyBar;
import com.quant.platform.market.service.MarketDataService;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 因子管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorService {

    /**
     * factorCode 白名单正则（防御 SQL 注入）
     */
    private static final java.util.regex.Pattern FACTOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_\\-]+");

    /**
     * 校验 factorCode（防御 SQL 注入）
     * @param factorCode 因子代码
     * @throws IllegalArgumentException 如果 factorCode 包含非法字符
     */
    private void validateFactorCode(String factorCode) {
        if (factorCode == null || !FACTOR_CODE_PATTERN.matcher(factorCode).matches()) {
            throw new IllegalArgumentException("Invalid factorCode: " + factorCode);
        }
    }

    private final FactorDefinitionMapper factorMapper;
    private final ClickHouseFactorValueService clickHouseFactorValueService;
    private final FactorTestReportMapper testReportMapper;
    private final FactorComputeEngine computeEngine;
    private final ScriptedFactorEngine scriptedEngine;
    private final MarketDataService marketDataService;
    private final StockInfoMapper stockInfoMapper;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final FactorMetaCacheService factorMetaCache;

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
     * 因子值统计只查ClickHouse，不降级到MySQL
     */
    public Map<String, Map<String, Object>> batchGetFactorStatus(List<String> factorCodes) {
        if (factorCodes == null || factorCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new java.util.HashMap<>();

        // 1. 从ClickHouse批量查询因子值统计（count + 日期范围），不降级MySQL
        try {
            Map<String, Map<String, Object>> chStats = clickHouseFactorValueService.batchGetStatusFromCH(factorCodes);
            for (String code : factorCodes) {
                Map<String, Object> entry = new java.util.HashMap<>();
                Map<String, Object> chEntry = chStats.get(code);
                if (chEntry != null) {
                    entry.putAll(chEntry);
                } else {
                    entry.put("valueCount", 0L);
                }
                result.put(code, entry);
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 因子值统计查询失败，返回空统计: {}", e.getMessage());
            for (String code : factorCodes) {
                result.put(code, Map.of("valueCount", 0L));
            }
        }

        // 2. 查询检测报告数量（MySQL，test_report表不在CH中）
        for (String code : factorCodes) {
            long testCount = testReportMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorTestReport>()
                            .eq(FactorTestReport::getFactorCode, code));
            result.get(code).put("testCount", testCount);
        }

        return result;
    }

    /**
     * 删除指定因子的所有因子值（只删 ClickHouse，不删 MySQL）
     */
    @Transactional
    public int deleteFactorValues(Long factorId) {
        FactorDefinition factor = getById(factorId);
        long deleted = clickHouseFactorValueService.deleteByFactorCode(factor.getFactorCode());
        log.info("已删除 ClickHouse 中因子 [{}] 的值，约 {} 条（异步）", factor.getFactorCode(), deleted);
        return (int) Math.min(deleted, Integer.MAX_VALUE);
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
        // 脚本安全验证（防止通过 update 注入恶意脚本绕过创建时验证）
        if (existing.getFactorType() == FactorDefinition.FactorType.SCRIPTED
                && update.getScriptCode() != null && !update.getScriptCode().isBlank()) {
            String error = scriptedEngine.validateScript(update.getScriptCode());
            if (error != null) {
                throw new BusinessException("Groovy脚本安全/语法验证未通过: " + error);
            }
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
     *
     * @param factorCodes 因子代码列表
     * @param startDate   开始日期
     * @param endDate     结束日期
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

        // ── 统一预加载K线数据（所有因子共享，避免 N×11 次 CH 查询）──
        Map<String, List<MarketDailyBar>> preloadedBars = null;
        LocalDate histStart = startDate.minusDays(400);
        log.info("[批量计算] 统一预加载K线: {} 只股票, {} ~ {}", symbols.size(), histStart, endDate);
        long preloadStart = System.currentTimeMillis();
        try {
            preloadedBars = marketDataService.getBarsBatch(symbols, histStart, endDate, false);
            long preloadMs = System.currentTimeMillis() - preloadStart;
            long totalBars = preloadedBars.values().stream().mapToLong(List::size).sum();
            log.info("[批量计算] 预加载完成: {} 只股票, {} 行K线, 耗时 {}ms", preloadedBars.size(), totalBars, preloadMs);
            if (totalBars == 0) {
                log.warn("[批量计算] 预加载K线为空！请检查数据源");
            }
        } catch (Exception e) {
            log.error("[批量计算] 预加载K线失败，将回退到各因子自行预加载: {}", e.getMessage());
            preloadedBars = null;
        }
        final Map<String, List<MarketDailyBar>> sharedBars = preloadedBars;

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
                // ★ Fix: 增量模式应从 latestDate 的次日开始计算，而非使用传入的 startDate。
                //   传入的 startDate 可能是 today（周末/非交易日），导致 getTradingDates 返回空列表，
                //   从而跳过所有待计算日期（如 MARGIN_BUY_RATIO 只到7月2日，但 startDate=7月12日周六，
                //   getTradingDates(7/12, 7/12) 返回空，7月3-10日全部被跳过）。
                LocalDate incrementalStart = (latestDate != null) ? latestDate.plusDays(1) : startDate;
                log.info("[{}] incremental: existing data up to {}, computing from {} (request start: {})",
                        code, latestDate, incrementalStart, startDate);
                try {
                    if (sharedBars != null) {
                        computeEngine.computeFactorIncrementalWithBars(factor, incrementalStart, endDate, symbols, sharedBars);
                    } else {
                        computeEngine.computeFactorIncremental(factor, incrementalStart, endDate, symbols);
                    }
                } catch (Exception e) {
                    skipped.add(code + "(提交失败:" + e.getMessage().split("\\n")[0] + ")");
                    log.warn("[{}] 提交增量计算失败: {}", code, e.getMessage());
                    continue;
                }
            } else {
                try {
                    if (sharedBars != null) {
                        computeEngine.computeFactorWithBars(factor, startDate, endDate, symbols, sharedBars);
                    } else {
                        computeEngine.computeFactor(factor, startDate, endDate, symbols);
                    }
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
     * 查询因子已有数据量（从ClickHouse查询，不降级MySQL）
     */
    public Map<String, Object> getFactorValueCount(Long factorId) {
        FactorDefinition factor = getById(factorId);
        long count = 0L;
        try {
            Map<String, Map<String, Object>> chStats = clickHouseFactorValueService.batchGetStatusFromCH(List.of(factor.getFactorCode()));
            Map<String, Object> stat = chStats.get(factor.getFactorCode());
            if (stat != null && stat.get("valueCount") != null) {
                count = (Long) stat.get("valueCount");
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 因子值数量查询失败: {}", e.getMessage());
        }
        return Map.of("factorCode", factor.getFactorCode(), "count", count);
    }

    /**
     * 聚合初始化接口：一次返回因子详情 + 测试报告列表 + 因子值数量
     * 减少前端多次 RTT 的等待时间
     */
    public Map<String, Object> getFactorInit(Long factorId) {
        FactorDefinition factor = getById(factorId);
        List<FactorTestReport> reports = testReportMapper.findByFactorCode(factor.getFactorCode());

        // 从ClickHouse查询因子值数量（不降级MySQL）
        long valueCount = 0L;
        try {
            Map<String, Map<String, Object>> chStats = clickHouseFactorValueService.batchGetStatusFromCH(List.of(factor.getFactorCode()));
            Map<String, Object> stat = chStats.get(factor.getFactorCode());
            if (stat != null && stat.get("valueCount") != null) {
                valueCount = (Long) stat.get("valueCount");
            }
        } catch (Exception e) {
            log.warn("[ClickHouse] 详情页因子值统计查询失败: {}", e.getMessage());
        }

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
        // SQL注入防护
        validateFactorCode(factorCode);
        
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
        // SQL注入防护
        validateFactorCode(factorCode);
        
        List<FactorValue> result = clickHouseFactorValueService
                .findByFactorCodeAndDateRange(factorCode, start, end);
        return result.stream()
                .filter(fv -> {
                    String raw = fv.getSymbol();
                    int dot = raw.indexOf('.');
                    String code = dot > 0 ? raw.substring(0, dot) : raw;
                    return code.equals(symbol);
                })
                .toList();
    }

    /**
     * 查询该因子有数据的股票列表（带名称），支持关键词搜索
     * 全部走 ClickHouse；无关键词时返回前 200 条（按代码排序），有关键词时不限条数
     */
    public List<Map<String, String>> getFactorSymbols(String factorCode, String keyword) {
        // SQL注入防护
        validateFactorCode(factorCode);
        
        // 从 CH 获取该因子有数据的股票列表
        Set<String> factorSymbols;
        try {
            factorSymbols = clickHouseFactorValueService.getDistinctSymbols(factorCode);
        } catch (Exception e) {
            log.warn("[FactorService] getFactorSymbols CH 失败: {}", e.getMessage());
            return List.of();
        }

        if (factorSymbols.isEmpty()) {
            return List.of();
        }

        // 2. 查 stock_info 获取名称，支持关键词过滤
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
        LambdaQueryWrapper<StockInfo> siWrapper = new LambdaQueryWrapper<>();
        if (hasKeyword) {
            String kw = keyword.trim();
            siWrapper.and(w -> w
                    .like(StockInfo::getCode, kw)
                    .or()
                    .like(StockInfo::getName, kw));
        }
        List<StockInfo> stocks = stockInfoMapper.selectList(siWrapper);

        // 3. 取交集：CH symbol 可能带后缀(000001.SZ)，去后缀后与 stock_info.code 匹配
        Map<String, String> nameMap = stocks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        StockInfo::getCode,
                        StockInfo::getName,
                        (a, existing) -> a));

        java.util.stream.Stream<Map<String, String>> resultStream = factorSymbols.stream()
                .map(sym -> {
                    int dot = sym.indexOf('.');
                    String code = dot > 0 ? sym.substring(0, dot) : sym;
                    return new AbstractMap.SimpleEntry<>(code, sym);
                })
                .filter(e -> nameMap.containsKey(e.getKey()))
                // 按 code 去重：CH 里 symbol 可能同时存在带后缀(000001.SZ)和不带后缀(000001)
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        e -> Map.of("symbol", e.getKey(), "name", nameMap.getOrDefault(e.getKey(), "")),
                        (existing, replacement) -> existing,  // 重复时保留第一个
                        java.util.TreeMap::new))               // 按 code 排序
                .values()
                .stream();

        // 无关键词：限制 200 条防止下拉框过大；有关键词：不限条数
        if (!hasKeyword) {
            resultStream = resultStream.limit(200);
        }

        return resultStream.toList();
    }

    /**
     * 获取某日因子截面数据（带股票名称），按因子值降序，分页返回
     * 全部走 ClickHouse
     */
    public Map<String, Object> getFactorCrossSection(String factorCode, LocalDate date, int page, int size) {
        // SQL注入防护
        validateFactorCode(factorCode);
        
        List<FactorValue> values = new java.util.ArrayList<>(clickHouseFactorValueService.findByFactorCodeAndDate(factorCode, date));

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
                            (a, existing) -> a));
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
     * 查询指定日期缺少因子值的因子列表
     * 对比所有激活因子 vs 有该日期数据的因子，返回缺失的因子
     */
    public List<Map<String, Object>> findMissingFactorsByDate(LocalDate date) {
        // 1. 查询所有激活因子
        List<FactorDefinition> allFactors = factorMapper.selectList(
                new LambdaQueryWrapper<FactorDefinition>()
                        .eq(FactorDefinition::getStatus, FactorDefinition.FactorStatus.ACTIVE)
                        .orderByAsc(FactorDefinition::getFactorCode));
        if (allFactors.isEmpty()) {
            return List.of();
        }

        // 2. 从 ClickHouse 查询该日期有数据的因子
        Set<String> existingCodes = new java.util.HashSet<>(
                clickHouseFactorValueService.findFactorsWithDates(date.toString()));

        // 3. 找出缺失因子（排除季频财务因子，非日频更新）
        return allFactors.stream()
                .filter(f -> !existingCodes.contains(f.getFactorCode()))
                .filter(f -> {
                    String code = f.getFactorCode();
                    return code != null && !factorMetaCache.isFinancial(code);
                })
                .map(f -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", f.getId());
                    m.put("factorCode", f.getFactorCode());
                    m.put("factorName", f.getFactorName());
                    m.put("category", f.getCategory().name());
                    m.put("factorType", f.getFactorType().name());
                    return m;
                })
                .toList();
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

        // 1. 缓存有效，直接返回
        if (cached != null && (now - monitorCacheTs) < MONITOR_CACHE_TTL) {
            return cached;
        }

        // 2. 缓存过期/不存在，确保刷新任务已提交
        if (monitorRefreshing.compareAndSet(false, true)) {
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

        // 3. 有缓存先用过期缓存兜底（避免前端拿到空数据）
        if (cached != null) {
            return cached;
        }

        // 4. 首次加载：同步等待刷新完成（最多等5秒）
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (monitorCache == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (monitorCache != null) {
                log.info("[getMonitorData] first-load sync wait OK");
                return monitorCache;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 5. 超时，返回空数据
        log.warn("[getMonitorData] first-load timeout, returning empty");
        return Map.of("totalRecords", 0L, "factors", java.util.Collections.emptyList());
    }

    /**
     * 从 ClickHouse 加载监控数据
     */
    private Map<String, Object> loadMonitorDataFromDb() {
        long start = System.currentTimeMillis();
        log.info("[loadMonitorData] querying ClickHouse...");
        long total = clickHouseFactorValueService.selectTotalCount();
        List<Map<String, Object>> stats = clickHouseFactorValueService.selectFactorStats();
        log.info("[loadMonitorData] CH done, total={}, stats={}, cost={}ms",
                total, stats.size(), System.currentTimeMillis() - start);
        return Map.of("totalRecords", total, "factors", stats);
    }

}
