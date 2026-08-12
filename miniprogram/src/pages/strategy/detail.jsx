import { useState, useEffect, useCallback } from 'react';
import { View, Text, Canvas } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { strategyApi, confidenceApi, recommendationApi, factorApi } from '../../api';
import { formatRatio, formatDate, priceColor } from '../../utils/format';
import './detail.scss';

const STATUS_TEXT = { ACTIVE: '运行中', TESTING: '测试中', DRAFT: '草稿', DEPRECATED: '已停用' };
const STATUS_CLASS = { ACTIVE: 'st-active', TESTING: 'st-testing', DRAFT: 'st-draft', DEPRECATED: 'st-deprecated' };

const TYPE_TEXT = {
  FACTOR_LONG: '因子多头', LONG_SHORT: '市场中性', MARKET_NEUTRAL: '市场中性',
  MOMENTUM: '动量', MEAN_REVERSION: '反转', PATTERN: '形态', CUSTOM: '自定义'
};
const FREQ_TEXT = { DAILY: '日频', WEEKLY: '周频', MONTHLY: '月频', QUARTERLY: '季频' };
const BENCH_NAME = {
  '000300': '沪深300', '000300.SH': '沪深300', '000905': '中证500', '000905.SH': '中证500',
  '000852': '中证1000', '000852.SH': '中证1000', '000906': '中证全指', '000016': '上证50'
};
const FACTOR_NAME = {
  FIN_ROE: '净资产收益率', FIN_EARNINGS_QUALITY: '盈利质量', FIN_NET_PROFIT_YOY: '净利润同比',
  VOL20: '20日波动率(反向)', MOM60: '60日动量', MOM20: '20日动量', SIZE: '规模',
  BETA_60D: '60日Beta', REVERSAL_5D: '5日反转', PATTERN_STRENGTH: '形态强度',
  EARNINGS_SURPRISE: '盈利惊喜', LHB_INST_NET: '龙虎榜机构净买', INST_RESEARCH: '机构研报',
  VALUE: '估值', LOWVOL: '低波', DIVYIELD: '股息率', LIQUIDITY: '流动性', GROWTH: '营收增长',
  MOMENTUM_TS: '时序动量', TURN: '换手率', VOLUME_PRICE: '量价共振', PB: '市净率',
  RD: '研发投入', CHANTHEORY: '缠论笔'
};
const FACTOR_DEGRADED = new Set(['MOM60', 'VOL20', 'RD', 'PB']);

const PERF_HINT = {
  累计收益: '策略回测期间的总收益率。',
  年化收益: '按年折算的平均收益率，反映策略的长期盈利能力。',
  最大回撤: '策略净值从最高点回落到最低点的最大跌幅，数值越小风险越低。',
  夏普比率: '每承担一单位风险所获得的超额收益，数值越高风险收益比越好。',
  卡玛比率: '年化收益与最大回撤的比值，反映承担回撤风险后的收益能力。',
  年化波动: '策略收益率的年化波动程度，数值越大表示收益波动越剧烈。',
  交易胜率: '盈利交易次数占总交易次数的比例。'
};

