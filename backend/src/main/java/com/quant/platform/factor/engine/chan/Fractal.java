package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分型：顶分型或底分型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fractal {
    /** 分型中间K线在合并序列中的位置 */
    private int index;
    /** 分型类型 */
    private FractalType fractalType;
    /** 分型的高点 */
    private double high;
    /** 分型的低点 */
    private double low;
    /** 中间那根合并K线 */
    private MergedBar mergedBar;
}
