import React, { useEffect, useState, useCallback } from 'react';
import {
  Card, Tree, Button, Space, Form, Input, InputNumber, TreeSelect, Switch,
  Popconfirm, App, Tag, Empty,
} from 'antd';
import { PlusOutlined, DeleteOutlined, ReloadOutlined, ApartmentOutlined } from '@ant-design/icons';
import departmentApi from '../../api/department';

// 后端树（deptName/id/children）→ antd Tree（title/key/children）
const toDisplayTree = (nodes) =>
  (nodes || []).map((n) => ({
    title: (
      <span>
        {n.deptName}
        {n.status === 0 && <Tag color="default" style={{ marginLeft: 6 }}>禁用</Tag>}
      </span>
    ),
    key: n.id,
    deptName: n.deptName,
    parentId: n.parentId,
    sort: n.sort,
    status: n.status,
    children: n.children && n.children.length ? toDisplayTree(n.children) : undefined,
  }));

// 移除某节点及其子树（编辑时避免选自己/子孙做上级）
const removeSubtree = (nodes, excludeId) =>
  (nodes || [])
    .filter((n) => n.id !== excludeId)
    .map((n) => ({
      ...n,
      children: n.children ? removeSubtree(n.children, excludeId) : undefined,
    }));

export default function DepartmentManage() {
  const { message } = App.useApp();
  const [rawTree, setRawTree] = useState([]);          // 后端原始树（含 deptName/id/children）
  const [selected, setSelected] = useState(null);      // 当前选中的节点（原始对象）
  const [editingId, setEditingId] = useState(null);    // 正在编辑的部门 id；null = 新增
  const [parentForNew, setParentForNew] = useState(0);  // 新增时的上级（0=根）
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const loadTree = useCallback(async () => {
    setLoading(true);
    try {
      const data = await departmentApi.tree();
      setRawTree(data || []);
    } catch (e) {
      // 拦截器已弹错误提示
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadTree(); }, [loadTree]);

  const onSelect = (keys, info) => {
    if (!keys.length) return;
    const node = info.node;
    setEditingId(keys[0]);
    setSelected(node);
    form.setFieldsValue({
      deptName: node.deptName,
      parentId: node.parentId || 0,
      sort: node.sort ?? 0,
      status: node.status !== 0,
    });
  };

  const resetForm = (parentId = 0) => {
    setEditingId(null);
    setSelected(null);
    setParentForNew(parentId);
    form.resetFields();
    form.setFieldsValue({ parentId, sort: 0, status: true });
  };

  const onSubmit = async () => {
    const values = await form.validateFields();
    const payload = {
      deptName: values.deptName,
      parentId: values.parentId || 0,
      sort: values.sort ?? 0,
      status: values.status ? 1 : 0,
    };
    try {
      if (editingId) {
        await departmentApi.update({ ...payload, id: editingId });
        message.success('更新成功');
      } else {
        await departmentApi.create(payload);
        message.success('新增成功');
      }
      resetForm(values.parentId || 0);
      loadTree();
    } catch (e) {
      // 拦截器已弹错误提示
    }
  };

  const onDelete = async () => {
    if (!editingId) return;
    try {
      await departmentApi.remove(editingId);
      message.success('删除成功');
      resetForm();
      loadTree();
    } catch (e) {
      // 拦截器已弹错误提示（如“存在子部门”）
    }
  };

  // 上级部门 TreeSelect 选项：根 + 业务树（编辑时排除自身与子孙）
  const parentOptions = [
    { id: 0, deptName: '（根部门 / 默认部门）', children: editingId ? removeSubtree(rawTree, editingId) : rawTree },
  ];

  const displayTree = toDisplayTree(rawTree);

  return (
    <div style={{ display: 'flex', gap: 16, height: '100%' }}>
      <Card
        title={
          <Space>
            <ApartmentOutlined />
            部门组织树
          </Space>
        }
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadTree}>刷新</Button>}
        style={{ width: 340, flexShrink: 0 }}
        styles={{ body: { padding: 8, overflow: 'auto', maxHeight: 'calc(100vh - 160px)' } }}
      >
        {displayTree.length ? (
          <Tree
            treeData={displayTree}
            onSelect={onSelect}
            selectedKeys={selected ? [selected.id] : []}
            defaultExpandAll
            blockNode
          />
        ) : (
          <Empty description="暂无部门" />
        )}
        <Space style={{ marginTop: 12 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => resetForm(0)}>
            新增根部门
          </Button>
          <Button icon={<PlusOutlined />} disabled={!selected} onClick={() => resetForm(selected.id)}>
            新增子部门
          </Button>
        </Space>
      </Card>

      <Card
        title={editingId ? `编辑部门${selected ? '：' + selected.deptName : ''}` : '新增部门'}
        style={{ flex: 1 }}
        extra={
          editingId ? (
            <Popconfirm title="确认删除该部门？（存在子部门时会被拒绝）" onConfirm={onDelete} okText="删除" cancelText="取消">
              <Button danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          ) : null
        }
      >
        <Form
          form={form}
          layout="vertical"
          style={{ maxWidth: 480 }}
          initialValues={{ parentId: 0, sort: 0, status: true }}
        >
          <Form.Item name="deptName" label="部门名称" rules={[{ required: true, message: '请输入部门名称' }]}>
            <Input placeholder="如 量化研究部" />
          </Form.Item>
          <Form.Item name="parentId" label="上级部门">
            <TreeSelect
              treeData={parentOptions}
              treeDefaultExpandAll
              placeholder="请选择上级部门"
              allowClear={false}
              treeNodeLabelProp="deptName"
              fieldNames={{ label: 'deptName', value: 'id', children: 'children' }}
            />
          </Form.Item>
          <Form.Item name="sort" label="排序号">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="status" label="状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
          <Space>
            <Button type="primary" onClick={onSubmit}>
              {editingId ? '保存修改' : '创建部门'}
            </Button>
            <Button onClick={() => resetForm(parentForNew)}>重置</Button>
          </Space>
        </Form>
      </Card>
    </div>
  );
}