const REGIME_TEXT = {
  BULL: '趋势市', BULL_MARKET: '趋势市', TREND: '趋势市', '趋势市': '趋势市',
  BEAR: '防御市', BEAR_MARKET: '防御市', '防御市': '防御市',
  SIDEWAYS: '震荡市', SHOCK: '震荡市', RANGE: '震荡市', '震荡市': '震荡市',
  GROWTH: '成长市', '成长市': '成长市',
  VALUE: '估值修复', '估值修复': '估值修复',
  NEUTRAL: '全市场', '全市场': '全市场', '中性': '全市场'
};
const REGIME_DESC = {
  '震荡市': '当前处于震荡格局，策略降低时序动量暴露，以盈利惊喜与质量因子获取稳健超额，对回撤控制严格。',
  '趋势市': '时序动量策略依赖明确趋势行情，震荡市中频繁止损；趋势确认后超额显著。',
  '防御市': '以高股息与低波动因子构建防御型组合，熊市与震荡市回撤控制优异。',
  '成长市': '侧重规模与盈利质量因子，弹性高于宽基增强，成长风格占优时超额显著。',
  '估值修复': '把握估值洼地修复机会，受资金与汇率影响大，波动较高但弹性充足。',
  '全市场': '多空对冲剔除 Beta，收益来自横截面因子选股，适应各类市场状态。'
};
const ACTION_TEXT = { BUY: '买入', ADD: '增持', HOLD: '持有', WATCH: '观察', REDUCE: '减持', SELL: '卖出' };
function actionClass(tag) {
  if (tag === 'BUY' || tag === '增持') return 'buy';
  if (tag === 'ADD' || tag === '减持') return 'add';
  return 'hold';
}
function fmtPct1(v) {
  if (v == null) return '--';
  return (v >= 0 ? '+' : '') + (Number(v) * 100).toFixed(1) + '%';
}

const LEVEL_TEXT = { HIGH: '高', MEDIUM: '中', LOW: '低', UNTRAINED: '未训练' };

function Sparkline({ id, values, color = '#3B9EFF' }) {
  useEffect(() => {
    if (!values || values.length < 2) return;
    let q;
    try {
      q = Taro.createSelectorQuery();
    } catch (e) {
      console.warn('[Sparkline] createSelectorQuery 失败', e);
      return;
    }
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
        // 渐变填充
        const grad = ctx.createLinearGradient(0, 0, 0, h);
        grad.addColorStop(0, color + '33');
        grad.addColorStop(1, color + '00');
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
        ctx.lineTo(pts[pts.length - 1][0], h);
        ctx.lineTo(pts[0][0], h);
        ctx.closePath();
        ctx.fillStyle = grad;
        ctx.fill();
        // 折线
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.lineJoin = 'round';
        ctx.stroke();
      } catch (e) {
        console.warn('[Sparkline] 绘制失败', e);
      }
    });
  }, [values, id, color]);
  return <Canvas type='2d' id={id} className='sparkline' />;
}

function parseCurve(json, key = 'value') {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    if (!Array.isArray(arr)) return [];
    return arr
      .map((o) => ({ date: o.date, value: Number(o[key]) }))
      .filter((o) => !isNaN(o.value));
  } catch (e) { return []; }
}

// 取最近 N 个月的数据点（按 date 升序裁剪），返回数值数组与起止日期
function sliceLastMonths(raw, months) {
  if (!Array.isArray(raw) || raw.length === 0) return { values: [], range: null };
  const sorted = raw.slice().sort((a, b) => (a.date || '').localeCompare(b.date || ''));
  const lastDate = new Date((sorted[sorted.length - 1].date || '').replace(/-/g, '/'));
  let cutoff = null;
  if (!isNaN(lastDate)) {
    cutoff = new Date(lastDate);
    cutoff.setMonth(cutoff.getMonth() - months);
  }
  const sliced = cutoff
    ? sorted.filter((p) => {
        const d = new Date((p.date || '').replace(/-/g, '/'));
        return !isNaN(d) && d >= cutoff;
      })
    : sorted;
  return {
    values: sliced.map((p) => p.value),
    range: sliced.length ? { start: sliced[0].date, end: sliced[sliced.length - 1].date } : null
  };
}

function parseMonthly(json) {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr.map((o) => ({ month: o.month, ret: Number(o.return) || 0 })) : [];
  } catch (e) { return []; }
}

function parseFactors(json, nameMap) {
  if (!json) return [];
  try {
    const o = JSON.parse(json);
    const fs = o && o.factors;
    if (!Array.isArray(fs)) return [];
    const total = fs.reduce((s, f) => s + (Number(f.weight) || 0), 0) || 1;
    return fs
      .map((f) => ({
        code: f.code,
        name: (nameMap && nameMap[f.code]) || FACTOR_NAME[f.code] || f.code,
        weight: Math.round(((Number(f.weight) || 0) / total) * 1000) / 10,
        degraded: FACTOR_DEGRADED.has(f.code)
      }))
      .filter((f) => f.weight > 0)
      .sort((a, b) => b.weight - a.weight);
  } catch (e) { return []; }
}

