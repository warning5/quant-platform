package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 合并后的K线（处理完包含关系后的标准K线）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergedBar {
    /**
     * 合并后在序列中的位置(0-based)
     */
    private int index;
    /**
     * 起始日期
     */
    private LocalDate dateStart;
    /**
     * 结束日期
     */
    private LocalDate dateEnd;
    private double open;
    private double high;
    private double low;
    private double close;
    /**
     * 合并方向（由包含关系处理确定）
     */
    private Direction direction;
    /**
     * 包含的原始K线索引列表
     */
    @Builder.Default
    private List<Integer> rawIndices = new ArrayList<>();
}
