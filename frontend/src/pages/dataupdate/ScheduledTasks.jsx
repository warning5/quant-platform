import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Switch, Button, Tag, Typography, Space, Select, TimePicker, Input, InputNumber, Row, Col, Popconfirm, Badge, Spin, Modal, Tabs, Checkbox, Tooltip, DatePicker, Form } from 'antd';
import { message } from '../../utils/messageUtil';
import {
  PlayCircleOutlined, ClockCircleOutlined,
  CheckCircleOutlined, CloseCircleOutlined, SyncOutlined,
  ThunderboltOutlined, GlobalOutlined, HistoryOutlined,
  ReloadOutlined, StopOutlined, DeleteOutlined,
  EditOutlined, CheckOutlined, ClearOutlined, SettingOutlined, LoadingOutlined, LinkOutlined, ApartmentOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import api, { scheduleApi } from '../../api/index';

const { Text } = Typography;
const { RangePicker } = DatePicker;

// ========== 数据项定义 ==========
const TASK_ITEMS = [
  {
    key: 'DAILY', name: '日线数据', desc: '沪深/北交所股票日K线数据（Baostock + 腾讯证券）',
    icon: '📈', defaultEnabled: true, color: '#1677ff',
  },
  {
    key: 'INDEX', name: '指数数据', desc: '10大指数日线数据',
    icon: '📊', defaultEnabled: true, color: '#52c41a',
  },
  {
    key: 'DIVIDEND', name: '分红除权', desc: '股票分红、送股、拆细等除权信息',
    icon: '💰', defaultEnabled: false, color: '#fa8c16',
  },
  {
    key: 'FINANCIAL', name: '财务数据', desc: '三大报表（利润表/资产负债表/现金流量表）',
    icon: '📋', defaultEnabled: true, color: '#722ed1',
  },
  {
    key: 'BIDASK', name: '内外盘数据', desc: '内外盘比、成交量等盘口数据',
    icon: '⚖️', defaultEnabled: true, color: '#13c2c2',
  },
  {
    key: 'SENTIMENT_MF', name: '情绪数据-资金流向', desc: '东财资金流向数据（实时全市场 / 历史120天）',
    icon: '💹', defaultEnabled: true, color: '#eb2f96',
  },
  {
    key: 'SENTIMENT_OTHER', name: '情绪数据-其它', desc: '龙虎榜/融资融券/机构调研/大宗交易/市场活跃度/涨跌停池/公告/基金持仓/股东人数/新闻/国债收益率/申万行业指数/一致预期/业绩快报/QVIX恐慌指数',
    icon: '🔥', defaultEnabled: true, color: '#f5222d',
  },
  {
    key: 'RESEARCH', name: '研报数据', desc: '券商研报评级与盈利预测',
    icon: '📝', defaultEnabled: false, color: '#faad14',
  },
  {
    key: 'RECOMMENDATION_TRACK', name: '推荐追踪', desc: '计算昨日推荐股票的当日收益率，用于复盘策略效果。依赖当日收盘数据生成，建议 16:00 后自动执行',
    icon: '🎯', defaultEnabled: true, color: '#13c2c2',
  },
  {
    key: 'FACTOR_COMPUTE', name: '因子计算', desc: '根据策略配置的因子公式，每日日终批量计算因子值',
    icon: '🔢', defaultEnabled: true, color: '#1677ff',
  },
  {
    key: 'DAILY_RECOMMENDATION', name: '每日推荐', desc: '',
    icon: '⭐', defaultEnabled: true, color: '#faad14',
  },
  {
    key: 'DATA_FRESHNESS', name: '数据新鲜度检查', desc: '检查各数据源最新日期是否落后，超过阈值则告警。非数据更新任务，纯监控',
    icon: '🩺', defaultEnabled: true, color: '#1677ff',
  },
  {
    key: 'PRICE_ANOMALY', name: '价格异常检测', desc: '扫描近7天涨跌幅>50%的异常K线，发现脏数据。非数据更新任务，纯监控',
    icon: '🔍', defaultEnabled: true, color: '#722ed1',
  },
  {
    key: 'FACTOR_NULL_CHECK', name: '因子NULL检测', desc: '检查各因子最新日期的NULL比例，>50%触发告警。非数据更新任务，纯监控',
    icon: '⚠️', defaultEnabled: true, color: '#fa8c16',
  },
  {
    key: 'FINANCIAL_ANOMALY', name: '财务突变检测', desc: '基于单季值（累计值拆解）检测营收/净利润环比跳变>500%的异常记录。非数据更新任务，纯监控',
    icon: '📉', defaultEnabled: true, color: '#eb2f96',
  },
];

// 频率选项 → 转cron
const FREQ_OPTIONS = [
  { label: '跟随全局', value: '__GLOBAL__' },
  { label: '每小时', value: '0 * * * *' },
  { label: '每2小时', value: '0 */2 * * *' },
  { label: '每6小时', value: '0 */6 * * *' },
  { label: '每天 (可设时间)', value: '__DAILY__' },
  { label: '工作日 (可设时间)', value: '__WEEKDAY__' },
  { label: '每周一 (可设时间)', value: '__MON__' },
  { label: '每月1号 (可设时间)', value: '__MONTHLY__' },
  { label: '自定义 Cron', value: '__CUSTOM__' },
];

// 日期快捷选项
const DATE_MODE_OPTIONS = [
  { label: '当天', value: 'today' },
  { label: '最近1天', value: 'recent_1' },
  { label: '最近3天', value: 'recent_3' },
  { label: '自定义日期段', value: 'custom' },
];

/**
 * 将用户友好的频率选项转为实际cron表达式
 */
function resolveCron(freqValue, customTime) {
  const m = customTime ? customTime.minute() : 0;
  const h = customTime ? customTime.hour() : 16;

  switch (freqValue) {
    case '__GLOBAL__': return null;
    case '__DAILY__': return `${m} ${h} * * *`;
    case '__WEEKDAY__': return `${m} ${h} * * 1-5`;
    case '__MON__': return `${m} ${h} * * 1`;
    case '__MONTHLY__': return `${m} ${h} 1 * *`;
    case '__CUSTOM__': return null;
    default:
      return freqValue;
  }
}

/**
 * 从cron表达式反推UI显示值（近似）
 */
function inferFreqFromCron(cronExpr) {
  if (!cronExpr) return '__GLOBAL__';
  let parts = cronExpr.split(/\s+/);
  // 兼容6字段cron（秒 分 时 日 月 周），去掉秒字段
  if (parts.length === 6) parts = parts.slice(1);
  if (parts.length !== 5) return '__CUSTOM__';
  const [min, hour, dom, month, dow] = parts;

  if (dom === '*' && month === '*') {
    if (dow === '1-5') return '__WEEKDAY__';
    if (dow === '1') return '__MON__';
    if (dow === '*') {
      if (hour === '*' || hour.match(/^\*\/\d+$/)) {
        if (hour === '*') return '0 * * * *';
        return `${min} ${hour} * * *`;
      }
      return '__DAILY__';
    }
  }
  if (dom === '1' && month === '*' && dow === '*') return '__MONTHLY__';

  return '__CUSTOM__';
}

function extractTimeFromCron(cronExpr) {
  if (!cronExpr) return dayjs('16:00', 'HH:mm');
  const parts = cronExpr.split(/\s+/);
  // 兼容6字段cron，索引偏移
  const offset = parts.length === 6 ? 1 : 0;
  if (parts.length < 2 + offset) return dayjs('16:00', 'HH:mm');
  const h = parseInt(parts[1 + offset], 10);
  const m = parseInt(parts[0 + offset], 10);
  if (isNaN(h) || isNaN(m)) return dayjs('16:00', 'HH:mm');
  return dayjs().hour(h).minute(m).second(0);
}

// ========== extra_config 解析工具 ==========

/** 解析 DB 中的 extra_config JSON 字符串 */
function parseExtraConfig(raw) {
  if (!raw) return { incremental: true, dateMode: 'today', startDate: null, endDate: null, strategyIds: [], weightModes: ['ICW'], weightMode: 'ICW', topN: 15, enableConfidenceControl: true };
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
    // 向后兼容：weightMode 单值 -> weightModes 数组
    let weightModes = obj.weightModes;
    if (!weightModes && obj.weightMode) {
      weightModes = [obj.weightMode];
    }
    if (!Array.isArray(weightModes) || weightModes.length === 0) {
      weightModes = ['ICW'];
    }
    return {
      incremental: obj.incremental !== false,  // 未配置时默认增量模式
      dateMode: obj.dateMode || 'today',
      startDate: obj.startDate || null,
      endDate: obj.endDate || null,
      strategyIds: obj.strategyIds || (obj.strategyId ? [obj.strategyId] : []),
      weightModes,
      weightMode: weightModes[0] || 'ICW',
      topN: obj.topN || 15,
      enableConfidenceControl: obj.enableConfidenceControl !== false,
    };
  } catch {
    return { incremental: true, dateMode: 'today', startDate: null, endDate: null, strategyIds: [], weightModes: ['ICW'], weightMode: 'ICW', topN: 15, enableConfidenceControl: true };
  }
}

