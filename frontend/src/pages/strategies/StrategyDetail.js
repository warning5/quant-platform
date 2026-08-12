import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tag, Button, Space, Typography, Spin, Tabs, Table, Modal, Select, Badge } from 'antd';
import { message } from '../../utils/messageUtil';
import {
  ArrowLeftOutlined, EditOutlined, ExperimentOutlined,
  PlayCircleOutlined, PauseCircleOutlined, EyeOutlined,
  CheckCircleOutlined
} from '@ant-design/icons';
import { strategyApi, backtestApi } from '../../api';
import { useAuthStore } from '../../stores/authStore';
import { useDict } from '../../utils/useDict';

const { Title, Text } = Typography;
const { Option } = Select;

export default function StrategyDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const canEdit = useAuthStore((s) => s.hasPermission('strategy:edit'));
  const [strategy, setStrategy]         = useState(null);
  const [loading, setLoading]           = useState(true);
  const [backtests, setBacktests]       = useState([]);
  const [btLoading, setBtLoading]       = useState(false);
  const [statusModal, setStatusModal]   = useState(false);
  const [statusUpdating, setStatusUpdating] = useState(false);
  const [newStatus, setNewStatus]       = useState('');

  const { dictMap: dictMapStatus, dictList: dictListStatus } = useDict('STRATEGY_STATUS');
  const { dictMap: dictMapType } = useDict('STRATEGY_TYPE');
  const { dictMap: dictMapFreq } = useDict('STRATEGY_FREQ');
  const { dictMap: dictMapBt, dictList: dictListBt } = useDict('BACKTEST_STATUS');

  const loadStrategy = () => {
    strategyApi.getById(id).then(res => {
      setStrategy(res);
      setNewStatus(res.status);
    }).finally(() => setLoading(false));
  };

  const loadBacktests = () => {
    setBtLoading(true);
    backtestApi.list({ page: 0, size: 50, strategyCode: strategy?.strategyCode })
      .then(res => setBacktests(res.records || []))
      .catch(() => {})
      .finally(() => setBtLoading(false));
  };

  useEffect(() => { loadStrategy(); }, [id]);

  // 策略加载后再加载回测列表
  useEffect(() => {
    if (strategy?.strategyCode) {
      setBtLoading(true);
      backtestApi.list({ page: 0, size: 50 })
        .then(res => {
          const all = res.records || [];
          setBacktests(all.filter(t => t.strategyCode === strategy.strategyCode));
        })
        .catch(() => {})
        .finally(() => setBtLoading(false));
    }
  }, [strategy?.strategyCode]);

  const handleStatusChange = () => {
    setStatusUpdating(true);
    strategyApi.changeStatus(id, newStatus)
      .then(() => {
        message.success(`策略状态已更新为「${dictMapStatus[newStatus]?.dictLabel ?? newStatus}」`);
        setStatusModal(false);
        loadStrategy();
      })
      .finally(() => setStatusUpdating(false));
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin /></div>;
  if (!strategy) return <Text type="danger">策略不存在</Text>;

  const formatJson = (json) => {
    try { return JSON.stringify(JSON.parse(json), null, 2); } catch { return json; }
  };

  const btColumns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '任务名称', dataIndex: 'taskName', ellipsis: true,
      render: (v, r) => v || `回测-${r.strategyCode}`,
    },
    {
      title: '回测区间', key: 'range', width: 200,
      render: (_, r) => `${r.startDate} ~ ${r.endDate}`,
    },
    {
      title: '初始资金', dataIndex: 'initialCapital', width: 120,
      render: v => v ? `¥${(+v).toLocaleString()}` : '-',
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: v => <Tag color={dictMapBt[v]?.color ?? 'default'}>{dictMapBt[v]?.dictLabel ?? v}</Tag>,
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 160, ellipsis: true },
    {
      title: '操作', key: 'action', width: 90, fixed: 'right',
      render: (_, r) => r.status === 'COMPLETED' && (
        <Button size="small" type="primary" icon={<EyeOutlined />}
                onClick={() => navigate(`/backtests/${r.id}/report`)}>
          查看
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/strategies')}>返回</Button>
          <Title level={4} style={{ margin: 0 }}>{strategy.strategyName}</Title>
          <Tag color="geekblue">{strategy.strategyCode}</Tag>
          <Tag color={dictMapStatus[strategy.status]?.color ?? 'default'}>{dictMapStatus[strategy.status]?.dictLabel ?? strategy.status}</Tag>
        </Space>
        <Space>
          {canEdit && (
            <Button
              icon={<CheckCircleOutlined />}
              onClick={() => { setNewStatus(strategy.status); setStatusModal(true); }}
            >
              状态切换
            </Button>
          )}
          {canEdit && (
            <Button icon={<ExperimentOutlined />} type="primary"
                    onClick={() => navigate(`/backtests/new?strategyId=${id}`)}>
              创建回测
            </Button>
          )}
          {canEdit && (
            <Button icon={<EditOutlined />} onClick={() => navigate(`/strategies/${id}/edit`)}>
              编辑策略
            </Button>
          )}
        </Space>
      </div>

      <Tabs defaultActiveKey="info" items={[
        /* ── Tab 1：基本信息 ── */
        {
          key: 'info',
          label: '基本信息',
          children: (
            <Card>
              <Descriptions bordered column={2} size="middle"
                styles={{ label: { whiteSpace: 'nowrap', width: 100 }, content: { minWidth: 200 } }}>
                <Descriptions.Item label="策略代码">{strategy.strategyCode}</Descriptions.Item>
                <Descriptions.Item label="策略名称">{strategy.strategyName}</Descriptions.Item>
                <Descriptions.Item label="策略类型">{dictMapType[strategy.strategyType]?.dictLabel ?? strategy.strategyType}</Descriptions.Item>
                <Descriptions.Item label="调仓频率">{dictMapFreq[strategy.rebalanceFrequency]?.dictLabel ?? (strategy.rebalanceFrequency || '-')}</Descriptions.Item>
                <Descriptions.Item label="最大持仓数">{strategy.maxPositionCount || '-'}</Descriptions.Item>
                <Descriptions.Item label="仓位方式">{strategy.positionSizeType || '-'}</Descriptions.Item>
                <Descriptions.Item label="止损比例">
                  {strategy.stopLossPct ? `${(+strategy.stopLossPct * 100).toFixed(1)}%` : '-'}
                </Descriptions.Item>
                <Descriptions.Item label="止盈比例">
                  {strategy.stopProfitPct ? `${(+strategy.stopProfitPct * 100).toFixed(1)}%` : '-'}
                </Descriptions.Item>
                <Descriptions.Item label="当前状态">
                  <Badge status={strategy.status === 'ACTIVE' ? 'success' : strategy.status === 'TESTING' ? 'processing' : 'default'} />
                  <Tag color={dictMapStatus[strategy.status]?.color ?? 'default'} style={{ marginLeft: 4 }}>
                    {dictMapStatus[strategy.status]?.dictLabel ?? strategy.status}
                  </Tag>
                </Descriptions.Item>
                <Descriptions.Item label="版本">v{strategy.version}</Descriptions.Item>
                <Descriptions.Item label="创建人" span={2}>{strategy.author || '-'}</Descriptions.Item>
                <Descriptions.Item label="描述" span={2}>{strategy.description || '-'}</Descriptions.Item>
                {strategy.factorConfigJson && (
                  <Descriptions.Item label="因子配置" span={2}>
                    <pre style={{ background: '#f6f8fa', padding: 8, borderRadius: 4, fontSize: 12, margin: 0 }}>
                      {formatJson(strategy.factorConfigJson)}
                    </pre>
                  </Descriptions.Item>
                )}
                {strategy.filterConfigJson && (
                  <Descriptions.Item label="过滤配置" span={2}>
                    <pre style={{ background: '#f6f8fa', padding: 8, borderRadius: 4, fontSize: 12, margin: 0 }}>
                      {formatJson(strategy.filterConfigJson)}
                    </pre>
                  </Descriptions.Item>
                )}
                {strategy.scriptCode && (
                  <Descriptions.Item label="策略脚本" span={2}>
                    <pre style={{
                      background: '#1e1e1e', color: '#d4d4d4', padding: 12,
                      borderRadius: 6, maxHeight: 400, overflow: 'auto', fontSize: 12, margin: 0,
                    }}>
                      {strategy.scriptCode}
                    </pre>
                  </Descriptions.Item>
                )}
              </Descriptions>
            </Card>
          ),
        },

        /* ── Tab 2：关联回测 ── */
        {
          key: 'backtests',
          label: `关联回测 (${backtests.length})`,
          children: (
            <Card
              title="该策略的历史回测任务"
              extra={
                <Button type="primary" size="small" icon={<ExperimentOutlined />}
                        onClick={() => navigate(`/backtests/new?strategyId=${id}`)}>
                  新建回测
                </Button>
              }
            >
              <Table
                dataSource={backtests}
                columns={btColumns}
                rowKey="id"
                loading={btLoading}
                size="small"
                scroll={{ x: 900 }}
                pagination={{ defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'], showTotal: t => `共 ${t} 次回测` }}
                locale={{ emptyText: '该策略暂无回测记录，点击「新建回测」开始' }}
              />
            </Card>
          ),
        },
      ]} />

      {/* 状态切换 Modal */}
      <Modal
        title="切换策略状态"
        open={statusModal}
        onOk={handleStatusChange}
        onCancel={() => setStatusModal(false)}
        confirmLoading={statusUpdating}
        okText="确认切换"
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <div>
            <Text>当前状态：</Text>
            <Tag color={dictMapStatus[strategy.status]?.color ?? 'default'} style={{ marginLeft: 8 }}>
              {dictMapStatus[strategy.status]?.dictLabel ?? strategy.status}
            </Tag>
          </div>
          <div>
            <Text>切换至：</Text>
            <Select
              value={newStatus}
              onChange={setNewStatus}
              style={{ width: 160, marginLeft: 8 }}
            >
              {dictListStatus.length ? dictListStatus.map(d => (
                <Option key={d.dictValue} value={d.dictValue}>{d.dictLabel}</Option>
              )) : []}
            </Select>
          </div>
          <Text type="secondary" style={{ fontSize: 12 }}>
            将策略状态设为「已激活」后可在回测中正常使用；「草稿」状态策略仅供编辑。
          </Text>
        </Space>
      </Modal>
    </div>
  );
}
