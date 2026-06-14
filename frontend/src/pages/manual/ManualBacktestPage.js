import React from 'react';
import { Card, Typography, FloatButton, Tag, Alert, Row, Col, Steps, Table, Space } from 'antd';
import { ThunderboltOutlined, ExperimentOutlined } from '@ant-design/icons';
const { Title, Text, Paragraph } = Typography;

export default function ManualBacktestPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          ⚡ 使用手册 · 回测管理
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>策略回测 · 绩效指标 · 策略对比</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: {padding: '8px 12px'} }}>
        <Space size={[4, 4]} wrap>
          <a onClick={() => scrollTo('overview')}><Tag color="blue">功能概述</Tag></a>
          <a onClick={() => scrollTo('config')}><Tag color="green">回测配置</Tag></a>
          <a onClick={() => scrollTo('metrics-reward')}><Tag color="green">收益指标</Tag></a>
          <a onClick={() => scrollTo('metrics-risk')}><Tag color="orange">风险指标</Tag></a>
          <a onClick={() => scrollTo('metrics-risk-adj')}><Tag color="purple">风险调整收益</Tag></a>
          <a onClick={() => scrollTo('report')}><Tag color="blue">回测报告</Tag></a>
          <a onClick={() => scrollTo('compare')}><Tag color="cyan">策略对比</Tag></a>
          <a onClick={() => scrollTo('advice')}><Tag color="magenta">选优建议</Tag></a>
        </Space>
      </Card>

      <Card>
        {/* 功能概述 */}
        <section id="overview" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>功能概述</Title>
          <Paragraph>
            回测管理模块通过<Text strong>历史数据模拟交易</Text>，验证策略在过去的表现，
            帮助判断策略的有效性。包含<Text strong>回测配置、绩效分析、策略对比</Text>三大功能。
          </Paragraph>
          <Alert type="warning" showIcon message="核心原则"
            description="回测不是预测未来，而是验证策略在过去是否有效。过拟合（过度优化）是回测中最常见的问题，需要警惕。" />
        </section>

        {/* 回测配置 */}
        <section id="config" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>回测配置参数（6项）</Title>
          <Row gutter={[12, 12]}>
            {[
              { title: '回测区间', tag: 'Period', icon: '📅', color: '#1677ff',
                desc: '设置回测的开始和结束日期。区间越长样本越丰富，但需覆盖2年以上数据。' ,
                detail: '起止日期必须包含在 stock_daily 数据范围内。区间过长可能包含多个市场风格，需结合宏观背景分析。' },
              { title: '初始资金', tag: 'Capital', icon: '💰', color: '#52c41a',
                desc: '设置回测的初始资金金额（单位：元）。默认 1,000,000 元。',
                detail: '资金规模会影响个股仓位计算和流动性判断。资金越小，部分大市值股票可能无法买入。' },
              { title: '基准选择', tag: 'Benchmark', icon: '📊', color: '#fa8c16',
                desc: '选择对标基准：沪深300/上证指数/创业板指/中证500/中证1000等。',
                detail: '基准用于计算 Alpha（超额收益）和跟踪误差。建议选择与策略风格匹配的指数。' },
              { title: '交易费用', tag: 'Cost', icon: '💸', color: '#722ed1',
                desc: '佣金 ≥ 5元/笔（双向）+ 印花税 0.1%（仅卖出）+ 过户费 0.02‰。默认滑点 0.1%。',
                detail: '实盘中佣金可谈更低。滑点模拟成交价与指令价的偏差，高频策略需加大滑点。' },
              { title: '调仓频率', tag: 'Rebalance', icon: '🔄', color: '#13c2c2',
                desc: '每日(D)/每周(W)/每月(M)调仓。调仓频率由因子衰减速度决定。',
                detail: '日频成本最高但Alpha捕获最多；月频成本低但可能错过中期信号。需根据策略特性选择。' },
              { title: '成交模式', tag: 'Fill', icon: '⚡', color: '#eb2f96',
                desc: 'CLOSE（收盘价）/ NEXT_OPEN（次日开盘价）/ VWAP（成交量加权均价）。',
                detail: 'NEXT_OPEN 最接近实盘；CLOSE 会引入未来函数（实盘中不可能用当日收盘价下单）。' },
            ].map(m => (
              <Col xs={24} md={12} key={m.tag}>
                <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color}` }}>
                  <Title level={5}>{m.icon} {m.title} <Tag color={m.color}>{m.tag}</Tag></Title>
                  <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
                  <Text type="secondary" style={{ fontSize: 11 }}>{m.detail}</Text>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 收益类指标 */}
        <section id="metrics-reward" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #52c41a', paddingLeft: 12, marginBottom: 16 }}>绩效指标 — 收益类</Title>
          <Row gutter={[12, 12]}>
            {[
              { name: '年化收益率', formula: '(1+总收益率)^(250/交易天数)-1', standard: '> 15% 优秀；8~15% 较好；< 8% 偏弱' },
              { name: '总收益率', formula: '(期末-期初)/期初', standard: '结合年化一起看，短区间总收益率参考价值有限' },
              { name: '超额收益', formula: '策略收益 - 基准收益', standard: '年化超额 > 5% 优秀；2~5% 较好；< 2% 较弱' },
              { name: '胜率', formula: '盈利次数/总交易次数', standard: '> 50% 基础要求；趋势策略30~40%但盈亏比高' },
            ].map(m => (
              <Col xs={24} md={12} key={m.name}>
                <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                  <Title level={5}>{m.name}</Title>
                  <Paragraph style={{ fontSize: 11, margin: 0, fontFamily: 'monospace' }}><Text code>{m.formula}</Text></Paragraph>
                  <Text type="secondary" style={{ fontSize: 11 }}>评价：{m.standard}</Text>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 风险类指标 */}
        <section id="metrics-risk" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>绩效指标 — 风险类</Title>
          <Row gutter={[12, 12]}>
            {[
              { name: '最大回撤', formula: 'max(峰值-谷值)/峰值', standard: '< 10% 极优秀；10~20% 优秀；> 30% 风险较大' },
              { name: '年化波动率', formula: 'StdDev(日收益)×√250', standard: '波动率越低策略越稳健；高波动需要更高收益补偿' },
              { name: '下行波动率', formula: 'StdDev(负收益日)×√250', standard: '下行波动率/年化波动率 越小越好' },
              { name: 'VaR(95%)', formula: 'Percentile(日收益, 5%)', standard: 'VaR 越小越好；结合 CVaR 一起看更准确' },
            ].map(m => (
              <Col xs={24} md={12} key={m.name}>
                <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
                  <Title level={5}>{m.name}</Title>
                  <Paragraph style={{ fontSize: 11, margin: 0, fontFamily: 'monospace' }}><Text code>{m.formula}</Text></Paragraph>
                  <Text type="secondary" style={{ fontSize: 11 }}>评价：{m.standard}</Text>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 风险调整收益类指标 */}
        <section id="metrics-risk-adj" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #722ed1', paddingLeft: 12, marginBottom: 16 }}>绩效指标 — 风险调整收益类</Title>
          <Row gutter={[12, 12]}>
            {[
              { name: '夏普比率', formula: '(年化收益-无风险)/年化波动率', standard: '> 2.0 极优秀；1.5~2.0 优秀；< 1.0 较弱' },
              { name: '索提诺比率', formula: '(年化收益-目标)/下行波动率', standard: '> 2.0 极优秀；索提诺 > 夏普 说明下行控制好' },
              { name: 'Calmar比率', formula: '年化收益/最大回撤', standard: '> 2.0 优秀；< 1.0 表示回撤风险大于收益' },
              { name: '信息比率', formula: '超额收益均值/跟踪误差', standard: '> 0.5 稳定超额；> 1.0 优秀；< 0.3 不稳定' },
              { name: '盈亏比', formula: '平均盈利/平均亏损(绝对值)', standard: '> 1.5 较好；配合胜率：30%胜率×3盈亏比=不错策略' },
            ].map(m => (
              <Col xs={24} md={12} key={m.name}>
                <Card size="small" type="inner" style={{ borderLeft: '4px solid #722ed1' }}>
                  <Title level={5}>{m.name}</Title>
                  <Paragraph style={{ fontSize: 11, margin: 0, fontFamily: 'monospace' }}><Text code>{m.formula}</Text></Paragraph>
                  <Text type="secondary" style={{ fontSize: 11 }}>评价：{m.standard}</Text>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 回测报告 */}
        <section id="report" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>回测报告内容</Title>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message="完整回测报告包含"
            description="绩效概览 → 净值曲线 → 超额曲线 → 已实现收益曲线 → 回撤曲线 → 持仓分析 → 交易记录 → 归因分析" />
          <Row gutter={[12, 12]}>
            {[
              { title: '净值曲线', desc: '策略净值随时间变化，与基准对比。曲线越平滑、收益越高越好。' },
              { title: '超额收益曲线', desc: '策略净值-基准净值。持续在0轴上方说明持续跑赢基准。' },
              { title: '已实现收益曲线', desc: '仅统计已平仓交易的累计收益率，更真实反映已实现盈利。' },
              { title: '回撤曲线', desc: '从每个历史高点到当时亏损幅度。回撤越大、持续越长，风险越高。' },
              { title: '归因分析', desc: '分析策略收益中各因子的贡献，解释收益来源，帮助优化因子配置。' },
            ].map(m => (
              <Col xs={24} md={8} key={m.title}>
                <Card size="small" type="inner">
                  <Title level={5}>{m.title}</Title>
                  <Paragraph style={{ fontSize: 12, margin: 0 }}>{m.desc}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 策略对比 */}
        <section id="compare" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #13c2c2', paddingLeft: 12, marginBottom: 16 }}>策略对比（5个维度）</Title>
          <Paragraph>选择 2~4 个已完成回测的策略进行横向对比，帮助选择最优策略。</Paragraph>
          <Row gutter={[12, 12]}>
            {[
              { title: '收益对比', icon: '📈', color: '#52c41a',
                metrics: '年化收益率、总收益率、超额收益率、Alpha' },
              { title: '风险对比', icon: '📉', color: '#fa8c16',
                metrics: '最大回撤、年化波动率、下行波动率、VaR' },
              { title: '风险调整收益', icon: '⚖️', color: '#722ed1',
                metrics: '夏普、索提诺、Calmar、信息比率' },
              { title: '月度收益分布', icon: '📊', color: '#1677ff',
                metrics: '月度胜率、季度胜率、最大连续亏损月数' },
              { title: '交易成本对比', icon: '💸', color: '#eb2f96',
                metrics: '总交易次数、换手率、累计佣金、累计印花税' },
            ].map(m => (
              <Col xs={24} md={12} key={m.title}>
                <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color}` }}>
                  <Title level={5}>{m.icon} {m.title}</Title>
                  <Paragraph style={{ fontSize: 11, margin: '0 0 2px' }}>核心指标：{m.metrics}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 选优建议 */}
        <section id="advice" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #eb2f96', paddingLeft: 12, marginBottom: 16 }}>对比选优建议</Title>
          <Alert type="info" showIcon
            message="推荐选优步骤"
            description={
              <ol style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                <li>先看风险：剔除最大回撤超过承受能力的策略（如 &gt; 30%）</li>
                <li>再看收益：剩余策略中优先选择年化收益最高的</li>
                <li>综合评价：用夏普比率排序，夏普 &gt; 1.0 的策略具有实战价值</li>
                <li>验证稳健：选择在不同市场区间（牛/熊/震荡）均有超额收益的策略</li>
                <li>考虑容量：换手率高、交易次数多的策略实盘容量有限</li>
              </ol>
            }
          />
        </section>

        {/* 注意事项 */}
        <section id="warning">
          <Alert type="warning" showIcon
            message="⚠️ 回测结果仅供参考，过往业绩不代表未来表现"
            description={
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                <li>未来函数偏差：使用收盘价下单（CLOSE模式）会高估收益</li>
                <li>流动性冲击：大额资金无法以收盘价全部成交</li>
                <li>滑点：实际成交价通常劣于回测价（特别是小市值策略）</li>
                <li>过拟合：参数优化过度可能导致回测优秀但实盘失效</li>
                <li>市场变化：回测区间内的有效策略在区间外可能失效</li>
              </ul>
            }
          />
        </section>
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
