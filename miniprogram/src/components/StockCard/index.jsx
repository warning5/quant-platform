import { View, Text } from '@tarojs/components';
import './StockCard.scss';
import {
  actionTagText,
  actionTagClass
} from '../../utils/format';

/**
 * 股票推荐卡片组件 —— 对齐设计稿截图布局：
 *
 *   [①]  山金国际                现价 26.61   [买入]
 *        000975.SZ · 贵金属       +0.99%       32
 *
 * 布局要点：
 *   - 排名：圆形徽章（前3名粉红底，其余灰底）
 *   - 中间：名称(粗体) + 代码·驱动类型(灰色) + 实时价行(可选)
 *   - 右侧两行：
 *     上行："现价 {price}"(大字)  ……  [操作标签pill]
 *     下行："+x.xx%"(红涨绿跌)   ……  评分(纯数字)
 *   - 有实时行情(liveQuote)时展示现价+涨跌；无则隐藏该行
 *
 * @param {object} item - StockRecommendation 数据
 * @param {object} [liveQuote] - 个股实时行情 {price,change,changePct}
 * @param {function} onClick - 点击回调
 */
export default function StockCard({ item, liveQuote, onClick }) {
  // 驱动类型优先用 item.driver，fallback 到行业
  const subText = item.driver || item.industry || '--';

  // 实时价优先，fallback 到批次收盘数据
  const price = liveQuote != null ? liveQuote.price : (item.closePrice ?? null);
  const pct = liveQuote != null ? liveQuote.changePct : (item.changePercent ?? null);
  const pcolor = pct != null
    ? (pct > 0 ? '#E53935' : pct < 0 ? '#2E9E5B' : '#9AA1AC')
    : '#9AA1AC';

  // 排名徽章样式：前3名粉红，其余灰
  const rank = item.rankNum || '-';
  const isTop = Number(rank) <= 3;

  return (
    <View className='rec-item' onClick={onClick}>
      {/* 排名圆形徽章 */}
      <View className={`rnum-badge ${isTop ? 'top' : ''}`}>
        <Text className={`rnum-txt ${isTop ? 'top' : ''}`}>{rank}</Text>
      </View>

      {/* 中间：名称 + 代码·驱动 */}
      <View className='rcenter'>
        <Text className='rnm'>{item.stockName}</Text>
        <Text className='rcode'>{item.stockCode} · {subText}</Text>
      </View>

      {/* 右侧：现价+标签上行 / 涨跌+评分下行 */}
      <View className='rright'>
        {/* 上行：现价 + 操作标签 */}
        <View className='rrow-top'>
          {price != null ? (
            <View className='rprice-block'>
              <Text className='rprice-lbl'>现价</Text>
              <Text className='rprice-val' style={{ color: pcolor }}>
                {Number(price).toFixed(2)}
              </Text>
            </View>
          ) : (
            <View />
          )}
          <View className={`rtag ${actionTagClass(item.actionTag)}`}>
            {actionTagText(item.actionTag)}
          </View>
        </View>

        {/* 下行：涨跌幅 + 评分 */}
        <View className='rrow-btm'>
          {pct != null ? (
            <Text className='rpct' style={{ color: pcolor }}>
              {pct > 0 ? '+' : ''}{Number(pct).toFixed(2)}%
            </Text>
          ) : (
            <View />
          )}
          <Text className='rscore-num'>
            {item.finalScore ? Math.round(item.finalScore * 100) : '--'}
          </Text>
        </View>
      </View>
    </View>
  );
}
