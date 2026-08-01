import React, { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Input, Select, Card, Typography, Popconfirm, Tooltip, Badge } from 'antd';
import { message } from '../../utils/messageUtil';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, ExperimentOutlined, DownloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { strategyApi } from '../../api';
import { useAuthStore } from '../../stores/authStore';
import { exportCsv } from '../../utils/exportUtil';
import { useDict } from '../../utils/useDict';

const { Title } = Typography;
const { Option } = Select;

export default function StrategyList() {
  const navigate = useNavigate();
  const canEdit = useAuthStore((s) => s.hasPermission('strategy:edit'));
  const canDelete = useAuthStore((s) => s.hasPermission('strategy:delete'));
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState({ records: [], total: 0 });
  const [params, setParams] = useState({ page: 0, size: 15, keyword: '', type: undefined, status: undefined });

  const { dictMap: dictMapStatus, dictList: dictListStatus } = useDict('STRATEGY_STATUS');
  const { dictMap: dictMapType, dictList: dictListType } = useDict('STRATEGY_TYPE');
  const { dictMap: dictMapFreq, dictList: dictListFreq } = useDict('STRATEGY_FREQ');

  const fetchData = (p = params) => {
    setLoading(true);
    // 清除 undefined 值，避免序列化为字符串 "undefined"
    const cleaned = Object.fromEntries(
      Object.entries(p).filter(([, v]) => v !== undefined && v !== '')
    );
    strategyApi.list(cleaned).then(res => setData(res)).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, []);

  const handleDelete = (id) => {
    if (!canDelete) { message.warning('无权限删除策略'); return; }
    strategyApi.delete(id).then(() => { message.success('删除成功'); fetchData(); });
  };

  const columns = [
    { title: '策略代码', dataIndex: 'strategyCode', key: 'code', width: 200, render: v => <Tag color="geekblue">{v}</Tag> },
    { title: '策略名称', dataIndex: 'strategyName', key: 'name', ellipsis: true },
    { title: '类型', dataIndex: 'strategyType', key: 'type', width: 110, render: v => <Tag>{dictMapType[v]?.dictLabel ?? v}</Tag> },
    { title: '调仓频率', dataIndex: 'rebalanceFrequency', key: 'freq', width: 90, render: v => dictMapFreq[v]?.dictLabel ?? v },
    { title: '最大持仓', dataIndex: 'maxPositionCount', key: 'pos', width: 90, align: 'center', render: v => v || '-' },
    { title: '版本', dataIndex: 'version', key: 'ver', width: 60, align: 'center', render: v => `v${v}` },
    {
      title: '状态', dataIndex: 'status', key: 'st', width: 90,
      render: v => <Tag color={dictMapStatus[v]?.color ?? 'default'}>{dictMapStatus[v]?.dictLabel ?? v}</Tag>
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
            <Button size="small" icon={<EditOutlined />} onClick={() => navigate(`/strategies/${record.id}/edit`)} disabled={!canEdit} />
          </Tooltip>
          <Tooltip title="创建回测">
            <Button size="small" type="primary" icon={<ExperimentOutlined />}
                    onClick={() => navigate(`/backtests/new?strategyId=${record.id}`)} disabled={!canEdit} />
          </Tooltip>
          <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
            <Tooltip title="删除">
              <Button size="small" danger icon={<DeleteOutlined />} disabled={!canDelete} />
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
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/strategies/new')} disabled={!canEdit}>
          新建策略
        </Button>
      </div>

      <Card style={{ marginBottom: 16, border: '1px solid #d9d9d9' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <Space wrap>
            <Input.Search
              placeholder="搜索策略代码/名称"
              allowClear style={{ width: 240 }}
              onSearch={v => { const p = { ...params, keyword: v, page: 0 }; setParams(p); fetchData(p); }}
            />
            <Select
              placeholder="策略类型" allowClear style={{ width: 140 }}
              onChange={v => { const p = { ...params, type: v, page: 0 }; setParams(p); fetchData(p); }}>
              {dictListType.length ? dictListType.map(d => <Option key={d.dictValue} value={d.dictValue}>{d.dictLabel}</Option>) : []}
            </Select>
            <Select
              placeholder="状态" allowClear style={{ width: 110 }}
              onChange={v => { const p = { ...params, status: v, page: 0 }; setParams(p); fetchData(p); }}>
              {dictListStatus.length ? dictListStatus.map(d => <Option key={d.dictValue} value={d.dictValue}>{d.dictLabel}</Option>) : []}
            </Select>
          </Space>
          <Button icon={<DownloadOutlined />} onClick={() => exportCsv({ data: data?.records || [], columns, filename: '策略列表' })} disabled={!data?.records?.length}>导出CSV</Button>
        </div>
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
