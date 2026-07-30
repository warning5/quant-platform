import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Card, Tabs, Form, Input, Button, Alert, Space, Typography } from 'antd'
import { message } from '../../utils/messageUtil';
import { UserOutlined, LockOutlined, WechatOutlined } from '@ant-design/icons';
import authApi from '../../api/auth';
import { useAuthStore } from '../../stores/authStore';

const { Title, Text } = Typography;

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const login = useAuthStore((s) => s.login);
  const setToken = useAuthStore((s) => s.setToken);
  const fetchMe = useAuthStore((s) => s.fetchMe);
  const [loading, setLoading] = useState(false);
  const [wechatLoading, setWechatLoading] = useState(false);
  const popupRef = useRef(null);

  // 公众号授权回调：URL 上带有 ?wechat=success&token=xxx
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    if (token && params.get('wechat') === 'success') {
      (async () => {
        try {
          setToken(token);
          await fetchMe();
          message.success('微信登录成功');
          navigate('/', { replace: true });
        } catch (e) {
          message.error('微信登录失败，请重试');
        }
      })();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 监听微信扫码弹窗回传的 token（postMessage）
  useEffect(() => {
    const onMessage = async (e) => {
      if (e.data && e.data.type === 'wechat-login' && e.data.token) {
        try {
          setToken(e.data.token);
          await fetchMe();
          message.success('微信登录成功');
          if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
          navigate('/', { replace: true });
        } catch (err) {
          message.error('微信登录失败，请重试');
        }
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onAccountFinish = async (values) => {
    setLoading(true);
    try {
      const result = await authApi.login(values.username, values.password);
      login(result);
      message.success('登录成功');
      const from = location.state?.from?.pathname || '/';
      navigate(from, { replace: true });
    } catch (e) {
      // 错误提示已由 axios 拦截器统一处理
    } finally {
      setLoading(false);
    }
  };

  const onWechatScan = async () => {
    setWechatLoading(true);
    try {
      const url = await authApi.wechatWebsiteAuthorize();
      popupRef.current = window.open(
        url,
        'wechat_login',
        'width=500,height=620,menubar=no,toolbar=no,location=no,status=no'
      );
      if (!popupRef.current) {
        message.warning('浏览器拦截了弹窗，请允许弹出窗口后重试');
      }
    } catch (e) {
      // ignore
    } finally {
      setWechatLoading(false);
    }
  };

  const accountTab = (
    <Form layout="vertical" onFinish={onAccountFinish} size="large" style={{ marginTop: 8 }}>
      <Form.Item name="username" rules={[{ required: true, message: '请输入账号' }]}>
        <Input prefix={<UserOutlined />} placeholder="账号：admin" />
      </Form.Item>
      <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
        <Input.Password prefix={<LockOutlined />} placeholder="密码：admin123" />
      </Form.Item>
      <Button type="primary" htmlType="submit" block loading={loading}>
        登录
      </Button>
    </Form>
  );

  const wechatTab = (
    <Space direction="vertical" align="center" style={{ width: '100%', padding: '24px 0' }}>
      <WechatOutlined style={{ fontSize: 56, color: '#07c160' }} />
      <Text type="secondary">使用微信扫码登录（网站应用）</Text>
      <Button
        type="primary"
        icon={<WechatOutlined />}
        loading={wechatLoading}
        onClick={onWechatScan}
        style={{ background: '#07c160', borderColor: '#07c160' }}
      >
        微信扫码登录
      </Button>
    </Space>
  );

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #1f2a44 0%, #0b1220 100%)',
      }}
    >
      <Card style={{ width: 380, boxShadow: '0 8px 30px rgba(0,0,0,0.25)' }}>
        <Title level={3} style={{ textAlign: 'center', marginBottom: 4 }}>
          量化平台
        </Title>
        <Text type="secondary" style={{ display: 'block', textAlign: 'center', marginBottom: 16 }}>
          系统登录
        </Text>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="默认账号 admin / admin123（首次启动自动创建，请尽快修改密码）"
        />
        <Tabs
          defaultActiveKey="account"
          centered
          items={[
            { key: 'account', label: '账号密码', children: accountTab },
            { key: 'wechat', label: '微信登录', children: wechatTab },
          ]}
        />
      </Card>
    </div>
  );
}
