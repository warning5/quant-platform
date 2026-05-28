import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Card, Progress, Typography, Space, Button, Tag, Statistic, Row, Col,
  Alert, Spin,
} from 'antd';
import {
  LineChartOutlined, CheckCircleOutlined, CloseCircleOutlined,
  ArrowLeftOutlined, FileTextOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { Client } from '@stomp/stompjs';
import { backtestApi } from '../../api';

const { Title, Text } = Typography;

// 原生 WebSocket 端点（Vite 代理转发到后端 /api/ws-native）
const WS_URL = '/ws-native';

// ─── 工具 ──────────────────────────────────────────────────────────────────
function pct(v) {
  if (v == null) return '-';
  return `${(v * 100).toFixed(2)}%`;
}

// ─── 实时对比折线图 ────────────────────────────────────────────────────────
function LiveChart({ points }) {
  const dates      = points.map(p => p.date);
  const stratVals  = points.map(p => +((p.stratValue - 1) * 100).toFixed(4));
  const bmVals     = points.map(p => +((p.bmValue - 1) * 100).toFixed(4));
  const excessVals = points.map(p => +((p.stratValue - p.bmValue) * 100).toFixed(4));

  const lastStrat  = stratVals.at(-1) ?? 0;
  const lastBm     = bmVals.at(-1) ?? 0;
  const lastExcess = excessVals.at(-1) ?? 0;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { type: 'dashed' } },
      formatter: params => {
        if (!params.length) return '';
        let html = `<div style="font-weight:600;margin-bottom:4px">${params[0].name}</div>`;
        params.forEach(p => {
          const col = p.seriesName === '策略收益' ? '#1677ff'
            : p.seriesName === '基准收益' ? '#faad14' : '#52c41a';
          const sign = p.value >= 0 ? '+' : '';
          html += `<div><span style="color:${col}">●</span> ${p.seriesName}：`
               + `<b style="color:${p.value >= 0 ? '#52c41a' : '#ff4d4f'}">${sign}${(+p.value).toFixed(2)}%</b></div>`;
        });
        return html;
      },
    },
    legend: {
      data: ['策略收益', '基准收益', '超额收益'],
      top: 4,
      textStyle: { color: '#666' },
    },
    grid: { left: 56, right: 24, top: 42, bottom: 56 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { rotate: 30, fontSize: 10, color: '#888' },
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#ddd' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => `${v > 0 ? '+' : ''}${v.toFixed(1)}%`, color: '#888' },
      splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
    },
    series: [
      {
        name: '策略收益',
        type: 'line',
        data: stratVals,
        smooth: true,
        lineStyle: { color: '#1677ff', width: 2.5 },
        itemStyle: { color: '#1677ff' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(22,119,255,0.18)' },
              { offset: 1, color: 'rgba(22,119,255,0.01)' },
            ],
          },
        },
        symbol: 'none',
        z: 3,
      },
      {
        name: '基准收益',
        type: 'line',
        data: bmVals,
        smooth: true,
        lineStyle: { color: '#faad14', width: 2, type: 'dashed' },
        itemStyle: { color: '#faad14' },
        symbol: 'none',
        z: 2,
      },
      {
        name: '超额收益',
        type: 'line',
        data: excessVals,
        smooth: true,
        lineStyle: { color: '#52c41a', width: 1.5, type: 'dotted' },
        itemStyle: { color: '#52c41a' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(82,196,26,0.12)' },
              { offset: 1, color: 'rgba(82,196,26,0.0)' },
            ],
          },
          origin: 'auto',
        },
        symbol: 'none',
        z: 1,
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#ccc', type: 'solid', width: 1 },
          data: [{ yAxis: 0 }],
        },
      },
    ],
    dataZoom: [
      { type: 'inside', throttle: 50 },
      { type: 'slider', height: 20, bottom: 8 },
    ],
    animation: false,
  };

  return (
    <div>
      {/* 实时指标小卡 */}
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={8}>
          <Statistic
            title="策略累计收益"
            value={`${lastStrat >= 0 ? '+' : ''}${lastStrat.toFixed(2)}%`}
            valueStyle={{ color: lastStrat >= 0 ? '#52c41a' : '#ff4d4f', fontSize: 20 }}
          />
        </Col>
        <Col span={8}>
          <Statistic
            title="基准累计收益"
            value={`${lastBm >= 0 ? '+' : ''}${lastBm.toFixed(2)}%`}
            valueStyle={{ color: lastBm >= 0 ? '#52c41a' : '#ff4d4f', fontSize: 20 }}
          />
        </Col>
        <Col span={8}>
          <Statistic
            title="超额收益"
            value={`${lastExcess >= 0 ? '+' : ''}${lastExcess.toFixed(2)}%`}
            valueStyle={{ color: lastExcess >= 0 ? '#52c41a' : '#ff4d4f', fontSize: 20, fontWeight: 700 }}
          />
        </Col>
      </Row>
      <ReactECharts
        option={option}
        style={{ height: 480 }}
        notMerge={false}
        lazyUpdate={true}
      />
    </div>
  );
}

