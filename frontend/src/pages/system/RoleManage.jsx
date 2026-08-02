import React, { useEffect, useState } from 'react';
import { Table, Button, Space, Form, Input, Modal, Tag, Popconfirm, Tooltip, Tree, Select, Row, Col } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SafetyOutlined } from '@ant-design/icons';
import { roleApi, menuApi } from '../../api/system';
import { useAuthStore } from '../../stores/authStore';
import { message as msg } from '../../utils/messageUtil';

// 菜单树 -> antd Tree 数据
function toTreeData(nodes) {
  return (nodes || []).map((n) => ({
    title: `${n.menuName}${n.permission ? `（${n.permission}）` : ''}`,
    key: String(n.id),
    children: toTreeData(n.children),
  }));
}

// checkStrictly 下 antd 不自动级联，这里手动实现：
// 勾选某节点 -> 其全部后代一并勾上；取消某节点 -> 其全部后代一并取消（所见即所得）
function cascadeTreeChecked(prevKeys, nextKeys, tree) {
  const allNodes = {};
  const walk = (nodes) => {
    (nodes || []).forEach((n) => {
      allNodes[String(n.key)] = n;
      walk(n.children);
    });
  };
  walk(tree);

  const collectDescendants = (key, acc) => {
    const node = allNodes[String(key)];
    if (node && node.children) {
      node.children.forEach((c) => {
        const ck = String(c.key);
        acc.push(ck);
        collectDescendants(ck, acc);
      });
    }
  };

  const prevSet = new Set(prevKeys);
  const nextSet = new Set(nextKeys);
  // 本次被勾选 / 被取消的节点（在可见树范围内 diff）
  const checkedNow = nextKeys.filter((k) => !prevSet.has(k));
  const uncheckedNow = prevKeys.filter((k) => !nextSet.has(k));

  const result = new Set(nextKeys);
  checkedNow.forEach((k) => {
    const desc = [];
    collectDescendants(k, desc);
    desc.forEach((d) => result.add(d));
  });
  uncheckedNow.forEach((k) => {
    const desc = [];
    collectDescendants(k, desc);
    desc.forEach((d) => result.delete(d));
  });

  return Array.from(result);
}

