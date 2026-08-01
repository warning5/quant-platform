import React, { useEffect, useState, useCallback } from 'react';
import {
  Layout, Card, Table, Button, Space, Modal, Form, Input, InputNumber, Switch,
  Tag, Popconfirm, App, Typography, Select,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import configApi from '../../api/config';
import { useAuthStore } from '../../stores/authStore';

const { Sider, Content } = Layout;
const { Text } = Typography;

const TYPE_OPTIONS = [
  { value: 'STRING', label: '文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'BOOLEAN', label: '开关' },
  { value: 'JSON', label: 'JSON' },
];

export default function ConfigCenter() {
  const { message } = App.useApp();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canEdit = hasPermission('system:config:edit');
  const canAdd = hasPermission('system:config:add');
  const canDelete = hasPermission('system:config:delete');

  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await configApi.list();
      setList(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error('加载配置失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => { load(); }, [load]);

  const openAdd = () => {
    form.resetFields();
    form.setFieldsValue({ configType: 'STRING', enabled: true, sort: 0, configGroup: 'SYSTEM' });
    setEditing(null);
    setModalOpen(true);
  };

  const openEdit = (row) => {
    form.resetFields();
    form.setFieldsValue({
      id: row.id,
      configKey: row.configKey,
      configValue: row.configValue,
      configGroup: row.configGroup,
      configLabel: row.configLabel,
      configType: row.configType,
      enabled: row.enabled === 1,
      sort: row.sort,
      remark: row.remark || '',
    });
    setEditing(row);
    setModalOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    const payload = { ...v, enabled: v.enabled ? 1 : 0 };
    if (editing) {
      await configApi.update(payload);
      message.success('配置已更新');
    } else {
      await configApi.add(payload);
      message.success('配置已新增');
    }
    setModalOpen(false);
    await load();
  };

  const remove = async (id) => {
    await configApi.remove(id);
    message.success('配置已删除');
    await load();
  };

  const columns = [
    { title: '配置键', dataIndex: 'configKey', key: 'configKey', width: 200, render: (t) => <Text code>{t}</Text> },
    { title: '标签', dataIndex: 'configLabel', key: 'configLabel', width: 160 },
    {
      title: '分组', dataIndex: 'configGroup', key: 'configGroup', width: 110,
      render: (g) => <Tag color="blue">{g}</Tag>,
    },
    {
      title: '值', dataIndex: 'configValue', key: 'configValue', ellipsis: true,
      render: (v, row) => row.configType === 'BOOLEAN'
        ? <Tag color={v === 'true' ? 'green' : 'default'}>{v}</Tag>
        : <Text>{v}</Text>,
    },
    {
      title: '类型', dataIndex: 'configType', key: 'configType', width: 90,
      render: (t) => <Tag>{TYPE_OPTIONS.find((o) => o.value === t)?.label || t}</Tag>,
    },
    {
      title: '状态', dataIndex: 'enabled', key: 'enabled', width: 80,
      render: (e) => <Tag color={e === 1 ? 'success' : 'default'}>{e === 1 ? '启用' : '禁用'}</Tag>,
    },
    { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
    {
      title: '操作', key: 'action', width: 140, fixed: 'right',
      render: (_, row) => (
        <Space>
          {canEdit && <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>}
          {canDelete && (
            <Popconfirm title="确认删除该配置?" onConfirm={() => remove(row.id)}>
              <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Layout style={{ background: 'transparent' }}>
      <Content>
        <Card
          title="参数配置中心"
          extra={(
            <Space>
              <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
              {canAdd && <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增配置</Button>}
            </Space>
          )}
        >
          <Table
            rowKey="id"
            columns={columns}
            dataSource={list}
            loading={loading}
            pagination={false}
            scroll={{ x: 900 }}
            size="middle"
          />
        </Card>
      </Content>

      <Modal
        title={editing ? '编辑配置' : '新增配置'}
        open={modalOpen}
        onOk={submit}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Form.Item label="配置键" name="configKey" rules={[{ required: true, message: '请输入配置键' }]}>
            <Input disabled={!!editing} placeholder="如 rate_limit_qps" />
          </Form.Item>
          <Form.Item label="显示标签" name="configLabel" rules={[{ required: true, message: '请输入标签' }]}>
            <Input placeholder="如 全局限流 QPS 上限" />
          </Form.Item>
          <Form.Item label="分组" name="configGroup" rules={[{ required: true, message: '请输入分组' }]}>
            <Input placeholder="如 SYSTEM / SCHEDULE" />
          </Form.Item>
          <Form.Item label="类型" name="configType" rules={[{ required: true }]}>
            <Select options={TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item label="配置值" name="configValue" rules={[{ required: true, message: '请输入配置值' }]}>
            <Input.TextArea rows={2} placeholder="字符串 / 数字 / true|false / JSON" />
          </Form.Item>
          <Form.Item label="排序" name="sort">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item label="备注" name="remark">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
}