// ─── 主页面 ────────────────────────────────────────────────────────────────
export default function BacktestRunning() {
  const { taskId } = useParams();
  const navigate   = useNavigate();

  const [task, setTask]       = useState(null);
  const [progress, setProgress] = useState(0);
  const [stage, setStage]     = useState('PENDING');
  const [message, setMessage] = useState('');
  const [points, setPoints]   = useState([]);
  const [error, setError]     = useState(null);
  const [loading, setLoading] = useState(true);

  const clientRef   = useRef(null);
  const pointsRef   = useRef([]);

  // 加载任务信息和曲线数据（优先从API获取已有数据）
  useEffect(() => {
    const loadData = async () => {
      try {
        // 1. 获取任务基本信息
        const t = await backtestApi.getTask(taskId);
        setTask(t);
        setStage(t.status);
        setProgress(t.progress || 0);

        // 2. 获取曲线数据（如果已完成，会返回完整数据）
        const curveData = await backtestApi.getCurve(taskId);
        
        if (curveData.stratCurve?.length && curveData.bmCurve?.length) {
          // 合并策略和基准曲线数据
          const bmMap = {};
          curveData.bmCurve.forEach(d => { bmMap[d.date] = d.value; });
          
          const merged = curveData.stratCurve.map(s => ({
            date: s.date,
            stratValue: s.value,
            bmValue: bmMap[s.date] ?? 1.0,
          }));
          
          pointsRef.current = merged;
          setPoints([...merged]);
        }

        setLoading(false);
      } catch (e) {
        setError('无法加载回测任务信息');
        setLoading(false);
      }
    };
    loadData();
  }, [taskId]);

  // WebSocket 连接（只在任务未完成时连接）
  useEffect(() => {
    // 如果任务已完成/失败/取消，不需要 WebSocket
    if (stage === 'COMPLETED' || stage === 'FAILED' || stage === 'CANCELLED') {
      return;
    }

    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 3000,
      onConnect: () => {
        client.subscribe(`/topic/backtest/${taskId}`, (frame) => {
          try {
            const data = JSON.parse(frame.body);
            setStage(data.stage);
            setProgress(data.progress ?? 0);
            setMessage(data.message ?? '');

            // 有净值数据点就追加到曲线
            if (data.date && data.stratValue != null && data.bmValue != null) {
              const pt = {
                date: data.date,
                stratValue: +data.stratValue,
                bmValue: +data.bmValue,
              };
              pointsRef.current = [...pointsRef.current, pt];
              setPoints([...pointsRef.current]);
            }

            // 完成时刷新任务信息
            if (data.stage === 'COMPLETED' || data.stage === 'FAILED') {
              backtestApi.getTask(taskId).then(res => setTask(res)).catch(() => {});
              // 完成后重新加载完整曲线数据
              backtestApi.getCurve(taskId).then(res => {
                const d = res.data;
                if (d.stratCurve?.length && d.bmCurve?.length) {
                  const bmMap = {};
                  d.bmCurve.forEach(x => { bmMap[x.date] = x.value; });
                  const merged = d.stratCurve.map(s => ({
                    date: s.date,
                    stratValue: s.value,
                    bmValue: bmMap[s.date] ?? 1.0,
                  }));
                  pointsRef.current = merged;
                  setPoints([...merged]);
                }
              }).catch(() => {});
            }
          } catch (e) {
            console.warn('WS parse error', e);
          }
        });
      },
      onStompError: (f) => {
        console.warn('STOMP error', f);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => { client.deactivate(); };
  }, [taskId, stage, loading]);

  const handleViewReport = () => navigate(`/backtests/${taskId}/report`);
  const handleBack       = () => navigate('/backtests');

  const isFinished = stage === 'COMPLETED' || stage === 'FAILED' || stage === 'CANCELLED';

  const stageTag = {
    PENDING:   <Tag color="default">等待中</Tag>,
    RUNNING:   <Tag color="processing">运行中</Tag>,
    COMPLETED: <Tag color="success" icon={<CheckCircleOutlined />}>已完成</Tag>,
    FAILED:    <Tag color="error"   icon={<CloseCircleOutlined />}>失败</Tag>,
    CANCELLED: <Tag color="warning">已取消</Tag>,
  }[stage] ?? <Tag>{stage}</Tag>;

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" tip="加载回测任务...">
          <div />
        </Spin>
      </div>
    );
  }

  if (error) {
    return <Alert type="error" message={error} style={{ margin: 24 }} />;
  }

  return (
    <div style={{ width: '100%' }}>
      {/* 页头 */}
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={handleBack}>回测列表</Button>
          <Title level={4} style={{ margin: 0 }}>
            <LineChartOutlined style={{ color: '#1677ff', marginRight: 8 }} />
            {stage === 'COMPLETED' ? '回测结果' : '回测执行中'}
          </Title>
        </Space>
        {stage === 'COMPLETED' && (
          <Button type="primary" icon={<FileTextOutlined />} onClick={handleViewReport}>
            查看完整报告
          </Button>
        )}
      </div>

      {/* 任务信息卡 */}
      {task && (
        <Card size="small" style={{ marginBottom: 16 }}>
          <Row gutter={24}>
            <Col><Text type="secondary">任务ID：</Text><Text strong>{task.id}</Text></Col>
            <Col><Text type="secondary">策略：</Text><Tag color="geekblue">{task.strategyCode}</Tag></Col>
            <Col><Text type="secondary">区间：</Text><Text>{task.startDate} ~ {task.endDate}</Text></Col>
            <Col><Text type="secondary">初始资金：</Text><Text>¥{(+task.initialCapital).toLocaleString()}</Text></Col>
            <Col><Text type="secondary">状态：</Text>{stageTag}</Col>
          </Row>
        </Card>
      )}

      {/* 进度条 - 只在未完成时显示 */}
      {!isFinished && (
        <Card style={{ marginBottom: 16 }}>
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <Text strong>执行进度</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>{message || '等待回测开始...'}</Text>
            </div>
            <Progress
              percent={progress}
              status={stage === 'FAILED' ? 'exception' : 'active'}
              strokeColor={stage === 'RUNNING' ? { from: '#1677ff', to: '#36cfc9' } : undefined}
              format={p => `${p}%`}
            />
          </Space>
        </Card>
      )}

      {/* 实时/历史收益对比图 */}
      <Card
        title={
          <Space>
            <LineChartOutlined style={{ color: '#1677ff' }} />
            <span>策略收益 vs 基准收益{stage === 'RUNNING' ? '（实时）' : ''}</span>
            {stage === 'RUNNING' && (
              <Tag color="processing" style={{ marginLeft: 4 }}>实时更新中</Tag>
            )}
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        {points.length >= 2 ? (
          <LiveChart points={points} />
        ) : (
          <div style={{ textAlign: 'center', padding: '48px 0', color: '#bbb' }}>
            {stage === 'PENDING' || stage === 'RUNNING' ? (
              <Space direction="vertical" size={8}>
                <Spin />
                <Text type="secondary">等待回测数据中，图表将在回测开始后自动显示...</Text>
              </Space>
            ) : (
              <Text type="secondary">暂无数据</Text>
            )}
          </div>
        )}
      </Card>

      {/* 完成后的操作提示 */}
      {stage === 'COMPLETED' && (
        <Alert
          type="success"
          icon={<CheckCircleOutlined />}
          showIcon
          message="回测完成"
          description={
            <Space>
              <span>回测已成功完成，完整报告已生成，包含详细的净值曲线、回撤分析、月度收益等指标。</span>
              <Button type="primary" size="small" onClick={handleViewReport}>
                立即查看完整报告 →
              </Button>
            </Space>
          }
        />
      )}
      {stage === 'FAILED' && (
        <Alert
          type="error"
          showIcon
          message="回测失败"
          description={task?.errorMessage || '回测执行过程中发生错误，请检查策略配置后重试。'}
        />
      )}
    </div>
  );
}
