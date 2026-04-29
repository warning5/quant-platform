package com.quant.platform.factor.engine.chan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 缠论计算完整结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChanTheoryResult {
    private List<MergedBar> mergedBars;
    private List<Fractal> fractals;
    private List<Pen> pens;
    private List<Segment> segments;
    private List<Hub> hubs;
    private List<Trend> trends;
    private List<BuySellPoint> buySellPoints;

    /** 获取最后一笔，无笔时返回null */
    public Pen lastPen() {
        return pens == null || pens.isEmpty() ? null : pens.getLast();
    }

    /** 获取最后一个中枢 */
    public Hub lastHub() {
        return hubs == null || hubs.isEmpty() ? null : hubs.getLast();
    }

    /** 获取最后一个走势 */
    public Trend lastTrend() {
        return trends == null || trends.isEmpty() ? null : trends.getLast();
    }

    /** 获取最新的买卖点 */
    public BuySellPoint lastBuySellPoint() {
        return buySellPoints == null || buySellPoints.isEmpty() ? null : buySellPoints.getLast();
    }

    /** 空结果 */
    public static ChanTheoryResult empty() {
        return ChanTheoryResult.builder()
                .mergedBars(new ArrayList<>())
                .fractals(new ArrayList<>())
                .pens(new ArrayList<>())
                .segments(new ArrayList<>())
                .hubs(new ArrayList<>())
                .trends(new ArrayList<>())
                .buySellPoints(new ArrayList<>())
                .build();
    }
}
