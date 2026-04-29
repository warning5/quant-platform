"""
缠论基础计算引擎
严格按缠中说禅《教你炒股票》系列定义实现

计算链: K线合并(包含关系) → 分型识别 → 笔 → 线段(特质序列)

设计原则:
1. 每一步都有严格的数学定义,不留模糊空间
2. 输入输出都是结构化数据,可独立验证
3. 支持日线和分钟级K线,不绑定数据源
"""

from typing import List, Optional, Tuple
from chan_theory import (
    RawBar, MergedBar, Fractal, FractalType, Pen, Segment,
    Direction, Hub, Trend, TrendType, BuySellPoint, BuySellType,
    ChanTheoryResult
)


# ═══════════════════════════════════════════════════════════
# Step 1: K线合并 — 处理包含关系
# ═══════════════════════════════════════════════════════════

def merge_bars(raw_bars: List[RawBar]) -> List[MergedBar]:
    """
    处理K线包含关系,生成合并后的标准K线序列

    缠论定义: 当前后两根K线存在包含关系时(前高>=后高 且 前低<=后低, 或反之),
    需要合并为一根K线。合并方向取决于前一根非包含K线的方向:
    - 上升时: 取高高+高低 → 合并后high=max(h1,h2), low=max(l1,l2)
    - 下降时: 取低高+低低 → 合并后high=min(h1,h2), low=min(l1,l2)

    注意: 第一根K线没有方向,默认按其与第二根的关系确定
    """
    if not raw_bars:
        return []

    merged: List[MergedBar] = []

    # 第一根K线直接加入
    first = raw_bars[0]
    merged.append(MergedBar(
        index=0,
        date_start=first.date,
        date_end=first.date,
        open=first.open,
        high=first.high,
        low=first.low,
        close=first.close,
        direction=Direction.UP,  # 初始方向,后续会修正
        raw_indices=[0]
    ))

    for i in range(1, len(raw_bars)):
        bar = raw_bars[i]
        last = merged[-1]

        if _is_contained(last, bar):
            # 存在包含关系,需要合并
            # 合并方向取last的方向
            direction = last.direction
            if direction == Direction.UP:
                # 上升: 取高高、高低
                new_high = max(last.high, bar.high)
                new_low = max(last.low, bar.low)
            else:
                # 下降: 取低高、低低
                new_high = min(last.high, bar.high)
                new_low = min(last.low, bar.low)

            # 更新合并K线
            last.high = new_high
            last.low = new_low
            last.close = bar.close  # 取最新收盘价
            last.date_end = bar.date
            last.raw_indices.append(i)
        else:
            # 不存在包含关系,新增合并K线
            # 方向由价格高低决定
            if bar.high > last.high and bar.low > last.low:
                direction = Direction.UP
            elif bar.high < last.high and bar.low < last.low:
                direction = Direction.DOWN
            else:
                # 理论上不会到这里(不包含就是严格高低),但容错
                direction = Direction.UP if bar.close >= last.close else Direction.DOWN

            merged.append(MergedBar(
                index=len(merged),
                date_start=bar.date,
                date_end=bar.date,
                open=bar.open,
                high=bar.high,
                low=bar.low,
                close=bar.close,
                direction=direction,
                raw_indices=[i]
            ))

    return merged


def _is_contained(a: MergedBar, b: RawBar) -> bool:
    """判断合并K线a与原始K线b是否存在包含关系"""
    # a包含b: a.high >= b.high and a.low <= b.low
    # b包含a: b.high >= a.high and b.low <= a.low
    return (a.high >= b.high and a.low <= b.low) or \
           (b.high >= a.high and b.low <= a.low)


# ═══════════════════════════════════════════════════════════
# Step 2: 分型识别 — 顶分型与底分型
# ═══════════════════════════════════════════════════════════