/** 将任务配置序列化为 extra_config JSON 字符串 */
function stringifyExtraConfig(config) {
  const result = {
    incremental: config.incremental,
    dateMode: config.dateMode,
    startDate: config.startDate,
    endDate: config.endDate,
  };
  // 推荐任务专属字段
  if (config.strategyIds && config.strategyIds.length > 0) {
    result.strategyIds = config.strategyIds;
    result.topN = config.topN || 15;
    result.enableConfidenceControl = config.enableConfidenceControl !== false;
  }
  // 权重模式（多选数组：weightModes 数组优先，weightMode 单值时兼容写入）
  const wms = config.weightModes && config.weightModes.length > 0
    ? config.weightModes
    : (config.weightMode ? [config.weightMode] : ['ICW']);
  result.weightModes = wms;
  // 同步写单值字段（向后兼容旧后端/旧解析逻辑）
  result.weightMode = wms[0];
  return JSON.stringify(result);
}

// ========== Cron 表达式解析 / 生成工具 ==========
// 6字段标准（Spring/Quartz）: 秒 分 时 日 月 周

const FIELD_DEFS = [
  { key: 'second', label: '秒', min: 0, max: 59, cols: 10 },
  { key: 'minute', label: '分', min: 0, max: 59, cols: 10 },
  { key: 'hour',   label: '时', min: 0, max: 23, cols: 6 },
  { key: 'dom',    label: '日', min: 1, max: 31, cols: 7 },
  { key: 'month',  label: '月', min: 1, max: 12, cols: 6 },
  { key: 'dow',    label: '周', min: 0, max: 6, cols: 7 },
];

/** 解析单个 cron field 为 Set<number> */
/** 星期文本 → 数字映射（Spring cron: 1=MON ... 7=SUN/0=SUN） */
const DOW_NAME_MAP = {
  'MON': 1, 'TUE': 2, 'WED': 3, 'THU': 4, 'FRI': 5, 'SAT': 6, 'SUN': 7, '0': 0,
};

function parseCronField(expr, min, max) {
  const result = new Set();
  if (!expr || expr === '*') {
    for (let i = min; i <= max; i++) result.add(i);
    return result;
  }
  expr.split(',').forEach(part => {
    part = part.trim();
    if (!part) return;
    const stepMatch = part.match(/^\*\/(\d+)$/);
    if (stepMatch) {
      const step = parseInt(stepMatch[1], 10);
      for (let i = min; i <= max; i += step) result.add(i);
      return;
    }
    const rangeMatch = part.match(/^(\d+)-(\d+)$/);
    if (rangeMatch) {
      const s = parseInt(rangeMatch[1], 10);
      const e = parseInt(rangeMatch[2], 10);
      for (let i = Math.max(s, min); i <= Math.min(e, max); i++) result.add(i);
      return;
    }
    const rangeStepMatch = part.match(/^(\d+)-(\d+)\/(\d+)$/);
    if (rangeStepMatch) {
      const s = parseInt(rangeStepMatch[1], 10);
      const e = parseInt(rangeStepMatch[2], 10);
      const step = parseInt(rangeStepMatch[3], 10);
      for (let i = Math.max(s, min); i <= Math.min(e, max); i += step) result.add(i);
      return;
    }
    // 文本星期范围：MON-FRI, MON-WED 等
    const textRangeMatch = part.match(/^([A-Z]{3})-([A-Z]{3})$/i);
    if (textRangeMatch) {
      const s = DOW_NAME_MAP[textRangeMatch[1].toUpperCase()];
      const e = DOW_NAME_MAP[textRangeMatch[2].toUpperCase()];
      if (s !== undefined && e !== undefined) {
        for (let i = s; i <= e; i++) result.add(i);
        // SUN=7，也支持 0 表示周日
        if (e === 7 && result.has(7)) result.add(0);
        return;
      }
    }
    // 单文本星期：MON, TUE 等
    const textName = part.toUpperCase();
    if (DOW_NAME_MAP[textName] !== undefined) {
      result.add(DOW_NAME_MAP[textName]);
      return;
    }
    const num = parseInt(part, 10);
    if (!isNaN(num) && num >= min && num <= max) result.add(num);
  });
  return result;
}

/** 将 Set<number> 反向生成 cron field 字符串 */
function generateCronField(selectedSet, min, max) {
  if (!selectedSet || selectedSet.size === 0) return '*';
  const sorted = Array.from(selectedSet).sort((a, b) => a - b);
  if (sorted.length === max - min + 1 && sorted[0] === min && sorted[sorted.length - 1] === max) {
    return '*';
  }
  const parts = [];
  let rangeStart = sorted[0];
  let prev = sorted[0];

  for (let i = 1; i <= sorted.length; i++) {
    const curr = i < sorted.length ? sorted[i] : null;
    if (curr !== null && curr === prev + 1) {
      prev = curr;
    } else {
      if (prev === rangeStart) {
        parts.push(String(prev));
      } else {
        parts.push(`${rangeStart}-${prev}`);
      }
      if (curr !== null) {
        rangeStart = curr;
        prev = curr;
      }
    }
  }

  if (parts.length >= 3) {
    const diffs = [];
    for (let i = 1; i < sorted.length; i++) diffs.push(sorted[i] - sorted[i - 1]);
    const allSameDiff = diffs.length > 0 && diffs.every(d => d === diffs[0]) && sorted[0] === min;
    if (allSameDiff && diffs[0] > 1) {
      return `*/${diffs[0]}`;
    }
  }

  return parts.join(',');
}

function parseFullCron(cronExpr) {
  if (!cronExpr) {
    return FIELD_DEFS.map(f => new Set(Array.from({ length: f.max - f.min + 1 }, (_, i) => f.min + i)));
  }
  const parts = cronExpr.trim().split(/\s+/);
  if (parts.length === 5 && !parts[0].includes('/') || (parseInt(parts[0], 10) > 59)) {
    return FIELD_DEFS.map((f, idx) => {
      if (idx === 0) return new Set([0]);
      return parseCronField(parts[idx - 1] || '*', f.min, f.max);
    });
  }
  return FIELD_DEFS.map((f, idx) => parseCronField(parts[idx] || '*', f.min, f.max));
}

function generateFullCron(fieldSets) {
  return FIELD_DEFS.map((f, idx) => generateCronField(fieldSets[idx], f.min, f.max)).join(' ');
}

function getNextRunTimes(cronExpr, count = 3) {
  const fieldSets = parseFullCron(cronExpr);
  // 将每个字段的 Set 转为排序数组，便于二分查找
  const arrays = fieldSets.map(s => Array.from(s).sort((a, b) => a - b));
  const results = [];
  const now = dayjs();
  let cursor = now.add(1, 'second').millisecond(0);
  const limit = now.add(4, 'year');

  function findNextOrEqual(arr, val) {
    // 二分查找 >= val 的最小值
    let lo = 0, hi = arr.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (arr[mid] < val) lo = mid + 1; else hi = mid;
    }
    return lo < arr.length ? arr[lo] : null;
  }

  while (results.length < count && cursor.isBefore(limit)) {
    // 秒
    let s = findNextOrEqual(arrays[0], cursor.second());
    if (s === null) {
      // 当前分钟没有更多匹配的秒，进位到下一分钟
      cursor = cursor.add(1, 'minute').second(arrays[0][0]).millisecond(0);
      continue;
    }
    if (s > cursor.second()) {
      cursor = cursor.second(s).millisecond(0);
      continue;
    }

    // 分
    let m = findNextOrEqual(arrays[1], cursor.minute());
    if (m === null) {
      // 进位到下一小时
      cursor = cursor.add(1, 'hour').minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }
    if (m > cursor.minute()) {
      cursor = cursor.minute(m).second(arrays[0][0]).millisecond(0);
      continue;
    }

    // 时
    let h = findNextOrEqual(arrays[2], cursor.hour());
    if (h === null) {
      cursor = cursor.add(1, 'day').hour(arrays[2][0]).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }
    if (h > cursor.hour()) {
      cursor = cursor.hour(h).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }

    // 月
    let mon = findNextOrEqual(arrays[4], cursor.month() + 1);
    if (mon === null) {
      cursor = cursor.add(1, 'year').month(arrays[4][0] - 1).date(1).hour(arrays[2][0]).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }
    if (mon > cursor.month() + 1) {
      cursor = cursor.month(mon - 1).date(1).hour(arrays[2][0]).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }

    // 日 —— 需过滤掉超出本月天数的值
    const maxDay = cursor.daysInMonth();
    let d = findNextOrEqual(
      arrays[3].filter(v => v <= maxDay),
      cursor.date()
    );
    if (d === null || d > maxDay) {
      // 进位到下月
      cursor = cursor.add(1, 'month').date(1).hour(arrays[2][0]).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }
    if (d > cursor.date()) {
      cursor = cursor.date(d).hour(arrays[2][0]).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }

    // 星期几（dow）—— 如果不匹配则跳到第二天
    const dow = cursor.day();
    if (!fieldSets[5].has(dow)) {
      cursor = cursor.add(1, 'day').hour(arrays[2][0]).minute(arrays[1][0]).second(arrays[0][0]).millisecond(0);
      continue;
    }

    // 所有字段都匹配！
    results.push(cursor);
    // 找到一个后进位到下一分钟继续找（不能只加1秒，否则秒字段可能不匹配）
    cursor = cursor.add(1, 'minute').second(arrays[0][0]).millisecond(0);
  }

  return results;
}

