import React, { useEffect, useState } from 'react';
import { Table, Button, Space, Form, Input, Modal, Tag, Popconfirm, Select, TreeSelect, Row, Col } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, AppstoreOutlined } from '@ant-design/icons';
import { menuApi } from '../../api/system';
import { useAuthStore } from '../../stores/authStore';
import { message as msg } from '../../utils/messageUtil';
import { ICON_MAP, ICON_OPTIONS, COMPONENT_OPTIONS } from '../../utils/iconMap';

const TYPE_LABEL = { 0: '目录', 1: '菜单', 2: '按钮' };
const TYPE_COLOR = { 0: 'blue', 1: 'green', 2: 'orange' };

function toTreeSelect(nodes, disableId) {
  return (nodes || []).map((n) => ({
    title: n.menuName,
    value: n.id,
    disabled: disableId != null && n.id === disableId,
    children: toTreeSelect(n.children, disableId),
  }));
}

// 按菜单名称过滤树，保留命中节点及其祖先
function filterTree(nodes, kw) {
  if (!kw) return nodes;
  const res = [];
  (nodes || []).forEach((n) => {
    const hit = (n.menuName || '').includes(kw);
    const kids = filterTree(n.children, kw);
    if (hit || kids.length) {
      // 如果目录本身命中，保留完整子树，方便查看结构
      res.push({ ...n, children: hit ? n.children : kids });
    }
  });
  return res;
}

export default function MenuManage() {
  const [form] = Form.useForm();
  const [data, setData] = useState([]);
  const [tree, setTree] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [keyword, setKeyword] = useState('');
  const menuType = Form.useWatch('menuType', form);
  const has = useAuthStore((s) => s.hasPermission);

  // 目录/按钮不需要前端组件，在类型切换时清空（不用 useEffect 避免打开弹框时 race）
  const onMenuTypeChange = (val) => {
    if (val !== 1) {
      form.setFieldValue('component', '');
    }
  };

  const load = async () => {
    setLoading(true);
    try {
      const treeData = await menuApi.tree().catch(() => []);
      setTree(treeData);
      setData(treeData);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openAdd = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ menuType: 1, status: 1, sort: 0, parentId: 0 });
    setModalOpen(true);
  };
  const openEdit = (row) => {
    setEditing(row);
    form.resetFields();
    form.setFieldsValue({ ...row, parentId: row.parentId || 0, component: row.component || '' });
    setModalOpen(true);
  };
  const onSubmit = async () => {
    const values = await form.validateFields();
    const payload = { ...values, parentId: values.parentId || 0 };
    if (editing) {
      await menuApi.update({ id: editing.id, ...payload });
    } else {
      await menuApi.add(payload);
    }
    msg.success('保存成功');
    setModalOpen(false);
    load();
  };
  const onDelete = async (id) => {
    await menuApi.remove(id);
    msg.success('已删除');
    load();
  };

  const columns = [
    {
      title: '菜单名称',
      dataIndex: 'menuName',
    },
    {
      title: '类型',
      dataIndex: 'menuType',
      width: 90,
      render: (t) => <Tag color={TYPE_COLOR[t]}>{TYPE_LABEL[t]}</Tag>,
    },
    { title: '路由', dataIndex: 'path' },
    {
      title: '组件',
      dataIndex: 'component',
      render: (v, row) => (row.menuType === 0 ? '-' : v || '-'),
    },
    {
      title: '权限标识',
      dataIndex: 'permission',
      render: (v) => (v ? <code>{v}</code> : '-'),
    },
    {
      title: '图标',
      dataIndex: 'icon',
      width: 140,
      align: 'center',
      render: (v, row) => {
        // 按钮类型显示为按钮预览：图标在按钮上展示，无图标时不加图标
        if (row.menuType === 2) {
          const iconNode = ICON_MAP[v];
          return (
            <Button size="small" icon={iconNode || undefined} disabled style={{ cursor: 'default' }}>
              {row.menuName}
            </Button>
          );
        }
        return ICON_MAP[v] || <AppstoreOutlined />;
      },
    },
    { title: '排序', dataIndex: 'sort', width: 70 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (s) => (s === 1 ? <Tag color="green">显示</Tag> : <Tag color="red">隐藏</Tag>),
    },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_, row) => (
        <Space>
          {has('system:menu:edit') && (
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
              编辑
            </Button>
          )}
          {has('system:menu:delete') && (
            <Popconfirm title="确认删除该菜单及其子项?" onConfirm={() => onDelete(row.id)}>
              <Button size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const filtered = filterTree(data, keyword);

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input
          allowClear
          placeholder="按菜单名称查询"
          style={{ width: 240 }}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        {has('system:menu:add') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>
            新增菜单
          </Button>
        )}
      </Space>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={filtered}
        columns={columns}
        pagination={false}
        size="small"
        childrenColumnName="children"
        defaultExpandAllRows
      />

      <Modal
        title={editing ? '编辑菜单' : '新增菜单'}
        open={modalOpen}
        onOk={onSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
        width={640}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="parentId" label="上级菜单">
                <TreeSelect
                  allowClear
                  placeholder="顶级菜单"
                  treeData={[{ title: '顶级菜单', value: 0, children: toTreeSelect(tree, editing?.id) }]}
                  treeDefaultExpandAll
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="menuName" label="菜单名称" rules={[{ required: true, message: '请输入菜单名称' }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="menuType" label="类型" rules={[{ required: true }]}>
                <Select
                  options={[
                    { value: 0, label: '目录' },
                    { value: 1, label: '菜单' },
                    { value: 2, label: '按钮' },
                  ]}
                  onChange={onMenuTypeChange}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="sort" label="排序">
                <Input type="number" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="path" label="路由路径">
                <Input placeholder="如 /system/users" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="component"
                label="前端组件"
                tooltip="对应 src/pages 下的相对路径，例如 System/UserManage；目录/按钮可不选"
              >
                <Select
                  allowClear
                  showSearch
                  placeholder="选择前端组件"
                  options={COMPONENT_OPTIONS}
                  optionFilterProp="label"
                  disabled={menuType === 0 || menuType === 2}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="permission" label="权限标识">
                <Input placeholder="如 system:user:list（按钮必填）" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="icon" label="图标">
                <Select
                  allowClear
                  showSearch
                  placeholder="选择图标"
                  options={ICON_OPTIONS}
                  optionFilterProp="value"
                  styles={{ popup: { root: { minWidth: 220 } } }}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="status" label="状态">
                <Select
                  options={[
                    { value: 1, label: '显示' },
                    { value: 0, label: '隐藏' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
