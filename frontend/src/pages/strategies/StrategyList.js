import React, { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Input, Select, Card, Typography, Popconfirm, message, Tooltip, Badge } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, ExperimentOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { strategyApi } from '../../api';

const { Title } = Typography;
const { Option } = Select;

const TYPE_OPTIONS = ['FACTOR_LONG','LONG_SHORT','MARKET_NEUTRAL','MOMENTUM','MEAN_REVERSION','CUSTOM'];
const STATUS_COLORS = { DRAFT:'default', TESTING:'processing', ACTIVE:'success', DEPRECATED:'default' };
const STATUS_LABELS = { DRAFT:'草稿', TESTING:'测试中', ACTIVE:'已激活', DEPRECATED:'已废弃' };
const TYPE_LABELS = {
  FACTOR_LONG:'因子多头', LONG_SHORT:'多空策略', MARKET_NEUTRAL:'市场中性',
  MOMENTUM:'动量策略', MEAN_REVERSION:'均值回归', CUSTOM:'自定义脚本'
};
const FREQ_LABELS = { DAILY:'日频', WEEKLY:'周频', MONTHLY:'月频', QUARTERLY:'季频' };

export default function StrategyList() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState({ records: [], total: 0 });
  const [params, setParams] = useState({ page: 0, size: 15, keyword: '', type: undefined, status: undefined });

  const fetchData = (p = params) => {
    setLoading(true);
    strategyApi.list(p).then(res => setData(res)).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleDelete = (id) => {
    strategyApi.delete(id).then(() => { message.success('删除成功'); fetchData(); });
  };

  const columns = [
    { title: '策略代码', dataIndex: 'strategyCode', key: 'code', width: 200, render: v => <Tag color="geekblue">{v}</Tag> },
    { title: '策略名称', dataIndex: 'strategyName', key: 'name', ellipsis: true },
    { title: '类型', dataIndex: 'strategyType', key: 'type', width: 110, render: v => <Tag>{TYPE_LABELS[v] || v}</Tag> },
    { title: '调仓频率', dataIndex: 'rebalanceFrequency', key: 'freq', width: 90, render: v => FREQ_LABELS[v] || v },
    { title: '最大持仓', dataIndex: 'maxPositionCount', key: 'pos', width: 90, align: 'center', render: v => v || '-' },
    { title: '版本', dataIndex: 'version', key: 'ver', width: 60, align: 'center', render: v => `v${v}` },
    {
      title: '状态', dataIndex: 'status', key: 'st', width: 90,
      render: v => <Tag color={STATUS_COLORS[v]}>{STATUS_LABELS[v] || v}</Tag>
    },
    { title: '创建人', dataIndex: 'author', key: 'author', width: 90 },
    {
      title: '操作', key: 'action', width: 160, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Tooltip title="查看详情">
            <Button size="small" icon={<EyeOutlined />} onClick={() => navigate(`/strategies/${record.id}`)} />
          </Tooltip>
          <Tooltip title="编辑">
            <Button size="small" icon={<EditOutlined />} onClick={() => navigate(`/strategies/${record.id}/edit`)} />
          </Tooltip>
          <Tooltip title="创建回测">
            <Button size="small" type="primary" icon={<ExperimentOutlined />}
                    onClick={() => navigate(`/backtests/new?strategyId=${record.id}`)} />
          </Tooltip>
          <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
            <Tooltip title="删除">
              <Button size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>策略管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/strategies/new')}>
          新建策略
        </Button>
      </div>

      <Card style={{ marginBottom: 16, border: '1px solid #d9d9d9' }}>
        <Space wrap>
          <Input.Search
            placeholder="搜索策略代码/名称"
            allowClear style={{ width: 240 }}
            onSearch={v => { const p = { ...params, keyword: v, page: 0 }; setParams(p); fetchData(p); }}
          />
          <Select
            placeholder="策略类型" allowClear style={{ width: 140 }}
            onChange={v => { const p = { ...params, type: v, page: 0 }; setParams(p); fetchData(p); }}>
            {TYPE_OPTIONS.map(t => <Option key={t} value={t}>{TYPE_LABELS[t]}</Option>)}
          </Select>
          <Select
            placeholder="状态" allowClear style={{ width: 110 }}
            onChange={v => { const p = { ...params, status: v, page: 0 }; setParams(p); fetchData(p); }}>
            {Object.entries(STATUS_LABELS).map(([k, v]) => <Option key={k} value={k}>{v}</Option>)}
          </Select>
        </Space>
      </Card>

      <Card style={{ border: '1px solid #d9d9d9' }}>
        <Table
          dataSource={data.records}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1000 }}
          pagination={{
            total: data.total,
            pageSize: params.size,
            current: params.page + 1,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
            showTotal: t => `共 ${t} 条`,
            onChange: (page, size) => {
              setParams(prev => {
                const p = { ...prev, page: page - 1, size };
                fetchData(p);
                return p;
              });
            },
            onShowSizeChange: (current, size) => {
              setParams(prev => {
                const p = { ...prev, page: 0, size };
                fetchData(p);
                return p;
              });
            },
          }}
        />
      </Card>
    </div>
  );
}
