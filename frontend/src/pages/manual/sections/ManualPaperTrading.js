import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps, Space, Descriptions, Table } from 'antd';
import {
  ThunderboltOutlined, FundOutlined, SafetyCertificateOutlined,
  AlertOutlined, BellOutlined, WarningOutlined,
  SendOutlined, PlayCircleOutlined, ClockCircleOutlined, GiftOutlined,
} from '@ant-design/icons';

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
        current={4}
        items={[
          { title: '创建策略', description: '在策略列表中创建策略，配置因子+权重' },
          { title: '新建模拟盘', description: '选择策略，设置初始资金，创建模拟盘' },
          { title: '生成信号', description: '系统根据因子截面得分生成买卖信号' },
          { title: '执行交易', description: '手动或批量执行信号，系统自动建仓/清仓' },
          { title: '追踪净值', description: '查看净值曲线和持仓盈亏，评估策略表现' },
        ]}
      />

      {/* ──────────────────────────────────────────────────────────
          详情页功能区（与 UI 的 4 个 Tab 对应）
      ────────────────────────────────────────────────────────── */}
      <Title level={4} style={{ marginTop: 24 }}>详情页功能区说明</Title>
      <Paragraph type="secondary" style={{ fontSize: 13 }}>
        模拟盘详情页分为 4 个功能区 Tab，涵盖从信号生成到风控的全流程操作。
      </Paragraph>

      {/* Tab 1：基础使用 */}
      <Card
        size="small"
        style={{ marginBottom: 12, borderLeft: '4px solid #1677ff' }}
        title={
          <span><FundOutlined style={{ marginRight: 6 }} />Tab 1 · 基础使用</span>
        }
      >
        <Paragraph style={{ fontSize: 13 }}>
          基础操作区，包含顶部统计卡片、净值曲线和持仓列表。操作按钮组：
        </Paragraph>
        <Row gutter={[8, 8]} style={{ marginBottom: 12 }}>
          {[
            { icon: <SendOutlined />, label: '生成信号', desc: '按因子得分生成买卖信号（待执行状态）', color: 'blue' },
            { icon: <PlayCircleOutlined />, label: '一键执行', desc: '批量执行所有待处理信号，无需逐个点击', color: 'green' },
            { icon: <GiftOutlined />, label: '处理分红', desc: '结算持仓股票的分红送股，更新现金和持仓', color: 'orange' },
            { icon: <SafetyCertificateOutlined />, label: '风控配置', desc: '切换到风控配置 Tab，设置止损止盈等参数', color: 'red' },
          ].map(b => (
            <Col xs={24} md={12} key={b.label}>
              <Card size="small" type="inner">
                <Tag color={b.color} style={{ marginBottom: 4 }}>{b.icon} {b.label}</Tag>
                <Text type="secondary" style={{ fontSize: 12 }}>{b.desc}</Text>
              </Card>
            </Col>
          ))}
        </Row>

        <Row gutter={12}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📊 净值统计卡片（顶部）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>初始资金</Text>：模拟盘启动资金，用户自定义<br/>
                <Text strong>当前资产</Text>：现金 + 所有持仓市值之和<br/>
                <Text strong>累计收益</Text>：绝对收益额 + 收益率（红色=盈利，绿色=亏损）<br/>
                <Text strong>持仓数</Text>：当前持仓股票数量<br/>
                <Text strong>可用资金</Text>：可用于买入的现金（不含持仓占用）
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📈 净值曲线">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                展示累计收益率随时间的变化。<Text type="secondary">每次交易后自动追加 NAV 记录，无需手动操作。</Text><br/>
                <Text strong>蓝线</Text>：模拟盘累计收益率 &nbsp;
                <Text strong>橙线</Text>：基准指数（沪深300/中证500等）&nbsp;
                <Text strong>柱状图</Text>：超额收益（日超额 = 模拟盘 - 基准）<br/>
                IR（信息比率）= 超额收益均值 / 超额收益标准差，值越大说明跑赢基准越稳定
              </Paragraph>
            </Card>
          </Col>
        </Row>

        <Card size="small" type="inner" title="💼 当前持仓" style={{ marginTop: 12 }}>
          <Paragraph style={{ fontSize: 12, margin: 0 }}>
            展示所有持仓股票的成本价、现价、市值和盈亏百分比。
            盈亏按 <Text code>profit_loss_pct</Text> 字段颜色区分（红色=盈利，绿色=亏损）。
            点击「执行」可手动卖出单只持仓。
          </Paragraph>
        </Card>
      </Card>

      {/* Tab 2：择时信号 */}
      <Card
        size="small"
        style={{ marginBottom: 12, borderLeft: '4px solid #52c41a' }}
        title={
          <span><ThunderboltOutlined style={{ marginRight: 6 }} />Tab 2 · 择时信号</span>
        }
      >
        <Alert
          message="择时信号机制"
          description={
            <ul style={{ margin: 0, paddingLeft: 16, fontSize: 13 }}>
              <li><Text strong>信号生成</Text>：每个交易日收盘后，系统根据策略配置的因子+权重计算截面得分，按分数排序生成 TOP N 买入/卖出信号</li>
              <li><Text strong>执行时机</Text>：信号生成后为 PENDING 状态，用户手动点击「执行」或「一键执行」进行成交</li>
              <li><Text strong>大盘择时</Text>：在「风控配置」Tab 中开启「大盘择时」开关后，当大盘温度计为空头信号时，自动暂停新开仓（不影响已有持仓）</li>
            </ul>
          }
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
        />

        <Card size="small" type="inner" title="信号字段说明" style={{ marginBottom: 12 }}>
          <Table
            size="small"
            pagination={false}
            columns={[
              { title: '字段', dataIndex: 'field', width: 100 },
              { title: '含义', dataIndex: 'desc' },
            ]}
            dataSource={[
              { field: 'signal_date', desc: '信号生成日期（因子截面日期）' },
              { field: 'direction', desc: 'BUY=买入信号，SELL=卖出信号' },
              { field: 'signal_price', desc: '生成信号时的参考价格（收盘价）' },
              { field: 'factor_score', desc: '综合因子得分，数值越高排名越靠前' },
              { field: 'reason', desc: '生成原因，如"因子得分 TOP 5%"、"因子得分下降 20%"' },
              { field: 'status', desc: 'PENDING=待执行，EXECUTED=已执行，SKIPPED=已跳过，EXPIRED=已过期' },
            ]}
          />
        </Card>

        <Row gutter={12}>
          <Col xs={24} md={8}>
            <Card size="small" type="inner" title="🟢 买入信号触发条件">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                • 因子综合得分排名靠前（TOP N）<br/>
                • 当前无持仓或持仓不足<br/>
                • 可用资金充足<br/>
                • 大盘择时未触发（开启时）
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card size="small" type="inner" title="🔴 卖出信号触发条件">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                • 因子综合得分下降（排名滑出）<br/>
                • 单笔止盈达到阈值<br/>
                • 单笔止损达到阈值<br/>
                • 大盘择时触发（空头信号）
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card size="small" type="inner" title="⏰ 执行价规则">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                • 交易日执行：按收盘价成交<br/>
                • 非交易日执行：拦截（不允许）<br/>
                • 信号日期宽松判断（最近3天内有数据即可），执行时严格判断（必须是当天）
              </Paragraph>
            </Card>
          </Col>
        </Row>
      </Card>

      {/* Tab 3：持仓预警 */}
      <Card
        size="small"
        style={{ marginBottom: 12, borderLeft: '4px solid #fa8c16' }}
        title={
          <span>
            <AlertOutlined style={{ marginRight: 6 }} />Tab 3 · 持仓预警
            <Tag color="orange" style={{ marginLeft: 8 }}>10 种预警类型</Tag>
          </span>
        }
      >
        <Alert
          message="预警扫描说明"
          description={
            <Space direction="vertical" size={4}>
              <Text type="secondary">• 手动扫描：点击「手动扫描预警」按钮，系统检测持仓股票的所有预警条件</Text>
              <Text type="secondary">• 自动触发：执行交易信号后自动扫描（买/卖成交时）</Text>
              <Text type="secondary">• 未读标记：新预警红色高亮显示，已读/删除后恢复普通样式</Text>
            </Space>
          }
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
        />

        <Title level={5} style={{ marginTop: 8 }}>10 种预警类型</Title>
        <Row gutter={[8, 8]} style={{ marginBottom: 12 }}>
          {/* 技术面 */}
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🔧 技术面预警" style={{ borderLeft: '3px solid #1890ff' }}>
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                <li><Text strong>MA_BREAK（均线破位）</Text>：股价跌破 5 日或 20 日均线，发出警示</li>
                <li><Text strong>DROP（大跌）</Text>：单日跌幅超过阈值（默认 -5%），提示关注</li>
              </ul>
            </Card>
          </Col>
          {/* 消息面 */}
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📰 消息面预警" style={{ borderLeft: '3px solid #722ed1' }}>
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                <li><Text strong>NOTICE（公告）</Text>：检测到近期重要公告，可能影响股价</li>
                <li><Text strong>REPORT（研报）</Text>：机构发布新研报，关注评级变化</li>
              </ul>
            </Card>
          </Col>
          {/* 风控预警 */}
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🛡️ 风控预警" style={{ borderLeft: '3px solid #f5222d' }}>
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                <li><Text strong>RISK_CONCENTRATION（集中度）</Text>：单只股票市值超过最大集中度阈值</li>
                <li><Text strong>RISK_INDUSTRY（行业暴露）</Text>：单一行业市值超过最大行业暴露阈值（建议 ≤35%）</li>
                <li><Text strong>RISK_DRAWDOWN（回撤）</Text>：当前净值从历史峰值回撤超过最大回撤限制</li>
              </ul>
            </Card>
          </Col>
          {/* 事件驱动 */}
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📅 事件驱动预警" style={{ borderLeft: '3px solid #fa8c16' }}>
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                <li><Text strong>EVENT_INCREASE（定增）</Text>：近 7 天内有定向增发公告，WARNING 级</li>
                <li><Text strong>EVENT_UNLOCK（解禁）</Text>：近 7 天内有股份解禁公告，WARNING 级</li>
                <li><Text strong>EVENT_INCENTIVE（股权激励）</Text>：近 7 天内有股权激励公告，INFO 级</li>
                <li><Text strong>EVENT_FORECAST（业绩预告）</Text>：近 7 天内有业绩预告，INFO 级</li>
              </ul>
            </Card>
          </Col>
        </Row>

        <Card size="small" type="inner" title="预警级别说明" style={{ marginBottom: 0 }}>
          <Row gutter={12}>
            {[
              { level: 'CRITICAL', color: 'red', text: '严重', desc: '需立即关注，可能造成较大损失' },
              { level: 'WARNING', color: 'orange', text: '警告', desc: '需要关注，建议查看详情并考虑操作' },
              { level: 'INFO', color: 'blue', text: '提示', desc: '信息通知，常规事件，不一定需要操作' },
            ].map(l => (
              <Col xs={24} md={8} key={l.level}>
                <Tag color={l.color}>{l.level} · {l.text}</Tag>
                <Text type="secondary" style={{ fontSize: 12 }}>{l.desc}</Text>
              </Col>
            ))}
          </Row>
        </Card>
      </Card>

      {/* Tab 4：风控配置 */}
      <Card
        size="small"
        style={{ marginBottom: 12, borderLeft: '4px solid #f5222d' }}
        title={
          <span><SafetyCertificateOutlined style={{ marginRight: 6 }} />Tab 4 · 风控配置</span>
        }
      >
        <Alert
          message="风控配置说明"
          description={
            <Text type="secondary">
              风控参数在「风控配置」Tab 中设置，点击「保存配置」后立即生效。止损/止盈达到阈值后自动生成卖出信号（需手动执行）。
            </Text>
          }
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
        />

        <Title level={5}>8 个风控参数详解</Title>
        <Row gutter={[8, 8]} style={{ marginBottom: 12 }}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="⚡ 止损阈值（stopLossPct）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：单笔持仓亏损达到此比例，触发止损卖出信号<br/>
                <Text strong>建议值</Text>：8%（亏损 8% 时止损）<br/>
                <Text strong>计算</Text>：(当前价 - 成本价) / 成本价 ≤ -stopLossPct<br/>
                <Text type="secondary">注意：触发后生成 SELL 信号，需手动执行</Text>
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🏆 止盈阈值（takeProfitPct）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：单笔持仓盈利达到此比例，触发止盈卖出信号<br/>
                <Text strong>建议值</Text>：30%（盈利 30% 时止盈）<br/>
                <Text strong>计算</Text>：(当前价 - 成本价) / 成本价 ≥ takeProfitPct<br/>
                <Text type="secondary">建议 ≤ 2（A股波动大，过高止盈难以触发）</Text>
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📊 最大单股集中度（maxPositionPct）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：单一股票市值占总资产的比例上限<br/>
                <Text strong>建议值</Text>：20%（单只股票不超过资产的 20%）<br/>
                <Text strong>触发</Text>：超过阈值后生成 RISK_CONCENTRATION 预警
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🏭 最大行业暴露（maxIndustryPct）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：同一申万行业市值占总资产的比例上限<br/>
                <Text strong>建议值</Text>：30%（单一行业不超过资产的 30%）<br/>
                <Text strong>触发</Text>：超过阈值后生成 RISK_INDUSTRY 预警
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📉 最大回撤限制（maxDrawdownPct）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：从历史净值峰值最大回撤比例<br/>
                <Text strong>建议值</Text>：15%（回撤不超过 15%）<br/>
                <Text strong>触发</Text>：超过阈值后生成 RISK_DRAWDOWN 预警，提示策略需调整
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🌡️ 大盘择时（timingEnabled）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：开启后，大盘温度计为空头信号时自动暂停新开仓<br/>
                <Text strong>开关</Text>：OFF=不启用，ON=开启大盘择时<br/>
                <Text strong>效果</Text>：仅影响新开仓买操作，已持仓不强制卖出
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📋 基准指数（benchmarkCode）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>含义</Text>：用于计算超额收益（模拟盘 - 基准）的基准<br/>
                <Text strong>选项</Text>：沪深300 / 中证500 / 中证1000 / 创业板指 / 万得全A<br/>
                <Text strong>效果</Text>：净值曲线图叠加显示基准指数走势
              </Paragraph>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="💰 资金分配模式（allocationMode）">
              <Paragraph style={{ fontSize: 12, margin: 0 }}>
                <Text strong>等权（equal）</Text>：初始资金 ÷ N，每只标的平均分配（N=标的数量）<br/>
                <Text strong>动态权重（dynamic）</Text>：按因子得分比例分配，min=初始/20，max=初始/5<br/>
                <Text strong>凯利公式（kelly）</Text>：基于历史胜率计算最优仓位，需≥5笔历史，限制 5%~25%
              </Paragraph>
            </Card>
          </Col>
        </Row>
      </Card>

      {/* 状态说明 */}
      <Title level={4} style={{ marginTop: 16 }}>模拟盘状态说明</Title>
      <Row gutter={[12, 12]}>
        {[
          { status: 'RUNNING', color: 'green', text: '运行中', desc: '定时调度会处理此模拟盘；可手动生成信号和执行交易；可一键执行批量处理' },
          { status: 'PAUSED', color: 'orange', text: '已暂停', desc: '定时调度跳过；可查看历史数据，但不能生成新信号；可手动操作持仓' },
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
            <li><Text strong>初次使用</Text>：手动模式（生成信号 → 逐个/批量执行），熟悉流程后再开启定时自动</li>
            <li><Text strong>定时自动运行</Text>：将状态设为 RUNNING，每个交易日 15:30 自动生成+执行信号，无需人工干预</li>
            <li><Text strong>分红处理</Text>：A 股分红季（5~7 月）建议手动点击「处理分红」，确保收益计算准确</li>
            <li><Text strong>风控配置</Text>：建仓前先在「风控配置」Tab 中设置止损/止盈/集中度/行业暴露参数</li>
            <li><Text strong>策略评估</Text>：运行 1 个月后观察净值曲线，累计收益跑赢基准且回撤可控，才考虑实盘</li>
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