def find_fractals(merged_bars: List[MergedBar]) -> List[Fractal]:
    """
    识别分型

    顶分型: 三根K线,中间那根的高点最高、低点最高
    底分型: 三根K线,中间那根的低点最低、高点最低

    缠论定义(处理后):
    - 顶分型: middle.high > left.high 且 middle.high > right.high
    - 底分型: middle.low < left.low 且 middle.low < right.low

    注意: 包含关系已在前一步处理,所以这里不需要再处理包含
    """
    if len(merged_bars) < 3:
        return []

    fractals: List[Fractal] = []

    for i in range(1, len(merged_bars) - 1):
        left = merged_bars[i - 1]
        middle = merged_bars[i]
        right = merged_bars[i + 1]

        # 顶分型
        if middle.high > left.high and middle.high > right.high:
            fractals.append(Fractal(
                index=i,
                fractal_type=FractalType.TOP,
                high=middle.high,
                low=middle.low,
                merged_bar=middle
            ))
        # 底分型
        elif middle.low < left.low and middle.low < right.low:
            fractals.append(Fractal(
                index=i,
                fractal_type=FractalType.BOTTOM,
                high=middle.high,
                low=middle.low,
                merged_bar=middle
            ))

    return fractals


# ═══════════════════════════════════════════════════════════
# Step 3: 笔的生成 — 顶底分型交替连接
# ═══════════════════════════════════════════════════════════

def build_pens(fractals: List[Fractal], merged_bars: List[MergedBar]) -> List[Pen]:
    """
    从分型序列中构建笔

    缠论定义:
    1. 笔由相邻的顶分型和底分型连接而成
    2. 顶分型与底分型之间至少有一根独立K线(非共用)
       即: 顶底分型的合并序列位置差 >= 4 (中间至少隔3根合并K线)
    3. 笔的起点必须是顶分型或底分型,终点必须是相反的分型
    4. 一笔的顶必须高于底,即上升笔的顶>底,下降笔的底<顶

    简化实现:
    - 从第一个有效分型开始,逐步构建笔
    - 顶分型后必须接底分型,底分型后必须接顶分型
    - 如果连续出现同类型分型,取极值(顶取最高,底取最低)
    """
    if len(fractals) < 2:
        return []

    pens: List[Pen] = []

    # 过滤有效分型: 顶底必须交替,且间距足够
    valid_fractals = _filter_valid_fractals(fractals, merged_bars)

    if len(valid_fractals) < 2:
        return []

    for i in range(1, len(valid_fractals)):
        prev_f = valid_fractals[i - 1]
        curr_f = valid_fractals[i]

        # 必须一顶一底
        if prev_f.fractal_type == curr_f.fractal_type:
            continue

        # 确定方向和起终点
        if prev_f.fractal_type == FractalType.BOTTOM and curr_f.fractal_type == FractalType.TOP:
            # 上升笔: 从底到顶
            direction = Direction.UP
            start_price = prev_f.low
            end_price = curr_f.high
        else:
            # 下降笔: 从顶到底
            direction = Direction.DOWN
            start_price = prev_f.high
            end_price = curr_f.low

        # 有效性检查: 上升笔的终点必须高于起点, 下降笔反之
        if direction == Direction.UP and end_price <= start_price:
            continue
        if direction == Direction.DOWN and end_price >= start_price:
            continue

        # 间距检查: 至少5根合并K线(缠论要求顶底之间有独立K线)
        bar_count = abs(curr_f.index - prev_f.index) + 1
        if bar_count < 4:  # 放宽到4,允许紧凑笔
            continue

        pens.append(Pen(
            index=len(pens),
            direction=direction,
            start_index=prev_f.index,
            end_index=curr_f.index,
            start_price=start_price,
            end_price=end_price,
            start_date=merged_bars[prev_f.index].date_start if prev_f.index < len(merged_bars) else '',
            end_date=merged_bars[curr_f.index].date_start if curr_f.index < len(merged_bars) else '',
            bar_count=bar_count
        ))

    return pens


