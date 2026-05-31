import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Form, Input, Select, InputNumber, Button, Space, Typography, Spin, App, Tabs, Row, Col,
  Table, Tag, Tooltip, Modal, InputNumber as AntInputNumber
} from 'antd';
import {
  ArrowLeftOutlined, SaveOutlined, PlusOutlined, DeleteOutlined,
  CopyOutlined, SwapOutlined, CodeOutlined, EditOutlined
} from '@ant-design/icons';
import { strategyApi, factorApi } from '../../api';

const { Title, Text } = Typography;
const { TextArea } = Input;
const { Option } = Select;

export default function StrategyEditor() {
  const { message } = App.useApp();
  const { id } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(!!id);
  const [saving, setSaving] = useState(false);
  const [scriptCode, setScriptCode] = useState('');
  const [factorConfig, setFactorConfig] = useState('{"factors":[{"code":"MOM20","weight":0.5},{"code":"VOL20","weight":-0.3},{"code":"SIZE","weight":-0.2}]}');
  const [allFactors, setAllFactors] = useState([]);
  const [jsonModalVisible, setJsonModalVisible] = useState(false);
  const isEdit = !!id;

  const parseFactorConfig = useCallback((json) => {
    try {
      const obj = JSON.parse(json);
      return (obj.factors || []).map((f, i) => ({
        key: i + 1,
        code: f.code || '',
        weight: f.weight || 0,
      }));
    } catch {
      return [];
    }
  }, []);

  const factorList = parseFactorConfig(factorConfig);

  const serializeFactors = useCallback((list) => {
    const factors = list.map(f => ({
      code: f.code,
      weight: f.weight,
    }));
    return JSON.stringify({ factors }, null, 2);
  }, []);

  useEffect(() => {
    factorApi.list({ page: 0, size: 200 }).then(res => {
      const list = res.records || res || [];
      setAllFactors(Array.isArray(list) ? list : []);
    });
  }, []);

  useEffect(() => {
    if (id) {
      strategyApi.getById(id).then(res => {
        const s = res;
        form.setFieldsValue({
          strategyCode: s.strategyCode,
          strategyName: s.strategyName,
          description: s.description,
          strategyType: s.strategyType,
          rebalanceFrequency: s.rebalanceFrequency,
          maxPositionCount: s.maxPositionCount,
          positionSizeType: s.positionSizeType,
          stopLossPct: s.stopLossPct ? +s.stopLossPct : undefined,
          stopProfitPct: s.stopProfitPct ? +s.stopProfitPct : undefined,
          author: s.author,
        });
        setScriptCode(s.scriptCode || '');
        setFactorConfig(s.factorConfigJson || '');
      }).finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, [id]);

  const handleSave = () => {
    form.validateFields().then(values => {
      setSaving(true);
      const payload = {
        ...values,
        scriptCode,
        factorConfigJson: factorConfig,
      };
      const req = isEdit ? strategyApi.update(id, payload) : strategyApi.create(payload);
      req.then(res => {
        message.success(isEdit ? '策略更新成功' : '策略创建成功');
        navigate(`/strategies/${res.id}`);
      }).finally(() => setSaving(false));
    });
  };

  const updateFactor = (idx, field, value) => {
    const list = parseFactorConfig(factorConfig);
    if (idx >= 0 && idx < list.length) {
      list[idx] = { ...list[idx], [field]: value };
      setFactorConfig(serializeFactors(list));
    }
  };

  const addFactor = () => {
    const list = parseFactorConfig(factorConfig);
    const usedCodes = new Set(list.map(f => f.code));
    const available = allFactors.find(f => !usedCodes.has(f.factorCode));
    list.push({
      key: Date.now(),
      code: available ? available.factorCode : 'RSI14',
      weight: 0.1,
    });
    setFactorConfig(serializeFactors(list));
  };

  const removeFactor = (idx) => {
    const list = parseFactorConfig(factorConfig);
    list.splice(idx, 1);
    setFactorConfig(serializeFactors(list));
  };

  const moveFactor = (idx, direction) => {
    const list = parseFactorConfig(factorConfig);
    const target = idx + direction;
    if (target < 0 || target >= list.length) return;
    [list[idx], list[target]] = [list[target], list[idx]];
    setFactorConfig(serializeFactors(list));
  };

  const handleJsonImport = (value) => {
    try {
      const obj = JSON.parse(value);
      if (obj.factors && Array.isArray(obj.factors)) {
        setFactorConfig(JSON.stringify(obj, null, 2));
        setJsonModalVisible(false);
        message.success('JSON 导入成功');
      } else {
        message.error('JSON 格式错误：缺少 factors 数组');
      }
    } catch {
      message.error('JSON 格式错误');
    }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin /></div>;

  const factorColumns = [
    {
      title: '#',
      dataIndex: 'key',
      width: 40,
      align: 'center',
      render: (_, __, idx) => (
        <Space size={2}>
          <Button type="text" size="small" icon={<SwapOutlined />}
            style={{ transform: 'rotate(180deg)', fontSize: 10, padding: '0 2px' }}
            disabled={idx === 0} onClick={() => moveFactor(idx, -1)} />
          <Button type="text" size="small" icon={<SwapOutlined />}
            style={{ fontSize: 10, padding: '0 2px' }}
            disabled={idx === factorList.length - 1} onClick={() => moveFactor(idx, 1)} />
        </Space>
      ),
    },
    {
      title: '因子代码',
      dataIndex: 'code',
      width: 160,
      render: (code, _, idx) => {
        const info = allFactors.find(f => f.factorCode === code);
        return (
          <Tooltip title={info ? `${info.factorName || ''}（${info.category || ''}）` : code}>
            <Select
              value={code}
              size="small"
              style={{ width: '100%' }}
              showSearch
              optionFilterProp="label"
              onChange={v => updateFactor(idx, 'code', v)}
            >
              {allFactors.map(f => (
                <Option key={f.factorCode} value={f.factorCode} label={f.factorCode}
                  disabled={factorList.some((sf, si) => si !== idx && sf.code === f.factorCode)}>
                  <Space size={4}>
                    <Tag color={f.category === 'TECHNICAL' || f.category === 'MOMENTUM' ? 'blue' : 'green'}
                      style={{ fontSize: 10, padding: '0 3px', margin: 0 }}>
                      {(f.category || 'TECH').substring(0, 4)}
                    </Tag>
                    <span>{f.factorCode}</span>
                  </Space>
                </Option>
              ))}
            </Select>
          </Tooltip>
        );
      },
    },
    {
      title: '方向',
      dataIndex: 'weight',
      width: 70,
      align: 'center',
      render: (weight, _, idx) => {
        const dir = weight >= 0 ? 1 : -1;
        return (
          <Tag color={dir > 0 ? 'red' : 'green'} style={{ cursor: 'pointer', userSelect: 'none' }}
            onClick={() => updateFactor(idx, 'weight', -weight)}>
            {dir > 0 ? '正向 ↑' : '反向 ↓'}
          </Tag>
        );
      },
    },
    {
      title: '权重',
      dataIndex: 'weight',
      width: 100,
      render: (weight, _, idx) => (
        <AntInputNumber
          value={Math.abs(weight)}
          size="small"
          min={0}
          max={1}
          step={0.05}
          style={{ width: '100%' }}
          onChange={v => updateFactor(idx, 'weight', v >= 0 ? v : -v)}
        />
      ),
    },
    {
      title: '因子名称',
      width: 140,
      render: (_, record) => {
        const info = allFactors.find(f => f.factorCode === record.code);
        return (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {info?.factorName || record.code}
          </Text>
        );
      },
    },
    {
      title: '',
      width: 32,
      render: (_, __, idx) => (
        <Button type="text" danger size="small" icon={<DeleteOutlined />}
          onClick={() => removeFactor(idx)} />
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/strategies')}>返回</Button>
          <Title level={4} style={{ margin: 0 }}>{isEdit ? '编辑策略' : '新建策略'}</Title>
        </Space>
        <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
          保存策略
        </Button>
      </div>

      <Tabs defaultActiveKey="basic" type="card" items={[
        {
          key: 'basic',
          label: '基本配置',
          children: (
            <Card bordered>
              <Form form={form} layout="vertical">
                <Row gutter={24}>
                  <Col span={12}>
                    <Form.Item name="strategyCode" label="策略代码" rules={[{ required: true }]}>
                      <Input placeholder="如：MY_STRATEGY_001" disabled={isEdit} style={{ fontFamily: 'monospace' }} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="strategyName" label="策略名称" rules={[{ required: true }]}>
                      <Input placeholder="策略名称" />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={24}>
                  <Col span={12}>
                    <Form.Item name="strategyType" label="策略类型" rules={[{ required: true }]}>
                      <Select placeholder="选择策略类型">
                        {[
                          ['FACTOR_LONG','因子多头选股'],['LONG_SHORT','多空策略'],
                          ['MARKET_NEUTRAL','市场中性'],['MOMENTUM','动量策略'],
                          ['MEAN_REVERSION','均值回归'],['CUSTOM','自定义脚本']
                        ].map(([v, l]) => <Option key={v} value={v}>{l}</Option>)}
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="rebalanceFrequency" label="调仓频率">
                      <Select>
                        {[['DAILY','日频'],['WEEKLY','周频'],['MONTHLY','月频'],['QUARTERLY','季频']]
                          .map(([v, l]) => <Option key={v} value={v}>{l}</Option>)}
                      </Select>
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={24}>
                  <Col span={12}>
                    <Form.Item name="maxPositionCount" label="最大持仓数">
                      <InputNumber min={1} max={100} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="positionSizeType" label="仓位方式">
                      <Select>
                        {[['EQUAL','等权'],['FACTOR_WEIGHTED','因子加权'],['CUSTOM','自定义']].map(([v, l]) =>
                          <Option key={v} value={v}>{l}</Option>)}
                      </Select>
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={24}>
                  <Col span={12}>
                    <Form.Item name="stopLossPct" label="止损比例 (如 0.08 = 8%)">
                      <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="stopProfitPct" label="止盈比例">
                      <InputNumber min={0} max={10} step={0.01} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={24}>
                  <Col span={12}>
                    <Form.Item name="author" label="创建人">
                      <Input />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="description" label="策略描述">
                      <TextArea rows={3} />
                    </Form.Item>
                  </Col>
                </Row>
              </Form>
            </Card>
          ),
        },
        {
          key: 'factors',
          label: '因子配置',
          children: (
            <Card bordered>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <Space>
                  <Text strong>因子列表</Text>
                  <Text type="secondary">
                    共 {factorList.length} 个因子，权重合计：{factorList.reduce((s, f) => s + Math.abs(f.weight), 0).toFixed(2)}
                  </Text>
                </Space>
                <Space>
                  <Button size="small" icon={<PlusOutlined />} onClick={addFactor}>
                    添加因子
                  </Button>
                  <Button size="small" icon={<CodeOutlined />} onClick={() => setJsonModalVisible(true)}>
                    JSON 预览
                  </Button>
                </Space>
              </div>
              <Table
                dataSource={factorList}
                columns={factorColumns}
                pagination={false}
                size="small"
                bordered
                locale={{ emptyText: '暂无因子，点击"添加因子"开始配置' }}
              />
              <div style={{ marginTop: 12 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  💡 点击方向标签可切换正向/反向；权重按因子重要性分配，无需归一（系统自动计算）
                </Text>
              </div>
            </Card>
          ),
        },
        {
          key: 'script',
          label: '策略脚本 (高级)',
          children: (
            <Card bordered>
              <Row gutter={24}>
                <Col span={16}>
                  <TextArea
                    value={scriptCode}
                    onChange={e => setScriptCode(e.target.value)}
                    rows={18}
                    style={{ fontFamily: 'monospace', fontSize: 13, background: '#1e1e1e', color: '#d4d4d4' }}
                  />
                </Col>
                <Col span={8}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
                    仅CUSTOM类型策略需要填写策略脚本。脚本返回 Map&lt;String, Double&gt;（symbol → weight）
                  </Text>
                  <Card style={{ background: '#f6f8fa' }} size="small">
                    <Text strong>脚本示例：</Text>
                    <pre style={{ fontSize: 11, margin: '8px 0 0' }}>
{`// 获取因子数据
def momMap = factorValues['MOM20'] ?: [:]
def volMap = factorValues['VOL20'] ?: [:]

// 计算得分
def scores = [:]
marketBars.each { bar ->
    def mom = momMap[bar.symbol]?.rankValue ?: 0.5
    def vol = volMap[bar.symbol]?.rankValue ?: 0.5
    scores[bar.symbol] = mom * 0.6 + (1-vol) * 0.4
}

// 返回前N只股票的权重
return scores.sort { -it.value }
    .take(maxPositions)
    .collectEntries { k, v -> [k, 1.0/maxPositions] }`}
                    </pre>
                  </Card>
                </Col>
              </Row>
            </Card>
          ),
        },
      ]} />

      <Modal
        title="因子配置 JSON"
        open={jsonModalVisible}
        onCancel={() => setJsonModalVisible(false)}
        footer={[
          <Button key="cancel" onClick={() => setJsonModalVisible(false)}>关闭</Button>,
          <Button key="copy" icon={<CopyOutlined />}
            onClick={() => {
              navigator.clipboard.writeText(factorConfig);
              message.success('已复制到剪贴板');
            }}>
            复制
          </Button>,
          <Button key="import" icon={<EditOutlined />} type="primary"
            onClick={() => {
              const el = document.getElementById('json-editor-textarea');
              if (el) handleJsonImport(el.value);
            }}>
            导入
          </Button>,
        ]}
        width={640}
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          可直接编辑 JSON 后点击"导入"应用更改，或复制后用于 API 调用
        </Text>
        <TextArea
          id="json-editor-textarea"
          defaultValue={factorConfig}
          rows={16}
          style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 13 }}
        />
      </Modal>
    </div>
  );
}
