package com.quant.platform.mp.controller;

import com.quant.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 监控相关 API（小程序专用）
 * 指数实时行情直接调腾讯 API，不依赖主后端
 */
@Slf4j
@RestController
@RequestMapping("/mp/monitor")
public class MpMonitorController {

    /** 腾讯行情接口：上证、深证、创业板、科创50、上证50、沪深300、中证500、中证1000、北证50 */
    private static final String INDICES_PARAM = "sh000001,sz399001,sz399006,sh000688,sh000016,sh000300,sh000905,sh000852,sz899050";

    private static final String TENCENT_URL = "https://qt.gtimg.cn/q=" + INDICES_PARAM;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    @GetMapping("/indices")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getIndexQuotes() {
        try {
            String raw = fetchTencent(TENCENT_URL);
            List<Map<String, Object>> list = parseIndexQuotes(raw);
            return ResponseEntity.ok(ApiResponse.success(list));
        } catch (Exception e) {
            log.error("获取指数行情失败", e);
            return ResponseEntity.ok(ApiResponse.success(Collections.emptyList()));
        }
    }

    /**
     * 批量获取个股实时行情
     * GET /mp/monitor/stocks?codes=002422,601211,600519
     *
     * 返回 Map<stockCode, {price, change, changePct}>
     */
    @GetMapping("/stocks")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Object>>>> getStockQuotes(
            @RequestParam String codes) {
        try {
            String[] codeArr = codes.split(",");
            // 转换股票代码为腾讯格式（sh/sz/bj前缀）
            String tencentParam = Arrays.stream(codeArr)
                    .map(this::toTencentCode)
                    .collect(Collectors.joining(","));
            String url = "https://qt.gtimg.cn/q=" + tencentParam;
            String raw = fetchTencent(url);
            // 把后缀（如 .SZ/.SH/.BJ）剥掉后再传入解析
            String[] normalizedCodes = Arrays.stream(codeArr)
                    .map(c -> c.replaceAll("\\.[A-Za-z]+$", ""))
                    .toArray(String[]::new);
            Map<String, Map<String, Object>> result = parseStockQuotes(raw, normalizedCodes);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("获取个股行情失败", e);
            return ResponseEntity.ok(ApiResponse.success(Collections.emptyMap()));
        }
    }

    /**
     * 股票代码转腾讯行情代码
     * 6开头 → sh + code（上海）
     * 0/3开头 → sz + code（深圳）
     * 4/8开头 → bj + code（北交所）
     * 自动剥掉可能带的 .SH/.SZ/.BJ 后缀
     */
    private String toTencentCode(String code) {
        code = code.trim().replaceAll("\\.[A-Za-z]+$", "");
        if (code.startsWith("6")) return "sh" + code;
        if (code.startsWith("0") || code.startsWith("3")) return "sz" + code;
        if (code.startsWith("4") || code.startsWith("8")) return "bj" + code;
        return "sz" + code; // 兜底
    }

    /**
     * 解析个股行情响应
     * 返回 Map<原始代码, {price, change, changePct}>
     */
    private Map<String, Map<String, Object>> parseStockQuotes(String raw, String[] originalCodes) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;

        // 建立腾讯代码→原始代码的映射（原始代码已剥掉 .SZ/.SH 后缀）
        Map<String, String> codeMap = Arrays.stream(originalCodes)
                .collect(Collectors.toMap(
                        c -> toTencentCode(c.trim()),
                        c -> c.trim(),
                        (a, b) -> a));

        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (start < 0 || end <= start) continue;

            String content = line.substring(start + 1, end);
            String[] fields = content.split("~");
            if (fields.length < 33) continue;

            // 从变量名提取腾讯代码（如 v_sh600519="..." → sh600519）
            String varName = line.substring(0, start).replace("v_", "").replace("=", "").trim();
            String originalCode = codeMap.get(varName);
            if (originalCode == null) continue;

            Map<String, Object> map = new HashMap<>();
            map.put("price", parseDouble(fields[3]));
            map.put("change", parseDouble(fields[31]));
            map.put("changePct", parseDouble(fields[32]));
            map.put("name", fields[1]);

            result.put(originalCode, map);
        }
        return result;
    }

    private String fetchTencent(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Referer", "https://finance.qq.com")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
        // 腾讯 qt.gtimg.cn 返回的是 GBK 编码
        return new String(response.body(), Charset.forName("GBK"));
    }

    /**
     * 解析腾讯指数行情响应
     * 格式：v_sh000001="1~上证指数~000001~3365.67~-12.34~-0.37%~...";
     */
    private List<Map<String, Object>> parseIndexQuotes(String raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;

        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 提取引号内的内容: v_sh000001="1~上证指数~..."
            int start = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (start < 0 || end <= start) continue;

            String content = line.substring(start + 1, end);
            String[] fields = content.split("~");
            if (fields.length < 5) continue;

            Map<String, Object> map = new HashMap<>();
            map.put("code", fields[2]);           // 代码
            map.put("name", fields[1]);           // 名称
            map.put("price", parseDouble(fields[3])); // 当前价

            // 腾讯行情API字段格式（指数）：
            // fields[31] = 涨跌额, fields[32] = 涨跌幅(%), fields[33] = 最高, fields[34] = 最低
            if (fields.length >= 33) {
                map.put("change", parseDouble(fields[31]));
                map.put("changePct", parseDouble(fields[32]));
                map.put("high", parseDouble(fields[33]));
                map.put("low", parseDouble(fields[34]));
            } else {
                // 兜底：用当前价和昨收计算
                Double price = parseDouble(fields[3]);
                Double prevClose = parseDouble(fields[4]);
                if (price != null && prevClose != null && prevClose != 0) {
                    double change = price - prevClose;
                    map.put("change", Math.round(change * 100.0) / 100.0);
                    map.put("changePct", Math.round(change / prevClose * 10000.0) / 100.0);
                } else {
                    map.put("change", null);
                    map.put("changePct", null);
                }
            }

            result.add(map);
        }
        return result;
    }

    private Double parseDouble(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
