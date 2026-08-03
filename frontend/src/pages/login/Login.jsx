import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Card, Tabs, Form, Input, Button, Alert, Space, Typography } from 'antd'
import { message } from '../../utils/messageUtil';
import { UserOutlined, LockOutlined, WechatOutlined, SafetyOutlined } from '@ant-design/icons';
import authApi from '../../api/auth';
import { useAuthStore } from '../../stores/authStore';

const { Title, Text } = Typography;

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const login = useAuthStore((s) => s.login);
  const fetchMe = useAuthStore((s) => s.fetchMe);
  const [loading, setLoading] = useState(false);
  const [wechatLoading, setWechatLoading] = useState(false);
  const [needCaptcha, setNeedCaptcha] = useState(false);
  const [captchaId, setCaptchaId] = useState('');
  const [captchaImg, setCaptchaImg] = useState('');
  const popupRef = useRef(null);

  // 统一获取登录后回跳路径：优先 react-router state（SPA 内跳转），其次 sessionStorage（401 整页刷新）
  const getRedirectPath = () => {
    return location.state?.from?.pathname
      || sessionStorage.getItem('redirect_after_login')
      || '/';
  };

  // 清除回跳路径（避免重复使用）
  const clearRedirectPath = () => {
    try { sessionStorage.removeItem('redirect_after_login'); } catch (_) {}
  };

  // 拉取图形验证码（渐进式：登录失败达阈值后展示）
  const fetchCaptcha = async () => {
    try {
      const result = await authApi.captcha();
      if (result && result.captchaId) {
        setCaptchaId(result.captchaId);
        setCaptchaImg(result.image);
      }
    } catch (_) {
      // 验证码获取失败不阻断登录流程
    }
  };

  // 公众号授权回调：后端已把 token 写入 httpOnly cookie 并重定向到此，
  // 这里只负责恢复登录态（不再从 URL 读取 token 字符串，#6）
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('wechat') === 'success') {
      (async () => {
        try {
          await fetchMe();
          message.success('微信登录成功');
          const from = getRedirectPath();
          clearRedirectPath();
          navigate(from, { replace: true });
        } catch (e) {
          message.error('微信登录失败，请重试');
        }
      })();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 监听微信扫码弹窗回传（postMessage）：现在仅通知「登录成功」，
  // token 已由后端种入 httpOnly cookie，前端不读取，直接恢复登录态（#6）
  useEffect(() => {
    const onMessage = async (e) => {
      // 安全：只接受同源回传，防止任意页面伪造 postMessage 注入 token 完成登录劫持
      if (!e.origin || e.origin !== window.location.origin) {
        return;
      }
      if (e.data && e.data.type === 'wechat-login-success') {
        try {
          await fetchMe();
          message.success('微信登录成功');
          if (popupRef.current && !popupRef.current.closed) popupRef.current.close();
          const from = getRedirectPath();
          clearRedirectPath();
          navigate(from, { replace: true });
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
      const result = await authApi.login(
        values.username,
        values.password,
        needCaptcha ? captchaId : undefined,
        needCaptcha ? values.captcha : undefined
      );
      login(result);
      message.success('登录成功');
      setNeedCaptcha(false);
      const from = getRedirectPath();
      clearRedirectPath();
      navigate(from, { replace: true });
    } catch (e) {
      // 登录失败：若后端要求验证码则展示验证码框并拉取新图
      const need = e?.response?.data?.data?.needCaptcha;
      if (need) {
        setNeedCaptcha(true);
        fetchCaptcha();
      }
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
      {needCaptcha && (
        <Form.Item name="captcha" rules={[{ required: true, message: '请输入验证码' }]}>
          <Input
            prefix={<SafetyOutlined />}
            placeholder="请输入右侧验证码"
            suffix={
              <img
                src={captchaImg}
                alt="验证码"
                onClick={fetchCaptcha}
                style={{ cursor: 'pointer', height: 32, borderRadius: 4 }}
                title="点击刷新验证码"
              />
            }
          />
        </Form.Item>
      )}
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
          message={
            <span>
              默认账号 admin / admin123（首次启动自动创建，
              <Typography.Link onClick={() => navigate('/account/profile')}>
                请尽快修改密码
              </Typography.Link>
              ）
            </span>
          }
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
