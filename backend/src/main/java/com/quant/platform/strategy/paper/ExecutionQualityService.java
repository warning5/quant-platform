package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行质量分析服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionQualityService {

    private final PaperExecutionQualityMapper paperExecutionQualityMapper;

    /**
     * 获取执行质量报告
     */
    public Map<String, Object> getQualityReport(Long paperId) {
        List<PaperExecutionQuality> records = paperExecutionQualityMapper.selectList(
                new LambdaQueryWrapper<PaperExecutionQuality>()
                        .eq(PaperExecutionQuality::getPaperId, paperId)
                        .orderByDesc(PaperExecutionQuality::getExecutionTime)
        );

        if (records.isEmpty()) {
            return Collections.emptyMap();
        }

        int totalTrades = records.size();

        // 平均偏差（%）
        BigDecimal avgDeviationPct = records.stream()
                .filter(r -> r.getPriceDeviationPct() != null)
                .map(PaperExecutionQuality::getPriceDeviationPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP);

        // 平均滑点成本（元）
        BigDecimal avgSlippageCost = records.stream()
                .filter(r -> r.getSlippageCost() != null)
                .map(PaperExecutionQuality::getSlippageCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);

        // 总交易成本
        BigDecimal totalCost = records.stream()
                .filter(r -> r.getTotalCost() != null)
                .map(PaperExecutionQuality::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 平均成交率
        BigDecimal avgFillRate = records.stream()
                .filter(r -> r.getFillRate() != null)
                .map(PaperExecutionQuality::getFillRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP);

        // 买入/卖出分别统计
        long buyCount = records.stream().filter(r -> "BUY".equals(r.getDirection())).count();
        long sellCount = records.stream().filter(r -> "SELL".equals(r.getDirection())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTrades", totalTrades);
        result.put("records", records);
        result.put("avgDeviationPct", avgDeviationPct);
        result.put("avgSlippageCost", avgSlippageCost);
        result.put("totalCost", totalCost);
        result.put("avgFillRate", avgFillRate);
        result.put("buyCount", buyCount);
        result.put("sellCount", sellCount);

        return result;
    }
}
