package com.quant.platform.dataupdate;

import com.quant.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据质量监控 API
 * - 数据新鲜度检查（日线/因子/财务）
 * - 价格异常检测
 * - 全量质量扫描
 */
@Slf4j
@RestController
@RequestMapping("/data-quality")
@RequiredArgsConstructor
@Tag(name = "数据质量", description = "数据新鲜度、异常检测等质量监控接口")
public class DataQualityController {

    private final DataQualityService dataQualityService;

    /** 全量质量扫描 */
    @GetMapping("/full")
    @Operation(summary = "全量质量扫描", description = "同时执行新鲜度检查和价格异常检测")
    public ApiResponse<Map<String, Object>> fullCheck() {
        return ApiResponse.success(dataQualityService.runAllChecks());
    }

    /** 数据新鲜度检查 */
    @GetMapping("/freshness")
    @Operation(summary = "数据新鲜度检查", description = "检查日线/因子/财务数据是否过期")
    public ApiResponse<Map<String, Object>> freshness() {
        return ApiResponse.success(dataQualityService.checkDataFreshness());
    }

    /** 价格异常检测 */
    @GetMapping("/price-anomalies")
    @Operation(summary = "价格异常检测", description = "查询近N天内涨跌幅>50%的记录")
    public ApiResponse<Map<String, Object>> priceAnomalies(
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(dataQualityService.checkPriceAnomalies(days));
    }

    /** 因子NULL检测 */
    @GetMapping("/factor-nulls")
    @Operation(summary = "因子NULL检测", description = "检查因子值在最新交易日的NULL比例，>50%为异常")
    public ApiResponse<Map<String, Object>> factorNulls() {
        return ApiResponse.success(dataQualityService.checkFactorNullRatio());
    }

    /** 财务突变检测 */
    @GetMapping("/financial-anomalies")
    @Operation(summary = "财务突变检测", description = "基于单季值（累计值拆解）检查营收/净利润环比跳变>500%的记录")
    public ApiResponse<Map<String, Object>> financialAnomalies() {
        return ApiResponse.success(dataQualityService.checkFinancialAnomalies());
    }
}
