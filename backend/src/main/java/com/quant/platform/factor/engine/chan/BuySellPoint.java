package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 买卖点
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuySellPoint {
    /** 在合并序列中的位置 */
    private int index;
    private BuySellType buySellType;
    private double price;
    private LocalDate date;
    /** 关联的中枢（如有） */
    private Hub hub;
}
