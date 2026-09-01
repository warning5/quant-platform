import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Card, Segmented, DatePicker, Button, Spin, Empty, Alert, Row, Col, Tooltip, Tag, Space, Table } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import api from '../../api';
import ReactECharts from '../../components/LazyECharts';

// 把某日的 distribution 按该日总量归一化为占比(%)
function normalize(distribution) {
  if (!distribution || !distribution.length) return [];
  const total = distribution.reduce((s, p) => s + (p.value || 0), 0) || 1;
  return distribution.map((p) => ({ price: Number(p.price), pct: (p.value / total) * 100 }));
}

// 截图风格：红色=获利盘(价格低于收盘价)，蓝色=套牢盘(价格高于收盘价)
const RED = '#ef4444';
const BLUE = '#3b82f6';
const GRAY = '#9ca3af';
const PALETTE = ['#1677ff', '#722ed1', '#fa8c16', '#13c2c2', '#eb2f96', '#52c41a', '#2f54eb', '#fa541c', '#a0d911', '#f5222d'];

function mainCostTag(conf) {
  if (conf === 'H') return <Tag color="green">置信 高</Tag>;
  if (conf === 'M') return <Tag color="gold">置信 中</Tag>;
  return <Tag>置信 低</Tag>;
}

export function CyqDistribution({ code, tradeDate, onClearLink }) {
  const [mode, setMode] = useState('latest'); // latest | history
  const [dates, setDates] = useState([]); // 历史模式选中的日期(dayjs[])
  const [loading, setLoading] = useState(false);
  const [latest, setLatest] = useState(null); // 最新快照
  const [multi, setMulti] = useState([]); // 多日列表
  const [missingDays, setMissingDays] = useState([]); // 多选时后端缺失(无数据)的日期
  const [errMsg, setErrMsg] = useState('');
  const [pinnedDay, setPinnedDay] = useState(null); // K线联动选中的单日快照

  // 实时页: 默认拉最新快照
  const fetchLatest = useCallback(async () => {
    if (!code) return;
    setLoading(true); setErrMsg('');
    try {
      const res = await api.get('/cyq', { params: { code } });
      const d = res;
      if (!d || d.found === false) {
        setLatest(null);
        setErrMsg('暂无筹码数据(请先运行 CYQ 计算服务)');
      } else {
        setLatest(d);
      }
    } catch (e) {
      setErrMsg('查询失败: ' + (e?.response?.data?.message || e.message));
    } finally {
      setLoading(false);
    }
  }, [code]);

  const fetchMulti = useCallback(async () => {
    if (!code || !dates.length) return;
    setLoading(true); setErrMsg(''); setMissingDays([]);
    try {
      const ds = dates.map((d) => d.format('YYYY-MM-DD')).join(',');
      const res = await api.get('/cyq/multi', { params: { code, dates: ds } });
      const d = res;
      const items = d?.items || [];
      setMulti(items);
      if (items.length === 0) {
        setErrMsg('所选日期无筹码数据');
      } else {
        // 后端按 IN(...) 只回有数据的日期, 比对选中日期找出缺日
        const got = new Set(items.map((it) => it.trade_date));
        const miss = dates.map((x) => x.format('YYYY-MM-DD')).filter((sd) => !got.has(sd));
        setMissingDays(miss);
      }
    } catch (e) {
      setErrMsg('查询失败: ' + (e?.response?.data?.message || e.message));
    } finally {
      setLoading(false);
    }
  }, [code, dates]);

  useEffect(() => {
    if (mode === 'latest') fetchLatest();
  }, [mode, fetchLatest]);

  // K线联动：选中某日 -> 拉该日筹码分布(复用 /cyq/multi 单日)
  const isLinked = !!tradeDate;
  useEffect(() => {
    if (!tradeDate || !code) { setPinnedDay(null); return; }
    let cancelled = false;
    setLoading(true); setErrMsg('');
    api.get('/cyq/multi', { params: { code, dates: tradeDate } })
      .then((res) => {
        if (cancelled) return;
        const items = res?.items || [];
        setPinnedDay(items[0] || null);
        if (!items.length) setErrMsg('所选日期无筹码数据');
      })
      .catch((e) => { if (!cancelled) setErrMsg('查询失败: ' + (e?.response?.data?.message || e.message)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tradeDate, code]);

  const days = useMemo(() => {
    if (isLinked) return pinnedDay ? [pinnedDay] : [];
    if (mode === 'latest') return latest ? [latest] : [];
    return multi;
  }, [isLinked, pinnedDay, mode, latest, multi]);

  const option = useMemo(() => {
    if (!days.length) return null;

    // 单日视图：仿截图右侧横向筹码分布
    if (days.length === 1) {
      const day = days[0];
      const close = Number(day.close_price);
      const hasClose = Number.isFinite(close) && close > 0;
      const mc = Number(day.main_cost);
      const hasMc = Number.isFinite(mc) && mc > 0;
      const norm = normalize(day.distribution);
      if (!norm.length) return null;
      const prices = norm.map((p) => p.price);
      const step = prices.length > 1 ? prices[1] - prices[0] : 0.01;
      const half = step / 2;

      let pMin = Math.min(...prices), pMax = Math.max(...prices);
      const span = (pMax - pMin) || 1;
      pMin -= span * 0.02;
      pMax += span * 0.02;

      const renderItem = (params, api) => {
        const price = api.value(0);
        const pct = api.value(1);
        const p0 = api.coord([0, price - half]);
        const p1 = api.coord([pct, price + half]);
        return {
          type: 'rect',
          shape: {
            x: p0[0],
            y: p1[1],
            width: p1[0] - p0[0],
            height: p0[1] - p1[1],
          },
          style: api.style({
            fill: hasClose ? (price < close ? RED : BLUE) : GRAY,
            stroke: null,
          }),
        };
      };

      const series = [
        {
          type: 'custom',
          renderItem,
          data: norm.map((p) => [p.price, Number(p.pct.toFixed(3))]),
          clip: true,
          encode: { x: 1, y: 0 },
        },
      ];

      if (hasClose) {
        series.push({
          type: 'line',
          markLine: {
            silent: true,
            symbol: 'none',
            data: [{
              yAxis: close,
              label: {
                formatter: `收盘价 ${close.toFixed(2)}`,
                position: 'end',
                fontSize: 10,
                color: '#b45309',
                backgroundColor: 'rgba(254,243,199,0.9)',
                padding: [2, 4],
                borderRadius: 3,
              },
            }],
            lineStyle: { color: '#f59e0b', type: 'dashed', width: 1 },
          },
        });
      }

      if (hasMc) {
        series.push({
          type: 'line',
          markLine: {
            silent: true,
            symbol: 'none',
            data: [{
              yAxis: mc,
              label: { formatter: `主力成本 ${mc.toFixed(2)}`, position: 'end', fontSize: 10, color: '#ea580c' },
            }],
            lineStyle: { color: '#ea580c', type: 'solid', width: 1.5 },
          },
        });
      }

      return {
        grid: { left: 24, right: 80, top: 16, bottom: 32 },
        tooltip: {
          trigger: 'item',
          formatter: (p) => {
            const price = Number(p.value[0]);
            const pct = Number(p.value[1]);
            const profit = hasClose && price < close;
            return `价格: ${price.toFixed(2)}<br/>筹码占比: ${pct.toFixed(2)}%<br/>${profit ? '获利盘' : hasClose ? '套牢盘' : '收盘价缺失'}`;
          },
        },
        xAxis: {
          type: 'value',
          name: '筹码占比(%)',
          nameTextStyle: { fontSize: 11 },
          axisLabel: { fontSize: 10 },
          splitLine: { show: false },
        },
        yAxis: {
          type: 'value',
          position: 'right',
          inverse: false,
          min: pMin,
          max: pMax,
          scale: true,
          name: '价格',
          nameLocation: 'end',
          nameGap: 8,
          nameTextStyle: { fontSize: 11 },
          axisLabel: { fontSize: 10, formatter: (v) => Number(v).toFixed(2) },
          splitLine: { show: false },
        },
        series,
      };
    }

    // 多日视图：纵向叠加对比
    let pMin = Infinity, pMax = -Infinity;
    days.forEach((d) => {
      (d.distribution || []).forEach((p) => {
        const pr = Number(p.price);
        if (pr < pMin) pMin = pr;
        if (pr > pMax) pMax = pr;
      });
    });
    if (!isFinite(pMin)) { pMin = 0; pMax = 1; }
    const span = (pMax - pMin) || 1;
    pMin = pMin - span * 0.02; pMax = pMax + span * 0.02;

    const series = days.map((d, i) => {
      const norm = normalize(d.distribution);
      const dayClose = Number(d.close_price);
      const hasDayClose = Number.isFinite(dayClose) && dayClose > 0;
      const mc = Number(d.main_cost);
      const hasMc = Number.isFinite(mc) && mc > 0;
      return {
        name: d.trade_date,
        type: 'bar',
        data: norm.map((p) => ({
          value: [Number(p.pct.toFixed(3)), Number(p.price)],
          itemStyle: { color: hasDayClose ? (p.price < dayClose ? RED : BLUE) : PALETTE[i % PALETTE.length] },
        })),
        barWidth: days.length > 1 ? 7 : 12,
        barGap: '-100%',
        emphasis: { disabled: true },
        z: i + 1,
        markLine: hasMc ? {
          silent: true, symbol: 'none',
          data: [{
            yAxis: mc,
            label: { formatter: `主力 ${mc.toFixed(2)}`, position: 'end', fontSize: 9, color: PALETTE[i % PALETTE.length] },
          }],
          lineStyle: { color: PALETTE[i % PALETTE.length], type: 'solid', width: 1 },
        } : undefined,
      };
    });

    return {
      grid: { left: 56, right: 24, top: 36, bottom: 40 },
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const day = days.find((d) => d.trade_date === p.seriesName);
          const dayClose = Number(day?.close_price);
          const hasDayClose = Number.isFinite(dayClose) && dayClose > 0;
          const profit = hasDayClose && Number(p.value[1]) < dayClose;
          return `${p.seriesName}<br/>价格: ${Number(p.value[1]).toFixed(2)}<br/>` +
            `筹码占比: ${Number(p.value[0]).toFixed(2)}%<br/>` +
            `${hasDayClose ? (profit ? '获利盘' : '套牢盘') : '收盘价缺失'}`;
        },
      },
      legend: {
        type: 'scroll', top: 4,
        data: days.map((d) => d.trade_date),
        textStyle: { fontSize: 11 },
      },
      xAxis: { type: 'value', name: '筹码占比(%)', nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10 } },
      yAxis: {
        type: 'value', name: '价格', inverse: false, min: pMin, max: pMax, scale: true,
        nameTextStyle: { fontSize: 11 }, axisLabel: { fontSize: 10, formatter: (v) => Number(v).toFixed(2) },
      },
      series,
    };
  }, [days]);

  const metricColumns = [
    { title: '日期', dataIndex: 'trade_date', key: 'trade_date', width: 110 },
    { title: '收盘价', dataIndex: 'close_price', key: 'close_price',
      render: (v) => <span>{Number(v).toFixed(2)}</span> },
    { title: '平均成本', dataIndex: 'avg_cost', key: 'avg_cost',
      render: (v) => <span style={{ color: RED }}>{Number(v).toFixed(2)}</span> },
    { title: '获利比例', dataIndex: 'benefit', key: 'benefit',
      render: (v) => <span style={{ color: Number(v) >= 0.5 ? RED : BLUE }}>{(Number(v) * 100).toFixed(2)}%</span> },
    { title: '90%成本', key: 'c90',
      render: (_, r) => `${Number(r.c90_lo).toFixed(2)} ~ ${Number(r.c90_hi).toFixed(2)}` },
    { title: '90%集中度', dataIndex: 'c90_conc', key: 'c90_conc',
      render: (v) => Number(v).toFixed(3) },
    { title: '70%成本', key: 'c70',
      render: (_, r) => `${Number(r.c70_lo).toFixed(2)} ~ ${Number(r.c70_hi).toFixed(2)}` },
    { title: '70%集中度', dataIndex: 'c70_conc', key: 'c70_conc',
      render: (v) => Number(v).toFixed(3) },
    { title: '主力成本(估算)', key: 'main_cost',
      render: (_, r) => r.main_cost
        ? <span style={{ color: '#ea580c' }}>{Number(r.main_cost).toFixed(2)} {mainCostTag(r.main_cost_conf)}</span>
        : '—' },
  ];

  const renderSingleMetrics = () => {
    if (days.length !== 1) return null;
    const d = days[0];
    const close = Number(d.close_price);
    const hasClose = Number.isFinite(close) && close > 0;
    const benefit = Number(d.benefit);
    return (
      <div style={{ marginTop: 12, padding: '10px 12px', background: '#f8fafc', borderRadius: 8, border: '1px solid #e2e8f0' }}>
        <Row gutter={[16, 8]} align="middle">
          <Col span={6}>
            <div style={{ fontSize: 12, color: '#64748b' }}>收盘获利</div>
            <div style={{ color: hasClose && benefit >= 0.5 ? RED : BLUE, fontSize: 16, fontWeight: 600 }}>
              {(benefit * 100).toFixed(2)}%
            </div>
          </Col>
          <Col span={6}>
            <div style={{ fontSize: 12, color: '#64748b' }}>平均成本</div>
            <div style={{ color: '#334155', fontSize: 16, fontWeight: 600 }}>{Number(d.avg_cost).toFixed(2)}</div>
          </Col>
          <Col span={6}>
            <div style={{ fontSize: 12, color: '#64748b' }}>90%筹码</div>
            <div style={{ color: '#334155', fontSize: 14 }}>{Number(d.c90_lo).toFixed(2)} ~ {Number(d.c90_hi).toFixed(2)}</div>
          </Col>
          <Col span={6}>
            <div style={{ fontSize: 12, color: '#64748b' }}>70%筹码</div>
            <div style={{ color: '#334155', fontSize: 14 }}>{Number(d.c70_lo).toFixed(2)} ~ {Number(d.c70_hi).toFixed(2)}</div>
          </Col>
        </Row>
        <div style={{ marginTop: 8, padding: '8px 10px', background: '#fff7ed', borderRadius: 8, border: '1px solid #fed7aa' }}>
          <Row align="middle" gutter={[12, 4]}>
            <Col><span style={{ fontSize: 12, color: '#64748b' }}>主力成本(估算)</span></Col>
            <Col><span style={{ fontSize: 16, fontWeight: 600, color: '#ea580c' }}>{d.main_cost ? Number(d.main_cost).toFixed(2) : '—'}</span></Col>
            <Col><span style={{ fontSize: 12, color: '#94a3b8' }}>区间 {d.main_cost_lo ? Number(d.main_cost_lo).toFixed(2) : '—'} ~ {d.main_cost_hi ? Number(d.main_cost_hi).toFixed(2) : '—'}</span></Col>
            <Col>{mainCostTag(d.main_cost_conf)}</Col>
          </Row>
          <div style={{ marginTop: 4, fontSize: 11, color: '#94a3b8' }}>①大单加权均价 ②筹码集中带 ③龙虎榜锚点 融合估算，非精确值</div>
        </div>
        {!hasClose && (
          <div style={{ marginTop: 6, fontSize: 12, color: '#94a3b8' }}>收盘价缺失，红/蓝分色暂时不可用</div>
        )}
      </div>
    );
  };

  return (
    <Card
      size="small"
      title="筹码分布 (CYQ)"
      extra={
        isLinked ? (
          <Tag color="blue">联动 K线选中日</Tag>
        ) : (
          <Space>
            <Segmented
              value={mode}
              onChange={setMode}
              options={[
                { label: '最新', value: 'latest' },
                { label: '历史对比', value: 'history' },
              ]}
            />
          </Space>
        )
      }
    >
      {isLinked && (
        <Alert
          type="info"
          showIcon
          message={`已联动 K线选中日 ${tradeDate} 的筹码分布`}
          action={
            <Button size="small" onClick={onClearLink}>
              恢复最新
            </Button>
          }
          style={{ marginBottom: 12 }}
        />
      )}

      {!isLinked && mode === 'history' && (
        <Space wrap style={{ marginBottom: 12 }}>
          <DatePicker
            multiple
            maxCount={10}
            placeholder="选择最多 10 个日期"
            value={dates}
            onChange={setDates}
            style={{ minWidth: 320 }}
          />
          <Button type="primary" onClick={fetchMulti} disabled={!dates.length}>
            查询对比
          </Button>
          <Tooltip title="红色=获利盘(价格低于收盘价)，蓝色=套牢盘(价格高于收盘价)">
            <Tag color="default" icon={<InfoCircleOutlined />}>红获利/蓝套牢</Tag>
          </Tooltip>
        </Space>
      )}

      {errMsg && <Alert type="warning" showIcon message={errMsg} style={{ marginBottom: 12 }} />}

      {mode === 'history' && missingDays.length > 0 && (
        <Alert
          type="info"
          showIcon
          message={`${missingDays.length} 天无筹码数据(可能未运行 CYQ 计算或当日停牌): ${missingDays.join('、')}`}
          style={{ marginBottom: 12 }}
        />
      )}

      <Spin spinning={loading}>
        {!days.length ? (
          <Empty description={isLinked ? '所选日期无筹码数据' : mode === 'latest' ? '暂无最新筹码数据' : '请选择日期查询'} />
        ) : (
          <>
            {days.length > 0 && (
              <div style={{ marginBottom: 8, fontSize: 12, color: '#64748b' }}>
                数据日期：
                <Tag color="geekblue">
                  {days.length === 1 ? days[0].trade_date : `${days[0].trade_date} ~ ${days[days.length - 1].trade_date}`}
                </Tag>
                {isLinked && <span style={{ color: '#1677ff' }}>（联动 K线选中日）</span>}
              </div>
            )}
            <ReactECharts option={option} style={{ height: 420, width: '100%' }} notMerge lazyUpdate />
            {renderSingleMetrics()}
            {days.length > 1 && (
              <Table
                size="small"
                rowKey="trade_date"
                columns={metricColumns}
                dataSource={days}
                pagination={false}
                scroll={{ x: 760 }}
                style={{ marginTop: 12 }}
              />
            )}
          </>
        )}
      </Spin>
    </Card>
  );
}

export default CyqDistribution;
