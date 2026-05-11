import React, { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Card, Typography, Popconfirm, message, Progress, Tooltip } from 'antd';
import { PlusOutlined, EyeOutlined, DeleteOutlined, ReloadOutlined, StopOutlined, LoadingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { backtestApi } from '../../api';

const { Title, Text } = Typography;

const STATUS_COLORS = {
  PENDING: 'default', RUNNING: 'processing', COMPLETED: 'success',
  FAILED: 'error', CANCELLED: 'warning'
};
const STATUS_LABELS = {
  PENDING: '等待中', RUNNING: '运行中', COMPLETED: '已完成',
  FAILED: '失败', CANCELLED: '已取消'
};

export default function BacktestList() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState({ records: [], total: 0 });
  const [params, setParams] = useState({ page: 0, size: 15 });
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);

  const fetchData = (p = params) => {
    setLoading(true);
    backtestApi.list(p).then(res => setData(res)).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  // 轮询刷新运行中的任务
  useEffect(() => {
    const hasRunning = data.records?.some(t => t.status === 'RUNNING' || t.status === 'PENDING');
    if (!hasRunning) return;
    const timer = setInterval(() => fetchData(), 5000);
    return () => clearInterval(timer);
  }, [data.records]);

  // 批量删除
  const handleBatchDelete = () => {
    if (selectedRowKeys.length === 0) return;
    Promise.all(selectedRowKeys.map(id => backtestApi.delete(id)))
      .then(() => {
        message.success(`已删除 ${selectedRowKeys.length} 条`);
        setSelectedRowKeys([]);
        fetchData();
      })
      .catch(() => message.error('删除失败，请稍后重试'));
  };

  const handleDelete = (id) => {
    backtestApi.delete(id).then(() => { message.success('删除成功'); fetchData(); });
  };

  const handleCancel = (id) => {
    backtestApi.cancel(id).then(() => { message.success('已发送取消请求'); fetchData(); });
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    {
      title: '任务名称', dataIndex: 'taskName', key: 'name', ellipsis: true,
      render: (v, r) => v || `回测-${r.strategyCode}`
    },
    { title: '策略代码', dataIndex: 'strategyCode', key: 'code', width: 220, render: v => <Tag color="geekblue">{v}</Tag> },
    {
      title: '回测区间', key: 'range', width: 200,
      render: (_, r) => `${r.startDate} ~ ${r.endDate}`
    },
    {
      title: '初始资金', dataIndex: 'initialCapital', key: 'cap', width: 120,
      render: v => v ? `¥${(+v).toLocaleString()}` : '-'
    },
    {
      title: '进度', key: 'progress', width: 130,
      render: (_, r) => r.status === 'RUNNING' ? (
        <Progress percent={r.progress || 0} size="small" status="active" />
      ) : (
        <Tag color={STATUS_COLORS[r.status]}>{STATUS_LABELS[r.status]}</Tag>
      )
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'created', width: 160, ellipsis: true, render: v => v ? v.replace('T', ' ').substring(0, 19) : '-' },
    {
      title: '操作', key: 'action', width: 130, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          {record.status === 'COMPLETED' && (
            <Tooltip title="查看报告">
              <Button size="small" type="primary" icon={<EyeOutlined />}
                      onClick={() => navigate(`/backtests/${record.id}/report`)} />
            </Tooltip>
          )}
          {(record.status === 'RUNNING' || record.status === 'PENDING') && (
            <Tooltip title="查看执行进度">
              <Button size="small" type="default" icon={<LoadingOutlined />}
                      onClick={() => navigate(`/backtests/${record.id}/running`)} />
            </Tooltip>
          )}
          {(record.status === 'RUNNING' || record.status === 'PENDING') && (
            <Popconfirm title="确认取消该回测任务？" onConfirm={() => handleCancel(record.id)}>
              <Tooltip title="取消任务">
                <Button size="small" danger icon={<StopOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
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
        <Title level={4} style={{ margin: 0 }}>回测列表</Title>
        <Space>
          {selectedRowKeys.length > 0 && (
            <Button danger icon={<DeleteOutlined />} onClick={handleBatchDelete}>
              批量删除 ({selectedRowKeys.length})
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={() => fetchData()}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/backtests/new')}>
            新建回测
          </Button>
        </Space>
      </div>

      <Card style={{ border: '1px solid #d9d9d9' }}>
        <Table
          dataSource={data.records}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1000 }}
          rowSelection={{
            selectedRowKeys,
            onChange: setSelectedRowKeys,
            getCheckboxProps: (record) => ({
              disabled: record.status === 'RUNNING' || record.status === 'PENDING',
            }),
          }}
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
