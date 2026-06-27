import React, { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Card, Typography, Popconfirm, Progress, Tooltip, Select, Modal, DatePicker } from 'antd';
import { message } from '../../utils/messageUtil';
import { PlusOutlined, EyeOutlined, DeleteOutlined, ReloadOutlined, StopOutlined, LoadingOutlined, RedoOutlined, ThunderboltOutlined, DownloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { backtestApi, strategyApi } from '../../api';
import { exportCsv } from '../../utils/exportUtil';
import dayjs from 'dayjs';

const { Title, Text } = Typography;

const STATUS_COLORS = {
  PENDING: 'default', RUNNING: 'processing', COMPLETED: 'success',
  FAILED: 'error', CANCELLED: 'warning'
};
const STATUS_LABELS = {
  PENDING: '等待中', RUNNING: '运行中', COMPLETED: '已完成',
  FAILED: '失败', CANCELLED: '已取消'
};

const SIGNAL_LABELS = {
  STRATEGY: { color: 'blue', text: '策略因子' },
  SCREEN: { color: 'purple', text: '因子筛选' },
};

export default function BacktestList() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState({ records: [], total: 0 });
  const [params, setParams] = useState({ page: 0, size: 15 });
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);
  const [signalFilter, setSignalFilter] = useState(null); // null=全部, 'STRATEGY', 'SCREEN'

  // 批量回测
  const [batchModalOpen, setBatchModalOpen] = useState(false);
  const [batchDateRange, setBatchDateRange] = useState([dayjs().subtract(1, 'year'), dayjs()]);
  const [strategyList, setStrategyList] = useState([]);
  const [batchStrategyIds, setBatchStrategyIds] = useState([]);
  const [batchLoading, setBatchLoading] = useState(false);

  const fetchData = (p = params) => {
    setLoading(true);
    const query = { ...p };
    if (signalFilter) query.signalSource = signalFilter;
    backtestApi.list(query).then(res => setData(res)).finally(() => setLoading(false));
  };

  useEffect(() => { fetchData(); }, [signalFilter]);

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

  const handleRerun = (id) => {
    backtestApi.rerun(id).then(() => {
      message.success('已重新提交，回测正在执行...');
      fetchData();
    }).catch(() => message.error('重跑失败，请稍后重试'));
  };

  // 打开批量回测弹窗
  const openBatchModal = () => {
    strategyApi.list({ page: 0, size: 200 }).then(res => {
      const content = res?.records || res?.content || res || [];
      setStrategyList(Array.isArray(content) ? content : []);
      setBatchStrategyIds((Array.isArray(content) ? content : []).map(s => s.id ?? s.strategyId));
      setBatchModalOpen(true);
    }).catch(() => message.error('获取策略列表失败'));
  };

  // 批量回测提交
  const handleBatchBacktest = async () => {
    if (batchStrategyIds.length === 0) {
      message.warning('请选择至少1个策略');
      return;
    }
    if (!batchDateRange[0] || !batchDateRange[1]) {
      message.warning('请选择日期范围');
      return;
    }
    setBatchLoading(true);
    const startDate = batchDateRange[0].format('YYYY-MM-DD');
    const endDate = batchDateRange[1].format('YYYY-MM-DD');
    let successCount = 0;
    let failCount = 0;
    for (const sid of batchStrategyIds) {
      const s = strategyList.find(x => (x.id ?? x.strategyId) === sid);
      const taskName = `批量回测-${s?.strategyName || s?.name || sid}`;
      try {
        await backtestApi.create({
          signalSource: 'STRATEGY',
          strategyId: sid,
          taskName,
          startDate,
          endDate,
          initialCapital: 1000000,
          commissionRate: 0.0003,
          slippage: 0.001,
          benchmark: 'CSI300',
        });
        successCount++;
      } catch {
        failCount++;
      }
    }
    setBatchLoading(false);
    setBatchModalOpen(false);
    if (successCount > 0) {
      message.success(`已提交 ${successCount} 个策略回测${failCount > 0 ? `，${failCount} 个失败` : ''}`);
      fetchData();
    } else {
      message.error('全部策略回测提交失败');
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
    {
      title: '任务名称', dataIndex: 'taskName', key: 'name', width: 200, ellipsis: true,
      render: (v, r) => v || `回测-${r.strategyCode || '筛选'}`
    },
    {
      title: '选股方式', dataIndex: 'signalSource', key: 'source', width: 100, align: 'center',
      render: v => {
        const info = SIGNAL_LABELS[v] || { color: 'default', text: v || '-' };
        return <Tag color={info.color}>{info.text}</Tag>;
      },
    },
    {
      title: '策略/配置', key: 'config', width: 200, ellipsis: true,
      render: (_, r) => {
        if (r.signalSource === 'SCREEN') {
          if (!r.screenConfigJson) return <Text type="secondary">-</Text>;
          try {
            const c = JSON.parse(r.screenConfigJson);
            const factors = Array.isArray(c.factors) ? c.factors : [];
            return <Text type="secondary">{factors.length}因子 · Top{c.topN ?? '-'}</Text>;
          } catch { return <Text type="secondary">解析失败</Text>; }
        }
        return <Tag color="geekblue">{r.strategyCode || '-'}</Tag>;
      },
    },
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
      title: '操作', key: 'action', width: 160, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          {record.status === 'COMPLETED' && (
            <Tooltip title="查看报告">
              <Button size="small" type="primary" icon={<EyeOutlined />}
                      onClick={() => navigate(`/backtests/${record.id}/report`)} />
            </Tooltip>
          )}
          {(record.status === 'COMPLETED' || record.status === 'FAILED' || record.status === 'CANCELLED') && (
            <Popconfirm title="将清空旧结果并重新执行，确认重跑？" onConfirm={() => handleRerun(record.id)}>
              <Tooltip title="重跑">
                <Button size="small" icon={<RedoOutlined />} />
              </Tooltip>
            </Popconfirm>
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
          <Select
            allowClear
            placeholder="选股方式"
            style={{ width: 130 }}
            value={signalFilter}
            onChange={v => setSignalFilter(v || null)}
            options={[
              { value: 'STRATEGY', label: '策略因子' },
              { value: 'SCREEN', label: '因子筛选' },
            ]}
          />
          {selectedRowKeys.length > 0 && (
            <Button danger icon={<DeleteOutlined />} onClick={handleBatchDelete}>
              批量删除 ({selectedRowKeys.length})
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={() => fetchData()}>刷新</Button>
          <Button icon={<ThunderboltOutlined />} onClick={openBatchModal}>批量回测</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/backtests/new')}>
            新建回测
          </Button>
        </Space>
      </div>

      <Card style={{ border: '1px solid #d9d9d9' }} extra={
        <Button size="small" icon={<DownloadOutlined />} onClick={() => exportCsv({ data: data?.records || [], columns, filename: '回测列表' })} disabled={!data?.records?.length}>导出CSV</Button>
      }>
        <Table
          dataSource={data.records}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
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

      <Modal
        title="批量回测全部策略"
        open={batchModalOpen}
        onCancel={() => setBatchModalOpen(false)}
        onOk={handleBatchBacktest}
        confirmLoading={batchLoading}
        width={520}
        okText={`开始回测 (${batchStrategyIds.length}个策略)`}
        cancelText="取消"
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
              日期范围
            </Typography.Text>
            <DatePicker.RangePicker
              value={batchDateRange}
              onChange={setBatchDateRange}
              style={{ width: '100%' }}
              presets={[
                { label: '近1年', value: [dayjs().subtract(1, 'year'), dayjs()] },
                { label: '近6个月', value: [dayjs().subtract(6, 'month'), dayjs()] },
                { label: '近2年', value: [dayjs().subtract(2, 'year'), dayjs()] },
              ]}
            />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
              选择策略（已默认全选，可取消不需要的）
            </Typography.Text>
            <Select
              mode="multiple"
              value={batchStrategyIds}
              onChange={setBatchStrategyIds}
              style={{ width: '100%' }}
              maxTagCount="responsive"
              options={strategyList.map(s => ({
                label: `${s.strategyName || s.name || '未命名'} (${s.strategyCode || s.code || ''})`,
                value: s.id ?? s.strategyId,
              }))}
            />
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            每个策略将创建独立的回测任务，使用相同日期范围和参数（初始资金100万、佣金0.03%、滑点0.1%、沪深300基准）。
            提交后可在列表中查看进度，每个策略约需5~10分钟。
          </Typography.Text>
        </Space>
      </Modal>
    </div>
  );
}
