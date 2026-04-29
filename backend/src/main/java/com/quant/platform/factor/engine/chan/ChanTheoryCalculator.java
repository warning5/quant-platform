package com.quant.platform.factor.engine.chan;

import com.quant.platform.market.domain.MarketDailyBar;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;

/**
 * 缠论基础计算引擎
 * 严格按缠中说禅《教你炒股票》系列定义实现
 * 计算链: K线合并(包含关系) → 分型识别 → 笔 → 线段(特质序列) → 中枢 → 走势类型 → 买卖点
 * 设计原则:
 * 1. 每一步都有严格的数学定义,不留模糊空间
 * 2. 输入输出都是结构化数据,可独立验证
 * 3. 支持日线级别K线,不绑定数据源
 * 4. 线程安全：纯计算，无状态
 */
@Slf4j
public class ChanTheoryCalculator {

    /** 笔最少包含的合并K线数（缠论要求顶底间有独立K线） */
    private static final int MIN_PEN_BAR_COUNT = 4;

    // ═══════════════════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════════════════

    /**
     * 从 MarketDailyBar 列表计算缠论结构
     * @param bars 历史数据（时间正序）
     * @return 缠论计算结果
     */
    public static ChanTheoryResult calculate(List<MarketDailyBar> bars) {
        if (bars == null || bars.size() < 5) {
            return ChanTheoryResult.empty();
        }

        // Step 1: K线合并
        List<MergedBar> merged = mergeBars(bars);
        if (merged.size() < 3) {
            return ChanTheoryResult.empty();
        }

        // Step 2: 分型识别
        List<Fractal> fractals = findFractals(merged);
        if (fractals.size() < 2) {
            return buildPartialResult(merged, fractals);
        }

        // Step 3: 笔
        List<Pen> pens = buildPens(fractals, merged);
        if (pens.size() < 3) {
            return buildPartialResult(merged, fractals, pens);
        }

        // Step 4: 线段
        List<Segment> segments = buildSegments(pens, merged);
        if (segments.size() < 3) {
            return buildPartialResult(merged, fractals, pens, segments);
        }

        // Step 5: 中枢
        List<Hub> hubs = buildHubs(segments);

        // Step 6: 走势类型
        List<Trend> trends = buildTrends(hubs, segments);

        // Step 7: 买卖点
        List<BuySellPoint> buySellPoints = findBuySellPoints(pens, segments, hubs, trends, merged);

        return ChanTheoryResult.builder()
                .mergedBars(merged)
                .fractals(fractals)
                .pens(pens)
                .segments(segments)
                .hubs(hubs)
                .trends(trends)
                .buySellPoints(buySellPoints)
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Step 1: K线合并 — 处理包含关系
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理K线包含关系,生成合并后的标准K线序列
     * 缠论定义: 当前后两根K线存在包含关系时(前高>=后高 且 前低<=后低, 或反之),
     * 需要合并为一根K线。合并方向取决于前一根非包含K线的方向:
     * - 上升时: 取高高+高低 → high=max(h1,h2), low=max(l1,l2)
     * - 下降时: 取低高+低低 → high=min(h1,h2), low=min(l1,l2)
     */
    public static List<MergedBar> mergeBars(List<MarketDailyBar> rawBars) {
        if (rawBars == null || rawBars.isEmpty()) {
            return new ArrayList<>();
        }

        List<MergedBar> merged = new ArrayList<>();

        // 第一根K线直接加入
        MarketDailyBar first = rawBars.getFirst();
        merged.add(MergedBar.builder()
                .index(0)
                .dateStart(first.getTradeDate())
                .dateEnd(first.getTradeDate())
                .open(first.getOpen().doubleValue())
                .high(first.getHigh().doubleValue())
                .low(first.getLow().doubleValue())
                .close(first.getClose().doubleValue())
                .direction(Direction.UP)  // 初始方向,后续会修正
                .rawIndices(new ArrayList<>(List.of(0)))
                .build());

        for (int i = 1; i < rawBars.size(); i++) {
            MarketDailyBar bar = rawBars.get(i);
            MergedBar last = merged.getLast();
            double barHigh = bar.getHigh().doubleValue();
            double barLow = bar.getLow().doubleValue();

            if (isContained(last.getHigh(), last.getLow(), barHigh, barLow)) {
                // 存在包含关系,需要合并
                Direction dir = last.getDirection();
                if (dir == Direction.UP) {
                    // 上升: 取高高、高低
                    last.setHigh(Math.max(last.getHigh(), barHigh));
                    last.setLow(Math.max(last.getLow(), barLow));
                } else {
                    // 下降: 取低高、低低
                    last.setHigh(Math.min(last.getHigh(), barHigh));
                    last.setLow(Math.min(last.getLow(), barLow));
                }
                last.setClose(bar.getClose().doubleValue());
                last.setDateEnd(bar.getTradeDate());
                last.getRawIndices().add(i);
            } else {
                // 不存在包含关系,新增合并K线
                Direction direction;
                if (barHigh > last.getHigh() && barLow > last.getLow()) {
                    direction = Direction.UP;
                } else if (barHigh < last.getHigh() && barLow < last.getLow()) {
                    direction = Direction.DOWN;
                } else {
                    direction = bar.getClose().doubleValue() >= last.getClose() ? Direction.UP : Direction.DOWN;
                }

                merged.add(MergedBar.builder()
                        .index(merged.size())
                        .dateStart(bar.getTradeDate())
                        .dateEnd(bar.getTradeDate())
                        .open(bar.getOpen().doubleValue())
                        .high(barHigh)
                        .low(barLow)
                        .close(bar.getClose().doubleValue())
                        .direction(direction)
                        .rawIndices(new ArrayList<>(List.of(i)))
                        .build());
            }
        }

        return merged;
    }

    /** 判断两根K线是否存在包含关系 */
    private static boolean isContained(double high1, double low1, double high2, double low2) {
        // a包含b: high1 >= high2 && low1 <= low2
        // b包含a: high2 >= high1 && low2 <= low1
        return (high1 >= high2 && low1 <= low2) || (high2 >= high1 && low2 <= low1);
    }

    // ═══════════════════════════════════════════════════════════
    // Step 2: 分型识别 — 顶分型与底分型
    // ═══════════════════════════════════════════════════════════

    /**
     * 识别分型
     * 顶分型: middle.high > left.high 且 middle.high > right.high
     * 底分型: middle.low < left.low 且 middle.low < right.low
     */
    public static List<Fractal> findFractals(List<MergedBar> mergedBars) {
        if (mergedBars == null || mergedBars.size() < 3) {
            return new ArrayList<>();
        }

        List<Fractal> fractals = new ArrayList<>();
        for (int i = 1; i < mergedBars.size() - 1; i++) {
            MergedBar left = mergedBars.get(i - 1);
            MergedBar middle = mergedBars.get(i);
            MergedBar right = mergedBars.get(i + 1);

            if (middle.getHigh() > left.getHigh() && middle.getHigh() > right.getHigh()) {
                fractals.add(Fractal.builder()
                        .index(i)
                        .fractalType(FractalType.TOP)
                        .high(middle.getHigh())
                        .low(middle.getLow())
                        .mergedBar(middle)
                        .build());
            } else if (middle.getLow() < left.getLow() && middle.getLow() < right.getLow()) {
                fractals.add(Fractal.builder()
                        .index(i)
                        .fractalType(FractalType.BOTTOM)
                        .high(middle.getHigh())
                        .low(middle.getLow())
                        .mergedBar(middle)
                        .build());
            }
        }

        return fractals;
    }

    // ═══════════════════════════════════════════════════════════
    // Step 3: 笔的生成 — 顶底分型交替连接
    // ═══════════════════════════════════════════════════════════

    /**
     * 从分型序列中构建笔
     * 核心逻辑:
     * 1. 过滤分型确保顶底严格交替,间距>=4
     * 2. 连续同类型分型取极值(顶取最高,底取最低)
     * 3. 上升笔: 底→顶, 终点价格>起点
     * 4. 下降笔: 顶→底, 终点价格<起点
     */
    public static List<Pen> buildPens(List<Fractal> fractals, List<MergedBar> mergedBars) {
        if (fractals == null || fractals.size() < 2) {
            return new ArrayList<>();
        }

        // 过滤有效分型: 顶底必须交替,且间距足够
        List<Fractal> validFractals = filterValidFractals(fractals);
        if (validFractals.size() < 2) {
            return new ArrayList<>();
        }

        List<Pen> pens = new ArrayList<>();
        for (int i = 1; i < validFractals.size(); i++) {
            Fractal prevF = validFractals.get(i - 1);
            Fractal currF = validFractals.get(i);

            // 必须一顶一底
            if (prevF.getFractalType() == currF.getFractalType()) {
                continue;
            }

            // 确定方向和起终点
            Direction direction;
            double startPrice, endPrice;

            if (prevF.getFractalType() == FractalType.BOTTOM) {
                // 上升笔: 从底到顶
                direction = Direction.UP;
                startPrice = prevF.getLow();
                endPrice = currF.getHigh();
            } else {
                // 下降笔: 从顶到底
                direction = Direction.DOWN;
                startPrice = prevF.getHigh();
                endPrice = currF.getLow();
            }

            // 有效性检查
            if (direction == Direction.UP && endPrice <= startPrice) continue;
            if (direction == Direction.DOWN && endPrice >= startPrice) continue;

            // 间距检查
            int barCount = Math.abs(currF.getIndex() - prevF.getIndex()) + 1;
            if (barCount < MIN_PEN_BAR_COUNT) continue;

            LocalDate startDate = prevF.getIndex() < mergedBars.size()
                    ? mergedBars.get(prevF.getIndex()).getDateStart() : null;
            LocalDate endDate = currF.getIndex() < mergedBars.size()
                    ? mergedBars.get(currF.getIndex()).getDateStart() : null;

            pens.add(Pen.builder()
                    .index(pens.size())
                    .direction(direction)
                    .startIndex(prevF.getIndex())
                    .endIndex(currF.getIndex())
                    .startPrice(startPrice)
                    .endPrice(endPrice)
                    .startDate(startDate)
                    .endDate(endDate)
                    .barCount(barCount)
                    .build());
        }

        return pens;
    }

    /**
     * 过滤无效分型,确保顶底严格交替,且间距>=4
     * 改进后的算法(修复Python版过度过滤问题):
     * 1. 从左到右扫描分型
     * 2. 维护当前"待确认"分型 candidate
     * 3. 如果遇到同类型分型: 取极值(顶取最高,底取最低),更新candidate
     * 4. 如果遇到异类型分型:
     *    - 间距>=MIN_PEN_BAR_COUNT: 确认candidate入列,新分型成为candidate
     *    - 间距<MIN_PEN_BAR_COUNT: 不确认candidate,将新分型与candidate比较,
     *      如果新分型更极端则替换candidate(跳过中间的无效分型)
     */
    private static List<Fractal> filterValidFractals(List<Fractal> fractals) {
        if (fractals.isEmpty()) {
            return new ArrayList<>();
        }

        List<Fractal> result = new ArrayList<>();
        Fractal candidate = fractals.get(0);

        for (int i = 1; i < fractals.size(); i++) {
            Fractal f = fractals.get(i);

            if (f.getFractalType() == candidate.getFractalType()) {
                // 同类型: 取极值
                if (f.getFractalType() == FractalType.TOP) {
                    if (f.getHigh() > candidate.getHigh()) {
                        candidate = f;
                    }
                } else {
                    if (f.getLow() < candidate.getLow()) {
                        candidate = f;
                    }
                }
            } else {
                // 异类型: 检查间距
                int gap = f.getIndex() - candidate.getIndex();

                if (gap >= MIN_PEN_BAR_COUNT) {
                    // 间距足够,确认candidate并切换
                    result.add(candidate);
                    candidate = f;
                } else {
                    // 间距不够 — 关键改进: 不简单丢弃,而是看谁更极端
                    // 将candidate与f比较,选择保留哪个类型取决于已确认列表
                    if (!result.isEmpty()) {
                        Fractal lastConfirmed = result.get(result.size() - 1);
                        // candidate 和 lastConfirmed 是交替的,所以 f 和 lastConfirmed 同类型
                        // 用 f 替换 lastConfirmed 如果 f 更极端
                        if (f.getFractalType() == FractalType.TOP && f.getHigh() > lastConfirmed.getHigh()) {
                            result.set(result.size() - 1, f);
                            // candidate 废弃,重新从 f 之后开始找
                            candidate = f; // 会被后续覆盖
                        } else if (f.getFractalType() == FractalType.BOTTOM && f.getLow() < lastConfirmed.getLow()) {
                            result.set(result.size() - 1, f);
                            candidate = f;
                        }
                        // 否则 f 不够极端,丢弃 f,保留 candidate 继续等待
                    }
                    // 如果 result 为空,间距不够就直接丢弃 candidate,用 f 作为新 candidate
                    // (第一个分型间距不足说明太紧凑,等下一个)
                }
            }
        }

        // 最后一个candidate
        if (!result.isEmpty()) {
            Fractal last = result.get(result.size() - 1);
            if (candidate.getFractalType() != last.getFractalType()
                    && candidate.getIndex() - last.getIndex() >= MIN_PEN_BAR_COUNT) {
                result.add(candidate);
            }
        } else {
            result.add(candidate);
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // Step 4: 线段计算 — 特质序列
    // ═══════════════════════════════════════════════════════════

    /**
     * 从笔序列推导线段
     *
     * 简化判断(实用版):
     * - 3笔重叠构成线段
     * - 线段被破坏: 特质序列出现分型
     *   上升线段: 反向笔低点低于前一笔低点,且终点低于前一笔起点
     *   下降线段: 反向笔高点高于前一笔高点,且终点高于前一笔起点
     */
    public static List<Segment> buildSegments(List<Pen> pens, List<MergedBar> mergedBars) {
        if (pens == null || pens.size() < 3) {
            return new ArrayList<>();
        }

        List<Segment> segments = new ArrayList<>();
        int segStartPen = 0;

        int i = 2;
        while (i < pens.size()) {
            if (isSegmentBroken(pens, segStartPen, i)) {
                Pen startPen = pens.get(segStartPen);
                Pen endPen = pens.get(i - 1);

                segments.add(Segment.builder()
                        .index(segments.size())
                        .direction(startPen.getDirection())
                        .startIndex(segStartPen)
                        .endIndex(i - 1)
                        .startPrice(startPen.getStartPrice())
                        .endPrice(endPen.getEndPrice())
                        .startDate(startPen.getStartDate())
                        .endDate(endPen.getEndDate())
                        .penCount(i - segStartPen)
                        .build());

                segStartPen = i - 1;
                i++;
            } else {
                i++;
            }
        }

        // 最后一段(可能未完成)
        if (segStartPen < pens.size() - 1) {
            Pen startPen = pens.get(segStartPen);
            Pen endPen = pens.get(pens.size() - 1);
            segments.add(Segment.builder()
                    .index(segments.size())
                    .direction(startPen.getDirection())
                    .startIndex(segStartPen)
                    .endIndex(pens.size() - 1)
                    .startPrice(startPen.getStartPrice())
                    .endPrice(endPen.getEndPrice())
                    .startDate(startPen.getStartDate())
                    .endDate(endPen.getEndDate())
                    .penCount(pens.size() - segStartPen)
                    .build());
        }

        return segments;
    }

    /**
     * 判断线段是否在当前位置被破坏
     *
     * 上升线段中的破坏: 特质序列出现顶分型,即上升笔序列高点先升后降
     * 下降线段中的破坏: 特质序列出现底分型,即下降笔序列低点先降后升
     */
    private static boolean isSegmentBroken(List<Pen> pens, int segStart, int current) {
        if (current < 2 || current - segStart < 2) {
            return false;
        }

        Direction segDirection = pens.get(segStart).getDirection();
        Pen p0 = pens.get(current - 2);
        Pen p1 = pens.get(current - 1);
        Pen p2 = pens.get(current);

        if (segDirection == Direction.UP) {
            // 上升线段被破坏: 特质序列出现顶分型
            if (p1.getDirection() == Direction.UP && p2.getDirection() == Direction.DOWN) {
                if (p1.getEndPrice() > p0.getEndPrice() && p1.getEndPrice() > p2.getEndPrice()) {
                    // 回穿确认: 下降笔的终点低于上升笔的起点
                    return p2.getEndPrice() < p1.getStartPrice();
                }
            }
        } else {
            // 下降线段被破坏: 特质序列出现底分型
            if (p1.getDirection() == Direction.DOWN && p2.getDirection() == Direction.UP) {
                if (p1.getEndPrice() < p0.getEndPrice() && p1.getEndPrice() < p2.getEndPrice()) {
                    // 反弹确认: 上升笔的终点高于下降笔的起点
                    return p2.getEndPrice() > p1.getStartPrice();
                }
            }
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // Step 5: 中枢识别
    // ═══════════════════════════════════════════════════════════

    /**
     * 由线段构建中枢
     *
     * 缠论定义:
     * 1. 最低级别中枢: 三段连续线段的价格区间有重叠
     * 2. 中枢区间(ZD, ZG): 三段重叠部分的(最高低点, 最低高点)
     * 3. 中枢延伸: 后续线段进入中枢区间则中枢延伸
     * 4. 中枢结束: 后续线段完全离开中枢区间
     */
    public static List<Hub> buildHubs(List<Segment> segments) {
        if (segments == null || segments.size() < 3) {
            return new ArrayList<>();
        }

        List<Hub> hubs = new ArrayList<>();
        int i = 0;

        while (i <= segments.size() - 3) {
            Segment s1 = segments.get(i);
            Segment s2 = segments.get(i + 1);
            Segment s3 = segments.get(i + 2);

            // 计算三段的重叠区间
            double overlapHigh = minOf(
                    segHigh(s1), segHigh(s2), segHigh(s3));
            double overlapLow = maxOf(
                    segLow(s1), segLow(s2), segLow(s3));

            if (overlapHigh > overlapLow) {
                // 存在重叠,构成中枢
                int hubStart = i;
                int hubEnd = i + 2;
                int oscillationCount = 1;

                // 中枢延伸
                int j = i + 3;
                while (j < segments.size()) {
                    Segment seg = segments.get(j);
                    double segH = segHigh(seg);
                    double segL = segLow(seg);

                    if (segL < overlapHigh && segH > overlapLow) {
                        hubEnd = j;
                        oscillationCount++;
                        overlapHigh = Math.min(overlapHigh, segH);
                        overlapLow = Math.max(overlapLow, segL);
                        j++;
                    } else {
                        break;
                    }
                }

                double zz = (overlapHigh + overlapLow) / 2;
                int level = oscillationCount >= 9 ? 1 : 0;

                hubs.add(Hub.builder()
                        .index(hubs.size())
                        .level(level)
                        .high(overlapHigh)
                        .low(overlapLow)
                        .zz(zz)
                        .startIndex(hubStart)
                        .endIndex(hubEnd)
                        .startDate(segments.get(hubStart).getStartDate())
                        .endDate(segments.get(hubEnd).getEndDate())
                        .segmentCount(hubEnd - hubStart + 1)
                        .oscillationCount(oscillationCount)
                        .build());

                i = hubEnd + 1;
            } else {
                i++;
            }
        }

        return hubs;
    }

    /** 线段的高点 */
    private static double segHigh(Segment seg) {
        return Math.max(seg.getStartPrice(), seg.getEndPrice());
    }

    /** 线段的低点 */
    private static double segLow(Segment seg) {
        return Math.min(seg.getStartPrice(), seg.getEndPrice());
    }

    private static double minOf(double a, double b, double c) {
        return Math.min(Math.min(a, b), c);
    }

    private static double maxOf(double a, double b, double c) {
        return Math.max(Math.max(a, b), c);
    }

    // ═══════════════════════════════════════════════════════════
    // Step 6: 走势类型判断
    // ═══════════════════════════════════════════════════════════

    /**
     * 由中枢判断走势类型
     *
     * 缠论定义:
     * - 盘整: 只有一个中枢
     * - 趋势: 两个或以上同向中枢
     *   - 上涨趋势: 后一个中枢的区间高于前一个
     *   - 下跌趋势: 后一个中枢的区间低于前一个
     */
    public static List<Trend> buildTrends(List<Hub> hubs, List<Segment> segments) {
        if (hubs == null || hubs.isEmpty()) {
            return new ArrayList<>();
        }

        List<Trend> trends = new ArrayList<>();
        int i = 0;

        while (i < hubs.size()) {
            Hub hub = hubs.get(i);

            // 检查是否能与后续中枢构成趋势
            if (i + 1 < hubs.size()) {
                Hub nextHub = hubs.get(i + 1);

                if (hub.getLow() > nextHub.getHigh()) {
                    // 下跌趋势
                    trends.add(Trend.builder()
                            .index(trends.size())
                            .trendType(TrendType.DOWNTREND)
                            .startIndex(hub.getStartIndex())
                            .endIndex(nextHub.getEndIndex())
                            .startDate(hub.getStartDate())
                            .endDate(nextHub.getEndDate())
                            .hubs(new ArrayList<>(List.of(hub, nextHub)))
                            .direction(Direction.DOWN)
                            .build());
                    i += 2;
                    continue;
                } else if (nextHub.getLow() > hub.getHigh()) {
                    // 上涨趋势
                    trends.add(Trend.builder()
                            .index(trends.size())
                            .trendType(TrendType.UPTREND)
                            .startIndex(hub.getStartIndex())
                            .endIndex(nextHub.getEndIndex())
                            .startDate(hub.getStartDate())
                            .endDate(nextHub.getEndDate())
                            .hubs(new ArrayList<>(List.of(hub, nextHub)))
                            .direction(Direction.UP)
                            .build());
                    i += 2;
                    continue;
                }
            }

            // 单独中枢 → 盘整
            Direction direction = Direction.UP;
            if (hub.getEndIndex() + 1 < segments.size()) {
                direction = segments.get(hub.getEndIndex() + 1).getDirection();
            }

            trends.add(Trend.builder()
                    .index(trends.size())
                    .trendType(TrendType.CONSOLIDATION)
                    .startIndex(hub.getStartIndex())
                    .endIndex(hub.getEndIndex())
                    .startDate(hub.getStartDate())
                    .endDate(hub.getEndDate())
                    .hubs(new ArrayList<>(List.of(hub)))
                    .direction(direction)
                    .build());
            i++;
        }

        return trends;
    }

    // ═══════════════════════════════════════════════════════════
    // Step 7: 买卖点识别
    // ═══════════════════════════════════════════════════════════

    /**
     * 识别买卖点
     *
     * 缠论定义:
     * - 一买: 下跌趋势背驰(最后一个中枢之后的下跌力度减弱)
     * - 二买: 一买之后的回调不创新低
     * - 三买: 离开中枢后回抽不进中枢
     * - 卖点对称定义
     */
    public static List<BuySellPoint> findBuySellPoints(
            List<Pen> pens, List<Segment> segments,
            List<Hub> hubs, List<Trend> trends,
            List<MergedBar> mergedBars) {

        List<BuySellPoint> points = new ArrayList<>();
        if (trends == null || pens == null || trends.isEmpty()) {
            return points;
        }

        for (Trend trend : trends) {
            if (trend.getTrendType() == TrendType.DOWNTREND) {
                findBuyPointsInDowntrend(trend, pens, mergedBars, points);
            } else if (trend.getTrendType() == TrendType.UPTREND) {
                findSellPointsInUptrend(trend, pens, mergedBars, points);
            }
        }

        // 对盘整中的中枢检查三买三卖
        for (Trend trend : trends) {
            if (trend.getTrendType() == TrendType.CONSOLIDATION) {
                findPointsInConsolidation(trend, pens, segments, hubs, mergedBars, points);
            }
        }

        // 按日期排序
        points.sort(Comparator.comparing(BuySellPoint::getDate));
        return points;
    }

    /** 下跌趋势中寻找买点 */
    private static void findBuyPointsInDowntrend(Trend trend, List<Pen> pens,
                                                  List<MergedBar> mergedBars,
                                                  List<BuySellPoint> points) {
        if (trend.getHubs().size() < 2) return;

        Hub lastHub = trend.getHubs().get(trend.getHubs().size() - 1);
        int lastPenIdx = trend.getEndIndex();

        if (lastPenIdx >= 1 && lastPenIdx < pens.size()) {
            Pen lastPen = pens.get(lastPenIdx);
            Pen prevPen = pens.get(lastPenIdx - 1);

            if (lastPen.getDirection() == Direction.DOWN) {
                // 背驰判断: 最后一笔的幅度 < 前一笔的幅度
                double lastAmplitude = Math.abs(lastPen.getStartPrice() - lastPen.getEndPrice());
                double prevAmplitude = Math.abs(prevPen.getStartPrice() - prevPen.getEndPrice());

                if (prevAmplitude > 0 && lastAmplitude < prevAmplitude) {
                    int idx = lastPen.getEndIndex();
                    if (idx < mergedBars.size()) {
                        points.add(BuySellPoint.builder()
                                .index(idx)
                                .buySellType(BuySellType.FIRST_BUY)
                                .price(lastPen.getEndPrice())
                                .date(mergedBars.get(idx).getDateEnd())
                                .hub(lastHub)
                                .build());
                    }
                }
            }
        }
    }

    /** 上涨趋势中寻找卖点 */
    private static void findSellPointsInUptrend(Trend trend, List<Pen> pens,
                                                 List<MergedBar> mergedBars,
                                                 List<BuySellPoint> points) {
        if (trend.getHubs().size() < 2) return;

        Hub lastHub = trend.getHubs().get(trend.getHubs().size() - 1);
        int lastPenIdx = trend.getEndIndex();

        if (lastPenIdx >= 1 && lastPenIdx < pens.size()) {
            Pen lastPen = pens.get(lastPenIdx);
            Pen prevPen = pens.get(lastPenIdx - 1);

            if (lastPen.getDirection() == Direction.UP) {
                double lastAmplitude = Math.abs(lastPen.getEndPrice() - lastPen.getStartPrice());
                double prevAmplitude = Math.abs(prevPen.getEndPrice() - prevPen.getStartPrice());

                if (prevAmplitude > 0 && lastAmplitude < prevAmplitude) {
                    int idx = lastPen.getEndIndex();
                    if (idx < mergedBars.size()) {
                        points.add(BuySellPoint.builder()
                                .index(idx)
                                .buySellType(BuySellType.FIRST_SELL)
                                .price(lastPen.getEndPrice())
                                .date(mergedBars.get(idx).getDateEnd())
                                .hub(lastHub)
                                .build());
                    }
                }
            }
        }
    }

    /** 盘整中寻找三买三卖 */
    private static void findPointsInConsolidation(Trend trend, List<Pen> pens,
                                                   List<Segment> segments,
                                                   List<Hub> allHubs,
                                                   List<MergedBar> mergedBars,
                                                   List<BuySellPoint> points) {
        if (trend.getHubs().isEmpty()) return;
        Hub hub = trend.getHubs().get(0);

        // 三买: 中枢后的回调低点 > 中枢上沿(ZG)
        if (hub.getEndIndex() + 1 < segments.size()) {
            Segment nextSeg = segments.get(hub.getEndIndex() + 1);
            double segLow = Math.min(nextSeg.getStartPrice(), nextSeg.getEndPrice());

            if (segLow > hub.getHigh()) {
                int idx = nextSeg.getEndIndex();
                if (idx < pens.size()) {
                    Pen pen = pens.get(idx);
                    int barIdx = pen.getEndIndex();
                    if (barIdx < mergedBars.size()) {
                        points.add(BuySellPoint.builder()
                                .index(barIdx)
                                .buySellType(BuySellType.THIRD_BUY)
                                .price(pen.getEndPrice())
                                .date(mergedBars.get(barIdx).getDateEnd())
                                .hub(hub)
                                .build());
                    }
                }
            }
        }

        // 三卖: 中枢前的反弹高点 < 中枢下沿(ZD)
        if (hub.getStartIndex() > 0) {
            Segment prevSeg = segments.get(hub.getStartIndex() - 1);
            double segHigh = Math.max(prevSeg.getStartPrice(), prevSeg.getEndPrice());

            if (segHigh < hub.getLow()) {
                int idx = prevSeg.getEndIndex();
                if (idx < pens.size()) {
                    Pen pen = pens.get(idx);
                    int barIdx = pen.getEndIndex();
                    if (barIdx < mergedBars.size()) {
                        points.add(BuySellPoint.builder()
                                .index(barIdx)
                                .buySellType(BuySellType.THIRD_SELL)
                                .price(pen.getEndPrice())
                                .date(mergedBars.get(barIdx).getDateEnd())
                                .hub(hub)
                                .build());
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private static ChanTheoryResult buildPartialResult(List<MergedBar> merged, List<Fractal> fractals) {
        return ChanTheoryResult.builder()
                .mergedBars(merged)
                .fractals(fractals)
                .pens(new ArrayList<>())
                .segments(new ArrayList<>())
                .hubs(new ArrayList<>())
                .trends(new ArrayList<>())
                .buySellPoints(new ArrayList<>())
                .build();
    }

    private static ChanTheoryResult buildPartialResult(List<MergedBar> merged, List<Fractal> fractals,
                                                        List<Pen> pens) {
        return ChanTheoryResult.builder()
                .mergedBars(merged)
                .fractals(fractals)
                .pens(pens)
                .segments(new ArrayList<>())
                .hubs(new ArrayList<>())
                .trends(new ArrayList<>())
                .buySellPoints(new ArrayList<>())
                .build();
    }

    private static ChanTheoryResult buildPartialResult(List<MergedBar> merged, List<Fractal> fractals,
                                                        List<Pen> pens, List<Segment> segments) {
        return ChanTheoryResult.builder()
                .mergedBars(merged)
                .fractals(fractals)
                .pens(pens)
                .segments(segments)
                .hubs(new ArrayList<>())
                .trends(new ArrayList<>())
                .buySellPoints(new ArrayList<>())
                .build();
    }
}