export default function RoleManage() {
  const [form] = Form.useForm();
  const [data, setData] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [menuRole, setMenuRole] = useState(null);
  const [rawMenuTree, setRawMenuTree] = useState([]);
  const [menuKeyword, setMenuKeyword] = useState('');
  const [checkedKeys, setCheckedKeys] = useState([]);
  const has = useAuthStore((s) => s.hasPermission);

  const load = async () => {
    setLoading(true);
    try {
      const res = await roleApi.page({ page: page - 1, size });
      setData(res.records || []);
      setTotal(res.total || 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [page, size]);

  const openAdd = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 1 });
    setModalOpen(true);
  };
  const openEdit = (row) => {
    setEditing(row);
    form.resetFields();
    form.setFieldsValue({ ...row });
    setModalOpen(true);
  };
  const onSubmit = async () => {
    const values = await form.validateFields();
    if (editing) {
      await roleApi.update({ id: editing.id, ...values });
    } else {
      await roleApi.add(values);
    }
    msg.success('保存成功');
    setModalOpen(false);
    load();
  };
  const onDelete = async (id) => {
    await roleApi.remove(id);
    msg.success('已删除');
    load();
  };

  const openMenu = async (row) => {
    setMenuRole(row);
    setMenuKeyword('');
    const tree = await menuApi.tree().catch(() => []);
    setRawMenuTree(tree || []);
    const menus = await roleApi.getMenus(row.id).catch(() => []);
    setCheckedKeys((menus || []).map(String));
    setMenuOpen(true);
  };

  // 按名称过滤菜单树（保留命中节点的祖先）
  const filterMenuNodes = (nodes, kw) => {
    if (!kw) return nodes;
    const res = [];
    (nodes || []).forEach((n) => {
      const hit = (n.menuName || '').includes(kw);
      const kids = filterMenuNodes(n.children, kw);
      if (hit || kids.length) res.push({ ...n, children: kids });
    });
    return res;
  };
  const menuTree = toTreeData(filterMenuNodes(rawMenuTree, menuKeyword));
  const onMenuOk = async () => {
    // 严格 1:1：UI 勾什么就保存什么（checkStrictly 下 UI 等于 DB 真实授权，不再展开父子联动）
    await roleApi.assignMenus(menuRole.id, checkedKeys.map(String));
    msg.success('菜单权限已保存');
    setMenuOpen(false);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '角色编码', dataIndex: 'roleCode', width: 140 },
    { title: '角色名称', dataIndex: 'roleName', render: (t, row) => (
        <span>
          {t}
          {row.roleCode && row.roleCode.toUpperCase() === 'ADMIN' && (
            <Tag color="red" style={{ marginLeft: 8 }}>内置管理员</Tag>
          )}
        </span>
      ) },
    { title: '备注', dataIndex: 'remark' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (s) => (s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag>),
    },
    {
      title: '操作',
      key: 'action',
      render: (_, row) => (
        <Space>
          {has('system:role:edit') && (
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
              编辑
            </Button>
          )}
          {has('system:role:assign') && (
            <Button size="small" icon={<SafetyOutlined />} onClick={() => openMenu(row)}>
              分配菜单
            </Button>
          )}
          {(() => {
            if (!has('system:role:delete')) return null;
            const isAdminRole = row.roleCode && row.roleCode.toUpperCase() === 'ADMIN';
            const onlyOne = total <= 1;
            if (isAdminRole || onlyOne) {
              const tip = isAdminRole
                ? '内置管理员(ADMIN)角色不可删除'
                : '系统至少需要保留一个角色';
              return (
                <Tooltip title={tip}>
                  <span>
                    <Button size="small" danger icon={<DeleteOutlined />} disabled>
                      删除
                    </Button>
                  </span>
                </Tooltip>
              );
            }
            return (
              <Popconfirm
                title={`确认删除角色「${row.roleName}」？`}
                description="删除后该角色下所有用户将失去对应权限，且不可恢复。"
                okText="删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => onDelete(row.id)}
              >
                <Button size="small" danger icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            );
          })()}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        {has('system:role:add') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>
            新增角色
          </Button>
        )}
      </Space>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={data}
        columns={columns}
        pagination={{
          current: page,
          pageSize: size,
          total,
          onChange: (p, s) => {
            setPage(p);
            setSize(s);
          },
        }}
      />

      <Modal
        title={editing ? '编辑角色' : '新增角色'}
        open={modalOpen}
        onOk={onSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
        width={640}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="roleCode" label="角色编码" rules={[{ required: true, message: '请输入角色编码' }]}>
                <Input disabled={!!editing} placeholder="如 ADMIN" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="roleName" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="remark" label="备注">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="status" label="状态">
                <Select
                  options={[
                    { value: 1, label: '启用' },
                    { value: 0, label: '禁用' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={`分配菜单权限 - ${menuRole?.roleName || ''}`}
        open={menuOpen}
        onOk={onMenuOk}
        onCancel={() => setMenuOpen(false)}
        width={520}
        destroyOnHidden
      >
        <Input.Search
          allowClear
          placeholder="按菜单名称查询"
          style={{ marginBottom: 12 }}
          value={menuKeyword}
          onChange={(e) => setMenuKeyword(e.target.value)}
        />
        <div style={{ maxHeight: 360, overflowY: 'auto', paddingRight: 4 }}>
          <Tree
            key={menuKeyword}
            checkable
            checkStrictly
            defaultExpandAll
            treeData={menuTree}
            checkedKeys={checkedKeys}
            onCheck={(info) => {
              const nextArr = (Array.isArray(info) ? info : info.checked).map(String);
              setCheckedKeys(cascadeTreeChecked(checkedKeys, nextArr, menuTree));
            }}
          />
        </div>
      </Modal>
    </div>
  );
}
