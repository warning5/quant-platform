import React, { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { Card, Table, Button, Tag, Select, Space, Statistic, Row, Col, Typography, Tooltip, Spin, message, Progress, DatePicker, Divider, Modal, Popconfirm, Switch } from 'antd';
import dayjs from 'dayjs';
import { ThunderboltOutlined, ReloadOutlined, LineChartOutlined, StockOutlined, RiseOutlined, FallOutlined, MinusOutlined, QuestionCircleOutlined, RadarChartOutlined, StopOutlined, UnlockOutlined } from '@ant-design/icons';
import { recommendationApi, blacklistApi, confidenceApi } from '../../api';
import api from '../../api';
import ReactEcharts from 'echarts-for-react';

const { Title, Text } = Typography;

// ── 市场环境配色 ──
const REGIME_CONFIG = {
  BULL:     { color: '#cf1322', bg: '#fff1f0', text: '牛市', icon: <RiseOutlined /> },
  BEAR:     { color: '#3f8600', bg: '#f6ffed', text: '熊市', icon: <FallOutlined /> },
  SIDEWAYS: { color: '#597ef7', bg: '#f0f5ff', text: '震荡', icon: <MinusOutlined /> },
  NEUTRAL:  { color: '#597ef7', bg: '#f0f5ff', text: '中性', icon: <MinusOutlined /> }, // 兼容旧数据
};

// ── 操作建议配色 ──
const ACTION_CONFIG = {
  BUY:  { color: 'red',    text: '买入' },
  HOLD: { color: 'blue',   text: '持有' },
  SELL: { color: 'green',  text: '卖出' },
};

// ── 高相关行业分组（与后端 RecommendationService.INDUSTRY_CORR_GROUPS 一致） ──
const INDUSTRY_CORR_GROUPS = [
  ['银行', '非银金融'],
  ['房地产开发', '房地产服务', '建筑装饰', '建筑材料'],
  ['煤炭', '石油石化', '电力设备'],
  ['食品饮料', '农林牧渔', '纺织服饰'],
  ['计算机', '通信', '传媒'],
  ['汽车', '机械设备'],
  ['医药生物', '公用事业'],
  ['电子', '国防军工'],
];
const CORR_GROUP_LABEL = {
  '银行': '金融板块',
  '房地产开发': '地产链',
  '煤炭': '能源链',
  '食品饮料': '消费链',
  '计算机': 'TMT',
  '汽车': '制造链',
  '医药生物': '防御板块',
  '电子': '科技制造',
};

// ── 市值格式化 ──
function formatMarketCap(val) {
  if (!val) return '-';
  if (val >= 1e12) return (val / 1e12).toFixed(1) + '万亿';
  if (val >= 1e8) return (val / 1e8).toFixed(1) + '亿';
  if (val >= 1e4) return (val / 1e4).toFixed(1) + '万';
  return val.toFixed(0);
}

// ── 因子元信息 ──
const factorMeta = {
  MOM20: { cat: '动量', desc: '20日涨幅' },
  MOM5: { cat: '动量', desc: '5日涨幅' },
  MTM6: { cat: '动量', desc: '6日动量' },
  VOL20: { cat: '波动', desc: '年化波动率' },
  VAL_PE_TTM: { cat: '价值', desc: '市盈率TTM' },
  VAL_PB: { cat: '价值', desc: '市净率' },
  VAL_PS_TTM: { cat: '价值', desc: '市销率TTM' },
  VAL_DIVIDEND_YIELD: { cat: '价值', desc: '股息率' },
  RSI14: { cat: '技术', desc: '14日RSI' },
  MACD: { cat: '技术', desc: 'MACD离差值' },
  TURN20: { cat: '流动性', desc: '20日换手率' },
  FIN_EARNINGS_QUALITY: { cat: '财务', desc: '盈利质量' },
  FIN_DEBT_TO_ASSET: { cat: '财务', desc: '资产负债率' },
  FIN_REVENUE_QUALITY: { cat: '财务', desc: '营收质量' },
  FIN_NET_PROFIT_YOY: { cat: '成长', desc: '净利润同比增长率' },
  FIN_REVENUE_TTM_YOY: { cat: '成长', desc: '营收同比增长率' },
};

// ── IC 诊断动作配色 ──
const DIAG_CONFIG = {
  KEPT:    { color: '#52c41a', text: '参与加权', bg: '#f6ffed' },
  DROPPED: { color: '#ff4d4f', text: '已剔除',   bg: '#fff1f0' },
  REVERSED:{ color: '#fa8c16', text: '方向反转', bg: '#fff7e6' },
  NO_DATA: { color: '#d9d9d9', text: '无数据',   bg: '#fafafa' },
};

export default function RecommendationList() {
  const [recommendations, setRecommendations] = useState([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [regime, setRegime] = useState(null);
  const [indexInfo, setIndexInfo] = useState(null);
  const [weightInfo, setWeightInfo] = useState(null); // Phase 2: 动态权重
  const [strategies, setStrategies] = useState([]);
  const [selectedStrategyId, setSelectedStrategyId] = useState(null);
  const [screenDate, setScreenDate] = useState(null);
  const [factorDiagnostics, setFactorDiagnostics] = useState(null); // IC加权诊断
  const [diagExpanded, setDiagExpanded] = useState(false); // 已剔除/异常面板默认收起
  const [weightMode, setWeightMode] = useState('STATIC'); // 权重模式: STATIC(固定) / IC(动态IC)
  const [icDataDate, setIcDataDate] = useState(null); // IC数据可用日期
  const [enableConfidenceControl, setEnableConfidenceControl] = useState(() => {
    const saved = localStorage.getItem('confidenceControlEnabled');
    return saved !== null ? JSON.parse(saved) : true; // 默认开启
  }); // 置信度控制开关
  const [batchHistory, setBatchHistory] = useState(null); // 历史表现汇总（按策略+日期）
  const [topBottom, setTopBottom] = useState(null); // 当前策略+日期的最佳/最差
  const [trackingLoading, setTrackingLoading] = useState(false); // 追踪触发中
  const [qualityTag, setQualityTag] = useState(null); // 当前策略+日期质量标签
  // 复盘筛选状态（按策略隔离）
  const [reviewStrategyId, setReviewStrategyId] = useState(null);
  const [reviewDate, setReviewDate] = useState(null);
  const [strategyDates, setStrategyDates] = useState([]); // 筛选策略对应的可用日期列表
  const [strategiesWithData, setStrategiesWithData] = useState([]); // 有推荐数据的策略列表

  // ── 方案B: 黑名单状态 ──
  const [blacklist, setBlacklist] = useState([]); // 当前黑名单列表
  const [blacklistModalVisible, setBlacklistModalVisible] = useState(false); // 黑名单管理弹窗
  const [blacklistLoading, setBlacklistLoading] = useState(false);
  const [addBlacklistReason, setAddBlacklistReason] = useState(''); // 手动添加原因
  const [addBlacklistDays, setAddBlacklistDays] = useState(30); // 手动添加天数

  // ── 方案C: 策略置信度状态 ──
  const [confidenceData, setConfidenceData] = useState(null); // 当前策略的置信度
  const [allConfidence, setAllConfidence] = useState([]); // 所有策略的置信度列表（用于策略下拉展示）
  const [confidenceWarningVisible, setConfidenceWarningVisible] = useState(false); // 低置信度警告弹窗

  // 加载策略列表（含复盘筛选用）
  useEffect(() => {
    api.get('/strategies', { params: { status: 'ACTIVE', size: 100 } })
      .then(res => {
        const list = res?.records || [];
        setStrategies(list);
        if (list.length > 0 && !selectedStrategyId) {
          setSelectedStrategyId(list[0].id);
        }
      })
      .catch(() => {});
    // 加载有推荐数据的策略列表（用于复盘筛选下拉）
    recommendationApi.strategiesWithData()
      .then(ids => {
        setStrategiesWithData(ids);
        if (ids.length > 0 && !reviewStrategyId) {
          setReviewStrategyId(ids[0]);
        }
      })
      .catch(() => {});
  }, []);

  const renderFactorTable = (strategy) => {
    if (!strategy || !strategy.factorConfigJson) return null;
    let factors = [];
    try {
      const parsed = JSON.parse(strategy.factorConfigJson);
      factors = Array.isArray(parsed) ? parsed : (parsed.factors || []);
    } catch { return null; }
    if (factors.length === 0) return null;

    return (
      <div style={{ fontSize: 12, lineHeight: '20px' }}>
        <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>{strategy.strategyName} 包含因子</div>
        <table style={{ borderCollapse: 'collapse', fontSize: 11 }}>
          <tbody>
            {factors.map((f, idx) => {
              const code = f.factorCode || f.code || '';
              const meta = factorMeta[code];
              const dir = f.direction ?? f.dir ?? 1;
              return (
                <tr key={idx}>
                  <td style={{ padding: '1px 6px 1px 0', color: '#8c8c8c' }}>{meta?.cat || ''}</td>
                  <td style={{ padding: '1px 6px', fontFamily: 'monospace', fontWeight: 500 }}>{code}</td>
                  <td style={{ padding: '1px 6px', color: dir > 0 ? '#cf1322' : '#3f8600' }}>{dir > 0 ? '正向' : '反向'}</td>
                  <td style={{ padding: '1px 0', color: '#595959' }}>权重{(f.weight ?? 1).toFixed(1)}</td>
                  <td style={{ padding: '1px 0 1px 6px', color: '#8c8c8c' }}>{meta?.desc || ''}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  };

  // 加载推荐数据
  const loadRecommendations = useCallback(async (sid, date) => {
    setLoading(true);
    try {
      let data;
      if (sid && date) {
        data = await recommendationApi.getByStrategyAndDate(sid, date);
      } else {
        data = await recommendationApi.getLatest();
      }
      const list = Array.isArray(data) ? data : [];
      list.sort((a, b) => (b.finalScore || 0) - (a.finalScore || 0));
      setRecommendations(list);
      if (list.length > 0) {
        // 同步复盘筛选状态到当前查看的数据
        setReviewStrategyId(list[0].strategyId);
        setReviewDate(list[0].recommendDate);
        setRegime(list[0].regime);
        setIndexInfo({
          close: list[0].indexClose,
          ma20: list[0].indexMa20,
          ma60: list[0].indexMa60,
        });
        setWeightInfo({
          factorWeight: list[0].factorWeight,
          analysisWeight: list[0].analysisWeight,
        });
      } else {
        setRegime(null);
        setIndexInfo(null);
      }
    } catch { /* ignore */ }
    setLoading(false);
  }, []);

  useEffect(() => {
    loadRecommendations(null);
  }, [loadRecommendations]);

  // 生成推荐
  const handleGenerate = async () => {
    if (!selectedStrategyId) {
      message.warning('请先选择策略');
      return;
    }
    setGenerating(true);
    try {
      const dateStr = screenDate ? dayjs(screenDate).format('YYYY-MM-DD') : null;
      const result = await recommendationApi.generate(dateStr, 20, selectedStrategyId, weightMode, enableConfidenceControl);
      message.success(`推荐列表生成成功: ${result.count} 只`);
      setFactorDiagnostics(weightMode === 'IC' ? (result.factorDiagnostics || null) : null);
      setIcDataDate(weightMode === 'IC' ? (result.icDataDate || null) : null);
      await loadRecommendations(null);
    } catch (e) {
      message.error('生成失败: ' + (e.message || '未知错误'));
    }
    setGenerating(false);
  };

  // 置信度控制开关变化
  const handleConfidenceControlChange = (checked) => {
    setEnableConfidenceControl(checked);
    localStorage.setItem('confidenceControlEnabled', JSON.stringify(checked));
  };

  // 切换复盘策略
  const handleReviewStrategyChange = (value) => {
    setReviewStrategyId(value);
    setReviewDate(null); // 切策略时清空日期，等 batchHistory 加载后自动选最新
  };

  // 切换复盘日期
  const handleReviewDateChange = (dateStr) => {
    setReviewDate(dateStr);
  };

  // 加载批次历史表现（支持按策略筛选）
  useEffect(() => {
    const load = async () => {
      try {
        const hist = await recommendationApi.getBatchHistory(20, reviewStrategyId);
        setBatchHistory(hist);
        if (hist && hist.length > 0 && !reviewDate) {
          setReviewDate(hist[hist.length - 1].recommendDate);
        }
      } catch { /* ignore */ }
    };
    load();
  }, [reviewStrategyId]);

  // 当 reviewStrategyId + reviewDate 变化时加载最佳/最差，并取质量标签
  useEffect(() => {
    if (!reviewStrategyId || !reviewDate) { setTopBottom(null); setQualityTag(null); return; }
    const load = async () => {
      try {
        const tb = await recommendationApi.getBatchTopBottom(reviewStrategyId, reviewDate);
        setTopBottom(tb);
      } catch { setTopBottom(null); }
      if (batchHistory && batchHistory.length > 0) {
        const entry = batchHistory.find(b => b.recommendDate === reviewDate);
        if (entry && entry.qualityTag) {
          setQualityTag(entry.qualityTag);
        } else {
          setQualityTag(null);
        }
      } else {
        setQualityTag(null);
      }
    };
    load();
  }, [reviewStrategyId, reviewDate, batchHistory]);

  // 手动触发表现追踪
  const handleTrack = async () => {
    setTrackingLoading(true);
    try {
      const res = await recommendationApi.trackPerformance();
      message.success(`表现追踪完成，更新 ${res.updated} 条记录`);
      // 刷新推荐列表
      loadRecommendations(reviewStrategyId, reviewDate);
      // 刷新历史命中率趋势图、质量标签、复盘数据
      try {
        const hist = await recommendationApi.getBatchHistory(20, reviewStrategyId);
        setBatchHistory(hist);
      } catch { /* ignore */ }
      if (reviewStrategyId && reviewDate) {
        try {
          const tb = await recommendationApi.getBatchTopBottom(reviewStrategyId, reviewDate);
          setTopBottom(tb);
        } catch { /* ignore */ }
      }
    } catch (e) {
      message.error('追踪失败: ' + (e.message || '未知错误'));
    }
    setTrackingLoading(false);
  };

  // ── 方案B: 黑名单操作函数 ──
  const handleManualBlacklist = async (rec) => {
    if (!selectedStrategyId) {
      message.warning('请先选择策略');
      return;
    }
    try {
      await blacklistApi.add(
        selectedStrategyId,
        rec.stockCode.replace(/\.(SH|SZ)$/i, ''),
        rec.stockName,
        addBlacklistReason || '手动屏蔽',
        addBlacklistDays
      );
      message.success(`${rec.stockName} 已加入黑名单`);
      loadBlacklist();
    } catch (e) {
      message.error('加入黑名单失败: ' + (e.message || '未知错误'));
    }
  };

  const handleRemoveFromBlacklist = async (id) => {
    try {
      await blacklistApi.removeById(id);
      message.success('已从黑名单移除');
      loadBlacklist();
    } catch (e) {
      message.error('解封失败: ' + (e.message || '未知错误'));
    }
  };

  const handleClearBlacklist = async () => {
    if (!selectedStrategyId) return;
    try {
      await blacklistApi.clearAll(selectedStrategyId);
      message.success('已清空全部黑名单');
      loadBlacklist();
    } catch (e) {
      message.error('清空失败: ' + (e.message || '未知错误'));
    }
  };

  const loadBlacklist = async () => {
    if (!selectedStrategyId) return;
    setBlacklistLoading(true);
    try {
      const list = await blacklistApi.getList(selectedStrategyId, true); // 包含已过期
      setBlacklist(list || []);
    } catch { /* ignore */ }
    setBlacklistLoading(false);
  };

  // 原因中文映射
  const REASON_MAP = {
    CONSECUTIVE_LOSS: { label: '连续失利', color: 'orange', desc: '连续N次推荐次日收益为负' },
    LOW_HIT_RATE: { label: '低命中率', color: 'gold', desc: '近N次推荐命中率过低' },
    SEVERE_LOSS: { label: '踩雷', color: 'red', desc: '单日跌幅过大(≥8%)' },
    MANUAL: { label: '手动屏蔽', color: 'blue', desc: '用户手动加入' },
  };
  const isExpired = (until) => until && dayjs(until).isBefore(dayjs(), 'day');

  // ── 方案C: 置信度操作函数 ──
  const loadConfidence = async (strategyId) => {
    if (!strategyId) return;
    try {
      const data = await confidenceApi.getLatest(strategyId);
      setConfidenceData(data);
    } catch { /* ignore */ }
  };

  const loadAllConfidence = async () => {
    try {
      const list = await confidenceApi.getAllLatest();
      setAllConfidence(list || []);
    } catch { /* ignore */ }
  };

  // 当策略切换时自动加载置信度
  useEffect(() => {
    if (selectedStrategyId) loadConfidence(selectedStrategyId);
  }, [selectedStrategyId]);

  // 首次加载时获取所有策略置信度（用于策略下拉展示）
  useEffect(() => {
    loadAllConfidence();
  }, []);

  // 置信度等级配置
  const CONFIDENCE_CONFIG = {
    HIGH:       { color: '#3f8600', bg: '#f6ffed', text: '高', icon: '✅' },
    NORMAL:     { color: '#597ef7', bg: '#f0f5ff', text: '中等', icon: '📊' },
    LOW:        { color: '#fa8c16', bg: '#fff7e6', text: '偏低', icon: '⚠️' },
    SUSPENDED:  { color: '#cf1322', bg: '#fff1f0', text: '建议暂停', icon: '🛑' },
    UNTRAINED:  { color: '#8c8c8c', bg: '#fafafa', text: '暂无数据', icon: '❓' },
  };

  /** 获取某策略的置信度（用于下拉列表显示） */
  const getConfidenceForStrategy = (strategyId) => {
    if (!allConfidence || allConfidence.length === 0) return null;
    return allConfidence.find(c => c.strategyId === strategyId) || null;
  };

  const rc = regime ? REGIME_CONFIG[regime] : null;

  const columns = [
    {
      title: '#',
      width: 45,
      fixed: 'left',
      render: (_v, _r, index) => <Text strong>{index + 1}</Text>,
    },
    {
      title: '代码',
      dataIndex: 'stockCode',
      width: 95,
      fixed: 'left',
      render: (v) => {
        const pureCode = v ? v.replace(/\.(SH|SZ)$/i, '') : v;
        return <Link to={`/stock-analysis?code=${pureCode}`}>{pureCode}</Link>;
      },
    },
    {
      title: '名称',
      dataIndex: 'stockName',
      width: 90,
      fixed: 'left',
      ellipsis: true,
    },
    {
      title: '综合得分',
      dataIndex: 'finalScore',
      width: 95,
      sorter: (a, b) => a.finalScore - b.finalScore,
      defaultSortOrder: 'descend',
      render: (v) => {
        const pct = v * 100;
        let color = '#597ef7';
        if (pct >= 80) color = '#cf1322';
        else if (pct >= 60) color = '#fa8c16';
        else if (pct < 30) color = '#8c8c8c';
        return (
          <div>
            <Text strong style={{ color }}>{(v * 100).toFixed(1)}</Text>
            <Progress percent={pct} showInfo={false} size="small" strokeColor={color} style={{ marginTop: 2 }} />
          </div>
        );
      },
    },
    {
      title: <Tooltip title="多因子选股百分位得分(0~100)，越高越好">因子得分</Tooltip>,
      dataIndex: 'factorScore',
      width: 75,
      render: (v) => v != null ? <Text type="secondary">{(v * 100).toFixed(1)}</Text> : '-',
    },
    {
      title: <Tooltip styles={{ root: {maxWidth: 360} }} title={
        <div style={{ fontSize: 12, lineHeight: '20px' }}>
          <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>个股六维度综合得分（满分134）</div>
          <div style={{ marginBottom: 4 }}>技术（满分30）：缠论信号+MACD+RSI+均线</div>
          <div style={{ marginBottom: 4 }}>资金（满分25）：主力净流入+换手率+资金流向</div>
          <div style={{ marginBottom: 4 }}>事件（满分25）：涨停炸板率+龙虎榜+舆情</div>
          <div style={{ marginBottom: 4 }}>基本面（满分29）：PE/PB估值+盈利质量+财务健康</div>
          <div style={{ marginBottom: 4 }}>风险（满分15）：最大回撤+波动率+ATR/价格比</div>
          <div style={{ marginBottom: 4 }}>流动性（满分10）：20日均成交额+换手率适中度</div>
        </div>
      }>分析得分</Tooltip>,
      dataIndex: 'analysisScore',
      width: 75,
      render: (v, rec) => {
        if (v == null && rec.technicalScore == null) return '-';
        const detail = (
          <div style={{ fontSize: 12, lineHeight: '20px' }}>
            <div>技术: {rec.technicalScore ?? '-'}/30</div>
            <div>资金: {rec.capitalScore ?? '-'}/25</div>
            <div>事件: {rec.eventScore ?? '-'}/25</div>
            <div>基本面: {rec.fundamentalScore ?? '-'}/29</div>
            <div style={{ borderTop: '1px solid #434343', marginTop: 2, paddingTop: 2 }}>风险: {rec.riskScore ?? '-'}/15</div>
            <div>流动性: {rec.liquidityScore ?? '-'}/10</div>
          </div>
        );
        return (
          <Tooltip styles={{ root: {maxWidth: 360} }} title={detail}>
            <Text>{v != null ? v : '-'}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: '建议',
      dataIndex: 'actionTag',
      width: 60,
      render: (v) => {
        const cfg = ACTION_CONFIG[v];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : '-';
      },
    },
    {
      title: (
        <Tooltip
          styles={{ root: {maxWidth: 360} }}
          title={
            <div style={{ fontSize: 12, lineHeight: '20px' }}>
              <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>行业 Regime 图标说明</div>
              <div style={{ marginBottom: 4 }}>📈 牛市（BULL）：行业指数 MA20 &gt; MA60，趋势向上</div>
              <div style={{ marginBottom: 4 }}>📉 熊市（BEAR）：行业指数 MA20 &lt; MA60，趋势向下</div>
              <div style={{ marginBottom: 4 }}>↔ 震荡（SIDEWAYS）：MA20 与 MA60 接近，无明确趋势</div>
              <div style={{ marginTop: 8, fontWeight: 'bold', fontSize: 13 }}>行业动量标签说明</div>
              <div style={{ marginBottom: 4 }}>🔥 强势行业（近5日 z-score &gt; 0.3）：近5日平均涨幅显著跑赢大盘，选股上限放宽至 6 只</div>
              <div style={{ marginBottom: 4 }}>❄️ 弱势行业（近5日 z-score &lt; -0.3）：近5日平均涨幅显著跑输大盘，选股上限收紧至 1 只</div>
              <div style={{ marginBottom: 4 }}>无标签：中等强度（-0.3 ~ 0.3），选股上限默认 3 只</div>
              <div style={{ marginTop: 8, color: '#8c8c8c' }}>行业分散化限制：强势行业最多 6 只，中等 3 只，弱势 1 只（基于近5日平滑均值，避免单日极端值）</div>
              <div style={{ marginTop: 8, fontWeight: 'bold', fontSize: 13, color: '#b37feb' }}>高相关行业分组说明</div>
              <div style={{ marginBottom: 4 }}>🏷 紫色标签 = 该股票所属高相关组（组内各行业走势相关系数 &gt; 0.7）</div>
              <div style={{ marginBottom: 4 }}>📦 同组内行业<b>共享</b>分散化名额，避免"伪分散"（如银行+非银金融实际一跌全跌）</div>
              <div style={{ marginBottom: 4 }}>⚠ <Tag color="orange" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>降权</Tag> = 该股票因同组名额已满被移至推荐列表末尾</div>
              <div style={{ marginTop: 4, color: '#8c8c8c' }}>分组：金融板块 · 地产链 · 能源链 · 消费链 · TMT · 制造链 · 防御板块 · 科技制造</div>
            </div>
          }
        >
          <span>行业 <QuestionCircleOutlined style={{ color: '#91caff', fontSize: 12 }} /></span>
        </Tooltip>
      ),
      dataIndex: 'industry',
      width: 130,
      ellipsis: true,
      render: (v, rec) => {
        if (!v) return '-';
        const regime = rec.industryRegime;
        const momentum = rec.industryMomentum;
        const gKey = rec.corrGroup;
        const gLabel = gKey ? CORR_GROUP_LABEL[gKey] : null;
        let icon = null;
        let tag = null;
        if (regime === 'BULL') icon = '📈';
        else if (regime === 'BEAR') icon = '📉';
        else if (regime === 'SIDEWAYS') icon = '↔';
        if (momentum != null) {
          if (momentum > 0.3) tag = '🔥';
          else if (momentum < -0.3) tag = '❄️';
        }
        const groupTooltip = gLabel ? `${gLabel}: ${(INDUSTRY_CORR_GROUPS.find(g => g[0] === gKey) || []).join('、')} 走势高相关，共享分散化名额` : '';
        const demotedTag = rec.diversificationDemoted
          ? <Tag color="orange" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginLeft: 4 }}>降权</Tag>
          : null;
        return (
          <Tooltip title={`${v}${icon ? ' ' + icon : ''}${tag ? ' ' + tag : ''}${groupTooltip ? '\n' + groupTooltip : ''}${rec.diversificationDemoted ? '\n⚠ 因行业分散化限额被降权至末尾' : ''}`}>
            <span style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 2 }}>
              {gLabel && <Tag color="purple" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{gLabel}</Tag>}
              <span>{icon}{tag}{v}</span>
              {demotedTag}
            </span>
          </Tooltip>
        );
      },
    },
    {
      title: (
        <Tooltip
          styles={{ root: {maxWidth: 380} }}
          title={
            <div style={{ fontSize: 12, lineHeight: '20px' }}>
              <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>行业强度说明</div>
              <div style={{ marginBottom: 6 }}>行业强度 = (行业近5日平均涨跌幅 - 全市场行业均值) / 标准差，即 z-score（基于近5日平滑均值，避免单日极端值导致排名跳变）。</div>
              <div style={{ marginBottom: 4 }}><b>数值含义：</b></div>
              <div style={{ marginBottom: 4 }}>• &gt; 0.3（红色）：强势行业，近5日涨幅显著跑赢市场平均，选股上限放宽至 6 只</div>
              <div style={{ marginBottom: 4 }}>• -0.3 ~ 0.3（灰色）：中等强度，选股上限默认 3 只</div>
              <div style={{ marginBottom: 4 }}>• &lt; -0.3（绿色）：弱势行业，近5日涨幅显著跑输市场平均，选股上限收紧至 1 只</div>
              <div style={{ marginTop: 8, fontWeight: 'bold' }}>作用机制：</div>
              <div style={{ marginBottom: 4 }}>1. 行业分散化：根据强度动态调整同行业入选数量上限（强势≤6，中等≤3，弱势≤1）</div>
              <div style={{ marginBottom: 4 }}>2. 因子融合加分：强势行业个股最终得分 +0.06，弱势行业 -0.06</div>
              <div style={{ marginBottom: 4 }}>3. 行业轮动参考：结合 📈/📉/↔ Regime 图标判断行业趋势方向</div>
            </div>
          }
        >
          <span>行业强度 <QuestionCircleOutlined style={{ color: '#91caff', fontSize: 12 }} /></span>
        </Tooltip>
      ),
      dataIndex: 'industryMomentum',
      width: 95,
      render: (v) => {
        if (v == null) return '-';
        let color = '#8c8c8c';
        if (v > 0.3) color = '#cf1322';
        else if (v < -0.3) color = '#3f8600';
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}</Text>;
      },
    },
    {
      title: '市值',
      dataIndex: 'marketCap',
      width: 80,
      render: (v) => formatMarketCap(v),
    },
    {
      title: '现价',
      dataIndex: 'closePrice',
      width: 70,
      render: (v) => v != null ? v.toFixed(2) : '-',
    },
    {
      title: (
        <Tooltip title="推荐买入价格：优先取MA20支撑位，若无法获取则取现价×0.95作为保守买入价">
          买入价 <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} />
        </Tooltip>
      ),
      dataIndex: 'suggestedBuyPrice',
      width: 80,
      render: (v, rec) => {
        if (v == null) return '-';
        const isBelowClose = rec.closePrice != null && v < rec.closePrice;
        return <Text type={isBelowClose ? 'danger' : 'secondary'}>{v.toFixed(2)}</Text>;
      },
    },
    {
      title: '买入理由',
      dataIndex: 'buyReason',
      width: 200,
      ellipsis: true,
      render: (v) => v ? <Tooltip title={v}><Text type="secondary" style={{ fontSize: 12 }}>{v}</Text></Tooltip> : '-',
    },
    // 追踪字段（Phase 2 填充）
    {
      title: <Tooltip title="推荐次日收盘相对推荐日收盘的涨跌幅">次日收益</Tooltip>,
      dataIndex: 'nextDayReturn',
      width: 85,
      render: (v, rec) => {
        if (v == null) {
          const now = new Date();
          const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
          const isToday = rec.recommendDate === todayStr;
          return (
            <Tooltip title={isToday ? '推荐当日，需次日收盘后计算' : '数据积累中，稍后可追踪'}>
              <Text type="secondary" style={{ fontSize: 11 }}>{isToday ? '当日' : '待追踪'}</Text>
            </Tooltip>
          );
        }
        const color = v > 0 ? '#cf1322' : v < 0 ? '#3f8600' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}%</Text>;
      },
    },
    {
      title: <Tooltip title="推荐后第5个交易日收盘相对推荐日收盘的涨跌幅">一周收益</Tooltip>,
      dataIndex: 'nextWeekReturn',
      width: 85,
      render: (v) => {
        if (v == null) return (
          <Tooltip title="需至少5个交易日后才能计算">
            <Text type="secondary" style={{ fontSize: 11 }}>待追踪</Text>
          </Tooltip>
        );
        const color = v > 0 ? '#cf1322' : v < 0 ? '#3f8600' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}%</Text>;
      },
    },
    {
      title: <Tooltip title="推荐后第22个交易日收盘相对推荐日收盘的涨跌幅">一月收益</Tooltip>,
      dataIndex: 'nextMonthReturn',
      width: 85,
      render: (v) => {
        if (v == null) return (
          <Tooltip title="需至少22个交易日后才能计算">
            <Text type="secondary" style={{ fontSize: 11 }}>待追踪</Text>
          </Tooltip>
        );
        const color = v > 0 ? '#cf1322' : v < 0 ? '#3f8600' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}%</Text>;
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 70,
      fixed: 'right',
      render: (_, rec) => (
        <Popconfirm
          title={`确认将 ${rec.stockName}(${rec.stockCode}) 加入黑名单？`}
          description="加入后默认30天内不再推荐该股票"
          onConfirm={() => handleManualBlacklist(rec)}
          okText="确认"
          cancelText="取消"
        >
          <Button type="link" size="small" danger icon={<StopOutlined />} style={{ padding: 0, fontSize: 11 }}>
            屏蔽
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* 头部 */}
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Title level={4} style={{ margin: 0 }}>
            <ThunderboltOutlined /> 智能推荐
            <Tooltip
              styles={{ root: {maxWidth: 520} }}
              title={
                <div style={{ fontSize: 12, lineHeight: '20px' }}>
                  <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>推荐生成流程</div>
                  <div style={{ fontFamily: 'monospace', marginBottom: 8 }}>
                    全市场 ~5000只 A股<br />
                    &nbsp;→ <b>多因子筛选</b>（按选定因子组合筛选）<br />
                    &nbsp;&nbsp;→ <b>个股深度分析</b>（六维度：技术/资金/事件/基本面/风险/流动性）<br />
                    &nbsp;&nbsp;&nbsp;→ <b>Regime-Adaptive融合</b>（市场环境自适应权重）<br />
                    &nbsp;&nbsp;&nbsp;&nbsp;→ <b>行业分散化</b>（同行业≤3只）
                  </div>
                  <div style={{ fontWeight: 'bold', marginBottom: 4, fontSize: 13, borderTop: '1px solid #444', paddingTop: 6 }}>综合得分计算过程</div>
                  <div style={{ marginBottom: 4 }}>
                    <b>finalScore = wFactor × 因子得分 + wAnalysis × 分析得分 + 行业加分</b>
                  </div>
                  <div style={{ marginBottom: 4 }}>
                    <b>① 因子得分</b>：用户选定因子组合的 Z-score 标准化加权综合得分（0~1）
                  </div>
                  <div style={{ marginBottom: 4 }}>
                    <b>② 分析得分</b>（134分制，归一化到 0~1）：六维度加权求和
                  </div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 技术面（满分30）：RSI、MACD、MTM6 等</div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 资金面（满分25）：主力净流入、换手率</div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 事件面（满分25）：利好事件驱动</div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 基本面（满分29）：盈利增速、估值、分红</div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 风险（满分15）：最大回撤、波动率、ATR</div>
                  <div style={{ marginLeft: 8, marginBottom: 4 }}>• 流动性（满分10）：成交额、换手率适中度</div>
                  <div style={{ marginBottom: 4 }}>
                    <b>③ 市场环境权重</b>（wFactor / wAnalysis）：根据 Regime 动态分配
                  </div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 📈 牛市（BULL）：因子60% / 分析40%</div>
                  <div style={{ marginLeft: 8, marginBottom: 2 }}>• 📉 熊市（BEAR）：因子40% / 分析60%</div>
                  <div style={{ marginLeft: 8, marginBottom: 4 }}>• ↔️ 震荡（SIDEWAYS）：因子50% / 分析50%</div>
                  <div style={{ marginBottom: 4 }}>
                    <b>④ 行业轮动加分</b>：基于近5日行业动量校准值（非固定值），加速期 ×1.5，减速期 ×0.5
                  </div>
                  <div style={{ marginBottom: 4 }}>
                    <b>⑤ 利率环境影响</b>：利率下行加技术面/资金面权重，利率上行加基本面/风险权重
                  </div>
                  <div style={{ marginBottom: 2 }}>
                    <b>⑥ 市值风格</b>：小盘风格时，因子权重再 +0.05（偏向量化因子选股）
                  </div>
                </div>
              }
            >
              <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 14, marginLeft: 6, cursor: 'help' }} />
            </Tooltip>
          </Title>
          {rc && (
            <Tag icon={rc.icon} color={rc.color} style={{ fontSize: 13 }}>
              {rc.text}
            </Tag>
          )}
        </Space>
        <Space>
          <DatePicker
            value={screenDate ? dayjs(screenDate) : null}
            onChange={date => setScreenDate(date ? date.format('YYYY-MM-DD') : null)}
            placeholder="选择日期"
            style={{ width: 170 }}
            disabledDate={(current) => current && current.isAfter(dayjs().endOf('day'))}
          />
          <Select
            value={selectedStrategyId}
            onChange={value => setSelectedStrategyId(value)}
            style={{ width: 260 }}
            placeholder="选择策略"
            showSearch
            filterOption={(input, option) =>
              (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
            }
            options={strategies.map(s => {
              const conf = getConfidenceForStrategy(s.id);
              const cCfg = conf?.level ? CONFIDENCE_CONFIG[conf.level] : null;
              return {
                value: s.id,
                label: (
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span>{s.strategyName}</span>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 8 }}>
                      {cCfg && conf?.score != null && (
                        <Tag
                          color={cCfg.color}
                          style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', borderRadius: 4 }}
                        >
                          {conf.score}
                        </Tag>
                      )}
                      {conf?.score != null && (
                        <Tooltip title={`置信度${cCfg?.text || ''}: 命中率${((conf.hitRateValue || 0) * 100).toFixed(1)}%（近10期推荐股票次日收益>0的比例） | 平均收益${(conf.avgReturnValue || 0).toFixed(2)}%（近10期所有推荐股票次日涨跌幅均值） | 样本${conf.sampleSize || 0}条 | 截止${conf.dataAsOfDate || '-'}`}>
                          <QuestionCircleOutlined
                            style={{ color: '#91caff', fontSize: 13 }}
                            onMouseDown={e => e.stopPropagation()}
                            onClick={e => e.stopPropagation()}
                          />
                        </Tooltip>
                      )}
                      <Tooltip
                        styles={{ root: {maxWidth: 420} }}
                        title={renderFactorTable(s)}
                      >
                        <RadarChartOutlined
                          style={{ color: '#91caff', fontSize: 13, marginLeft: 2 }}
                          onMouseDown={e => e.stopPropagation()}
                          onClick={e => e.stopPropagation()}
                        />
                      </Tooltip>
                    </div>
                  </div>
                ),
              };
            })}
          />
          <Select
            value={weightMode}
            onChange={setWeightMode}
            style={{ width: 120 }}
            size="middle"
          >
            <Select.Option value="STATIC">固定权重</Select.Option>
            <Select.Option value="IC">动态IC加权</Select.Option>
          </Select>
          <Switch
            checked={enableConfidenceControl}
            onChange={handleConfidenceControlChange}
            checkedChildren="置信度控制"
            unCheckedChildren="置信度控制"
            style={{ marginLeft: 8 }}
          />
          <Tooltip title="开启后，系统根据策略置信度自动调整生成数量：高/中置信度正常生成，低置信度自动减少推荐数量，暂停置信度仅生成1-2只。关闭则完全按设定数量生成。">
            <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} />
          </Tooltip>
          <Button
            type="primary"
            icon={<ReloadOutlined spin={generating} />}
            loading={generating}
            onClick={handleGenerate}
          >
            生成推荐
          </Button>
          <Button
            icon={<StopOutlined />}
            onClick={() => { loadBlacklist(); setBlacklistModalVisible(true); }}
          >
            黑名单{blacklist.filter(b => !isExpired(b.blacklist_until)).length > 0 &&
              <Tag color="red" style={{ marginLeft: 4, fontSize: 10, padding: '0 3px' }}>
                {blacklist.filter(b => !isExpired(b.blacklist_until)).length}
              </Tag>}
          </Button>
        </Space>
      </div>

      {/* 市场环境概览 */}
      {regime && (
        <Row gutter={12} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
              <Statistic
                title="市场环境"
                value={REGIME_CONFIG[regime]?.text || regime}
                valueStyle={{ color: REGIME_CONFIG[regime]?.color || '#597ef7', fontSize: 20 }}
                prefix={REGIME_CONFIG[regime]?.icon}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
              <Statistic
                title="沪深300"
                value={indexInfo?.close?.toFixed(2) || '-'}
                prefix={<LineChartOutlined />}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
              <Statistic
                title={<span>MA20 <Tooltip title="沪深300指数过去20个交易日的移动平均收盘价。指数收盘 > MA20 意味着中期趋势偏强，部分策略（如大盘择时）会以此判断是否适合做多。这里显示的数值为你参考趋势强弱用"><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                value={indexInfo?.ma20?.toFixed(2) || '-'}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
              <Statistic
                title={<span>MA60 <Tooltip title="沪深300指数过去60个交易日的移动平均收盘价，代表长期趋势。指数收盘 > MA60 通常被视为中长期牛市信号。MA20 和 MA60 的相对位置（金叉/死叉）也是市场环境判断的重要参考"><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                value={indexInfo?.ma60?.toFixed(2) || '-'}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
              <Tooltip
                styles={{ root: {maxWidth: 300} }}
                title={weightInfo?.factorWeight != null
                  ? (
                    <div style={{ fontSize: 12, lineHeight: '18px' }}>
                      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>各市场环境权重分配</div>
                      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid #434343' }}>
                            <th style={{ padding: '2px 8px', textAlign: 'left' }}>环境</th>
                            <th style={{ padding: '2px 8px', textAlign: 'center' }}>因子</th>
                            <th style={{ padding: '2px 8px', textAlign: 'center' }}>分析</th>
                            <th style={{ padding: '2px 8px', textAlign: 'left' }}>策略</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr style={{ color: '#cf1322' }}>
                            <td style={{ padding: '2px 8px' }}>牛市</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>60%</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>40%</td>
                            <td style={{ padding: '2px 8px' }}>动量因子占优</td>
                          </tr>
                          <tr style={{ color: '#597ef7' }}>
                            <td style={{ padding: '2px 8px' }}>震荡</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>50%</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>50%</td>
                            <td style={{ padding: '2px 8px' }}>攻守均衡</td>
                          </tr>
                          <tr style={{ color: '#3f8600' }}>
                            <td style={{ padding: '2px 8px' }}>熊市</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>40%</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>60%</td>
                            <td style={{ padding: '2px 8px' }}>偏防守反弹</td>
                          </tr>
                        </tbody>
                      </table>
                      <div style={{ marginTop: 6, color: '#8c8c8c' }}>
                        综合得分 = 因子得分 × {weightInfo.factorWeight != null ? (weightInfo.factorWeight * 100).toFixed(0) : '?'}% + 分析得分 × {weightInfo.analysisWeight != null ? (weightInfo.analysisWeight * 100).toFixed(0) : '?'}%
                      </div>

                      <div style={{ fontWeight: 'bold', marginTop: 10, marginBottom: 4, borderTop: '1px solid #555', paddingTop: 8 }}>分析得分六维度权重分配</div>
                      <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 11 }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid #434343' }}>
                            <th style={{ padding: '2px 4px', textAlign: 'left' }}>环境</th>
                            <th style={{ padding: '2px 4px', textAlign: 'center' }}>技术面</th>
                            <th style={{ padding: '2px 4px', textAlign: 'center' }}>资金面</th>
                            <th style={{ padding: '2px 4px', textAlign: 'center' }}>事件面</th>
                            <th style={{ padding: '2px 4px', textAlign: 'center' }}>基本面</th>
                            <th style={{ padding: '2px 4px', textAlign: 'center' }}>风险</th>
                            <th style={{ padding: '2px 4px', textAlign: 'center' }}>流动性</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr style={{ color: '#cf1322' }}>
                            <td style={{ padding: '2px 4px' }}>牛市</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>30%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>25%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>15%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                          </tr>
                          <tr style={{ color: '#3f8600' }}>
                            <td style={{ padding: '2px 4px' }}>熊市</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>15%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>35%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>20%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                          </tr>
                          <tr style={{ color: '#597ef7' }}>
                            <td style={{ padding: '2px 4px' }}>震荡</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>25%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>20%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>20%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>15%</td>
                            <td style={{ padding: '2px 4px', textAlign: 'center' }}>10%</td>
                          </tr>
                        </tbody>
                      </table>
                      <div style={{ marginTop: 4, color: '#8c8c8c' }}>注：风险在熊市权重提升至20%，基本面也同步提升至35%</div>
                    </div>
                  )
                  : '旧批次未记录权重信息，重新生成推荐后可显示'}>
                <Statistic
                  title={<span>因子权重 <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></span>}
                  value={weightInfo?.factorWeight != null ? `${(weightInfo.factorWeight * 100).toFixed(0)}%` : '未设置'}
                  valueStyle={{ fontSize: 20 }}
                  suffix={weightInfo?.analysisWeight != null ? `/ ${(weightInfo.analysisWeight * 100).toFixed(0)}%分析` : ''}
                  titleStyle={{ fontSize: 12 }}
                />
              </Tooltip>
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
              <Statistic
                title={<span>选股范围 <Tooltip title="从全市场约5000+只A股中，先用12个多因子（动量/波动/估值/技术/流动性/财务质量/成长）筛选出Top50候选，再对其中N只做深度六维度分析（技术/资金/事件/基本面/风险/流动性），最终经行业分散化后输出推荐结果"><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                value={recommendations.length}
                suffix="只"
                prefix={<ThunderboltOutlined />}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
        </Row>
      )}

      {/* IC 加权因子诊断 */}
      {factorDiagnostics && factorDiagnostics.length > 0 && (() => {
        const kept = factorDiagnostics.filter(d => d.action === 'KEPT');
        const dropped = factorDiagnostics.filter(d => d.action === 'DROPPED');
        const reversed = factorDiagnostics.filter(d => d.action === 'REVERSED');
        const noData = factorDiagnostics.filter(d => d.action === 'NO_DATA');
        const abnormalCount = dropped.length + reversed.length + noData.length;

        return (
          <Card
            size="small"
            style={{ marginBottom: 16 }}
            title={
              <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '0 8px' }}>
                <span>
                  <ThunderboltOutlined style={{ marginRight: 6 }} />
                  IC 加权因子诊断
                  <Tooltip title="基于各因子近60日IC均值，自动判断是否参与本次选股。IC>0的因子按IC占比分配权重，IC≤0的因子被自动剔除。">
                    <QuestionCircleOutlined style={{ color: '#91caff', fontSize: 13, marginLeft: 6 }} />
                  </Tooltip>
                </span>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                  <Tag color="success" style={{ fontSize: 11, marginRight: 0, lineHeight: '18px' }}>参与加权 {kept.length}</Tag>
                  {dropped.length > 0 && <Tag color="error" style={{ fontSize: 11, marginRight: 0, lineHeight: '18px' }}>已剔除 {dropped.length}</Tag>}
                  {reversed.length > 0 && <Tag color="warning" style={{ fontSize: 11, marginRight: 0, lineHeight: '18px' }}>方向反转 {reversed.length}</Tag>}
                  {noData.length > 0 && <Tag style={{ fontSize: 11, marginRight: 0, lineHeight: '18px' }}>无数据 {noData.length}</Tag>}
                  {abnormalCount > 0 && (
                    <span
                      onClick={(e) => { e.stopPropagation(); setDiagExpanded(v => !v); }}
                      style={{ cursor: 'pointer', color: '#ff4d4f', fontSize: 12, display: 'flex', alignItems: 'center', gap: 4, marginLeft: 4 }}
                    >
                      <span style={{ display: 'inline-block', transform: diagExpanded ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }}>▶</span>
                      已剔除/异常 ({abnormalCount}个)
                    </span>
                  )}
                </span>
              </div>
            }
          >
            <div style={{ fontSize: 13 }}>
              {/* IC数据日期提示 */}
              {icDataDate && (
                <div style={{
                  background: '#e6f7ff', border: '1px solid #91d5ff',
                  borderRadius: 4, padding: '6px 12px', marginBottom: 12,
                  fontSize: 12, color: '#0050b3',
                }}>
                  📅 IC 数据截止日期: <b>{icDataDate}</b>（近5个交易日因缺少前瞻价格数据，IC不可用，已自动回退至最近可用IC日期）
                </div>
              )}
              {/* 参与加权的因子 */}
              {kept.length > 0 && (
                <div style={{ marginBottom: abnormalCount > 0 ? 16 : 0 }}>
                  <div style={{ fontWeight: 600, marginBottom: 8, color: '#52c41a' }}>
                    参与加权 ({kept.length}个)
                  </div>
                  <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 12 }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid #f0f0f0', color: '#8c8c8c' }}>
                        <th style={{ textAlign: 'left', padding: '4px 8px', width: 100 }}>因子代码</th>
                        <th style={{ textAlign: 'left', padding: '4px 8px', width: 70 }}>分类</th>
                        <th style={{ textAlign: 'right', padding: '4px 8px', width: 80 }}>IC均值</th>
                        <th style={{ textAlign: 'right', padding: '4px 8px', width: 80 }}>原始权重</th>
                        <th style={{ textAlign: 'right', padding: '4px 8px', width: 80 }}>调整后权重</th>
                        <th style={{ textAlign: 'left', padding: '4px 8px' }}>说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {kept.map((d, i) => (
                        <tr key={i} style={{ borderBottom: '1px solid #fafafa' }}>
                          <td style={{ padding: '4px 8px', fontFamily: 'monospace' }}>{d.factorCode}</td>
                          <td style={{ padding: '4px 8px' }}>{factorMeta[d.factorCode]?.cat || '-'}</td>
                          <td style={{ padding: '4px 8px', textAlign: 'right', color: '#52c41a' }}>{(d.icMean).toFixed(4)}</td>
                          <td style={{ padding: '4px 8px', textAlign: 'right' }}>{d.originalWeight.toFixed(2)}</td>
                          <td style={{ padding: '4px 8px', textAlign: 'right', fontWeight: 600, color: '#1890ff' }}>{d.adjustedWeight.toFixed(4)}</td>
                          <td style={{ padding: '4px 8px', color: '#595959' }}>{d.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {/* 已剔除/异常详情 */}
              {diagExpanded && abnormalCount > 0 && (
                <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 12 }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #f0f0f0', color: '#8c8c8c' }}>
                      <th style={{ textAlign: 'left', padding: '4px 8px', width: 100 }}>因子代码</th>
                      <th style={{ textAlign: 'left', padding: '4px 8px', width: 70 }}>分类</th>
                      <th style={{ textAlign: 'right', padding: '4px 8px', width: 80 }}>IC均值</th>
                      <th style={{ textAlign: 'right', padding: '4px 8px', width: 80 }}>原始权重</th>
                      <th style={{ textAlign: 'left', padding: '4px 8px', width: 90 }}>状态</th>
                      <th style={{ textAlign: 'left', padding: '4px 8px' }}>原因</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...dropped, ...reversed, ...noData].map((d, i) => {
                      const cfg = DIAG_CONFIG[d.action] || {};
                      return (
                        <tr key={i} style={{ borderBottom: '1px solid #fafafa' }}>
                          <td style={{ padding: '4px 8px', fontFamily: 'monospace' }}>{d.factorCode}</td>
                          <td style={{ padding: '4px 8px' }}>{factorMeta[d.factorCode]?.cat || '-'}</td>
                          <td style={{ padding: '4px 8px', textAlign: 'right', color: (d.icMean || 0) < 0 ? '#ff4d4f' : '#8c8c8c' }}>{(d.icMean || 0).toFixed(4)}</td>
                          <td style={{ padding: '4px 8px', textAlign: 'right' }}>{(d.originalWeight || 0).toFixed(2)}</td>
                          <td style={{ padding: '4px 8px' }}>
                            <Tag color={cfg.color}>{cfg.text}</Tag>
                          </td>
                          <td style={{ padding: '4px 8px', color: '#595959' }}>{d.reason}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>
          </Card>
        );
      })()}

      {/* 推荐列表 */}
      <Card size="small" styles={{ body: { padding: 0 } }}>
        <Spin spinning={loading}>

          {/* ── 方案C: 策略置信度状态栏 ── */}
          {confidenceData && confidenceData.level !== 'UNTRAINED' && confidenceData.score != null && (() => {
            const cfg = CONFIDENCE_CONFIG[confidenceData.level] || CONFIDENCE_CONFIG.UNTRAINED;
            const isLow = confidenceData.level === 'LOW' || confidenceData.level === 'SUSPENDED';
            return (
              <div style={{
                padding: '8px 12px',
                background: cfg.bg,
                borderBottom: `1px solid ${cfg.color}`,
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                flexWrap: 'wrap',
                gap: 8,
              }}>
                <Space size={12}>
                  <span>
                    <span style={{ fontSize: 14 }}>{cfg.icon}</span>
                    <Text strong style={{ color: cfg.color, marginLeft: 4 }}>
                      策略置信度：{confidenceData.score}分 ({cfg.text})
                    </Text>
                  </span>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    近10期命中率{((confidenceData.hitRateValue || 0) * 100).toFixed(0)}%
                  </Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    平均收益{(confidenceData.avgReturnValue || 0).toFixed(2)}%
                  </Text>
                  <Button
                    type="link"
                    size="small"
                    style={{ padding: 0, fontSize: 11 }}
                    onClick={() => setConfidenceWarningVisible(true)}
                  >
                    查看详情
                  </Button>
                </Space>
                {isLow && (
                  <Tag color="warning" style={{ fontSize: 11 }}>
                    ⚠️ 置信度偏低，建议降低推荐数量或暂停使用
                  </Tag>
                )}
              </div>
            );
          })()}

          {/* 未训练提示 */}
          {confidenceData && confidenceData.level === 'UNTRAINED' && (
            <div style={{
              padding: '6px 12px',
              background: '#fafafa',
              borderBottom: '1px solid #d9d9d9',
              textAlign: 'center',
            }}>
              <Text type="secondary" style={{ fontSize: 11 }}>
                📊 当前策略（ID: {confidenceData?.strategyId || selectedStrategyId}）暂无追踪数据，置信度未计算（至少追踪1期后显示）
              </Text>
            </div>
          )}

          <Table
            dataSource={recommendations}
            columns={columns}
            rowKey="id"
            size="small"
            scroll={{ x: 1400 }}
            pagination={false}
            expandable={{
              expandedRowRender: (rec) => {
                const dims = [
                  { name: '技术', val: rec.technicalScore || 0, max: 30 },
                  { name: '资金', val: rec.capitalScore || 0, max: 25 },
                  { name: '事件', val: rec.eventScore || 0, max: 25 },
                  { name: '基本面', val: rec.fundamentalScore || 0, max: 29 },
                  { name: '风险', val: rec.riskScore || 0, max: 15 },
                  { name: '流动性', val: rec.liquidityScore || 0, max: 10 },
                ];
                const radarOption = {
                  radar: {
                    indicator: dims.map(d => ({ name: `${d.name}\n(${d.max}分)`, max: d.max })),
                    shape: 'circle',
                    center: ['50%', '55%'],
                    radius: '65%',
                    axisName: { color: '#666', fontSize: 11 },
                    splitArea: { areaStyle: { color: ['rgba(114,46,209,0.02)', 'rgba(114,46,209,0.02)', 'rgba(114,46,209,0.04)', 'rgba(114,46,209,0.04)', 'rgba(114,46,209,0.06)', 'rgba(114,46,209,0.06)'] } },
                  },
                  series: [{
                    type: 'radar',
                    symbol: 'circle',
                    symbolSize: 4,
                    data: [{
                      value: dims.map(d => d.val),
                      name: rec.stockName,
                      areaStyle: { color: 'rgba(114,46,209,0.15)' },
                      lineStyle: { color: '#722ed1', width: 2 },
                      itemStyle: { color: '#722ed1' },
                    }],
                  }],
                };
                const total6d = dims.reduce((s, d) => s + d.val, 0);
                return (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 24, padding: '8px 16px', background: '#fafafa' }}>
                    <ReactEcharts option={radarOption} style={{ width: 280, height: 220 }} />
                    <div style={{ flex: 1, fontSize: 13 }}>
                      <div style={{ fontWeight: 600, marginBottom: 12, fontSize: 14 }}>📊 {rec.stockName}（{rec.stockCode}）六维度评分详情</div>
                      <Row gutter={[16, 8]}>
                        {dims.map((d) => {
                          const pct = d.max > 0 ? Math.round(d.val / d.max * 100) : 0;
                          let color = '#52c41a';
                          if (pct < 40) color = '#ff4d4f';
                          else if (pct < 70) color = '#fa8c16';
                          return (
                            <Col span={12} key={d.name}>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                <Text style={{ width: 50, textAlign: 'right', fontSize: 12 }}>{d.name}</Text>
                                <Progress percent={pct} size="small" style={{ flex: 1, margin: 0 }} strokeColor={color} />
                                <Text strong style={{ width: 55, fontSize: 12 }}>{d.val}/{d.max}</Text>
                              </div>
                            </Col>
                          );
                        })}
                      </Row>
                      <div style={{ marginTop: 12, padding: '6px 12px', background: '#fff', borderRadius: 4, border: '1px solid #f0f0f0' }}>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          六维度总分: <Text strong>{total6d}</Text>/134
                          {rec.riskScore > 0 && <span style={{ marginLeft: 12 }}>🔻 风险: {rec.riskScore}/15（回撤/波动率/ATR）</span>}
                          {rec.liquidityScore > 0 && <span style={{ marginLeft: 12 }}>💧 流动性: {rec.liquidityScore}/10（成交额/换手率）</span>}
                        </Text>
                      </div>
                    </div>
                  </div>
                );
              },
              rowExpandable: (rec) => rec.technicalScore != null || rec.riskScore != null,
              expandIcon: ({ expanded, onExpand, record }) => {
                if (record.technicalScore == null && record.riskScore == null) return null;
                return (
                  <span
                    onClick={e => onExpand(record, e)}
                    style={{ cursor: 'pointer', color: '#722ed1', fontSize: 16 }}
                  >
                    <RadarChartOutlined rotate={expanded ? 90 : 0} />
                  </span>
                );
              },
            }}
            locale={{ emptyText: recommendations.length === 0 && !loading
              ? '暂无推荐数据，点击「生成推荐」开始'
              : '加载中...'
            }}
          />
        </Spin>
      </Card>

      {/* 表现追踪面板 */}
      {batchHistory && batchHistory.length > 0 && (() => {
        const trackedBatches = batchHistory.filter(b => b.tracked > 0);
        const latest = [...batchHistory].reverse().find(b => b.tracked > 0) || batchHistory[batchHistory.length - 1];
        const avgHitRate = trackedBatches.length > 0
          ? trackedBatches.reduce((s, b) => s + (b.hitRate || 0), 0) / trackedBatches.length
          : 0;
        const avgDayRet = trackedBatches.length > 0
          ? trackedBatches.reduce((s, b) => s + (b.avgDayReturn || 0), 0) / trackedBatches.length
          : 0;

        // 命中率趋势图
        const trendOption = {
          grid: { top: 30, right: 60, bottom: 30, left: 50 },
          tooltip: {
            trigger: 'axis',
            formatter: params => {
              if (!params || params.length === 0) return '';
              const p = params[0];
              const batch = batchHistory.find(b => b.recommendDate === p.axisValue);
              if (!batch) return '';
              return `<b>${p.axisValue}</b><br/>
                次日命中率: ${(batch.hitRate * 100).toFixed(0)}%<br/>
                次日均收益: ${batch.avgDayReturn != null ? (batch.avgDayReturn > 0 ? '+' : '') + batch.avgDayReturn.toFixed(2) + '%' : '-'}<br/>
                一周均收益: ${batch.avgWeekReturn != null ? (batch.avgWeekReturn > 0 ? '+' : '') + batch.avgWeekReturn.toFixed(2) + '%' : '-'}<br/>
                一月均收益: ${batch.avgMonthReturn != null ? (batch.avgMonthReturn > 0 ? '+' : '') + batch.avgMonthReturn.toFixed(2) + '%' : '-'}`;
            },
          },
          xAxis: {
            type: 'category',
            data: batchHistory.map(b => b.recommendDate),
            axisLabel: { fontSize: 10, rotate: 30 },
          },
          yAxis: [
            {
              type: 'value',
              name: '命中率',
              position: 'left',
              axisLabel: { formatter: v => (v * 100).toFixed(0) + '%' },
              splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
            },
            {
              type: 'value',
              name: '次日均收益',
              position: 'right',
              nameGap: 30,
              axisLabel: { formatter: v => v.toFixed(1) + '%' },
              splitLine: { show: false },
            },
          ],
          visualMap: {
            show: false,
            pieces: [
              { lt: 0.4, color: '#ff4d4f' },
              { gte: 0.4, lt: 0.6, color: '#fa8c16' },
              { gte: 0.6, color: '#52c41a' },
            ],
            dimension: 1,
          },
          series: [
            {
              name: '命中率',
              type: 'bar',
              data: batchHistory.map(b => b.hitRate != null ? +(b.hitRate).toFixed(3) : null),
              barWidth: '40%',
              label: { show: true, position: 'top', fontSize: 10, color: '#595959', formatter: p => p.value != null ? (p.value * 100).toFixed(0) + '%' : '-' },
              itemStyle: { borderRadius: [4, 4, 0, 0] },
            },
            {
              name: '次日均收益',
              type: 'line',
              yAxisIndex: 1,
              data: batchHistory.map(b => b.avgDayReturn != null ? +(b.avgDayReturn).toFixed(2) : null),
              lineStyle: { color: '#1890ff', width: 2 },
              symbol: 'none',
              itemStyle: { color: '#1890ff' },
            },
          ],
        };

        const qConfig = {
          HIGH_QUALITY: { color: '#52c41a', bg: '#f6ffed', text: '高质量', desc: '近5期平均命中率 ≥ 60%' },
          NORMAL: { color: '#1890ff', bg: '#e6f7ff', text: '正常', desc: '近5期平均命中率 40%-60%' },
          LOW_QUALITY: { color: '#ff4d4f', bg: '#fff1f0', text: '低质量', desc: '近5期平均命中率 < 40%' },
          UNTRAINED: { color: '#8c8c8c', bg: '#fafafa', text: '未追踪', desc: '尚无追踪数据' },
        };

        return (
          <>
            {/* 复盘策略筛选栏 */}
            <Row gutter={12} style={{ marginTop: 16, marginBottom: 12 }} align="middle">
              <Col>
                <Space>
                  <Text strong style={{ fontSize: 13 }}>复盘筛选：</Text>
                  <Select
                    value={reviewStrategyId}
                    onChange={handleReviewStrategyChange}
                    style={{ width: 200 }}
                    placeholder="选择策略"
                    size="small"
                  >
                    {strategiesWithData.map(sid => {
                      const s = strategies.find(st => st.id === sid);
                      return (
                        <Select.Option key={sid} value={sid}>
                          {s ? s.strategyName : `策略${sid}`}
                        </Select.Option>
                      );
                    })}
                  </Select>
                  <Select
                    value={reviewDate}
                    onChange={handleReviewDateChange}
                    style={{ width: 140 }}
                    placeholder="选择日期"
                    size="small"
                    disabled={!reviewStrategyId}
                  >
                    {(batchHistory || []).map(b => (
                      <Select.Option key={b.recommendDate} value={b.recommendDate}>
                        {b.recommendDate}
                      </Select.Option>
                    ))}
                  </Select>
                </Space>
              </Col>
              <Col flex="auto">
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {qualityTag && (() => {
                    const tag = qConfig[qualityTag];
                    return tag ? (
                      <Tag color={tag.color} style={{ marginRight: 0 }}>
                        质量标签: {tag.text}
                      </Tag>
                    ) : null;
                  })()}
                  {reviewStrategyId && reviewDate && (
                    <span style={{ marginLeft: 8 }}>当前查看: 策略{reviewStrategyId} @ {reviewDate}</span>
                  )}
                </Text>
              </Col>
            </Row>

            <Row gutter={12} style={{ marginBottom: 16 }}>
              <Col span={4}>
                <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
                  <Statistic
                    title={<span>平均命中率 <Tooltip title={<>含义：所有已追踪批次中，推荐股票次日收盘上涨的比例均值<br/>作用：衡量策略整体选股的准确性<br/>阈值：≥60% 高质量 / 40%-60% 正常 / &lt;40% 低质量<br/>影响：持续低于40%时，需审视策略参数或市场环境</>}><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                    value={(avgHitRate * 100).toFixed(0)}
                    suffix="%"
                    valueStyle={{ color: avgHitRate >= 0.6 ? '#52c41a' : avgHitRate >= 0.4 ? '#1890ff' : '#ff4d4f', fontSize: 20 }}
                  />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
                  <Statistic
                    title={<span>次日均收益 <Tooltip title={<>含义：所有已追踪批次中，推荐股票次日涨跌幅的算术平均值<br/>作用：衡量策略的整体盈利能力<br/>阈值：&gt;0 为正收益策略 / 接近0 效果一般 / &lt;0 策略亏损<br/>影响：持续为负时，即使命中率高也可能是小涨大跌，需关注盈亏比</>}><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                    value={avgDayRet > 0 ? '+' + avgDayRet.toFixed(2) : avgDayRet.toFixed(2)}
                    suffix="%"
                    valueStyle={{ color: avgDayRet > 0 ? '#cf1322' : avgDayRet < 0 ? '#3f8600' : undefined, fontSize: 20 }}
                  />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
                  <Statistic
                    title={<span>已追踪批次 <Tooltip title={<>含义：已完成次日收益计算的推荐批次数 / 总批次数<br/>作用：反映数据完整度，样本量越大指标越可靠<br/>阈值：建议至少追踪10期以上再评估策略稳定性<br/>影响：样本过少时（&lt;5期），命中率和收益波动大，参考价值有限</>}><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                    value={trackedBatches.length}
                    suffix={`/ ${batchHistory.length}`}
                    valueStyle={{ fontSize: 20 }}
                  />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
                  {(() => {
                    const entry = batchHistory?.find(b => b.recommendDate === reviewDate);
                    const tag = qualityTag ? qConfig[qualityTag] : null;
                    return (
                      <Statistic
                        title={<span>当前批次质量 <Tooltip title={<>含义：当前批次及前4期已追踪批次的滚动平均命中率<br/>作用：比单期命中率更稳定，反映当前推荐质量趋势<br/>阈值：≥60% 高质量 / 40%-60% 正常 / &lt;40% 低质量<br/>影响：低质量时建议减少仓位或暂停跟单，等待质量回升</>}><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                        value={tag ? tag.text : '-'}
                        suffix={entry?.rollingAvgHitRate != null ? `(${(entry.rollingAvgHitRate * 100).toFixed(0)}%)` : ''}
                        valueStyle={{ color: tag ? tag.color : '#8c8c8c', fontSize: 20 }}
                      />
                    );
                  })()}
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
                  <Statistic
                    title={<span>最近批次命中反馈 <Tooltip title={<>含义：最近一期已追踪批次的命中率及对应的自动反馈动作<br/>作用：根据上期表现动态调整下一批推荐数量，降低风险<br/>阈值：≥60% 正常(20只) / 40%-60% 观察中(20只) / &lt;40% 缩减(15只)<br/>影响：命中过低时自动减少推荐数量，过滤噪音，保留高置信度标的</>}><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                    value={latest && latest.tracked > 0 ? (latest.hitRate < 0.4 ? '⚠ 已缩减' : latest.hitRate >= 0.6 ? '✅ 正常' : '↗ 观察中') : '等待数据'}
                    valueStyle={{ color: latest && latest.tracked > 0 ? (latest.hitRate < 0.4 ? '#ff4d4f' : latest.hitRate >= 0.6 ? '#52c41a' : '#fa8c16') : '#8c8c8c', fontSize: 18 }}
                  />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small" styles={{ body: { padding: '12px 16px', display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' } }}>
                  <Button
                    icon={<ReloadOutlined spin={trackingLoading} />}
                    loading={trackingLoading}
                    onClick={handleTrack}
                    size="small"
                  >
                    手动追踪
                  </Button>
                </Card>
              </Col>
            </Row>

            {/* 命中率趋势图 */}
            {trackedBatches.length >= 2 && (
              <Card
                size="small"
                title={
                  <span>
                    <LineChartOutlined /> 命中率 & 次日均收益趋势
                    <Tooltip
                      styles={{ root: {maxWidth: 560} }}
                      title={
                        <div style={{ fontSize: 12, lineHeight: '20px' }}>
                          <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>图表指标说明</div>
                          <table style={{ borderCollapse: 'collapse', width: '100%', marginBottom: 10 }}>
                            <thead>
                              <tr style={{ borderBottom: '1px solid #555' }}>
                                <th style={{ textAlign: 'left', padding: '2px 6px', fontWeight: 'bold' }}>指标</th>
                                <th style={{ textAlign: 'left', padding: '2px 6px', fontWeight: 'bold' }}>含义</th>
                              </tr>
                            </thead>
                            <tbody>
                              <tr style={{ borderBottom: '1px solid #434343' }}>
                                <td style={{ padding: '2px 6px', whiteSpace: 'nowrap' }}>次日命中率</td>
                                <td style={{ padding: '2px 6px' }}>推荐日 T 的名单中，T+1 当天收益为正的股票占比</td>
                              </tr>
                              <tr style={{ borderBottom: '1px solid #434343' }}>
                                <td style={{ padding: '2px 6px', whiteSpace: 'nowrap' }}>次日均收益</td>
                                <td style={{ padding: '2px 6px' }}>推荐日 T 名单的所有股票，T+1 当天的平均涨跌幅</td>
                              </tr>
                              <tr style={{ borderBottom: '1px solid #434343' }}>
                                <td style={{ padding: '2px 6px', whiteSpace: 'nowrap' }}>一周收益</td>
                                <td style={{ padding: '2px 6px' }}>推荐日 T 名单，T+1 到 T+5（约一周）的平均持有收益</td>
                              </tr>
                              <tr>
                                <td style={{ padding: '2px 6px', whiteSpace: 'nowrap' }}>一月均收益</td>
                                <td style={{ padding: '2px 6px' }}>推荐日 T 名单，T+1 到 T+22（约一个月）的平均持有收益</td>
                              </tr>
                            </tbody>
                          </table>
                          <div style={{ marginBottom: 10, color: '#aaa' }}>
                            提示：tooltip 显示的日期是<b>推荐日</b>，不是收益结算日。例如 26% 命中率代表：当天推荐的股票里，只有 26% 在次日上涨（柱子变红/深色的原因）。
                          </div>
                          <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>蓝色折线的含义</div>
                          <div style={{ marginBottom: 4 }}>蓝色线是<b>次日均收益趋势</b>，对应右侧 Y 轴（收益率 %）。</div>
                          <div style={{ marginBottom: 4 }}>• 蓝线显著上扬 → 那批推荐次日平均收益高</div>
                          <div style={{ marginBottom: 4 }}>• 蓝线跌入负区间 → 那批推荐次日平均亏损（即使命中率过半，也可能赚小赔大、均值被拉低）</div>
                          <div style={{ marginBottom: 10, color: '#ff7875' }}>
                            注意：命中率高 ≠ 赚钱。命中率 53% 但均收益为负，说明涨得少、跌得多。
                          </div>
                          <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>整体读图建议</div>
                          <div style={{ marginBottom: 4, color: '#52c41a' }}>好信号 = 命中率高（柱子高/绿色）AND 蓝线在正区间</div>
                          <div style={{ marginBottom: 2, color: '#ff4d4f' }}>坏信号 = 命中率低（柱子矮/红色）OR 蓝线跌入负区间（即使命中率 {'>'} 50%）</div>
                        </div>
                      }
                    >
                      <QuestionCircleOutlined style={{ color: '#91caff', fontSize: 13, marginLeft: 6, cursor: 'help' }} />
                    </Tooltip>
                  </span>
                }
                style={{ marginBottom: 16 }}
              >
                <ReactEcharts option={trendOption} style={{ height: 260 }} />
              </Card>
            )}

            {/* 推荐复盘：最佳/最差 */}
            {topBottom && ((topBottom.best3 && topBottom.best3.length > 0) || (topBottom.worst3 && topBottom.worst3.length > 0)) && (
              <Card
                size="small"
                style={{ marginBottom: 16 }}
                title={<span><RiseOutlined /> 本期推荐复盘 — 次日表现最佳/最差</span>}
              >
                <Row gutter={16}>
                  <Col span={12}>
                    <div style={{ fontWeight: 600, marginBottom: 8, color: '#cf1322', fontSize: 13 }}>
                      ✅ 最佳 3 只（次日涨幅最高）
                    </div>
                    {topBottom.best3 && topBottom.best3.length > 0 ? (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid #f0f0f0', color: '#8c8c8c' }}>
                            <th style={{ textAlign: 'left', padding: '4px 8px' }}>股票</th>
                            <th style={{ textAlign: 'left', padding: '4px 8px' }}>行业</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>次日收益</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>一周收益</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>一月收益</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>分析得分</th>
                          </tr>
                        </thead>
                        <tbody>
                          {topBottom.best3.map((r, i) => (
                            <tr key={i} style={{ borderBottom: '1px solid #fafafa' }}>
                              <td style={{ padding: '4px 8px' }}>
                                <Text strong>{r.stockName}</Text>
                                <Text type="secondary" style={{ marginLeft: 4, fontSize: 11 }}>{r.stockCode}</Text>
                              </td>
                              <td style={{ padding: '4px 8px', color: '#595959' }}>{r.industry || '-'}</td>
                              <td style={{ padding: '4px 8px', textAlign: 'right', color: '#cf1322', fontWeight: 600 }}>
                                +{r.nextDayReturn.toFixed(2)}%
                              </td>
                              <td style={{ padding: '4px 8px', textAlign: 'right', color: (r.nextWeekReturn || 0) > 0 ? '#cf1322' : r.nextWeekReturn != null ? '#3f8600' : '#8c8c8c' }}>
                                {r.nextWeekReturn != null ? (r.nextWeekReturn > 0 ? '+' : '') + r.nextWeekReturn.toFixed(2) + '%' : '-'}
                              </td>
                              <td style={{ padding: '4px 8px', textAlign: 'right', color: (r.nextMonthReturn || 0) > 0 ? '#cf1322' : r.nextMonthReturn != null ? '#3f8600' : '#8c8c8c' }}>
                                {r.nextMonthReturn != null ? (r.nextMonthReturn > 0 ? '+' : '') + r.nextMonthReturn.toFixed(2) + '%' : '-'}
                              </td>
                              <td style={{ padding: '4px 8px', textAlign: 'right' }}>
                                {r.analysisScorePct != null ? (r.analysisScorePct * 100).toFixed(0) + '%' : '-'}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : <Text type="secondary">暂无追踪数据</Text>}
                  </Col>
                  <Col span={12}>
                    <div style={{ fontWeight: 600, marginBottom: 8, color: '#3f8600', fontSize: 13 }}>
                      ❌ 最差 3 只（次日跌幅最大）
                    </div>
                    {topBottom.worst3 && topBottom.worst3.length > 0 ? (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid #f0f0f0', color: '#8c8c8c' }}>
                            <th style={{ textAlign: 'left', padding: '4px 8px' }}>股票</th>
                            <th style={{ textAlign: 'left', padding: '4px 8px' }}>行业</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>次日收益</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>一周收益</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>一月收益</th>
                            <th style={{ textAlign: 'right', padding: '4px 8px' }}>分析得分</th>
                          </tr>
                        </thead>
                        <tbody>
                          {topBottom.worst3.map((r, i) => (
                            <tr key={i} style={{ borderBottom: '1px solid #fafafa' }}>
                              <td style={{ padding: '4px 8px' }}>
                                <Text strong>{r.stockName}</Text>
                                <Text type="secondary" style={{ marginLeft: 4, fontSize: 11 }}>{r.stockCode}</Text>
                              </td>
                              <td style={{ padding: '4px 8px', color: '#595959' }}>{r.industry || '-'}</td>
                              <td style={{ padding: '4px 8px', textAlign: 'right', color: '#3f8600', fontWeight: 600 }}>
                                {r.nextDayReturn.toFixed(2)}%
                              </td>
                              <td style={{ padding: '4px 8px', textAlign: 'right', color: (r.nextWeekReturn || 0) > 0 ? '#cf1322' : r.nextWeekReturn != null ? '#3f8600' : '#8c8c8c' }}>
                                {r.nextWeekReturn != null ? (r.nextWeekReturn > 0 ? '+' : '') + r.nextWeekReturn.toFixed(2) + '%' : '-'}
                              </td>
                              <td style={{ padding: '4px 8px', textAlign: 'right', color: (r.nextMonthReturn || 0) > 0 ? '#cf1322' : r.nextMonthReturn != null ? '#3f8600' : '#8c8c8c' }}>
                                {r.nextMonthReturn != null ? (r.nextMonthReturn > 0 ? '+' : '') + r.nextMonthReturn.toFixed(2) + '%' : '-'}
                              </td>
                              <td style={{ padding: '4px 8px', textAlign: 'right' }}>
                                {r.analysisScorePct != null ? (r.analysisScorePct * 100).toFixed(0) + '%' : '-'}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : <Text type="secondary">暂无追踪数据</Text>}
                  </Col>
                </Row>

                {/* 深度归因分析 */}
                {topBottom.analysis && (
                  <div style={{ marginTop: 16, padding: '12px 16px', background: '#fafafa', borderRadius: 4, border: '1px solid #f0f0f0' }}>
                    <div style={{ fontWeight: 600, marginBottom: 10, fontSize: 13 }}>
                      🔍 深度归因分析 — 最佳 vs 最差差异来源
                    </div>
                    <Row gutter={[16, 12]}>
                      {/* 行业分布对比 */}
                      {topBottom.analysis.industryDiff && (() => {
                        const id = topBottom.analysis.industryDiff;
                        const bestList = Object.entries(id.bestIndustries || {});
                        const worstList = Object.entries(id.worstIndustries || {});
                        const worstOnly = id.worstOnlyIndustries || [];
                        return (
                          <Col span={8}>
                            <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 12, color: '#595959' }}>🏭 行业分布对比</div>
                            <div style={{ fontSize: 12, lineHeight: '20px' }}>
                              <div style={{ color: '#cf1322' }}>最佳: {bestList.length > 0 ? bestList.map(([k,v]) => `${k}(${v})`).join('、') : '-'}</div>
                              <div style={{ color: '#3f8600' }}>最差: {worstList.length > 0 ? worstList.map(([k,v]) => `${k}(${v})`).join('、') : '-'}</div>
                              {worstOnly.length > 0 && (
                                <div style={{ color: '#fa8c16', marginTop: 4 }}>
                                  ⚠ 仅最差组出现: {worstOnly.join('、')}（弱势行业拖累）
                                </div>
                              )}
                            </div>
                          </Col>
                        );
                      })()}

                      {/* 市值中位数对比 */}
                      {topBottom.analysis.marketCapDiff && (() => {
                        const md = topBottom.analysis.marketCapDiff;
                        const fmt = (v) => {
                          if (v == null) return '-';
                          if (v >= 1e12) return (v / 1e12).toFixed(1) + '万亿';
                          if (v >= 1e8) return (v / 1e8).toFixed(1) + '亿';
                          return (v / 1e4).toFixed(0) + '万';
                        };
                        return (
                          <Col span={8}>
                            <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 12, color: '#595959' }}>💰 市值中位数对比</div>
                            <div style={{ fontSize: 12, lineHeight: '20px' }}>
                              <div>最佳组: <Text strong>{fmt(md.bestMedianCap)}</Text></div>
                              <div>最差组: <Text strong>{fmt(md.worstMedianCap)}</Text></div>
                              {md.hint && <div style={{ color: '#1890ff', marginTop: 4 }}>💡 {md.hint}</div>}
                            </div>
                          </Col>
                        );
                      })()}

                      {/* 得分差距分析 */}
                      {topBottom.analysis.scoreDiff && (() => {
                        const sd = topBottom.analysis.scoreDiff;
                        return (
                          <Col span={8}>
                            <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 12, color: '#595959' }}>
                              📊 得分差距分析
                              {sd.dominantGap && <Tag color={sd.dominantGap === 'FACTOR' ? 'purple' : 'blue'} style={{ fontSize: 10, lineHeight: '16px', marginLeft: 6 }}>
                                {sd.dominantGap === 'FACTOR' ? '因子主导' : '分析主导'}
                              </Tag>}
                            </div>
                            <div style={{ fontSize: 12, lineHeight: '20px' }}>
                              <div>因子得分: 最佳 {(sd.bestAvgFactorScore * 100).toFixed(1)} vs 最差 {(sd.worstAvgFactorScore * 100).toFixed(1)}</div>
                              <div>分析得分: 最佳 {(sd.bestAvgAnalysisPct * 100).toFixed(1)}% vs 最差 {(sd.worstAvgAnalysisPct * 100).toFixed(1)}%</div>
                              {sd.hint && <div style={{ color: '#1890ff', marginTop: 4 }}>💡 {sd.hint}</div>}
                            </div>
                          </Col>
                        );
                      })()}
                    </Row>

                    {/* 失败模式识别 */}
                    {topBottom.analysis.failurePatterns && topBottom.analysis.failurePatterns.length > 0 && (
                      <div style={{ marginTop: 12, padding: '8px 12px', background: '#fff7e6', borderRadius: 4, border: '1px solid #ffe58f' }}>
                        <div style={{ fontWeight: 500, marginBottom: 4, fontSize: 12, color: '#d48806' }}>⚠ 失败模式识别</div>
                        {topBottom.analysis.failurePatterns.map((p, i) => (
                          <div key={i} style={{ fontSize: 12, lineHeight: '18px', color: '#8c8c8c' }}>• {p}</div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </Card>
            )}
          </>
        );
      })()}

      {/* 底部说明 */}
      <div style={{ marginTop: 12, display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          <StockOutlined /> 综合得分 = Regime-Adaptive 动态权重融合 | 牛市因子60%+分析40%，熊市因子40%+分析60%，震荡均衡50:50 | 12因子: 动量/波动/价值×3/技术×2/换手率/质量×3/成长
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {reviewDate && `策略${reviewStrategyId} | ${reviewDate} | ${recommendations.length} 只`}
        </Text>
      </div>

      {/* ── 方案B: 黑名单管理 Modal ── */}
      <Modal
        title={
          <span>
            <StopOutlined style={{ color: '#cf1322', marginRight: 8 }} />
            股票黑名单管理
            {selectedStrategyId && <Tag style={{ marginLeft: 8 }}>策略ID: {selectedStrategyId}</Tag>}
            {blacklist.filter(b => !isExpired(b.blacklist_until)).length > 0 &&
              <Tag color="red">生效中: {blacklist.filter(b => !isExpired(b.blacklist_until)).length}</Tag>}
          </span>
        }
        open={blacklistModalVisible}
        onCancel={() => setBlacklistModalVisible(false)}
        width={720}
        footer={[
          <Button key="clear" danger onClick={handleClearBlacklist} disabled={!selectedStrategyId}>
            全部解封
          </Button>,
          <Button key="refresh" icon={<ReloadOutlined />} onClick={loadBlacklist}>
            刷新
          </Button>,
          <Button key="close" type="primary" onClick={() => setBlacklistModalVisible(false)}>
            关闭
          </Button>,
        ]}
      >
        <Spin spinning={blacklistLoading}>
          {blacklist.length === 0 ? (
            <div style={{ textAlign: 'center', padding: 40, color: '#8c8c8c' }}>
              <StopOutlined style={{ fontSize: 48, marginBottom: 16, display: 'block' }} />
              当前黑名单为空<br />
              <Text type="secondary" style={{ fontSize: 12 }}>
                推荐列表中每行有「屏蔽」按钮可手动添加，追踪后系统会自动将连续失利或踩雷的股票加入黑名单
              </Text>
            </div>
          ) : (
            <Table
              size="small"
              dataSource={blacklist}
              rowKey="id"
              pagination={false}
              scroll={{ y: 400 }}
              columns={[
                {
                  title: '状态',
                  width: 65,
                  render: (_, bl) => isExpired(bl.blacklist_until)
                    ? <Tag color="default">已过期</Tag>
                    : <Tag color="red">生效中</Tag>,
                },
                {
                  title: '股票',
                  width: 150,
                  render: (_, bl) => (
                    <span>
                      <Link to={`/stock-analysis?code=${bl.stockCode}`} style={{ fontWeight: 500 }}>{bl.stockName}</Link>
                      <Text type="secondary" style={{ marginLeft: 4 }}>({bl.stockCode})</Text>
                    </span>
                  ),
                },
                {
                  title: '原因',
                  width: 120,
                  render: (_, bl) => {
                    const r = REASON_MAP[bl.reason] || { label: bl.reason, color: 'default', desc: '' };
                    return <Tag color={r.color}>{r.label}</Tag>;
                  },
                },
                {
                  title: '详情',
                  ellipsis: true,
                  dataIndex: 'reasonDetail',
                },
                {
                  title: '到期日',
                  width: 105,
                  render: (_, bl) => bl.blacklist_until
                    ? dayjs(bl.blacklist_until).format('YYYY-MM-DD')
                    : <Text type="secondary">永久</Text>,
                },
                {
                  title: '加入方式',
                  width: 70,
                  dataIndex: 'createdBy',
                },
                {
                  title: '操作',
                  width: 70,
                  fixed: 'right',
                  render: (_, bl) => (
                    <Popconfirm
                      title="确认解封？"
                      description={`解封后该股票可再次被推荐`}
                      onConfirm={() => handleRemoveFromBlacklist(bl.id)}
                      okText="确认"
                      cancelText="取消"
                    >
                      <Button type="link" size="small" icon={<UnlockOutlined />} style={{ padding: 0 }}>
                        解封
                      </Button>
                    </Popconfirm>
                  ),
                },
              ]}
            />
          )}
        </Spin>

        {/* 黑名单规则说明 */}
        <Divider style={{ margin: '12px 0 6px', borderColor: '#333' }} />
        <div style={{ fontSize: 11, color: '#8c8c8c', lineHeight: '18px' }}>
          <Text strong style={{ color: '#b37feb' }}>自动规则：</Text>
          <Tag color="orange" style={{ fontSize: 10 }}>连续失利</Tag> 连续3次推荐次日收益为负 → 拉黑30天 |&nbsp;
          <Tag color="gold" style={{ fontSize: 10 }}>低命中率</Tag> 近5次命中率{'<'}20% → 拉黑14天 |&nbsp;
          <Tag color="red" style={{ fontSize: 10 }}>踩雷</Tag> 单日跌幅≥8% → 拉黑60天
        </div>
      </Modal>

      {/* ── 方案C: 策略置信度详情弹窗 ── */}
      <Modal
        title={
          <span>
            <span style={{ color: confidenceData?.level ? (CONFIDENCE_CONFIG[confidenceData.level]?.color || '#8c8c8c') : '#8c8c8c', marginRight: 8 }}>
              {confidenceData?.level ? CONFIDENCE_CONFIG[confidenceData.level]?.icon : '📊'}
            </span>
            策略置信度详情
            {confidenceData?.score != null &&
              <Tag color={CONFIDENCE_CONFIG[confidenceData.level]?.color} style={{ marginLeft: 8 }}>
                {confidenceData.score}分 · {CONFIDENCE_CONFIG[confidenceData.level]?.text || '未知'}
              </Tag>}
          </span>
        }
        open={confidenceWarningVisible}
        onCancel={() => setConfidenceWarningVisible(false)}
        width={640}
        footer={[
          <Button key="close" type="primary" onClick={() => setConfidenceWarningVisible(false)}>
            关闭
          </Button>,
        ]}
      >
        {confidenceData && (
          <div>
            {/* 综合得分进度条 */}
            <div style={{ marginBottom: 20 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <Text strong>综合置信度</Text>
                <Text strong style={{ color: CONFIDENCE_CONFIG[confidenceData.level]?.color }}>
                  {confidenceData.score ?? 'N/A'}/100
                </Text>
              </div>
              <Progress
                percent={confidenceData.score ?? 0}
                strokeColor={CONFIDENCE_CONFIG[confidenceData.level]?.color}
                showInfo={false}
                size="default"
              />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: '#8c8c8c', marginTop: 4 }}>
                <span>建议暂停{'<'}30)</span>
                <span>偏低(30-49)</span>
                <span>中等(50-69)</span>
                <span>高(70+)</span>
              </div>
            </div>

            {/* 维度得分明细 */}
            <Card size="small" title="维度得分明细" style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col span={12}>
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <Text type="secondary">命中率维度</Text>
                      <Text>{confidenceData.hitRateScore ?? '-'}/40</Text>
                    </div>
                    <Progress percent={confidenceData.hitRateScore != null ? confidenceData.hitRateScore * 2.5 : 0} size="small" />
                    <div style={{ fontSize: 11, color: '#8c8c8c' }}>
                      实际命中率: {confidenceData.hitRateValue != null ? (confidenceData.hitRateValue * 100).toFixed(1) + '%' : '-'}
                    </div>
                  </div>
                </Col>
                <Col span={12}>
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <Text type="secondary">平均收益维度</Text>
                      <Text>{confidenceData.returnScore ?? '-'}/25</Text>
                    </div>
                    <Progress percent={confidenceData.returnScore != null ? confidenceData.returnScore * 4 : 0} size="small" />
                    <div style={{ fontSize: 11, color: '#8c8c8c' }}>
                      平均收益率: {confidenceData.avgReturnValue != null ? confidenceData.avgReturnValue.toFixed(2) + '%' : '-'}
                    </div>
                  </div>
                </Col>
                <Col span={12}>
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <Text type="secondary">最大回撤维度</Text>
                      <Text>{confidenceData.drawdownScore ?? '-'}/20</Text>
                    </div>
                    <Progress percent={confidenceData.drawdownScore != null ? confidenceData.drawdownScore * 5 : 0} size="small" />
                    <div style={{ fontSize: 11, color: '#8c8c8c' }}>
                      最大单日跌幅: {confidenceData.maxDrawdownValue != null ? confidenceData.maxDrawdownValue.toFixed(2) + '%' : '-'}
                    </div>
                  </div>
                </Col>
                <Col span={12}>
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <Text type="secondary">稳定性维度</Text>
                      <Text>{confidenceData.volatilityScore ?? '-'}/15</Text>
                    </div>
                    <Progress percent={confidenceData.volatilityScore != null ? (confidenceData.volatilityScore * 100 / 15).toFixed(1) : 0} size="small" />
                    <div style={{ fontSize: 11, color: '#8c8c8c' }}>
                      收益标准差: {confidenceData.volatilityValue != null ? confidenceData.volatilityValue.toFixed(2) + '%' : '-'}
                    </div>
                  </div>
                </Col>
              </Row>
            </Card>

            {/* 建议 */}
            <div style={{ fontSize: 12, lineHeight: '22px', color: '#595959' }}>
              <Text strong>当前建议：</Text>
              {confidenceData.level === 'HIGH' && <span>策略表现优秀，可正常使用。</span>}
              {confidenceData.level === 'NORMAL' && <span>策略表现中等，建议持续追踪观察。</span>}
              {confidenceData.level === 'LOW' && <span>⚠️ 策略表现偏弱，系统已自动降低推荐数量，建议关注策略表现。</span>}
              {confidenceData.level === 'SUSPENDED' && <span>🛑 策略表现较差，建议暂停使用该策略，或回检策略参数。</span>}
              {confidenceData.level === 'UNTRAINED' && <span>暂无足够追踪数据，建议至少追踪1期后再评估。</span>}
            </div>

            <Divider style={{ margin: '12px 0 8px' }} />
            <div style={{ fontSize: 11, color: '#8c8c8c' }}>
              计算样本数: {confidenceData.sampleSize || 0} 条 |
              数据截止: {confidenceData.dataAsOfDate || '-'}
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
