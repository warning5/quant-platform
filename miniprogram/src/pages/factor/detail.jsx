import { useState, useEffect, useCallback, useMemo } from 'react';
import { View, Text, Canvas } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { factorApi } from '../../api';
import './detail.scss';

const CAT_TEXT = {
  MOMENTUM: '动量', VALUE: '价值', QUALITY: '质量', VOLATILITY: '波动率',
  TECHNICAL: '技术', FUNDAMENTAL: '基本面', FINANCIAL: '财务', SENTIMENT: '情绪',
  CHANTHEORY: '缠论', LIQUIDITY: '流动性', VOLUME_PRICE: '量价'
};
const ST_TEXT = { ACTIVE: '运行中', DEGRADED: '降级', DEPRECATED: '废弃', TESTING: '测试中', DRAFT: '草稿' };
const ST_CLASS = { ACTIVE: 'st-active', DEGRADED: 'st-degraded', DEPRECATED: 'st-deprecated', TESTING: 'st-testing', DRAFT: 'st-draft' };
const EFF_TEXT = { valid: '有效因子', weak: '弱有效', invalid: '无效' };
const EFF_CLASS = { valid: 'badge-valid', weak: 'badge-weak', invalid: 'badge-invalid' };
const TYPE_TEXT = { PATTERN: '形态因子', COMPOSITE: '复合因子', SCRIPTED: '脚本因子' };

// 频率：后端无字段，按因子性质给合理默认（与原型一致）
const FREQ_MAP = {
  FIN_ROE: '季频', FIN_NET_PROFIT_YOY: '季频', FIN_EARNINGS_QUALITY: '季频',
  RD: '季频', GROWTH: '季频', EARNINGS_SURPRISE: '季频'
};
const freqOf = (f) => FREQ_MAP[f.factorCode] || '日频';

// 有效性判定（与原型一致）：|IC|>=0.05 且 |IR|>=0.5 有效；>=0.03 且 >=0.3 弱有效
function effOf(ic, ir) {
  const a = Math.abs(ic || 0), b = Math.abs(ir || 0);
  if (a >= 0.05 && b >= 0.5) return 'valid';
  if (a >= 0.03 && b >= 0.3) return 'weak';
  return 'invalid';
}

// 误差函数 + 标准正态 CDF（用于 t 值转 p 值）
function erf(x) {
  const t = 1 / (1 + 0.3275911 * Math.abs(x));
  const y = 1 - (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t) * Math.exp(-x * x);
  return x >= 0 ? y : -y;
}
function normCdf(x) { return 0.5 * (1 + erf(x / Math.sqrt(2))); }
function round(v, d) { if (v == null || isNaN(v)) return null; const p = Math.pow(10, d); return Math.round(v * p) / p; }

// 客户端基于 IC 序列计算统计指标（IC 均值 / ICIR / IC 正比率 / t 值 / p 值）
function computeStats(trend) {
  const vals = (trend || []).map(r => Number(r.icValue)).filter(v => !isNaN(v));
  const n = vals.length;
  if (n === 0) return null;
  const mean = vals.reduce((a, b) => a + b, 0) / n;
  const variance = n > 1 ? vals.reduce((a, b) => a + (b - mean) ** 2, 0) / (n - 1) : 0;
  const std = Math.sqrt(variance);
  const ir = std > 0 ? mean / std : null;
  const pos = vals.filter(v => v > 0).length / n * 100;
  const t = (std > 0 && n > 1) ? mean / (std / Math.sqrt(n)) : null;
  const p = t != null ? 2 * (1 - normCdf(Math.abs(t))) : null;
  return {
    icMean: round(mean, 3),
    icir: round(ir, 2),
    icPos: round(pos, 1),
    tStat: round(t, 2),
    pVal: t != null ? round(p, 3) : null,
    n,
  };
}

// 安全解析 JSON
function safeJson(str, fallback) {
  if (!str) return fallback;
  try { return JSON.parse(str); } catch (e) { return fallback; }
}

// 百分比格式化
function pct(v) { if (v == null || isNaN(v)) return '--'; return (Number(v) * 100).toFixed(2) + '%'; }
function num(v, d = 2) { if (v == null || isNaN(v)) return '--'; return Number(v).toFixed(d); }

// ── 简要说明（点击标签浮动展示）──
const TOOLTIPS = {
  'IC 均值': '因子值与未来收益的相关系数均值',
  'ICIR': 'IC均值÷IC标准差，衡量稳定度',
  'IC 正比率': 'IC 为正的交易日占比',
  'RankIC': '排序后的秩相关系数，更抗异常值',
  't 值': 'IC 显著性的统计量，|t|>2 显著',
  'p 值': '显著性概率，<0.05 为有效',
};

