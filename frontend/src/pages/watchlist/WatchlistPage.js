import React, { useState, useEffect, useCallback } from 'react';
import { Table, Card, Button, Tag, Space, Modal, Form, Input, InputNumber, Select, DatePicker, message, Tooltip, Popconfirm, Badge, Tabs, Empty, Row, Col, Statistic } from 'antd';
import { StarOutlined, StarFilled, PlusOutlined, DeleteOutlined, EditOutlined, EyeOutlined, ClockCircleOutlined, WarningOutlined, ReloadOutlined } from '@ant-design/icons';
import { watchlistApi } from '../../api';
import dayjs from 'dayjs';

const { TextArea } = Input;

const SOURCE_MAP = {
  MANUAL: { color: 'blue', label: '手动添加' },
  RECOMMENDATION: { color: 'green', label: '推荐候选' },
  SCREEN: { color: 'orange', label: '选股结果' },
};

export default function WatchlistPage() {
  const [data, setData] = useState([]);
  const [groups, setGroups] = useState([]);
  const [activeGroup, setActiveGroup] = useState(null);
  const [loading, setLoading] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [currentItem, setCurrentItem] = useState(null);
  const [expiredCount, setExpiredCount] = useState(0);
  const [addForm] = Form.useForm();
  const [editForm] = Form.useForm();

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [list, groupList, expired] = await Promise.all([
        watchlistApi.getList(activeGroup || undefined),
        watchlistApi.getGroups(),
        watchlistApi.getExpired(),
      ]);
      setData(list || []);
      setGroups(groupList || []);
      setExpiredCount((expired || []).length);
    } catch (e) {
      message.error('加载自选股失败: ' + (e.message || '未知错误'));
    } finally {
      setLoading(false);
    }
  }, [activeGroup]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleAdd = async (values) => {
    try {
      await watchlistApi.add({
        ...values,
        watchEndDate: values.watchEndDate ? values.watchEndDate.format('YYYY-MM-DD') : undefined,
      });
      message.success('添加成功');
      setAddModalOpen(false);
      addForm.resetFields();
      fetchData();
    } catch (e) {
      message.error(e.response?.data?.message || '添加失败');
    }
  };

  const handleEdit = async (values) => {
    try {
      await watchlistApi.update(currentItem.id, {
        ...values,
        watchEndDate: values.watchEndDate ? values.watchEndDate.format('YYYY-MM-DD') : undefined,
      });
      message.success('更新成功');
      setEditModalOpen(false);
      editForm.resetFields();
      fetchData();
    } catch (e) {
      message.error('更新失败');
    }
  };

  const handleRemove = async (id) => {
    try {
      await watchlistApi.remove(id);
      message.success('已移除');
      fetchData();
    } catch (e) {
      message.error('移除失败');
    }
  };

  const handleClearGroup = async (groupName) => {
    try {
      const count = await watchlistApi.clearGroup(groupName);
      message.success(`已清空分组 "${groupName}"，共 ${count} 只`);
      fetchData();
    } catch (e) {
      message.error('清空失败');
    }
  };

  const isExpired = (item) => item.watchEndDate && dayjs(item.watchEndDate).isBefore(dayjs(), 'day');
  const isExpiringSoon = (item) => {
    if (!item.watchEndDate) return false;
    const diff = dayjs(item.watchEndDate).diff(dayjs(), 'day');
    return diff >= 0 && diff <= 2;
  };

  const columns = [
    {
      title: '股票代码',
      dataIndex: 'stockCode',
      width: 100,
      render: (code) => <a href={`/analysis?code=${code}`} style={{ fontWeight: 600 }}>{code}</a>,
    },
    {
      title: '股票名称',
      dataIndex: 'stockName',
      width: 100,
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 90,
      render: (s) => {
        const src = SOURCE_MAP[s] || { color: 'default', label: s };
        return <Tag color={src.color}>{src.label}</Tag>;
      },
    },
    {
      title: '目标买入价',
      dataIndex: 'targetBuyPrice',
      width: 110,
      render: (v) => v ? <span style={{ color: '#cf1322' }}>{v.toFixed(2)}</span> : '-',
    },
    {
      title: '止损价',
      dataIndex: 'stopLossPrice',
      width: 90,
      render: (v) => v ? <span style={{ color: '#faad14' }}>{v.toFixed(2)}</span> : '-',
    },
    {
      title: '目标卖出价',
      dataIndex: 'targetSellPrice',
      width: 110,
      render: (v) => v ? <span style={{ color: '#52c41a' }}>{v.toFixed(2)}</span> : '-',
    },
    {
      title: '观测到期',
      dataIndex: 'watchEndDate',
      width: 110,
      render: (date, record) => {
        if (!date) return '-';
        const expired = isExpired(record);
        const soon = isExpiringSoon(record);
        return (
          <Space>
            {expired ? <WarningOutlined style={{ color: '#ff4d4f' }} /> :
             soon ? <ClockCircleOutlined style={{ color: '#faad14' }} /> :
             <ClockCircleOutlined style={{ color: '#52c41a' }} />}
            <span style={{ color: expired ? '#ff4d4f' : soon ? '#faad14' : undefined }}>
              {date}
            </span>
          </Space>
        );
      },
    },
    {
      title: '加入原因',
      dataIndex: 'reason',
      width: 150,
      ellipsis: true,
      render: (text) => <Tooltip title={text}>{text || '-'}</Tooltip>,
    },
    {
      title: '分组',
      dataIndex: 'groupName',
      width: 90,
      render: (g) => <Tag>{g || 'default'}</Tag>,
    },
    {
      title: '备注',
      dataIndex: 'notes',
      width: 120,
      ellipsis: true,
      render: (text) => <Tooltip title={text}>{text || '-'}</Tooltip>,
    },
    {
      title: '操作',
      width: 120,
      fixed: 'right',
      render: (_, record) => (
        <Space>
          <Tooltip title="编辑">
            <Button type="link" size="small" icon={<EditOutlined />}
              onClick={() => {
                setCurrentItem(record);
                editForm.setFieldsValue({
                  ...record,
                  watchEndDate: record.watchEndDate ? dayjs(record.watchEndDate) : undefined,
                });
                setEditModalOpen(true);
              }} />
          </Tooltip>
          <Popconfirm title="确认移除？" onConfirm={() => handleRemove(record.id)}>
            <Tooltip title="移除">
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      <Card
        title={
          <Space>
            <StarFilled style={{ color: '#faad14' }} />
            <span>自选股看板</span>
            {expiredCount > 0 && (
              <Badge count={expiredCount} offset={[6, -2]}>
                <Tag color="error">有{expiredCount}只到期</Tag>
              </Badge>
            )}
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
              添加自选股
            </Button>
          </Space>
        }
      >
        {/* 统计栏 */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}>
            <Statistic title="自选股数量" value={data.length} suffix="只" />
          </Col>
          <Col span={6}>
            <Statistic title="分组数" value={groups.length || 1} suffix="个" />
          </Col>
          <Col span={6}>
            <Statistic title="已设目标价" value={data.filter(d => d.targetBuyPrice).length} suffix="只" />
          </Col>
          <Col span={6}>
            <Statistic title="观测到期" value={expiredCount} suffix="只"
              valueStyle={expiredCount > 0 ? { color: '#cf1322' } : undefined} />
          </Col>
        </Row>

        {/* 分组Tab */}
        <Tabs
          activeKey={activeGroup || '__all__'}
          onChange={(key) => setActiveGroup(key === '__all__' ? null : key)}
          items={[
            { key: '__all__', label: `全部 (${data.length})` },
            ...(groups || []).map(g => ({
              key: g,
              label: g,
            })),
          ]}
          tabBarExtraContent={
            activeGroup && activeGroup !== '__all__' ? (
              <Popconfirm title={`确认清空分组 "${activeGroup}" 的所有自选股？`} onConfirm={() => handleClearGroup(activeGroup)}>
                <Button size="small" danger icon={<DeleteOutlined />}>清空此分组</Button>
              </Popconfirm>
            ) : null
          }
        />

        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={loading}
          size="small"
          scroll={{ x: 1200 }}
          rowClassName={(record) => isExpired(record) ? 'expired-row' : isExpiringSoon(record) ? 'expiring-row' : ''}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无自选股，点击右上角添加" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
        />
      </Card>

      {/* 添加弹窗 */}
      <Modal title="添加自选股" open={addModalOpen} onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
        onOk={() => addForm.submit()} destroyOnClose>
        <Form form={addForm} onFinish={handleAdd} layout="vertical">
          <Form.Item name="stockCode" label="股票代码" rules={[{ required: true, message: '请输入股票代码' }]}>
            <Input placeholder="如 600519" />
          </Form.Item>
          <Form.Item name="stockName" label="股票名称">
            <Input placeholder="如 贵州茅台" />
          </Form.Item>
          <Row gutter={8}>
            <Col span={12}>
              <Form.Item name="groupName" label="分组">
                <Select placeholder="选择分组" allowClear>
                  {(groups || []).map(g => <Select.Option key={g} value={g}>{g}</Select.Option>)}
                  <Select.Option value="default">默认</Select.Option>
                  <Select.Option value="__new__">新建分组...</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="watchEndDate" label="观测到期日">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={8}>
            <Col span={8}>
              <Form.Item name="targetBuyPrice" label="目标买入价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="stopLossPrice" label="止损价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="targetSellPrice" label="目标卖出价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="reason" label="加入原因">
            <TextArea rows={2} placeholder="为什么关注这只股票" />
          </Form.Item>
          <Form.Item name="notes" label="备注">
            <TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑弹窗 */}
      <Modal title="编辑自选股" open={editModalOpen} onCancel={() => { setEditModalOpen(false); editForm.resetFields(); }}
        onOk={() => editForm.submit()} destroyOnClose>
        <Form form={editForm} onFinish={handleEdit} layout="vertical">
          <Row gutter={8}>
            <Col span={8}>
              <Form.Item name="targetBuyPrice" label="目标买入价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="stopLossPrice" label="止损价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="targetSellPrice" label="目标卖出价">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={8}>
            <Col span={12}>
              <Form.Item name="groupName" label="分组">
                <Select placeholder="选择分组">
                  {(groups || []).map(g => <Select.Option key={g} value={g}>{g}</Select.Option>)}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="watchEndDate" label="观测到期日">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="notes" label="备注">
            <TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <style>{`
        .expired-row { background-color: #fff2f0 !important; }
        .expiring-row { background-color: #fffbe6 !important; }
      `}</style>
    </div>
  );
}
