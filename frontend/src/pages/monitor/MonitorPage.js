import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Card, Table, Button, Tag, Space, Alert, Typography, Tooltip, Modal, Input, InputNumber, Form, Popover } from 'antd';
import { message, notification } from '../../utils/messageUtil';
import { ReloadOutlined, PlayCircleOutlined, EyeOutlined, ThunderboltOutlined, QuestionCircleOutlined, PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import api, { silentConfig } from '../../api';

const { Text } = Typography;

export default function MonitorPage() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [scanLoading, setScanLoading] = useState(false);
  const [scanResult, setScanResult] = useState(null);
  const [realtimePrices, setRealtimePrices] = useState({});
  const [realtimeChangePct, setRealtimeChangePct] = useState({});
  const [sseConnected, setSseConnected] = useState(false);
  const [sseSignals, setSseSignals] = useState([]);
  const [lastPriceUpdate, setLastPriceUpdate] = useState(null);  // 最后价格更新时间
  const [customModalOpen, setCustomModalOpen] = useState(false);
  const [customLoading, setCustomLoading] = useState(false);
  const [editingStock, setEditingStock] = useState(null); // null=新增, 有值=编辑
  const [customForm] = Form.useForm();
  const [autoNameLoading, setAutoNameLoading] = useState(false);
  const eventSourceRef = useRef(null);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.get('/monitor/status');
      setStatus(data);
      // 从status响应中初始化涨跌幅缓存
      if (data?.targetPrices) {
        const changeMap = {};
        data.targetPrices.forEach(p => {
          if (p.stockCode != null && p.changePct != null) {
            changeMap[p.stockCode] = p.changePct;
          }
        });
        setRealtimeChangePct(prev => ({ ...prev, ...changeMap }));
        // 同时初始化价格缓存
        const priceMap = {};
        data.targetPrices.forEach(p => {
          if (p.stockCode != null && p.currentPrice != null) {
            priceMap[p.stockCode] = p.currentPrice;
          }
        });
        setRealtimePrices(prev => ({ ...prev, ...priceMap }));
      }
      // 从status响应中加载信号历史（页面刷新后恢复）
      if (data?.signalHistory && Array.isArray(data.signalHistory) && data.signalHistory.length > 0) {
        setSseSignals(data.signalHistory);
      }
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

  // SSE实时信号推送 + 实时价格更新
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

          if (data.type === 'price') {
            // 实时价格更新
            setRealtimePrices(prev => ({ ...prev, ...data.prices }));
            if (data.changePct) {
              setRealtimeChangePct(prev => ({ ...prev, ...data.changePct }));
            }
            setLastPriceUpdate(data.time);
            return;
          }

          if (data.type === 'signal') {
            const isBuy = data.signalType === 'BUY';
            const isStop = data.signalType === 'STOP';

            // 更新实时价格
            if (data.currentPrice && data.stockCode) {
              setRealtimePrices(prev => ({ ...prev, [data.stockCode]: data.currentPrice }));
            }

            // 添加到信号历史
            setSseSignals(prev => [data, ...prev].slice(0, 50));

            // 弹出通知
            notification.open({
              message: isBuy ? '🟢 买入信号' : isStop ? '🔴 止损警告' : '信号通知',
              description: data.message,
              duration: isStop ? 0 : 10,
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
      // 扫描后自动拉实时价格
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

  // 手动查看实时价格
  const handleShowRealtime = async () => {
    if (!status?.targetPrices || status.targetPrices.length === 0) return;
    const codes = status.targetPrices.map(p => p.stockCode).join(',');
    try {
      const data = await api.get('/monitor/realtime', { params: { stockCodes: codes } });
      setRealtimePrices(prev => ({ ...prev, ...data }));
    } catch (err) {
      message.error('获取实时价格失败: ' + (err.response?.data?.message || err.message));
    }
  };

  // 添加或编辑自定义股票
  const handleAddCustomStock = async (values) => {
    setCustomLoading(true);
    try {
      await api.post('/monitor/add-custom-stock', {
        stockCode: values.stockCode.toUpperCase(),
        stockName: values.stockName || values.stockCode,
        buyPriceLow: values.buyPriceLow,
        buyPriceHigh: values.buyPriceHigh,
        stopLoss: values.stopLoss || null,
        targetPrice: values.targetPrice || null,
      });
      antMessage.success(editingStock ? `已更新: ${values.stockCode}` : `已添加自定义股票: ${values.stockCode}`);
      setCustomModalOpen(false);
      setEditingStock(null);
      customForm.resetFields();
      fetchStatus();
    } catch (err) {
      const msg = err.response?.data?.message || err.message;
      antMessage.error((editingStock ? '更新失败: ' : '添加失败: ') + msg);
    } finally {
      setCustomLoading(false);
    }
  };

  // 根据股票代码自动查询名称
  const handleStockCodeBlur = async (e) => {
    const code = e.target.value?.trim();
    if (!code || code.length < 4) return;
    setAutoNameLoading(true);
    try {
      const data = await api.get('/monitor/stock-name', { ...silentConfig, params: { code } });
      if (data?.stockName) {
        const currentName = customForm.getFieldValue('stockName');
        // 仅在名称为空时自动填入（不覆盖用户手动输入）
        if (!currentName) {
          customForm.setFieldsValue({ stockName: data.stockName });
        }
      }
    } catch {
      // 查询失败不影响操作
    } finally {
      setAutoNameLoading(false);
    }
  };

  // 股票代码变化时也尝试自动补全名称
  const handleStockCodeChange = (e) => {
    const code = e.target.value?.trim();
    // 输入6位纯数字时自动查询
    if (/^\d{6}$/.test(code)) {
      setAutoNameLoading(true);
      api.get('/monitor/stock-name', { ...silentConfig, params: { code } })
        .then(data => {
          if (data?.stockName) {
            customForm.setFieldsValue({ stockName: data.stockName });
          }
        })
        .catch(() => {})
        .finally(() => setAutoNameLoading(false));
    }
  };

  // 删除自定义股票
  const handleRemoveCustomStock = async (stockCode) => {
    try {
      await api.delete(`/monitor/custom-stock?stockCode=${encodeURIComponent(stockCode)}`);
      antMessage.success('已移除');
      fetchStatus();
    } catch (err) {
      antMessage.error('移除失败: ' + (err.response?.data?.message || err.message));
    }
  };

  // 编辑自定义股票
  const handleEditCustomStock = (record) => {
    setEditingStock(record);
    customForm.setFieldsValue({
      stockCode: record.stockCode,
      stockName: record.stockName,
      buyPriceLow: record.buyPriceLow != null ? Number(record.buyPriceLow) : undefined,
      buyPriceHigh: record.buyPriceHigh != null ? Number(record.buyPriceHigh) : undefined,
      stopLoss: record.stopLoss != null ? Number(record.stopLoss) : undefined,
      targetPrice: record.targetPrice != null ? Number(record.targetPrice) : undefined,
    });
    setCustomModalOpen(true);
  };

  /** 从后端status/SSE/扫描结果中查找某只股票的信号状态 */
  const getSignalInfo = (record) => {
    // 1. 优先用后端status返回的快速信号状态（基于实时价格计算，永远有值）
    if (record.signalStatus) {
      return { signalType: record.signalStatus, message: record.signalMessage || '' };
    }
    // 2. SSE信号（买入/止损推送）
    const sseSignal = sseSignals.find(s => s.stockCode === record.stockCode);
    if (sseSignal) {
      return { signalType: sseSignal.signalType, message: sseSignal.message, score: sseSignal.score };
    }
    // 3. 手动扫描结果
    if (!scanResult) return null;
    const all = [...(scanResult.signals || []), ...(scanResult.watches || []), ...(scanResult.skipped || [])];
    const found = all.find(s => s.stockCode === record.stockCode);
    return found ? { signalType: found.signalType, message: found.message, score: found.score } : null;
  };

  // 价格涨跌色（中国惯例：红涨绿跌）
  const getPriceColor = (current, buyLow) => {
    if (!current || !buyLow) return undefined;
    return current >= buyLow ? '#cf1322' : '#389e0d';  // 红=高于区间下沿, 绿=低于
  };

  const stockColumns = [
    {
      title: '代码', dataIndex: 'stockCode', key: 'stockCode', width: 105,
      render: (v, r) => r.source === '客户定义'
        ? (
          <Popover
            trigger="hover"
            placement="rightTop"
            content={
              <Space>
                <Button type="link" size="small" icon={<EditOutlined />} onClick={(e) => { e.stopPropagation(); handleEditCustomStock(r); }} style={{ padding: 0, fontSize: 12, color: '#1890ff' }}>编辑</Button>
                <Button type="link" danger size="small" icon={<DeleteOutlined />} onClick={(e) => { e.stopPropagation(); handleRemoveCustomStock(r.stockCode); }} style={{ padding: 0, fontSize: 12 }}>删除</Button>
              </Space>
            }
          >
            <span style={{ cursor: 'pointer' }}>{v}</span>
          </Popover>
        )
        : v,
    },
    {
      title: '名称', dataIndex: 'stockName', key: 'stockName', width: 90,
      render: (v, r) => r.source === '客户定义'
        ? (
          <Popover
            trigger="hover"
            placement="rightTop"
            content={
              <Space>
                <Button type="link" size="small" icon={<EditOutlined />} onClick={(e) => { e.stopPropagation(); handleEditCustomStock(r); }} style={{ padding: 0, fontSize: 12, color: '#1890ff' }}>编辑</Button>
                <Button type="link" danger size="small" icon={<DeleteOutlined />} onClick={(e) => { e.stopPropagation(); handleRemoveCustomStock(r.stockCode); }} style={{ padding: 0, fontSize: 12 }}>删除</Button>
              </Space>
            }
          >
            <span style={{ cursor: 'pointer' }}>{v}</span>
          </Popover>
        )
        : v,
    },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 75,
      align: 'center',
      render: (v, r) => {
        const isCustom = v === '客户定义';
        if (isCustom) return <Tag color="gold" size="small">客户定义</Tag>;
        if (v === 'LLM') return <Tag color="purple" size="small">LLM</Tag>;
        return <Tag color="blue" size="small">推荐</Tag>;
      },
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
      width: 100,
      render: (_, r) => {
        const price = realtimePrices[r.stockCode] || r.currentPrice;
        if (!price) return <Text type="secondary">-</Text>;
        const color = getPriceColor(price, r.buyPriceLow);
        return <Text strong style={{ color }}>{Number(price).toFixed(2)}</Text>;
      },
    },
    {
      title: '涨跌幅',
      key: 'changePct',
      width: 80,
      align: 'left',
      render: (_, r) => {
        const pct = realtimeChangePct[r.stockCode] ?? r.changePct;
        if (pct == null) return <Text type="secondary">-</Text>;
        const val = Number(pct);
        const color = val > 0 ? '#cf1322' : val < 0 ? '#3f8600' : '#8c8c8c';
        const sign = val > 0 ? '+' : '';
        return <Text strong style={{ color, fontSize: 13 }}>{sign}{val.toFixed(2)}%</Text>;
      },
    },
    {
      title: '信号状态',
      key: 'signalStatus',
      width: 140,
      render: (_, r) => {
        const sig = getSignalInfo(r);
        if (!sig) return <Tag color="#999" size="small">未扫描</Tag>;
        const t = sig.signalType;
        if (t === 'BUY' || t === 'STRONG_BUY' || t === 'BUY_FALLBACK') return <Tooltip title={sig.message}><Tag color="red" size="small">买入信号</Tag></Tooltip>;
        if (t === 'STOP') return <Tooltip title={sig.message}><Tag color="orange" size="small">止损警告</Tag></Tooltip>;
        if (t === 'IN_RANGE') return <Tooltip title={sig.message}><Tag color="red" size="small">区间内</Tag></Tooltip>;
        if (t === 'WATCH') return <Tooltip title={sig.message}><Tag color="blue" size="small">观察中</Tag></Tooltip>;
        if (t === 'BELOW') return <Tooltip title={sig.message}><Tag color="green" size="small">区间下方</Tag></Tooltip>;
        if (t === 'ABOVE') return <Tooltip title={sig.message}><Tag color="default" size="small">区间上方</Tag></Tooltip>;
        if (t === 'NO_PRICE') return <Tag color="#999" size="small">无价格</Tag>;
        if (t === 'NO_RANGE') return <Tag color="#999" size="small">无区间</Tag>;
        return (
          <Tooltip title={sig.message}>
            <Tag color="default" size="small">{t}</Tag>
          </Tooltip>
        );
      },
    },
  ];

  const targetPrices = status?.targetPrices || [];
  const signalCount = scanResult?.signalCount ?? 0;

  return (
    <div style={{ padding: '12px 8px' }}>
      {/* 页头 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Space>
          <ThunderboltOutlined style={{ fontSize: 22 }} />
          <span style={{ fontSize: 22, fontWeight: 600 }}>盘中监控</span>
          <Tooltip
            styles={{ root: {width: 520, maxWidth: 520} }}
            title={
              <div style={{ padding: '4px 0', lineHeight: '1.8', fontSize: 13 }}>
                <div style={{ marginBottom: 6, fontWeight: 600 }}>信号含义</div>
                <div style={{ marginBottom: 4 }}><Tag color="red" size="small">区间内</Tag> 实时价在买入区间内，可关注入场时机</div>
                <div style={{ marginBottom: 4 }}><Tag color="red" size="small">买入信号</Tag> 进入买入区间 + 4维评分通过 → 推送买入通知</div>
                <div style={{ marginBottom: 4 }}><Tag color="orange" size="small">止损警告</Tag> 跌破止损价 → 立即推送止损警告</div>
                <div style={{ marginBottom: 4 }}><Tag color="blue" size="small">观察中</Tag> 接近触发区间(±2%)，但还未完全满足条件</div>
                <div style={{ marginBottom: 4 }}><Tag color="green" size="small">区间下方</Tag> 低于买入区间下沿，等待回调到位（悬停看距离）</div>
                <div style={{ marginBottom: 4 }}><Tag color="default" size="small">区间上方</Tag> 高于买入区间上沿，已错过最佳入场点，追高风险大（悬停看距离）</div>

                <div style={{ marginTop: 8, borderTop: '1px solid #e8e8e8', paddingTop: 6, fontWeight: 600 }}>分钟K线作用</div>
                <div>价格进入买入区间(±2%)时，自动拉取m5分钟K线做4维评分：</div>
                <div style={{ paddingLeft: 12, color: '#595959', fontSize: 12 }}>
                  突破确认(35) + 均线排列(20) + 量价配合(25) + 回踩确认(20)
                </div>
                <div style={{ marginBottom: 6 }}>总分≥80才触发买入信号。非交易时段降级为纯价格判断。</div>

                <div style={{ marginTop: 8, borderTop: '1px solid #e8e8e8', paddingTop: 6, fontWeight: 600 }}>实时推送机制</div>
                <div>后端每10秒轮询行情，价格变动通过SSE实时推送到页面，无需手动刷新。</div>
                <div style={{ marginBottom: 6 }}>K线拉取已改为并行（线程池），多只股同时分析不叠加延迟。</div>

                <div style={{ marginTop: 8, borderTop: '1px solid #e8e8e8', paddingTop: 6, fontWeight: 600 }}>与分钟K线的关系</div>
                <div><Tag color="red" size="small">买入信号</Tag> 有关 — 需m5 K线4维评分≥80</div>
                <div><Tag color="orange" size="small">止损警告</Tag> 无关 — 直接比较实时价与止损价</div>
                <div><Tag color="blue" size="small">观察中</Tag> 有关 — 进入区间后即拉K线评分</div>
                <div><Tag color="green" size="small">区间下方</Tag> 无关 — 纯价格判断</div>
                <div><Tag color="default" size="small">区间上方</Tag> 无关 — 纯价格判断</div>
              </div>
            }>
            <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 16, cursor: 'pointer' }} />
          </Tooltip>
          <Tooltip title={status?.monitoring ? '盘中监控运行中' : '交易时段(9:30-15:00)内自动启动，当前处于非交易时段或服务刚重启'}>
            <Tag color={status?.monitoring ? 'success' : 'default'}>{status?.monitoring ? '运行中' : '未启动'}</Tag>
          </Tooltip>
          <Tooltip title={sseConnected ? 'SSE实时推送已连接' : 'SSE未连接，5秒后自动重连'}>
            <Tag color={sseConnected ? 'cyan' : 'default'}>{sseConnected ? '推送已连接' : '推送未连接'}</Tag>
          </Tooltip>
          {lastPriceUpdate && sseConnected && (
            <Text type="secondary" style={{ fontSize: 11 }}>
              行情更新: {new Date(lastPriceUpdate).toLocaleTimeString()}
            </Text>
          )}
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchStatus} loading={loading}>刷新状态</Button>
          <Button icon={<ReloadOutlined />} onClick={handleRefreshTargets}>刷新目标价</Button>
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleTriggerScan} loading={scanLoading}>
            手动触发扫描
          </Button>
          <Button icon={<EyeOutlined />} onClick={handleShowRealtime}>手动查价</Button>
          <Button type="dashed" icon={<PlusOutlined />} onClick={() => { setEditingStock(null); setCustomModalOpen(true); }}>
            添加自定义
          </Button>
        </Space>
      </div>

      {/* 非交易日提示 */}
      {status?.dataDate && (() => {
        const d = new Date(status.dataDate);
        const today = new Date();
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
              pagination={false} scroll={{ x: 1000 }}
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
            {sseSignals.map((sig, i) => {
              const isBuy = sig.signalType === 'BUY' || sig.signalType === 'STRONG_BUY';
              const isStop = sig.signalType === 'STOP';
              const isWatch = sig.signalType === 'WATCH';
              return (
                <div key={i} style={{
                  padding: '4px 8px',
                  background: isBuy ? '#fff1f0' : isStop ? '#fff7e6' : isWatch ? '#e6f7ff' : (i % 2 === 0 ? '#fafafa' : 'transparent'),
                  borderRadius: 4,
                  marginBottom: 2,
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}>
                  <Space>
                    {isBuy
                      ? <Tag color="red" size="small">买入信号</Tag>
                      : isStop
                      ? <Tag color="orange" size="small">止损警告</Tag>
                      : isWatch
                      ? <Tag color="blue" size="small">观察中</Tag>
                      : <Tag size="small">{sig.signalType}</Tag>}
                    <Text strong>{sig.stockName}</Text>
                    <Text type="secondary">{sig.stockCode}</Text>
                    {sig.currentPrice && <Text>¥{Number(sig.currentPrice).toFixed(2)}</Text>}
                    {sig.score != null && (
                      <Text type={sig.score >= 80 ? 'danger' : 'secondary'} style={{ fontSize: 12 }}>
                        评分{sig.score}{sig.score < 80 && isWatch ? '（待达标）' : ''}
                      </Text>
                    )}
                  </Space>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {sig.time ? new Date(sig.time).toLocaleTimeString() : ''}
                  </Text>
              </div>
              );
            })}
          </div>
        </Card>
      )}

      {/* 添加/编辑自定义股票弹窗 */}
      <Modal
        title={editingStock ? '编辑自定义监控股票' : '添加自定义监控股票'}
        open={customModalOpen}
        onCancel={() => { setCustomModalOpen(false); setEditingStock(null); customForm.resetFields(); }}
        footer={null}
        destroyOnHidden
        width={480}
      >
        <Form
          form={customForm}
          layout="vertical"
          onFinish={handleAddCustomStock}
          initialValues={{ buyPriceLow: undefined, buyPriceHigh: undefined }}
        >
          <Space style={{ width: '100%' }} wrap>
            <Form.Item name="stockCode" label="股票代码" rules={[{ required: true, message: '请输入股票代码' }]} style={{ width: 150 }}>
              <Input placeholder="如 600519" onBlur={handleStockCodeBlur} onChange={handleStockCodeChange} disabled={!!editingStock} />
            </Form.Item>
            <Form.Item name="stockName" label="股票名称" style={{ flex: 1 }} validateStatus={autoNameLoading ? 'validating' : undefined}>
              <Input placeholder="输入代码自动填入，可修改" />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} wrap>
            <Form.Item name="buyPriceLow" label="买入区间下沿" rules={[{ required: true, message: '必填' }]}>
              <InputNumber placeholder="下沿价" min={0} step={0.01} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="buyPriceHigh" label="买入区间上沿" rules={[{ required: true, message: '必填' }]}>
              <InputNumber placeholder="上沿价" min={0} step={0.01} style={{ width: 130 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} wrap>
            <Form.Item name="stopLoss" label="止损价">
              <InputNumber placeholder="可选" min={0} step={0.01} style={{ width: 130 }} />
            </Form.Item>
            <Form.Item name="targetPrice" label="目标价">
              <InputNumber placeholder="可选" min={0} step={0.01} style={{ width: 130 }} />
            </Form.Item>
          </Space>
          <div style={{ textAlign: 'right', marginTop: 8 }}>
            <Space>
              <Button onClick={() => { setCustomModalOpen(false); setEditingStock(null); }}>取消</Button>
              <Button type="primary" htmlType="submit" loading={customLoading} icon={editingStock ? <EditOutlined /> : <PlusOutlined />}>
                {editingStock ? '保存修改' : '添加到监控'}
              </Button>
            </Space>
          </div>
        </Form>
      </Modal>
    </div>
  );
}