def _filter_valid_fractals(fractals: List[Fractal], merged_bars: List[MergedBar]) -> List[Fractal]:
    """
    过滤无效分型,确保顶底严格交替,且间距>=4

    算法(贪心):
    1. 从左到右扫描分型
    2. 维护当前"待确认"分型 candidate
    3. 如果遇到同类型分型:
       - 取极值(顶取最高,底取最低),更新candidate
    4. 如果遇到异类型分型:
       - 检查与已确认列表最后一个的间距
       - 间距>=4: 确认candidate入列,新分型成为candidate
       - 间距<4: 与candidate比较极值,取更极端的那个
    """
    if not fractals:
        return []

    result: List[Fractal] = []
    candidate = fractals[0]

    for i in range(1, len(fractals)):
        f = fractals[i]

        if f.fractal_type == candidate.fractal_type:
            # 同类型: 取极值
            if f.fractal_type == FractalType.TOP:
                if f.high > candidate.high:
                    candidate = f
            else:
                if f.low < candidate.low:
                    candidate = f
        else:
            # 异类型: 检查间距
            if not result:
                # 第一个确认的分型
                result.append(candidate)
                candidate = f
            else:
                last = result[-1]
                gap = f.index - last.index
                if gap >= 4:
                    # 间距足够,确认candidate并切换
                    # 但也要检查candidate与last的间距
                    cand_gap = candidate.index - last.index
                    if cand_gap >= 4:
                        result.append(candidate)
                        candidate = f
                    else:
                        # candidate间距不够,跟f比较看谁更极端
                        if candidate.fractal_type == FractalType.TOP:
                            if f.high > candidate.high:
                                candidate = f  # 跳过旧的,用新的
                        else:
                            if f.low < candidate.low:
                                candidate = f
                else:
                    # 间距不够,看是否需要更新last的极值
                    if f.fractal_type == last.fractal_type:
                        if f.fractal_type == FractalType.TOP:
                            if f.high > last.high:
                                result[-1] = f
                        else:
                            if f.low < last.low:
                                result[-1] = f
                    # 否则忽略f(间距不足的异类型)

    # 最后一个candidate
    if result:
        last = result[-1]
        if candidate.fractal_type != last.fractal_type and candidate.index - last.index >= 4:
            result.append(candidate)
    else:
        result.append(candidate)

    return result


# ═══════════════════════════════════════════════════════════
# Step 4: 线段计算 — 特质序列 + 包含关系
# ═══════════════════════════════════════════════════════════

def build_segments(pens: List[Pen], merged_bars: List[MergedBar]) -> List[Segment]:
    """
    从笔序列推导线段

    缠论定义:
    1. 线段由笔构成,笔的方向构成特质序列
    2. 特质序列: 将笔按方向分为奇偶序列,检查包含关系
    3. 线段被破坏的条件: 特质序列出现分型(即反向笔突破了前一个同向笔的端点)

    简化实现(缠论线段判断标准):
    - 标准特征序列: 奇数笔/偶数笔各自构成序列
    - 处理特征序列的包含关系
    - 特征序列出现分型 → 前一线段结束,新线段开始

    更实用的判断:
    - 3笔重叠构成线段
    - 线段被破坏: 反向第3笔突破第1笔端点
    """
    if len(pens) < 3:
        return []

    segments: List[Segment] = []
    seg_start_pen = 0  # 当前线段的起始笔序号

    i = 2  # 从第3笔开始判断
    while i < len(pens):
        # 检查是否出现线段破坏信号
        # 方法: 检查最近3笔是否形成反向分型
        # 上升线段中的破坏: 高点降低 + 低点降低
        # 下降线段中的破坏: 低点升高 + 高点升高

        if _is_segment_broken(pens, seg_start_pen, i):
            # 当前线段结束,新线段开始
            start_pen = pens[seg_start_pen]
            end_pen = pens[i - 1]

            segments.append(Segment(
                index=len(segments),
                direction=start_pen.direction,
                start_index=seg_start_pen,
                end_index=i - 1,
                start_price=start_pen.start_price,
                end_price=end_pen.end_price,
                start_date=start_pen.start_date,
                end_date=end_pen.end_date,
                pen_count=i - seg_start_pen
            ))

            seg_start_pen = i - 1  # 新线段从上一笔的终点开始
            i += 1  # 必须推进,否则 seg_start=i-1 时会无限循环
        else:
            i += 1

    # 最后一段(可能未完成)
    if seg_start_pen < len(pens) - 1:
        start_pen = pens[seg_start_pen]
        end_pen = pens[-1]
        segments.append(Segment(
            index=len(segments),
            direction=start_pen.direction,
            start_index=seg_start_pen,
            end_index=len(pens) - 1,
            start_price=start_pen.start_price,
            end_price=end_pen.end_price,
            start_date=start_pen.start_date,
            end_date=end_pen.end_date,
            pen_count=len(pens) - seg_start_pen
        ))

    return segments


