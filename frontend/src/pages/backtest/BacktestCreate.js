import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Card, Form, Input, Select, InputNumber, DatePicker, Button, Space,
  Typography, message, Divider, Row, Col, Switch, Tooltip, Alert, Tag
} from 'antd';
import { ArrowLeftOutlined, PlayCircleOutlined, QuestionCircleOutlined, RocketOutlined, ThunderboltFilled } from '@ant-design/icons';
import dayjs from 'dayjs';
import { backtestApi, strategyApi } from '../../api';

// 高亮字段 label：带「最优」徽标 + Tooltip
function OptLabel({ label, tip }) {
  return (
    <Space size={4}>
      <span>{label}</span>
      <Tooltip title={tip || '来自参数优化的最优结果'}>
        <Tag
          icon={<ThunderboltFilled />}
          color="green"
          style={{ fontSize: 11, lineHeight: '16px', padding: '0 5px', cursor: 'default', marginLeft: 2 }}
        >
          最优
        </Tag>
      </Tooltip>
    </Space>
  );
}

// 高亮 wrapper 样式
const highlightStyle = {
  background: 'linear-gradient(90deg, #f6ffed 0%, #fff 100%)',
  border: '1.5px solid #b7eb8f',
  borderRadius: 8,
  padding: '2px 8px 2px 8px',
  marginBottom: 0,
};

const { Title, Text } = Typography;
const { Option } = Select;
const { RangePicker } = DatePicker;

