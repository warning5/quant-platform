import React, { useEffect, useState, useCallback, useMemo } from 'react';
import {
  Card, Button, Space, Typography, Table, Tag, InputNumber, Select,
  DatePicker, Row, Col, Statistic, Divider, Tooltip, Badge,
  Empty, Spin, message, Progress, Alert, Form, Popover, Modal, Input, Slider, Tabs, Checkbox,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, PlayCircleOutlined, FilterOutlined,
  InfoCircleOutlined, ReloadOutlined, SwapOutlined, QuestionCircleOutlined,
  SaveOutlined, CopyOutlined, StarOutlined, WarningOutlined,
  SafetyCertificateOutlined, ArrowUpOutlined, ArrowDownOutlined,
  PlusSquareOutlined, MinusSquareOutlined, ThunderboltOutlined, LineChartOutlined, FundOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, RiseOutlined, StockOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import api, { factorApi } from '../../api';

const { Title, Text, Paragraph } = Typography;
const { Option } = Select;
const { TextArea } = Input;

/* ── 常量 ─────────────────────────────────────────────────────────── */
const CATEGORY_COLOR = {
  MOMENTUM: 'blue', VALUE: 'gold', QUALITY: 'green', VOLATILITY: 'orange',
  TECHNICAL: 'purple', FINANCIAL: 'green', SENTIMENT: 'magenta',
  LIQUIDITY: 'volcano', VOLUME_PRICE: 'geekblue', CUSTOM: 'default',
};
const CATEGORY_LABEL = {
  MOMENTUM: '动量', VALUE: '价值', QUALITY: '质量', VOLATILITY: '波动率',
  TECHNICAL: '技术', FINANCIAL: '财务', SENTIMENT: '情绪',
  LIQUIDITY: '流动性', VOLUME_PRICE: '量价', CUSTOM: '自定义',
};

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
const FILTER_OP_OPTIONS = [
  { value: 'NONE', label: '无' },
  { value: 'GT', label: '大于 (>)' },
  { value: 'GTE', label: '大于等于 (≥)' },
  { value: 'LT', label: '小于 (<)' },
  { value: 'LTE', label: '小于等于 (≤)' },
];

/* ── 预设组合说明数据 ────────────────────────────────────────────── */
const PRESET_DESCRIPTIONS = {
  '经典多因子': {
    desc: '均衡配置：价值+动量+波动率+流动性+质量+成长，适合中长期持有',
    factors: [
      { code: 'SIZE', name: '市值', direction: '反向', weight: 1, reason: 'A 股小市值长期跑赢大盘' },
      { code: 'MOM20', name: '20日动量', direction: '正向', weight: 1, reason: '中短期趋势跟踪' },
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 1, reason: '低波动长期收益更优' },
      { code: 'AMIHUD', name: '非流动性', direction: '反向', weight: 1, reason: '低非流动性溢价' },
      { code: 'FIN_ROE', name: 'ROE', direction: '正向', weight: 1, reason: '盈利能力强的公司' },
      { code: 'FIN_REVENUE_YOY', name: '营收增速', direction: '正向', weight: 1, reason: '营收增长驱动股价' },
      { code: 'RSI14', name: 'RSI(14)', direction: '反向', weight: 0.5, reason: '未超买区域' },
    ],
  },
  '小盘成长': {
    desc: '聚焦小市值+高成长+高ROE，适合追求高弹性的投资者',
    factors: [
      { code: 'SIZE', name: '市值', direction: '反向', weight: 2, reason: '核心因子：小市值溢价' },
      { code: 'FIN_REVENUE_YOY', name: '营收增速', direction: '正向', weight: 1.5, reason: '高成长驱动' },
      { code: 'FIN_ROE', name: 'ROE', direction: '正向', weight: 1, reason: '盈利质量保障' },
      { code: 'FIN_NET_PROFIT_YOY', name: '净利润增速', direction: '正向', weight: 1, reason: '利润持续增长' },
      { code: 'MOM20', name: '20日动量', direction: '正向', weight: 0.5, reason: '趋势确认' },
    ],
  },
  '低波动红利': {
    desc: '低波动+高盈利质量，适合稳健型投资者',
    factors: [
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 2, reason: '核心：低波动异象' },
      { code: 'FIN_EARNINGS_QUALITY', name: '盈余质量', direction: '正向', weight: 1.5, reason: '真实盈利能力' },
      { code: 'FIN_CF_TO_NP', name: '现金流/净利润', direction: '正向', weight: 1, reason: '现金流充裕' },
      { code: 'FIN_BPS', name: '每股净资产', direction: '正向', weight: 1, reason: '安全边际' },
      { code: 'VOLUME_RATIO', name: '量比', direction: '正向', weight: 0.5, reason: '资金关注度' },
    ],
  },
  '技术动量': {
    desc: '纯技术面选股：趋势跟踪+量价确认，适合短线交易',
    factors: [
      { code: 'MOM20', name: '20日动量', direction: '正向', weight: 1.5, reason: '中短期趋势' },
      { code: 'MOM60', name: '60日动量', direction: '正向', weight: 1, reason: '中长期趋势' },
      { code: 'RSI14', name: 'RSI(14)', direction: '反向', weight: 1, reason: '回避超买' },
      { code: 'MACD', name: 'MACD', direction: '正向', weight: 1, reason: '趋势动能' },
      { code: 'VOLUME_RATIO', name: '量比', direction: '正向', weight: 1, reason: '放量确认' },
      { code: 'BOLL_POS', name: '布林位置', direction: '正向', weight: 0.5, reason: '中轨上方' },
    ],
  },
  '价值投资': {
    desc: '深度价值：低估值+高质量+高安全边际',
    factors: [
      { code: 'SIZE', name: '市值', direction: '反向', weight: 1, reason: '小盘估值优势' },
      { code: 'FIN_EARNINGS_YIELD', name: '盈利收益率', direction: '正向', weight: 1.5, reason: '核心：估值锚' },
      { code: 'FIN_BPS', name: '每股净资产', direction: '正向', weight: 1, reason: '安全边际' },
      { code: 'FIN_GROSS_MARGIN', name: '毛利率', direction: '正向', weight: 1, reason: '竞争壁垒' },
      { code: 'FIN_CF_QUALITY', name: '现金流质量', direction: '正向', weight: 1, reason: '盈利真实性' },
      { code: 'FIN_DEBT_TO_ASSET', name: '资产负债率', direction: '反向', weight: 0.5, reason: '财务风险控制' },
    ],
  },
  '趋势突破': {
    desc: '量价配合的趋势跟踪：强动量+放量确认+低波动率偏离，捕捉持续上涨个股',
    factors: [
      { code: 'MOM60', name: '60日动量', direction: '正向', weight: 2, reason: '核心：中长期强趋势（>5%）' },
      { code: 'MOM20', name: '20日动量', direction: '正向', weight: 1.5, reason: '中短期趋势确认（>0）' },
      { code: 'VOLUME_RATIO', name: '量比', direction: '正向', weight: 1.5, reason: '放量确认（>1倍）' },
      { code: 'BOLL_POS', name: '布林位置', direction: '正向', weight: 1, reason: '中轨上方运行' },
      { code: 'VPCORR20', name: '量价相关性', direction: '正向', weight: 1, reason: '量价齐升（>0）' },
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 0.5, reason: '趋势稳定性' },
    ],
  },
  '高盈利质量': {
    desc: '聚焦盈利能力强、利润含金量高、财务健康的优质公司，适合价值成长型配置',
    factors: [
      { code: 'FIN_ROE', name: 'ROE', direction: '正向', weight: 2, reason: '核心：盈利能力（>10%）' },
      { code: 'FIN_GROSS_MARGIN', name: '毛利率', direction: '正向', weight: 1.5, reason: '竞争壁垒（>25%）' },
      { code: 'FIN_EARNINGS_QUALITY', name: '盈余质量', direction: '正向', weight: 1.5, reason: '利润含金量（>0.8）' },
      { code: 'FIN_CF_TO_NP', name: '现金流/净利润', direction: '正向', weight: 1, reason: '盈利真实性（>0.8）' },
      { code: 'FIN_DEBT_TO_ASSET', name: '资产负债率', direction: '反向', weight: 1, reason: '低杠杆（<50%）' },
      { code: 'FIN_NET_MARGIN', name: '净利率', direction: '正向', weight: 0.5, reason: '利润转化效率（>5%）' },
    ],
  },
  '成长加速': {
    desc: '营收净利双高增长+趋势加速，捕捉处于高速成长期的公司',
    factors: [
      { code: 'FIN_REVENUE_YOY', name: '营收增速', direction: '正向', weight: 2, reason: '核心：收入高增长（>15%）' },
      { code: 'FIN_NET_PROFIT_YOY', name: '净利润增速', direction: '正向', weight: 2, reason: '核心：利润高增长（>20%）' },
      { code: 'PRICE_MOM_ACC', name: '价格动量加速度', direction: '正向', weight: 1.5, reason: '趋势加速中' },
      { code: 'FIN_ROE', name: 'ROE', direction: '正向', weight: 1, reason: '盈利质量保障（>8%）' },
      { code: 'MOM20', name: '20日动量', direction: '正向', weight: 1, reason: '趋势确认（>0）' },
      { code: 'SIZE', name: '市值', direction: '反向', weight: 0.5, reason: '小盘弹性' },
    ],
  },
  '低估反转': {
    desc: '超跌低估值个股的均值回归：短期弱势+基本面支撑+估值低位，适合逆向投资',
    factors: [
      { code: 'REVERSAL5', name: '5日反转', direction: '正向', weight: 1.5, reason: '核心：短期超跌反弹' },
      { code: 'RSI14', name: 'RSI(14)', direction: '反向', weight: 1.5, reason: '超卖区间（≤40）' },
      { code: 'FIN_BPS', name: '每股净资产', direction: '正向', weight: 1.5, reason: '估值安全垫（>2元）' },
      { code: 'FIN_ROE', name: 'ROE', direction: '正向', weight: 1, reason: '基本面支撑（>5%）' },
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 1, reason: '低波动更稳健' },
      { code: 'FIN_DEBT_TO_ASSET', name: '资产负债率', direction: '反向', weight: 0.5, reason: '排除高负债（<65%）' },
    ],
  },
  '量价异动': {
    desc: '捕捉资金异常涌入迹象：成交量突变+量价背离修复+情绪因子，适合短线博弈',
    factors: [
      { code: 'VOLUME_SURPRISE', name: '成交量惊喜', direction: '正向', weight: 2, reason: '核心：量能突破（>0.1）' },
      { code: 'TURNOVER_ANOMALY', name: '换手率异常', direction: '正向', weight: 1.5, reason: '交易活跃异常（>1.2）' },
      { code: 'VROC12', name: '12日量变速率', direction: '正向', weight: 1.5, reason: '量能持续放大' },
      { code: 'VPCORR20', name: '量价相关性', direction: '正向', weight: 1, reason: '量价齐升（>0.2）' },
      { code: 'MOM5', name: '5日动量', direction: '正向', weight: 1, reason: '短期向上突破' },
      { code: 'SIZE', name: '市值', direction: '反向', weight: 0.5, reason: '小盘易拉升' },
    ],
  },
  '财务健康': {
    desc: '偿债能力强+营运效率高+现金流充裕，规避财务风险，适合防御型配置',
    factors: [
      { code: 'FIN_CURRENT_RATIO', name: '流动比率', direction: '正向', weight: 1.5, reason: '核心：短期偿债能力（>1.5）' },
      { code: 'FIN_DEBT_TO_ASSET', name: '资产负债率', direction: '反向', weight: 1.5, reason: '核心：低杠杆（<50%）' },
      { code: 'FIN_CF_TO_REVENUE', name: '现金流/营收', direction: '正向', weight: 1.5, reason: '核心：营收含金量（>5%）' },
      { code: 'FIN_AR_TURNOVER', name: '应收账款周转率', direction: '正向', weight: 1, reason: '回款效率高' },
      { code: 'FIN_ASSETS_TURNOVER', name: '总资产周转率', direction: '正向', weight: 1, reason: '资产运营效率' },
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 0.5, reason: '低波动防御' },
    ],
  },
  '综合打分': {
    desc: '技术+基本面+情绪全覆盖：八因子均衡打分，降低单一因子的随机性',
    factors: [
      { code: 'FIN_ROE', name: 'ROE', direction: '正向', weight: 1.2, reason: '盈利质量' },
      { code: 'FIN_REVENUE_YOY', name: '营收增速', direction: '正向', weight: 1.2, reason: '成长能力' },
      { code: 'MOM60', name: '60日动量', direction: '正向', weight: 1, reason: '趋势跟踪' },
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 1, reason: '低波动溢价' },
      { code: 'SIZE', name: '市值', direction: '反向', weight: 1, reason: '小盘溢价' },
      { code: 'FIN_EARNINGS_QUALITY', name: '盈余质量', direction: '正向', weight: 1, reason: '利润真实性' },
      { code: 'VOLUME_RATIO', name: '量比', direction: '正向', weight: 0.8, reason: '资金关注度' },
      { code: 'RSI14', name: 'RSI(14)', direction: '反向', weight: 0.8, reason: '回避超买（≤70）' },
    ],
  },
  '经典技术指标': {
    desc: '均线趋势+MACD动能+RSI超卖回避+布林带位置：经典技术分析四件套，参考 baostock 社区用户实战经验',
    factors: [
      { code: 'MOM20', name: '20日动量', direction: '正向', weight: 1.5, reason: '核心：短期均线趋势（>0）' },
      { code: 'MOM60', name: '60日动量', direction: '正向', weight: 1.5, reason: '核心：中长期均线趋势（>0），价格在均线上方' },
      { code: 'MACD', name: 'MACD', direction: '正向', weight: 1.5, reason: '核心：MACD金叉/动能增强，趋势确认信号' },
      { code: 'RSI14', name: 'RSI(14)', direction: '反向', weight: 1, reason: '回避超买区间（≤70）' },
      { code: 'BOLL_POS', name: '布林位置', direction: '正向', weight: 1, reason: '布林带中轨上方运行（>0.2）' },
      { code: 'VPCORR20', name: '量价相关性', direction: '正向', weight: 0.8, reason: '量价齐升（>0），趋势健康度验证' },
      { code: 'VOL20', name: '20日波动率', direction: '反向', weight: 0.7, reason: '低波动更稳健' },
    ],
  },
};

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

