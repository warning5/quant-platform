package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 笔：由相邻的顶底分型连接而成
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Pen {
    /** 笔的序号(0-based) */
    private int index;
    /** 笔的方向 */
    private Direction direction;
    /** 起始分型的合并序列位置 */
    private int startIndex;
    /** 结束分型的合并序列位置 */
    private int endIndex;
    /** 起始价格 */
    private double startPrice;
    /** 结束价格 */
    private double endPrice;
    /** 起始日期 */
    private LocalDate startDate;
    /** 结束日期 */
    private LocalDate endDate;
    /** 笔内合并K线数量（含端点，>=4） */
    @Builder.Default
    private int barCount = 0;
}
