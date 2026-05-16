import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps, Descriptions } from 'antd';
import { ThunderboltOutlined, PlayCircleOutlined, ClockCircleOutlined, GiftOutlined, FundOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualPaperTradingCore() {
  return (
    <section id="paper-core" style={{ paddingBottom: 32 }}>
      <Title level={2}><ThunderboltOutlined /> 模拟盘基础使用</Title>
      <Paragraph>
        模拟盘（Paper Trading）是基于策略因子配置进行虚拟交易的功能模块。
        无需真实资金即可验证策略的实盘效果，是连接回测与实盘的关键环节。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="核心价值"
        description="零成本验证策略 · 避免实盘试错 · 分红送股真实结算 · 净值曲线自动追踪 · 定时调度全自动运行" />

      {/* 使用流程 */}
      <Title level={4}>使用流程（5 步）</Title>
      <Steps
        current={5}
        items={[
          { title: '创建策略', description: '在策略列表中创建策略，配置因子+权重' },
          { title: '新建模拟盘', description: '选择策略，设置初始资金，创建模拟盘' },
          { title: '生成信号', description: '系统根据因子截面得分生成买卖信号' },
          { title: '执行交易', description: '手动或批量执行信号，系统自动建仓/清仓' },
          { title: '追踪净值', description: '查看净值曲线和持仓盈亏，评估策略表现' },
        ]}
      />

      {/* 详情页功能 */}
      <Title level={4} style={{ marginTop: 20 }}>详情页功能说明</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }} title="📊 净值统计卡片">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>初始资金</Text>：模拟盘启动资金<br/>
              <Text strong>当前资产</Text>：现金 + 持仓市值<br/>
              <Text strong>累计收益</Text>：绝对收益和收益率<br/>
              <Text strong>持仓数</Text>：当前持仓股票数量<br/>
              <Text strong>可用资金</Text>：可用于买入的现金
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="📈 净值曲线">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示累计收益率随时间的变化曲线。每次交易后自动追加 NAV 记录，无需手动刷新。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="📋 持仓与信号">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>当前持仓</Text>：成本价、现价、盈亏百分比<br/>
              <Text strong>交易信号</Text>：待执行/已执行/已跳过状态<br/>
              点击「执行」手动单笔执行，或「一键执行」批量处理。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* 批量执行 */}
      <Title level={4} style={{ marginTop: 20 }}>一键执行 + 定时调度</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}><PlayCircleOutlined /> 一键执行所有信号</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              自动按顺序执行所有 <Tag color="blue">PENDING</Tag> 状态的信号。
              按信号日期升序、ID 升序依次执行；单笔失败不中断，继续处理下一笔。
            </Paragraph>
            <Descriptions size="small" column={1} style={{ marginTop: 8 }}>
              <Descriptions.Item label="API">POST /api/paper-trading/{`{paperId}`}/execute-all-signals</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}><ClockCircleOutlined /> 定时自动运行</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              每个交易日 <Text strong>15:30</Text>（收盘后）自动处理所有 <Tag color="green">RUNNING</Tag> 模拟盘：
              分红结算 → 生成信号 → 批量执行。
            </Paragraph>
            <Descriptions size="small" column={1} style={{ marginTop: 8 }}>
              <Descriptions.Item label="Cron">0 30 15 * * MON-FRI（北京时间）</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      {/* 分红 + NAV */}
      <Title level={4} style={{ marginTop: 20 }}>分红送股 + NAV 自动追加</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
            <Title level={5}><GiftOutlined /> 分红送股结算</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              支持现金分红、送股、转增三种除权方式。
              按除权除息日（ex_dividend_date）匹配，非派息日。
            </Paragraph>
            <Descriptions size="small" column={1} style={{ marginTop: 8 }}>
              <Descriptions.Item label="API">POST /api/paper-trading/{`{paperId}`}/process-dividends</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #722ed1' }}>
            <Title level={5}><FundOutlined /> NAV 曲线自动追加</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              买入/卖出/分红后自动计算并追加 NAV 记录，净值曲线实时反映策略表现。
            </Paragraph>
            <Paragraph style={{ fontSize: 11, margin: '4px 0 0', color: '#722ed1' }}>
              daily_return = (今日资产 - 昨日资产) / 昨日资产
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* 状态说明 */}
      <Title level={4} style={{ marginTop: 20 }}>模拟盘状态说明</Title>
      <Row gutter={[12, 12]}>
        {[
          { status: 'RUNNING', color: 'green', text: '运行中', desc: '定时调度会处理此模拟盘；可手动生成信号和执行交易' },
          { status: 'PAUSED', color: 'orange', text: '已暂停', desc: '定时调度跳过；可查看历史数据，但不能生成新信号' },
          { status: 'STOPPED', color: 'red', text: '已停止', desc: '定时调度跳过；模拟盘归档，不再接受任何操作' },
        ].map(s => (
          <Col xs={24} md={8} key={s.status}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${s.color}` }}>
              <Title level={5}><Tag color={s.color}>{s.text}</Tag></Title>
              <Paragraph style={{ fontSize: 12, margin: 0 }}>{s.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 执行价规则 */}
      <Title level={4} style={{ marginTop: 20 }}>成交价规则</Title>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="执行时点决定成交价"
        description={
          <div>
            <ul style={{ margin: 0, paddingLeft: 20, fontSize: 13 }}>
              <li><Text strong>生成信号（非交易日允许）</Text>：用最近交易日数据生成信号</li>
              <li><Text strong>执行信号（交易日才可）</Text>：今天是交易日 → 用今日收盘价</li>
              <li><Text strong>执行信号（非交易日拦截）</Text>：今天非交易日 → 提示"请于下一交易日开盘后再执行"</li>
            </ul>
          </div>
        }
      />

      <Alert type="warning" showIcon style={{ marginTop: 12 }}
        message="⚠️ 模拟盘局限性"
        description="滑点/流动性未模拟 · 涨跌停无法成交未模拟 · 分红税率未区分 · 无交易冲击成本。模拟盘验证通过后，建议小资金实盘试运行再逐步加仓。" />
    </section>
  );
}