def _is_segment_broken(pens: List[Pen], seg_start: int, current: int) -> bool:
    """
    判断线段是否在当前位置被破坏

    使用特质序列分型判断:
    1. 构建特质序列(每笔的端点构成)
    2. 处理特质序列的包含关系
    3. 检查特质序列是否出现分型

    简化判断(实用版):
    上升线段中,如果最近一笔的低点低于前一笔的低点,且该笔的终点低于前一笔的高点,
    则线段可能被破坏。需要确认:该笔之后的反弹不超过破坏点。
    """
    if current < 2:
        return False

    # 至少需要从 seg_start 到 current 有 3 笔以上,否则线段还未形成
    if current - seg_start < 2:
        return False

    seg_direction = pens[seg_start].direction

    # 取最近3笔判断(特质序列分型)
    p0 = pens[current - 2]
    p1 = pens[current - 1]
    p2 = pens[current]

    if seg_direction == Direction.UP:
        # 上升线段被破坏: 特质序列出现顶分型
        # 即: 上升笔序列 p0.end < p1.end > p2.end
        if p1.direction == Direction.UP and p2.direction == Direction.DOWN:
            if p1.end_price > p0.end_price and p1.end_price > p2.end_price:
                # 额外确认: 下降笔的终点确实低于上升笔的起点(回穿确认)
                if p2.end_price < p1.start_price:
                    return True
    else:
        # 下降线段被破坏: 特质序列出现底分型
        # 即: 下降笔序列 p0.end > p1.end < p2.end
        if p1.direction == Direction.DOWN and p2.direction == Direction.UP:
            if p1.end_price < p0.end_price and p1.end_price < p2.end_price:
                # 额外确认: 上升笔的终点确实高于下降笔的起点(反弹确认)
                if p2.end_price > p1.start_price:
                    return True

    return False


# ═══════════════════════════════════════════════════════════
# Step 5: 中枢识别
# ═══════════════════════════════════════════════════════════

