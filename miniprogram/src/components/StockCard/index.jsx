import { View, Text } from '@tarojs/components';
import './StockCard.scss';
import {
  formatPrice,
  formatPercent,
  priceColor,
  actionTagText,
  actionTagClass
} from '../../utils/format';

/**
 * 股票推荐卡片组件
 * @param {object} item - StockRecommendation 数据
 * @param {object} quote - 实时行情 {price, change, changePct}
 * @param {function} onClick - 点击回调
 */
export default function StockCard({ item, quote, onClick }) {
  // 优先用实时行情，没有则用推荐里的价格（可能为 null）
  const displayPrice = quote?.price ?? item.closePrice;
  const displayChangePct = quote?.changePct ?? item.changePercent;
  const colorClass = priceColor(displayChangePct);

  return (
    <View className='stock-card' onClick={onClick}>
      <View className='card-left'>
        <View className={`rank-badge ${item.rankNum <= 3 ? 'rank-top' : ''}`}>
          {item.rankNum || '-'}
        </View>
      </View>
      <View className='card-center'>
        <View className='stock-name'>{item.stockName}</View>
        <View className='stock-code'>{item.stockCode} · {item.industry || '--'}</View>
      </View>
      <View className='card-right'>
        <View className='price-row'>
          <Text className='price-tag'>现价</Text>
          <Text className={`price ${colorClass}`}>
            {displayPrice != null ? formatPrice(displayPrice) : '--'}
          </Text>
        </View>
        <View className={`change ${colorClass}`}>
          {displayChangePct != null ? formatPercent(displayChangePct) : '--'}
        </View>
      </View>
      <View className='card-tag'>
        <View className={`tag ${actionTagClass(item.actionTag)}`}>
          {actionTagText(item.actionTag)}
        </View>
        <View className='score'>{item.finalScore ? (item.finalScore * 100).toFixed(0) : '--'}</View>
      </View>
    </View>
  );
}
