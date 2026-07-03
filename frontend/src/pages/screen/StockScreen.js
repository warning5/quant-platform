import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import {
  Card, Button, Space, Typography, Table, Tag, InputNumber, Select,
  DatePicker, Row, Col, Statistic, Divider, Tooltip, Badge,
  Empty, Spin, Progress, Alert, Form, Popover, Modal, Input, Slider, Tabs, Checkbox, App,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, PlayCircleOutlined, FilterOutlined,
  InfoCircleOutlined, ReloadOutlined, SwapOutlined, QuestionCircleOutlined,
  StarOutlined, WarningOutlined, EditOutlined,
  SafetyCertificateOutlined, ArrowUpOutlined, ArrowDownOutlined,
  PlusSquareOutlined, MinusSquareOutlined, ThunderboltOutlined, LineChartOutlined, FundOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, RiseOutlined, StockOutlined,
  HistoryOutlined, CopyOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import api, { factorApi } from '../../api';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { useMarketThermometer } from '../../hooks/useMarketThermometer';
import { CATEGORY_LABELS as CATEGORY_LABEL } from '../factors/constants';
import RollingBacktestModal from './RollingBacktestModal';

const { Title, Text, Paragraph } = Typography;
const { Option } = Select;
const { TextArea } = Input;

/* ── 常量 ─────────────────────────────────────────────────────────── */
const CATEGORY_COLOR = {
  MOMENTUM: 'blue', VALUE: 'gold', QUALITY: 'green', VOLATILITY: 'orange',
  TECHNICAL: 'purple', FINANCIAL: 'green', SENTIMENT: 'magenta',
  LIQUIDITY: 'volcano', VOLUME_PRICE: 'geekblue', CUSTOM: 'default',
};
// 分类中文标签统一从 constants.js 导入（别名 CATEGORY_LABEL）

const OUTLIER_OPTIONS = [
  { value: 'NONE', label: '不处理' },
  { value: 'MAD', label: '中位数去极值法' },
  { value: 'SIGMA3', label: '3σ法' },
  { value: 'PERCENTILE', label: '百分位截断' },
];
const NORMALIZE_OPTIONS = [
  { value: 'NONE', label: '不处理' },
  { value: 'ZSCORE', label: '标准化法 (Z-Score)' },
  { value: 'MINMAX', label: 'Min-Max 归一化' },
  { value: 'RANK', label: '百分位排名' },
];
const ORTHOGONAL_OPTIONS = [
  { value: 'NONE', label: '不正交化' },
  { value: 'SCHMIDT', label: '施密特正交化 (Gram-Schmidt)' },
];
const NEUTRALIZATION_OPTIONS = [
  { value: 'NONE', label: '不中性化' },
  { value: 'INDUSTRY', label: '行业中性化' },
  { value: 'MARKET_CAP', label: '市值中性化' },
  { value: 'BOTH', label: '行业+市值双重中性化' },
];
const WEIGHT_MODE_OPTIONS = [
  { value: 'EQUAL', label: '等权（使用配置权重）' },
  { value: 'IC', label: 'IC动态加权（近60日均值）' },
  { value: 'IR', label: 'IR动态加权（信噪比）' },
];

/** 根据因子类型组合，给出中性化方式推荐 */
function recommendNeutralization(factors, availableFactors) {
  if (!factors || factors.length === 0) return null;
  const codeSet = new Set(factors.map(f => f.factorCode));
  const cats = new Set();
  availableFactors.forEach(af => {
    if (codeSet.has(af.factorCode) && af.category) cats.add(af.category);
  });
  // 需要行业中性化的类型
  const needIndustry = ['FINANCIAL', 'VALUE', 'QUALITY'].some(c => cats.has(c));
  // 需要市值中性化的类型
  const needMarketCap = ['LIQUIDITY', 'VOLATILITY', 'VOLUME_PRICE'].some(c => cats.has(c));
  // 技术/动量/情绪类可以不做
  if (needIndustry && needMarketCap) return { method: 'BOTH', reason: '财务/估值/质量 + 流动性/波动率/量价，双重中性化' };
  if (needIndustry) return { method: 'INDUSTRY', reason: '含财务/估值/质量因子，行业中性化（避免行业结构偏差）' };
  if (needMarketCap) return { method: 'MARKET_CAP', reason: '含流动性/波动率/量价因子，市值中性化（避免大小盘偏差）' };
  return { method: 'NONE', reason: '动量/技术/情绪类因子，无需中性化（方向性信号跨组可比）' };
}
const FILTER_OP_OPTIONS = [
  { value: 'NONE', label: '无' },
  { value: 'GT', label: '大于 (>)' },
  { value: 'GTE', label: '大于等于 (≥)' },
  { value: 'LT', label: '小于 (<)' },
  { value: 'LTE', label: '小于等于 (≤)' },
];

/* ── 预设组合说明数据 ────────────────────────────────────────────── */
/* ── 默认单条因子配置 ──────────────────────────────────────────────── */
const DEFAULT_FACTOR = (factorCode) => ({
  factorCode,
  direction: 1,
  weight: 1,
  filterOp: 'NONE',
  filterValue: null,
  outlierMethod: null,
  normalizeMethod: null,
});

function scoreColor(v) {
  if (v >= 0.8) return '#52c41a';
  if (v >= 0.6) return '#1677ff';
  if (v >= 0.4) return '#faad14';
  return '#ff4d4f';
}

function riskColor(level) {
  if (level === 'low') return '#52c41a';
  if (level === 'medium') return '#faad14';
  return '#ff4d4f';
}

function riskLabel(level) {
  if (level === 'low') return '低风险';
  if (level === 'medium') return '中风险';
  if (level === 'high') return '高风险';
  return '未知';
}

function fmt(v, digits = 2) {
  if (v == null || isNaN(v)) return '-';
  return Number(v).toFixed(digits);
}

function fmtPct(v) {
  if (v == null || isNaN(v)) return '-';
  const n = Number(v);
  return n >= 0 ? `+${n.toFixed(2)}%` : `${n.toFixed(2)}%`;
}

/* ── 展开行：买入价建议 + 止盈止损 + 风险提示 ─────────────────────── */
function ExpandedRow({ record }) {
  const { techLevels, valuationLevels, risks, buyReason, riskLevel } = record;

  return (
    <div style={{ padding: '8px 16px', background: '#fafbfc' }}>
      <Row gutter={24}>
        {/* 买入理由 */}
        <Col span={24} style={{ marginBottom: 8 }}>
          <Text strong style={{ color: '#1677ff' }}>
            <ThunderboltOutlined /> 买入理由：
          </Text>
          <Text>{buyReason || '暂无'}</Text>
        </Col>

        {/* 风险提示 */}
        <Col span={24} style={{ marginBottom: 12 }}>
          <Space wrap>
            <Tag color={riskColor(riskLevel)} style={{ fontSize: 12, padding: '2px 8px' }}>
              <WarningOutlined /> {riskLabel(riskLevel)}
            </Tag>
            {risks && risks.map((r, i) => (
              <Tag key={i} color="warning" style={{ fontSize: 11, maxWidth: 400, whiteSpace: 'normal' }}>
                {r}
              </Tag>
            ))}
          </Space>
        </Col>

        {/* 技术支撑位 */}
        <Col span={12}>
          <Card size="small" title={<Space><LineChartOutlined /> 技术支撑位</Space>} style={{ margin: 0 }}>
            <table style={{ width: '100%', fontSize: 12 }}>
              <tbody>
                {techLevels && Object.entries(techLevels).map(([k, v]) => (
                  <tr key={k}>
                    <td style={{ padding: '2px 8px', color: '#8c8c8c' }}>
                      {k === 'suggestTechPrice' ? '综合技术面价格' :
                       k === 'MA5' ? 'MA5' : k === 'MA10' ? 'MA10' :
                       k === 'MA20' ? 'MA20' : k === 'bollLower' ? '布林带下轨' :
                       k === 'low20' ? '近20日最低' : k === 'low60' ? '近60日最低' :
                       k === 'atrStop' ? 'ATR止损位' : k === 'atr14' ? 'ATR(14)' : k}
                    </td>
                    <td style={{ padding: '2px 8px', textAlign: 'right', fontWeight: k === 'suggestTechPrice' ? 600 : 400 }}>
                      {fmt(v)} 元
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </Col>

        {/* 估值支撑位 */}
        <Col span={12}>
          <Card size="small" title={<Space><FundOutlined /> 估值支撑位</Space>} style={{ margin: 0 }}>
            <table style={{ width: '100%', fontSize: 12 }}>
              <tbody>
                {valuationLevels && Object.entries(valuationLevels).map(([k, v]) => {
                  if (v == null) return null;
                  const label = k === 'suggestValuationPrice' ? '综合估值面价格' :
                    k === 'bpsPrice' ? 'BPS支撑价' : k === 'pbPrice' ? 'PB估值支撑' :
                    k === 'pePrice' ? 'PE估值支撑' : k === 'bps' ? '每股净资产' :
                    k === 'eps' ? '每股收益' : k === 'industry' ? '所属行业' :
                    k === 'industryPbMedian' ? '行业PB中位数' :
                    k === 'industryPeMedian' ? '行业PE中位数' : k;
                  return (
                    <tr key={k}>
                      <td style={{ padding: '2px 8px', color: '#8c8c8c' }}>{label}</td>
                      <td style={{ padding: '2px 8px', textAlign: 'right', fontWeight: k === 'suggestValuationPrice' ? 600 : 400 }}>
                        {typeof v === 'number' ? fmt(v) + ' 元' : String(v)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
        </Col>
      </Row>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════ */
export default function StockScreen() {
  const { message } = App.useApp();
  /* ── 可用因子 ──────────────────────────────────────────────────── */
  const [availableFactors, setAvailableFactors] = useState([]);
  const [loadingFactors, setLoadingFactors] = useState(false);

  /* ── 选股策略 ──────────────────────────────────────────────────── */
  const [strategies, setStrategies] = useState([]);
  const [selectedStrategyId, setSelectedStrategyId] = useState(null);
  const pendingRestoreStrategyName = useRef(null);

  /* ── 因子配置列表 ─────────────────────────────────────────────── */
  const [factors, setFactors] = useState([]);

  /* ── 全局处理配置 ─────────────────────────────────────────────── */
  const [globalOutlier, setGlobalOutlier] = useState('MAD');
  const [globalNormalize, setGlobalNormalize] = useState('ZSCORE');
  const [orthogonalMethod, setOrthogonalMethod] = useState('NONE');
  const [neutralizationMethod, setNeutralizationMethod] = useState('NONE');
  const [weightMode, setWeightMode] = useState('EQUAL');

  // 中性化智能推荐
  const neutralizationRecommendation = useMemo(
    () => recommendNeutralization(factors, availableFactors),
    [factors, availableFactors]
  );

  // 因子变化时自动跟随智能推荐切换中性化方式
  useEffect(() => {
    if (neutralizationRecommendation && neutralizationRecommendation.method) {
      setNeutralizationMethod(neutralizationRecommendation.method);
    }
  }, [factors]); // 仅因子列表变化时触发，用户手动改下拉不会覆盖

  /* ── 选股参数 ─────────────────────────────────────────────────── */
  const [screenDate, setScreenDate] = useState(null);
  const [screenDateRange, setScreenDateRange] = useState(null); // [startDate, endDate] 多日平均模式
  const [useMultiDayMode, setUseMultiDayMode] = useState(false); // 是否使用多日平均模式
  const [topN, setTopN] = useState(10);
  const [direction, setDirection] = useState('LONG');
  const [excludeSt, setExcludeSt] = useState(true);
  const [blacklistFilter, setBlacklistFilter] = useState(true);
  const [valuationWeight, setValuationWeight] = useState(40);
  const [customSqlWhere, setCustomSqlWhere] = useState('');
  const [maAbove30, setMaAbove30] = useState(false);
  const [maAbove60, setMaAbove60] = useState(false);
  const [maAbove100, setMaAbove100] = useState(false);

  /* ── 结果 ─────────────────────────────────────────────────────── */
  const [result, setResult] = useState(null);
  const [running, setRunning] = useState(false);
  const [resultPageSize, setResultPageSize] = useState(20);
  const [backtestModalVisible, setBacktestModalVisible] = useState(false);

  /* ── 左侧面板折叠 ────────────────────────────────────────────── */
  const [collapsed, setCollapsed] = useState(false);

  /* ── 左侧面板展开/折叠的过渡样式 ───────────────────────────────── */

  /* ── 加载可用因子 + 预设组合 + 最新日期 ───────────────────────── */
  useEffect(() => {
    setLoadingFactors(true);
    api.get('/screen/factors')
      .then(res => setAvailableFactors(res || []))
      .catch(() => {})
      .finally(() => setLoadingFactors(false));

    api.get('/screen/latest-date')
      .then(res => { if (res) setScreenDate(dayjs(res)); })
      .catch(() => { setScreenDate(dayjs('2024-12-31')); });

    api.get('/strategies', { params: { status: 'ACTIVE', size: 100 } })
      .then(res => setStrategies(res?.records || []))
      .catch(() => {});

    // 检查是否有从权重优化页面传来的配置
    const savedConfig = localStorage.getItem('factorWeightConfig');
    if (savedConfig) {
      try {
        const config = JSON.parse(savedConfig);
        // 清除配置，避免重复加载
        localStorage.removeItem('factorWeightConfig');
        
        // 应用配置
        if (config.factors && config.factors.length > 0) {
          setFactors(config.factors.map(f => ({
            factorCode: f.code,
            direction: 1,
            weight: f.weight / 100, // 百分比转小数
            filterOp: 'NONE',
            filterValue: null,
            outlierMethod: null,
            normalizeMethod: null,
          })));
          message.success(`已加载「${config.name}」的因子权重配置`);
        }
      } catch (e) {
        console.error('解析权重配置失败:', e);
      }
    }
  }, []);

  /* ── 从回测报告页跳转回填配置 ─────────────────────────────────── */
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  useEffect(() => {
    const restoreParam = searchParams.get('__restore');
    console.log('[restore] __restore 参数:', restoreParam ? '有值(长度' + restoreParam.length + ')' : '无/空');
    if (!restoreParam) return;

    try {
      const decoded = decodeURIComponent(atob(restoreParam));
      const config = JSON.parse(decoded);
      console.log('[restore] 解析后 config keys:', Object.keys(config), 'presetName:', config.presetName);

      // 清除 URL 参数（避免刷新重复回填）
      setSearchParams({}, { replace: true });

      // 回填因子列表
      if (Array.isArray(config.factors) && config.factors.length > 0) {
        const factorMap = {};
        availableFactors.forEach(af => { factorMap[af.factorCode] = af; });
        const restoredFactors = config.factors.map(f => {
          const base = factorMap[f.factorCode] || { factorCode: f.factorCode };
          return {
            factorCode: f.factorCode,
            name: base.name || f.factorCode,
            direction: f.direction ?? 1,
            weight: f.weight ?? 1,
            filterOp: f.filterOp || 'NONE',
            filterValue: f.filterValue != null ? f.filterValue : null,
            outlierMethod: f.outlierMethod || null,
            normalizeMethod: f.normalizeMethod || null,
          };
        });
        setFactors(restoredFactors);
      }

      // 回填基本参数
      if (config.direction) setDirection(config.direction);
      if (config.topN != null) setTopN(config.topN);
      if (config.excludeSt !== undefined) setExcludeSt(config.excludeSt);
      if (config.globalOutlierMethod) setGlobalOutlier(config.globalOutlierMethod);
      if (config.globalNormalizeMethod) setGlobalNormalize(config.globalNormalizeMethod);
      if (config.orthogonalizationMethod) setOrthogonalMethod(config.orthogonalizationMethod);
      if (config.weightMode) setWeightMode(config.weightMode);
      if (config.valuationWeight != null) setValuationWeight(Math.round(config.valuationWeight * 100));
      if (config.customSqlWhere) setCustomSqlWhere(config.customSqlWhere);

      // 回填选股日期
      if (config.screenStartDate && config.screenEndDate) {
        setUseMultiDayMode(true);
        setScreenDateRange([dayjs(config.screenStartDate), dayjs(config.screenEndDate)]);
      } else if (config.screenDate) {
        setUseMultiDayMode(false);
        setScreenDate(dayjs(config.screenDate));
      }

      // 回填 MA 过滤
      if (config.maPositionFilter) {
        setMaAbove30(!!config.maPositionFilter.aboveMA30);
        setMaAbove60(!!config.maPositionFilter.aboveMA60);
        setMaAbove100(!!config.maPositionFilter.aboveMA100);
      }

      // 回填选股策略名称（strategies 可能还没加载完，先存到 ref，等 strategies 就绪后再匹配）
      if (config.presetName || config.strategyName) {
        const name = config.strategyName || config.presetName;
        pendingRestoreStrategyName.current = name;
        // 立即尝试匹配一次（strategies 已加载时）
        const matched = strategies.find(p => p.strategyName === name);
        if (matched) {
          setSelectedStrategyId(matched.id);
          pendingRestoreStrategyName.current = null;
        }
      }

      message.success('已从回测报告自动回填策略配置');
    } catch (e) {
      console.error('回填配置失败:', e);
      message.error('回填配置解析失败');
      setSearchParams({}, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams.get('__restore')]);

  /* ── strategies 加载后延迟匹配选股策略回填 ───────────────────── */
  useEffect(() => {
    if (!pendingRestoreStrategyName.current || strategies.length === 0) return;
    const name = pendingRestoreStrategyName.current;
    const matched = strategies.find(p => p.strategyName === name);
    if (matched) {
      setSelectedStrategyId(matched.id);
      pendingRestoreStrategyName.current = null;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategies]);

  /* ── 选择选股策略 ─────────────────────────────────────────────── */
  const handleStrategySelect = useCallback((strategyId) => {
    setSelectedStrategyId(strategyId);
    if (!strategyId) {
      setFactors([]);
      return;
    }
    const strategy = strategies.find(s => s.id === strategyId);
    if (!strategy || !strategy.factorConfigJson) return;
    try {
      const config = JSON.parse(strategy.factorConfigJson);
      // 兼容两种格式：
      // 旧格式: [{"factorCode":"MOM20","direction":1,"weight":1.0}, ...]
      // 新格式: {"factors": [{"code":"MOM20","weight":1.0,"direction":1,"filterOp":"NONE",...}, ...]}
      const factorList = Array.isArray(config) ? config : (config.factors || []);
      const loadedFactors = factorList.map(c => ({
        factorCode: c.code || c.factorCode,
        direction: c.direction ?? 1,
        weight: c.weight ?? 1,
        filterOp: c.filterOp || 'NONE',
        filterValue: c.filterValue ?? null,
        outlierMethod: null,
        normalizeMethod: null,
      }));
      setFactors(loadedFactors);
      // 中性化方式由 useEffect 根据因子变化自动切换
      message.success(`已加载「${strategy.strategyName}」策略`);
    } catch (e) {
      message.error('策略因子配置解析失败，请检查数据格式');
    }
  }, [strategies, availableFactors]);

  /* ── 因子增删改 ───────────────────────────────────────────────── */
  const addFactor = () => {
    const used = new Set(factors.map(f => f.factorCode));
    const next = availableFactors.find(f => !used.has(f.factorCode));
    if (!next) { message.info('所有可用因子已添加'); return; }
    setFactors(prev => [...prev, DEFAULT_FACTOR(next.factorCode)]);
  };

  const removeFactor = (idx) =>
    setFactors(prev => prev.filter((_, i) => i !== idx));

  const updateFactor = (idx, patch) =>
    setFactors(prev => prev.map((f, i) => i === idx ? { ...f, ...patch } : f));

  const autoWeight = () => {
    if (factors.length === 0) return;
    const avg = +(1 / factors.length).toFixed(4);
    setFactors(prev => prev.map(f => ({ ...f, weight: avg })));
  };

  const totalWeight = useMemo(() =>
    factors.reduce((s, f) => s + Math.abs(f.weight || 0), 0), [factors]);

  /* ── 执行选股 ─────────────────────────────────────────────────── */
  const handleRun = useCallback(() => {
    if (factors.length === 0) { message.warning('请至少添加一个因子'); return; }
    if (totalWeight === 0)    { message.warning('因子权重合计不能为0'); return; }

    setRunning(true);
    setResult(null);

    const payload = {
      // 多日平均模式：传 screenStartDate + screenEndDate；单日模式：传 screenDate
      ...(useMultiDayMode && screenDateRange?.[0] && screenDateRange?.[1]
        ? {
            screenStartDate: screenDateRange[0].format('YYYY-MM-DD'),
            screenEndDate:   screenDateRange[1].format('YYYY-MM-DD'),
          }
        : { screenDate: screenDate ? screenDate.format('YYYY-MM-DD') : null }),
      globalOutlierMethod: globalOutlier,
      globalNormalizeMethod: globalNormalize,
      orthogonalizationMethod: orthogonalMethod,
      neutralizationMethod,
      weightMode,
      topN,
      direction,
      excludeSt,
      valuationWeight: valuationWeight / 100,
      customSqlWhere: customSqlWhere || null,
      strategyId: selectedStrategyId || null,
      blacklistFilter: blacklistFilter || null,
      maPositionFilter: (maAbove30 || maAbove60 || maAbove100) ? {
        aboveMA30:  maAbove30  || null,
        aboveMA60:  maAbove60  || null,
        aboveMA100: maAbove100 || null,
      } : null,
      factors: factors.map(f => ({
        factorCode: f.factorCode,
        direction: f.direction,
        weight: f.weight,
        filterOp: f.filterOp,
        filterValue: f.filterOp !== 'NONE' ? f.filterValue : null,
        outlierMethod: f.outlierMethod || null,
        normalizeMethod: f.normalizeMethod || null,
      })),
    };

    api.post('/screen/run', payload, { timeout: 180000 })
      .then(res => {
        setResult(res);
        setCollapsed(true);
        message.success(`选股完成，共选出 ${res?.stocks?.length ?? 0} 只股票`);
      })
      .catch((err) => {
        if (err.message && !err.response) {
          message.error('请求超时或网络异常，请稍后重试（MA均线过滤计算量较大，可能需要2-3分钟）');
        }
      })
      .finally(() => setRunning(false));
  }, [factors, screenDate, screenDateRange, useMultiDayMode, topN, direction, excludeSt, globalOutlier, globalNormalize, orthogonalMethod, neutralizationMethod, weightMode, totalWeight, valuationWeight, selectedStrategyId, customSqlWhere, maAbove30, maAbove60, maAbove100]);

  /* ── 构建当前选股配置（供回测弹窗使用） ──────────────────────── */
  const buildScreenRequest = useCallback(() => ({
    ...(useMultiDayMode && screenDateRange?.[0] && screenDateRange?.[1]
      ? {
          screenStartDate: screenDateRange[0].format('YYYY-MM-DD'),
          screenEndDate:   screenDateRange[1].format('YYYY-MM-DD'),
        }
      : { screenDate: screenDate ? screenDate.format('YYYY-MM-DD') : null }),
    globalOutlierMethod: globalOutlier,
    globalNormalizeMethod: globalNormalize,
    orthogonalizationMethod: orthogonalMethod,
    neutralizationMethod,
    weightMode,
    topN,
    direction,
    excludeSt,
    valuationWeight: valuationWeight / 100,
    customSqlWhere: customSqlWhere || null,
    strategyId: selectedStrategyId || null,
    blacklistFilter: blacklistFilter || null,
    strategyName: selectedStrategyId
      ? (strategies.find(p => p.id === selectedStrategyId)?.strategyName || null)
      : null,
    maPositionFilter: (maAbove30 || maAbove60 || maAbove100) ? {
      aboveMA30:  maAbove30  || null,
      aboveMA60:  maAbove60  || null,
      aboveMA100: maAbove100 || null,
    } : null,
    factors: factors.map(f => ({
      factorCode: f.factorCode,
      direction: f.direction,
      weight: f.weight,
      filterOp: f.filterOp,
      filterValue: f.filterOp !== 'NONE' ? f.filterValue : null,
    })),
  }), [factors, screenDate, screenDateRange, useMultiDayMode, topN, direction, excludeSt, globalOutlier, globalNormalize, orthogonalMethod, neutralizationMethod, weightMode, totalWeight, valuationWeight, selectedStrategyId, customSqlWhere, maAbove30, maAbove60, maAbove100]);

  /* ── 结果表格列 ───────────────────────────────────────────────── */
  const isMultiDay = !!(result?.screenStartDate && result?.screenEndDate);
  const factorColumns = useMemo(() => (result?.factors || []).map(fw => ({
    title: (
      <Tooltip title={`权重: ${fw.weight}  方向: ${fw.direction >= 0 ? '正向' : '反向'}`}>
        <span>{fw.factorCode} <InfoCircleOutlined style={{ fontSize: 11, color: '#aaa' }} /></span>
      </Tooltip>
    ),
    key: fw.factorCode,
    width: isMultiDay ? 120 : 100,
    align: 'center',
    render: (_, row) => {
      const rank = row.factorRanks?.[fw.factorCode];
      const val = row.factorValues?.[fw.factorCode];
      const trend = row.factorTrends?.[fw.factorCode]; // 多日模式才有
      if (rank == null) {
        // 多日模式下 rank 为空通常是因为 CV 稳定性过滤
        const hint = isMultiDay ? '因子值波动过大(CV过滤)，已从该因子排名排除' : '暂无因子数据';
        if (val != null) {
          // 有原始值但无排名 → CV 过滤（保留原始值展示）
          return (
            <Tooltip title={`${hint}｜原始值: ${Number(val).toFixed(4)}`}>
              <Text type="secondary">{Number(val).toFixed(2)}</Text>
            </Tooltip>
          );
        }
        return <Tooltip title={hint}><Text type="secondary">-</Text></Tooltip>;
      }
      const pct = Math.min(100, Math.max(0, Math.round(rank * 100)));
      // 趋势高亮逻辑：|trend| > 0.3 认为变化显著
      const absTrend = trend != null ? Math.abs(trend) : 0;
      const trendSignificant = absTrend > 0.3;
      let trendColor = '#888';
      if (trendSignificant) trendColor = trend > 0 ? '#52c41a' : '#ff4d4f';
      return (
        <div>
          <Tooltip title={`原始值: ${val != null ? Number(val).toFixed(4) : '-'}`}>
            <Progress
              percent={pct}
              size="small"
              strokeColor={pct >= 70 ? '#52c41a' : pct >= 40 ? '#1677ff' : '#ff4d4f'}
              format={p => `${p}%`}
            />
          </Tooltip>
          {trend != null && isMultiDay && (
            <Tooltip title={`趋势动量: ${(trend * 100).toFixed(1)}% (${trend > 0 ? '改善' : '恶化'})`}>
              <span style={{
                fontSize: 10, color: trendColor,
                fontWeight: trendSignificant ? 600 : 400,
                display: 'inline-block',
                background: trendSignificant ? (trend > 0 ? '#f6ffed' : '#fff2f0') : 'transparent',
                padding: '0 3px', borderRadius: 3, marginTop: 2
              }}>
                {trend > 0 ? '↑' : '↓'}{Math.abs(trend * 100).toFixed(1)}%
              </span>
            </Tooltip>
          )}
        </div>
      );
    },
  })), [result, isMultiDay]);

  const resultColumns = useMemo(() => [
    {
      title: '排名', dataIndex: 'rank', key: 'rank', width: 55, align: 'center', fixed: 'left',
      render: v => <Tag color={v <= 3 ? 'red' : v <= 10 ? 'orange' : 'default'} style={{ minWidth: 28, textAlign: 'center' }}>{v}</Tag>,
    },
    {
      title: '股票代码', dataIndex: 'symbol', key: 'symbol', width: 110, fixed: 'left',
      render: v => <Tag color="geekblue">{v}</Tag>,
    },
    { title: '股票名称', dataIndex: 'name', key: 'name', width: 90, ellipsis: true, fixed: 'left' },
    {
      title: '综合得分', dataIndex: 'compositeScore', key: 'score', width: 110,
      sorter: (a, b) => b.compositeScore - a.compositeScore,
      render: v => (
        <Progress
          percent={Math.round(v * 100)}
          size="small"
          strokeColor={scoreColor(v)}
          style={{ width: 70 }}
        />
      ),
    },
    {
      title: '当前价', dataIndex: 'currentPrice', key: 'currentPrice', width: 75, align: 'right',
      render: v => v != null ? <Text strong>¥{fmt(v)}</Text> : <Text type="secondary">-</Text>,
    },
    {
      title: (
        <Tooltip title="综合估值面(40%)和技术面(60%)计算的建议买入区间">
          <Space size={4}><SafetyCertificateOutlined /> 建议买入价</Space>
        </Tooltip>
      ),
      key: 'suggestPrice', width: 140, align: 'center',
      render: (_, row) => {
        if (!row.suggestPrice) return <Text type="secondary">-</Text>;
        const low = row.suggestPriceLow ?? row.suggestPrice;
        const high = row.suggestPriceHigh ?? row.suggestPrice;
        const deviation = row.currentPrice
          ? ((row.currentPrice - row.suggestPrice) / row.currentPrice * 100).toFixed(1)
          : null;
        return (
          <Tooltip title={deviation != null ? `距当前价偏离: ${Number(deviation) >= 0 ? '+' : ''}${deviation}%` : ''}>
            <div>
              <Text strong style={{ color: '#1677ff', fontSize: 13 }}>{fmt(row.suggestPrice)}</Text>
              <br />
              <Text type="secondary" style={{ fontSize: 11 }}>{fmt(low)} ~ {fmt(high)}</Text>
            </div>
          </Tooltip>
        );
      },
    },
    {
      title: (
        <Tooltip title="基于 ATR(14) 计算：止损=当前价-2×ATR，第一止盈=+2×ATR，第二止盈=+3×ATR">
          <Space size={4}><WarningOutlined /> 止损</Space>
        </Tooltip>
      ),
      key: 'stopLoss', width: 75, align: 'center',
      render: (_, row) => {
        if (!row.stopLoss) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`止损幅度: ${fmtPct(row.stopLossPercent)}`}>
            <Text type="danger">{fmt(row.stopLoss)}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: '止盈1', key: 'tp1', width: 75, align: 'center',
      render: (_, row) => {
        if (!row.takeProfit1) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`止盈幅度: ${fmtPct(row.takeProfit1Percent)}`}>
            <Text type="success">{fmt(row.takeProfit1)}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: '止盈2', key: 'tp2', width: 75, align: 'center',
      render: (_, row) => {
        if (!row.takeProfit2) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`止盈幅度: ${fmtPct(row.takeProfit2Percent)}`}>
            <Text style={{ color: '#52c41a' }}>{fmt(row.takeProfit2)}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: (
        <span>
          风险
          <Tooltip
            title={
              <div style={{ maxWidth: 360, lineHeight: 1.7 }}>
                <div style={{ fontWeight: 600, marginBottom: 6 }}>风险等级判定逻辑</div>
                <div>基于<strong>波动率(σ)</strong>和<strong>最大回撤</strong>两个维度，将候选股票分为低/中/高三档：</div>
                <div style={{ marginTop: 6 }}>
                  <Tag color="green" size="small">低风险</Tag> 年化波动率 &lt; 25% 且近20日最大回撤 &lt; 10%
                </div>
                <div style={{ marginTop: 4 }}>
                  <Tag color="orange" size="small">中风险</Tag> 年化波动率 25%~40% 或近20日最大回撤 10%~20%
                </div>
                <div style={{ marginTop: 4 }}>
                  <Tag color="red" size="small">高风险</Tag> 年化波动率 &gt; 40% 或近20日最大回撤 &gt; 20%
                </div>
                <Divider style={{ margin: '8px 0' }} />
                <div style={{ fontSize: 12, color: '#888' }}>
                  💡 高风险 ≠ 不能买。波动率高的股票弹性大，适合短线/波段策略；低风险适合稳健持仓。建议结合「止损」列和自身风险承受能力决策。
                </div>
              </div>
            }
          >
            <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4, fontSize: 12, cursor: 'pointer' }} />
          </Tooltip>
        </span>
      ),
      key: 'risk', width: 80, align: 'center',
      render: (_, row) => {
        if (!row.riskLevel) return <Text type="secondary">-</Text>;
        return <Tag color={riskColor(row.riskLevel)} style={{ fontSize: 11 }}>{riskLabel(row.riskLevel)}</Tag>;
      },
    },
    ...factorColumns,
  ], [result, factorColumns]);

  /* ── 大盘温度计提示 ──────────────────────── */
  const { data: thData, status: thStatus } = useMarketThermometer();

  /* ══════════════════════════════════════════════════════════════ */
  return (
    <div style={{ width: '100%' }}>
      {/* 页头 */}
      <div style={{ marginBottom: 4 }}>
        <Title level={4} style={{ margin: '0 0 8px' }}>因子选股</Title>
      </div>
      <Tabs
        defaultActiveKey="multifactor"
        size="small"
        tabBarExtraContent={{
          right: (
              <Space size="small">
                <Badge
                  color={totalWeight > 0 ? (Math.abs(totalWeight - 1) < 0.01 ? 'green' : 'orange') : 'red'}
                  text={`权重合计: ${totalWeight.toFixed(2)}`}
                />
                {thData && thStatus && (
                  <Tooltip title={`大盘${thStatus.label}（${thData.fearGreedIndex?.toFixed(0)}°），${thStatus.action}`}>
                    <Tag color={thStatus.label === '极度贪婪' ? 'red' : thStatus.label === '极度恐慌' ? 'green' : 'blue'}>
                      <Link to="/market-thermometer" style={{ color: 'inherit' }}>{thStatus.label} {thData.fearGreedIndex?.toFixed(0)}°</Link>
                    </Tag>
                  </Tooltip>
                )}
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  size="small"
                  loading={running}
                  onClick={handleRun}
                  disabled={factors.length === 0}
                >
                  {running ? '选股中' : '执行选股'}
                </Button>
              </Space>
            ),
          }}
        items={[
          {
            key: 'multifactor',
            label: <span><FilterOutlined /> 多因子选股</span>,
            children: (
      <div style={{ display: 'flex', gap: 16, width: '100%' }}>
        {/* ══ 左侧配置区（通过 width 过渡折叠）══ */}
        <div style={{
          flexShrink: 0,
          width: collapsed ? 0 : '54.17%',
          overflow: 'hidden',
          opacity: collapsed ? 0 : 1,
          transition: 'width 0.4s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.25s ease 0.15s',
          pointerEvents: collapsed ? 'none' : 'auto',
        }}>
          {/* ── 选股策略选择 ─────────────────────────────────────── */}
          <Card
            title={<Space><StarOutlined /> 选股策略</Space>}
            style={{ marginBottom: 16 }}
            styles={{ body: { padding: '12px 16px 12px' } }}
          >
            <Row gutter={[12, 12]} align="middle">
              <Col flex="auto">
                <Select
                  value={selectedStrategyId}
                  onChange={handleStrategySelect}
                  style={{ width: '100%' }}
                  size="small"
                  placeholder="选择策略加载因子配置"
                  allowClear
                  popupMatchSelectWidth={false}
                  popupRender={(menu) => <div style={{ paddingBottom: 16 }}>{menu}</div>}
                >
                  {strategies.map(s => (
                    <Option key={s.id} value={s.id}>
                      <span style={{ display: 'inline-flex', alignItems: 'center' }}>
                        <span>{s.strategyName}</span>
                        {s.description && (
                          <Tooltip title={s.description}>
                            <QuestionCircleOutlined
                              style={{ color: '#91caff', fontSize: 13, marginLeft: 6 }}
                              onMouseDown={(e) => e.stopPropagation()}
                              onClick={(e) => e.stopPropagation()}
                            />
                          </Tooltip>
                        )}
                      </span>
                    </Option>
                  ))}
                </Select>
              </Col>
              <Col flex="none">
                <div style={paramLabelStyle}> </div>
                <Space size={4} style={{ height: 24, alignItems: 'center' }}>
                  <Tooltip title="到策略管理页面编辑或创建策略">
                    <Button
                      size="small" icon={<EditOutlined />}
                      onClick={() => navigate(selectedStrategyId ? `/strategies/${selectedStrategyId}/edit` : '/strategies')}
                      style={{ height: 24 }}
                    />
                  </Tooltip>
                </Space>
              </Col>
            </Row>
          </Card>

          {/* ── 因子选择表格 ─────────────────────────────────────── */}
          <Card
            title={<Space><FilterOutlined />因子选择</Space>}
            extra={
              <Space size="small">
                <Button size="small" icon={<SwapOutlined />} onClick={autoWeight}>均分权重</Button>
                <Button
                  size="small" type="primary" icon={<PlusOutlined />}
                  onClick={addFactor} disabled={loadingFactors || availableFactors.length === 0}
                >
                  添加因子
                </Button>
                <Button size="small" icon={<CopyOutlined />}
                  tooltip={selectedStrategyId ? '复制当前因子选股配置JSON，粘贴到统一回测页使用' : '请先选择选股策略'}
                  disabled={!selectedStrategyId}
                  onClick={() => {
                    const cfg = buildScreenRequest();
                    const clean = {
                      topN: cfg.topN,
                      direction: cfg.direction,
                      excludeSt: cfg.excludeSt,
                      valuationWeight: cfg.valuationWeight,
                      globalOutlierMethod: cfg.globalOutlierMethod,
                      globalNormalizeMethod: cfg.globalNormalizeMethod,
                      factors: cfg.factors,
                    };
                    navigator.clipboard.writeText(JSON.stringify(clean, null, 2)).then(
                      () => message.success('配置JSON已复制'),
                    );
                  }}>
                  复制配置
                </Button>
              </Space>
            }
            style={{ marginBottom: 16 }}
            styles={{ body: { padding: '0 0 8px' } }}
          >
            {factors.length === 0 ? (
              <Empty description='选择选股策略或点击"添加因子"开始配置' style={{ padding: '32px 0' }} />
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr style={{ background: '#fafafa', borderBottom: '1px solid #f0f0f0' }}>
                    <th style={{ ...thStyle, width: 110 }}>因子名称</th>
                    <th style={{ ...thStyle, width: 76 }}>
                      <Tooltip title="正向：值越大越好；反向：值越小越好">
                        方向 <QuestionCircleOutlined style={{ color: '#aaa' }} />
                      </Tooltip>
                    </th>
                    <th style={{ ...thStyle, width: 108 }}>筛选条件</th>
                    <th style={{ ...thStyle, width: 72 }}>阈值</th>
                    <th style={{ ...thStyle, width: 64 }}>权重</th>
                    <th style={{ ...thStyle, width: 32 }}></th>
                  </tr>
                </thead>
                <tbody>
                  {factors.map((f, idx) => {
                    const info = availableFactors.find(x => x.factorCode === f.factorCode);
                    return (
                      <tr key={idx} style={{ borderBottom: '1px solid #f5f5f5' }}>
                        <td style={tdStyle}>
                          <Tooltip title={info ? `${info.factorName}（${CATEGORY_LABEL[info.category] || info.category}）` : f.factorCode}>
                            <Select
                              value={f.factorCode}
                              size="small"
                              style={{ width: '100%' }}
                              loading={loadingFactors}
                              onChange={v => updateFactor(idx, { factorCode: v })}
                              optionLabelProp="label"
                            >
                              {availableFactors.map(af => (
                                <Option
                                  key={af.factorCode}
                                  value={af.factorCode}
                                  label={af.factorCode}
                                  disabled={factors.some((sf, si) => si !== idx && sf.factorCode === af.factorCode)}
                                >
                                  <Space size={4}>
                                    <Tag
                                      color={CATEGORY_COLOR[af.category] || 'default'}
                                      style={{ fontSize: 10, padding: '0 3px', margin: 0 }}
                                    >
                                      {CATEGORY_LABEL[af.category] || af.category}
                                    </Tag>
                                    <span>{af.factorCode}</span>
                                  </Space>
                                </Option>
                              ))}
                            </Select>
                          </Tooltip>
                          {info && (
                            <div style={{ fontSize: 10, color: '#999', marginTop: 2, lineHeight: '12px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                              {info.factorName}
                            </div>
                          )}
                        </td>
                        <td style={tdStyle}>
                          <Select
                            value={f.direction} size="small" style={{ width: 72 }}
                            onChange={v => updateFactor(idx, { direction: v })}
                            optionLabelProp="label"
                          >
                            <Option value={1} label={<span style={{ color: '#1677ff', fontWeight: 500 }}>↑ 正向</span>}>
                              <Space size={4}>
                                <Tag color="blue" style={{ margin: 0, fontSize: 11 }}>正向</Tag>
                                <span style={{ fontSize: 12, color: '#595959' }}>越大越好</span>
                              </Space>
                            </Option>
                            <Option value={-1} label={<span style={{ color: '#ff4d4f', fontWeight: 500 }}>↓ 反向</span>}>
                              <Space size={4}>
                                <Tag color="red" style={{ margin: 0, fontSize: 11 }}>反向</Tag>
                                <span style={{ fontSize: 12, color: '#595959' }}>越小越好</span>
                              </Space>
                            </Option>
                          </Select>
                        </td>
                        <td style={tdStyle}>
                          <Select
                            value={f.filterOp} size="small" style={{ width: 100 }}
                            onChange={v => updateFactor(idx, { filterOp: v, filterValue: null })}
                          >
                            {FILTER_OP_OPTIONS.map(o => (
                              <Option key={o.value} value={o.value}>{o.label}</Option>
                            ))}
                          </Select>
                        </td>
                        <td style={tdStyle}>
                          <InputNumber
                            value={f.filterValue} size="small" style={{ width: 68 }}
                            disabled={f.filterOp === 'NONE'}
                            onChange={v => updateFactor(idx, { filterValue: v })}
                            placeholder="阈值"
                          />
                        </td>
                        <td style={tdStyle}>
                          <InputNumber
                            value={f.weight} size="small"
                            min={0} max={10} step={0.1}
                            style={{ width: 58 }}
                            onChange={v => updateFactor(idx, { weight: v ?? 1 })}
                          />
                        </td>
                        <td style={{ ...tdStyle, textAlign: 'center' }}>
                          <Button
                            type="text" size="small" danger icon={<DeleteOutlined />}
                            onClick={() => removeFactor(idx)}
                          />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </Card>

          {/* ── 选股参数 ─────────────────────────────────────────── */}
          <Card title="选股参数" style={{ marginBottom: 16 }} styles={{ body: { padding: '16px 16px 4px' } }}>
            {/* 第一行：选股日期（独立） */}
            <Row style={{ marginBottom: 12 }}>
              <Col span={24}>
                <div style={paramLabelStyle}>选股日期</div>
                <Space size={8}>
                  <Button
                    size="small"
                    type={!useMultiDayMode ? 'primary' : 'default'}
                    onClick={() => { setUseMultiDayMode(false); }}
                  >单日</Button>
                  <Button
                    size="small"
                    type={useMultiDayMode ? 'primary' : 'default'}
                    onClick={() => { setUseMultiDayMode(true); }}
                  >多日</Button>
                  <Tooltip
                    title={
                      <div style={{ width: 480, lineHeight: 1.8 }}>
                        <div style={{ fontWeight: 600, marginBottom: 4 }}>单日模式（默认）</div>
                        <div>取<strong>某一天</strong>的因子值快照做截面筛选。</div>
                        <div style={{ color: '#52c41a' }}>✓ 信号灵敏，能捕捉短期异动</div>
                        <div style={{ color: '#ff4d4f' }}>✗ 受单日噪声/异常值影响大</div>
                        <Divider style={{ margin: '10px 0' }} />

                        <div style={{ fontWeight: 600, marginBottom: 4 }}>多日模式（最新值 + 稳定性过滤）</div>
                        <div>取范围内<strong>最新一天</strong>的因子值做筛选，同时计算该范围内的<strong>变异系数(CV)</strong>，CV 过高说明因子波动剧烈、不稳定，予以剔除。</div>
                        <div style={{ color: '#52c41a' }}>✓ 保留单日灵敏度 + 过滤噪声票</div>
                        <div style={{ color: '#ff4d4f' }}>✗ 强势异动股（如连续涨停）CV 也高，可能被误杀</div>
                        <Divider style={{ margin: '10px 0' }} />

                        <div style={{ fontWeight: 600, marginBottom: 4 }}>📈 趋势动量（Trend）— 仅多日模式显示</div>
                        <div>在结果表每个因子的进度条下方，额外展示该因子在选定时间范围内的<strong>变化方向与幅度</strong>。</div>
                        <div style={{ background: '#fafafa', padding: '6px 8px', borderRadius: 4, marginTop: 4 }}>
                          <div><strong>公式：</strong>trend = (结束日因子值 - 起始日因子值) / |起始日因子值|</div>
                          <div><strong>含义：</strong>正值表示因子在该区间内走强（如 RSI 从 40→65），负值表示走弱。</div>
                          <div><strong>高亮：</strong>|trend| &gt; 30% 时彩色标注 —— 绿色↑=持续改善，红色↓=持续恶化；灰色=平稳无大幅波动。</div>
                          <div><strong>作用：</strong>帮助判断该股票的因子得分是"趋势性改善"还是"偶然跳升"，辅助区分真假信号。例如一只股票 PE 因子排名靠前但 trend 为红色↓，说明它在靠估值回归临时上位，持续性存疑。</div>
                        </div>
                        <Divider style={{ margin: '10px 0' }} />

                        <div style={{ fontSize: 12, color: '#888', background: '#fafafa', padding: '6px 8px', borderRadius: 4 }}>
                          💡 建议：日常快速选股用「单日」；市场波动剧烈或验证策略时切「多日」
                        </div>
                      </div>
                    }
                    placement="bottom"
                    styles={{ root: { maxWidth: 530 } }}
                  >
                    <QuestionCircleOutlined style={{ cursor: 'pointer', color: '#999', fontSize: 14 }} />
                  </Tooltip>
                  {!useMultiDayMode ? (
                    <DatePicker
                      value={screenDate} onChange={(d) => { setScreenDate(d); }}
                      placeholder="最新可用"
                      style={{ width: 240 }} size="small"
                    />
                  ) : (
                    <DatePicker.RangePicker
                      value={screenDateRange}
                      onChange={(dates) => { setScreenDateRange(dates); }}
                      placeholder={['开始日期', '结束日期']}
                      style={{ width: 360 }} size="small"
                    />
                  )}
                </Space>
              </Col>
            </Row>

            {/* 第二行：持仓数量 | 极值处理 */}
            <Row gutter={[12, 12]}>
              <Col span={12}>
                <div style={paramLabelStyle}>持仓数量</div>
                <InputNumber
                  value={topN} onChange={v => setTopN(v ?? 10)}
                  min={5} max={500} style={{ width: '100%' }} size="small"
                  addonAfter="只"
                />
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  极值处理
                  <Tooltip title="对每个因子的截面数据先做极值压缩，再做标准化">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={globalOutlier} onChange={setGlobalOutlier} style={{ width: '100%' }} size="small">
                  {OUTLIER_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 第三行：选股方向 | 标准化处理 */}
              <Col span={12}>
                <div style={paramLabelStyle}>选股方向</div>
                <Select value={direction} onChange={setDirection} style={{ width: '100%' }} size="small">
                  <Option value="LONG">做多（高分优先）</Option>
                  <Option value="SHORT">做空（低分优先）</Option>
                </Select>
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  标准化处理
                  <Tooltip title="将极值处理后的因子值映射到统一量纲，再按权重合成综合得分">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={globalNormalize} onChange={setGlobalNormalize} style={{ width: '100%' }} size="small">
                  {NORMALIZE_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 第四行：剔除ST股 | 过滤黑名单 */}
              <Col span={12}>
                <div style={paramLabelStyle}>剔除ST股</div>
                <Button
                  size="small"
                  type={excludeSt ? 'primary' : 'default'}
                  onClick={() => setExcludeSt(!excludeSt)}
                  style={{ width: '100%' }}
                >
                  {excludeSt ? '✓ 剔除' : '不剔除'}
                </Button>
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  过滤黑名单
                  <Tooltip title="排除已加入黑名单的股票（黑名单管理在「数据管理 → 黑名单管理」）">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Button
                  size="small"
                  type={blacklistFilter ? 'primary' : 'default'}
                  onClick={() => setBlacklistFilter(v => !v)}
                  style={{ width: '100%' }}
                >
                  {blacklistFilter ? '✓ 过滤' : '不过滤'}
                </Button>
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  因子正交化
                  <Tooltip title="对标准化后的因子值做正交化处理，消除因子间共线性（如动量类因子MOM5/MOM10/MOM20高度相关），使综合得分更均衡">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={orthogonalMethod} onChange={setOrthogonalMethod} style={{ width: '100%' }} size="small">
                  {ORTHOGONAL_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 第五行：中性化方式 | 权重模式 */}
              <Col span={12}>
                <div style={paramLabelStyle}>
                  <SafetyCertificateOutlined style={{ color: '#1677ff' }} /> 中性化方式
                  <Tooltip title="行业中性化：在每个申万一级行业内减均值，避免估值/财务因子被行业结构主导；市值中性化：按市值分5组减均值，避免流动性/波动率因子被大小盘主导；双重中性化：先行业后市值，因子最纯净">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                  {neutralizationRecommendation && neutralizationRecommendation.method !== neutralizationMethod && (
                    <Tag
                      color="orange"
                      style={{ marginLeft: 4, fontSize: 10, cursor: 'pointer', lineHeight: '16px' }}
                      onClick={() => setNeutralizationMethod(neutralizationRecommendation.method)}
                    >
                      推荐: {NEUTRALIZATION_OPTIONS.find(o => o.value === neutralizationRecommendation.method)?.label}
                    </Tag>
                  )}
                </div>
                <Select
                  value={neutralizationMethod}
                  onChange={setNeutralizationMethod}
                  style={{ width: '100%' }}
                  size="small"
                >
                  {NEUTRALIZATION_OPTIONS.map(o => (
                    <Option key={o.value} value={o.value}>
                      {o.label === '不中性化' && neutralizationRecommendation && neutralizationRecommendation.method !== 'NONE' ? (
                        <span>{o.label} <Text type="secondary" style={{ fontSize: 11 }}>（不推荐）</Text></span>
                      ) : o.label}
                    </Option>
                  ))}
                </Select>
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  <ThunderboltOutlined /> 权重模式
                  <Tooltip title="等权：使用下方配置的静态权重；IC动态加权：根据每个因子近60日IC均值自动调整权重；IR动态加权：根据近60日IR（信噪比）自动调整权重。IC<0的因子会被置零或反转方向">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={weightMode} onChange={setWeightMode} style={{ width: '100%' }} size="small">
                  {WEIGHT_MODE_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 自定义 SQL 条件（高级模式） */}
              <Col span={24}>
                <div style={paramLabelStyle}>
                  <Space size={4}>
                    <ThunderboltOutlined />
                    <span>自定义 SQL 条件（高级模式）</span>
                    <Tag color="purple" size="small">进阶</Tag>
                  </Space>
                  <Tooltip title="直接用 SQL WHERE 条件过滤候选股票池，适用于有数据库经验的用户。条件将作用于 stock_daily 表，可使用 close、volume、turnover、change_percent 等字段。仅支持 AND 连接的条件">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <TextArea
                  value={customSqlWhere}
                  onChange={e => setCustomSqlWhere(e.target.value)}
                  placeholder={'例如: close > 10 AND volume > 1000000 AND change_percent > -5\n\n可用字段: code, open, high, low, close, volume, turnover, change_percent, change_amount'}
                  autoSize={{ minRows: 2, maxRows: 4 }}
                  allowClear
                  style={{ fontSize: 12, fontFamily: 'monospace' }}
                />
              </Col>

              {/* MA 均线位置过滤 */}
              <Col span={24}>
                <div style={paramLabelStyle}>
                  <Space size={4}>
                    <RiseOutlined />
                    <span>均线位置过滤</span>
                    <Tag color="blue" size="small">多头</Tag>
                  </Space>
                  <Tooltip title="要求当前价格在指定均线上方，过滤掉处于下降趋势的股票。MA30≈1.5月、MA60≈3月、MA100≈5月">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Space size={8} wrap>
                  <Button
                    size="small"
                    type={maAbove30 ? 'primary' : 'default'}
                    onClick={() => setMaAbove30(v => !v)}
                    style={{ minWidth: 72 }}
                  >
                    {maAbove30 ? '✓ ' : ''}价格 &gt; MA30
                  </Button>
                  <Button
                    size="small"
                    type={maAbove60 ? 'primary' : 'default'}
                    onClick={() => setMaAbove60(v => !v)}
                    style={{ minWidth: 72 }}
                  >
                    {maAbove60 ? '✓ ' : ''}价格 &gt; MA60
                  </Button>
                  <Button
                    size="small"
                    type={maAbove100 ? 'primary' : 'default'}
                    onClick={() => setMaAbove100(v => !v)}
                    style={{ minWidth: 72 }}
                  >
                    {maAbove100 ? '✓ ' : ''}价格 &gt; MA100
                  </Button>
                </Space>
                {(maAbove30 || maAbove60 || maAbove100) && (
                  <div style={{ marginTop: 4, fontSize: 11, color: '#faad14' }}>
                    ⚠ 均线过滤会对每只候选股票单独加载历史行情，候选池较大时耗时会增加
                  </div>
                )}
              </Col>

              {/* 估值/技术加权比例 */}
              <Col span={24}>
                <div style={paramLabelStyle}>
                  买入价加权比例
                  <Tooltip title="控制估值面和技术面在建议买入价计算中的权重分配">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Row gutter={8} align="middle">
                  <Col span={4}><Text style={{ fontSize: 12 }}>估值 {(valuationWeight)}%</Text></Col>
                  <Col span={16}>
                    <Slider
                      min={0} max={100} value={valuationWeight}
                      onChange={setValuationWeight}
                      marks={{ 0: '纯技术', 40: '均衡', 100: '纯估值' }}
                      tooltip={{ formatter: v => `估值 ${v}% / 技术 ${100 - v}%` }}
                    />
                  </Col>
                  <Col span={4}><Text style={{ fontSize: 12, textAlign: 'right' }}>技术 {(100 - valuationWeight)}%</Text></Col>
                </Row>
              </Col>
            </Row>
          </Card>
        </div>

        {/* ══ 右侧结果区 ════════════════════════════════════════════ */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {running && (
            <Card>
              <div style={{ textAlign: 'center', padding: '80px 0' }}>
                <Spin size="large" tip="正在执行多因子选股...">
                  <div />
                </Spin>
              </div>
            </Card>
          )}

          {!running && !result && (
            <Card>
              <Empty
                description={
                  <span>选择选股策略或配置因子，点击 <Text strong>执行选股</Text> 获取结果</span>
                }
                style={{ padding: '80px 0' }}
              />
            </Card>
          )}

          {!running && result && (
            <>
              {/* 汇总统计 */}
              <Card style={{ marginBottom: 16 }}>
                <Row gutter={16} align="middle">
                  <Col span={6}>
                    <Statistic
                      title="选股日期"
                      value={result.screenStartDate ? `${result.screenStartDate} ~ ${result.screenEndDate}` : result.screenDate}
                      valueStyle={{ fontSize: 13 }}
                    />
                  </Col>
                  <Col span={6}>
                    <Statistic title="候选股票" value={result.candidateCount} suffix="只" />
                  </Col>
                  <Col span={6}>
                    <Statistic
                      title="选出数量" value={result.stocks?.length ?? 0} suffix="只"
                      valueStyle={{ color: '#1677ff' }}
                    />
                  </Col>
                  <Col span={6}>
                    <Statistic title="使用因子" value={result.factors?.length ?? 0} suffix="个" />
                  </Col>
                </Row>

                {(result.factorFilterPass || result.factorCoverage) && (
                  <>
                    <Divider style={{ margin: '12px 0' }} />
                    {result.factorFilterPass && Object.keys(result.factorFilterPass).length > 0 && (
                      <div style={{ marginBottom: result.factorCoverage ? 6 : 0 }}>
                        <span style={{ fontSize: 12, color:'#888', marginRight: 8 }}>筛选通过：</span>
                        <Row gutter={[8, 6]} wrap>
                          {Object.entries(result.factorFilterPass).map(([code, cnt]) => (
                            <Col key={`fp-${code}`}>
                              <Tooltip title={`${code}：${cnt} 只股票通过筛选条件`}>
                                <Tag color={cnt > 500 ? 'red' : cnt > 100 ? 'orange' : cnt > 20 ? 'green' : 'blue'}>
                                  {code}: {cnt} 只
                                </Tag>
                              </Tooltip>
                            </Col>
                          ))}
                        </Row>
                      </div>
                    )}
                    {result.factorCoverage && Object.keys(result.factorCoverage).length > 0 && (
                      <div>
                        <span style={{ fontSize: 12, color:'#aaa', marginRight: 8 }}>数据覆盖：</span>
                        <Row gutter={[8, 4]} wrap>
                          {Object.entries(result.factorCoverage).map(([code, cnt]) => (
                            <Col key={`cov-${code}`}>
                              <Tooltip title={`${code}：${cnt} 只股票有因子数据`}>
                                <span style={{ fontSize: 11, color: '#bbb', cursor: 'default' }}>
                                  {code}({cnt})
                                </span>
                              </Tooltip>
                            </Col>
                          ))}
                        </Row>
                      </div>
                    )}
                  </>
                )}
              </Card>

              {/* 结果表格 */}
              <Card
                title={
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 }}>
                    <span style={{ marginRight: 4 }}>筛选结果</span>
                    <Tag color="blue" style={{ marginRight: 0 }}>{direction === 'LONG' ? '做多' : '做空'}</Tag>
                    <Tag color="purple">{OUTLIER_OPTIONS.find(o => o.value === globalOutlier)?.label}</Tag>
                    <Tag color="cyan">{NORMALIZE_OPTIONS.find(o => o.value === globalNormalize)?.label}</Tag>
                    {orthogonalMethod !== 'NONE' && (
                      <Tag color="magenta">{ORTHOGONAL_OPTIONS.find(o => o.value === orthogonalMethod)?.label}</Tag>
                    )}
                    {weightMode !== 'EQUAL' && (
                      <Tag color="volcano">{WEIGHT_MODE_OPTIONS.find(o => o.value === weightMode)?.label}</Tag>
                    )}
                  </div>
                }
                extra={
                  <Space size="small">
                    {result && (
                      <Button type="primary" size="small" icon={<PlayCircleOutlined />}
                        onClick={() => setBacktestModalVisible(true)}>
                        滚动回测
                      </Button>
                    )}
                    <Button
                      size="small"
                      icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                      onClick={() => setCollapsed(c => !c)}
                    >
                      {collapsed ? '展开参数' : '折叠参数'}
                    </Button>
                    <Button size="small" icon={<ReloadOutlined />} onClick={handleRun} loading={running}>
                      刷新
                    </Button>
                  </Space>
                }
              >
                <Table
                  dataSource={result.stocks}
                  columns={resultColumns}
                  rowKey="symbol"
                  size="small"
                  scroll={{ x: 800 + (result.factors?.length ?? 0) * 100 }}
                  pagination={{
                    pageSize: resultPageSize,
                    showSizeChanger: true,
                    pageSizeOptions: ['10', '20', '50', '100'],
                    showTotal: t => `共 ${t} 只`,
                    onChange: (page, size) => setResultPageSize(size),
                  }}
                  rowClassName={(_, i) => i < 3 ? 'top10-row' : ''}
                  expandable={{
                    expandedRowRender: (record) => <ExpandedRow record={record} />,
                    rowExpandable: (record) => !!record.suggestPrice,
                    expandIcon: ({ expanded, onExpand, record }) =>
                      record.suggestPrice ? (
                        expanded
                          ? <MinusSquareOutlined style={{ color: '#1677ff', fontSize: 16, cursor: 'pointer' }} onClick={e => onExpand(record, e)} />
                          : <PlusSquareOutlined style={{ color: '#8c8c8c', fontSize: 16, cursor: 'pointer' }} onClick={e => onExpand(record, e)} />
                      ) : null,
                  }}
                />
              </Card>
            </>
          )}
        </div>
      </div>
            ),
          },
          {
            key: 'chan',
            label: <span><StockOutlined /> 缠论结构筛选</span>,
            children: <ChanScreenTab />,
          },
        ]}
      />

      {/* ── 滚动选股回测弹窗 ─────────────────────────────────────── */}
      <RollingBacktestModal
        visible={backtestModalVisible}
        onClose={() => setBacktestModalVisible(false)}
        screenConfig={buildScreenRequest()}
      />
    </div>
  );
}

/* ── 样式常量 ──────────────────────────────────────────────────────── */
const thStyle = {
  padding: '9px 10px',
  fontWeight: 500,
  fontSize: 12,
  color: '#595959',
  textAlign: 'left',
  whiteSpace: 'nowrap',
};
const tdStyle = {
  padding: '9px 10px',
  verticalAlign: 'middle',
};
const paramLabelStyle = {
  fontSize: 12,
  color: '#595959',
  marginBottom: 4,
  display: 'flex',
  alignItems: 'center',
};

/* ── 缠论筛选辅助函数（从 ChanScreen.js 迁移）─────────────────────── */
const chanFmtVal = (v, prec = 2) => {
  if (v == null) return '-';
  const n = Number(v);
  if (Number.isNaN(n)) return v;
  return n.toFixed(prec);
};
const chanPenDirTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  return n > 0
    ? <Tag color="red">▲ 上升</Tag>
    : <Tag color="green">▼ 下降</Tag>;
};
const chanTrendTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  if (n === 1)  return <Tag color="red">  上涨</Tag>;
  if (n === 0)  return <Tag color="blue"> 盘整</Tag>;
  return <Tag color="green">下跌</Tag>;
};
const chanBuySellTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  if (n > 0) return <Tag color="volcano">{n}买</Tag>;
  if (n < 0) return <Tag color="cyan">  {Math.abs(n)}卖</Tag>;
  return '-';
};
const getChanTagRenderer = (code) => {
  const c = code.toUpperCase();
  if (c === 'CHAN_PEN_DIR')   return chanPenDirTag;
  if (c === 'CHAN_TREND')     return chanTrendTag;
  if (c === 'CHAN_BUY_SELL')  return chanBuySellTag;
  return (v) => v == null ? '-' : <Text>{chanFmtVal(v, 3)}</Text>;
};

/* ── 缠论筛选 Tab 组件 ─────────────────────────────────────────────── */
function ChanScreenTab() {
  const [meta, setMeta]                 = useState(null);
  const [metaLoading, setMetaLoading]   = useState(true);
  const [checkboxFilters, setCheckboxFilters] = useState({});
  const [rangeFilters, setRangeFilters]       = useState({});
  const [keyword, setKeyword]                 = useState('');
  const [page, setPage]                       = useState(1);
  const [pageSize, setPageSize]               = useState(20);
  const [loading, setLoading]   = useState(false);
  const [data, setData]         = useState(null);
  const [error, setError]       = useState(null);
  const [helpVisible, setHelpVisible] = useState(false);

  useEffect(() => {
    factorApi.chanScreenMeta()
      .then(res => {
        setMeta(res);
        const defaultRanges = {};
        const defaultCheckbox = {};
        (res.factors || []).forEach(f => {
          if (f.controlType === 'slider') {
            defaultRanges[f.code] = [f.min ?? 0, f.max ?? 100];
          } else if (f.controlType === 'checkbox') {
            defaultCheckbox[f.code] = [];
          }
        });
        setRangeFilters(defaultRanges);
        setCheckboxFilters(defaultCheckbox);
      })
      .catch(() => message.error('加载缠论因子元数据失败，请稍后重试'))
      .finally(() => setMetaLoading(false));
  }, []);

  const buildChanParams = useCallback((newPage, newPageSize) => {
    const params = {};
    Object.entries(checkboxFilters).forEach(([code, vals]) => {
      if (vals && vals.length > 0) {
        const p = code.toUpperCase();
        if (p === 'CHAN_PEN_DIR')   params.penDir   = vals.join(',');
        if (p === 'CHAN_TREND')     params.trend    = vals.join(',');
        if (p === 'CHAN_BUY_SELL')  params.buySell  = vals.join(',');
      }
    });
    Object.entries(rangeFilters).forEach(([code, range]) => {
      if (!range) return;
      const p = code.toUpperCase();
      if (p === 'CHAN_HUB_POS') {
        if (range[0] > 0)  params.hubPosMin   = range[0];
        if (range[1] < 1)  params.hubPosMax   = range[1];
      }
      if (p === 'CHAN_PEN_COUNT') {
        if (range[0] > 1)  params.penCountMin = range[0];
        if (range[1] < 100) params.penCountMax = range[1];
      }
    });
    if (keyword.trim()) params.keyword = keyword.trim();
    params.page = newPage - 1;
    params.size = newPageSize;
    return params;
  }, [checkboxFilters, rangeFilters, keyword]);

  const doChanSearch = useCallback((newPage = 1, newPageSize = pageSize) => {
    setLoading(true);
    setError(null);
    setPage(newPage);
    setPageSize(newPageSize);
    const params = buildChanParams(newPage, newPageSize);
    factorApi.chanScreen(params)
      .then(res => setData(res))
      .catch(e => setError(e.message || '筛选失败'))
      .finally(() => setLoading(false));
  }, [buildChanParams, pageSize]);

  const buildChanColumns = useCallback(() => {
    if (!meta || !meta.columns) return [];
    const baseCols = [
      { title: '代码', dataIndex: 'ts_code', key: 'ts_code', width: 100,
        render: v => <Text strong copyable={{ text: v }}>{v}</Text> },
      { title: '名称', dataIndex: 'name', key: 'name', width: 90, ellipsis: true },
      { title: '数据日期', dataIndex: 'calc_date', key: 'date', width: 110 },
    ];
    const factorCols = (meta.columns || []).map(col => ({
      title: col.title, dataIndex: col.dataIndex, key: col.key, width: 90,
      render: (val, row) => { const tagFn = getChanTagRenderer(col.key); return tagFn(row[col.dataIndex]); },
    }));
    return [...baseCols, ...factorCols];
  }, [meta]);

  const renderChanFilterControls = () => {
    if (!meta || !meta.factors) return null;
    return meta.factors.map(factor => {
      if (factor.controlType === 'checkbox') {
        return (
          <Col span={12} key={factor.code}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{factor.name}</Text>
            <Checkbox.Group
              options={factor.options || []}
              value={checkboxFilters[factor.code] || []}
              onChange={(vals) => setCheckboxFilters(prev => ({ ...prev, [factor.code]: vals }))}
              style={{ gap: 16, flexWrap: 'wrap' }}
            />
            {!(checkboxFilters[factor.code] || []).length && (
              <Text type="secondary" style={{ fontSize: 12 }}>（不限）</Text>
            )}
          </Col>
        );
      }
      if (factor.controlType === 'slider') {
        const range = rangeFilters[factor.code] || [factor.min ?? 0, factor.max ?? 100];
        const p = factor.code.toUpperCase();
        const isPercent = p === 'CHAN_HUB_POS';
        const isPenCount = p === 'CHAN_PEN_COUNT';
        return (
          <Col span={12} key={factor.code}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
              {factor.name}：{isPercent
                ? `${range[0].toFixed(2)} ~ ${range[1].toFixed(2)}`
                : `${range[0]} ~ ${range[1]}`
              }
            </Text>
            <Slider
              range
              min={factor.min ?? 0}
              max={isPercent ? 1 : (isPenCount ? 100 : 100)}
              step={isPercent ? 0.01 : 1}
              value={range}
              onChange={(vals) => setRangeFilters(prev => ({ ...prev, [factor.code]: vals }))}
              tooltip={{ formatter: v => isPercent ? v.toFixed(2) : v }}
              style={{ maxWidth: 400 }}
            />
          </Col>
        );
      }
      return null;
    });
  };

  const total = data?.total || 0;
  const list  = data?.list  || [];

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>
          <StockOutlined style={{ marginRight: 8 }} />
          缠论结构筛选
        </Title>
        <Tooltip title="查看使用说明">
          <QuestionCircleOutlined
            style={{ fontSize: 16, color: '#1677ff', cursor: 'pointer', flexShrink: 0 }}
            onClick={() => setHelpVisible(true)}
          />
        </Tooltip>
      </div>

      {metaLoading ? (
        <Card size="small" style={{ marginBottom: 16 }}><Spin tip="加载因子定义..."><div /></Spin></Card>
      ) : (
        <>
          <Card size="small" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 12]}>
              {renderChanFilterControls()}
              <Col span={12}>
                <Space>
                  <Input placeholder="搜索代码或名称关键词" value={keyword}
                    onChange={e => setKeyword(e.target.value)} onPressEnter={() => doChanSearch(1)}
                    style={{ width: 220 }} allowClear />
                  <Button type="primary" icon={<FilterOutlined />} onClick={() => doChanSearch(1)} loading={loading}>筛选</Button>
                  <Button icon={<ReloadOutlined />} onClick={() => {
                    const defaults = {}; const cbDefaults = {};
                    (meta?.factors || []).forEach(f => {
                      if (f.controlType === 'slider') defaults[f.code] = [f.min ?? 0, f.max ?? (f.code.toUpperCase() === 'CHAN_HUB_POS' ? 1 : 100)];
                      else cbDefaults[f.code] = [];
                    });
                    setCheckboxFilters(cbDefaults); setRangeFilters(defaults); setKeyword(''); setData(null);
                  }}>重置</Button>
                </Space>
              </Col>
            </Row>
          </Card>

          <Card title={`筛选结果（共 ${total} 只）`} size="small">
            <Spin spinning={loading}>
              {!data && !error ? (
                <Empty description="请设置筛选条件后点击「筛选」" />
              ) : error ? (
                <Text type="danger">{error}</Text>
              ) : (
                <Table dataSource={list} columns={buildChanColumns()} rowKey="ts_code" size="small"
                  pagination={{
                    current: page, pageSize: pageSize, total: total,
                    showSizeChanger: true, pageSizeOptions: ['10', '20', '50'],
                    onChange: (p, ps) => doChanSearch(p, ps),
                  }}
                  scroll={{ x: 700 }} />
              )}
            </Spin>
          </Card>
        </>
      )}

      <Modal title="缠论结构筛选 · 使用说明" open={helpVisible} onCancel={() => setHelpVisible(false)}
        footer={null} width={900}>
        <div style={{ fontSize: 13, lineHeight: 1.8 }}>
          <Alert type="info" showIcon style={{ marginBottom: 16 }} message="这是做什么的"
            description="缠论结构筛选是基于缠论理论，从全市场股票中筛选出处于特定「结构状态」的标的。
            比如：当前有哪些股票刚出现「1买」买点？哪些处于「上涨走势+中枢上半区」？
            省去逐个翻图分析的时间，直接给出符合条件的股票清单。" />
          {meta && meta.factors && meta.factors.length > 0 && (
            <>
              <Title level={5}>筛选维度说明</Title>
              <ul style={{ paddingLeft: 20 }}>
                {meta.factors.map(f => (
                  <li key={f.code}>
                    <Text strong>{f.name}</Text>
                    {f.description && <Text type="secondary">：{f.description}</Text>}
                    {f.options && f.options.length > 0 && (
                      <Text type="secondary">（{f.options.map(o => o.label).join(' / ')}）</Text>
                    )}
                  </li>
                ))}
              </ul>
            </>
          )}
          <Title level={5}>使用流程</Title>
          <ul style={{ paddingLeft: 20 }}>
            <li>确保缠论因子已计算（因子管理 → 因子监控，确认缠论因子有数据）</li>
            <li>设置筛选条件（各条件间为「且」关系，建议先宽松再逐步收紧）</li>
            <li>点击「筛选」查看结果，可用关键词进一步过滤</li>
            <li>结合买卖点信号和中枢位置辅助买卖决策</li>
          </ul>
          <Alert type="warning" showIcon style={{ marginTop: 8 }} message="增减缠论因子的影响"
            description="缠论筛选页面已改为动态化，新增/删除/重命名缠论因子后自动生效，无需修改代码。
            但新增因子需要在因子计算引擎中实现计算逻辑，且在「因子管理」中激活后才会出现在筛选中。" />
        </div>
      </Modal>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════ */

