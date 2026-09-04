package com.quant.platform.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.dataupdate.DataUpdateExecutionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 盘中主力资金流采集（复用 westock_moneyflow.py 的腾讯自选股源）。
 *
 * 设计原则（最干净）：
 * 1. 完全复用现有 westock_moneyflow.py（CLI 入口输出 JSON），不引入新数据源。
 * 2. 只对盘中监控列表（watching 列表）拉取，避免全市场高频请求。
 * 3. 盘中交易时段由 IntradayMonitorService 低频触发（约每 5 分钟一次），结果存内存缓存，
 *    不写 ClickHouse，避免污染每日批处理数据；收盘后被每日批任务自然覆盖。
 * 4. 任何异常（网络/超时/解析）都降级返回空 Map，绝不影响主监控循环。
 */
@Slf4j
@Service
public class IntradayMoneyFlowService {

    private final DataUpdateExecutionService dataUpdateExecutionService;

    @Value("${quant.data-update.python-path:python}")
    private String pythonPath;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLATFORM_CODE = Pattern.compile("^(\\d{6})\\.(SH|SZ|BJ)$");
    private static final int TIMEOUT_SECONDS = 120;

    public IntradayMoneyFlowService(DataUpdateExecutionService dataUpdateExecutionService) {
        this.dataUpdateExecutionService = dataUpdateExecutionService;
    }

    /**
     * 拉取指定平台代码当日的盘中主力资金流（主力/超大/大单净流入）。
     *
     * @param platformCodes 平台代码，如 600519.SH / 000001.SZ / 8xxxxx.BJ
     * @return platformCode -> 当日快照（无数据的代码不会出现在结果中）
     */
    public Map<String, MoneyFlowSnapshot> fetchToday(Set<String> platformCodes) {
        Map<String, MoneyFlowSnapshot> result = new LinkedHashMap<>();
        if (platformCodes == null || platformCodes.isEmpty()) return result;

        // 平台代码 -> westock 代码映射（腾讯接口格式 sh600519）
        Map<String, String> platformByWs = new HashMap<>();
        List<String> wsCodes = new ArrayList<>();
        for (String pc : platformCodes) {
            String ws = toWestockCode(pc);
            if (ws != null) {
                platformByWs.put(ws.toUpperCase(), pc);
                wsCodes.add(ws);
            }
        }
        if (wsCodes.isEmpty()) return result;

        String scriptDir = dataUpdateExecutionService.getResolvedScriptDir();
        if (scriptDir == null || !new File(scriptDir).isDirectory()) {
            log.warn("[IntradayMoneyFlow] 脚本目录不可用: {}", scriptDir);
            return result;
        }

        String csv = String.join(",", wsCodes);
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add("-u");
        cmd.add("westock_moneyflow.py");
        cmd.add("--codes");
        cmd.add(csv);
        cmd.add("--date");
        cmd.add(LocalDate.now().toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(scriptDir));
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[IntradayMoneyFlow] 脚本超时({}s)，已终止: codes={}", TIMEOUT_SECONDS, csv);
                return result;
            }
            if (proc.exitValue() != 0) {
                log.warn("[IntradayMoneyFlow] 脚本退出码 {}: {}", proc.exitValue(), sb);
                return result;
            }

            JsonNode root = MAPPER.readTree(sb.toString());
            if (root.has("error")) {
                log.warn("[IntradayMoneyFlow] 脚本返回错误: {}", root.get("error").asText());
                return result;
            }
            String updatedAt = LocalDateTime.now().toString();
            root.fields().forEachRemaining(e -> {
                String wsKey = e.getKey().toUpperCase();
                String platform = platformByWs.get(wsKey);
                if (platform == null) return;
                JsonNode dayMap = e.getValue();
                if (dayMap == null || dayMap.isEmpty()) return;
                JsonNode vals = dayMap.elements().next(); // 取当日唯一日期节点
                MoneyFlowSnapshot snap = new MoneyFlowSnapshot();
                snap.setNetMain(toBigDecimal(vals.get("net_main")));
                snap.setNetMainPct(toBigDecimal(vals.get("net_main_pct")));
                snap.setNetHuge(toBigDecimal(vals.get("net_huge")));
                snap.setNetBig(toBigDecimal(vals.get("net_big")));
                snap.setUpdatedAt(updatedAt);
                result.put(platform, snap);
            });
            log.info("[IntradayMoneyFlow] 当日主力资金流拉取完成: 请求{}只, 命中{}只", wsCodes.size(), result.size());
        } catch (Exception ex) {
            log.warn("[IntradayMoneyFlow] 拉取失败: {}", ex.getMessage());
        }
        return result;
    }

    /** 平台代码(600519.SH) -> westock 代码(sh600519)；已是 westock 格式则直接返回。
     *  兼容纯数字代码（监控列表主要来源 stock_recommendation.stock_code 为 6 位纯数字）：
     *  按首位判断市场 —— 6=沪，0/3=深，4/8/9=北。 */
    private String toWestockCode(String platformCode) {
        if (platformCode == null) return null;
        String s = platformCode.trim().toUpperCase();
        // 已是 westock 格式
        if (s.matches("^(SH|SZ|BJ)\\d{6}$")) return s.toLowerCase();
        // 平台格式 600519.SH
        Matcher m = PLATFORM_CODE.matcher(s);
        if (m.find()) {
            return m.group(2).toLowerCase() + m.group(1);
        }
        // 纯数字 6 位：按首位推断交易所
        if (s.matches("^\\d{6}$")) {
            char c = s.charAt(0);
            String market = (c == '6') ? "sh" : (c == '0' || c == '3') ? "sz" : "bj";
            return market + s;
        }
        return null;
    }

    private BigDecimal toBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单只股票当日主力资金流快照（单位：元 / 百分比）。 */
    @Data
    public static class MoneyFlowSnapshot {
        private BigDecimal netMain;     // 主力净流入（元）
        private BigDecimal netMainPct; // 主力净流入占比（%）
        private BigDecimal netHuge;     // 超大单净流入（元）
        private BigDecimal netBig;      // 大单净流入（元）
        private String updatedAt;       // 采集时间 ISO
    }
}
