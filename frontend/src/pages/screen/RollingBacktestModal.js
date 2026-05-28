import React, { useEffect } from 'react';
import {
  Modal, Form, Input, Select, InputNumber, DatePicker, Button, Space,
  Typography, Divider, Row, Col, Switch, Tooltip, message, Alert
} from 'antd';
import { PlayCircleOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { rollingScreenApi } from '../../api';
import { useNavigate } from 'react-router-dom';

const { Text } = Typography;
const { Option } = Select;
const { RangePicker } = DatePicker;

/**
 * 滚动选股回测配置弹窗
 *
 * 从 StockScreen 页面打开，自动填充当前选股配置（screenConfigJson）。
 * 用户补充回测参数后提交，调用 POST /rolling-screen/run 启动异步回测。
 *
 * @param {{ visible: boolean, onClose: () => void, screenConfig: object, onCreated?: (taskId) => void }} props
 *   - visible: 弹窗可见性
 *   - onClose: 关闭回调
 *   - screenConfig: 当前选股的 ScreenRequest 配置对象（将被序列化为 JSON）
 *   - onCreated: 创建成功回调，参数为 taskId（可选）
 */
export default function RollingBacktestModal({ visible, onClose, screenConfig, onCreated }) {
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = React.useState(false);
  const isEmptyConfig = !screenConfig || !screenConfig.factors || screenConfig.factors.length === 0;

  // 弹窗打开时设置默认值
  useEffect(() => {
    if (visible) {
      form.setFieldsValue({
        taskName: `滚动选股回测 ${dayjs().format('MM-DD HH:mm')}`,
        dateRange: [dayjs().subtract(1, 'year'), dayjs()],
        rebalanceFreq: 'MONTHLY',
        initialCapital: 1000000,
        commissionRate: 0.0003,
        slippageRate: 0.001,
        slippageModel: 'FIXED',
        orderType: 'CLOSE',
        benchmarkCode: '000300.SH',
        weightMode: 'EQUAL',
        limitFilter: true,
        suspendFilter: true,
        stampTaxRate: 0.0005,
        minCommission: 5,
        transferFeeRate: 0.00002,
      });
    }
  }, [visible, form]);

  const handleSubmit = () => {
    form.validateFields().then(values => {
      const [start, end] = values.dateRange;

      // 构建请求体：RollingScreenTask 实体字段
      const payload = {
        taskName: values.taskName,
        screenConfigJson: JSON.stringify(screenConfig || {}),
        startDate: start.format('YYYY-MM-DD'),
        endDate: end.format('YYYY-MM-DD'),
        rebalanceFreq: values.rebalanceFreq || 'MONTHLY',
        initialCapital: values.initialCapital,
        commissionRate: values.commissionRate,
        slippageRate: values.slippageRate,
        slippageModel: values.slippageModel || 'FIXED',
        orderType: values.orderType || 'CLOSE',
        benchmarkCode: values.benchmarkCode || '000300.SH',
        weightMode: values.weightMode || 'EQUAL',
        limitFilter: values.limitFilter !== false,
        suspendFilter: values.suspendFilter !== false,
        stampTaxRate: values.stampTaxRate,
        minCommission: values.minCommission,
        transferFeeRate: values.transferFeeRate,
      };

      setSubmitting(true);
      rollingScreenApi.run(payload).then(res => {
        const taskId = res?.id;
        message.success('滚动回测任务已提交，正在后台执行');
        onClose();
        onCreated?.(taskId);
        if (taskId && !onCreated) {
          navigate(`/screen/backtest/${taskId}`);
        }
      }).catch(err => {
        message.error(err?.response?.data?.message || '提交回测任务失败');
      }).finally(() => setSubmitting(false));
    });
  };

  return (
    <Modal
      title="⏱ 滚动选股回测"
      open={visible}
      forceRender
      onCancel={onClose}
      width={720}
      footer={[
        <Button key="cancel" onClick={onClose}>取消</Button>,
        <Button key="submit" type="primary" icon={<PlayCircleOutlined />}
          onClick={handleSubmit} loading={submitting}
          disabled={isEmptyConfig}>
          {isEmptyConfig ? '请先配置选股条件' : '提交回测'}
        </Button>
      ]}
      destroyOnHidden
    >
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 16 }}>
        基于当前因子选股配置，在历史时间区间内按固定频率调仓，模拟完整交易过程。
        每个调仓日都会重新调用选股引擎筛选股票。
      </Text>

      {isEmptyConfig && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="当前无选股配置"
          description="未检测到因子筛选条件。请先前往「因子选股」页面配置好筛选条件，再通过该页面的「滚动回测」按钮启动。"
        />
      )}

      <Form form={form} layout="vertical" size="small">

        {/* ── 基本信息 ── */}
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="taskName" label="任务名称">
              <Input placeholder="可选，方便识别" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="rebalanceFreq" label="调仓频率">
              <Select>
                <Option value="WEEKLY">每周</Option>
                <Option value="BIWEEKLY">每两周</Option>
                <Option value="MONTHLY">每月（推荐）</Option>
              </Select>
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="dateRange" label="回测区间" rules={[{ required: true }]}>
              <RangePicker style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="benchmarkCode" label="基准指数">
              <Select>
                {[
                  ['000300.SH', '沪深300'],
                  ['000016.SH', '上证50'],
                  ['000905.SH', '中证500'],
                  ['399001.SZ', '深证成指'],
                  ['000852.SH', '中证1000'],
                ].map(([v, l]) => (
                  <Option key={v} value={v}>{l} ({v})</Option>
                ))}
              </Select>
            </Form.Item>
          </Col>
        </Row>

        {/* ── 资金与权重 ── */}
        <Divider plain><Text type="secondary">资金与权重</Text></Divider>
        <Row gutter={16}>
          <Col span={8}>
            <Form.Item name="initialCapital" label={
              <Space size={4}><span>初始资金</span>
                <Tooltip title="初始投入金额，用于计算仓位和费用"><QuestionCircleOutlined style={{ color: '#bbb' }} /></Tooltip>
              </Space>
            }>
              <InputNumber min={10000} style={{ width: '100%' }}
                formatter={v => `¥ ${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                parser={v => v.replace(/¥\s?|(,*)/g, '')} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="weightMode" label="权重分配">
              <Select>
                <Option value="EQUAL">等权</Option>
                <Option value="SCORE_PROPORTIONAL">按得分比例</Option>
              </Select>
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="orderType" label={
              <Space size={4}><span>成交价模式</span>
                <Tooltip title="CLOSE=收盘价；NEXT_OPEN=次日开盘价（更真实）；VWAP=成交量加权均价"><QuestionCircleOutlined style={{ color: '#bbb' }} /></Tooltip>
              </Space>
            }>
              <Select>
                <Option value="CLOSE">收盘价</Option>
                <Option value="NEXT_OPEN">次日开盘价</Option>
                <Option value="VWAP">VWAP 均价</Option>
              </Select>
            </Form.Item>
          </Col>
        </Row>

        {/* ── 费率设置 ── */}
        <Divider plain><Text type="secondary">费率设置</Text></Divider>
        <Row gutter={16}>
          <Col span={6}>
            <Form.Item name="commissionRate" label={
              <Space size={4}><span>佣金率</span>
                <Tooltip title="买卖双向，默认万三"><QuestionCircleOutlined style={{ color: '#bbb', fontSize: 11 }} /></Tooltip>
              </Space>
            }>
              <InputNumber min={0} max={0.01} step={0.0001} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="stampTaxRate" label={
              <Space size={4}><span>印花税率</span>
                <Tooltip title="仅卖出收取，默认万五"><QuestionCircleOutlined style={{ color: '#bbb', fontSize: 11 }} /></Tooltip>
              </Space>
            }>
              <InputNumber min={0} max={0.01} step={0.0001} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="minCommission" label={
              <Space size={4}><span>最低佣金</span>
                <Tooltip title="默认5元/笔"><QuestionCircleOutlined style={{ color: '#bbb', fontSize: 11 }} /></Tooltip>
              </Space>
            }>
              <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="slippageRate" label={
              <Space size={4}><span>滑点率</span>
                <Tooltip title="价格冲击模拟"><QuestionCircleOutlined style={{ color: '#bbb', fontSize: 11 }} /></Tooltip>
              </Space>
            }>
              <InputNumber min={0} max={0.01} step={0.0001} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>

        {/* ── 过滤选项 ── */}
        <Divider plain><Text type="secondary">交易过滤</Text></Divider>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="limitFilter" label="涨跌停过滤" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭"
                tooltip="涨停日不买入，跌停日不卖出" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="suspendFilter" label="停牌过滤" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭"
                tooltip="停牌股票（成交量为0）不进行买卖" />
            </Form.Item>
          </Col>
        </Row>

      </Form>
    </Modal>
  );
}
