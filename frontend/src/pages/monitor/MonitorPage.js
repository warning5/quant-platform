import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Card, Table, Button, Tag, Space, Alert, Typography, Tooltip } from 'antd';
import { message, notification } from '../../utils/messageUtil';
import { ReloadOutlined, PlayCircleOutlined, EyeOutlined, ThunderboltOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import api from '../../api';

const { Text } = Typography;

export default function MonitorPage() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [scanLoading, setScanLoading] = useState(false);
  const [scanResult, setScanResult] = useState(null);
  const [realtimePrices, setRealtimePrices] = useState(null);
  const [sseConnected, setSseConnected] = useState(false);
  const [sseSignals, setSseSignals] = useState([]);  // SSE推送的信号历史
  const eventSourceRef = useRef(null);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.get('/monitor/status');
      setStatus(data);
    } catch (err) {
      const msg = err.response?.data?.message || err.message || '未知错误';
      message.error('获取监控状态失败: ' + msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  // SSE实时信号推送
  useEffect(() => {
    const connectSSE = () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
      const es = new EventSource('/api/monitor/stream');
      eventSourceRef.current = es;

      es.onopen = () => {
        setSseConnected(true);
      };

      es.addEventListener('monitor', (e) => {
        try {
          const data = JSON.parse(e.data);
          if (data.type === 'signal') {
            // 新信号到达
            const isBuy = data.signalType === 'BUY';
            const isStop = data.signalType === 'STOP';

            // 添加到信号历史
            setSseSignals(prev => [data, ...prev].slice(0, 50));

            // 弹出通知
            notification.open({
              message: isBuy ? '🟢 买入信号' : isStop ? '🔴 止损警告' : '信号通知',
              description: data.message,
              duration: isStop ? 0 : 10,  // 止损警告不自动关闭
              style: {
                borderColor: isBuy ? '#52c41a' : isStop ? '#ff4d4f' : '#1890ff',
                borderWidth: 2,
              },
            });

            // 同步刷新状态
            fetchStatus();
          }
        } catch (err) {
          // 解析失败忽略
        }
      });

      es.onerror = () => {
        setSseConnected(false);
        es.close();
        // 5秒后重连
        setTimeout(connectSSE, 5000);
      };
    };

    connectSSE();
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
    };
  }, []);

  // 手动触发扫描
  const handleTriggerScan = async () => {
    setScanLoading(true);
    setScanResult(null);
    try {
      const data = await api.post('/monitor/trigger-scan');
      setScanResult(data);
      // 自动刷新实时价格
      if (status?.targetPrices?.length > 0) {
        handleShowRealtime();
      }
      message.success('扫描完成');
      fetchStatus();
    } catch (err) {
      const msg = err.response?.data?.message || err.message || '请求失败';
      message.error('触发扫描失败: ' + msg);
    } finally {
      setScanLoading(false);
    }
  };

  // 刷新目标价
  const handleRefreshTargets = async () => {
    try {
      await api.post('/monitor/refresh-targets');
      message.success('目标价已刷新');
      fetchStatus();
    } catch (err) {
      message.error('刷新失败: ' + (err.response?.data?.message || err.message));
    }
  };

  // 查看实时价格
  const handleShowRealtime = async () => {
    if (!status?.targetPrices || status.targetPrices.length === 0) return;
    const codes = status.targetPrices.map(p => p.stockCode).join(',');
    try {
      const data = await api.get('/monitor/realtime', { params: { stockCodes: codes } });
      setRealtimePrices(data);
    } catch (err) {
      message.error('获取实时价格失败: ' + (err.response?.data?.message || err.message));
    }
  };

  /** 从扫描结果或SSE信号中查找某只股票的信号状态 */
  const getSignalInfo = (code) => {
    // 优先用SSE推送的实时信号
    const sseSignal = sseSignals.find(s => s.stockCode === code);
    if (sseSignal) {
      return { signalType: sseSignal.signalType, message: sseSignal.message, score: sseSignal.score };
    }
    // 其次用手动扫描结果
    if (!scanResult) return null;
    const all = [...(scanResult.signals || []), ...(scanResult.watches || []), ...(scanResult.skipped || [])];
    return all.find(s => s.stockCode === code) || null;
  };

  // 合并表格：监控股票 + 扫描状态
  const stockColumns = [
    { title: '代码', dataIndex: 'stockCode', key: 'stockCode', width: 105 },
    { title: '名称', dataIndex: 'stockName', key: 'stockName', width: 90 },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 65,
      align: 'center',
      render: v => v === 'LLM' ? <Tag color="purple" size="small">LLM</Tag> : <Tag color="blue" size="small">推荐</Tag>,
    },
    {
      title: '买入区间',
      key: 'buyRange',
      width: 145,
      render: (_, r) => (r.buyPriceLow && r.buyPriceHigh)
        ? <Text>{Number(r.buyPriceLow).toFixed(2)} ~ {Number(r.buyPriceHigh).toFixed(2)}</Text>
        : <Text type="secondary">-</Text>,
    },
    {
      title: '止损价',
      dataIndex: 'stopLoss',
      key: 'stopLoss',
      width: 85,
      render: v => v ? <Text type="danger">{Number(v).toFixed(2)}</Text> : '-',
    },
    {
      title: '目标价',
      dataIndex: 'targetPrice',
      key: 'targetPrice',
      width: 85,
      render: v => v ? <Text type="success">{Number(v).toFixed(2)}</Text> : '-',
    },
    {
      title: '实时价',
      key: 'realtimePrice',
      width: 95,
      render: (_, r) => realtimePrices && realtimePrices[r.stockCode]
        ? <Text strong>{Number(realtimePrices[r.stockCode]).toFixed(2)}</Text>
        : <Text type="secondary">-</Text>,
    },
    {
      title: '信号状态',
      key: 'signalStatus',
      width: 120,
      render: (_, r) => {
        const sig = getSignalInfo(r.stockCode);
        if (!sig) return <Tag color="#999" size="small">未扫描</Tag>;
        if (sig.signalType === 'BUY') return <Tag color="red" size="small">买入信号</Tag>;
        if (sig.signalType === 'STOP') return <Tag color="orange" size="small">止损警告</Tag>;
        if (sig.signalType === 'WATCH') return <Tag color="blue" size="small">观察中</Tag>;
        return (
          <Tooltip title={sig.message}>
            <Tag color="default" size="small">未触发</Tag>
          </Tooltip>
        );
      },
    },
  ];

  const targetPrices = status?.targetPrices || [];
  const signalCount = scanResult?.signalCount ?? 0;

  return (
    <div style={{ padding: '12px 8px' }}>
      {/* 页头：标题 + 操作按钮 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Space>
          <ThunderboltOutlined style={{ fontSize: 22 }} />
          <span style={{ fontSize: 22, fontWeight: 600 }}>盘中监控</span>
          <Tooltip
            styles={{ root: {width: 420, maxWidth: 420} }}
            title={
              <div style={{ padding: '4px 0', lineHeight: '1.8', fontSize: 13 }}>
                <div style={{ marginBottom: 6, fontWeight: 600 }}>信号含义</div>
                <div style={{ marginBottom: 4 }}><Tag color="red" size="small">买入信号</Tag> 进入买入区间 + 4维评分通过 → 推送买入通知</div>
                <div style={{ marginBottom: 4 }}><Tag color="orange" size="small">止损警告</Tag> 跌破止损价 → 立即推送止损警告</div>
                <div style={{ marginBottom: 4 }}><Tag color="blue" size="small">观察中</Tag> 接近触发区间，但还未完全满足条件</div>
                <div style={{ marginBottom: 8 }}><Tag size="small">未触发</Tag> 远离买入区间（悬停看详情）</div>

                <div style={{ marginTop: 8, borderTop: '1px solid #e8e8e8', paddingTop: 6, fontWeight: 600 }}>分钟K线作用</div>
                <div>价格进入买入区间(±2%)时，自动拉取m5分钟K线做4维评分：</div>
                <div style={{ paddingLeft: 12, color: '#595959', fontSize: 12 }}>
                  突破确认(35) + 均线排列(20) + 量价配合(25) + 回踩确认(20)
                </div>
                <div style={{ marginBottom: 6 }}>总分≥80才触发买入信号。非交易时段降级为纯价格判断。</div>

                <div style={{ marginTop: 8, borderTop: '1px solid #e8e8e8', paddingTop: 6, fontWeight: 600 }}>与分钟K线的关系</div>
                <div><Tag color="red" size="small">买入信号</Tag> 有关 — 需m5 K线4维评分≥80</div>
                <div><Tag color="orange" size="small">止损警告</Tag> 无关 — 直接比较实时价与止损价</div>
                <div><Tag color="blue" size="small">观察中</Tag> 有关 — 进入区间后即拉K线评分</div>
                <div><Tag size="small">未触发</Tag> 无关 — 纯价格判断</div>
              </div>
            }>
            <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 16, cursor: 'pointer' }} />
          </Tooltip>
          <Tag color={status?.monitoring ? 'success' : 'default'}>{status?.monitoring ? '运行中' : '未启动'}</Tag>
          <Tooltip title={sseConnected ? 'SSE实时推送已连接' : 'SSE未连接，5秒后自动重连'}>
            <Tag color={sseConnected ? 'cyan' : 'default'}>{sseConnected ? '推送已连接' : '推送未连接'}</Tag>
          </Tooltip>
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchStatus} loading={loading}>刷新状态</Button>
          <Button icon={<ReloadOutlined />} onClick={handleRefreshTargets}>刷新目标价</Button>
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleTriggerScan} loading={scanLoading}>
            手动触发扫描
          </Button>
          <Button icon={<EyeOutlined />} onClick={handleShowRealtime}>查看实时价格</Button>
        </Space>
      </div>

      {/* 非交易日提示 */}
      {status?.dataDate && (() => {
        const d = new Date(status.dataDate);
        const today = new Date();
        const isWeekend = d.getDay() === 0 || d.getDay() === 6;
        // 如果数据日期不是今天，说明是非交易日回退
        return d.toDateString() !== today.toDateString()
          ? <Alert type="info" showIcon banner style={{ marginBottom: 10, padding: '6px 12px', fontSize: 13 }}
              message={`当前监控基于 ${d.toLocaleDateString('zh-CN')} 的数据，非交易日自动取最近一个交易日`} />
          : null;
      })()}

      {/* 主表格 */}
      <Card styles={{ body: {padding: 0} }}>
        {targetPrices.length === 0
          ? <Alert message="暂无监控股票，请先通过「智能推荐」生成BUY推荐" type="info" showIcon style={{ margin: 16 }} />
          : <Table columns={stockColumns} dataSource={targetPrices} rowKey="stockCode" size="small"
              pagination={false} scroll={{ x: 900 }}
              summary={() => scanResult && (
                <tr style={{ background: '#fafafa' }}>
                  <td colSpan={6} style={{ textAlign: 'right', paddingRight: 16, fontWeight: 500 }}>
                    扫描结果：
                  </td>
                  <td colSpan={2}>
                    {signalCount > 0
                      ? <Tag color="red">{signalCount} 只触发信号</Tag>
                      : null}
                    {(scanResult.watchCount ?? 0) > 0
                      ? <Tag color="blue">{scanResult.watchCount} 只观察中</Tag>
                      : null}
                    <Tag>{scanResult.skippedCount ?? 0} 只未触发</Tag>
                    &nbsp;&nbsp;
                    <Text type="secondary">{scanResult.message}</Text>
                  </td>
                </tr>
              )}
            />
        }
      </Card>

      {/* SSE实时信号历史 */}
      {sseSignals.length > 0 && (
        <Card title={`实时信号 (${sseSignals.length})`} size="small" style={{ marginTop: 12 }}>
          <div style={{ maxHeight: 200, overflowY: 'auto' }}>
            {sseSignals.map((sig, i) => (
              <div key={i} style={{
                padding: '4px 8px',
                background: i % 2 === 0 ? '#fafafa' : 'transparent',
                borderRadius: 4,
                marginBottom: 2,
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}>
                <Space>
                  {sig.signalType === 'BUY'
                    ? <Tag color="red" size="small">买入信号</Tag>
                    : sig.signalType === 'STOP'
                    ? <Tag color="orange" size="small">止损警告</Tag>
                    : <Tag size="small">{sig.signalType}</Tag>}
                  <Text strong>{sig.stockName}</Text>
                  <Text type="secondary">{sig.stockCode}</Text>
                  {sig.currentPrice && <Text>¥{Number(sig.currentPrice).toFixed(2)}</Text>}
                  {sig.score != null && <Text type="secondary">评分{sig.score}</Text>}
                </Space>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {sig.time ? new Date(sig.time).toLocaleTimeString() : ''}
                </Text>
              </div>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}
