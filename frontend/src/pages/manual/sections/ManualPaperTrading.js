import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps, Button, Space, Descriptions } from 'antd';
import { ThunderboltOutlined, PlayCircleOutlined, CheckCircleOutlined, ClockCircleOutlined, GiftOutlined, FundOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export function ManualPaperTrading() {
  return (
    <section id="paper-trading" style={{ paddingBottom: 16 }}>
      <Title level={2}><ThunderboltOutlined /> 模拟盘交易</Title>
      <Paragraph>
        模拟盘（Paper Trading）是基于策略因子配置进行虚拟交易的功能模块。
        无需真实资金即可验证策略的实盘效果，是连接回测与实盘的关键环节。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="模拟盘的核心价值"
        description="零成本验证策略 · 避免实盘试错 · 分红送股真实结算 · 净值曲线自动追踪 · 定时调度全自动运行"
      />

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

      {/* 模拟盘详情页功能 */}
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
              展示累计收益率随时间的变化曲线。<br/>
              <Text type="secondary">每次交易后自动追加 NAV 记录，无需手动刷新。</Text><br/>
              曲线平滑上升说明策略稳健；大幅波动说明风险较高。
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

      {/* 新增功能详解 */}
      <Title level={4} style={{ marginTop: 20 }}>🆕 新增功能详解（Phase 1 补全）</Title>

      {/* 功能 1：批量执行 */}
      <Card size="small" style={{ marginBottom: 12 }}>
        <Title level={5}><PlayCircleOutlined /> 一键执行所有信号</Title>
        <Paragraph style={{ fontSize: 13 }}>
          原来需要逐个点击「执行」按钮处理每个信号，操作繁琐。
          新增「一键执行」按钮，自动按顺序执行所有 <Tag color="blue">PENDING</Tag> 状态的信号。
        </Paragraph>
        <Descriptions size="small" column={1} bordered style={{ marginBottom: 8 }}>
          <Descriptions.Item label="入口">模拟盘详情页 → 「一键执行」按钮</Descriptions.Item>
          <Descriptions.Item label="执行顺序">按信号日期升序、ID 升序依次执行</Descriptions.Item>
          <Descriptions.Item label="异常处理">单笔失败不中断，继续处理下一笔，最终返回成功列表</Descriptions.Item>
          <Descriptions.Item label="API">POST /api/paper-trading/{`{paperId}`}/execute-all-signals</Descriptions.Item>
        </Descriptions>
      </Card>

      {/* 功能 2：定时自动运行 */}
      <Card size="small" style={{ marginBottom: 12 }}>
        <Title level={5}><ClockCircleOutlined /> 定时自动运行（调度器）</Title>
        <Paragraph style={{ fontSize: 13 }}>
          新增 <Text code>PaperTradingScheduler</Text> 定时调度器，
          每个交易日 <Text strong>15:30</Text>（收盘后）自动运行所有 <Tag color="green">RUNNING</Tag> 状态的模拟盘。
        </Paragraph>
        <Row gutter={12}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="自动执行流程">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                1. <Text strong>处理分红送股</Text>：结算当日除权股票的现金红利和送转股<br/>
                2. <Text strong>生成交易信号</Text>：根据策略因子配置计算截面得分，生成买卖信号<br/>
                3. <Text strong>批量执行信号</Text>：自动执行所有待处理信号，更新持仓和资金
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="调度配置">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>Cron 表达式</Text>：<Text code>0 30 15 * * MON-FRI</Text><br/>
                <Text strong>时区</Text>：Asia/Shanghai（北京时间）<br/>
                <Text strong>生效条件</Text>：模拟盘状态为 RUNNING<br/>
                <Text strong>依赖</Text>：需 @EnableScheduling（已启用）
              </Paragraph>
            </Card>
          </Col>
        </Row>
        <Alert type="warning" showIcon style={{ marginTop: 8 }}
          message="注意事项"
          description="定时任务依赖 ClickHouse 中有当日收盘数据。若数据未更新，信号生成可能使用过期数据。建议配合数据更新调度一起使用。"
        />
      </Card>

      {/* 功能 3：分红送股结算 */}
      <Card size="small" style={{ marginBottom: 12 }}>
        <Title level={5}><GiftOutlined /> 分红送股结算</Title>
        <Paragraph style={{ fontSize: 13 }}>
          实盘中分红送股会直接影响持仓成本和资金余额，模拟盘现已支持真实结算。
          数据来源：<Text code>stock_dividend</Text> 表（除权除息日驱动）。
        </Paragraph>
        <Row gutter={12}>
          <Col xs={24} md={8}>
            <Card size="small" type="inner" title="💰 现金分红（每股派息）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>计算</Text>：cash_dividend × 持仓股数<br/>
                <Text strong>效果</Text>：增加模拟盘可用资金（current_capital）<br/>
                <Text strong>示例</Text>：持仓 1000 股，每股派息 0.5 元 → 获得 500 元
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card size="small" type="inner" title="📈 送股（每股送股）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>计算</Text>：stock_dividend × 持仓股数<br/>
                <Text strong>效果</Text>：增加持仓数量，成本价自动摊薄<br/>
                <Text strong>示例</Text>：持仓 1000 股，每 10 股送 2 股 → 变为 1200 股
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card size="small" type="inner" title="🔄 转增（每股转增）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>计算</Text>：convert_dividend × 持仓股数<br/>
                <Text strong>效果</Text>：与送股相同，增加持仓数量<br/>
                <Text strong>区别</Text>：送股来自未分配利润，转增来自资本公积
              </Paragraph>
            </Card>
          </Col>
        </Row>
        <Descriptions size="small" column={1} bordered style={{ marginTop: 8 }}>
          <Descriptions.Item label="触发时机">手动点击「处理分红」按钮，或定时调度自动触发</Descriptions.Item>
          <Descriptions.Item label="结算日期">按除权除息日（ex_dividend_date）匹配，非派息日</Descriptions.Item>
          <Descriptions.Item label="API">POST /api/paper-trading/{`{paperId}`}/process-dividends</Descriptions.Item>
        </Descriptions>
      </Card>

      {/* 功能 4：NAV 自动追加 */}
      <Card size="small" style={{ marginBottom: 12 }}>
        <Title level={5}><FundOutlined /> NAV 曲线自动追加</Title>
        <Paragraph style={{ fontSize: 13 }}>
          原来模拟盘只有初始 NAV 记录，交易后净值曲线不会更新。
          现在每次交易执行后自动计算并追加 NAV 记录，净值曲线实时反映策略表现。
        </Paragraph>
        <Row gutter={12}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="自动触发时机">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                • 买入成交后自动追加<br/>
                • 卖出成交后自动追加<br/>
                • 分红送股结算后自动追加<br/>
                <Text type="secondary">同一天多次交易，只更新当日 NAV 记录，不重复插入</Text>
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="计算字段说明">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>total_assets</Text>：current_capital + ∑(持仓市值)<br/>
                <Text strong>daily_return</Text>：(今日资产 - 昨日资产) / 昨日资产<br/>
                <Text strong>cumulative_return</Text>：(今日资产 - 初始资金) / 初始资金
              </Paragraph>
            </Card>
          </Col>
        </Row>
      </Card>

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
              <Title level={5}><Tag color={s.color}>{s.text}</Tag> {s.status}</Title>
              <Paragraph style={{ fontSize: 12, margin: 0 }}>{s.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 操作建议 */}
      <Alert type="success" showIcon style={{ marginTop: 16 }}
        message="操作建议"
        description={
          <ul style={{ margin: 0, paddingLeft: 16, fontSize: 13 }}>
            <li><Text strong>初次使用</Text>：手动模式（生成信号 → 逐个/批量执行），熟悉流程后再开启自动</li>
            <li><Text strong>自动运行</Text>：将状态设为 RUNNING，每个交易日 15:30 自动运行，无需人工干预</li>
            <li><Text strong>分红处理</Text>：A 股分红季（5~7月）建议手动点击「处理分红」，确保收益准确</li>
            <li><Text strong>策略评估</Text>：运行 1 个月后观察净值曲线，累计收益 {'>'} 基准 + 无大额回撤，才考虑实盘</li>
            <li><Text strong>风险控制</Text>：模拟盘止损逻辑尚未内置，建议定期查看持仓，手动 STOP 表现差的模拟盘</li>
          </ul>
        }
      />

      <Alert type="warning" showIcon style={{ marginTop: 12 }}
        message="⚠️ 模拟盘局限性"
        description={
          <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
            <li>滑点和流动性冲击未模拟（所有信号按信号价全额成交）</li>
            <li>涨停板无法买入、跌停板无法卖出未模拟</li>
            <li>分红税率（持股时长不同税率不同）未区分，一律按现金全额计入</li>
            <li>模拟盘不包含实盘的交易冲击成本（大单砸盘效应）</li>
            <li>建议模拟盘验证通过后，先小资金实盘试运行再逐步加仓</li>
          </ul>
        }
      />
    </section>
  );
}
