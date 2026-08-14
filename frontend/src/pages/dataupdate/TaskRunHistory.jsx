import React, { useState, useEffect, useCallback } from 'react';
import {
  Card, Table, Tag, Select, Button, Input, Form, Row, Col, Statistic, Space, Tabs,
  Alert, message, DatePicker, Switch, InputNumber, Descriptions, Progress, Badge,
} from 'antd';
import { ReloadOutlined, BellOutlined, SendOutlined, WarningOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import taskHistoryApi from '../../api/taskHistory';
import useDict from '../../utils/useDict';

const { RangePicker } = DatePicker;

const SEVERITY_COLOR = { HIGH: 'red', MEDIUM: 'orange', LOW: 'blue' };

export default function TaskRunHistory() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState({ list: [], total: 0 });
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [filters, setFilters] = useState({ taskKey: undefined, status: undefined, triggerType: undefined, range: null });
  const [stats, setStats] = useState([]);
  const [recentFailures, setRecentFailures] = useState([]);
  const [sla, setSla] = useState([]);
  const [notif, setNotif] = useState({ channel: 'none', enabled: false });
  const [notifForm] = Form.useForm();
  const [savingNotif, setSavingNotif] = useState(false);

  const { dictMap: statusMap, dictList: statusList } = useDict('TASK_STATUS');
  const { dictMap: triggerMap, dictList: triggerList } = useDict('TASK_TRIGGER_TYPE');

  // ── 加载历史 ──
  const loadList = useCallback(async () => {
    setLoading(true);
    try {
      const params = { page, pageSize };
      if (filters.taskKey) params.taskKey = filters.taskKey;
      if (filters.status) params.status = filters.status;
      if (filters.triggerType) params.triggerType = filters.triggerType;
      if (filters.range && filters.range[0]) params.startDate = filters.range[0].format('YYYY-MM-DD');
      if (filters.range && filters.range[1]) params.endDate = filters.range[1].format('YYYY-MM-DD');
      const res = await taskHistoryApi.list(params);
      setData({ list: res.list || [], total: res.total || 0 });
    } catch (e) {
      message.error('加载执行历史失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, filters]);

  const loadStats = useCallback(async () => {
    try {
      const s = await taskHistoryApi.stats(30);
      setStats(s || []);
    } catch (e) { /* ignore */ }
    try {
      const f = await taskHistoryApi.recentFailures(20);
      setRecentFailures(f || []);
    } catch (e) { /* ignore */ }
    try {
      const sl = await taskHistoryApi.sla();
      setSla(sl || []);
    } catch (e) { /* ignore */ }
  }, []);

  const loadNotif = useCallback(async () => {
    try {
      const cfg = await taskHistoryApi.getNotificationConfig();
      setNotif(cfg || { channel: 'none', enabled: false });
      notifForm.setFieldsValue(cfg || {});
    } catch (e) { /* ignore */ }
  }, [notifForm]);

  useEffect(() => { loadList(); }, [loadList]);
  useEffect(() => { loadStats(); loadNotif(); }, [loadStats, loadNotif]);

  // 轮询刷新（每 30s）
  useEffect(() => {
    const t = setInterval(() => { loadStats(); }, 30000);
    return () => clearInterval(t);
  }, [loadStats]);

  // ── 告警配置保存 ──
  const onSaveNotif = async () => {
    const values = await notifForm.validateFields().catch(() => null);
    if (!values) return;
    setSavingNotif(true);
    try {
      await taskHistoryApi.saveNotificationConfig(values);
      message.success('通知配置已保存');
      setNotif(values);
    } catch (e) {
      message.error('保存失败');
    } finally {
      setSavingNotif(false);
    }
  };
  const onTestNotif = async () => {
    try {
      const r = await taskHistoryApi.testNotification();
      message.success(r || '已发送测试');
    } catch (e) {
      message.error('测试发送失败');
    }
  };

  const columns = [
    { title: '任务', dataIndex: 'taskKey', width: 220 },
    { title: '名称', dataIndex: 'taskName', width: 140, ellipsis: true, render: (v) => v || '-' },
    {
      title: '触发', dataIndex: 'triggerType', width: 90,
      render: (v) => <Tag>{triggerMap[v]?.dictLabel ?? (v || '-')}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v) => <Tag color={statusMap[v]?.color ?? 'default'}>{statusMap[v]?.dictLabel ?? v}</Tag>,
    },
    {
      title: '开始', dataIndex: 'startTime', width: 160,
      render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
    },
    {
      title: '耗时', dataIndex: 'durationSec', width: 90,
      render: (v) => (v != null ? `${v}s` : '—'),
    },
    {
      title: '错误信息', dataIndex: 'errorMsg', ellipsis: true,
      render: (v) => v ? <span style={{ color: '#cf1322' }}>{v}</span> : '-',
    },
  ];

  const totalFail = stats.reduce((a, s) => a + (s.fail || 0), 0);
  const totalConsecutive = stats.reduce((a, s) => a + (s.consecutiveFailures || 0), 0);
  const slaViolated = sla.filter((s) => s.slaMet === false).length;

  const tabItems = [
    {
      key: 'history',
      label: '执行历史',
      children: (
        <Card
          extra={
            <Space>
              <Select
                placeholder="任务" allowClear style={{ width: 150 }}
                value={filters.taskKey}
                onChange={(v) => setFilters((f) => ({ ...f, taskKey: v }))}
                options={[
                  { value: 'DAILY', label: '日线采集' }, { value: 'FACTOR_COMPUTE', label: '因子计算' },
                  { value: 'DAILY_RECOMMENDATION', label: '每日推荐' }, { value: 'SENTIMENT_MF', label: '资金流向' },
                  { value: 'SENTIMENT_OTHER', label: '其它情绪' }, { value: 'INDEX', label: '指数' },
                  { value: 'DIVIDEND', label: '分红' }, { value: 'FINANCIAL', label: '财务' },
                ]}
              />
              <Select
                placeholder="状态" allowClear style={{ width: 110 }}
                value={filters.status}
                onChange={(v) => setFilters((f) => ({ ...f, status: v }))}
                options={statusList.length ? statusList.map((d) => ({ value: d.dictValue, label: d.dictLabel, color: d.color })) : []}
              />
              <Select
                placeholder="触发" allowClear style={{ width: 110 }}
                value={filters.triggerType}
                onChange={(v) => setFilters((f) => ({ ...f, triggerType: v }))}
                options={triggerList.length ? triggerList.map((d) => ({ value: d.dictValue, label: d.dictLabel, color: d.color })) : []}
              />
              <RangePicker value={filters.range} onChange={(v) => setFilters((f) => ({ ...f, range: v }))} />
              <Button icon={<ReloadOutlined />} onClick={loadList}>查询</Button>
            </Space>
          }
        >
          <Table
            rowKey="id" size="small" loading={loading}
            columns={columns} dataSource={data.list}
            pagination={{
              current: page, pageSize, total: data.total, showSizeChanger: true,
              onChange: (p, ps) => { setPage(p); setPageSize(ps); },
            }}
          />
        </Card>
      ),
    },
    {
      key: 'sla',
      label: (
        <span>
          SLA 看板{slaViolated > 0 && <Badge count={slaViolated} style={{ marginLeft: 6 }} />}
        </span>
      ),
      children: (
        <Row gutter={[12, 12]}>
          {sla.map((s) => (
            <Col xs={24} sm={12} md={8} key={s.taskKey}>
              <Card size="small"
                title={
                  <span>
                    {s.taskName || s.taskKey}
                    {s.slaConfigured !== 1 && <Tag color="default" style={{ marginLeft: 6 }}>未设SLA</Tag>}
                  </span>
                }
              >
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="今日状态">
                    <Tag color={statusMap[s.lastStatus]?.color ?? 'default'}>{statusMap[s.lastStatus]?.dictLabel ?? '未执行'}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="期望完成">
                    {s.expectedFinishHour != null ? `${s.expectedFinishHour}:00 前` : '不限'}
                  </Descriptions.Item>
                  <Descriptions.Item label="最大耗时">
                    {s.maxDurationMin != null ? `${s.maxDurationMin} 分钟` : '不限'}
                  </Descriptions.Item>
                  <Descriptions.Item label="级别">
                    <Tag color={s.severityColor || SEVERITY_COLOR[s.severity]}>{s.severity}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="SLA">
                    {s.slaConfigured !== 1
                      ? <Tag color="default">未设SLA（仅展示运行）</Tag>
                      : s.slaMet
                        ? <Tag color="success">达标</Tag>
                        : <Tag color="error">未达标</Tag>}
                  </Descriptions.Item>
                </Descriptions>
                {s.errorMsg && <Alert type="error" showIcon message={s.errorMsg} style={{ marginTop: 8 }} />}
              </Card>
            </Col>
          ))}
          {sla.length === 0 && <Col span={24}><Alert type="info" message="暂无 SLA 配置" /></Col>}
        </Row>
      ),
    },
    {
      key: 'config',
      label: '告警配置',
      forceRender: true,
      children: (
        <Card title="失败告警通知" extra={<Tag color={notif.enabled ? 'success' : 'default'}>{notif.enabled ? '已启用' : '未启用'}</Tag>}>
          <Form form={notifForm} layout="vertical" style={{ maxWidth: 520 }}>
            <Form.Item name="enabled" label="启用告警" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="channel" label="推送渠道" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'none', label: '关闭' },
                  { value: 'serverchan', label: 'Server酱' },
                  { value: 'wecom', label: '企业微信' },
                  { value: 'dingtalk', label: '钉钉' },
                ]}
              />
            </Form.Item>
            <Form.Item noStyle shouldUpdate>
              {() => {
                const ch = notifForm.getFieldValue('channel');
                if (ch === 'serverchan') {
                  return (
                    <Form.Item name="serverchanSendkey" label="Server酱 SendKey">
                      <Input placeholder="sctapi 的 SendKey" />
                    </Form.Item>
                  );
                }
                if (ch === 'wecom') {
                  return (
                    <Form.Item name="wecomWebhookUrl" label="企业微信 Webhook URL">
                      <Input placeholder="https://qyapi.weixin.qq.com/..." />
                    </Form.Item>
                  );
                }
                if (ch === 'dingtalk') {
                  return (
                    <>
                      <Form.Item name="dingtalkWebhookUrl" label="钉钉 Webhook URL">
                        <Input placeholder="https://oapi.dingtalk.com/robot/send?access_token=..." />
                      </Form.Item>
                      <Form.Item name="dingtalkSecret" label="钉钉加签密钥（可选）">
                        <Input placeholder="SEC..." />
                      </Form.Item>
                    </>
                  );
                }
                return null;
              }}
            </Form.Item>
            <Space>
              <Button type="primary" loading={savingNotif} onClick={onSaveNotif}>保存配置</Button>
              <Button icon={<SendOutlined />} onClick={onTestNotif}>发送测试</Button>
            </Space>
          </Form>
        </Card>
      ),
    },
  ];

  return (
    <div style={{ padding: 4 }}>
      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        <Col xs={12} sm={6}>
          <Card size="small"><Statistic title="近30天失败总数" value={totalFail} valueStyle={{ color: totalFail > 0 ? '#cf1322' : '#3f8600' }} /></Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small"><Statistic title="当前连续失败任务数" value={stats.filter((s) => (s.consecutiveFailures || 0) > 0).length} valueStyle={{ color: '#cf1322' }} /></Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small"><Statistic title="SLA 未达标" value={slaViolated} valueStyle={{ color: slaViolated > 0 ? '#cf1322' : '#3f8600' }} /></Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small"><Statistic title="最近失败(条)" value={recentFailures.length} /></Card>
        </Col>
      </Row>

      {recentFailures.length > 0 && (
        <Alert
          type="error" showIcon icon={<WarningOutlined />}
          message={`检测到 ${recentFailures.length} 条最近失败执行记录`}
          description={
            <Space wrap>
              {recentFailures.slice(0, 6).map((f) => (
                <Tag key={f.id} color="error">
                  {f.taskName || f.taskKey} · {dayjs(f.startTime).format('MM-DD HH:mm')}
                </Tag>
              ))}
            </Space>
          }
          style={{ marginBottom: 12 }}
        />
      )}

      <Tabs items={tabItems} />
    </div>
  );
}