// ========== Cron 人类可读说明 ==========
/**
 * 将 cron 表达式转为人类可读的中文说明
 * 支持 5字段（分 时 日 月 周）和 6字段（秒 分 时 日 月 周）
 */
function explainCron(cronExpr) {
  if (!cronExpr) return '';

  let parts = cronExpr.trim().split(/\s+/);
  if (parts.length === 6) parts = parts.slice(1); // 去掉秒字段
  if (parts.length !== 5) return cronExpr;

  const [min, hour, dom, month, dow] = parts;
  const h = parseInt(hour, 10);
  const m = parseInt(min, 10);
  const timeStr = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;

  // 工作日
  if (dow === '1-5' && dom === '*' && month === '*') {
    return `每个工作日 ${timeStr} 执行`;
  }
  // 每周特定日
  const dowNum = parseInt(dow, 10);
  if (!isNaN(dowNum) && dom === '*' && month === '*') {
    const dayNames = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    if (dowNum >= 0 && dowNum <= 7) {
      return `每${dayNames[dowNum]} ${timeStr} 执行`;
    }
  }
  // 每天
  if (dow === '*' && dom === '*' && month === '*') {
    if (hour === '*') {
      if (min.match(/^\d+$/)) return `每小时 ${String(m).padStart(2, '0')} 分执行`;
      return `每小时执行`;
    }
    return `每天 ${timeStr} 执行`;
  }
  // 每月特定日
  const domNum = parseInt(dom, 10);
  if (!isNaN(domNum) && month === '*' && dow === '*') {
    return `每月 ${domNum} 号 ${timeStr} 执行`;
  }
  // 特定月份日期
  if (dom !== '*' && month !== '*' && dow === '*') {
    return `每年 ${month}月${dom}日 ${timeStr} 执行`;
  }

  return cronExpr; // 无法识别时返回原始表达式
}

// ========== 可视化编辑器弹窗（含 Cron + 任务配置 双 Tab） ==========