function MetricItem({ label, value, tip }) {
  const [show, setShow] = useState(false);
  return (
    <View className='plan-item'>
      <View
        className='lbl-row'
        onTouchStart={() => setShow(true)}
        onTouchEnd={() => setTimeout(() => setShow(false), 300)}
      >
        <Text className='plbl'>{label}</Text>
        {tip && <Text className='q-mark'>?</Text>}
      </View>
      {show && tip && (
        <View className='float-tip'><Text className='ft-text'>{tip}</Text></View>
      )}
      <Text className='pval'>{value}</Text>
    </View>
  );
}

// IC 趋势折线（Canvas 2d，复用 strategy/detail 的 Sparkline 绘制逻辑）
function Sparkline({ id, values, color = '#3B9EFF' }) {
  useEffect(() => {
    if (!values || values.length < 2) return;
    let q;
    try { q = Taro.createSelectorQuery(); } catch (e) { return; }
    q.select('#' + id).fields({ node: true, size: true }).exec((res) => {
      if (!res || !res[0] || !res[0].node) return;
      const canvas = res[0].node;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      try {
        const dpr = (Taro.getSystemInfoSync && Taro.getSystemInfoSync().pixelRatio) || 2;
        const w = res[0].width, h = res[0].height;
        canvas.width = w * dpr; canvas.height = h * dpr;
        ctx.scale(dpr, dpr);
        ctx.clearRect(0, 0, w, h);
        const min = Math.min.apply(null, values);
        const max = Math.max.apply(null, values);
        const range = (max - min) || 1;
        const pad = 8;
        const pts = values.map((v, i) => {
          const x = pad + (i / (values.length - 1)) * (w - 2 * pad);
          const y = h - pad - ((v - min) / range) * (h - 2 * pad);
          return [x, y];
        });
        const grad = ctx.createLinearGradient(0, 0, 0, h);
        grad.addColorStop(0, color + '33');
        grad.addColorStop(1, color + '00');
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
        ctx.lineTo(pts[pts.length - 1][0], h);
        ctx.lineTo(pts[0][0], h);
        ctx.closePath();
        ctx.fillStyle = grad; ctx.fill();
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
        ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.stroke();
      } catch (e) { console.warn('[Sparkline] 绘制失败', e); }
    });
  }, [values, id, color]);
  return <Canvas type='2d' id={id} className='sparkline' />;
}

