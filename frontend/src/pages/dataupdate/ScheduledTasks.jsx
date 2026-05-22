import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Card, Table, Switch, Button, Tag, Typography, Space,
  Select, TimePicker, Input, message, Row, Col,
  Popconfirm, Badge, Spin, Modal, Tabs, Checkbox, Tooltip, DatePicker
} from 'antd';
import {
  PlayCircleOutlined, ClockCircleOutlined,
  CheckCircleOutlined, CloseCircleOutlined, SyncOutlined,
  ThunderboltOutlined, GlobalOutlined, HistoryOutlined,
  ReloadOutlined, StopOutlined, DeleteOutlined,
  EditOutlined, CheckOutlined, ClearOutlined, SettingOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { scheduleApi } from '../../api/index';

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
    key: 'SENTIMENT_OTHER', name: '情绪数据-其它', desc: '龙虎榜/融资融券/机构调研/涨跌停池/公告/基金持仓/股东人数/新闻',
    icon: '🔥', defaultEnabled: true, color: '#f5222d',
  },
  {
    key: 'RESEARCH', name: '研报数据', desc: '券商研报评级与盈利预测',
    icon: '📝', defaultEnabled: false, color: '#faad14',
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
  const parts = cronExpr.split(/\s+/);
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
  if (parts.length < 2) return dayjs('16:00', 'HH:mm');
  const h = parseInt(parts[1], 10);
  const m = parseInt(parts[0], 10);
  if (isNaN(h) || isNaN(m)) return dayjs('16:00', 'HH:mm');
  return dayjs().hour(h).minute(m).second(0);
}

// ========== extra_config 解析工具 ==========

/** 解析 DB 中的 extra_config JSON 字符串 */
function parseExtraConfig(raw) {
  if (!raw) return { incremental: false, dateMode: 'today', startDate: null, endDate: null };
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return {
      incremental: !!obj.incremental,
      dateMode: obj.dateMode || 'today',
      startDate: obj.startDate || null,
      endDate: obj.endDate || null,
    };
  } catch {
    return { incremental: false, dateMode: 'today', startDate: null, endDate: null };
  }
}

/** 将任务配置序列化为 extra_config JSON 字符串 */
function stringifyExtraConfig(config) {
  return JSON.stringify({
    incremental: config.incremental,
    dateMode: config.dateMode,
    startDate: config.startDate,
    endDate: config.endDate,
  });
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
  const results = [];
  const now = dayjs();
  let cursor = now.startOf('second').add(1, 'second');
  const limit = now.add(4, 'year');

  while (results.length < count && cursor.isBefore(limit)) {
    const s = cursor.second();
    const m = cursor.minute();
    const h = cursor.hour();
    const dom = cursor.date();
    const month = cursor.month() + 1;
    const dow = cursor.day();

    if (fieldSets[0].has(s) &&
        fieldSets[1].has(m) &&
        fieldSets[2].has(h) &&
        fieldSets[3].has(dom) &&
        fieldSets[4].has(month) &&
        fieldSets[5].has(dow)) {
      results.push(cursor);
    }
    cursor = cursor.add(1, 'second');
  }

  return results;
}

// ========== 可视化编辑器弹窗（含 Cron + 任务配置 双 Tab） ==========

function CronVisualEditor({ open, initialValue, initialExtraConfig, onOk, onCancel }) {
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
  const [incremental, setIncremental] = useState(false);
  const [dateMode, setDateMode] = useState('today');
  const [customDates, setCustomDates] = useState(null); // [dayjs, dayjs]

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
    }
  }, [open, initialValue, initialExtraConfig]);

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
  // 最近3次执行时间基于 effectiveCron 计算
  const nextRuns = useMemo(() => getNextRunTimes(effectiveCron, 3), [effectiveCron]);

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
  const mainTabItems = useMemo(() => [
    {
      key: 'config',
      label: <span><SettingOutlined /> 任务配置</span>,
      children: (
        <div style={{ padding: '16px 0' }}>
          {/* 增量/全量 */}
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

          {/* 日期范围 */}
          <div>
            <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>日期范围</Text>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Select
                value={dateMode}
                onChange={v => setDateMode(v)}
                options={DATE_MODE_OPTIONS}
                style={{ width: 200 }}
              />

              {dateMode === 'custom' ? (
                <RangePicker
                  value={customDates}
                  onChange={(dates) => setCustomDates(dates)}
                  format="YYYY-MM-DD"
                  placeholder={['开始日期', '结束日期']}
                />
              ) : (
                <div style={{
                  padding: '8px 12px',
                  background: '#fafafa',
                  borderRadius: 6,
                  border: '1px solid #f0f0f0',
                  maxWidth: 360,
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
          </div>
        </div>
      ),
    },
    {
      key: 'cron',
      label: <span><ClockCircleOutlined /> Cron 配置</span>,
      children: (
        <div>
          {/* 内层：6个 cron 字段子 Tab */}
          <Tabs
            activeKey={activeCronFieldIdx}
            onChange={setActiveCronFieldIdx}
            items={cronFieldTabs}
            size="small"
            type="card"
          />

          {/* ★ Cron 预览区（可编辑 + 最近3次执行）— 放在 Cron 配置内部 */}
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
                onBlur={() => { /* blur 时已通过 onChange 处理 */ }}
                onPressEnter={() => {}}
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

            {nextRuns.length > 0 && (
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
            )}
          </div>
        </div>
      ),
    },
  ], [cronFieldTabs, incremental, dateMode, customDates]);

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
    });
  }, [incremental, dateMode, customDates]);

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
        items={mainTabItems}
        size="middle"
        type="card"
        style={{ marginBottom: 16 }}
      />

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
      if (res && res.taskId) {
        message.success(`${taskKey} 任务已触发`);
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
        return (
          <div>
            <Text strong style={{ fontSize: 13 }}>
              {def?.icon || '📦'} {record.task_name || record.task_key}
            </Text>
            <br />
            <Text type="secondary" style={{ fontSize: 11 }}>{def?.desc}</Text>
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
      width: 260,
      render: (_, record) => (
        <Space size={8} wrap>
          <Space size={4}>
            <Switch
              size="small"
              checked={!!record.use_global_cron}
              onChange={(v) => updateLocalConfig(record.task_key, 'use_global_cron', v ? 1 : 0)}
            />
            <Text type="secondary" style={{ fontSize: 11 }}>使用全局</Text>
          </Space>

          <Text code style={{ fontSize: 11, letterSpacing: 0.5 }}>
            {record.use_global_cron
              ? (globalConfig?.cron_expression || '—')
              : (record.cron_expression || '—')}
          </Text>
        </Space>
      ),
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
    <div style={{ padding: '16px 24px', maxWidth: 1400 }}>
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
          <Button icon={<ReloadOutlined />} onClick={() => { fetchConfig(); clearAllRunning(); }}>刷新</Button>
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
        onOk={handleEditorOk}
        onCancel={() => { setEditorOpen(false); setEditorTarget(null); }}
      />
    </div>
  );
}
