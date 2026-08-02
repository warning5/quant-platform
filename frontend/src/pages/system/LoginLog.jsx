import React, { useEffect, useState } from 'react';
import { Table, Button, Space, Form, Input, Select, Modal, Tag, DatePicker, Typography } from 'antd';
import { EyeOutlined, DeleteOutlined } from '@ant-design/icons';
import { auditApi } from '../../api/system';
import { useAuthStore } from '../../stores/authStore';
import { message as msg } from '../../utils/messageUtil';

const { RangePicker } = DatePicker;

export default function LoginLog() {
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
        module: 'auth',
        username: form.getFieldValue('username'),
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
    {
      title: '登录时间', dataIndex: 'operationTime', width: 170,
      render: (v) => (v ? String(v).replace('T', ' ') : v),
    },
    { title: '账号', dataIndex: 'username', width: 120 },
    { title: 'IP', dataIndex: 'ip', width: 130 },
    { title: 'User-Agent', dataIndex: 'userAgent', width: 240, ellipsis: true },
    {
      title: '结果', dataIndex: 'action', width: 110,
      render: (a) => (a === 'login_success'
        ? <Tag color="green">登录成功</Tag>
        : <Tag color="red">登录失败</Tag>),
    },
    { title: '失败原因', dataIndex: 'errorMsg', width: 160, ellipsis: true, render: (v) => v || '-' },
    {
      title: '操作', key: 'action', width: 100,
      render: (_, row) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => showDetail(row)}>详情</Button>
          {has('system:audit:delete') && (
            <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(row.id)} />
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Form layout="inline" style={{ marginBottom: 16 }} form={form}
        onFinish={() => { setPage(1); load(); }}>
        <Form.Item name="username" label="账号">
          <Input allowClear placeholder="账号" style={{ width: 120 }} />
        </Form.Item>
        <Form.Item name="action" label="结果">
          <Select allowClear style={{ width: 120 }} placeholder="全部"
            options={[
              { value: 'login_success', label: '登录成功' },
              { value: 'login_fail', label: '登录失败' },
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

      <Modal title="登录详情" open={detailOpen} footer={null} onCancel={() => setDetailOpen(false)} width={720} destroyOnHidden>
        {detailRecord && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Text>账号：{detailRecord.username}</Typography.Text>
            <Typography.Text>IP：{detailRecord.ip}</Typography.Text>
            <Typography.Text>User-Agent：{detailRecord.userAgent || '（无）'}</Typography.Text>
            <Typography.Text>结果：{detailRecord.action === 'login_success' ? '登录成功' : '登录失败'}</Typography.Text>
            <Typography.Text>失败原因：{detailRecord.errorMsg || '无'}</Typography.Text>
            <Typography.Text>登录时间：{detailRecord.operationTime ? String(detailRecord.operationTime).replace('T', ' ') : '-'}</Typography.Text>
          </Space>
        )}
      </Modal>
    </div>
  );
}
