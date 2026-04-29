package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 中枢：由至少三段连续重叠构成
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hub {
    /** 中枢序号(0-based) */
    private int index;
    /** 中枢级别（0=最低级别由线段构成,1=高一级...） */
    @Builder.Default
    private int level = 0;
    /** 中枢上沿(ZG) */
    private double high;
    /** 中枢下沿(ZD) */
    private double low;
    /** 中枢中轴 = (high + low) / 2 */
    private double zz;
    /** 起始线段序号 */
    private int startIndex;
    /** 结束线段序号 */
    private int endIndex;
    private LocalDate startDate;
    private LocalDate endDate;
    /** 进入中枢的线段数 */
    @Builder.Default
    private int segmentCount = 0;
    /** 震荡次数 */
    @Builder.Default
    private int oscillationCount = 0;
}
