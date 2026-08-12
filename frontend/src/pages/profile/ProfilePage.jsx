import React, { useEffect, useState } from 'react';
import { Card, Form, Input, Button, Row, Col, Avatar, Space, Typography, Spin, Divider } from 'antd';
import { UserOutlined, SafetyOutlined } from '@ant-design/icons';
import { authApi } from '../../api/auth';
import { message as msg } from '../../utils/messageUtil';

const { Title, Text } = Typography;

export default function ProfilePage() {
  const [profileForm] = Form.useForm();
  const [pwdForm] = Form.useForm();
  const [loading, setLoading] = useState(true);
  const [savingProfile, setSavingProfile] = useState(false);
  const [savingPwd, setSavingPwd] = useState(false);
  const [avatar, setAvatar] = useState(undefined);

  const loadProfile = async () => {
    setLoading(true);
    try {
      const res = await authApi.profile({ _noAuthRedirect: true });
      // 拦截器已统一返回 res.data.data（即 ProfileVO 本身），此处直接用 res
      const data = res ?? {};
      profileForm.setFieldsValue({
        nickname: data.nickname || '',
        email: data.email || '',
        phone: data.phone || '',
        avatar: data.avatar || '',
      });
      setAvatar(data.avatar);
    } catch (e) {
      const status = e?.response?.status;
      if (status === 401) {
        // 登录态已失效：此处直接提示并跳登录页。全局拦截器已清登录态；
        // 静默刷新重试失败的最终 401 也统一在此处理，避免误导性的「加载个人资料失败」
        msg.error('登录已过期，请重新登录');
        if (window.location.pathname !== '/login') {
          try {
            sessionStorage.setItem('redirect_after_login', window.location.pathname + window.location.search);
          } catch (_) {
            /* ignore */
          }
          window.location.href = '/login';
        }
        return;
      }
      msg.error('加载个人资料失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProfile();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onSaveProfile = async () => {
    const values = await profileForm.validateFields();
    setSavingProfile(true);
    try {
      await authApi.updateProfile(values);
      msg.success('资料已更新');
      setAvatar(values.avatar);
    } catch (e) {
      const m = e?.response?.data?.message;
      if (m) msg.error(m);
    } finally {
      setSavingProfile(false);
    }
  };

  const onSavePwd = async () => {
    const values = await pwdForm.validateFields();
    setSavingPwd(true);
    try {
      await authApi.changePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      });
      msg.success('密码修改成功');
      pwdForm.resetFields();
    } catch (e) {
      const m = e?.response?.data?.message;
      if (m) msg.error(m);
    } finally {
      setSavingPwd(false);
    }
  };

  return (
    <Spin spinning={loading}>
      <Row gutter={24}>
        <Col xs={24} md={10} lg={8}>
          <Card>
            <Space direction="vertical" align="center" style={{ width: '100%', padding: '12px 0' }}>
              <Avatar size={72} src={avatar} icon={<UserOutlined />} />
              <Title level={4} style={{ marginBottom: 0 }}>
                个人中心
              </Title>
              <Text type="secondary">管理你的账号资料与登录密码</Text>
            </Space>
            <Divider />
            <Form form={profileForm} layout="vertical">
              <Form.Item label="昵称" name="nickname" rules={[{ max: 32, message: '昵称最长 32 字符' }]}>
                <Input placeholder="请输入昵称" />
              </Form.Item>
              <Form.Item
                label="邮箱"
                name="email"
                rules={[{ type: 'email', message: '邮箱格式不正确' }]}
              >
                <Input placeholder="请输入邮箱" />
              </Form.Item>
              <Form.Item
                label="手机号"
                name="phone"
                rules={[{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]}
              >
                <Input placeholder="请输入手机号" />
              </Form.Item>
              <Form.Item label="头像 URL" name="avatar" rules={[{ type: 'url', message: '请输入合法 URL' }]}>
                <Input placeholder="https://..." />
              </Form.Item>
              <Button type="primary" block loading={savingProfile} onClick={onSaveProfile}>
                保存资料
              </Button>
            </Form>
          </Card>
        </Col>

        <Col xs={24} md={14} lg={16}>
          <Card>
            <Space>
              <SafetyOutlined style={{ color: '#1677ff' }} />
              <Title level={5} style={{ margin: 0 }}>
                修改密码
              </Title>
            </Space>
            <Divider />
            <Form
              form={pwdForm}
              layout="vertical"
              style={{ maxWidth: 420 }}
              initialValues={{ oldPassword: '', newPassword: '', confirmPassword: '' }}
            >
              <Form.Item
                label="原密码"
                name="oldPassword"
                rules={[{ required: true, message: '请输入原密码' }]}
              >
                <Input.Password placeholder="请输入当前密码" />
              </Form.Item>
              <Form.Item
                label="新密码"
                name="newPassword"
                dependencies={['oldPassword']}
                rules={[
                  { required: true, message: '请输入新密码' },
                  { min: 8, max: 64, message: '密码长度需为 8-64 位' },
                  { pattern: /^(?=.*[a-zA-Z])(?=.*\d).+$/, message: '必须同时包含字母和数字' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      const oldP = getFieldValue('oldPassword');
                      if (!value || !oldP || value !== oldP) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('新密码不能与原密码相同'));
                    },
                  }),
                ]}
              >
                <Input.Password placeholder="8-64 位，含字母和数字" />
              </Form.Item>
              <Form.Item
                label="确认新密码"
                name="confirmPassword"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: '请再次输入新密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      if (!value || getFieldValue('newPassword') === value) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password placeholder="请再次输入新密码" />
              </Form.Item>
              <Button type="primary" block loading={savingPwd} onClick={onSavePwd}>
                修改密码
              </Button>
            </Form>
          </Card>
        </Col>
      </Row>
    </Spin>
  );
}