export default function FactorDetailPage() {
  const { params } = useRouter();
  const id = params.id;

  const [factor, setFactor] = useState(null);
  const [icTrend, setIcTrend] = useState(null);
  const [testReport, setTestReport] = useState(null); // 新增：测试报告
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [f, t, tr] = await Promise.all([
        factorApi.get(id),
        factorApi.icTrend(id, { forwardDays: 5 }).catch(() => null),
        factorApi.testReport(id).catch(() => null), // 新增：加载测试报告
      ]);
      setFactor(f);
      setIcTrend(t);
      setTestReport(tr);
    } catch (e) {
      console.error('加载因子详情失败', e);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const trend = icTrend && icTrend.trend ? icTrend.trend : [];
  const stats = useMemo(() => computeStats(trend), [trend]);
  const icValues = trend.map(r => Number(r.icValue)).filter(v => !isNaN(v));
  const last12 = icValues.slice(-12);
  const eff = stats ? effOf(stats.icMean, stats.icir) : 'invalid';

  // 测试报告快捷引用
  const hasReport = testReport && testReport.hasData === true;
  const gb = hasReport ? testReport.groupBacktest : null;
  const dc = hasReport ? testReport.decay : null;
  const cr = hasReport ? testReport.crowd : null;

  // 分组回测详情（后端已解析 JSON，直接用）
  const groupReturns = gb ? (gb.groupReturns || []) : [];
  const groupNav = gb ? (gb.groupNav || []) : [];
  const lsNav = gb ? (gb.longShortNav || []) : [];

  // 衰减序列（后端已解析 JSON，直接用）
  const decaySeries = dc ? (dc.series || []) : [];

  // 测试报告中的 IC 汇总（含 Pearson IC 与 Rank IC 区分）
  const icSummary = hasReport ? testReport.icSummary : null;
  const rankIcMean = icSummary && icSummary.rankIcMean != null
    ? Number(icSummary.rankIcMean) : null;

  const icStats = [
    ['IC 均值', stats ? stats.icMean : '--'],
    ['ICIR', stats ? stats.icir : '--'],
    ['IC 正比率', stats ? stats.icPos + '%' : '--'],
    ['RankIC', rankIcMean != null ? round(rankIcMean, 3) : (stats ? stats.icMean : '--')],
    ['t 值', stats && stats.tStat != null ? stats.tStat : '--'],
    ['p 值', stats && stats.pVal != null ? stats.pVal : '--'],
  ];

  const showStatusCard = factor && (factor.status === 'DEGRADED' || factor.status === 'DEPRECATED');
  const statusNote = factor && factor.status === 'DEGRADED'
    ? '因子已降级，ICW 权重置 0，暂不参与合成。'
    : factor && factor.status === 'DEPRECATED'
      ? '因子已废弃，不再参与任何合成与推荐。'
      : '';

  return (
    <View className='factor-detail-page'>
      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : !factor ? (
        <View className='empty-state'><Text className='empty-text'>因子不存在</Text></View>
      ) : (
        <View>
          {/* 头部 */}
          <View className='card fd-head'>
            <View className='fd-head-top'>
              <View className='fd-head-left'>
                <Text className='fd-name'>{factor.factorName}</Text>
                <View className='fd-meta'>
                  <Text className='cat-tag'>{CAT_TEXT[factor.category] || factor.category || '--'}</Text>
                  <Text className='weak'>{factor.factorCode}</Text>
                  <Text className='freq'>{freqOf(factor)}</Text>
                  {factor.factorType && (
                    <Text className='weak'>{TYPE_TEXT[factor.factorType] || factor.factorType}</Text>
                  )}
                </View>
              </View>
              <Text className={`st-pill ${ST_CLASS[factor.status] || ''}`}>{ST_TEXT[factor.status] || factor.status}</Text>
            </View>
            <View className='fd-eff-row'>
              <Text className={`badge ${EFF_CLASS[eff]}`}>{EFF_TEXT[eff]}</Text>
              <Text className='weak'>
                IC <Text className='b'>{stats ? stats.icMean : '--'}</Text>
                {' '}· IR <Text className='b'>{stats ? stats.icir : '--'}</Text>
              </Text>
            </View>
            {factor.description && <Text className='fd-desc'>{factor.description}</Text>}
            {factor.stockPool && (
              <View className='fd-row'>
                <Text className='fd-label'>适配股票池</Text>
                <Text className='fd-value'>{factor.stockPool}</Text>
              </View>
            )}
          </View>

          {showStatusCard && (
            <View className='card fd-statuscard'>
              <Text className='warn'>{statusNote}</Text>
            </View>
          )}

          {/* IC 趋势 */}
          <View className='card'>
            <View className='section-t'>IC 趋势（近 {last12.length} 期）</View>
            {last12.length >= 2 ? (
              <Sparkline id='fd-ic' values={last12} color='#3B9EFF' />
            ) : (
              <Text className='muted'>暂无足够 IC 数据绘制趋势</Text>
            )}
            <Text className='muted fd-eval'>
              有效性评估：{EFF_TEXT[eff]}（|IC|≥0.05 且 |IR|≥0.5 为有效因子；≥0.03 且 ≥0.3 为弱有效）。
            </Text>
          </View>

          {/* IC 统计 */}
          <View className='card'>
            <View className='section-t'>IC 统计</View>
            <View className='plan-grid'>
              {icStats.map((it, i) => (
                <MetricItem key={i} label={it[0]} value={it[1]} tip={TOOLTIPS[it[0]] || ''} />
              ))}
            </View>
            {stats && <Text className='muted fd-eval'>样本数 {stats.n} 期（基于前瞻 5 日 IC 序列客户端计算）。</Text>}
          </View>

          {/* ═══════════ 分组回测 ═══════════ */}
          <View className='card'>
            <View className='section-t'>分组回测</View>
            {hasReport && gb ? (
              <View>
                {/* 核心指标行 */}
                <View className='gb-summary'>
                  <View className='gb-metric'>
                    <Text className='gb-label'>多头年化</Text>
                    <Text className='gb-val pos'>{pct(gb.topGroupReturn)}</Text>
                  </View>
                  <View className='gb-metric'>
                    <Text className='gb-label'>空头年化</Text>
                    <Text className='gb-val neg'>{pct(gb.bottomGroupReturn)}</Text>
                  </View>
                  <View className='gb-metric'>
                    <Text className='gb-label'>多空年化</Text>
                    <Text className={`gb-val ${Number(gb.longShortReturn || 0) >= 0 ? 'pos' : 'neg'}`}>{pct(gb.longShortReturn)}</Text>
                  </View>
                </View>
                {/* 详细网格 */}
                <View className='plan-grid'>
                  {[
                    ['单调性', num(gb.monotonicity, 3)],
                    ['分组 IR', num(gb.groupIr, 2)],
                    ['最佳夏普', num(gb.bestSharpe, 2)],
                    ['多头胜率', pct(gb.winRateVsBenchmark)],
                    ['主动波动', num(gb.activeVolatility, 2)],
                    ['多空 p 值', num(gb.lsPValue, 3)],
                  ].map((it, i) => (
                    <View key={i} className='plan-item'>
                      <Text className='plbl'>{it[0]}</Text>
                      <Text className='pval'>{it[1]}</Text>
                    </View>
                  ))}
                </View>
                {/* 各组收益明细 */}
                {groupReturns.length > 0 && (
                  <View className='gb-groups'>
                    <Text className='gb-subtitle'>各组年化收益明细</Text>
                    {groupReturns.map((g, i) => (
                      <View key={i} className='gb-group-row'>
                        <Text className='gb-gname'>{g.group || `G${i+1}`}</Text>
                        <Text className='gb-gret'>{pct(g.annualReturn)}</Text>
                        <Text className='gb-gsub'>{`夏普${num(g.sharpe)} | 回撤${pct(g.maxDrawdown)} | 波动${pct(g.volatility)}`}</Text>
                      </View>
                    ))}
                  </View>
                )}
                {/* 多空净值曲线（如果有足够点） */}
                {lsNav.length >= 2 && (
                  <View className='gb-chart-area'>
                    <Text className='gb-subtitle'>多空净值累计</Text>
                    <Sparkline id='fd-lsnav' values={lsNav.map(p => Number(p.net || 1))} color='#52C41A' />
                  </View>
                )}
                {testReport.completedAt && (
                  <Text className='muted fd-eval'>报告完成时间：{testReport.completedAt}</Text>
                )}
              </View>
            ) : (
              <Text className='placeholder'>暂无数据（后端尚未运行因子检测任务：需在主后端触发因子回测以生成分组回测、衰减、拥挤度数据）。</Text>
            )}
          </View>

          {/* ═══════════ 因子衰减 ═══════════ */}
          <View className='card'>
            <View className='section-t'>因子衰减</View>
            {hasReport && dc ? (
              <View>
                <View className='plan-grid'>
                  {[
                    ['有效期', dc.decayPeriods != null ? `${dc.decayPeriods} 期` : '--'],
                    ['半衰期', dc.halfLifePeriods != null ? `${dc.halfLifePeriods} 期` : '--'],
                    ['衰减系数 λ', num(dc.decayCoefficient, 4)],
                    ['拟合 R²', num(dc.decayRSquared, 3)],
                  ].map((it, i) => (
                    <View key={i} className='plan-item'>
                      <Text className='plbl'>{it[0]}</Text>
                      <Text className='pval'>{it[1]}</Text>
                    </View>
                  ))}
                </View>
                {/* 衰减序列表 */}
                {decaySeries.length > 0 && (
                  <View className='dc-table-wrap'>
                    <View className='dc-table'>
                      <View className='dc-row dc-hd'>
                        <Text className='dc-cell'>Lag</Text>
                        <Text className='dc-cell'>IC</Text>
                        <Text className='dc-cell'>|IC|</Text>
                      </View>
                      {decaySeries.map((dp, i) => (
                        <View key={i} className='dc-row'>
                          <Text className='dc-cell'>{dp.period}</Text>
                          <Text className='dc-cell'>{num(dp.absoluteIc, 4)}</Text>
                          <Text className='dc-cell'>{num(Math.abs(dp.absoluteIc || 0), 4)}</Text>
                        </View>
                      ))}
                    </View>
                  </View>
                )}
              </View>
            ) : (
              <Text className='placeholder'>暂无数据（后端尚未计算衰减：半衰期、衰减 R²、有效期）。</Text>
            )}
          </View>

          {/* ═══════════ 拥挤度与去重 ═══════════ */}
          <View className='card'>
            <View className='section-t'>拥挤度与去重</View>
            {hasReport && cr ? (
              <View className='plan-grid'>
                {[
                  ['Top 组换手率', cr.turnoverRate != null ? pct(cr.turnoverRate) : '--'],
                  ['因子自相关', num(cr.factorAutoCorr, 3)],
                ].map((it, i) => (
                  <View key={i} className='plan-item'>
                    <Text className='plbl'>{it[0]}</Text>
                    <Text className='pval'>{it[1]}</Text>
                  </View>
                ))}
                <Text className='muted fd-eval'>
                  换手率高 → 因子不稳定，回测收益可能虚高；自相关低 → 因子值逐期波动大，需频繁调仓。
                </Text>
              </View>
            ) : (
              <Text className='placeholder'>暂无数据（后端尚未计算相关性去重：换手率、因子值自相关）。</Text>
            )}
          </View>
        </View>
      )}
    </View>
  );
}