export default function BacktestCreate() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm();
  const [strategies, setStrategies] = useState([]);
  const [submitting, setSubmitting] = useState(false);
  const [fromOptimize, setFromOptimize] = useState(false);
  // 记录从参数优化自动填充的字段名集合，用于高亮标识
  const [highlightedFields, setHighlightedFields] = useState(new Set());
  
  // 使用 useWatch 监听滑点模型变化
  const slippageModel = Form.useWatch('slippageModel', form);

  const presetStrategyId = searchParams.get('strategyId');

  useEffect(() => {
    strategyApi.list({ page: 0, size: 100 }).then(res => {
      const list = res.records || [];
      setStrategies(list);
      if (presetStrategyId) {
        form.setFieldValue('strategyId', +presetStrategyId);
        const s = list.find(s => s.id === +presetStrategyId);
        if (s) form.setFieldValue('taskName', `${s.strategyName}回测`);
      }
    });

    // 读取参数优化带过来的最优参数
    const urlParams = new URLSearchParams(window.location.search);
    const optimizeParams = {};
    const filledKeys = new Set();
    const paramKeys = ['stopLossPct', 'stopProfitPct', 'maxPositionCount', 'initialCapital'];
    paramKeys.forEach(k => {
      const v = urlParams.get(k);
      if (v != null && v !== '') { optimizeParams[k] = +v; filledKeys.add(k); }
    });
    // 读取回测区间
    const startDate = urlParams.get('startDate');
    const endDate = urlParams.get('endDate');
    if (startDate && endDate) {
      optimizeParams.dateRange = [dayjs(startDate), dayjs(endDate)];
      filledKeys.add('dateRange');
    }
    if (Object.keys(optimizeParams).length > 0) {
      setFromOptimize(true);
      setHighlightedFields(filledKeys);
      // 同步设置每个参数，setFieldValue 立即生效
      paramKeys.forEach(k => {
        if (optimizeParams[k] !== undefined) {
          form.setFieldValue(k, optimizeParams[k]);
        }
      });
      if (optimizeParams.dateRange) {
        form.setFieldValue('dateRange', optimizeParams.dateRange);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presetStrategyId]);

  const handleSubmit = () => {
    form.validateFields().then(values => {
      const [start, end] = values.dateRange;
      const payload = {
        strategyId: values.strategyId,
        taskName: values.taskName,
        startDate: start.format('YYYY-MM-DD'),
        endDate: end.format('YYYY-MM-DD'),
        initialCapital: values.initialCapital,
        commissionRate: values.commissionRate,
        slippageRate: values.slippageRate,
        slippageModel: values.slippageModel || 'FIXED',
        stampTaxRate: values.stampTaxRate,
        minCommission: values.minCommission,
        benchmarkCode: values.benchmarkCode,
        limitFilter: values.limitFilter !== false,
        suspendFilter: values.suspendFilter !== false,
        dividendReinvest: values.dividendReinvest === true,
        transferFeeRate: values.transferFeeRate,
        orderType: values.orderType || 'CLOSE',
        stopLossPct: values.stopLossPct || null,
        stopProfitPct: values.stopProfitPct || null,
        maxPositionCount: values.maxPositionCount || null,
      };
      setSubmitting(true);
      backtestApi.create(payload).then(res => {
        const taskId = res.data?.id;
        message.success('回测任务已提交，正在执行...');
        if (taskId) {
          navigate(`/backtests/${taskId}/running`);
        } else {
          navigate('/backtests');
        }
      }).finally(() => setSubmitting(false));
    });
  };

  return (
    <div style={{ width: '100%' }}>
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/backtests')}>返回</Button>
          <Title level={4} style={{ margin: 0 }}>新建回测</Title>
        </Space>
      </div>

      <Card variant="bordered">
        {fromOptimize && (
          <Alert
            type="success"
            icon={<RocketOutlined />}
            message="已自动填入参数优化的最优结果，请确认各项参数后开始回测。"
            style={{ marginBottom: 20 }}
            showIcon
            closable
            onClose={() => setFromOptimize(false)}
          />
        )}
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            dateRange: [dayjs('2025-01-01'), dayjs('2025-01-01').add(1, 'year').subtract(1, 'day')],
            initialCapital: 1000000,
            commissionRate: 0.0003,
            slippageRate: 0.0002,
            slippageModel: 'FIXED',
            stampTaxRate: 0.0005,
            minCommission: 5,
            transferFeeRate: 0.00002,
            orderType: 'CLOSE',
            benchmarkCode: '000300.SH',
            limitFilter: true,
            suspendFilter: true,
            dividendReinvest: false,
            stopLossPct: 0,
            stopProfitPct: 0,
            maxPositionCount: 0,
          }}
        >
          <Row gutter={24}>
            <Col span={12}>
              <Form.Item name="strategyId" label="选择策略" rules={[{ required: true, message: '请选择策略' }]}>
                <Select
                  placeholder="选择回测策略"
                  showSearch
                  filterOption={(input, option) =>
                    option?.children?.toLowerCase().includes(input.toLowerCase())
                  }
                  onChange={v => {
                    const s = strategies.find(s => s.id === v);
                    if (s) form.setFieldValue('taskName', `${s.strategyName}回测`);
                  }}
                >
                  {strategies.map(s => (
                    <Option key={s.id} value={s.id}>{s.strategyName} ({s.strategyCode})</Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="taskName" label="回测任务名称">
                <Input placeholder="回测任务名称（可选）" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={24}>
            <Col span={12}>
              <div style={highlightedFields.has('dateRange') ? highlightStyle : {}}>
                <Form.Item
                  name="dateRange"
                  label={highlightedFields.has('dateRange')
                    ? <OptLabel label="回测时间区间" tip="来自参数优化任务的回测区间" />
                    : '回测时间区间'}
                  rules={[{ required: true }]}
                >
                  <RangePicker style={{ width: '100%' }} />
                </Form.Item>
              </div>
            </Col>
            <Col span={12}>
              <Form.Item name="benchmarkCode" label="基准指数">
                <Select>
                  {[['000300.SH','沪深300'],['000016.SH','上证50'],['000905.SH','中证500'],['399001.SZ','深证成指']]
                    .map(([v, l]) => <Option key={v} value={v}>{l} ({v})</Option>)}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Divider>
            <Space>
              资金与费率设置
              <Tooltip title="佣金/印花税/滑点等交易费用参数">
                <QuestionCircleOutlined style={{ color: '#bbb' }} />
              </Tooltip>
            </Space>
          </Divider>

          <Row gutter={24}>
            <Col span={6}>
              <div style={highlightedFields.has('initialCapital') ? highlightStyle : {}}>
                <Form.Item
                  name="initialCapital"
                  label={highlightedFields.has('initialCapital')
                    ? <OptLabel label="初始资金（元）" tip="来自参数优化的最优初始资金配置" />
                    : '初始资金（元）'}
                >
                  <InputNumber
                    min={10000}
                    style={{ width: '100%' }}
                    formatter={v => `¥ ${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                    parser={v => v.replace(/¥\s?|(,*)/g, '')}
                  />
                </Form.Item>
              </div>
            </Col>
            <Col span={6}>
              <Form.Item
                name="commissionRate"
                label={
                  <Space size={4}>
                    <span>佣金率</span>
                    <Tooltip title="买卖双向收取，默认万三（0.0003）">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={0.01} step={0.0001} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="stampTaxRate"
                label={
                  <Space size={4}>
                    <span>印花税率</span>
                    <Tooltip title="仅卖出时收取，当前A股标准万五（0.0005）">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={0.01} step={0.0001} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="minCommission"
                label={
                  <Space size={4}>
                    <span>最低佣金（元）</span>
                    <Tooltip title="每笔交易最低收取的佣金，默认5元">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={24}>
            <Col span={8}>
              <Form.Item
                name="transferFeeRate"
                label={
                  <Space size={4}>
                    <span>过户费率</span>
                    <Tooltip title="沪深股票双向收取，标准0.02‰（0.00002）。北交所不收取。2022年起A股过户费已统一为沪深双向">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={0.001} step={0.000001} precision={6} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Divider>
            <Space>
              滑点设置
              <Tooltip title="滑点模拟交易时的价格冲击">
                <QuestionCircleOutlined style={{ color: '#bbb' }} />
              </Tooltip>
            </Space>
          </Divider>

          <Row gutter={24}>
            <Col span={8}>
              <Form.Item name="slippageModel" label="滑点模型">
                <Select>
                  <Option value="FIXED">固定滑点</Option>
                  <Option value="VOLUME">成交量比例滑点</Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="slippageRate" label="基础滑点（如 0.0002 = 万二）">
                <InputNumber min={0} max={0.01} step={0.0001} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8} style={{ paddingTop: 30 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {slippageModel === 'VOLUME'
                  ? '成交量比例模型：滑点 = 基础滑点 × (1 + √(成交额/日成交额) × 10)'
                  : '固定滑点：买入加滑点，卖出减滑点'}
              </Text>
            </Col>
          </Row>

          <Divider>
            <Space>
              成交模式
              <Tooltip title="决定以哪个价格成交：收盘价最保守；次日开盘价更接近实盘；VWAP用日内均价模拟大单成交">
                <QuestionCircleOutlined style={{ color: '#bbb' }} />
              </Tooltip>
            </Space>
          </Divider>

          <Row gutter={24}>
            <Col span={10}>
              <Form.Item
                name="orderType"
                label={
                  <Space size={4}>
                    <span>成交价格模式</span>
                    <Tooltip title="CLOSE=收盘价（默认），NEXT_OPEN=次日开盘价，VWAP=(最高+最低+收盘)/3">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Select>
                  <Option value="CLOSE">收盘价（默认，最保守）</Option>
                  <Option value="NEXT_OPEN">次日开盘价（接近实盘）</Option>
                  <Option value="VWAP">VWAP 均价（日内大单近似）</Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={14} style={{ paddingTop: 30 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                实盘建议选「次日开盘价」或「VWAP」，收盘价成交在A股中通常难以实现
              </Text>
            </Col>
          </Row>

          <Divider>
            <Space>
              策略参数
              <Tooltip title="止损/止盈触发后自动平仓，0 表示不启用">
                <QuestionCircleOutlined style={{ color: '#bbb' }} />
              </Tooltip>
            </Space>
          </Divider>

          <Row gutter={24}>
            <Col span={8}>
              <div style={highlightedFields.has('stopLossPct') ? highlightStyle : {}}>
                <Form.Item
                  name="stopLossPct"
                  label={highlightedFields.has('stopLossPct')
                    ? <OptLabel label="止损比例" tip="来自参数优化的最优止损比例" />
                    : <Space size={4}><span>止损比例</span>
                        <Tooltip title="单只股票亏损达到此比例时自动平仓，0 = 不启用（如 0.05 = 5%）">
                          <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                        </Tooltip>
                      </Space>}
                >
                  <InputNumber
                    min={0} max={1} step={0.01} precision={2}
                    style={{ width: '100%' }}
                    placeholder="0 = 不启用"
                    formatter={v => v ? `${(v * 100).toFixed(0)}%` : ''}
                    parser={v => v ? parseFloat(v) / 100 : 0}
                  />
                </Form.Item>
              </div>
            </Col>
            <Col span={8}>
              <div style={highlightedFields.has('stopProfitPct') ? highlightStyle : {}}>
                <Form.Item
                  name="stopProfitPct"
                  label={highlightedFields.has('stopProfitPct')
                    ? <OptLabel label="止盈比例" tip="来自参数优化的最优止盈比例" />
                    : <Space size={4}><span>止盈比例</span>
                        <Tooltip title="单只股票盈利达到此比例时自动平仓，0 = 不启用（如 0.20 = 20%）">
                          <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                        </Tooltip>
                      </Space>}
                >
                  <InputNumber
                    min={0} max={10} step={0.01} precision={2}
                    style={{ width: '100%' }}
                    placeholder="0 = 不启用"
                    formatter={v => v ? `${(v * 100).toFixed(0)}%` : ''}
                    parser={v => v ? parseFloat(v) / 100 : 0}
                  />
                </Form.Item>
              </div>
            </Col>
            <Col span={8}>
              <div style={highlightedFields.has('maxPositionCount') ? highlightStyle : {}}>
                <Form.Item
                  name="maxPositionCount"
                  label={highlightedFields.has('maxPositionCount')
                    ? <OptLabel label="最大持仓数" tip="来自参数优化的最优持仓数（在策略配置中生效）" />
                    : '最大持仓数'}
                  tooltip="最多同时持有几只股票，0 = 不限制"
                  extra={highlightedFields.has('maxPositionCount') ? '此参数在策略配置中生效' : undefined}
                >

                  <Space.Compact style={{ width: '100%' }}>
                    <InputNumber
                      style={{ width: '100%' }}
                      min={0}
                      value={form.getFieldValue('maxPositionCount')}
                      onChange={v => form.setFieldValue('maxPositionCount', v)}
                      placeholder="默认不限制"
                    />
                    <span style={{ padding: '0 8px', lineHeight: '32px', background: '#f5f5f5', border: '1px solid #d9d9d9', borderLeft: 'none' }}>只</span>
                  </Space.Compact>
                </Form.Item>
              </div>
            </Col>
          </Row>

          <Divider>
            <Space>
              交易过滤
              <Tooltip title="模拟真实交易中的涨跌停和停牌限制">
                <QuestionCircleOutlined style={{ color: '#bbb' }} />
              </Tooltip>
            </Space>
          </Divider>

          <Row gutter={24}>
            <Col span={12}>
              <Form.Item
                name="limitFilter"
                label={
                  <Space size={4}>
                    <span>涨跌停过滤</span>
                    <Tooltip title="启用后：涨停日不买入，跌停日不卖出。模拟真实交易限制">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
                valuePropName="checked"
              >
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="suspendFilter"
                label={
                  <Space size={4}>
                    <span>停牌过滤</span>
                    <Tooltip title="启用后：停牌股票（成交量为0）不进行买卖">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
                valuePropName="checked"
              >
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
          </Row>

          <Divider>
            <Space>
              分红除权
              <Tooltip title="模拟持有期间的现金分红和送股转增">
                <QuestionCircleOutlined style={{ color: '#bbb' }} />
              </Tooltip>
            </Space>
          </Divider>

          <Row gutter={24}>
            <Col span={12}>
              <Form.Item
                name="dividendReinvest"
                label={
                  <Space size={4}>
                    <span>分红处理</span>
                    <Tooltip title="开启后：除权除息日自动处理送股转增（增加持仓股数）和现金分红（计入账户）。使用不复权价格 + 分红到账，更接近真实收益">
                      <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb' }} />
                    </Tooltip>
                  </Space>
                }
                valuePropName="checked"
              >
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item style={{ marginTop: 24 }}>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={handleSubmit}
              loading={submitting}
              size="large"
            >
              开始回测
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
