"""
缠论核心数据结构定义
严格按缠中说禅《教你炒股票》系列定义
"""

from dataclasses import dataclass, field
from enum import IntEnum
from typing import List, Optional


class Direction(IntEnum):
    """方向"""
    UP = 1      # 上升
    DOWN = -1   # 下降


class FractalType(IntEnum):
    """分型类型"""
    TOP = 1     # 顶分型
    BOTTOM = -1 # 底分型


class BuySellType(IntEnum):
    """买卖点类型"""
    FIRST_BUY = 1       # 一买：趋势背驰后的底部
    SECOND_BUY = 2      # 二买：回调不创新低
    THIRD_BUY = 3       # 三买：离开中枢后回抽不进中枢
    FIRST_SELL = -1     # 一卖：趋势背驰后的顶部
    SECOND_SELL = -2    # 二卖：反弹不创新高
    THIRD_SELL = -3     # 三卖：离开中枢后反弹不进中枢


@dataclass
class RawBar:
    """原始K线"""
    index: int           # 在原始序列中的位置(0-based)
    date: str            # 交易日期
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0
    amount: float = 0.0


@dataclass
class MergedBar:
    """合并后的K线（处理完包含关系后的标准K线）"""
    index: int           # 合并后在序列中的位置(0-based)
    date_start: str      # 起始日期
    date_end: str        # 结束日期
    open: float
    high: float
    low: float
    close: float
    direction: Direction # 合并方向（由包含关系处理确定）
    raw_indices: List[int] = field(default_factory=list)  # 包含的原始K线索引


@dataclass
class Fractal:
    """分型"""
    index: int           # 分型中间K线在合并序列中的位置
    fractal_type: FractalType
    high: float          # 分型的高点
    low: float           # 分型的低点
    merged_bar: MergedBar = None  # 中间那根合并K线


@dataclass
class Pen:
    """笔：由相邻的顶底分型连接而成"""
    index: int           # 笔的序号(0-based)
    direction: Direction # 笔的方向
    start_index: int     # 起始分型的合并序列位置
    end_index: int       # 结束分型的合并序列位置
    start_price: float   # 起始价格
    end_price: float     # 结束价格
    start_date: str      # 起始日期
    end_date: str        # 结束日期
    bar_count: int = 0   # 笔内合并K线数量（含端点，>=5）


@dataclass
class Segment:
    """线段：由笔的特质序列推导而来"""
    index: int           # 线段序号(0-based)
    direction: Direction # 线段方向
    start_index: int     # 起始笔的序号
    end_index: int       # 结束笔的序号
    start_price: float   # 起始价格
    end_price: float     # 结束价格
    start_date: str
    end_date: str
    pen_count: int = 0   # 线段内包含的笔数


@dataclass
class Hub:
    """中枢：由至少三段连续重叠构成"""
    index: int           # 中枢序号(0-based)
    level: int           # 中枢级别（0=最低级别由线段构成,1=高一级...）
    high: float          # 中枢上沿(ZG)
    low: float           # 中枢下沿(ZD)
    zz: float            # 中枢中轴 = (high + low) / 2
    start_index: int     # 起始线段序号
    end_index: int       # 结束线段序号
    start_date: str
    end_date: str
    segment_count: int = 0   # 进入中枢的线段数
    oscillation_count: int = 0  # 震荡次数


class TrendType(IntEnum):
    """走势类型"""
    CONSOLIDATION = 0   # 盘整：只有一个中枢
    UPTREND = 1         # 上涨趋势：两个以上同向上升中枢
    DOWNTREND = -1      # 下跌趋势：两个以上同向下降中枢


@dataclass
class Trend:
    """走势类型"""
    index: int
    trend_type: TrendType
    start_index: int    # 起始线段序号
    end_index: int      # 结束线段序号(-1表示未结束)
    start_date: str
    end_date: str
    hubs: List[Hub] = field(default_factory=list)
    direction: Direction = Direction.UP


@dataclass
class BuySellPoint:
    """买卖点"""
    index: int          # 在合并序列中的位置
    buy_sell_type: BuySellType
    price: float
    date: str
    hub: Optional[Hub] = None  # 关联的中枢（如有）


@dataclass
class ChanTheoryResult:
    """缠论计算完整结果"""
    raw_bars: List[RawBar]
    merged_bars: List[MergedBar]
    fractals: List[Fractal]
    pens: List[Pen]
    segments: List[Segment]
    hubs: List[Hub]
    trends: List[Trend]
    buy_sell_points: List[BuySellPoint]
