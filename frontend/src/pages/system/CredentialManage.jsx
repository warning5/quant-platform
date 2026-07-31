import React, { useEffect, useState } from 'react';
import { Table, Button, Space, Form, Input, Select, Modal, Tag, Popconfirm, Row, Col } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { credentialApi } from '../../api/system';
import { useAuthStore } from '../../stores/authStore';
import { message as msg } from '../../utils/messageUtil';

const CATEGORIES = [
  { value: 'llm', label: '大模型 LLM' },
  { value: 'wechat', label: '微信' },
  { value: 'notification', label: '通知推送' },
  { value: 'db', label: '数据库' },
];

export default function CredentialManage() {
  const [form] = Form.useForm();
  const [modalForm] = Form.useForm();
  const [data, setData] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const has = useAuthStore((s) => s.hasPermission);

  const load = async () => {
    setLoading(true);
    try {
      const res = await credentialApi.page({
        page: page - 1,
        size,
        category: form.getFieldValue('category'),
        keyword: form.getFieldValue('keyword'),
      });
      setData(res.records || []);
      setTotal(res.total || 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [page, size]);

  const openAdd = () => {
    setEditing(null);
    modalForm.resetFields();
    modalForm.setFieldsValue({ category: 'llm', enabled: 1 });
    setModalOpen(true);
  };
  const openEdit = async (row) => {
    setEditing(row);
    const d = await credentialApi.detail(row.id).catch(() => null);
    modalForm.resetFields();
    modalForm.setFieldsValue({
      credentialKey: row.credentialKey,
      name: row.name,
      category: row.category,
      enabled: row.enabled,
      remark: row.remark,
      value: d ? d.value : '',
    });
    setModalOpen(true);
  };
  const onSubmit = async () => {
    const values = await modalForm.validateFields();
    const payload = { ...values };
    if (editing) {
      payload.id = editing.id;
      await credentialApi.update(payload);
    } else {
      await credentialApi.add(payload);
    }
    msg.success('保存成功');
    setModalOpen(false);
    load();
  };
  const onDelete = async (id) => {
    await credentialApi.remove(id);
    msg.success('已删除');
    load();
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '标识', dataIndex: 'credentialKey', width: 180 },
    { title: '名称', dataIndex: 'name' },
    { title: '分类', dataIndex: 'category', width: 110, render: (c) => {
      const m = CATEGORIES.find((x) => x.value === c);
      return m ? m.label : c;
    } },
    { title: '密文掩码', dataIndex: 'maskedValue', width: 160, render: (v) => <Tag>{v || '—'}</Tag> },
    { title: '状态', dataIndex: 'enabled', width: 80, render: (e) => (e === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag>) },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', width: 170 },
    {
      title: '操作', key: 'action', width: 130,
      render: (_, row) => (
        <Space>
          {has('system:credential:edit') && (
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
          )}
          {has('system:credential:delete') && (
            <Popconfirm title="确认删除该凭证?" onConfirm={() => onDelete(row.id)}>
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
        <Form.Item name="category" label="分类">
          <Select allowClear style={{ width: 140 }} options={CATEGORIES} />
        </Form.Item>
        <Form.Item name="keyword" label="关键字">
          <Input allowClear placeholder="标识/名称" />
        </Form.Item>
        <Button type="primary" htmlType="submit">查询</Button>
        {has('system:credential:add') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd} style={{ marginLeft: 8 }}>新增凭证</Button>
        )}
      </Form>

      <Table rowKey="id" loading={loading} dataSource={data} columns={columns}
        pagination={{ current: page, pageSize: size, total,
          onChange: (p, s) => { setPage(p); setSize(s); } }} />

      <Modal title={editing ? '编辑凭证' : '新增凭证'} open={modalOpen}
        onOk={onSubmit} onCancel={() => setModalOpen(false)} destroyOnHidden width={640}>
        <Form form={modalForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="credentialKey" label="凭证标识" rules={[{ required: true, message: '请输入标识' }]}>
                <Input disabled={!!editing} placeholder="如 DEEPSEEK_API_KEY" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="name" label="名称">
                <Input placeholder="展示名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="category" label="分类" rules={[{ required: true, message: '请选择分类' }]}>
                <Select options={CATEGORIES} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="enabled" label="状态">
                <Select options={[{ value: 1, label: '启用' }, { value: 0, label: '禁用' }]} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="value" label={editing ? '密钥值（留空不改）' : '密钥值'} rules={editing ? [] : [{ required: true, message: '请输入密钥值' }]}>
                <Input.Password placeholder={editing ? '留空则保持原值' : '明文输入，保存后加密存储'} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="remark" label="备注">
                <Input.TextArea rows={2} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
