package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.FactorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quant.platform.common.enums.JobStatus;
/**
 * 数据更新服务
 * 通过 ProcessBuilder 调用 Python 脚本，解析 stdout 实时推送进度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataUpdateService {

    private final DataUpdateExecutionService executionService;

    private final DataUpdateScriptService scriptService;

    private final DataUpdateCoverageService dataUpdateCoverageService;

    /**
     * 配置 Python 子进程的运行时环境变量（委托给 DataUpdateScriptService）。
     * 保留包级可见性以兼容同包 DataLifecycleService 的直接调用。
     */
    void configurePythonEnv(ProcessBuilder pb) {
        scriptService.configurePythonEnv(pb);
    }

    /** 默认起始天数（委托 DataUpdateExecutionService，原 Lombok @Getter） */
    public int getDefaultStartDays() {
        return executionService.getDefaultStartDays();
    }

    /** 脚本目录绝对路径（委托 DataUpdateExecutionService，原 Lombok @Getter） */
    public String getResolvedScriptDir() {
        return executionService.getResolvedScriptDir();
    }

    public DataUpdateTask submitTask(DataUpdateRequest request) {
        return executionService.submitTask(request);
    }

    public DataUpdateTask submitTaskConcurrent(DataUpdateRequest request) {
        return executionService.submitTaskConcurrent(request);
    }

    public DataUpdateTask getTaskStatus(String taskId) {
        return executionService.getTaskStatus(taskId);
    }

    public DataUpdateTask getCurrentTask() {
        return executionService.getCurrentTask();
    }

    public List<DataUpdateTask> getRecentTasks() {
        return executionService.getRecentTasks();
    }

    public List<Map<String, Object>> getScheduledRunningTasks() {
        return executionService.getScheduledRunningTasks();
    }

    public boolean cancelTask(String taskId) {
        return executionService.cancelTask(taskId);
    }

    public boolean cancelOrphanTask(String taskKey) {
        return executionService.cancelOrphanTask(taskKey);
    }

    public boolean cancelByUpdateType(String updateType) {
        return executionService.cancelByUpdateType(updateType);
    }

    public java.util.List<Map<String, Object>> getTaskLogs(String taskId) {
        return executionService.getTaskLogs(taskId);
    }

    public Map<String, Object> getDataCoverage() {
        return dataUpdateCoverageService.getDataCoverage();
    }

    public Map<String, Object> getIndexCoverage() {
        return dataUpdateCoverageService.getIndexCoverage();
    }

    public List<Map<String, Object>> getMissingIndices(LocalDate date) {
        return dataUpdateCoverageService.getMissingIndices(date);
    }

    public Map<String, Object> getDividendCoverage() {
        return dataUpdateCoverageService.getDividendCoverage();
    }

    public Map<String, Object> getBidaskCoverage() {
        return dataUpdateCoverageService.getBidaskCoverage();
    }

    public Map<String, Object> getMissingDividendStats() {
        return dataUpdateCoverageService.getMissingDividendStats();
    }

    public List<Map<String, Object>> getMissingDividendStocks(String market, int page, int pageSize) {
        return dataUpdateCoverageService.getMissingDividendStocks(market, page, pageSize);
    }

    public List<Map<String, Object>> getMissingStocks(String date, String market) {
        return dataUpdateCoverageService.getMissingStocks(date, market);
    }

    public Map<String, Map<String, Object>> loadBidAskStats(LocalDate startDate, LocalDate endDate) {
        return dataUpdateCoverageService.loadBidAskStats(startDate, endDate);
    }

    public Map<String, Object> checkDataFreshness() {
        return dataUpdateCoverageService.checkDataFreshness();
    }

    public Map<String, Object> checkPriceAnomalies(int days) {
        return dataUpdateCoverageService.checkPriceAnomalies(days);
    }

}
