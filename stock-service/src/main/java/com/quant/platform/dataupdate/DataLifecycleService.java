package com.quant.platform.dataupdate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.stock.service.ClickHouseStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 退市股票生命周期管理业务逻辑层
 * 承接原 DataUpdateController 中直接内联的退市查询/标记/清理逻辑（含调用 Python 脚本）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataLifecycleService {

    private final DataUpdateService dataUpdateService;
    private final JdbcTemplate jdbcTemplate;
    private final ClickHouseStockService clickHouseStockService;
    private final ObjectMapper objectMapper;

    @Value("${quant.data-update.python-path:python}")
    private String pythonPath;

    /**
     * 调用 find_delisted_stocks.py 脚本获取退市股票列表
     */
    private String runFindDelistedScript(int inactiveDays) throws Exception {
        String resolvedScriptDir = dataUpdateService.getResolvedScriptDir();
        if (resolvedScriptDir == null) {
            throw new IllegalStateException("脚本目录未配置，请在 application.yml 中设置 quant.data-update.script-dir");
        }
        File script = new File(resolvedScriptDir, "find_delisted_stocks.py");
        if (!script.exists()) {
            throw new FileNotFoundException("脚本不存在: " + script.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(pythonPath, script.getAbsolutePath(), String.valueOf(inactiveDays));
        pb.directory(new File(resolvedScriptDir));
        pb.redirectErrorStream(false);
        dataUpdateService.configurePythonEnv(pb);
        Process p = pb.start();

        String json;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            json = sb.toString().trim();
        }

        int rc = p.waitFor();
        if (rc != 0) {
            String err;
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                err = String.join("\n", er.lines().toArray(String[]::new));
            }
            log.error("[find_delisted] 脚本执行失败 (rc={}): {}", rc, err);
            throw new RuntimeException("脚本执行失败: " + err);
        }

        if (json.isEmpty()) {
            return "[]";
        }
        return json;
    }

    /**
     * 查询退市股票列表（ClickHouse 检测最近无交易数据）
     */
    public ApiResponse<List<Map<String, Object>>> listDelistedStocks(int inactiveDays) {
        try {
            String json = runFindDelistedScript(inactiveDays);
            List<Map<String, Object>> stocks = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            log.info("[退市查询] 脚本执行完毕 ({} 只)", stocks.size());
            return ApiResponse.success(stocks);
        } catch (Exception e) {
            log.error("查询退市股票列表失败", e);
            return ApiResponse.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 标记退市股票（更新 delist_date 而非删除）
     */
    public ApiResponse<Map<String, Object>> markDelistedStocks() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String json = runFindDelistedScript(60);
            List<Map<String, Object>> stocks = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            long markable = stocks.stream().filter(s -> {
                Object out = s.get("out_date");
                Object max = s.get("max_date");
                return (out != null && !out.toString().isEmpty()) || (max != null && !max.toString().isEmpty());
            }).count();
            result.put("markedCount", markable);
            result.put("candidateCount", stocks.size());
            result.put("stocks", stocks);
            log.info("[退市标记] 完成: 候选 {} 只, 可标记 {} 只", stocks.size(), markable);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("退市标记失败", e);
            return ApiResponse.error("标记失败: " + e.getMessage());
        }
    }

    /**
     * 清理退市股票数据（物理删除，慎用）
     */
    public ApiResponse<Map<String, Object>> cleanDelistedStocks(List<String> codes) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (codes == null || codes.isEmpty()) {
                return ApiResponse.error("请选择要清理的股票");
            }

            List<String> validatedCodes = new ArrayList<>();
            for (String c : codes) {
                if (c == null) continue;
                String trimmed = c.trim();
                if (trimmed.matches("^[0-9]{6}(\\.(SH|SZ|BJ))?$")) {
                    validatedCodes.add(trimmed);
                } else {
                    log.warn("[退市清理] 拒绝无效的股票代码: {}", trimmed);
                }
            }
            if (validatedCodes.isEmpty()) {
                return ApiResponse.error("没有有效的股票代码可清理");
            }
            codes = validatedCodes;

            result.put("cleanedCodes", codes);
            result.put("codeCount", codes.size());
            int totalDeleted = 0;

            try {
                String placeholders = String.join(",", codes.stream().map(c -> "?").toArray(String[]::new));
                int deleted = jdbcTemplate.update(
                        "DELETE FROM stock_info WHERE code IN (" + placeholders + ")", codes.toArray());
                result.put("stockInfoDeleted", deleted);
                totalDeleted += deleted;
            } catch (Exception e) {
                log.error("删除 MySQL stock_info 失败", e);
                result.put("stockInfoError", e.getMessage());
            }

            Map<String, String> codeColumns = new LinkedHashMap<>();
            codeColumns.put("stock_daily", "code");
            codeColumns.put("factor_value", "symbol");
            codeColumns.put("stock_sentiment_moneyflow", "code");

            for (Map.Entry<String, String> entry : codeColumns.entrySet()) {
                String table = entry.getKey();
                String col = entry.getValue();
                try {
                    String ph = String.join(",", codes.stream().map(c -> "?").toArray(String[]::new));
                    Object bc = clickHouseStockService.queryForObject(
                            "SELECT count() FROM stock." + table + " FINAL WHERE " + col + " IN (" + ph + ")",
                            codes.toArray());
                    long beforeCount = bc != null ? ((Number) bc).longValue() : 0L;
                    result.put(table + "_before", beforeCount);

                    if (beforeCount > 0) {
                        String inClause = String.join(",", codes.stream().map(c -> "'" + c + "'").toArray(String[]::new));
                        String deleteSql = "ALTER TABLE stock." + table + " DELETE WHERE " + col + " IN (" + inClause + ")";
                        clickHouseStockService.executeDdl(deleteSql);
                        result.put(table + "_deleted", beforeCount);
                        totalDeleted += (int) beforeCount;
                    } else {
                        result.put(table + "_deleted", 0);
                    }
                } catch (Exception e) {
                    log.error("删除 CH {} 失败", table, e);
                    result.put(table + "_error", e.getMessage());
                }
            }

            result.put("totalDeleted", totalDeleted);
            log.info("[退市清理] 完成: codes={}, 总删除 {}", codes, totalDeleted);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("退市清理失败", e);
            return ApiResponse.error("清理失败: " + e.getMessage());
        }
    }
}
