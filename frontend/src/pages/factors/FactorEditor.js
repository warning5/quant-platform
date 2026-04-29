import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Form, Input, Select, Button, Space, Typography, Tabs, message, Spin, Alert
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { factorApi } from '../../api';

const { Title, Text } = Typography;
const { TextArea } = Input;
const { Option } = Select;

const CATEGORIES = ['MOMENTUM','VALUE','QUALITY','VOLATILITY','TECHNICAL','FUNDAMENTAL','SENTIMENT','CHANTHEORY','CUSTOM'];

const TEMPLATES = {
  default:    '自定义',
  momentum:   '动量因子模板',
  volatility: '波动率模板',
  technical:  '技术指标模板',
};

export default function FactorEditor() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(!!id);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [scriptCode, setScriptCode] = useState('');
  const [validateResult, setValidateResult] = useState(null);
  const isEdit = !!id;

  useEffect(() => {
    if (id) {
      factorApi.getById(id).then(res => {
        const f = res.data;
        form.setFieldsValue({
          factorCode: f.factorCode,
          factorName: f.factorName,
          description: f.description,
          category: f.category,
          author: f.author,
        });
        setScriptCode(f.scriptCode || '');
      }).finally(() => setLoading(false));
    } else {
      factorApi.getTemplate('default').then(res => {
        setScriptCode(res.data || '');
        setLoading(false);
      });
    }
  }, [id]);

  const loadTemplate = (type) => {
    factorApi.getTemplate(type).then(res => setScriptCode(res.data || ''));
  };

  const handleValidate = () => {
    setValidating(true);
    factorApi.validateScript(scriptCode).then(res => {
      setValidateResult(res.data);
    }).finally(() => setValidating(false));
  };

  const handleSave = () => {
    form.validateFields().then(values => {
      setSaving(true);
      const payload = {
        ...values,
        factorType: 'SCRIPTED',
        scriptCode,
      };
      const req = isEdit ? factorApi.update(id, payload) : factorApi.create(payload);
      req.then(res => {
        message.success(isEdit ? '更新成功' : '因子创建成功');
        navigate(`/factors/${res.data.id}`);
      }).finally(() => setSaving(false));
    });
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;

  return (
    <div>
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/factors')}>返回</Button>
          <Title level={4} style={{ margin: 0 }}>{isEdit ? '编辑因子' : '新建因子'}</Title>
        </Space>
        <Space>
          <Button icon={<CheckCircleOutlined />} onClick={handleValidate} loading={validating}>
            验证脚本
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
            保存因子
          </Button>
        </Space>
      </div>

      <Tabs defaultActiveKey="basic" items={[
        {
          key: 'basic',
          label: '基本信息',
          children: (
            <Card>
              <Form form={form} layout="vertical">
                <Form.Item name="factorCode" label="因子代码" rules={[{ required: true, message: '请输入因子代码' }]}>
                  <Input placeholder="如：MY_FACTOR_001" disabled={isEdit} style={{ fontFamily: 'monospace' }} />
                </Form.Item>
                <Form.Item name="factorName" label="因子名称" rules={[{ required: true }]}>
                  <Input placeholder="如：自定义动量因子" />
                </Form.Item>
                <Form.Item name="category" label="因子分类" rules={[{ required: true }]}>
                  <Select placeholder="选择分类">
                    {CATEGORIES.map(c => <Option key={c} value={c}>{c}</Option>)}
                  </Select>
                </Form.Item>
                <Form.Item name="description" label="因子描述">
                  <TextArea rows={3} placeholder="描述因子的含义和计算逻辑" />
                </Form.Item>
                <Form.Item name="author" label="创建人">
                  <Input placeholder="作者姓名" />
                </Form.Item>
              </Form>
            </Card>
          ),
        },
        {
          key: 'script',
          label: '计算脚本 (Groovy)',
          children: (
            <Card>
              <div style={{ marginBottom: 12 }}>
                <Space>
                  <Text strong>加载模板：</Text>
                  {Object.entries(TEMPLATES).map(([k, v]) => (
                    <Button key={k} size="small" onClick={() => loadTemplate(k)}>{v}</Button>
                  ))}
                </Space>
              </div>

              {validateResult != null && (
                <Alert
                  style={{ marginBottom: 12 }}
                  type={validateResult.valid ? 'success' : 'error'}
                  message={validateResult.valid ? '脚本语法正确' : `语法错误: ${validateResult.error}`}
                  showIcon
                  closable
                  onClose={() => setValidateResult(null)}
                />
              )}

              <div style={{
                background: '#1e1e1e', borderRadius: 6, border: '1px solid #444',
                padding: 2, minHeight: 400
              }}>
                <TextArea
                  value={scriptCode}
                  onChange={e => setScriptCode(e.target.value)}
                  style={{
                    background: '#1e1e1e', color: '#d4d4d4', border: 'none',
                    fontFamily: '"JetBrains Mono", "Fira Code", monospace',
                    fontSize: 13, minHeight: 400, resize: 'vertical',
                  }}
                />
              </div>

              <Card style={{ marginTop: 12, background: '#f6f8fa' }} size="small">
                <Text strong>脚本可用变量：</Text>
                <ul style={{ margin: '8px 0', paddingLeft: 20, fontSize: 13 }}>
                  <li><Text code>history</Text> - <Text type="secondary">List&lt;MarketDailyBar&gt; 历史K线（时间正序）</Text></li>
                  <li><Text code>bar</Text> - <Text type="secondary">最新K线（MarketDailyBar）</Text></li>
                  <li><Text code>close</Text> - <Text type="secondary">最新收盘价（BigDecimal）</Text></li>
                  <li><Text code>n</Text> - <Text type="secondary">历史数据条数（int）</Text></li>
                  <li><Text code>symbol</Text> - <Text type="secondary">股票代码（String）</Text></li>
                  <li><Text code>calcDate</Text> - <Text type="secondary">计算日期（LocalDate）</Text></li>
                </ul>
                <Text strong>MarketDailyBar 字段：</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {' open, high, low, close, preClose, change, pctChg, vol, amount, marketCap, circMarketCap, turnoverRate'}
                </Text>
                <br />
                <Text strong>返回值：</Text>
                <Text type="secondary"> 返回 Number（BigDecimal/Double/Integer），null 表示无法计算本期</Text>
              </Card>
            </Card>
          ),
        },
      ]} />
    </div>
  );
}
