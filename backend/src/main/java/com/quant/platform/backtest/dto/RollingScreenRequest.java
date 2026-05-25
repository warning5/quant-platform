package com.quant.platform.backtest.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.screen.dto.ScreenRequest;
import lombok.Data;
import lombok.SneakyThrows;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 滚动选股回测请求 DTO
 * <p>
 * 包含一个完整回测配置：选股配置（序列化为 JSON）+ 回测参数（资金/频率/费用等）。
 * 使用 {@link #toScreenRequest(LocalDate)} 可以生成任意调仓日的 ScreenRequest。
 */
@Data
public class RollingScreenRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    // ===== 选股配置（前端提交时全量传入，序列化为 screenConfigJson 存库）=====

    /**
     * 选股配置 JSON 字符串（ScreenRequest 完整序列化）。
     * 存库用，运行时通过 {@link #parseScreenRequest()} 反序列化。
     */
    private String screenConfigJson;

    /**
     * 调仓频率：WEEKLY / BIWEEKLY / MONTHLY
     */
    private String rebalanceFreq = "MONTHLY";

    // ===== 回测参数 =====

    private LocalDate startDate;
    private LocalDate endDate;

    private BigDecimal initialCapital = BigDecimal.valueOf(1_000_000);
    private BigDecimal commissionRate = new BigDecimal("0.0003");
    private BigDecimal slippageRate = new BigDecimal("0.001");
    private String slippageModel = "FIXED";

    /**
     * 成交价模式：CLOSE(当日收盘价) / NEXT_OPEN(次日开盘价) / VWAP(成交量加权均价)
     */
    private String orderType = "CLOSE";

    private String benchmarkCode = "000300.SH";

    /**
     * 权重分配：EQUAL(等权) / SCORE_PROPORTIONAL(按得分比例)
     */
    private String weightMode = "EQUAL";

    private Boolean limitFilter = true;
    private Boolean suspendFilter = true;

    private BigDecimal stampTaxRate = new BigDecimal("0.0005");
    private BigDecimal minCommission = new BigDecimal("5.00");
    private BigDecimal transferFeeRate = new BigDecimal("0.00002");

    private String taskName;

    /**
     * 选股因子列表（与 ScreenRequest.factors 一致）。
     * 存在这里是为了序列化/反序列化 screenConfigJson 时不丢 factors。
     */
    private List<ScreenRequest.FactorWeight> factors;

    // ===== 工具方法 =====

    /**
     * 将 screenConfigJson 反序列化为 ScreenRequest。
     */
    @SneakyThrows
    public ScreenRequest parseScreenRequest() {
        // 优先使用内存中的 factors（前端直接传入，不经过 screenConfigJson）
        ScreenRequest req;
        if (screenConfigJson != null && !screenConfigJson.isEmpty()) {
            req = MAPPER.readValue(screenConfigJson, ScreenRequest.class);
        } else {
            req = new ScreenRequest();
        }
        // 如果 factors 在内存中有值（前端直接传的），覆盖 parse 结果
        if (this.factors != null && !this.factors.isEmpty()) {
            req.setFactors(this.factors);
        }
        return req;
    }

    /**
     * 为指定调仓日生成 ScreenRequest。
     * 核心逻辑：复用选股配置，将 screenDate 设为调仓日。
     */
    public ScreenRequest toScreenRequest(LocalDate rebalanceDate) {
        ScreenRequest req = parseScreenRequest();
        if (req == null) {
            req = new ScreenRequest();
        }
        req.setScreenDate(rebalanceDate);
        // 多日模式关闭：回测用单日选股
        req.setScreenStartDate(null);
        req.setScreenEndDate(null);
        return req;
    }

    /**
     * 将 ScreenRequest 序列化为 JSON 存入 screenConfigJson。
     */
    @SneakyThrows
    public void setScreenConfig(ScreenRequest screenRequest) {
        this.screenConfigJson = MAPPER.writeValueAsString(screenRequest);
    }

    /**
     * 解析调仓频率，返回实际含义（用于生成调仓日序列）。
     * WEEKLY → 每周第一个交易日
     * BIWEEKLY → 每两周第一个交易日
     * MONTHLY → 每月第一个交易日
     */
    public String getRebalanceFreq() {
        return rebalanceFreq != null ? rebalanceFreq : "MONTHLY";
    }
}