function CronVisualEditor({ open, initialValue, initialExtraConfig, taskKey, onOk, onCancel }) {
  // --- Cron 相关 state ---
  const [fields, setFields] = useState(() => parseFullCron(initialValue));
  // ★ 第一层 activeTab: 'config' = 任务配置, 'cron' = Cron配置(内含6子tab)
  const [activeMainTab, setActiveMainTab] = useState('config');
  // ★ Cron 内层子 tab（秒/分/时/日/月/周）
  const [activeCronFieldIdx, setActiveCronFieldIdx] = useState('0');
  const [triggerNow, setTriggerNow] = useState(false);
  // ★ 手动编辑 cron 文本（可编辑）
  const [manualEditCron, setManualEditCron] = useState(initialValue || '');

  // --- 任务配置 state ---
  const [incremental, setIncremental] = useState(true);
  const [dateMode, setDateMode] = useState('today');
  const [customDates, setCustomDates] = useState(null); // [dayjs, dayjs]
  // --- 推荐任务专属 state ---
  const [strategyIds, setStrategyIds] = useState([]);
  const [weightModes, setWeightModes] = useState(['ICW']);
  const [topN, setTopN] = useState(15);
  const [enableConfidenceControl, setEnableConfidenceControl] = useState(true);
  const [allStrategies, setAllStrategies] = useState([]);

  // 初始化
  useEffect(() => {
    if (open) {
      setFields(parseFullCron(initialValue));
      setActiveMainTab('config');
      setActiveCronFieldIdx('0');
      setTriggerNow(false);
      setManualEditCron(initialValue || '');
      // 解析已有配置
      const ec = parseExtraConfig(initialExtraConfig);
      setIncremental(ec.incremental);
      setDateMode(ec.dateMode || 'today');
      if (ec.startDate && ec.endDate) {
        setCustomDates([dayjs(ec.startDate), dayjs(ec.endDate)]);
      } else {
        setCustomDates(null);
      }
      setStrategyIds(ec.strategyIds || []);
      setWeightModes(ec.weightModes || (ec.weightMode ? [ec.weightMode] : ['ICW']));
      setTopN(ec.topN || 15);
      setEnableConfidenceControl(ec.enableConfidenceControl !== false);
      // 推荐任务时加载策略列表
      if (taskKey === 'DAILY_RECOMMENDATION') {
        api.get('/strategies?size=100').then(res => {
          // axios interceptor unwraps to res.data.data (IPage: {records, total, ...})
          const records = res?.records;
          const data = Array.isArray(records) ? records : (Array.isArray(res) ? res : []);
          setAllStrategies(data);
          setAllStrategies(data);
        }).catch(() => {});
      }
    }
  }, [open, initialValue, initialExtraConfig, taskKey]);

  /** 切换单个 checkbox */
  const toggleItem = useCallback((fieldIdx, val) => {
    setFields(prev => {
      const newFields = prev.map((f, i) => {
        if (i !== fieldIdx) return new Set(f);
        const nf = new Set(f);
        if (nf.has(val)) nf.delete(val); else nf.add(val);
        return nf;
      });
      return newFields;
    });
  }, []);

  /** 快捷操作 */
  const quickAction = useCallback((fieldIdx, action) => {
    const def = FIELD_DEFS[fieldIdx];
    setFields(prev => {
      const newFields = [...prev];
      switch (action) {
        case 'all': {
          const s = new Set();
          for (let i = def.min; i <= def.max; i++) s.add(i);
          newFields[fieldIdx] = s;
          break;
        }
        case 'none':
          newFields[fieldIdx] = new Set();
          break;
        case 'even': {
          const s = new Set();
          for (let i = def.min; i <= def.max; i += 2) s.add(i);
          newFields[fieldIdx] = s;
          break;
        }
        case 'odd': {
          const start = def.min % 2 === 0 ? def.min + 1 : def.min;
          const s = new Set();
          for (let i = start; i <= def.max; i += 2) s.add(i);
          newFields[fieldIdx] = s;
          break;
        }
        default: {
          const n = parseInt(action, 10);
          if (!isNaN(n) && n > 0) {
            const s = new Set();
            for (let i = def.min; i <= def.max; i += n) s.add(i);
            newFields[fieldIdx] = s;
          }
          break;
        }
      }
      return newFields;
    });
  }, []);

  const previewCron = useMemo(() => generateFullCron(fields), [fields]);
  // 实际用于保存的 cron：优先使用手动编辑的值，否则用 checkbox 生成的
  const effectiveCron = manualEditCron || previewCron;
  // 最近3次执行时间：异步计算，避免阻塞渲染
  const [nextRuns, setNextRuns] = useState([]);
  useEffect(() => {
    // 使用 requestIdleCallback / setTimeout 让出主线程
    const timer = setTimeout(() => {
      try {
        setNextRuns(getNextRunTimes(effectiveCron, 3));
      } catch { setNextRuns([]); }
    }, 0);
    return () => clearTimeout(timer);
  }, [effectiveCron]);

  /** 当用户在 Input 中手动编辑 cron 文本时，回写到 fields（反解析） */
  const handleManualCronChange = useCallback((val) => {
    setManualEditCron(val);
    if (!val || !val.trim()) return;
    try {
      const parsed = parseFullCron(val.trim());
      if (parsed && parsed.every(s => s instanceof Set && s.size > 0)) {
        setFields(parsed);
      }
    } catch { /* 忽略非法输入 */ }
  }, []);

  /** 当 checkbox 变化时，同步更新 manualEditCron */
  useEffect(() => {
    if (open) {
      const generated = generateFullCron(fields);
      if (generated !== manualEditCron) {
        setManualEditCron(generated);
      }
    }
  }, [fields]); // eslint-disable-line react-hooks/exhaustive-deps

  // 构建 Cron 字段子 Tabs（仅用于「Cron 配置」内部）
  const cronFieldTabs = useMemo(() => FIELD_DEFS.map((def, idx) => ({
    key: String(idx),
    label: (
      <span>{def.label}
        <Tag style={{ marginLeft: 4 }} color={
          (fields[idx]?.size || 0) === (def.max - def.min + 1) ? 'green' :
          (fields[idx]?.size || 0) === 0 ? 'red' : 'blue'
        }>
          {fields[idx]?.size || 0}/{def.max - def.min + 1}
        </Tag>
      </span>
    ),
    children: (
      <div style={{ padding: '8px 0' }}>
        <Space size={4} style={{ marginBottom: 12, flexWrap: 'wrap' }}>
          <Button size="small" icon={<CheckOutlined />} onClick={() => quickAction(idx, 'all')}>全选</Button>
          <Button size="small" icon={<ClearOutlined />} onClick={() => quickAction(idx, 'none')}>清空</Button>
          {[2, 3, 5, 10, 15, 30].filter(n => n <= (def.max - def.min + 1)).map(n => (
            <Button key={n} size="small" onClick={() => quickAction(idx, String(n))}>每{n}{def.label}</Button>
          ))}
          {(idx === 0 || idx === 1) && (
            <>
              <Button size="small" onClick={() => quickAction(idx, 'even')}>偶数</Button>
              <Button size="small" onClick={() => quickAction(idx, 'odd')}>奇数</Button>
            </>
          )}
          {idx === 3 && (
            <Button size="small" onClick={() => quickAction(idx, '1-5')}>工作日(1-5)</Button>
          )}
          {idx === 4 && (
            <Button size="small" onClick={() => quickAction(idx, '1-5')}>周一至周五</Button>
          )}
        </Space>

        <div style={{
          display: 'grid',
          gridTemplateColumns: `repeat(${def.cols}, 1fr)`,
          gap: 4,
          maxHeight: 220,
          overflowY: 'auto',
          padding: 4,
          background: '#fafafa',
          borderRadius: 6,
        }}>
          {Array.from({ length: def.max - def.min + 1 }, (_, i) => def.min + i).map(v => (
            <Tooltip key={v} title={`${v}`}>
              <Checkbox
                checked={fields[idx]?.has(v) || false}
                onChange={() => toggleItem(idx, v)}
                style={{ marginRight: 0 }}
              >
                <span style={{ fontSize: 12,
                  fontWeight: fields[idx]?.has(v) ? 600 : 400,
                  color: fields[idx]?.has(v) ? '#1677ff' : '#999'
                }}>
                  {String(v).padStart(2, '0')}
                </span>
              </Checkbox>
            </Tooltip>
          ))}
        </div>
      </div>
    ),
  })), [fields]); // eslint-disable-line react-hooks/exhaustive-deps

  // ★ 第一层：两个大 Tab（任务配置 | Cron 配置）
  // ========== Tab 内容渲染函数（内联，不用 useMemo 避免数组重建导致卡切换） ==========
  const renderConfigTab = () => (
    <div style={{ padding: '16px 0' }}>
      {/* 增量/全量 — 推荐任务不需要 */}
      {taskKey !== 'DAILY_RECOMMENDATION' && (
      <div style={{ marginBottom: 20 }}>
        <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>更新模式</Text>
        <Space align="center">
          <Switch
            checked={incremental}
            onChange={setIncremental}
            checkedChildren="增量"
            unCheckedChildren="全量"
          />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {incremental
              ? '增量模式：仅更新新增/变更的数据（不使用 --force）'
              : '全量模式：强制重新写入覆盖已有数据（使用 --force）'}
          </Text>
        </Space>
      </div>
      )}

      {/* 任务参数：两列布局 */}
      <Row gutter={24}>
        {/* 左列：日期范围 + 每策略推荐数 */}
        <Col span={12}>
          <div>
            <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>日期范围</Text>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Select
                value={dateMode}
                onChange={v => setDateMode(v)}
                options={DATE_MODE_OPTIONS}
                style={{ width: '100%' }}
              />
              {dateMode === 'custom' ? (
                <RangePicker
                  value={customDates}
                  onChange={(dates) => setCustomDates(dates)}
                  format="YYYY-MM-DD"
                  placeholder={['开始日期', '结束日期']}
                  style={{ width: '100%' }}
                />
              ) : (
                <div style={{
                  padding: '8px 12px',
                  background: '#fafafa',
                  borderRadius: 6,
                  border: '1px solid #f0f0f0',
                }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {dateMode === 'today' && (
                      <>自动使用 <Tag color="blue">当天</Tag>（启动时传当天日期）</>
                    )}
                    {dateMode === 'recent_1' && (
                      <>自动使用 <Tag color="blue">昨天</Tag>（仅最近1个交易日）</>
                    )}
                    {dateMode === 'recent_3' && (
                      <>自动使用 <Tag color="blue">3天前 ~ 昨天</Tag>（最近3个交易日）</>
                    )}
                  </Text>
                </div>
              )}
            </Space>

            {/* 推荐任务专属：每策略推荐数 + 置信度控制 — 放左列底部 */}
            {taskKey === 'DAILY_RECOMMENDATION' && (
            <div style={{ marginTop: 20 }}>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>每策略推荐数</Text>
              <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
                <InputNumber min={5} max={50} value={topN} onChange={setTopN} style={{ width: 100 }} />
                <Switch checked={enableConfidenceControl} onChange={setEnableConfidenceControl} size="small"
                  checkedChildren="置信度控制" unCheckedChildren="固定TopN" />
              </div>
            </div>
            )}
          </div>
        </Col>

        {/* 右列：策略选择 + 权重模式 */}
        {taskKey === 'DAILY_RECOMMENDATION' ? (
        <Col span={12}>
          <div>
            {/* 策略选择 — 全选放在标题右侧 */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
              <Text strong style={{ fontSize: 13 }}>策略选择</Text>
              {strategyIds.length === allStrategies.length && allStrategies.length > 0 ? (
                <a onClick={() => setStrategyIds([])} style={{ cursor: 'pointer', fontSize: 12 }}>取消全选</a>
              ) : (
                <a onClick={() => setStrategyIds(allStrategies.map(s => s.id))} style={{ cursor: 'pointer', fontSize: 12 }}>全选</a>
              )}
            </div>
            <Select
              mode="multiple"
              value={strategyIds}
              onChange={setStrategyIds}
              placeholder="选择要执行的策略（可多选）"
              style={{ width: '100%', marginBottom: 8 }}
              options={allStrategies.map(s => ({
                value: s.id,
                label: `${s.strategyName}（#${s.id}）`,
              }))}
              maxTagCount={3}
              maxTagTextLength={10}
            />
            {strategyIds.length > 0 && (
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 16 }}>
                已选 {strategyIds.length}/{allStrategies.length} 个策略：{strategyIds.map(id => {
                  const s = allStrategies.find(x => x.id === id);
                  return s?.strategyName || `#${id}`;
                }).join('、')}
              </Text>
            )}

            {/* 权重模式选择（多选） */}
            <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>权重模式（可多选，每种模式生成独立快照）</Text>
            <Select
              mode="multiple"
              value={weightModes}
              onChange={setWeightModes}
              placeholder="选择因子权重模式（可多选）"
              style={{ width: '100%', marginBottom: 16 }}
              maxTagCount="responsive"
              options={[
                { value: 'ICW', label: 'IC动态加权（默认，根据因子预测能力自动调整）' },
                { value: 'STATIC', label: '固定等权（策略配置中的固定权重）' },
                { value: 'EQUAL', label: '简单等权（所有因子权重相同）' },
              ]}
            />
          </div>
        </Col>
        ) : /* 非推荐任务：右列为空 */ <Col span={12} />}
      </Row>
    </div>
  );


  // ── 任务关系 Tab ──────────────────────────────────────────
  const [deps, setDeps] = useState([]);
  const [taskKeyOptions, setTaskKeyOptions] = useState([]);
  const [depLoading, setDepLoading] = useState(false);
  const [depModalOpen, setDepModalOpen] = useState(false);
  const [depSubmitLoading, setDepSubmitLoading] = useState(false);
  const [depForm] = Form.useForm();

  const loadDeps = useCallback(async () => {
    setDepLoading(true);
    try {
      const data = await scheduleApi.getDependencies();
      setDeps(data || []);
    } catch { /* ignore */ } finally { setDepLoading(false); }
  }, []);

  const loadTaskKeys = useCallback(async () => {
    try {
      const data = await scheduleApi.getTaskKeys();
      setTaskKeyOptions(data || []);
    } catch { /* ignore */ }
  }, []);

  // 当前编辑弹窗选中的任务 key
  const currentTaskKey = taskKey;

  // 过滤与当前任务相关的依赖
  const relatedDeps = useMemo(() => {
    if (!currentTaskKey) return deps;
    return deps.filter(
      d => d.upstream_key === currentTaskKey || d.downstream_key === currentTaskKey
    );
  }, [deps, currentTaskKey]);

  // 新增依赖时：上游任务候选（排除自己 + 已存在的上游）
  const upstreamOptions = useMemo(() => {
    const existingUpstreams = new Set(
      deps
        .filter(d => d.downstream_key === currentTaskKey)
        .map(d => d.upstream_key)
    );
    return taskKeyOptions.filter(
      opt => opt.value !== currentTaskKey && !existingUpstreams.has(opt.value)
    );
  }, [taskKeyOptions, deps, currentTaskKey]);

  const handleOpenAddDep = () => {
    depForm.setFieldsValue({
      upstreamKey: undefined,
      downstreamKey: currentTaskKey,
      delaySeconds: 300,
    });
    setDepModalOpen(true);
  };

  useEffect(() => {
    if (activeMainTab === 'dependency') {
      loadDeps();
      loadTaskKeys();
    }
  }, [activeMainTab, loadDeps, loadTaskKeys]);

  const handleAddDep = async () => {
    if (depSubmitLoading) return;
    setDepSubmitLoading(true);
    try {
      await depForm.validateFields();
      const vals = depForm.getFieldsValue();
      await scheduleApi.addDependency(vals);
      message.success('添加成功');
      setDepModalOpen(false);
      depForm.resetFields();
      loadDeps();
    } catch (err) {
      message.error(err?.message || '添加失败');
    } finally {
      setDepSubmitLoading(false);
    }
  };

  const handleDeleteDep = async (id) => {
    try {
      await scheduleApi.deleteDependency(id);
      message.success('删除成功');
      loadDeps();
    } catch (err) {
      message.error(err?.message || '删除失败');
    }
  };

  const depColumns = [
    {
      title: '上游任务',
      dataIndex: 'upstream_key',
      width: 180,
      render: (v, r) => (
        <Space direction="vertical" size={0}>
          <Tag color="blue">{v}</Tag>
          <Text type="secondary" style={{ fontSize: 11 }}>{r.upstream_name || ''}</Text>
        </Space>
      ),
    },
    {
      title: '下游任务',
      dataIndex: 'downstream_key',
      width: 180,
      render: (v, r) => (
        <Space direction="vertical" size={0}>
          <Tag color="green">{v}</Tag>
          <Text type="secondary" style={{ fontSize: 11 }}>{r.downstream_name || ''}</Text>
        </Space>
      ),
    },
    {
      title: '触发延迟',
      dataIndex: 'delay_seconds',
      width: 100,
      render: (v) => v ? `约 ${Math.round(v / 60)} 分钟` : '5 分钟',
    },
    {
      title: '操作',
      width: 80,
      render: (_, r) => (
        <Popconfirm
          title="确认删除该依赖关系？"
          onConfirm={() => handleDeleteDep(r.id)}
          okText="确认"
          cancelText="取消"
        >
          <Button type="link" danger size="small" icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  const renderDependencyTab = () => (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space style={{ marginBottom: 8 }}>
          <Text strong>任务依赖关系</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            上游任务完成后，将自动触发下游任务。可添加/删除依赖关系，系统自动校验循环依赖。
          </Text>
        </Space>
        <div style={{ marginBottom: 8 }}>
          <Text type="secondary" style={{ fontSize: 11 }}>
            注意：删除依赖后，该触发链路将不再自动执行。
          </Text>
        </div>
      </Card>

      <Card size="small" style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <Text strong>依赖列表（{relatedDeps.length} 条）</Text>
          <Button
            type="primary"
            size="small"
            icon={<LinkOutlined />}
            onClick={handleOpenAddDep}
          >
            新增依赖
          </Button>
        </div>

        <Table
          columns={depColumns}
          dataSource={relatedDeps}
          rowKey="id"
          size="small"
          loading={depLoading}
          pagination={false}
          scroll={{ x: 700 }}
          locale={{ emptyText: '暂无依赖关系，点击"新增依赖"添加' }}
        />
      </Card>

      <Modal
        title={<><LinkOutlined /> 新增任务依赖</>}
        open={depModalOpen}
        onOk={handleAddDep}
        onCancel={() => { setDepModalOpen(false); depForm.resetFields(); }}
        okText="确认添加"
        cancelText="取消"
        confirmLoading={depSubmitLoading}
        destroyOnClose
      >
        <Form form={depForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="upstreamKey"
            label="上游任务（完成后触发）"
            rules={[{ required: true, message: '请选择上游任务' }]}
          >
            <Select
              showSearch
              allowClear
              placeholder="请选择上游任务"
              options={upstreamOptions}
              filterOption={(input, opt) =>
                opt.label.toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
          <Form.Item
            name="downstreamKey"
            label="下游任务（被触发）"
            rules={[{ required: true, message: '请选择下游任务' }]}
          >
            <Select
              showSearch
              disabled
              placeholder="下游任务"
              options={taskKeyOptions}
              filterOption={(input, opt) =>
                opt.label.toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
          <Form.Item
            name="delaySeconds"
            label="触发延迟（秒）"
            initialValue={300}
            rules={[{ required: true, message: '请输入延迟秒数' }]}
          >
            <InputNumber min={0} max={3600} style={{ width: 200 }} addonAfter="秒" />
          </Form.Item>
          <Text type="secondary" style={{ fontSize: 11 }}>
            延迟时间：上游任务完成后，等待指定秒数再触发下游任务（避免上游数据尚未完全写入）。
          </Text>
        </Form>
      </Modal>
    </div>
  );


  const renderCronTab = () => (
    <div>
      {/* 内层：6个 cron 字段子 Tab */}
      <Tabs
        activeKey={activeCronFieldIdx}
        onChange={setActiveCronFieldIdx}
        items={cronFieldTabs}
        size="small"
        type="card"
      />

      {/* ★ Cron 预览区（可编辑 + 最近3次执行） */}
      <div style={{
        marginTop: 12,
        padding: '10px 16px', background: '#f0f5ff',
        borderRadius: 6, border: '1px solid #d6e4ff'
      }}>
        <Space align="center" size={8}>
          <Text type="secondary" style={{ flexShrink: 0 }}>生成的 Cron:</Text>
          <Input
            value={manualEditCron}
            onChange={(e) => handleManualCronChange(e.target.value)}
            placeholder="秒 分 时 日 月 周"
            style={{ fontFamily: 'monospace', fontSize: 14, letterSpacing: 1, width: 280 }}
          />
          {manualEditCron !== previewCron && manualEditCron && (
            <Button size="small" type="link"
              onClick={() => setManualEditCron(previewCron)}
              style={{ padding: 0 }}
            >
              还原为选中值
            </Button>
          )}
        </Space>

        {nextRuns.length > 0 ? (
          <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px dashed #d6e4ff' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>最近 3 次执行：</Text>
            {nextRuns.map((t, i) => (
              <Tag
                key={i}
                color={i === 0 ? 'blue' : 'default'}
                style={{ marginLeft: 8, fontSize: 12 }}
              >
                {t.format('YYYY-MM-DD HH:mm:ss')}
              </Tag>
            ))}
          </div>
        ) : open && (
          <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px dashed #d6e4ff' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              <LoadingOutlined style={{ marginRight: 4 }} /> 计算执行时间...
            </Text>
          </div>
        )}
      </div>
    </div>
  );

  /** 构建最终的 extra_config */
  const buildExtraConfig = useCallback(() => {
    let sd = null, ed = null;
    if (dateMode === 'custom' && customDates && customDates[0] && customDates[1]) {
      sd = customDates[0].format('YYYY-MM-DD');
      ed = customDates[1].format('YYYY-MM-DD');
    }
    return stringifyExtraConfig({
      incremental,
      dateMode,
      startDate: sd,
      endDate: ed,
      strategyIds,
      weightModes,
      topN,
      enableConfidenceControl,
    });
  }, [incremental, dateMode, customDates, strategyIds, weightModes, topN, enableConfidenceControl]);

  return (
    <Modal
      title={<Space><EditOutlined /> 定时任务编辑器</Space>}
      open={open}
      onOk={() => onOk(effectiveCron, buildExtraConfig(), triggerNow)}
      onCancel={onCancel}
      width={720}
      okText="确认应用"
      cancelText="取消"
      destroyOnHidden
    >
      <Tabs
        activeKey={activeMainTab}
        onChange={setActiveMainTab}
        size="middle"
        type="card"
        style={{ marginBottom: 16 }}
      >
        <Tabs.TabPane tab={<span><SettingOutlined /> 参数配置</span>} key="config">
          {renderConfigTab()}
        </Tabs.TabPane>
        <Tabs.TabPane tab={<span><LinkOutlined /> 任务关系</span>} key="dependency">
          {renderDependencyTab()}
        </Tabs.TabPane>
        <Tabs.TabPane tab={<span><ClockCircleOutlined /> Cron 配置</span>} key="cron">
          {renderCronTab()}
        </Tabs.TabPane>
      </Tabs>

      {/* ★ 公共区域（不随 Tab 切换变化） */}
      <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px dashed #e8e8e8' }}>
        {/* 日期提示 */}
        <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 10 }}>
          提示：定时任务触发时，系统会根据此设置动态计算实际日期参数传入脚本。
          例如选择"最近1天"，则每次触发时自动传递昨天的日期。
        </Text>

        {/* 立即执行 */}
        <div style={{ marginBottom: 6 }}>
          <Checkbox
            checked={triggerNow}
            onChange={e => setTriggerNow(e.target.checked)}
          >
            保存后立即执行一次
          </Checkbox>
        </div>

        <Text type="secondary" style={{ fontSize: 11 }}>
          提示：「任务配置」Tab 设置更新模式和日期范围，「Cron 配置」Tab 设置执行周期。
          格式：秒 分 时 日 月 周 · 增量模式不使用 --force · 日期支持动态相对时间
        </Text>
      </div>
    </Modal>
  );
}

// ========== 原 CronPicker (保留用于行内快速切换) ==========
function CronPicker({ value, onChange }) {
  const [freq, setFreq] = useState(() => inferFreqFromCron(value));
  const [time, setTime] = useState(() => extractTimeFromCron(value));
  const [customCron, setCustomCron] = useState(value);

  useEffect(() => {
    setFreq(inferFreqFromCron(value));
    setTime(extractTimeFromCron(value));
    setCustomCron(value);
  }, [value]);

  const handleChange = useCallback((newFreq, newTime, newCustom) => {
    let resolved;
    if (newFreq === '__CUSTOM__') {
      resolved = newCustom || customCron;
    } else if (newFreq === '__GLOBAL__') {
      resolved = null;
    } else {
      resolved = resolveCron(newFreq, newTime !== undefined ? newTime : time);
    }
    onChange(resolved);
    if (newFreq !== undefined) setFreq(newFreq);
    if (newTime !== undefined) setTime(newTime);
    if (newCustom !== undefined) setCustomCron(newCustom);
  }, [onChange, time]); // eslint-disable-line react-hooks/exhaustive-deps

  const needsTime = freq === '__DAILY__' || freq === '__WEEKDAY__' ||
                     freq === '__MON__' || freq === '__MONTHLY__';

  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      <Space size={8}>
        <Select
          value={freq}
          onChange={(v) => handleChange(v)}
          style={{ width: 180 }}
          size="small"
          options={FREQ_OPTIONS}
        />
        {needsTime && (
          <TimePicker
            value={time}
            format="HH:mm"
            minuteStep={30}
            size="small"
            onChange={(t) => handleChange(freq, t)}
          />
        )}
      </Space>
      {freq === '__CUSTOM__' && (
        <Input
          placeholder="分 时 日 月 周 (如: 0 18 * * 1-5)"
          size="small"
          value={customCron || ''}
          onChange={(e) => {
            setCustomCron(e.target.value);
            handleChange('__CUSTOM__', undefined, e.target.value);
          }}
        />
      )}
      {value && freq !== '__GLOBAL__' && freq !== '__CUSTOM__' && (
        <Text type="secondary" style={{ fontSize: 11 }}>
          Cron: {value}
        </Text>
      )}
    </Space>
  );
}

// ========== 主组件 ==========
export default function ScheduledTasks() {
  const [loading, setLoading] = useState(false);
  const [globalConfig, setGlobalConfig] = useState(null);
  const [taskConfigs, setTaskConfigs] = useState([]);
  // ★ 改为 Set 支持多任务并发执行
  const [triggeringKeys, setTriggeringKeys] = useState(new Set());

  // Cron 可视化编辑器状态
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorTarget, setEditorTarget] = useState(null); // { type: 'global'|'sub', taskKey, initialValue, initialExtraConfig }

  // 任务关系图状态
  const [graphOpen, setGraphOpen] = useState(false);
  const [graphDeps, setGraphDeps] = useState([]);
  const [graphLoading, setGraphLoading] = useState(false);

  const loadGraphDeps = useCallback(async () => {
    setGraphLoading(true);
    try {
      const data = await scheduleApi.getDependencies();
      setGraphDeps(data || []);
    } catch { /* ignore */ } finally { setGraphLoading(false); }
  }, []);

  const handleOpenGraph = () => {
    loadGraphDeps();
    setGraphOpen(true);
  };

  // 加载配置
  const fetchConfig = useCallback(async () => {
    setLoading(true);
    try {
      const res = await scheduleApi.getAll();
      if (Array.isArray(res) && res.length > 0) {
        const gc = res.find(r => r.task_key === 'GLOBAL');
        setGlobalConfig(gc || null);
        const tasks = res.filter(r => r.task_key !== 'GLOBAL');
        setTaskConfigs(tasks);
        // ★ 从 DB 的 last_run_status 恢复正在执行中的任务（刷新页面后不再丢失）
        const runningKeys = tasks
          .filter(t => t.last_run_status === 'RUNNING')
          .map(t => t.task_key);
        if (runningKeys.length > 0) {
          setTriggeringKeys(new Set(runningKeys));
        }
      }
    } catch (e) {
      console.error('加载定时配置失败', e);
      message.error('加载定时任务配置失败');
    }
    setLoading(false);
  }, []);

  useEffect(() => { fetchConfig(); }, [fetchConfig]);

  // ★ 轮询：当有任务在执行中时，每5秒刷新一次状态（检测任务完成/失败）
  useEffect(() => {
    if (triggeringKeys.size === 0) return;
    const timer = setInterval(() => {
      // 静默刷新，不重置 loading 态
      scheduleApi.getAll().then(res => {
        if (Array.isArray(res) && res.length > 0) {
          const tasks = res.filter(r => r.task_key !== 'GLOBAL');
          setTaskConfigs(tasks);
          // 检查已完成的任务（DB 状态不再是 RUNNING）
          const stillRunning = new Set(
            tasks.filter(t => t.last_run_status === 'RUNNING').map(t => t.task_key)
          );
          const completed = [...triggeringKeys].filter(k => !stillRunning.has(k));
          if (completed.length > 0) {
            // 有任务完成了，更新 triggeringKeys 并提示
            setTriggeringKeys(stillRunning);
            completed.forEach(k => {
              const t = tasks.find(x => x.task_key === k);
              if (t?.last_run_status === 'SUCCESS') {
                message.success(`${t.task_name} 执行完成`);
              } else if (t?.last_run_status === 'FAILED') {
                message.error(`${t.task_name} 执行失败`);
              }
            });
          }
        }
      }).catch(() => {});
    }, 5000);
    return () => clearInterval(timer);
  }, [triggeringKeys.size]); // 只依赖 size 变化触发/停止轮询

  const saveSingle = async (taskKey, field, val) => {
    try {
      await scheduleApi.update(taskKey, { [field]: val });
    } catch (e) {
      message.error(`保存 ${field} 失败`);
      fetchConfig();
    }
  };

  const saveGlobal = async (field, val) => {
    try {
      await scheduleApi.update('GLOBAL', { [field]: val });
    } catch (e) {
      message.error('保存全局配置失败');
      fetchConfig();
    }
  };

  const updateLocalConfig = (taskKey, field, val) => {
    setTaskConfigs(prev => prev.map(t =>
      t.task_key === taskKey ? { ...t, [field]: val } : t
    ));
    saveSingle(taskKey, field, val);
  };

  // 手动触发 — 支持并发
  const handleTrigger = async (taskKey) => {
    setTriggeringKeys(prev => new Set(prev).add(taskKey));
    try {
      const res = await scheduleApi.trigger(taskKey);
      if (res && (res.taskKey || res.taskId || res.status === 'RUNNING')) {
        message.success(res?.message || `${taskKey} 任务已触发`);
        fetchConfig();
      } else {
        message.error(res?.message || '触发失败');
        setTriggeringKeys(prev => {
          const next = new Set(prev);
          next.delete(taskKey);
          return next;
        });
      }
    } catch (e) {
      message.error('触发失败: ' + (e.message || e));
      setTriggeringKeys(prev => {
        const next = new Set(prev);
        next.delete(taskKey);
        return next;
      });
    }
  };

  // 取消单个任务
  const handleCancel = async (taskKey) => {
    try {
      const res = await scheduleApi.cancel(taskKey);
      if (res === true) {
        message.success(`${taskKey} 已取消`);
        setTriggeringKeys(prev => {
          const next = new Set(prev);
          next.delete(taskKey);
          return next;
        });
        fetchConfig();
      } else {
        message.error('取消失败：任务可能已完成或未在运行中');
      }
    } catch (e) {
      message.error('取消失败: ' + (e.message || e));
    }
  };

  // 打开编辑器
  const openEditor = (type, taskKey, initialValue, extraConfig) => {
    setEditorTarget({ type, taskKey, initialValue, initialExtraConfig: extraConfig });
    setEditorOpen(true);
  };

  // 编辑器确认 → 保存 cron + extra_config + 可选触发
  const handleEditorOk = async (cronExpr, extraConfigStr, triggerNow = false) => {
    if (!editorTarget) return;
    try {
      if (editorTarget.type === 'global') {
        await scheduleApi.update('GLOBAL', { cron_expression: cronExpr });
        setGlobalConfig(prev => prev ? { ...prev, cron_expression: cronExpr } : null);
        message.success('全局 Cron 已更新');
      } else {
        const updateData = {
          cron_expression: cronExpr,
          use_global_cron: 0,
          extra_config: extraConfigStr,
        };
        await scheduleApi.update(editorTarget.taskKey, updateData);
        setTaskConfigs(prev => prev.map(t =>
          t.task_key === editorTarget.taskKey
            ? { ...t, cron_expression: cronExpr, use_global_cron: 0, extra_config: extraConfigStr }
            : t
        ));
        message.success(`${editorTarget.taskKey} 配置已更新`);

        // 保存后立即执行一次
        if (triggerNow) {
          try {
            setTriggeringKeys(prev => new Set(prev).add(editorTarget.taskKey));
            await scheduleApi.trigger(editorTarget.taskKey);
            message.info(`${editorTarget.taskKey} 已立即执行`);
          } catch (te) {
            message.error(`执行失败: ${te.message || te}`);
            setTriggeringKeys(prev => {
              const next = new Set(prev);
              next.delete(editorTarget.taskKey);
              return next;
            });
          }
          fetchConfig();
        }
      }
    } catch (e) {
      message.error('保存配置失败: ' + (e.message || e));
      fetchConfig();
    }
    setEditorOpen(false);
    setEditorTarget(null);
  };

  // 状态渲染 — TRIGGERED 仅表示"曾触发"，不代表正在执行
  const statusTag = (status) => {
    if (!status)
      return <Text type="secondary">—</Text>;
    switch (status) {
      case 'SUCCESS': return <Tag icon={<CheckCircleOutlined />} color="success">成功</Tag>;
      case 'FAILED': return <Tag icon={<CloseCircleOutlined />} color="error">失败</Tag>;
      case 'CANCELLED': return <Tag icon={<StopOutlined />} color="warning">已取消</Tag>;
      case 'RUNNING': return <Tag icon={<SyncOutlined spin />} color="processing">执行中</Tag>;
      case 'TRIGGERED':
        return <Tag icon={<ClockCircleOutlined />} color="default">已触发</Tag>;
      default: return <Tag>{status}</Tag>;
    }
  };

  const columns = [
    {
      title: '数据项',
      key: 'item',
      width: 240,
      render: (_, record) => {
        const def = TASK_ITEMS.find(d => d.key === record.task_key);
        // 优先使用后端返回的 sub_items 动态生成描述
        let desc = def?.desc;
        if (record.sub_items && record.sub_items.length > 0) {
          desc = record.sub_items.join(' / ');
        } else if (!desc && record.task_key === 'DAILY_RECOMMENDATION') {
          try {
            const ec = typeof record.extra_config === 'string' ? JSON.parse(record.extra_config) : record.extra_config;
            const ids = ec?.strategyIds || [];
            desc = ids.length > 0
              ? `每日按选定策略自动选股推荐（当前 ${ids.length} 个策略），含因子筛选、LLM深度分析、评分融合`
              : '每日按选定策略自动选股推荐，含因子筛选、LLM深度分析、评分融合';
          } catch {
            desc = '每日按选定策略自动选股推荐，含因子筛选、LLM深度分析、评分融合';
          }
        } else if (!desc && record.task_key === 'RECOMMENDATION_TRACK') {
          desc = '计算推荐股票的次日收益率，用于复盘追踪和策略效果评估';
        }
        return (
          <div>
            <Text strong style={{ fontSize: 13 }}>
              {def?.icon || '📦'} {record.task_name || record.task_key}
            </Text>
            <br />
            <Text type="secondary" style={{ fontSize: 11 }}>{desc}</Text>
          </div>
        );
      },
    },
    {
      title: '启用',
      key: 'enabled',
      width: 70,
      align: 'center',
      render: (_, record) => (
        <Switch
          size="small"
          checked={!!record.enabled}
          onChange={(v) => updateLocalConfig(record.task_key, 'enabled', v ? 1 : 0)}
        />
      ),
    },
    {
      title: '定时规则',
      key: 'cron',
      width: 280,
      render: (_, record) => {
        const effectiveCron = record.use_global_cron
          ? (globalConfig?.cron_expression || '—')
          : (record.cron_expression || '—');
        const explanation = effectiveCron !== '—' ? explainCron(effectiveCron) : '';
        return (
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            <Space size={4} wrap>
              <Space size={4}>
                <Switch
                  size="small"
                  checked={!!record.use_global_cron}
                  onChange={(v) => updateLocalConfig(record.task_key, 'use_global_cron', v ? 1 : 0)}
                />
                <Text type="secondary" style={{ fontSize: 11 }}>使用全局</Text>
              </Space>
              <Text code style={{ fontSize: 11, letterSpacing: 0.5 }}>
                {effectiveCron}
              </Text>
            </Space>
            {explanation && (
              <Text type="secondary" style={{ fontSize: 11, color: '#52c41a' }}>
                {explanation}
              </Text>
            )}
          </Space>
        );
      },
    },
    {
      title: '最近执行',
      key: 'lastRun',
      width: 150,
      render: (_, record) => {
        if (!record.last_run_time) return <Text type="secondary">—</Text>;
        return (
          <Space direction="vertical" size={2}>
            <Text style={{ fontSize: 11 }}>
              {dayjs(record.last_run_time).format('MM-DD HH:mm')}
            </Text>
            {statusTag(record.last_run_status)}
          </Space>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 220,
      align: 'center',
      render: (_, record) => {
        const isCustom = record.task_key?.startsWith('CUSTOM_');
        // ★ 使用 Set.has() 判断该任务是否正在执行
        const isRunning = triggeringKeys.has(record.task_key);

        return (
          <Space size={4}>
            <Button
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEditor(
                'sub',
                record.task_key,
                record.cron_expression || '0 * * * *',
                record.extra_config
              )}
            >
              修改
            </Button>
            {!isRunning ? (
              <Button
                type="primary"
                size="small"
                icon={<PlayCircleOutlined />}
                onClick={() => handleTrigger(record.task_key)}
                disabled={!record.enabled}
              >
                触发
              </Button>
            ) : (
              <>
                <Tag color="processing" icon={<SyncOutlined spin />}>执行中</Tag>
                <Popconfirm
                  title="确定取消此任务？"
                  onConfirm={() => handleCancel(record.task_key)}
                >
                  <Button size="small" danger icon={<StopOutlined />}>取消</Button>
                </Popconfirm>
              </>
            )}
            {isCustom && (
              <Popconfirm
                title="确定删除此定时配置？"
                onConfirm={async () => {
                  try {
                    await scheduleApi.delete(record.task_key);
                    message.success('已删除');
                    fetchConfig();
                  } catch (e) { message.error('删除失败'); }
                }}
              >
                <Button size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  if (loading) {
    return (
      <Card title={
        <Space><ClockCircleOutlined /> 定时任务管理</Space>
      }>
        <div style={{ padding: 60 }}>
          <Spin tip="加载中..." size="large">
            <div style={{ height: 80 }} />
          </Spin>
        </div>
      </Card>
    );
  }

  // 清空 triggeringKeys 的工具函数
  const clearAllRunning = () => setTriggeringKeys(new Set());

  return (
    <div style={{ padding: '16px' }}>
      {/* 全局配置行 */}
      {globalConfig && (
        <div style={{
          background: '#f6f8fa', borderRadius: 8, padding: '12px 20px',
          marginBottom: 16, border: '1px solid #e8e8e8'
        }}>
          <Row gutter={[32, 8]} align="middle">
            <Col>
              <Space>
                <GlobalOutlined style={{ color: '#1677ff', fontSize: 16 }} />
                <Text strong>全局调度</Text>
                <Switch
                  size="small"
                  checked={!!globalConfig.enabled}
                  onChange={(v) => {
                    setGlobalConfig({ ...globalConfig, enabled: v ? 1 : 0 });
                    saveGlobal('enabled', v ? 1 : 0);
                  }}
                />
                <Badge
                  status={globalConfig.enabled ? 'success' : 'default'}
                  text={globalConfig.enabled ? '运行中' : '已暂停'}
                />
              </Space>
            </Col>
            <Col>
              <Space>
                <Text type="secondary" style={{ fontSize: 12 }}>全局 Cron:</Text>
                <Text code style={{ fontSize: 13, letterSpacing: 0.5 }}>
                  {globalConfig.cron_expression || '—'}
                </Text>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => openEditor('global', 'GLOBAL', globalConfig.cron_expression, globalConfig.extra_config)}
                >
                  修改
                </Button>
              </Space>
            </Col>
          </Row>
        </div>
      )}

      {/* 任务列表 */}
      <Card
        title={
          <Space>
            <ThunderboltOutlined />
            <span>定时任务</span>
            <Tag color="blue">{taskConfigs.filter(t => !!t.enabled).length}/{taskConfigs.length} 启用</Tag>
            {triggeringKeys.size > 0 && (
              <Tag color="processing" icon={<SyncOutlined spin />}>
                {triggeringKeys.size} 个任务运行中
              </Tag>
            )}
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ApartmentOutlined />} onClick={handleOpenGraph}>任务关系图</Button>
            <Button icon={<ReloadOutlined />} onClick={() => { fetchConfig(); clearAllRunning(); }}>刷新</Button>
          </Space>
        }
      >
        <Table
          columns={columns}
          dataSource={taskConfigs}
          rowKey="task_key"
          pagination={false}
          size="middle"
          bordered
        />
      </Card>

      {/* 底部说明 */}
      <Card size="small" style={{ marginTop: 16 }} styles={{ body: { padding: '10px 20px' } }}>
        <Text type="secondary" style={{ fontSize: 11 }}>
          <HistoryOutlined /> Cron 格式：秒 分 时 日 月 周 · 工作日16:00 = <Text code>0 0 16 * * 1-5</Text> ·
           点击「修改」打开可视化编辑器（任务配置 + Cron 配置双 Tab）· 多任务可同时并发执行
        </Text>
      </Card>

      {/* 可视化编辑器弹窗 */}
      <CronVisualEditor
        open={editorOpen}
        initialValue={editorTarget?.initialValue || ''}
        initialExtraConfig={editorTarget?.initialExtraConfig || ''}
        taskKey={editorTarget?.taskKey || ''}
        onOk={handleEditorOk}
        onCancel={() => { setEditorOpen(false); setEditorTarget(null); }}
      />

      {/* 任务关系图弹窗 */}
      <Modal
        title={<Space><ApartmentOutlined /> 任务调度关系图</Space>}
        open={graphOpen}
        onCancel={() => setGraphOpen(false)}
        footer={null}
        width={900}
        destroyOnClose
      >
        <TaskDependencyGraph deps={graphDeps} loading={graphLoading} taskItems={TASK_ITEMS} />
      </Modal>
    </div>
  );
}

// ========== 任务关系图组件 ==========
function TaskDependencyGraph({ deps, loading, taskItems }) {
  // 构建节点和边
  const { nodes, edges, layers } = useMemo(() => {
    const taskMap = new Map();
    taskItems.forEach(t => taskMap.set(t.key, t));

    // 收集所有涉及的 task key
    const allKeys = new Set();
    deps.forEach(d => {
      allKeys.add(d.upstream_key);
      allKeys.add(d.downstream_key);
    });

    // 拓扑排序分层
    const inDegree = new Map();
    const adj = new Map(); // upstream -> [downstreams]
    allKeys.forEach(k => {
      inDegree.set(k, 0);
      adj.set(k, []);
    });
    deps.forEach(d => {
      adj.get(d.upstream_key)?.push(d.downstream_key);
      inDegree.set(d.downstream_key, (inDegree.get(d.downstream_key) || 0) + 1);
    });

    // BFS 分层
    const layerMap = new Map(); // key -> layer
    let queue = [];
    allKeys.forEach(k => {
      if ((inDegree.get(k) || 0) === 0) {
        queue.push(k);
        layerMap.set(k, 0);
      }
    });
    let layer = 1;
    while (queue.length > 0) {
      const next = [];
      for (const k of queue) {
        for (const child of (adj.get(k) || [])) {
          const newLayer = Math.max(layerMap.get(k) + 1, layer);
          layerMap.set(child, newLayer);
          next.push(child);
        }
      }
      // 去重
      queue = [...new Set(next)];
      layer++;
      if (layer > 20) break; // 安全上限
    }

    // 没有入度的节点也兜底设为 0
    allKeys.forEach(k => {
      if (!layerMap.has(k)) layerMap.set(k, 0);
    });

    // 按 layer 分组
    const maxLayer = Math.max(...layerMap.values(), 0);
    const layerGroups = [];
    for (let i = 0; i <= maxLayer; i++) {
      layerGroups.push([...allKeys].filter(k => layerMap.get(k) === i));
    }

    // 计算节点坐标
    const colWidth = 200;
    const rowHeight = 80;
    const nodeWidth = 150;
    const nodeHeight = 50;
    const startX = 40;
    const startY = 40;

    const nodePos = new Map();
    layerGroups.forEach((group, colIdx) => {
      group.forEach((key, rowIdx) => {
        nodePos.set(key, {
          x: startX + colIdx * colWidth,
          y: startY + rowIdx * rowHeight,
        });
      });
    });

    const nodeList = allKeys.size > 0
      ? [...allKeys].map(k => {
          const pos = nodePos.get(k);
          const item = taskMap.get(k);
          return {
            key: k,
            name: item?.name || k,
            x: pos.x,
            y: pos.y,
            width: nodeWidth,
            height: nodeHeight,
          };
        })
      : [];

    const edgeList = deps.map((d, i) => {
      const from = nodePos.get(d.upstream_key);
      const to = nodePos.get(d.downstream_key);
      if (!from || !to) return null;
      return {
        id: i,
        from: d.upstream_key,
        to: d.downstream_key,
        x1: from.x + nodeWidth,
        y1: from.y + nodeHeight / 2,
        x2: to.x,
        y2: to.y + nodeHeight / 2,
      };
    }).filter(Boolean);

    const svgWidth = Math.max(...nodeList.map(n => n.x + n.width), 200) + 60;
    const svgHeight = Math.max(...nodeList.map(n => n.y + n.height), 100) + 40;

    return { nodes: nodeList, edges: edgeList, layers: { width: svgWidth, height: svgHeight } };
  }, [deps, taskItems]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 60 }}>
        <Spin tip="加载中..." />
      </div>
    );
  }

  if (deps.length === 0) {
    return (
      <div style={{ textAlign: 'center', padding: 60 }}>
        <Text type="secondary">暂无任务依赖关系</Text>
      </div>
    );
  }

  const colorMap = {};
  taskItems.forEach(t => { colorMap[t.key] = t.color; });

  return (
    <div style={{ overflow: 'auto', maxHeight: 500 }}>
      <svg width={layers.width} height={layers.height} style={{ minWidth: '100%' }}>
        <defs>
          <marker
            id="arrowhead"
            markerWidth="10"
            markerHeight="7"
            refX="9"
            refY="3.5"
            orient="auto"
          >
            <polygon points="0 0, 10 3.5, 0 7" fill="#8c8c8c" />
          </marker>
        </defs>

        {/* 边 */}
        {edges.map(e => (
          <g key={`edge-${e.id}`}>
            <path
              d={`M ${e.x1} ${e.y1} C ${e.x1 + 40} ${e.y1}, ${e.x2 - 40} ${e.y2}, ${e.x2} ${e.y2}`}
              fill="none"
              stroke="#8c8c8c"
              strokeWidth={1.5}
              markerEnd="url(#arrowhead)"
            />
          </g>
        ))}

        {/* 节点 */}
        {nodes.map(n => (
          <g key={`node-${n.key}`}>
            <rect
              x={n.x}
              y={n.y}
              width={n.width}
              height={n.height}
              rx={6}
              ry={6}
              fill={colorMap[n.key] || '#1677ff'}
              fillOpacity={0.1}
              stroke={colorMap[n.key] || '#1677ff'}
              strokeWidth={1.5}
            />
            <text
              x={n.x + n.width / 2}
              y={n.y + 18}
              textAnchor="middle"
              fontSize={12}
              fontWeight={600}
              fill={colorMap[n.key] || '#1677ff'}
            >
              {n.key}
            </text>
            <text
              x={n.x + n.width / 2}
              y={n.y + 36}
              textAnchor="middle"
              fontSize={11}
              fill="#595959"
            >
              {n.name.length > 8 ? n.name.slice(0, 7) + '…' : n.name}
            </text>
          </g>
        ))}
      </svg>

      <div style={{ marginTop: 12, padding: '8px 12px', background: '#f5f5f5', borderRadius: 6 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          箭头方向：上游任务 → 下游任务（上游完成后自动触发下游）
        </Text>
      </div>
    </div>
  );
}