/* ── 预设说明浮动框组件 ──────────────────────────────────────────── */
function PresetDescPopover({ presetName }) {
  const info = PRESET_DESCRIPTIONS[presetName];
  if (!info) return null;

  const columns = [
    { title: '因子', dataIndex: 'code', width: 110, render: (v) => <Text code>{v}</Text> },
    { title: '名称', dataIndex: 'name', width: 100 },
    { title: '方向', dataIndex: 'direction', width: 70, align: 'center',
      render: (v) => <Tag color={v === '正向' ? 'blue' : 'red'} size="small">{v}</Tag> },
    { title: '权重', dataIndex: 'weight', width: 60, align: 'center' },
    { title: '选入理由', dataIndex: 'reason' },
  ];

  return (
    <Popover
      title={<Space><StarOutlined /> {presetName}</Space>}
      content={
        <div style={{ width: 520 }}>
          <Paragraph type="secondary" style={{ marginBottom: 8 }}>{info.desc}</Paragraph>
          <Table
            dataSource={info.factors}
            columns={columns}
            size="small"
            pagination={false}
            rowKey="code"
          />
        </div>
      }
      trigger="hover"
      placement="rightTop"
      getPopupContainer={() => document.body}
      styles={{ root: { zIndex: 99999 } }}
    >
      <InfoCircleOutlined style={{ color: '#1677ff', cursor: 'pointer', marginLeft: 4 }} />
    </Popover>
  );
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
  /* ── 可用因子 ──────────────────────────────────────────────────── */
  const [availableFactors, setAvailableFactors] = useState([]);
  const [loadingFactors, setLoadingFactors] = useState(false);

  /* ── 预设组合 ──────────────────────────────────────────────────── */
  const [presets, setPresets] = useState([]);
  const [selectedPresetId, setSelectedPresetId] = useState(null);
  const [saveModalVisible, setSaveModalVisible] = useState(false);
  const [saveForm] = Form.useForm();

  /* ── 因子配置列表 ─────────────────────────────────────────────── */
  const [factors, setFactors] = useState([]);

  /* ── 全局处理配置 ─────────────────────────────────────────────── */
  const [globalOutlier, setGlobalOutlier] = useState('MAD');
  const [globalNormalize, setGlobalNormalize] = useState('ZSCORE');
  const [orthogonalMethod, setOrthogonalMethod] = useState('NONE');

  /* ── 选股参数 ─────────────────────────────────────────────────── */
  const [screenDate, setScreenDate] = useState(null);
  const [topN, setTopN] = useState(10);
  const [direction, setDirection] = useState('LONG');
  const [excludeSt, setExcludeSt] = useState(true);
  const [valuationWeight, setValuationWeight] = useState(40);
  const [customSqlWhere, setCustomSqlWhere] = useState('');
  const [maAbove30, setMaAbove30] = useState(false);
  const [maAbove60, setMaAbove60] = useState(false);
  const [maAbove100, setMaAbove100] = useState(false);

  /* ── 结果 ─────────────────────────────────────────────────────── */
  const [result, setResult] = useState(null);
  const [running, setRunning] = useState(false);

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

    api.get('/screen/presets')
      .then(res => setPresets(res || []))
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

  /* ── 选择预设组合 ─────────────────────────────────────────────── */
  const handlePresetSelect = useCallback((presetId) => {
    setSelectedPresetId(presetId);
    if (!presetId) {
      setFactors([]);
      return;
    }
    const preset = presets.find(p => p.id === presetId);
    if (!preset || !preset.factorConfig) return;
    try {
      const config = JSON.parse(preset.factorConfig);
      setFactors(config.map(c => ({
        factorCode: c.factorCode,
        direction: c.direction ?? 1,
        weight: c.weight ?? 1,
        filterOp: c.filterOp || 'NONE',
        filterValue: c.filterValue ?? null,
        outlierMethod: null,
        normalizeMethod: null,
      })));
      message.success(`已加载「${preset.presetName}」组合`);
    } catch (e) {
      message.error('预设组合解析失败，请检查数据格式');
    }
  }, [presets]);

  /* ── 复制预设为自定义 ─────────────────────────────────────────── */
  const handleCopyPreset = useCallback(() => {
    if (!selectedPresetId) { message.warning('请先选择一个预设组合'); return; }
    const preset = presets.find(p => p.id === selectedPresetId);
    if (!preset) return;
    saveForm.setFieldsValue({
      presetName: `${preset.presetName}（副本）`,
      description: preset.description || '',
    });
    setSaveModalVisible(true);
  }, [selectedPresetId, presets, saveForm]);

  /* ── 保存自定义组合 ───────────────────────────────────────────── */
  const handleSavePreset = useCallback(() => {
    if (factors.length === 0) { message.warning('请至少添加一个因子'); return; }
    saveForm.validateFields().then(values => {
      const payload = {
        presetName: values.presetName,
        description: values.description || '',
        factorConfig: JSON.stringify(factors.map(f => ({
          factorCode: f.factorCode,
          direction: f.direction,
          weight: f.weight,
          filterOp: f.filterOp,
          filterValue: f.filterOp !== 'NONE' ? f.filterValue : null,
        }))),
      };
      api.post('/screen/presets', payload)
        .then(res => {
          message.success('组合已保存');
          setSaveModalVisible(false);
          saveForm.resetFields();
          // 刷新预设列表
          return api.get('/screen/presets');
        })
        .then(res => { if (res) setPresets(res || []); })
        .catch(() => {});
    });
  }, [factors, saveForm]);

  /* ── 删除自定义组合 ───────────────────────────────────────────── */
  const handleDeletePreset = useCallback((id) => {
    Modal.confirm({
      title: '确认删除',
      content: '确定要删除这个自定义组合吗？',
      okType: 'danger',
      onOk: () => {
        api.delete(`/screen/presets/${id}`)
          .then(() => {
            message.success('已删除');
            if (selectedPresetId === id) {
              setSelectedPresetId(null);
              setFactors([]);
            }
            return api.get('/screen/presets');
          })
          .then(res => { if (res) setPresets(res || []); })
          .catch(() => {});
      },
    });
  }, [selectedPresetId]);

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
      screenDate: screenDate ? screenDate.format('YYYY-MM-DD') : null,
      globalOutlierMethod: globalOutlier,
      globalNormalizeMethod: globalNormalize,
      orthogonalizationMethod: orthogonalMethod,
      topN,
      direction,
      excludeSt,
      valuationWeight: valuationWeight / 100,
      customSqlWhere: customSqlWhere || null,
      presetId: selectedPresetId || null,
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

    api.post('/screen/run', payload)
      .then(res => {
        setResult(res);
        setCollapsed(true);
        message.success(`选股完成，共选出 ${res?.stocks?.length ?? 0} 只股票`);
      })
      .catch(() => {})
      .finally(() => setRunning(false));
  }, [factors, screenDate, topN, direction, excludeSt, globalOutlier, globalNormalize, orthogonalMethod, totalWeight, valuationWeight, selectedPresetId, customSqlWhere, maAbove30, maAbove60, maAbove100]);

  /* ── 结果表格列 ───────────────────────────────────────────────── */
  const factorColumns = useMemo(() => (result?.factors || []).map(fw => ({
    title: (
      <Tooltip title={`权重: ${fw.weight}  方向: ${fw.direction >= 0 ? '正向' : '反向'}`}>
        <span>{fw.factorCode} <InfoCircleOutlined style={{ fontSize: 11, color: '#aaa' }} /></span>
      </Tooltip>
    ),
    key: fw.factorCode,
    width: 100,
    align: 'center',
    render: (_, row) => {
      const rank = row.factorRanks?.[fw.factorCode];
      const val = row.factorValues?.[fw.factorCode];
      if (rank == null) return <Text type="secondary">-</Text>;
      const pct = Math.min(100, Math.max(0, Math.round(rank * 100)));
      return (
        <Tooltip title={`原始值: ${val != null ? Number(val).toFixed(4) : '-'}`}>
          <Progress
            percent={pct}
            size="small"
            strokeColor={pct >= 70 ? '#52c41a' : pct >= 40 ? '#1677ff' : '#ff4d4f'}
            format={p => `${p}%`}
          />
        </Tooltip>
      );
    },
  })), [result]);

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
      title: '风险', key: 'risk', width: 65, align: 'center',
      render: (_, row) => {
        if (!row.riskLevel) return <Text type="secondary">-</Text>;
        return <Tag color={riskColor(row.riskLevel)} style={{ fontSize: 11 }}>{riskLabel(row.riskLevel)}</Tag>;
      },
    },
    ...factorColumns,
  ], [result, factorColumns]);

  /* ── 内置/自定义预设分组 ─────────────────────────────────────── */
  const builtinPresets = presets.filter(p => p.isBuiltin === 1);
  const customPresets = presets.filter(p => p.isBuiltin !== 1);

  /* ══════════════════════════════════════════════════════════════ */
  return (
    <div style={{ width: '100%' }}>
      {/* 页头 */}
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>因子策略</Title>
        <Space>
          <Badge
            color={totalWeight > 0 ? (Math.abs(totalWeight - 1) < 0.01 ? 'green' : 'orange') : 'red'}
            text={`权重合计: ${totalWeight.toFixed(2)}`}
          />
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
      </div>

      <Tabs
        defaultActiveKey="multifactor"
        size="small"
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
          {/* ── 预设组合选择 ─────────────────────────────────────── */}
          <Card
            title={<Space><StarOutlined /> 策略组合</Space>}
            style={{ marginBottom: 16 }}
            styles={{ body: { padding: '12px 16px 4px' } }}
          >
            <Row gutter={[12, 12]} align="middle">
              <Col flex="auto">
                <Select
                  value={selectedPresetId}
                  onChange={handlePresetSelect}
                  style={{ width: '100%' }}
                  size="small"
                  placeholder="选择一个预设组合快速开始"
                  allowClear
                  popupMatchSelectWidth={false}
                >
                  {builtinPresets.length > 0 && (
                    <Select.OptGroup label="内置组合">
                      {builtinPresets.map(p => (
                        <Option key={p.id} value={p.id}>
                          <Space size={4}>
                            <span>{p.presetName}</span>
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              ({p.description || '—'})
                            </Text>
                            <PresetDescPopover presetName={p.presetName} />
                          </Space>
                        </Option>
                      ))}
                    </Select.OptGroup>
                  )}
                  {customPresets.length > 0 && (
                    <Select.OptGroup label="我的组合">
                      {customPresets.map(p => (
                        <Option key={p.id} value={p.id}>
                          <Space size={4}>
                            <span>{p.presetName}</span>
                            <Button
                              type="text" size="small" danger
                              icon={<DeleteOutlined />}
                              onClick={(e) => { e.stopPropagation(); handleDeletePreset(p.id); }}
                              style={{ fontSize: 10 }}
                            />
                          </Space>
                        </Option>
                      ))}
                    </Select.OptGroup>
                  )}
                </Select>
              </Col>
              <Col flex="none">
                <div style={paramLabelStyle}> </div>
                <Space size={4} style={{ height: 24, alignItems: 'center' }}>
                  <Tooltip title="复制当前选中的预设组合，修改后另存为新组合">
                    <Button
                      size="small" icon={<CopyOutlined />}
                      onClick={handleCopyPreset}
                      disabled={!selectedPresetId}
                      style={{ height: 24 }}
                    />
                  </Tooltip>
                  <Tooltip title="将当前因子配置保存为自定义组合">
                    <Button
                      size="small" icon={<SaveOutlined />}
                      onClick={() => setSaveModalVisible(true)}
                      disabled={factors.length === 0}
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
              </Space>
            }
            style={{ marginBottom: 16 }}
            styles={{ body: { padding: '0 0 8px' } }}
          >
            {factors.length === 0 ? (
              <Empty description='选择预设组合或点击"添加因子"开始配置' style={{ padding: '32px 0' }} />
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
            <Row gutter={[12, 12]}>
              <Col span={12}>
                <div style={paramLabelStyle}>选股日期</div>
                <DatePicker
                  value={screenDate} onChange={setScreenDate}
                  placeholder="最新可用"
                  style={{ width: '100%' }} size="small"
                />
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>持仓数量</div>
                <InputNumber
                  value={topN} onChange={v => setTopN(v ?? 10)}
                  min={5} max={500} style={{ width: '100%' }} size="small"
                  addonAfter="只"
                />
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>选股方向</div>
                <Select value={direction} onChange={setDirection} style={{ width: '100%' }} size="small">
                  <Option value="LONG">做多（高分优先）</Option>
                  <Option value="SHORT">做空（低分优先）</Option>
                </Select>
              </Col>
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
                  极值处理
                  <Tooltip title="对每个因子的截面数据先做极值压缩，再做标准化">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={globalOutlier} onChange={setGlobalOutlier} style={{ width: '100%' }} size="small">
                  {OUTLIER_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
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
                <Spin size="large" tip="正在执行多因子选股..." />
              </div>
            </Card>
          )}

          {!running && !result && (
            <Card>
              <Empty
                description={
                  <span>选择策略组合或配置因子，点击 <Text strong>执行选股</Text> 获取结果</span>
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
                    <Statistic title="选股日期" value={result.screenDate} valueStyle={{ fontSize: 15 }} />
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

                {result.factorCoverage && (
                  <>
                    <Divider style={{ margin: '12px 0' }} />
                    <Row gutter={[8, 6]} wrap>
                      {Object.entries(result.factorCoverage).map(([code, cnt]) => (
                        <Col key={code}>
                          <Tooltip title={`${code}：${cnt} 只股票有数据`}>
                            <Tag color={cnt > 20 ? 'green' : cnt > 5 ? 'orange' : 'red'}>
                              {code}: {cnt} 只
                            </Tag>
                          </Tooltip>
                        </Col>
                      ))}
                    </Row>
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
                  </div>
                }
                extra={
                  <Space size="small">
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
                  pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'], showTotal: t => `共 ${t} 只` }}
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

      {/* ── 保存组合弹窗 ─────────────────────────────────────────── */}
      <Modal
        title="保存策略组合"
        open={saveModalVisible}
        onOk={handleSavePreset}
        onCancel={() => { setSaveModalVisible(false); saveForm.resetFields(); }}
        okText="保存"
        cancelText="取消"
      >
        <Form form={saveForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="presetName" label="组合名称" rules={[{ required: true, message: '请输入组合名称' }]}>
            <Input placeholder="如：我的成长组合" maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label="组合描述">
            <TextArea placeholder="简要描述这个组合的投资逻辑" rows={3} maxLength={500} />
          </Form.Item>
        </Form>
        <Alert
          type="info" showIcon
          message={`当前包含 ${factors.length} 个因子，权重合计 ${totalWeight.toFixed(2)}`}
          style={{ marginTop: 8 }}
        />
      </Modal>
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
        <Card size="small" style={{ marginBottom: 16 }}><Spin tip="加载因子定义..." /></Card>
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
