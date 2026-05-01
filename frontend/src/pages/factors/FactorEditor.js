import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Form, Input, Select, Button, Space, Typography, Tabs, message, Spin, Alert, Modal, Tooltip
} from 'antd';
import {
  ArrowLeftOutlined, SaveOutlined, CheckCircleOutlined,
  QuestionCircleOutlined, PlusOutlined, CodeOutlined,
} from '@ant-design/icons';
import { factorApi } from '../../api';
import { CATEGORY_DISPLAY, CATEGORY_LABELS } from './constants';

const { Title, Text } = Typography;
const { TextArea } = Input;
const { Option } = Select;

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
  const [helpVisible, setHelpVisible] = useState(false);
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
      {/* ── 标题行（纯内联样式，避免 page-header CSS 挤压） ─────────────────────── */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/factors')}>返回</Button>
          <Title level={4} style={{ margin: 0 }}>
            {isEdit ? '编辑因子' : '新建因子'}
          </Title>
          <Tooltip title="查看新建因子流程">
            <QuestionCircleOutlined
              style={{ fontSize: 16, color: '#1677ff', cursor: 'pointer' }}
              onClick={() => setHelpVisible(true)}
            />
          </Tooltip>
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
                  <Select showSearch optionFilterProp="children" placeholder="选择分类">
                    {CATEGORY_DISPLAY.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
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

      {/* ── 新建因子流程说明 ─────────────────────────────────────────────────── */}
      <Modal
        title="新建因子 · 完整流程"
        open={helpVisible}
        onCancel={() => setHelpVisible(false)}
        footer={null}
        width={900}
      >
        <div style={{ fontSize: 13, lineHeight: 1.8 }}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16, whiteSpace: 'pre-line' }}
            message="新增一个完整可用的因子，需要以下 4 步："
          />

          {/* 步骤卡片 */}
          {[
            {
              step: 1,
              icon: <CodeOutlined />,
              title: '实现计算逻辑（后端 Java）',
              desc: '在因子计算引擎中添加计算方法。例如缠论因子，需在 ChanTheoryCalculator 中新增方法实现算法。',
              color: '#1677ff',
            },
            {
              step: 2,
              icon: <PlusOutlined />,
              title: '注册因子（后端 Java）',
              desc: '在 FactorComputeEngine 的 registerBuiltin() 中注册新因子（定义代码、名称、分类）。',
              color: '#52c41a',
            },
            {
              step: 3,
              icon: <PlusOutlined />,
              title: '创建因子记录（数据库）',
              desc: '在因子管理页面填写基本信息（代码、名称、分类、描述），保存后在 factor_definition 表中创建记录，并设置为「已激活」状态。',
              color: '#faad14',
            },
            {
              step: 4,
              icon: <PlusOutlined />,
              title: '触发计算（前端操作）',
              desc: '在因子管理 → 因子监控中，点击计算按钮，触发因子值计算。计算完成后即可在筛选、选股等模块中使用。',
              color: '#f5222d',
            },
          ].map(item => (
            <Card
              key={item.step}
              size="small"
              style={{ marginBottom: 12, borderLeft: `3px solid ${item.color}` }}
            >
              <Space>
                <span style={{
                  background: item.color, color: '#fff',
                  borderRadius: '50%', width: 24, height: 24,
                  display: 'inline-flex', alignItems: 'center',
                  justifyContent: 'center', fontSize: 13, fontWeight: 'bold',
                }}>
                  {item.step}
                </span>
                <Text strong style={{ fontSize: 14 }}>{item.title}</Text>
              </Space>
              <div style={{ marginLeft: 36, marginTop: 4, color: '#666', whiteSpace: 'pre-line' }}>
                {item.desc}
              </div>
            </Card>
          ))}

          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 8 }}
            message="关于缠论因子"
            description={
              <div>
                <div>缠论因子属于特殊类别（CHANTHEORY），新增后会被缠论结构筛选页面<Text strong>自动感知</Text>——筛选维度会动态出现，无需修改前端代码。</div>
                <div style={{ marginTop: 4 }}>但计算逻辑本身必须用 Java 代码实现，无法通过配置完成。</div>
              </div>
            }
          />

          <Alert
            type="success"
            showIcon
            style={{ marginTop: 8 }}
            message="建议：先用策略管理测试"
            description="如果只是想用某个因子做选股或回测，可以直接在「策略管理 → 选股条件」中添加因子条件，无需新增因子定义。"
          />
        </div>
      </Modal>
    </div>
  );
}
