import React, { useState, useEffect } from 'react';
import {
  Card, Form, Select, Button, Radio, Table, Space, Tag, Popconfirm, Alert, TreeSelect,
} from 'antd';
import { ReloadOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { dataPermissionApi } from '../../api/dataPermission';
import { message as msg } from '../../utils/messageUtil';

const RESOURCE_TYPES = [
  { value: 'STRATEGY', label: '策略' },
  { value: 'BACKTEST', label: '回测' },
  { value: 'FACTOR', label: '因子' },
  { value: 'PAPER_TRADING', label: '模拟盘' },
];

const VISIBILITY_OPTIONS = [
  { value: 'PRIVATE', label: '私有（仅自己）' },
  { value: 'DEPT', label: '部门可见（含子部门）' },
  { value: 'PUBLIC', label: '公开（所有人）' },
];

const GRANTEE_TYPES = [
  { value: 'USER', label: '用户' },
  { value: 'DEPT', label: '部门' },
  { value: 'ROLE', label: '角色' },
];

const PERM_LEVELS = [
  { value: 'VIEW', label: '查看' },
  { value: 'EDIT', label: '编辑' },
];

export default function DataPermissionManage() {
  const [type, setType] = useState('STRATEGY');
  const [rid, setRid] = useState(null);
  const [options, setOptions] = useState([]);
  const [optLoading, setOptLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const [shareForm] = Form.useForm();

  // 授权对象下拉/树数据
  const [granteeOptions, setGranteeOptions] = useState([]);   // USER / ROLE 扁平列表 [{value,label}]
  const [deptTreeData, setDeptTreeData] = useState([]);        // DEPT 树形数据
  const [granteeLoading, setGranteeLoading] = useState(false);

  const loadOptions = async (t) => {
    setOptLoading(true);
    try {
      const res = await dataPermissionApi.options(t);
      setOptions((res || []).map(o => ({ value: o.id, label: o.label })));
    } catch (e) {
      setOptions([]);
      msg.error(e?.response?.data?.message || '加载资源列表失败');
    } finally {
      setOptLoading(false);
    }
  };

  /** 加载授权对象选项：USER/ROLE → 扁平 Select，DEPT → 部门树 */
  const loadGrantees = async (granteeType) => {
    if (!granteeType) return;
    setGranteeLoading(true);
    try {
      const res = await dataPermissionApi.grantees(granteeType);
      if (granteeType === 'DEPT') {
        // 后端返回 DeptTreeNodeVO[]，转为 antd TreeSelect 的 treeData 格式
        setDeptTreeData(buildTreeSelectData(res || []));
        setGranteeOptions([]);
      } else {
        // USER / ROLE：扁平列表 {id, label}
        setGranteeOptions((res || []).map(o => ({ value: o.id, label: o.label })));
        setDeptTreeData([]);
      }
    } catch (e) {
      setGranteeOptions([]);
      setDeptTreeData([]);
      msg.error(e?.response?.data?.message || '加载授权对象失败');
    } finally {
      setGranteeLoading(false);
    }
  };

  /** 递归将后端 DeptTreeNodeVO 转为 antd TreeSelect treeData */
  const buildTreeSelectData = (nodes) => (nodes || []).map(n => ({
    value: n.id,
    title: n.label,
    children: n.children && n.children.length ? buildTreeSelectData(n.children) : undefined,
  }));

  // 切换资源类型时重新拉取下拉选项
  useEffect(() => {
    setRid(null);
    setData(null);
    loadOptions(type);
  }, [type]);

  // 初始化时加载默认授权对象列表（USER）
  useEffect(() => {
    loadGrantees('USER');
  }, []);

  const load = async () => {
    if (!rid) {
      msg.warning('请选择资源');
      return;
    }
    setLoading(true);
    try {
      const res = await dataPermissionApi.get(type, rid);
      setData(res);
    } catch (e) {
      setData(null);
      msg.error(e?.response?.data?.message || '加载失败：无权访问或资源不存在');
    } finally {
      setLoading(false);
    }
  };

  const saveVisibility = async (visibility) => {
    try {
      await dataPermissionApi.setVisibility(type, rid, visibility);
      msg.success('可见性已更新');
      load();
    } catch (e) {
      msg.error(e?.response?.data?.message || '更新失败');
    }
  };

  const addShare = async () => {
    const v = await shareForm.validateFields();
    try {
      await dataPermissionApi.addShare(type, rid, v);
      msg.success('已添加授权');
      shareForm.resetFields();
      // 重置后恢复默认授权对象类型并重载选项
      shareForm.setFieldValue('granteeType', 'USER');
      loadGrantees('USER');
      load();
    } catch (e) {
      msg.error(e?.response?.data?.message || '添加失败');
    }
  };

  const removeShare = async (shareId) => {
    try {
      await dataPermissionApi.removeShare(type, rid, shareId);
      msg.success('已移除授权');
      load();
    } catch (e) {
      msg.error(e?.response?.data?.message || '移除失败');
    }
  };

  /** 授权对象类型切换时联动加载对应选项，同时清空已选值 */
  const onGranteeTypeChange = (val) => {
    shareForm.setFieldValue('granteeId', undefined);
    loadGrantees(val);
  };

  const shareColumns = [
    { title: '授权对象类型', dataIndex: 'granteeType', render: (t) => <Tag>{GRANTEE_TYPES.find((x) => x.value === t)?.label || t}</Tag> },
    { title: '对象 ID', dataIndex: 'granteeId' },
    { title: '权限', dataIndex: 'permLevel', render: (t) => <Tag color={t === 'EDIT' ? 'blue' : 'default'}>{PERM_LEVELS.find((x) => x.value === t)?.label || t}</Tag> },
    { title: '授权人', dataIndex: 'grantedBy' },
    {
      title: '操作',
      render: (_, row) => (
        <Popconfirm title="确认移除该授权？" onConfirm={() => removeShare(row.id)}>
          <Button size="small" danger icon={<DeleteOutlined />}>移除</Button>
        </Popconfirm>
      ),
    },
  ];

  // 当前选中的授权对象类型（用于决定渲染 Select 还是 TreeSelect）
  const currentGranteeType = Form.useWatch('granteeType', shareForm) || 'USER';

  return (
    <div style={{ padding: 24 }}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="数据权限配置"
        description="设置资源的可见范围，或显式授权给用户 / 部门 / 角色。仅资源 owner 与 ADMIN 可修改。"
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 160 }}
            value={type}
            onChange={setType}
            options={RESOURCE_TYPES}
            placeholder="资源类型"
          />
          <Select
            style={{ width: 360 }}
            value={rid}
            onChange={setRid}
            loading={optLoading}
            options={options}
            placeholder={`选择${RESOURCE_TYPES.find((x) => x.value === type)?.label || ''}资源`}
            showSearch
            optionFilterProp="label"
            notFoundContent={optLoading ? '加载中…' : '暂无可选资源'}
          />
          <Button type="primary" icon={<ReloadOutlined />} loading={loading} onClick={load}>
            加载权限
          </Button>
        </Space>
      </Card>

      {data && (
        <>
          <Card size="small" title="全局可见性" style={{ marginBottom: 16 }}>
            <Radio.Group
              value={data.visibility}
              onChange={(e) => saveVisibility(e.target.value)}
              optionType="button"
              buttonStyle="solid"
              options={VISIBILITY_OPTIONS}
            />
            <div style={{ marginTop: 8, color: '#888' }}>
              拥有者 user_id：{data.ownerId ?? '-'}
            </div>
          </Card>

          <Card size="small" title="显式授权" style={{ marginBottom: 16 }}>
            <Table
              rowKey="id"
              size="small"
              dataSource={data.shares || []}
              columns={shareColumns}
              pagination={false}
              locale={{ emptyText: '暂无授权' }}
            />
            <Form form={shareForm} layout="inline" style={{ marginTop: 16 }} initialValues={{ granteeType: 'USER', permLevel: 'VIEW' }}>
              <Form.Item name="granteeType" label="对象类型">
                <Select style={{ width: 120 }} options={GRANTEE_TYPES} onChange={onGranteeTypeChange} />
              </Form.Item>
              <Form.Item name="granteeId" label="对象" rules={[{ required: true, message: '请选择对象' }]}>
                {currentGranteeType === 'DEPT' ? (
                  <TreeSelect
                    style={{ width: 280 }}
                    treeData={deptTreeData}
                    loading={granteeLoading}
                    placeholder="选择部门"
                    allowClear
                    treeDefaultExpandAll
                    showSearch
                    treeNodeFilterProp="title"
                    notFoundContent={granteeLoading ? '加载中…' : '暂无部门'}
                  />
                ) : (
                  <Select
                    style={{ width: 280 }}
                    options={granteeOptions}
                    loading={granteeLoading}
                    placeholder={`选择${GRANTEE_TYPES.find((x) => x.value === currentGranteeType)?.label || ''}`}
                    showSearch
                    optionFilterProp="label"
                    allowClear
                    notFoundContent={granteeLoading ? '加载中…' : '暂无可选对象'}
                  />
                )}
              </Form.Item>
              <Form.Item name="permLevel" label="权限">
                <Select style={{ width: 100 }} options={PERM_LEVELS} />
              </Form.Item>
              <Form.Item>
                <Button type="primary" icon={<PlusOutlined />} onClick={addShare}>添加授权</Button>
              </Form.Item>
            </Form>
          </Card>
        </>
      )}
    </div>
  );
}
