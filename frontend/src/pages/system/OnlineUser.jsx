import React, { useEffect, useState, useCallback } from 'react';
import {
  Layout, Card, Table, Button, Space, Tag, Typography, Popconfirm, App, Avatar,
} from 'antd';
import { ReloadOutlined, TeamOutlined, LogoutOutlined } from '@ant-design/icons';
import api from '../../api';
import { useAuthStore } from '../../stores/authStore';

const { Content } = Layout;
const { Text } = Typography;

const fmtTime = (ts) => {
  if (!ts) return '-';
  // Sa-Token lastActivityTime 为毫秒时间戳
  const d = new Date(ts);
  return isNaN(d.getTime()) ? '-' : d.toLocaleString();
};

export default function OnlineUser() {
  const { message } = App.useApp();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canKick = hasPermission('system:online:kick');

  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.get('/system/online/list');
      setList(Array.isArray(data) ? data : []);
    } catch (e) {
      message.error('加载在线用户失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    load();
    const t = setInterval(load, 8000);
    return () => clearInterval(t);
  }, [load]);

  const kick = async (loginId) => {
    await api.post(`/system/online/kick?loginId=${encodeURIComponent(loginId)}`);
    message.success('已强制下线');
    await load();
  };

  const columns = [
    {
      title: '用户', dataIndex: 'username', key: 'username',
      render: (u, row) => (
        <Space>
          <Avatar size="small" icon={<TeamOutlined />} />
          <span>{u}</span>
          {row.current && <Tag color="blue">当前</Tag>}
        </Space>
      ),
    },
    { title: '登录ID', dataIndex: 'loginId', key: 'loginId', width: 100, render: (v) => <Text code>{String(v)}</Text> },
    {
      title: '设备', dataIndex: 'device', key: 'device', width: 110,
      render: (d) => <Tag>{d || 'default'}</Tag>,
    },
    { title: '登录时间', dataIndex: 'loginTime', key: 'loginTime', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作', key: 'action', width: 110, fixed: 'right',
      render: (_, row) => canKick && !row.current ? (
        <Popconfirm title="确认强制该用户下线?" onConfirm={() => kick(row.loginId)}>
          <Button size="small" danger icon={<LogoutOutlined />}>下线</Button>
        </Popconfirm>
      ) : <Text type="secondary">—</Text>,
    },
  ];

  return (
    <Layout style={{ background: 'transparent' }}>
      <Content>
        <Card
          title="在线用户"
          extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
        >
          <Table
            rowKey={(r) => `${r.loginId}-${r.token}`}
            columns={columns}
            dataSource={list}
            loading={loading}
            pagination={false}
            scroll={{ x: 800 }}
            size="middle"
          />
        </Card>
      </Content>
    </Layout>
  );
}
