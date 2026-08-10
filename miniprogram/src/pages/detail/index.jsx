import { useState, useEffect, useCallback, useRef } from 'react';
import { View, Text } from '@tarojs/components';
import Taro, { useDidShow, useDidHide } from '@tarojs/taro';
import { stockQuoteApi } from '../../api';
import {
  formatPrice,
  formatPercent,
  formatMarketCap,
  formatPosition,
  formatScore,
  priceColor,
  actionTagText,
  actionTagClass,
  regimeText,
  formatDate,
  confidenceText,
  parseBuyReason,
  weightModeText,
} from '../../utils/format';
import './index.scss';

/* ── 工具函数 ── */

/** 提取纯6位代码 */
function pureCode(code) {
  if (!code) return '';
  return code.replace(/\.\w+$/, '');
}

/** 交易时段判断 */
function isTradingTime() {
  const now = new Date();
  const day = now.getDay();
  if (day === 0 || day === 6) return false;
  const t = now.getHours() * 60 + now.getMinutes();
  return (t >= 570 && t <= 690) || (t >= 780 && t <= 900);
}

/** 日期格式 MM-DD 周X（如 08-07 周四） */
function fmtDateShort(dateStr) {
  if (!dateStr) return '--';
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return dateStr;
  const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${m}-${day} 周${weekdays[d.getDay()]}`;
}

/** 驱动类型中文 */
function driverText(val) {
  if (!val) return '--';
  if (val === 'fw' || val === 'FACTOR') return '因子主导';
  if (val === 'aw' || val === 'ANALYSIS') return '分析主导';
  return val;
}

/** 置信度等级 → 百分比数值（用于推荐置信度卡片的大字+进度条） */
function confidenceScore(level) {
  const map = { HIGH: 91, NORMAL: 72, LOW: 45, SUSPENDED: 20, UNTRAINED: 0 };
  return map[level] ?? null;
}

/* ═══════════════════════════════════════
   个股推荐详情页 —— 对齐原型 6 张截图
   ═══════════════════════════════════════ */

export default function DetailPage() {
  // ── 解析路由参数 ──
  let item = {};
  let initialQuote = {};
  try {
    const pages = getCurrentPages();
    const cur = pages[pages.length - 1];
    const raw = cur?.options?.data;
    const qRaw = cur?.options?.quote;
    if (raw) item = JSON.parse(decodeURIComponent(raw));
    if (qRaw) initialQuote = JSON.parse(decodeURIComponent(qRaw));
  } catch (e) { /* ignore */ }

  // ── 实时行情 ──
  const [liveQuote, setLiveQuote] = useState(initialQuote || {});
  const pollingRef = useRef(null);
  const codeRef = useRef(pureCode(item.stockCode));

  const fetchQuote = useCallback(async () => {
    const code = codeRef.current;
    if (!code) return;
    try {
      const res = await stockQuoteApi.getQuotes(code);
      const map = res?.data ? res.data : (res || {});
      if (map[code]) setLiveQuote(map[code]);
    } catch (_) { /* silent */ }
  }, []);

  const stopPolling = useCallback(() => {
    if (pollingRef.current) { clearInterval(pollingRef.current); pollingRef.current = null; }
  }, []);

  const startPolling = useCallback(() => {
    stopPolling();
    fetchQuote();
    if (isTradingTime()) pollingRef.current = setInterval(fetchQuote, 3000);
  }, [fetchQuote, stopPolling]);

  useDidShow(() => startPolling());
  useDidHide(() => stopPolling());
  useEffect(() => () => stopPolling(), [stopPolling]);

  // ── 空状态 ──
  if (!item?.stockCode) {
    return (
      <View className='dp-page'>
        <View className='dp-empty'><Text>数据加载失败</Text></View>
      </View>
    );
  }

  // ── 衍生数据 ──
  const displayPrice = liveQuote?.price ?? item.closePrice;
  const displayPct = liveQuote?.changePct ?? item.changePercent;
  const pctColor = priceColor(displayPct);
  const buyReason = parseBuyReason(item.buyReason);

  // 综合得分（0~100 整数）
  const finalScore = item.finalScore != null
    ? Math.round(Number(item.finalScore) * 100)
    : null;

  /* 置信度：有推荐数据但后端未返回 confidenceLevel 时默认 NORMAL */
  const effectiveConfLevel = item.confidenceLevel || 'NORMAL';

  /* ══════════ 渲染 ══════════ */
  return (
    <View className='dp-page'>

      {/* ═══════════════════════════════════════
          卡片 1 — 头部信息（严格对齐原型图1）
          原型结构：
          行1: 名称+代码行业 | 右上角[实时价格 + 涨跌幅]
          行2: [已入选绿] [推荐口径蓝] [质量批次蓝]
          行3: [个股推荐蓝] 日期文字 [驱动黄] 基准 策略名
          行4: 综合评分 红色大字  推荐排名 #N  [操作标签 买入/持有]
          行5(foot): 日期pill  策略pill  权重pill  regime pill  质量pill
          实时价格/涨跌幅在行1右上角（原型无，用户要求加，交易时段每3秒刷新）
          ═══════════════════════════════════════ */}
      <View className='dp-card dp-header'>
        {/* 行1：名称 + 右上角实时价格/涨跌幅（交易时段每3秒刷新） */}
        <View className='dh-row1'>
          <View className='dh-name-block'>
            <Text className='dh-name'>{item.stockName || '--'}</Text>
            <Text className='dh-sub'>{item.stockCode} · {item.industry || '--'}</Text>
          </View>
          <View className='dh-price-block'>
            <Text className={`dh-price ${pctColor}`}>{formatPrice(displayPrice)}</Text>
            <Text className={`dh-pct ${pctColor}`}>
              {formatPercent(displayPct)}
            </Text>
          </View>
        </View>

        {/* 行2：状态 pills */}
        <View className='dh-row2'>
          <Text className='dh-pill dh-pill-green'>已入选</Text>
          <Text className='dh-pill dh-pill-blue'>推荐口径</Text>
        </View>

        {/* 行3：元数据行 —— 原型是「个股推荐(蓝) 日期 驱动(黄) 基准 策略名」 */}
        <View className='dh-meta'>
          <Text className='dh-tag-blue'>个股推荐</Text>
          <Text className='dh-meta-text'>日期</Text>
          {item.driver ? (
            <Text className='dh-tag-yellow'>{driverText(item.driver)}</Text>
          ) : (
            <Text className='dh-tag-yellow'>分析主导</Text>
          )}
          <Text className='dh-meta-text'>基准 {item.strategyName || '--'}</Text>
        </View>

        {/* 行4：综合评分 + 排名 + 操作标签（买入/持有，原价格处） */}
        <View className='dh-score-row'>
          <View className='dh-score-left'>
            <Text className='dh-sl'>综合评分</Text>
            <Text className='dh-sv text-red'>{finalScore ?? '--'}</Text>
          </View>
          <View className='dh-score-center'>
            <Text className='dh-sl'>推荐排名</Text>
            <Text className='dh-sv-rank'>#{item.rankNum ?? '--'}</Text>
          </View>
          <View className='dh-score-right'>
            <View className={`dh-action ${actionTagClass(item.actionTag)}`}>
              {actionTagText(item.actionTag)}
            </View>
          </View>
        </View>

        {/* 行5：底部 meta pills */}
        <View className='dh-foot-pills'>
          <Text className='dh-fpill'>{fmtDateShort(item.recommendDate)}</Text>
          <Text className='dh-fpill'>{item.strategyName || '--'}</Text>
          {item.weightMode && <Text className='dh-fpill'>{weightModeText(item.weightMode)}</Text>}
          {item.regime ? (
            <Text className='dh-fpill'>{regimeText(item.regime)}</Text>
          ) : (
            <Text className='dh-fpill'>震荡市</Text>
          )}
        </View>
      </View>

      {/* ═══════════════════════════════════════
          卡片 2 — 推荐置信度（原型图2 上）
          注意：这里用 confidenceScore（批次可靠性），不是 finalScore（个股得分）
          ═══════════════════════════════════════ */}
      {(() => {
        const confVal = confidenceScore(effectiveConfLevel);
        return (
        <View className='dp-card'>
          <View className='dc-title'>推荐置信度</View>
          <View className='conf-body'>
            <View className='conf-left'>
              <Text className='conf-big text-blue'>{confVal ?? '--'}</Text>
              <Text className='conf-level'>{confidenceText(effectiveConfLevel)}</Text>
            </View>
            <View className='conf-right'>
              <View className='conf-bar-wrap'>
                <View className='conf-bar' style={{ width: `${confVal || 0}%` }} />
              </View>
              <Text className='conf-desc'>
                综合因子 IC 稳定性、基本面质量与资金面强度，由 StrategyConfidenceService 评级（推荐口径）。
              </Text>
            </View>
          </View>
        </View>
        );
      })()}

      {/* ═══════════════════════════════════════
          卡片 3 — 标的概况（原型图2 下）
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>标的概况</View>
        {/* 第一行 3 列 */}
        <View className='ov-grid3'>
          <View className='ov-item'>
            <Text className='ov-label'>综合评分</Text>
            <Text className='ov-val'>{finalScore ?? '--'}</Text>
          </View>
          <View className='ov-item'>
            <Text className='ov-label'>推荐排名</Text>
            <Text className='ov-val'>#{item.rankNum ?? '--'}</Text>
          </View>
          <View className='ov-item'>
            <Text className='ov-label'>因子权重</Text>
            <Text className='ov-val'>
              {item.factorWeight != null ? formatScore(item.factorWeight) + '%' : '--'}
            </Text>
          </View>
        </View>
        {/* 第二行 3 列 */}
        <View className='ov-grid3'>
          <View className='ov-item'>
            <Text className='ov-label'>分析权重</Text>
            <Text className='ov-val'>
              {item.analysisWeight != null ? formatScore(item.analysisWeight) + '%' : '--'}
            </Text>
          </View>
          <View className='ov-item'>
            <Text className='ov-label'>建议仓位</Text>
            <Text className='ov-val'>{formatPosition(item.suggestedPositionPct)}</Text>
          </View>
          <View className='ov-item'>
            <Text className='ov-label'>驱动</Text>
            <Text className='ov-val ov-val-driver'>{driverText(item.driver)}</Text>
          </View>
        </View>
      </View>

      {/* ═══════════════════════════════════════
          卡片 4 — 市场状态适配（原型图3 上）
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>市场状态适配</View>
        <View className='ma-top'>
          <Text className='ma-regime'>{regimeText(item.regime) || '震荡市'}</Text>
          <Text className='ma-mode'>
            {weightModeText(item.weightMode) || '固定权重'}
          </Text>
        </View>
        <Text className='ma-desc'>
          当前{regimeText(item.regime) || '震荡市'}，权重按 {weightModeText(item.weightMode) || '固定权重'}{' '}
          {item.weightMode === 'ICW' ? '动态' : ''}调节：
          {regimeText(item.regime) || '震荡市'}降低时序动量暴露，提升质量/盈利类因子权重（MarketRegimeDetector）。
        </Text>
      </View>

      {/* ═══════════════════════════════════════
          卡片 5 — 价格计划（原型图3 下）
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>价格计划</View>
        <View className='pp-list'>
          <View className='pp-row'>
            <Text className='pp-label'>目标价位</Text>
            <Text className='pp-val pp-red'>{formatPrice(item.suggestedTargetPrice)}</Text>
          </View>
          <View className='pp-row'>
            <Text className='pp-label'>止损价位</Text>
            <Text className='pp-val'>{formatPrice(item.suggestedStopLoss)}</Text>
          </View>
          <View className='pp-row'>
            <Text className='pp-label'>建议仓位</Text>
            <Text className='pp-val'>{formatPosition(item.suggestedPositionPct)}</Text>
          </View>
        </View>
      </View>

      {/* ═══════════════════════════════════════
          卡片 6 — 评分构成 八维子分（原型图4）
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>评分构成（八维子分）</View>
        {renderScoreRow('因子', item.factorScore)}
        {renderScoreRow('分析', item.analysisScore)}
        {renderScoreRow('技术', item.technicalScore)}
        {renderScoreRow('资金', item.capitalScore)}
        {renderScoreRow('事件', item.eventScore)}
        {renderScoreRow('基本面', item.fundamentalScore)}
        {renderScoreRow('风险', item.riskScore != null ? item.riskScore / 100 : null)}
        {renderScoreRow('流动性', item.liquidityScore != null ? item.liquidityScore / 10 : null)}
      </View>

      {/* ═══════════════════════════════════════
          卡片 7 — 因子/分析权重（原型图5 上）
          因子权重 = 量化因子模型（估值/成长/质量/动量等）在综合评分中的占比
          分析权重 = 基本面分析模型（盈利/负债/现金流等）在综合评分中的占比
          两者之和 = 100%
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>因子 / 分析 权重</View>
        <View className='fw-section'>
          <Text className='fw-label'>因子权重</Text>
          <Text className='fw-hint'>（量化因子模型：估值/成长/质量/动量等）</Text>
          <View className='fw-bar-wrap'>
            <View className='fw-bar' style={{ width: `${item.factorWeight ? Number(item.factorWeight) * 100 : 0}%` }} />
          </View>
          <Text className='fw-pct'>
            {item.factorWeight != null ? formatScore(item.factorWeight) + '%' : '--'}
          </Text>
        </View>
        <View className='fw-section'>
          <Text className='fw-label'>分析权重</Text>
          <Text className='fw-hint'>（基本面分析：盈利能力/负债/现金流等）</Text>
          <View className='fw-bar-wrap'>
            <View className='fw-bar' style={{ width: `${item.analysisWeight ? Number(item.analysisWeight) * 100 : 0}%` }} />
          </View>
          <Text className='fw-pct'>
            {item.analysisWeight != null ? formatScore(item.analysisWeight) + '%' : '--'}
          </Text>
        </View>
        <Text className='fw-note'>
          权重随市场状态（regime）动态调节：{regimeText(item.regime) || '震荡市'}降低时序动量暴露，提升质量/盈利类因子权重。
        </Text>
      </View>

      {/* ═══════════════════════════════════════
          卡片 8 — 风险调整指标（原型图5 中）
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>风险调整指标</View>
        <View className='ri-grid'>
          <View className='ri-item'>
            <Text className='ri-label'>风险评分</Text>
            <Text className='ri-val'>{item.riskScore != null ? Math.round(item.riskScore) : '--'}</Text>
          </View>
          <View className='ri-item'>
            <Text className='ri-label'>流动性评分</Text>
            <Text className='ri-val'>{item.liquidityScore != null ? Math.round(item.liquidityScore) : '--'}</Text>
          </View>
          <View className='ri-item'>
            <Text className='ri-label'>止损价位</Text>
            <Text className='ri-val'>{formatPrice(item.suggestedStopLoss)}</Text>
          </View>
          <View className='ri-item'>
            <Text className='ri-label'>建议仓位</Text>
            <Text className='ri-val'>{formatPosition(item.suggestedPositionPct)}</Text>
          </View>
          <View className='ri-item'>
            <Text className='ri-label'>单票上限</Text>
            <Text className='ri-val'>≤15%</Text>
          </View>
          <View className='ri-item'>
            <Text className='ri-label'>风险等级</Text>
            <Text className='ri-val ri-low'>{confidenceText(effectiveConfLevel) || '--'}</Text>
          </View>
        </View>
      </View>

      {/* ═══════════════════════════════════════
          卡片 9 — 风险提示（原型图5 下）
          ═══════════════════════════════════════ */}
      {buyReason.risk && (
        <View className='dp-card dp-risk-card'>
          <View className='dr-title'>风险提示</View>
          <Text className='dr-text'>
            止损价位 {formatPrice(item.suggestedStopLoss)}（触及即离场）；单票上限 ≤15%；当前风险等级{' '}
            <Text className='dr-em'>{confidenceText(effectiveConfLevel)}</Text>。
          </Text>
          <Text className='dr-text'>
            本推荐基于 {regimeText(item.regime) || '震荡市'} 下 {weightModeText(item.weightMode) || '固定权重'} 模型输出，综合评分 {finalScore ?? '--'}。
          </Text>
          <Text className='dr-text dr-disclaimer'>
            量化模型输出仅供参考，不构成投资建议。市场有风险，投资须谨慎；过往表现不代表未来收益。
          </Text>
        </View>
      )}

      {/* ═══════════════════════════════════════
          卡片 10 — 历史命中表现（原型图6 上）
          ═══════════════════════════════════════ */}
      {(item.nextDayReturn != null || item.nextWeekReturn != null || item.nextMonthReturn != null) && (
        <View className='dp-card'>
          <View className='dc-title'>历史命中表现</View>
          <View className='hm-header'>
            <View className='hm-left'>
              <Text className='hm-label-sm'>当月收益（推荐后跟踪）</Text>
              <Text className={`hm-big ${priceColor(item.nextMonthReturn)}`}>
                {item.nextMonthReturn != null ? formatPercent(item.nextMonthReturn) : '--'}
              </Text>
            </View>
            <Text className='hm-window'>跟踪窗口 次 / 当周 / 当月</Text>
          </View>
          {/* 仅当有当月收益数据时展示趋势图占位 */}
          {item.nextMonthReturn != null && (
            <View className='hm-chart-placeholder' />
          )}
          {item.nextMonthReturn == null && (
            <View className='hm-no-chart'>
              <Text className='hm-no-chart-text'>暂无足够跟踪数据</Text>
            </View>
          )}
          <View className='hm-stats'>
            {item.nextDayReturn != null && (
              <View className='hm-stat-item'>
                <Text className='hm-stat-l'>次日收益</Text>
                <Text className={`hm-stat-v ${priceColor(item.nextDayReturn)}`}>{formatPercent(item.nextDayReturn)}</Text>
              </View>
            )}
            {item.nextWeekReturn != null && (
              <View className='hm-stat-item'>
                <Text className='hm-stat-l'>当周收益</Text>
                <Text className={`hm-stat-v ${priceColor(item.nextWeekReturn)}`}>{formatPercent(item.nextWeekReturn)}</Text>
              </View>
            )}
            {item.nextMonthReturn != null && (
              <View className='hm-stat-item'>
                <Text className='hm-stat-l'>当月收益</Text>
                <Text className={`hm-stat-v ${priceColor(item.nextMonthReturn)}`}>{formatPercent(item.nextMonthReturn)}</Text>
              </View>
            )}
          </View>
          <Text className='hm-footer-note'>
            基于推荐后次/周/月实际涨跌，来自 RecommendationTracker 跟踪口径。
          </Text>
        </View>
      )}

      {/* ═══════════════════════════════════════
          卡片 11 — 标的属性（原型图6 中）
          ═══════════════════════════════════════ */}
      <View className='dp-card'>
        <View className='dc-title'>标的属性</View>
        <View className='prop-list'>
          <View className='prop-row'>
            <Text className='prop-k'>所属行业</Text>
            <Text className='prop-v'>{item.industry || '--'}</Text>
          </View>
          <View className='prop-row'>
            <Text className='prop-k'>策略归属</Text>
            <Text className='prop-v'>{item.strategyName || '--'}</Text>
          </View>
          <View className='prop-row'>
            <Text className='prop-k'>驱动类型</Text>
            <Text className='prop-v'>{driverText(item.driver)}</Text>
          </View>
          <View className='prop-row'>
            <Text className='prop-k'>权重模式</Text>
            <Text className='prop-v'>{weightModeText(item.weightMode)}</Text>
          </View>
        </View>
      </View>

      {/* ═══════════════════════════════════════
          卡片 12 — 买入理由 & 信号明细（原型图6 下）
          ═══════════════════════════════════════ */}
      {item.buyReason && (
        <View className='dp-card'>
          <View className='dc-title'>买入理由 & 信号明细</View>
          {buyReason.summary && (
            <Text className='br-summary'>{buyReason.summary}</Text>
          )}
          <View className='br-tags'>
            <Text className='br-tag'>{driverText(item.driver)}</Text>
            {buyReason.dimensions.length > 0 && buyReason.dimensions[0].name && (
              <Text className='br-tag'>主导维度：{buyReason.dimensions[0].name.replace('面', '')}</Text>
            )}
          </View>
        </View>
      )}

      {/* 底部留白 */}
      <View style={{ height: '40rpx' }} />

    </View>
  );
}

/* ── 子组件：八维子分进度条行 ──
   后端 score 字段量纲不统一：
   - 部分已是 0~100 整数（technicalScore=15, capitalScore=6, eventScore=19...）
   - 部分是 0~1 小数（factorScore=0.89）
   规则：val > 1 直接取整；0<=val<=1 则 ×100
*/
function renderScoreRow(label, scoreVal) {
  if (scoreVal == null) {
    return (
      <View className='ed-row'>
        <Text className='ed-label'>{label}</Text>
        <View className='ed-bar-wrap'><View className='ed-bar' style={{ width: '0%' }} /></View>
        <Text className='ed-val'>--</Text>
      </View>
    );
  }
  const num = Number(scoreVal);
  // 智能量纲：>1 当整数用，<=1 当百分比用
  const score = num > 1 ? Math.round(num) : Math.round(num * 100);
  return (
    <View className='ed-row'>
      <Text className='ed-label'>{label}</Text>
      <View className='ed-bar-wrap'>
        <View className='ed-bar' style={{ width: `${Math.min(score, 100)}%` }} />
      </View>
      <Text className='ed-val'>{score}</Text>
    </View>
  );
}
