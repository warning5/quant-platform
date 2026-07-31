import React, { useEffect, useState } from 'react';
import { Table, Button, Space, Form, Input, Select, Modal, Tag, Popconfirm, DatePicker, Typography } from 'antd';
import { DeleteOutlined, EyeOutlined } from '@ant-design/icons';
import { auditApi } from '../../api/system';
import { useAuthStore } from '../../stores/authStore';
import { message as msg } from '../../utils/messageUtil';

const { RangePicker } = DatePicker;

export default function AuditLog() {
  const [form] = Form.useForm();
  const [data, setData] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailRecord, setDetailRecord] = useState(null);
  const has = useAuthStore((s) => s.hasPermission);

  const load = async () => {
    setLoading(true);
    try {
      const range = form.getFieldValue('range');
      const params = {
        page: page - 1,
        size,
        username: form.getFieldValue('username'),
        module: form.getFieldValue('module'),
        action: form.getFieldValue('action'),
        startTime: range && range[0] ? range[0].format('YYYY-MM-DD HH:mm:ss') : undefined,
        endTime: range && range[1] ? range[1].format('YYYY-MM-DD HH:mm:ss') : undefined,
      };
      const res = await auditApi.page(params);
      setData(res.records || []);
      setTotal(res.total || 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [page, size]);

  const onDelete = async (id) => {
    await auditApi.remove(id);
    msg.success('已删除');
    load();
  };

  const showDetail = (row) => {
    setDetailRecord(row);
    setDetailOpen(true);
  };

  const columns = [
    { title: '操作人', dataIndex: 'username', width: 100 },
    { title: 'IP', dataIndex: 'ip', width: 120 },
    { title: 'URL', dataIndex: 'requestUrl', ellipsis: true },
    { title: '模块', dataIndex: 'module', width: 180 },
    { title: '动作', dataIndex: 'action', width: 80, render: (a) => <Tag>{a}</Tag> },
    {
      title: '结果', dataIndex: 'result', width: 80,
      render: (r) => (r === 1 ? <Tag color="green">成功</Tag> : <Tag color="red">失败</Tag>),
    },
    { title: '耗时(ms)', dataIndex: 'durationMs', width: 90 },
    { title: '操作时间', dataIndex: 'operationTime', width: 170,
      render: (v) => (v ? String(v).replace('T', ' ') : v) },
    {
      title: '操作', key: 'action', width: 120,
      render: (_, row) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => showDetail(row)}>详情</Button>
          {has('system:audit:list') && (
            <Popconfirm title="确认删除该日志记录?" onConfirm={() => onDelete(row.id)}>
              <Button size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Form layout="inline" style={{ marginBottom: 16 }} form={form}
        onFinish={() => { setPage(1); load(); }}>
        <Form.Item name="username" label="操作人">
          <Input allowClear placeholder="操作人" style={{ width: 120 }} />
        </Form.Item>
        <Form.Item name="module" label="模块">
          <Input allowClear placeholder="如 system:user" style={{ width: 140 }} />
        </Form.Item>
        <Form.Item name="action" label="动作">
          <Select allowClear style={{ width: 120 }} options={[
            { value: 'add', label: 'add' },
            { value: 'edit', label: 'edit' },
            { value: 'delete', label: 'delete' },
            { value: 'query', label: 'query' },
          ]} />
        </Form.Item>
        <Form.Item name="range" label="时间范围">
          <RangePicker showTime />
        </Form.Item>
        <Button type="primary" htmlType="submit">查询</Button>
      </Form>

      <Table rowKey="id" loading={loading} dataSource={data} columns={columns}
        pagination={{ current: page, pageSize: size, total,
          onChange: (p, s) => { setPage(p); setSize(s); } }} />

      <Modal title="操作详情" open={detailOpen} footer={null} onCancel={() => setDetailOpen(false)} width={720} destroyOnHidden>
        {detailRecord && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Text>方法：{detailRecord.methodName}</Typography.Text>
            <Typography.Text>错误信息：{detailRecord.errorMsg || '无'}</Typography.Text>
            <Typography.Text>请求参数：</Typography.Text>
            <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 6, maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
              {detailRecord.requestParam || '（无）'}
            </pre>
          </Space>
        )}
      </Modal>
    </div>
  );
}