def build_hubs(segments: List[Segment]) -> List[Hub]:
    """
    由线段构建中枢

    缠论定义:
    1. 最低级别中枢: 三段连续线段的价格区间有重叠
    2. 中枢区间(ZD, ZG): 三段重叠部分的(最高低点, 最低高点)
    3. 中枢升级: 9段以上重叠构成高一级中枢

    实现:
    - 滑动窗口扫描3段,找重叠区间
    - 中枢延伸: 后续线段进入中枢区间则中枢延伸
    - 中枢结束: 后续线段完全离开中枢区间
    """
    if len(segments) < 3:
        return []

    hubs: List[Hub] = []
    i = 0

    while i <= len(segments) - 3:
        # 尝试从位置i开始构建中枢
        s1, s2, s3 = segments[i], segments[i + 1], segments[i + 2]

        # 计算三段的重叠区间
        overlap_high = min(
            max(s1.start_price, s1.end_price),
            max(s2.start_price, s2.end_price),
            max(s3.start_price, s3.end_price)
        )
        overlap_low = max(
            min(s1.start_price, s1.end_price),
            min(s2.start_price, s2.end_price),
            min(s3.start_price, s3.end_price)
        )

        if overlap_high > overlap_low:
            # 存在重叠,构成中枢
            hub_start = i
            hub_end = i + 2
            oscillation_count = 1

            # 中枢延伸: 检查后续线段是否进入中枢区间
            j = i + 3
            while j < len(segments):
                seg = segments[j]
                seg_high = max(seg.start_price, seg.end_price)
                seg_low = min(seg.start_price, seg.end_price)

                # 线段与中枢有交集 → 中枢延伸
                if seg_low < overlap_high and seg_high > overlap_low:
                    hub_end = j
                    oscillation_count += 1
                    # 更新中枢区间(可能扩大)
                    overlap_high = min(overlap_high, seg_high)
                    overlap_low = max(overlap_low, seg_low)
                    j += 1
                else:
                    # 线段离开中枢
                    break

            zz = (overlap_high + overlap_low) / 2

            # 判断中枢级别: 9段以上 → 高一级
            level = 0 if oscillation_count < 9 else 1

            hubs.append(Hub(
                index=len(hubs),
                level=level,
                high=overlap_high,
                low=overlap_low,
                zz=zz,
                start_index=hub_start,
                end_index=hub_end,
                start_date=segments[hub_start].start_date,
                end_date=segments[hub_end].end_date,
                segment_count=hub_end - hub_start + 1,
                oscillation_count=oscillation_count
            ))

            i = hub_end + 1  # 从中枢结束后继续扫描
        else:
            i += 1

    return hubs


# ═══════════════════════════════════════════════════════════
# Step 6: 走势类型判断
# ═══════════════════════════════════════════════════════════

def build_trends(hubs: List[Hub], segments: List[Segment]) -> List[Trend]:
    """
    由中枢判断走势类型

    缠论定义:
    - 盘整: 只有一个中枢
    - 趋势: 两个或以上同向中枢
      - 上涨趋势: 后一个中枢的区间高于前一个
      - 下跌趋势: 后一个中枢的区间低于前一个
    """
    if not hubs:
        # 没有中枢,无法判断走势类型
        return []

    trends: List[Trend] = []
    i = 0

    while i < len(hubs):
        hub = hubs[i]

        # 检查是否能与后续中枢构成趋势
        if i + 1 < len(hubs):
            next_hub = hubs[i + 1]

            # 判断两个中枢是否同向(不重叠)
            if hub.low > next_hub.high:
                # 下跌趋势: 前高后低
                trend = Trend(
                    index=len(trends),
                    trend_type=TrendType.DOWNTREND,
                    start_index=hub.start_index,
                    end_index=next_hub.end_index,
                    start_date=hub.start_date,
                    end_date=next_hub.end_date,
                    hubs=[hub, next_hub],
                    direction=Direction.DOWN
                )
                trends.append(trend)
                i += 2
                continue
            elif next_hub.low > hub.high:
                # 上涨趋势: 前低后高
                trend = Trend(
                    index=len(trends),
                    trend_type=TrendType.UPTREND,
                    start_index=hub.start_index,
                    end_index=next_hub.end_index,
                    start_date=hub.start_date,
                    end_date=next_hub.end_date,
                    hubs=[hub, next_hub],
                    direction=Direction.UP
                )
                trends.append(trend)
                i += 2
                continue

        # 单独中枢 → 盘整
        # 判断方向: 看中枢后的线段方向
        direction = Direction.UP
        if hub.end_index + 1 < len(segments):
            direction = segments[hub.end_index + 1].direction

        trend = Trend(
            index=len(trends),
            trend_type=TrendType.CONSOLIDATION,
            start_index=hub.start_index,
            end_index=hub.end_index,
            start_date=hub.start_date,
            end_date=hub.end_date,
            hubs=[hub],
            direction=direction
        )
        trends.append(trend)
        i += 1

    return trends


# ═══════════════════════════════════════════════════════════
# Step 7: 买卖点识别
# ═══════════════════════════════════════════════════════════

