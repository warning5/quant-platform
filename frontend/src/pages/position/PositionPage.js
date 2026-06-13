import React, { useState, useEffect } from 'react';
import { Card, Table, Button, Tag, Space, Modal, Form, InputNumber, Input, message, Row, Col, Statistic, Descriptions } from 'antd';
import {
  WalletOutlined, PlusOutlined, RiseOutlined, FallOutlined,
  SyncOutlined, FileTextOutlined
} from '@ant-design/icons';
import axios from 'axios';

const PositionPage = () => {
  const [positions, setPositions] = useState([]);
  const [closedPositions, setClosedPositions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [tab, setTab] = useState('OPEN');
  const [openModal, setOpenModal] = useState(false);
  const [closeModal, setCloseModal] = useState(false);
  const [selectedPos, setSelectedPos] = useState(null);
  const [report, setReport] = useState(null);
  const [form] = Form.useForm();
  const [closeForm] = Form.useForm();

  useEffect(() => {
    fetchPositions();
  }, []);

  const fetchPositions = async () => {
    setLoading(true);
    try {
      const [openRes, closedRes] = await Promise.all([
        axios.get('/api/positions?status=OPEN'),
        axios.get('/api/positions?status=CLOSED')
      ]);
      setPositions(openRes.data?.data || []);
      setClosedPositions(closedRes.data?.data || []);
    } catch (e) {
      message.error('获取持仓数据失败');
    } finally {
      setLoading(false);
    }
  };

  const handleOpenPosition = async (values) => {
    try {
      await axios.post('/api/positions', values);
      message.success('建仓成功');
      setOpenModal(false);
      form.resetFields();
      fetchPositions();
    } catch (e) {
      message.error('建仓失败');
    }
  };

  const handleClosePosition = async (values) => {
    try {
      await axios.post(`/api/positions/${selectedPos.id}/close`, values);
      message.success('平仓成功');
      setCloseModal(false);
      closeForm.resetFields();
      setSelectedPos(null);
      fetchPositions();
    } catch (e) {
      message.error('平仓失败');
    }
  };

  const updatePrices = async () => {
    try {
      await axios.post('/api/positions/update-prices');
      message.success('价格已更新');
      fetchPositions();
    } catch (e) {
      message.error('更新失败');
    }
  };

  const fetchReport = async () => {
    try {
      const res = await axios.get('/api/positions/daily-report');
      setReport(res.data?.data);
    } catch (e) {
      message.error('获取报告失败');
    }
  };

  const totalMV = positions.reduce((s, p) => s + (p.marketValue || 0), 0);
  const totalCost = positions.reduce((s, p) => s + (p.costValue || 0), 0);
  const totalPL = positions.reduce((s, p) => s + (p.profitLoss || 0), 0);
  const totalPLPct = totalCost > 0 ? ((totalPL / totalCost) * 100).toFixed(2) : '0.00';

  const columns = [
    { title: '代码', dataIndex: 'stockCode', key: 'code', width: 100 },
    { title: '名称', dataIndex: 'stockName', key: 'name', width: 80 },
    { title: '买入价', dataIndex: 'buyPrice', key: 'buy', width: 80, render: v => `¥${v}` },
    { title: '数量', dataIndex: 'quantity', key: 'qty', width: 60 },
    { title: '现价', dataIndex: 'currentPrice', key: 'cur', width: 80, render: v => v ? `¥${v}` : '-' },
    {
      title: '盈亏', key: 'pl', width: 100,
      render: (_, r) => {
        const pl = r.profitLoss || 0;
        const pct = r.profitLossPct || 0;
        const color = pl >= 0 ? '#cf1322' : '#3f8600';
        return <span style={{ color, fontWeight: 600 }}>{pl >= 0 ? '+' : ''}{pl.toFixed(2)} ({pct.toFixed(2)}%)</span>;
      }
    },
    { title: '止损价', dataIndex: 'stopLossPrice', key: 'sl', width: 80, render: v => v ? `¥${v}` : '-' },
    { title: '止盈价', dataIndex: 'takeProfitPrice', key: 'tp', width: 80, render: v => v ? `¥${v}` : '-' },
    { title: '买入日', dataIndex: 'buyDate', key: 'bd', width: 100 },
    {
      title: '操作', key: 'action', width: 80,
      render: (_, r) => r.status === 'OPEN' ? (
        <Button size="small" danger onClick={() => { setSelectedPos(r); setCloseModal(true); }}>平仓</Button>
      ) : null
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card>
        <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <h3 style={{ margin: 0 }}><WalletOutlined /> 持仓管理</h3>
          </Col>
          <Col>
            <Space>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpenModal(true)}>建仓</Button>
              <Button icon={<SyncOutlined />} onClick={updatePrices}>更新价格</Button>
              <Button icon={<FileTextOutlined />} onClick={fetchReport}>每日报告</Button>
            </Space>
          </Col>
        </Row>

        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}><Card size="small"><Statistic title="持仓数" value={positions.length} suffix="只" /></Card></Col>
          <Col span={6}><Card size="small"><Statistic title="总市值" value={totalMV} precision={2} prefix="¥" /></Card></Col>
          <Col span={6}>
            <Card size="small">
              <Statistic
                title="总盈亏"
                value={totalPL}
                precision={2}
                prefix={totalPL >= 0 ? '+¥' : '-¥'}
                valueStyle={{ color: totalPL >= 0 ? '#cf1322' : '#3f8600' }}
              />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small">
              <Statistic title="盈亏率" value={totalPLPct} suffix="%" valueStyle={{ color: totalPL >= 0 ? '#cf1322' : '#3f8600' }} />
            </Card>
          </Col>
        </Row>

        <Space style={{ marginBottom: 12 }}>
          <Button type={tab === 'OPEN' ? 'primary' : 'default'} onClick={() => setTab('OPEN')}>
            持仓中 ({positions.length})
          </Button>
          <Button type={tab === 'CLOSED' ? 'primary' : 'default'} onClick={() => setTab('CLOSED')}>
            已平仓 ({closedPositions.length})
          </Button>
        </Space>

        <Table
          dataSource={tab === 'OPEN' ? positions : closedPositions}
          columns={columns}
          rowKey="id"
          loading={loading}
          size="small"
          pagination={{ pageSize: 20 }}
        />
      </Card>

      {/* 建仓弹窗 */}
      <Modal title="建仓" open={openModal} onCancel={() => setOpenModal(false)} onOk={() => form.submit()}>
        <Form form={form} layout="vertical" onFinish={handleOpenPosition}>
          <Form.Item name="stockCode" label="股票代码" rules={[{ required: true }]}>
            <Input placeholder="如 sh.600519 或 601077.SH" />
          </Form.Item>
          <Form.Item name="stockName" label="股票名称">
            <Input placeholder="如 贵州茅台" />
          </Form.Item>
          <Row gutter={8}>
            <Col span={12}>
              <Form.Item name="buyPrice" label="买入价" rules={[{ required: true }]}>
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="quantity" label="数量(股)" rules={[{ required: true }]}>
                <InputNumber min={1} step={100} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={8}>
            <Col span={12}>
              <Form.Item name="stopLossPrice" label="止损价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="takeProfitPrice" label="止盈价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="notes" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 平仓弹窗 */}
      <Modal title="平仓" open={closeModal} onCancel={() => setCloseModal(false)} onOk={() => closeForm.submit()}>
        {selectedPos && (
          <div style={{ marginBottom: 12 }}>
            <p><strong>{selectedPos.stockName}</strong> ({selectedPos.stockCode})</p>
            <p>买入价: ¥{selectedPos.buyPrice} | 数量: {selectedPos.quantity}</p>
          </div>
        )}
        <Form form={closeForm} layout="vertical" onFinish={handleClosePosition}>
          <Form.Item name="sellPrice" label="卖出价格" rules={[{ required: true }]}>
            <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 每日报告弹窗 */}
      <Modal title="每日持仓报告" open={!!report} onCancel={() => setReport(null)} footer={null} width={600}>
        {report && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="日期">{report.date}</Descriptions.Item>
            <Descriptions.Item label="持仓数">{report.openCount} 只</Descriptions.Item>
            <Descriptions.Item label="总市值">¥{(report.totalMarketValue || 0).toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="总成本">¥{(report.totalCostValue || 0).toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="总盈亏">
              <span style={{ color: (report.totalProfitLoss || 0) >= 0 ? '#cf1322' : '#3f8600' }}>
                ¥{(report.totalProfitLoss || 0).toFixed(2)} ({(report.totalProfitLossPct || 0).toFixed(2)}%)
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="今日平仓">{report.closedTodayCount} 只</Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};

export default PositionPage;
