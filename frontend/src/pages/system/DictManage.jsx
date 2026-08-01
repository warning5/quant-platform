import React, { useEffect, useState, useCallback } from 'react';
import {
  Card, Table, Button, Space, Modal, Form, Input, InputNumber, Switch,
  Tag, Popconfirm, App, Empty, Typography, Select, Row, Col,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import dictApi from '../../api/dict';

const { Text } = Typography;

export default function DictManage() {
  const { message } = App.useApp();
  const [types, setTypes] = useState([]);
  const [selected, setSelected] = useState(null);
  const [dataList, setDataList] = useState([]);
  const [loadingTypes, setLoadingTypes] = useState(false);
  const [loadingData, setLoadingData] = useState(false);

  // Modal key：每次打开递增 → 子组件（含内部 Form.useForm）整体销毁重建 → store 全新无残留
  const [typeModalKey, setTypeModalKey] = useState(0);
  const [typeModalOpen, setTypeModalOpen] = useState(false);
  const [editingType, setEditingType] = useState(null);
  const [typeInitialValues, setTypeInitialValues] = useState(null);

  const [dataModalKey, setDataModalKey] = useState(0);
  const [dataModalOpen, setDataModalOpen] = useState(false);
  const [editingData, setEditingData] = useState(null);
  const [dataInitialValues, setDataInitialValues] = useState(null);

  const loadTypes = useCallback(async () => {
    setLoadingTypes(true);
    try {
      const list = await dictApi.listTypes();
      setTypes(Array.isArray(list) ? list : []);
      setSelected((prev) => prev || (Array.isArray(list) && list[0] ? list[0] : null));
      return Array.isArray(list) ? list : [];   // 返回最新列表供调用方同步使用
    } catch (e) {
      message.error('加载字典类型失败');
      return [];
    } finally {
      setLoadingTypes(false);
    }
  }, [message]);

  const loadData = useCallback(async (type) => {
    if (!type) { setDataList([]); return; }
    setLoadingData(true);
    try {
      const list = await dictApi.listData(type.dictType, true);
      setDataList(Array.isArray(list) ? list : []);
    } catch (e) {
      message.error('加载字典项失败');
    } finally {
      setLoadingData(false);
    }
  }, [message]);

  useEffect(() => { loadTypes(); }, [loadTypes]);
  useEffect(() => { if (selected) loadData(selected); }, [selected, loadData]);

  // ---------- 类型弹窗控制 ----------
  const openTypeModal = () => {
    setEditingType(null);
    setTypeInitialValues({ status: true, sort: 0 });
    setTypeModalKey((k) => k + 1);   // 新 key → 子组件销毁重建 → 全新 Form store
    setTypeModalOpen(true);
  };
  const openEditType = (t) => {
    setEditingType(t);
    setTypeInitialValues({
      id: t.id,
      dictType: t.dictType,
      typeName: t.typeName,
      description: t.description || '',
      sort: t.sort ?? 0,
      status: t.status === 1,
    });
    setTypeModalKey((k) => k + 1);   // 新 key → 子组件销毁重建 → 全新 Form store
    setTypeModalOpen(true);
  };
  // 由子组件提交时回调，v 为校验后的字段值
  const submitType = async (v) => {
    const payload = { ...v, status: v.status ? 1 : 0 };
    if (editingType) {
      await dictApi.updateType(payload);
      message.success('字典类型已更新');
    } else {
      await dictApi.addType(payload);
      message.success('字典类型已新增');
    }
    setTypeModalOpen(false);
    setEditingType(null);
    const freshTypes = await loadTypes();
    // 从刷新后的列表中重新匹配 selected，确保状态/名称同步
    if (selected) {
      const updated = freshTypes.find((t) => t.dictType === selected.dictType);
      if (updated) setSelected(updated);
    }
  };

  // ---------- 数据项弹窗控制 ----------
  const openAddData = () => {
    setEditingData(null);
    setDataInitialValues({
      dictType: selected.dictType,
      dictValue: '',
      dictLabel: '',
      sort: 0,
      color: '',
      extJson: '',
      remark: '',
      status: true,
    });
    setDataModalKey((k) => k + 1);   // 新 key → 子组件销毁重建 → 全新 Form store
    setDataModalOpen(true);
  };
  const openEditData = (row) => {
    setEditingData(row);
    setDataInitialValues({
      id: row.id,
      dictType: row.dictType,
      dictValue: row.dictValue,
      dictLabel: row.dictLabel,
      sort: row.sort,
      color: row.color || '',
      extJson: row.extJson || '',
      remark: row.remark || '',
      status: row.status === 1,
    });
    setDataModalKey((k) => k + 1);   // 新 key → 子组件销毁重建 → 全新 Form store
    setDataModalOpen(true);
  };
  const submitData = async (v) => {
    const payload = {
      dictType: v.dictType,
      dictValue: v.dictValue,
      dictLabel: v.dictLabel,
      sort: v.sort,
      color: v.color || null,
      extJson: v.extJson || null,
      remark: v.remark || '',
      status: v.status ? 1 : 0,
    };
    if (editingData) {
      await dictApi.updateData({ ...payload, id: editingData.id });
      message.success('字典项已更新');
    } else {
      await dictApi.addData(payload);
      message.success('字典项已新增');
    }
    setDataModalOpen(false);
    await loadData(selected);
  };
  const removeData = async (id) => {
    await dictApi.deleteData(id);
    message.success('已删除');
    await loadData(selected);
  };

  const dataColumns = [
    { title: '值(dictValue)', dataIndex: 'dictValue', width: 140 },
    { title: '标签', dataIndex: 'dictLabel', width: 120 },
    { title: '排序', dataIndex: 'sort', width: 70 },
    {
      title: '颜色', dataIndex: 'color', width: 110,
      render: (c) => (c ? <Tag color={c}>{c}</Tag> : <Text type="secondary">-</Text>),
    },
    {
      title: '扩展', dataIndex: 'extJson', ellipsis: true,
      render: (e) => (e ? <Text style={{ fontSize: 12 }}>{e}</Text> : '-'),
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (s) => (s === 1 ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>),
    },
    {
      title: '操作', width: 130, fixed: 'right',
      render: (_, row) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditData(row)}>编辑</Button>
          <Popconfirm title="确认删除该字典项?" onConfirm={() => removeData(row.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // 类型选项：按分类分组
  const typeOptions = types.map((t) => ({
    value: t.dictType,
    label: `${t.typeName} (${t.dictType})`,
  }));

  return (
    <div style={{ padding: 16 }}>
      {/* 顶栏：类型选择 + 操作 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap size="middle" style={{ width: '100%' }}>
          <div>
            <Text strong style={{ marginRight: 8 }}>字典类型：</Text>
            <Select
              showSearch
              optionFilterProp="label"
              value={selected?.dictType ?? undefined}
              onChange={(val) => setSelected(types.find((t) => t.dictType === val) || null)}
              placeholder="请选择字典类型"
              style={{ minWidth: 280 }}
              loading={loadingTypes}
              options={typeOptions}
            />
          </div>
          <Button type="primary" icon={<PlusOutlined />} onClick={openTypeModal}>新增类型</Button>
          {selected && (
            <>
              <Button icon={<EditOutlined />} onClick={() => openEditType(selected)}>编辑类型</Button>
              <Button icon={<ReloadOutlined />} onClick={() => loadData(selected)}>刷新</Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={openAddData}>新增字典项</Button>
            </>
          )}
        </Space>
      </Card>

      {/* 内容区：数据项表格 */}
      {!selected ? (
        <Empty description="请在上方选择字典类型" />
      ) : (
        <Card
          size="small"
          title={
            <Space>
              <Text strong>{selected.typeName}</Text>
              <Text type="secondary">{selected.dictType}</Text>
              <Tag color={selected.status === 1 ? 'green' : 'default'}>
                {selected.status === 1 ? '启用' : '禁用'}
              </Tag>
            </Space>
          }
        >
          <Table
            rowKey="id"
            size="small"
            loading={loadingData}
            columns={dataColumns}
            dataSource={dataList}
            pagination={false}
            scroll={{ x: 760 }}
          />
        </Card>
      )}

      {/* 新增/编辑类型弹窗 — key 变化时 TypeModal 整体销毁重建，内部 Form.useForm 全新 */}
      <TypeModal
        key={typeModalKey}
        open={typeModalOpen}
        title={editingType ? '编辑字典类型' : '新增字典类型'}
        editingType={editingType}
        initialValues={typeInitialValues}
        onCancel={() => setTypeModalOpen(false)}
        onSubmit={submitType}
      />

      {/* 新增/编辑字典项弹窗 — key 变化时 DataModal 整体销毁重建，内部 Form.useForm 全新 */}
      <DataModal
        key={dataModalKey}
        open={dataModalOpen}
        title={editingData ? '编辑字典项' : '新增字典项'}
        editingData={editingData}
        initialValues={dataInitialValues}
        onCancel={() => setDataModalOpen(false)}
        onSubmit={submitData}
      />
    </div>
  );
}

// 类型弹窗：独立组件内部调用 Form.useForm()，随父组件 key 变化销毁重建 → store 永远全新
function TypeModal({ open, title, editingType, initialValues, onCancel, onSubmit }) {
  const [form] = Form.useForm();
  const handleOk = async () => {
    const v = await form.validateFields();
    onSubmit(v);
  };
  return (
    <Modal
      title={title}
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={initialValues}>
        <Form.Item name="dictType" label="类型编码" rules={[{ required: true, message: '请输入类型编码，如 SLA_SEVERITY' }]}>
          <Input placeholder="SLA_SEVERITY" disabled={!!editingType} />
        </Form.Item>
        <Form.Item name="typeName" label="类型名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="SLA严重级别" />
        </Form.Item>
        <Form.Item name="description" label="说明">
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item name="sort" label="排序">
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="status" label="启用" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}

// 字典项弹窗：同上，独立组件 + 内部 Form.useForm()
function DataModal({ open, title, editingData, initialValues, onCancel, onSubmit }) {
  const [form] = Form.useForm();
  const handleOk = async () => {
    const v = await form.validateFields();
    onSubmit(v);
  };
  return (
    <Modal
      title={title}
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="dictType" label="所属类型" rules={[{ required: true }]}>
              <Input disabled />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="dictValue" label="值(dictValue)" rules={[{ required: true, message: '业务代码读取的值' }]}>
              <Input disabled={!!editingData} placeholder="HIGH" />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="dictLabel" label="显示标签" rules={[{ required: true }]}>
              <Input placeholder="高" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="sort" label="排序（升序，越小越靠前）">
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="color" label="颜色（AntD 色名或 hex）">
              <Input placeholder="red / #ff4d4f" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="status" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="extJson" label="扩展属性(JSON)">
          <Input.TextArea rows={2} placeholder='{"notifyLevel":1}' />
        </Form.Item>
        <Form.Item name="remark" label="备注">
          <Input />
        </Form.Item>
      </Form>
    </Modal>
  );
}
