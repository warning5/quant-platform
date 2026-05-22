import React from 'react';
import { Card, Row, Col, Table, Statistic, Tag, Alert, Empty, Spin, Typography, Tooltip } from 'antd';
import ReactECharts from 'echarts-for-react';

const { Text } = Typography;

/**
 * 股东结构 Tab
 * 展示：1) 股东人数趋势图  2) 筹码集中度信号  3) 基金持仓明细
 */
export function ShareholderStructureTab({ data, code }) {
  if (!data) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (data.error) return <Alert type="warning" message={data.error} showIcon />;

  const {
    shareholderHistory = [],
    fundHolders = [],
    latestHolderCount,
    changePct,
    concentration,
    totalFundRatio,
  } = data;

  // ── 股东人数趋势图 ─────────────────────────────────────
  const holderChartOption = React.useMemo(() => {
    if (!shareholderHistory || shareholderHistory.length === 0) return {};
    // 历史按日期正序（数据库返回倒序）
    const sorted = [...shareholderHistory].reverse();
    const dates = sorted.map(d => String(d.report_date).substring(0, 10));
    const counts = sorted.map(d => d.holder_count || 0);
    const changes = sorted.map(d => {
      const v = parseFloat(d.change_pct);
      return isNaN(v) ? 0 : v;
    });

    // 计算人均持股（如果有avg_shares）
    const avgShares = sorted.map(d => {
      const v = parseFloat(d.avg_shares);
      return isNaN(v) ? null : v;
    });
    const hasAvgShares = avgShares.some(v => v !== null);

    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['股东人数(户)', ...(hasAvgShares ? ['人均持股(股)'] : [])], bottom: 0 },
      grid: { left: 65, right: 60, top: 20, height: '55%' },
      xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 10, rotate: 15 } },
      yAxis: [
        { type: 'value', name: '户数', axisLabel: { formatter: v => (v / 10000).toFixed(0) + '万' } },
        ...(hasAvgShares ? [{ type: 'value', name: '股', position: 'right' }] : []),
      ],
      series: [
        {
          name: '股东人数(户)',
          type: 'line',
          data: counts,
          lineStyle: { width: 2.5, color: '#1677ff' },
          itemStyle: { color: '#1677ff' },
          symbol: 'circle',
          symbolSize: 6,
        },
        ...(hasAvgShares ? [{
          name: '人均持股(股)',
          type: 'bar',
          yAxisIndex: 1,
          data: avgShares.map(v => v || 0),
          barMaxWidth: 16,
          itemStyle: { color: '#52c41a' },
        }] : []),
      ],
    };
  }, [shareholderHistory]);

  // ── 基金持仓列定义 ──────────────────────────────────────
  const fundColumns = [
    { title: '基金名称', dataIndex: 'fund_name', ellipsis: true, render: (v) => (
      <Tooltip title={v}>{v}</Tooltip>
    )},
    { title: '基金代码', dataIndex: 'fund_code', width: 100, align: 'center' },
    { title: '占流通股比', dataIndex: 'float_ratio', width: 110, align: 'right',
      render: (v) => v != null ? `${parseFloat(v).toFixed(2)}%` : '-' },
    { title: '持有份额(万)', dataIndex: 'shares_held', width: 120, align: 'right',
      render: (v) => v != null ? `${(Number(v) / 10000).toFixed(0)}万` : '-' },
    { title: '季度变动', dataIndex: 'change_pct_qoq', width: 100, align: 'right',
      render: (v) => {
        if (v == null) return '-';
        const n = parseFloat(v);
        const c = n > 0 ? '#f5222d' : n < 0 ? '#26a69a' : '#999';
        return <Text style={{ color: c }}>{n > 0 ? '+' : ''}{n.toFixed(2)}%</Text>;
      }},
  ];

  return (
    <div>
      {/* ── 筹码集中度信号卡 ─────────────────────────────── */}
      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="最新股东户数"
              value={latestHolderCount}
              suffix="户"
              valueStyle={{ fontSize: 18 }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="季度变化"
              value={changePct != null ? changePct : '-'}
              suffix="%"
              valueStyle={{
                fontSize: 18,
                color: changePct != null && changePct < 0 ? '#f5222d'
                  : changePct != null && changePct > 0 ? '#26a69a' : undefined,
              }}
              formatter={(v) => typeof v === 'number' && v < 0 ? Math.abs(v) : v}
              prefix={changePct != null && changePct < 0 ? '\u2193' : changePct != null && changePct > 0 ? '\u2191' : ''}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <div style={{ fontSize: 11, color: '#999' }}>筹码状态</div>
            <div style={{ fontSize: 14, fontWeight: 600, marginTop: 4 }}>{concentration || '-'}</div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="基金合计占比"
              value={totalFundRatio != null ? totalFundRatio : '-'}
              suffix="%"
              precision={2}
              valueStyle={{ fontSize: 18 }}
            />
          </Card>
        </Col>
      </Row>

      {/* ── 趋势图 ───────────────────────────────────────── */}
      {shareholderHistory.length > 0 ? (
        <Card size="small" title="股东人数趋势" style={{ marginBottom: 16 }}>
          <ReactECharts option={holderChartOption} style={{ height: 320 }} notMerge lazyUpdate />
        </Card>
      ) : (
        <Alert type="info" message="暂无股东人数数据，请运行 python update_shareholder_batch.py 采集" showIcon style={{ marginBottom: 16 }} />
      )}

      {/* ── 基金持仓明细 ─────────────────────────────────── */}
      <Card size="small" title={`基金持仓明细（最新一期${fundHolders.length > 0 ? '' : ' — 暂无数据'}）`}>
        {fundHolders.length > 0 ? (
          <Table
            dataSource={fundHolders}
            rowKey={(r, i) => r.fund_code || i}
            columns={fundColumns}
            size="small"
            pagination={false}
            scroll={{ x: 600 }}
          />
        ) : (
          <Empty description="暂无基金持仓数据" />
        )}
      </Card>

      {/* 数据说明 */}
      <div style={{ fontSize: 11, color: '#999', marginTop: 12, paddingLeft: 4 }}>
        数据来源：东方财富股东人数（stock_zh_a_gdhs_detail_em）；基金持仓来自基金季报。
        股东人数下降通常意味着筹码趋于集中，利好后续走势；上升则意味着散户入场，需警惕。
      </div>
    </div>
  );
}