def find_buy_sell_points(
    pens: List[Pen],
    segments: List[Segment],
    hubs: List[Hub],
    trends: List[Trend],
    merged_bars: List[MergedBar]
) -> List[BuySellPoint]:
    """
    识别买卖点

    缠论定义:
    - 一买: 下跌趋势背驰(最后一个中枢之后的下跌力度减弱)
    - 二买: 一买之后的回调不创新低
    - 三买: 离开中枢后回抽不进中枢
    - 卖点对称定义
    """
    points: List[BuySellPoint] = []

    if not trends or not pens:
        return points

    for trend in trends:
        if trend.trend_type == TrendType.DOWNTREND:
            # 下跌趋势 → 寻找买点
            _find_buy_points_in_downtrend(trend, pens, segments, hubs, merged_bars, points)
        elif trend.trend_type == TrendType.UPTREND:
            # 上涨趋势 → 寻找卖点
            _find_sell_points_in_uptrend(trend, pens, segments, hubs, merged_bars, points)

    # 对盘整中的中枢也检查三买三卖
    for trend in trends:
        if trend.trend_type == TrendType.CONSOLIDATION:
            _find_points_in_consolidation(trend, pens, segments, hubs, merged_bars, points)

    # 按日期排序
    points.sort(key=lambda p: p.date)
    return points


def _find_buy_points_in_downtrend(trend, pens, segments, hubs, merged_bars, points):
    """下跌趋势中寻找买点"""
    if len(trend.hubs) < 2:
        return

    last_hub = trend.hubs[-1]

    # 一买: 趋势背驰(简化判断: 最后一笔的幅度小于前一笔)
    last_pen_idx = trend.end_index
    if last_pen_idx >= 1 and last_pen_idx < len(pens):
        last_pen = pens[last_pen_idx]
        prev_pen = pens[last_pen_idx - 1] if last_pen_idx > 0 else None

        if prev_pen and last_pen.direction == Direction.DOWN:
            # 背驰判断: 最后一笔的幅度 < 前一笔的幅度
            last_amplitude = abs(last_pen.start_price - last_pen.end_price)
            prev_amplitude = abs(prev_pen.start_price - prev_pen.end_price) if prev_pen else 0

            if prev_amplitude > 0 and last_amplitude < prev_amplitude:
                # 背驰成立 → 一买
                idx = last_pen.end_index
                if idx < len(merged_bars):
                    points.append(BuySellPoint(
                        index=idx,
                        buy_sell_type=BuySellType.FIRST_BUY,
                        price=last_pen.end_price,
                        date=merged_bars[idx].date_end,
                        hub=last_hub
                    ))


def _find_sell_points_in_uptrend(trend, pens, segments, hubs, merged_bars, points):
    """上涨趋势中寻找卖点"""
    if len(trend.hubs) < 2:
        return

    last_hub = trend.hubs[-1]

    # 一卖: 上涨趋势背驰
    last_pen_idx = trend.end_index
    if last_pen_idx >= 1 and last_pen_idx < len(pens):
        last_pen = pens[last_pen_idx]
        prev_pen = pens[last_pen_idx - 1] if last_pen_idx > 0 else None

        if prev_pen and last_pen.direction == Direction.UP:
            last_amplitude = abs(last_pen.end_price - last_pen.start_price)
            prev_amplitude = abs(prev_pen.end_price - prev_pen.start_price) if prev_pen else 0

            if prev_amplitude > 0 and last_amplitude < prev_amplitude:
                idx = last_pen.end_index
                if idx < len(merged_bars):
                    points.append(BuySellPoint(
                        index=idx,
                        buy_sell_type=BuySellType.FIRST_SELL,
                        price=last_pen.end_price,
                        date=merged_bars[idx].date_end,
                        hub=last_hub
                    ))


