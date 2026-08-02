import React, { useEffect, useState } from 'react';
import { Table, Button, Space, Form, Input, Select, Modal, Tag, Popconfirm, Tooltip, Row, Col, TreeSelect } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, KeyOutlined } from '@ant-design/icons';
import { userApi, roleApi } from '../../api/system';
import departmentApi from '../../api/department';
import { useAuthStore } from '../../stores/authStore';
import { message as msg } from '../../utils/messageUtil';

export default function UserManage() {
  const [form] = Form.useForm();
  const [modalForm] = Form.useForm();
  const [pwForm] = Form.useForm();
  const [data, setData] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [pwOpen, setPwOpen] = useState(false);
  const [pwUser, setPwUser] = useState(null);
  const [roles, setRoles] = useState([]);
  const [deptTree, setDeptTree] = useState([]);
  const [userRoleMap, setUserRoleMap] = useState({});
  const has = useAuthStore((s) => s.hasPermission);
  const currentUserId = useAuthStore((s) => s.userId);

  const load = async () => {
    setLoading(true);
    try {
      const [res, roleList] = await Promise.all([
        userApi.page({
          page: page - 1,
          size,
          username: form.getFieldValue('username'),
          nickname: form.getFieldValue('nickname'),
          status: form.getFieldValue('status'),
        }),
        roleApi.list().catch(() => []),
      ]);
      setData(res.records || []);
      setTotal(res.total || 0);
      const roleMapById = new Map((roleList || []).map((r) => [r.id, r]));
      const roleMap = {};
      await Promise.all(
        (res.records || []).map(async (u) => {
          const ids = await userApi.getRoles(u.id).catch(() => []);
          roleMap[u.id] = ids.map((id) => roleMapById.get(id)).filter(Boolean);
        })
      );
      setUserRoleMap(roleMap);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [page, size]);
  useEffect(() => {
    roleApi.list().then((r) => setRoles(r || [])).catch(() => {});
  }, []);
  useEffect(() => {
    departmentApi.tree().then((t) => setDeptTree(t || [])).catch(() => {});
  }, []);

  const openAdd = () => {
    setEditing(null);
    modalForm.resetFields();
    modalForm.setFieldsValue({ status: 1 });
    setModalOpen(true);
  };
  const openEdit = async (row) => {
    setEditing(row);
    const roleIds = await userApi.getRoles(row.id).catch(() => []);
    modalForm.resetFields();
    modalForm.setFieldsValue({ ...row, roleIds });
    setModalOpen(true);
  };
  const onSubmit = async () => {
    const values = await modalForm.validateFields();
    if (editing) {
      await userApi.update({ id: editing.id, ...values });
    } else {
      await userApi.add(values);
    }
    msg.success('保存成功');
    setModalOpen(false);
    load();
  };
  const onDelete = async (id) => {
    await userApi.remove(id);
    msg.success('已删除');
    load();
  };
  const openReset = (row) => {
    setPwUser(row);
    pwForm.resetFields();
    setPwOpen(true);
  };
  const onReset = async () => {
    const v = await pwForm.validateFields();
    await userApi.resetPassword(pwUser.id, v.password);
    msg.success('密码已重置');
    setPwOpen(false);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '账号', dataIndex: 'username' },
    { title: '昵称', dataIndex: 'nickname' },
    { title: '邮箱', dataIndex: 'email' },
    { title: '手机', dataIndex: 'phone' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (s) => (s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag>),
    },
    {
      title: '角色',
      dataIndex: 'id',
      width: 160,
      render: (id) =>
        (userRoleMap[id] || []).map((r) => (
          <Tag key={r.id} color={r.roleCode && r.roleCode.toUpperCase() === 'ADMIN' ? 'red' : 'blue'}>
            {r.roleName}
          </Tag>
        )),
    },
    { title: '创建时间', dataIndex: 'createTime', width: 170 },
    {
      title: '操作',
      key: 'action',
      render: (_, row) => (
        <Space>
          {has('system:user:edit') && (
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>
              编辑
            </Button>
          )}
          {has('system:user:reset') && (
            <Button size="small" icon={<KeyOutlined />} onClick={() => openReset(row)}>
              改密
            </Button>
          )}
          {(() => {
            if (!has('system:user:delete')) return null;
            const myRoles = userRoleMap[row.id] || [];
            const isSelf = row.id === currentUserId;
            const isAdmin = myRoles.some((r) => r.roleCode && r.roleCode.toUpperCase() === 'ADMIN');
            if (isSelf || isAdmin) {
              const tip = isSelf
                ? '不能删除当前登录的用户'
                : '管理员账户请先解除角色后再删除';
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
                title={`确认删除用户「${row.username}」？`}
                description="删除后该用户的所有会话与角色关联将一并清除，且不可恢复。"
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
      <Form
        layout="inline"
        style={{ marginBottom: 16 }}
        form={form}
        onFinish={() => {
          setPage(1);
          load();
        }}
      >
        <Form.Item name="username" label="账号">
          <Input allowClear placeholder="账号" />
        </Form.Item>
        <Form.Item name="nickname" label="昵称">
          <Input allowClear placeholder="昵称" />
        </Form.Item>
        <Form.Item name="status" label="状态">
          <Select
            allowClear
            style={{ width: 120 }}
            options={[
              { value: 1, label: '启用' },
              { value: 0, label: '禁用' },
            ]}
          />
        </Form.Item>
        <Button type="primary" htmlType="submit">
          查询
        </Button>
        {has('system:user:add') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd} style={{ marginLeft: 8 }}>
            新增用户
          </Button>
        )}
      </Form>

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
        title={editing ? '编辑用户' : '新增用户'}
        open={modalOpen}
        onOk={onSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
        width={640}
      >
        <Form form={modalForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
                <Input disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="nickname" label="昵称">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="email" label="邮箱">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="phone" label="手机">
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
            <Col span={12}>
              <Form.Item name="roleIds" label="角色">
                <Select
                  mode="multiple"
                  placeholder="分配角色"
                  options={roles.map((r) => ({ value: r.id, label: r.roleName }))}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="deptId" label="部门">
                <TreeSelect
                  allowClear
                  placeholder="选择部门（留空=未分配）"
                  treeDefaultExpandAll
                  treeNodeLabelProp="deptName"
                  fieldNames={{ label: 'deptName', value: 'id', children: 'children' }}
                  treeData={[{ id: 0, deptName: '未分配', children: deptTree }]}
                />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="password"
                label={editing ? '重置密码（留空不改）' : '密码'}
                rules={editing ? [] : [{ required: true, message: '请输入密码' }]}
              >
                <Input.Password placeholder={editing ? '留空则不变更' : '初始密码'} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal title="重置密码" open={pwOpen} onOk={onReset} onCancel={() => setPwOpen(false)} destroyOnHidden>
        <Form form={pwForm} layout="vertical">
          <Form.Item name="password" label="新密码" rules={[{ required: true, message: '请输入新密码' }]}>
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
