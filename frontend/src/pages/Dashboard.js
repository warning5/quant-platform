import React, { useEffect, useState } from 'react';
import { Row, Col, Card, Statistic, Table, Tag, Typography, Space, Spin } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined, FundOutlined, ThunderboltOutlined, LineChartOutlined } from '@ant-design/icons';
import { factorApi, strategyApi, backtestApi } from '../api';

const { Title, Text } = Typography;

export default function Dashboard() {
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState({ factors: 0, strategies: 0, backtests: 0 });
  const [recentFactors, setRecentFactors] = useState([]);
  const [recentBacktests, setRecentBacktests] = useState([]);

  useEffect(() => {
    Promise.all([
      factorApi.list({ page: 0, size: 5 }),
      strategyApi.list({ page: 0, size: 5 }),
      backtestApi.list({ page: 0, size: 5 }),
    ]).then(([f, s, b]) => {
      setStats({
        factors: f.total,
        strategies: s.total,
        backtests: b.total,
      });
      setRecentFactors(f.records || []);
      setRecentBacktests(b.records || []);
    }).finally(() => setLoading(false));
  }, []);

  const statusColors = {
    DRAFT: 'default', TESTING: 'processing', ACTIVE: 'success', DEPRECATED: 'default',
    PENDING: 'default', RUNNING: 'processing', COMPLETED: 'success', FAILED: 'error',
  };

  const factorColumns = [
    { title: '因子代码', dataIndex: 'factorCode', key: 'code', width: 130, render: v => <Text code>{v}</Text> },
    { title: '因子名称', dataIndex: 'factorName', key: 'name' },
    { title: '分类', dataIndex: 'category', key: 'cat', width: 100 },
    { title: '状态', dataIndex: 'status', key: 'st', width: 80, render: v => <Tag color={statusColors[v]}>{v}</Tag> },
  ];

  const backtestColumns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '策略代码', dataIndex: 'strategyCode', key: 'code', render: v => <Text code>{v}</Text> },
    { title: '回测区间', key: 'range', render: (_, r) => `${r.startDate} ~ ${r.endDate}` },
    { title: '状态', dataIndex: 'status', key: 'st', width: 100, render: v => <Tag color={statusColors[v]}>{v}</Tag> },
  ];

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>系统总览</Title>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="因子总数"
              value={stats.factors}
              prefix={<FundOutlined />}
              valueStyle={{ color: '#1677ff' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="策略总数"
              value={stats.strategies}
              prefix={<ThunderboltOutlined />}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="回测任务"
              value={stats.backtests}
              prefix={<LineChartOutlined />}
              valueStyle={{ color: '#fa8c16' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="最新因子" size="small">
            <Table
              dataSource={recentFactors}
              columns={factorColumns}
              rowKey="id"
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="最新回测" size="small">
            <Table
              dataSource={recentBacktests}
              columns={backtestColumns}
              rowKey="id"
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24}>
          <Card size="small">
            <Space wrap>
              <Text strong>快速入门：</Text>
              <Text type="secondary">① 在「因子管理」中定义/测试因子</Text>
              <Text type="secondary">② 在「策略管理」中配置选股策略</Text>
              <Text type="secondary">③ 在「回测管理」中运行历史回测</Text>
              <Text type="secondary">④ 查看详细绩效报告（IC、夏普比率、最大回撤等）</Text>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