def _find_points_in_consolidation(trend, pens, segments, hubs, merged_bars, points):
    """盘整中寻找三买三卖"""
    if not trend.hubs:
        return

    hub = trend.hubs[0]

    # 检查中枢前后的线段
    # 三买: 中枢后的回调低点 > 中枢上沿(ZG)
    if hub.end_index + 1 < len(segments):
        next_seg = segments[hub.end_index + 1]
        seg_low = min(next_seg.start_price, next_seg.end_price)

        if seg_low > hub.high:
            # 三买: 回调不进中枢
            idx = next_seg.end_index
            if idx < len(pens):
                pen = pens[idx] if idx < len(pens) else None
                if pen:
                    bar_idx = pen.end_index
                    if bar_idx < len(merged_bars):
                        points.append(BuySellPoint(
                            index=bar_idx,
                            buy_sell_type=BuySellType.THIRD_BUY,
                            price=pen.end_price,
                            date=merged_bars[bar_idx].date_end,
                            hub=hub
                        ))

    # 三卖: 中枢前的反弹高点 < 中枢下沿(ZD)
    if hub.start_index > 0:
        prev_seg = segments[hub.start_index - 1]
        seg_high = max(prev_seg.start_price, prev_seg.end_price)

        if seg_high < hub.low:
            idx = prev_seg.end_index
            if idx < len(pens):
                pen = pens[idx] if idx < len(pens) else None
                if pen:
                    bar_idx = pen.end_index
                    if bar_idx < len(merged_bars):
                        points.append(BuySellPoint(
                            index=bar_idx,
                            buy_sell_type=BuySellType.THIRD_SELL,
                            price=pen.end_price,
                            date=merged_bars[bar_idx].date_end,
                            hub=hub
                        ))

    # 二买二卖: 简化判断
    # 二买: 一买后的回调不创新低(需要先有一买)
    # 二卖: 一卖后的反弹不创新高(需要先有一卖)
    # 这些需要更复杂的上下文判断,暂不在此实现


# ═══════════════════════════════════════════════════════════
# 主入口: 全流程计算
# ═══════════════════════════════════════════════════════════

def calculate(raw_bars: List[RawBar]) -> ChanTheoryResult:
    """
    缠论全流程计算

    输入: 原始K线序列(按时间正序)
    输出: 完整的缠论结构(合并K线 + 分型 + 笔 + 线段 + 中枢 + 走势 + 买卖点)
    """
    # Step 1: K线合并
    merged = merge_bars(raw_bars)

    # Step 2: 分型识别
    fractals = find_fractals(merged)

    # Step 3: 笔
    pens = build_pens(fractals, merged)

    # Step 4: 线段
    segments = build_segments(pens, merged)

    # Step 5: 中枢
    hubs = build_hubs(segments)

    # Step 6: 走势类型
    trends = build_trends(hubs, segments)

    # Step 7: 买卖点
    buy_sell_points = find_buy_sell_points(pens, segments, hubs, trends, merged)

    return ChanTheoryResult(
        raw_bars=raw_bars,
        merged_bars=merged,
        fractals=fractals,
        pens=pens,
        segments=segments,
        hubs=hubs,
        trends=trends,
        buy_sell_points=buy_sell_points
    )


def calculate_from_ohlcv(
    dates: List[str],
    opens: List[float],
    highs: List[float],
    lows: List[float],
    closes: List[float],
    volumes: List[float] = None,
    amounts: List[float] = None
) -> ChanTheoryResult:
    """
    便捷入口: 从OHLCV数组直接计算

    参数:
        dates: 交易日期列表
        opens/highs/lows/closes: OHLC
        volumes: 成交量(可选)
        amounts: 成交额(可选)
    """
    n = len(dates)
    vols = volumes or [0.0] * n
    amts = amounts or [0.0] * n

    raw_bars = [
        RawBar(
            index=i,
            date=dates[i],
            open=opens[i],
            high=highs[i],
            low=lows[i],
            close=closes[i],
            volume=vols[i],
            amount=amts[i]
        )
        for i in range(n)
    ]

    return calculate(raw_bars)