function nextRebalance(base, freq) {
  if (!base) return null;
  const d = new Date(base.replace(/-/g, '/'));
  if (isNaN(d)) return null;
  const f = (freq || '').toUpperCase();
  if (f === 'DAILY') d.setDate(d.getDate() + 1);
  else if (f === 'WEEKLY') d.setDate(d.getDate() + 7);
  else if (f === 'MONTHLY') d.setMonth(d.getMonth() + 1);
  else return null;
  const p = (n) => (n < 10 ? '0' + n : '' + n);
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export default function StrategyDetailPage() {
  const { params } = useRouter();
  const id = params.id;

  const [strategy, setStrategy] = useState(null);
  const [backtest, setBacktest] = useState(null);
  const [confidence, setConfidence] = useState(null);
  const [recs, setRecs] = useState([]);
  const [batches, setBatches] = useState([]);
  const [hitRate, setHitRate] = useState(null);
  const [hitRateMonthly, setHitRateMonthly] = useState(null);
  const [factorNames, setFactorNames] = useState({});
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [s, b, c, rl, bh] = await Promise.all([
        strategyApi.get(id),
        strategyApi.backtest(id).catch(() => null),
        confidenceApi.getLatest(id).catch(() => null),
        recommendationApi.getLatest(id).catch(() => []),
        recommendationApi.getBatchHistory(20, id).catch(() => [])
      ]);
      setStrategy(s);
      setBacktest(b);
      setConfidence(c);
      setRecs(Array.isArray(rl) ? rl : []);
      setBatches(Array.isArray(bh) ? bh : []);
      const latestDate = Array.isArray(rl) && rl.length > 0 ? rl[0].recommendDate : null;
      if (latestDate) {
        recommendationApi.getHitRate(id, latestDate).then(setHitRate).catch(() => setHitRate(null));
      }
      recommendationApi.getHitRateMonthly(id, 12).then(setHitRateMonthly).catch(() => setHitRateMonthly(null));
      // 因子权威中文名：从后端 factor_definition 表获取（/mp/factors）
      factorApi.list().then((list) => {
        const m = {};
        (Array.isArray(list) ? list : []).forEach((it) => {
          if (it && it.factorCode) m[it.factorCode] = it.factorName || it.factorCode;
        });
        setFactorNames(m);
      }).catch(() => {});
    } catch (e) {
      console.error('加载策略详情失败', e);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  if (loading) {
    return <View className='sd-page'><View className='empty-state'><Text className='empty-text'>加载中...</Text></View></View>;
  }
  if (!strategy) {
    return <View className='sd-page'><View className='empty-state'><Text className='empty-text'>策略不存在</Text></View></View>;
  }

  const report = backtest && backtest.report ? backtest.report : null;
  const task = backtest && backtest.task ? backtest.task : null;
  const factors = parseFactors(strategy.factorConfigJson, factorNames);
  const equityRaw = parseCurve(report && report.equityCurveJson, 'value');
  const equitySlice = sliceLastMonths(equityRaw, 2);
  const equity = equitySlice.values;
  const equityRange = equitySlice.range;
  const monthly = parseMonthly(report && report.monthlyReturnsJson);

  const bench = task && task.benchmarkCode
    ? (BENCH_NAME[task.benchmarkCode] || task.benchmarkCode)
    : (strategy.strategyType === 'FACTOR_LONG' ? '中证全指' : '');

  // 调仓日期：以最新推荐日期为上次调仓（真实再平衡点），否则回测完成日
  let lastReb = null;
  if (recs.length) {
    lastReb = recs.map((r) => r.recommendDate).sort().reverse()[0];
  } else if (task && task.completedAt) {
    lastReb = (task.completedAt || '').slice(0, 10);
  }
  const nextReb = nextRebalance(lastReb, strategy.rebalanceFrequency);

  const status = strategy.status;
  const confLevel = confidence ? (LEVEL_TEXT[confidence.level] || confidence.level) : '--';
  const confScore = confidence ? Number(confidence.score) : null;

  // 风险调整指标
  const riskAdj = report ? [
    { l: '索提诺', v: report.sortinoRatio != null ? Number(report.sortinoRatio).toFixed(2) : '--' },
    { l: '信息比率', v: report.informationRatio != null ? Number(report.informationRatio).toFixed(2) : '--' },
    { l: 'Alpha', v: report.alpha != null ? formatRatio(report.alpha) : '--', up: report.alpha != null && report.alpha >= 0 },
    { l: 'Beta', v: report.beta != null ? Number(report.beta).toFixed(2) : '--' },
    { l: '跟踪误差', v: '--' },
    { l: '下行风险', v: '--' },
    { l: '回撤持续', v: report.maxDrawdownDuration != null ? report.maxDrawdownDuration + '天' : '--' }
  ] : [];

  // 绩效指标（7 项，与原型一致）
  const perf = report ? [
    { l: '累计收益', v: report.totalReturn != null ? formatRatio(report.totalReturn) : '--', color: priceColor(report.totalReturn) },
    { l: '年化收益', v: report.annualReturn != null ? formatRatio(report.annualReturn) : '--', color: priceColor(report.annualReturn) },
    { l: '最大回撤', v: report.maxDrawdown != null ? formatRatio(report.maxDrawdown) : '--', color: priceColor(report.maxDrawdown) },
    { l: '夏普比率', v: report.sharpeRatio != null ? Number(report.sharpeRatio).toFixed(2) : '--' },
    { l: '卡玛比率', v: report.calmarRatio != null ? Number(report.calmarRatio).toFixed(2) : '--' },
    { l: '年化波动', v: report.volatility != null ? formatRatio(report.volatility) : '--' },
    { l: '交易胜率', v: report.winRate != null ? formatRatio(report.winRate) : '--', color: 'text-red' }
  ] : [];

  // 风控约束（仅展示有数据项）
  const riskRows = [];
  if (strategy.maxPositionCount != null) riskRows.push({ l: '单票上限', v: strategy.maxPositionCount + ' 只' });
  if (strategy.stopLossPct != null) riskRows.push({ l: '止损线', v: formatRatio(strategy.stopLossPct), up: false });
  if (strategy.stopProfitPct != null) riskRows.push({ l: '止盈线', v: formatRatio(strategy.stopProfitPct), up: true });
  if (strategy.maxDrawdownPct != null) riskRows.push({ l: '最大回撤', v: formatRatio(strategy.maxDrawdownPct), up: false });
  if (strategy.positionSizeType) riskRows.push({ l: '仓位方式', v: strategy.positionSizeType === 'EQUAL' ? '等权' : strategy.positionSizeType });

  // 历史命中曲线（批次命中率）
  const hitCurve = batches.map((b) => (b.hitRate != null ? Number(b.hitRate) : null)).filter((v) => v != null);

  // 实盘命中率月度序列（连续 12 个月，无跟踪数据为 null）
  const hrMonthly = hitRateMonthly && Array.isArray(hitRateMonthly.series) ? hitRateMonthly.series : [];
  const hrCurve = hrMonthly.map((s) => (s.hitRate != null ? Number(s.hitRate) * 100 : null));
  const hrCurveValues = hrCurve.filter((v) => v != null);
  const hrLatest = [...hrMonthly].reverse().find((s) => s.hitRate != null);

  // 市场状态：取最新批次的 regime（多数策略单 regime）
  const rawRegime = recs.length ? recs[0].regime : null;
  const regime = rawRegime ? (REGIME_TEXT[rawRegime] || rawRegime) : null;
  const regimeDesc = regime ? (REGIME_DESC[regime] || '策略根据当前市场状态动态调节因子暴露与仓位。') : null;

  // 持仓：最新推荐按仓位权重 Top N
  const holdings = recs
    .filter((r) => r.suggestedPositionPct != null)
    .map((r) => ({ name: r.stockName, code: r.stockCode, pct: Number(r.suggestedPositionPct), actionTag: r.actionTag }))
    .sort((a, b) => b.pct - a.pct)
    .slice(0, 8);
  const maxHolding = holdings.length ? Math.max.apply(null, holdings.map((h) => h.pct)) : 1;

  // 行业分布：按 industry 聚合仓位权重
  const indMap = {};
  recs.forEach((r) => {
    if (r.industry && r.suggestedPositionPct != null) {
      indMap[r.industry] = (indMap[r.industry] || 0) + Number(r.suggestedPositionPct);
    }
  });
  const industries = Object.keys(indMap)
    .map((n) => ({ name: n, w: Math.round(indMap[n] * 1000) / 1000 }))
    .sort((a, b) => b.w - a.w)
    .slice(0, 6);
  const maxInd = industries.length ? Math.max.apply(null, industries.map((i) => i.w)) : 1;

  // 调仓历史：批次时间线
  const rebalance = batches
    .map((b) => ({
      date: b.date || b.recommendDate,
      hitRate: b.hitRate != null ? Number(b.hitRate) : null,
      avgDayReturn: b.avgDayReturn != null ? Number(b.avgDayReturn) : null,
      avgWeekReturn: b.avgWeekReturn != null ? Number(b.avgWeekReturn) : null,
      total: b.total
    }))
    .filter((b) => b.date)
    .slice(0, 10);

  return (
    <View className='sd-page'>
      {/* 概览 */}
      <View className='card'>
        <View className='sd-top'>
          <Text className='sd-name'>{strategy.strategyName}</Text>
          <View className='sd-pills'>
            <Text className={`st-pill ${STATUS_CLASS[status] || 'st-draft'}`}>{STATUS_TEXT[status] || status}</Text>
            <Text className='pill tag-caliber'>实盘口径</Text>
          </View>
        </View>
        <View className='sd-meta'>
          <Text className='tag'>{TYPE_TEXT[strategy.strategyType] || strategy.strategyType}</Text>
          <Text className='sd-freq'>{FREQ_TEXT[strategy.rebalanceFrequency] || strategy.rebalanceFrequency}</Text>
          {bench && <Text className='weak'>基准 {bench}</Text>}
        </View>
        <View className='sd-perf'>
          <View className='perf-cell'>
            <Text className='perf-lbl'>年化收益</Text>
            <Text className={`perf-val ${priceColor(report && report.annualReturn)}`}>
              {report && report.annualReturn != null ? formatRatio(report.annualReturn) : '--'}
            </Text>
          </View>
          <View className='perf-cell'>
            <Text className='perf-lbl'>最大回撤</Text>
            <Text className={`perf-val ${priceColor(report && report.maxDrawdown)}`}>
              {report && report.maxDrawdown != null ? formatRatio(report.maxDrawdown) : '--'}
            </Text>
          </View>
        </View>
      </View>

      {/* 绩效指标 */}
      <View className='card'>
        <View className='section-title'>绩效指标</View>
        {perf.length ? (
          <View className='perf-grid'>
            {perf.map((m, i) => (
              <View key={i} className='perf-item' onClick={() => {
                Taro.showModal({ title: m.l, content: PERF_HINT[m.l] || '策略回测指标。', showCancel: false });
              }}>
                <Text className='perf-lbl'>{m.l}</Text>
                <Text className={`perf-val ${m.color || ''}`}>{m.v}</Text>
              </View>
            ))}
          </View>
        ) : <Text className='bt-empty'>暂无回测结果</Text>}
      </View>

      {/* 实盘命中率 */}
      {hitRate && (
        <View className='card'>
          <View className='section-title'>实盘命中率</View>
          <View className='hr-head'>
            <View className='hr-left'>
              <Text className='hr-sub'>推荐命中率（跟踪后N日）</Text>
              <Text className='hr-pct'>{hitRate.hitRate != null ? (Number(hitRate.hitRate) * 100).toFixed(0) + '%' : '--'}</Text>
            </View>
            <View className='hr-right'>
              <Text className='hr-count'>样本 {hitRate.tracked != null ? hitRate.tracked : '--'} 笔</Text>
              <Text className='hr-sub'>跑赢基准占比</Text>
            </View>
          </View>
          <View className={hrCurveValues.length >= 2 ? 'hr-curve' : 'hr-curve hr-curve-empty'}>
            {hrCurveValues.length >= 2 ? (
              <Sparkline id='hitRateCurve' values={hrCurveValues} color='#3B9EFF' />
            ) : (
              <Text className='hr-note-inline'>
                近12个月命中率数据积累中{hrLatest ? '，最新 ' + hrLatest.month + '：' + (Number(hrLatest.hitRate) * 100).toFixed(0) + '%' : ''}
              </Text>
            )}
          </View>
          <Text className='hr-note'>数据来自推荐跟踪(RecommendationTracker)，按推荐后N日涨跌判定命中，近12个月走势</Text>
        </View>
      )}

      {/* 净值曲线 */}
      {equity.length > 1 && (
        <View className='card'>
          <View className='section-title'>净值曲线</View>
          <View className='curve-wrap'>
            <Sparkline id='equityCurve' values={equity} color='#3B9EFF' />
          </View>
          <Text className='curve-meta'>
            {equityRange && equityRange.start
              ? formatDate(equityRange.start)
              : task && task.startDate
              ? formatDate(task.startDate)
              : '--'} ~ {equityRange && equityRange.end
              ? formatDate(equityRange.end)
              : task && task.endDate
              ? formatDate(task.endDate)
              : '--'}
            {equityRange ? '（近2个月）' : ''}
          </Text>
        </View>
      )}

      {/* 回测月度收益 */}
      {monthly.length > 0 && (
        <View className='card'>
          <View className='section-title'>回测月度收益</View>
          <View className='mh'>
            {monthly.map((m, i) => {
              const pos = m.ret >= 0;
              const intensity = Math.min(1, Math.abs(m.ret) / 0.15);
              const bg = pos
                ? `rgba(246,70,93,${0.15 + intensity * 0.7})`
                : `rgba(22,199,132,${0.15 + intensity * 0.7})`;
              return (
                <View key={i} className='mc' style={{ background: bg }}>
                  <Text className='mv'>{m.ret >= 0 ? '+' : ''}{(m.ret * 100).toFixed(1)}%</Text>
                  <Text className='mn'>{m.month.slice(5)}</Text>
                </View>
              );
            })}
          </View>
        </View>
      )}

      {/* 市场状态适配 */}
      {regime && (
        <View className='card'>
          <View className='section-title'>市场状态适配</View>
          <View className='regime-row'>
            <Text className='tag-regime'>{regime}</Text>
          </View>
          <Text className='regime-desc'>{regimeDesc}</Text>
        </View>
      )}

      {/* 调仓信息 */}
      <View className='card'>
        <View className='section-title'>调仓信息</View>
        <View className='info-row'>
          <Text className='info-lbl'>上次调仓</Text>
          <Text className='info-val'>{lastReb || '--'}</Text>
        </View>
        <View className='info-row'>
          <Text className='info-lbl'>下次调仓</Text>
          <Text className='info-val'>{nextReb || '待确认'}</Text>
        </View>
        <View className='info-row'>
          <Text className='info-lbl'>调仓频率</Text>
          <Text className='info-val'>{FREQ_TEXT[strategy.rebalanceFrequency] || strategy.rebalanceFrequency || '--'}</Text>
        </View>
      </View>

      {/* 调仓历史 */}
      {rebalance.length > 0 && (
        <View className='card'>
          <View className='section-title'>调仓历史</View>
          <View className='reb-list'>
            {rebalance.map((b, i) => (
              <View key={i} className='reb-item'>
                <View className='reb-line' />
                <View className='reb-body'>
                  <Text className='reb-date'>{formatDate(b.date)}</Text>
                  <View className='reb-metrics'>
                    <Text className='reb-m'>推荐 {b.total != null ? b.total : '--'} 只</Text>
                    <Text className={`reb-m ${b.hitRate != null ? 'text-red' : 'text-muted'}`}>
                      命中 {b.hitRate != null ? (b.hitRate * 100).toFixed(0) + '%' : '待追踪'}
                    </Text>
                    <Text className={`reb-m ${b.avgDayReturn != null ? (b.avgDayReturn >= 0 ? 'text-red' : 'text-green') : 'text-muted'}`}>
                      日均 {b.avgDayReturn != null ? fmtPct1(b.avgDayReturn) : '--'}
                    </Text>
                  </View>
                </View>
              </View>
            ))}
          </View>
        </View>
      )}

      {/* 策略置信度 */}
      <View className='card'>
        <View className='section-title'>策略置信度</View>
        <View className='conf-row'>
          <View className='conf-score'>
            <Text className='conf-num'>{confScore != null ? confScore : '--'}</Text>
            <Text className='conf-level'>{confLevel}</Text>
          </View>
          <View className='conf-body'>
            <View className='fbar'>
              <View className='ffill' style={{ width: (confScore != null ? Math.min(100, confScore) : 0) + '%' }} />
            </View>
            <Text className='conf-desc'>
              {confidence && confidence.sampleSize != null
                ? `基于 ${confidence.sampleSize} 个样本，综合命中率、收益与回撤评级（${confidence.weightMode || 'ICW'} 权重）。`
                : '综合因子 IC 稳定性、基本面质量与资金面强度评级。'}
            </Text>
          </View>
        </View>
      </View>

      {/* 历史命中表现 */}
      <View className='card'>
        <View className='section-title'>历史命中表现</View>
        <View className='hit-head'>
          <View>
            <Text className='weak'>综合命中率</Text>
            <Text className='hit-big'>
              {confidence && confidence.hitRateValue != null ? (confidence.hitRateValue * 100).toFixed(1) + '%' : '--'}
            </Text>
          </View>
          <View className='weak hit-sample'>
            <Text>样本 {confidence && confidence.sampleSize != null ? confidence.sampleSize : '--'}</Text>
            <Text>数据截至 {confidence && confidence.dataAsOfDate ? ('' + confidence.dataAsOfDate).slice(5) : '--'}</Text>
          </View>
        </View>
        {hitCurve.length > 1 && (
          <View className='curve-wrap sm'>
            <Sparkline id='hitCurve' values={hitCurve.map((v) => v * 100)} color='#3B9EFF' />
          </View>
        )}
        {batches.length > 0 && (
          <View className='hit-list'>
            {batches.slice(0, 8).map((b, i) => (
              <View key={i} className='hit-item'>
                <Text className='hit-date'>{formatDate(b.recommendDate || b.date)}</Text>
                <Text className='hit-meta'>推荐 {b.total || b.count || '--'} · 追踪 {b.tracked != null ? b.tracked : 0}</Text>
                <Text className={`hit-rate ${b.hitRate != null ? 'text-red' : 'text-muted'}`}>
                  {b.hitRate != null ? formatRatio(b.hitRate) : '待追踪'}
                </Text>
              </View>
            ))}
          </View>
        )}
        {batches.length === 0 && <Text className='bt-empty'>暂无批次追踪数据</Text>}
      </View>

      {/* 风险调整指标 */}
      {riskAdj.length > 0 && (
        <View className='card'>
          <View className='section-title'>风险调整指标</View>
          <View className='riskadj-grid'>
            {riskAdj.map((m, i) => (
              <View key={i} className='plan-item'>
                <Text className='plbl'>{m.l}</Text>
                <Text className={`pval ${m.up ? 'up' : ''}`}>{m.v}</Text>
              </View>
            ))}
          </View>
        </View>
      )}

      {/* 因子权重 */}
      {factors.length > 0 && (
        <View className='card'>
          <View className='section-title'>
            因子权重
            <Text className='title-sub'>{strategy.factorConfigJson && JSON.parse(strategy.factorConfigJson).weightMode} 加权</Text>
          </View>
          {factors.map((f, i) => (
            <View key={i} className={`frow ${f.degraded ? 'frow-off' : ''}`}>
              <View className='flbl'>
                <Text className='fname'>{f.name}</Text>
                <Text className='fic'>{f.degraded ? '降级' : '生效'}</Text>
              </View>
              <View className='fbot'>
                <View className='fbar'>
                  <View className='ffill' style={{ width: f.weight + '%', opacity: f.degraded ? 0.4 : 1 }} />
                </View>
                <Text className='fval'>{f.weight}%</Text>
              </View>
            </View>
          ))}
        </View>
      )}

      {/* 持仓 */}
      {holdings.length > 0 && (
        <View className='card'>
          <View className='section-title'>持仓</View>
          {holdings.map((h, i) => (
            <View key={i} className='hold-item' onClick={() => Taro.navigateTo({ url: `/pages/recommend/detail?code=${h.code}&strategyId=${id}` })}>
              <View className='hold-top'>
                <Text className='hold-name'>{h.name}</Text>
                <Text className='hold-pct'>{h.pct != null ? (h.pct * 100).toFixed(1) + '%' : '--'}</Text>
              </View>
              <View className='fbar'>
                <View className='ffill' style={{ width: (maxHolding ? (h.pct / maxHolding) * 100 : 0) + '%' }} />
              </View>
            </View>
          ))}
        </View>
      )}

      {/* 行业分布 */}
      {industries.length > 0 && (
        <View className='card'>
          <View className='section-title'>行业分布</View>
          {industries.map((ind, i) => (
            <View key={i} className='ind-item'>
              <View className='ind-top'>
                <Text className='ind-name'>{ind.name}</Text>
                <Text className='ind-pct'>{ind.w != null ? (ind.w * 100).toFixed(1) + '%' : '--'}</Text>
              </View>
              <View className='fbar'>
                <View className='ffill' style={{ width: (maxInd ? (ind.w / maxInd) * 100 : 0) + '%', background: '#7B61FF' }} />
              </View>
            </View>
          ))}
        </View>
      )}

      {/* 风控约束 */}
      {riskRows.length > 0 && (
        <View className='card'>
          <View className='section-title'>风控约束</View>
          {riskRows.map((r, i) => (
            <View key={i} className='info-row'>
              <Text className='info-lbl'>{r.l}</Text>
              <Text className={`info-val ${r.up === true ? 'text-green' : r.up === false ? 'text-red' : ''}`}>{r.v}</Text>
            </View>
          ))}
        </View>
      )}

      {/* 最新推荐 */}
      {recs.length > 0 && (
        <View className='card'>
          <View className='section-title'>最新推荐</View>
          {recs.slice(0, 8).map((r, i) => (
            <View key={i} className='rec-item' onClick={() => Taro.navigateTo({ url: `/pages/recommend/detail?code=${r.stockCode}&date=${r.recommendDate}&strategyId=${id}` })}>
              <View className='rinfo'>
                <Text className='rname'>{r.stockName}</Text>
                <Text className='rcode'>{r.stockCode} · {r.industry || ''}</Text>
              </View>
              <View className='rright'>
                <Text className='rscore'>{r.finalScore != null ? `${(Number(r.finalScore) * 100).toFixed(0)}` : '--'}</Text>
                <Text className={`rtag ${actionClass(r.actionTag)}`}>
                  {ACTION_TEXT[r.actionTag] || r.actionTag || '--'}
                </Text>
              </View>
            </View>
          ))}
        </View>
      )}

      <View style={{ height: '40rpx' }} />
    </View>
  );
}
