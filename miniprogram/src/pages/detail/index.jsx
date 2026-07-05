import { useState } from 'react';
import { View, Text } from '@tarojs/components';
import ScoreBar from '../../components/ScoreBar';
import {
  formatPrice,
  formatPercent,
  formatMarketCap,
  formatPosition,
  priceColor,
  actionTagText,
  actionTagClass,
  regimeText,
  formatDate,
  parseBuyReason,
  levelText,
  levelClass
} from '../../utils/format';
import './index.scss';

export default function DetailPage(props) {
  // 从路由参数获取数据
  const router = props.router || (typeof getCurrentPages === 'function' && getCurrentPages().length > 0 ? getCurrentPages()[getCurrentPages().length - 1].router : null);

  let item = {};
  let quote = {};
  try {
    const pages = getCurrentPages();
    const currentPage = pages[pages.length - 1];
    const raw = currentPage?.options?.data;
    const quoteRaw = currentPage?.options?.quote;
    if (raw) {
      item = JSON.parse(decodeURIComponent(raw));
    }
    if (quoteRaw) {
      quote = JSON.parse(decodeURIComponent(quoteRaw));
    }
  } catch (e) {
    console.error('解析详情数据失败', e);
  }

  if (!item || !item.stockCode) {
    return (
      <View className='detail-page'>
        <View className='empty-state'>
          <Text className='empty-text'>数据加载失败</Text>
        </View>
      </View>
    );
  }

  // 优先用实时行情（列表页带过来的），没有再用推荐里的收盘价/涨跌幅
  const displayPrice = quote?.price ?? item.closePrice;
  const displayChangePercent = quote?.changePct ?? item.changePercent;
  const colorClass = priceColor(displayChangePercent);
  const buyReason = parseBuyReason(item.buyReason);

  return (
    <View className='detail-page'>
      {/* 头部信息 */}
      <View className='detail-header card'>
        <View className='header-top'>
          <View className='header-left'>
            <Text className='stock-name'>{item.stockName}</Text>
            <Text className='stock-code'>{item.stockCode} · {item.industry || '--'}</Text>
          </View>
          <View className='header-right'>
            <Text className={`price ${colorClass}`}>{formatPrice(displayPrice)}</Text>
            <Text className={`change ${colorClass}`}>
              {formatPercent(displayChangePercent)}
            </Text>
          </View>
        </View>
        <View className='header-tags'>
          <View className={`tag ${actionTagClass(item.actionTag)}`}>
            {actionTagText(item.actionTag)}
          </View>
          <View className='tag tag-score'>
            最终得分 {item.finalScore ? (item.finalScore * 100).toFixed(0) : '--'}
          </View>
          <View className='tag tag-rank'>
            排名 #{item.rankNum || '--'}
          </View>
          {item.regime && (
            <View className='tag tag-regime'>
              {regimeText(item.regime)}
            </View>
          )}
        </View>
      </View>

      {/* 交易计划 */}
      <View className='section card'>
        <View className='section-title'>交易计划</View>
        <View className='plan-grid'>
          <View className='plan-item'>
            <Text className='plan-label'>建议买入价</Text>
            <Text className='plan-value'>{formatPrice(item.suggestedBuyPrice)}</Text>
          </View>
          <View className='plan-item'>
            <Text className='plan-label'>止损价</Text>
            <Text className='plan-value text-red'>{formatPrice(item.suggestedStopLoss)}</Text>
          </View>
          <View className='plan-item'>
            <Text className='plan-label'>止盈价</Text>
            <Text className='plan-value text-green'>{formatPrice(item.suggestedTakeProfit)}</Text>
          </View>
          <View className='plan-item'>
            <Text className='plan-label'>目标价</Text>
            <Text className='plan-value text-green'>{formatPrice(item.suggestedTargetPrice)}</Text>
          </View>
          <View className='plan-item'>
            <Text className='plan-label'>建议仓位</Text>
            <Text className='plan-value'>{formatPosition(item.suggestedPositionPct)}</Text>
          </View>
          <View className='plan-item'>
            <Text className='plan-label'>风险评分</Text>
            <Text className='plan-value'>{item.riskScore != null ? item.riskScore + '/15' : '--'}</Text>
          </View>
        </View>
      </View>

      {/* 多维度评分 */}
      <View className='section card'>
        <View className='section-title'>多维度评分</View>
        <ScoreBar label='技术面' score={item.technicalScore} color='red' />
        <ScoreBar label='资金面' score={item.capitalScore} color='orange' />
        <ScoreBar label='基本面' score={item.fundamentalScore} color='green' />
        <ScoreBar label='事件面' score={item.eventScore} color='purple' />
      </View>

      {/* 因子得分 */}
      <View className='section card'>
        <View className='section-title'>因子得分</View>
        <View className='factor-grid'>
          <View className='factor-item'>
            <Text className='factor-label'>因子综合</Text>
            <Text className='factor-value'>
              {item.factorScore ? (item.factorScore * 100).toFixed(0) : '--'}
            </Text>
          </View>
          <View className='factor-item'>
            <Text className='factor-label'>分析得分</Text>
            <Text className='factor-value'>
              {item.analysisScore != null ? item.analysisScore : '--'}
            </Text>
          </View>
          <View className='factor-item'>
            <Text className='factor-label'>因子权重</Text>
            <Text className='factor-value'>
              {item.factorWeight ? (item.factorWeight * 100).toFixed(0) + '%' : '--'}
            </Text>
          </View>
          <View className='factor-item'>
            <Text className='factor-label'>分析权重</Text>
            <Text className='factor-value'>
              {item.analysisWeight ? (item.analysisWeight * 100).toFixed(0) + '%' : '--'}
            </Text>
          </View>
        </View>
      </View>

      {/* 基本面信息 */}
      <View className='section card'>
        <View className='section-title'>基本面信息</View>
        <View className='info-row'>
          <Text className='info-label'>总市值</Text>
          <Text className='info-value'>{formatMarketCap(item.marketCap)}</Text>
        </View>
        <View className='info-row'>
          <Text className='info-label'>行业</Text>
          <Text className='info-value'>{item.industry || '--'}</Text>
        </View>
        <View className='info-row'>
          <Text className='info-label'>行业动量</Text>
          <Text className='info-value'>
            {item.industryMomentum != null ? item.industryMomentum.toFixed(2) : '--'}
          </Text>
        </View>
        <View className='info-row'>
          <Text className='info-label'>行业Regime</Text>
          <Text className='info-value'>{regimeText(item.industryRegime)}</Text>
        </View>
        <View className='info-row'>
          <Text className='info-label'>流动性评分</Text>
          <Text className='info-value'>
            {item.liquidityScore != null ? item.liquidityScore + '/10' : '--'}
          </Text>
        </View>
      </View>

      {/* 买入理由 */}
      {item.buyReason && (
        <View className='section card'>
          <View className='section-title'>买入理由</View>

          {/* 一句话摘要（开头【...】部分） */}
          {buyReason.summary && (
            <View className='reason-summary'>
              {buyReason.summary}
            </View>
          )}

          {/* 四维度评分要点 */}
          {buyReason.dimensions.length > 0 && (
            <View className='reason-dimensions'>
              {buyReason.dimensions.map((d, i) => (
                <View key={i} className='reason-dim-row'>
                  <View className='reason-dim-head'>
                    <Text className='reason-dim-name'>{d.name}</Text>
                    {d.level && (
                      <Text className={`reason-dim-level ${levelClass(d.level)}`}>
                        {levelText(d.level)}
                      </Text>
                    )}
                  </View>
                  {d.content && (
                    <Text className='reason-dim-content'>{d.content}</Text>
                  )}
                </View>
              ))}
            </View>
          )}

          {/* 风险提示 */}
          {buyReason.risk && (
            <View className='reason-risk'>
              <Text className='reason-risk-label'>风险提示</Text>
              <Text className='reason-risk-text'>{buyReason.risk}</Text>
            </View>
          )}
        </View>
      )}

      {/* 收益追踪 */}
      {(item.nextDayReturn != null || item.nextWeekReturn != null || item.nextMonthReturn != null) && (
        <View className='section card'>
          <View className='section-title'>收益追踪</View>
          <View className='track-grid'>
            <View className='track-item'>
              <Text className='track-label'>次日收益</Text>
              <Text className={`track-value ${priceColor(item.nextDayReturn)}`}>
                {formatPercent(item.nextDayReturn)}
              </Text>
            </View>
            <View className='track-item'>
              <Text className='track-label'>一周收益</Text>
              <Text className={`track-value ${priceColor(item.nextWeekReturn)}`}>
                {formatPercent(item.nextWeekReturn)}
              </Text>
            </View>
            <View className='track-item'>
              <Text className='track-label'>一月收益</Text>
              <Text className={`track-value ${priceColor(item.nextMonthReturn)}`}>
                {formatPercent(item.nextMonthReturn)}
              </Text>
            </View>
          </View>
        </View>
      )}

      {/* 推荐元信息 */}
      <View className='section meta-section'>
        <Text className='meta-text'>
          推荐日期: {formatDate(item.recommendDate)}
        </Text>
        {item.trackingUpdatedAt && (
          <Text className='meta-text'>
            追踪更新: {formatDate(item.trackingUpdatedAt)}
          </Text>
        )}
      </View>
    </View>
  );
}
