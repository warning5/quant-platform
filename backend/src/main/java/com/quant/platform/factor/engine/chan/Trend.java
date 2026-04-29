package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 走势类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trend {
    private int index;
    private TrendType trendType;
    /** 起始线段序号 */
    private int startIndex;
    /** 结束线段序号(-1表示未结束) */
    private int endIndex;
    private LocalDate startDate;
    private LocalDate endDate;
    @Builder.Default
    private List<Hub> hubs = new ArrayList<>();
    @Builder.Default
    private Direction direction = Direction.UP;
}
