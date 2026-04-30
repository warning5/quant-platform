package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 线段：由笔的特质序列推导而来
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Segment {
    /**
     * 线段序号(0-based)
     */
    private int index;
    /**
     * 线段方向
     */
    private Direction direction;
    /**
     * 起始笔的序号
     */
    private int startIndex;
    /**
     * 结束笔的序号
     */
    private int endIndex;
    /**
     * 起始价格
     */
    private double startPrice;
    /**
     * 结束价格
     */
    private double endPrice;
    /**
     * 起始日期
     */
    private LocalDate startDate;
    /**
     * 结束日期
     */
    private LocalDate endDate;
    /**
     * 线段内包含的笔数
     */
    @Builder.Default
    private int penCount = 0;
}
