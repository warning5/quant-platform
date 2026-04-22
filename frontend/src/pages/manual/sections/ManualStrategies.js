import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps } from 'antd';
import { ThunderboltOutlined, ExperimentOutlined, CheckCircleOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

const PRESETS = [
  { name: '经典多因子', desc: '均衡配置：价值+动量+波动率+流动性+质量+成长，适合中长期持有',
    factors: ['SIZE(-1)', 'MOM20(+1)', 'VOL20(-1)', 'AMIHUD(-1)', 'FIN_ROE(+1)', 'FIN_REVENUE_YOY(+1)', 'RSI14(-1,≤70)'],
    suitable: '中长期稳健型', style: 'blue', icon: '⚖️' },
  { name: '小盘成长', desc: '聚焦小市值+高成长+高ROE，适合追求高弹性的投资者',
    factors: ['SIZE(-1,权重2)', 'FIN_REVENUE_YOY(>10%)', 'FIN_ROE(>8%)', 'FIN_NET_PROFIT_YOY', 'MOM20'],
    suitable: '高弹性/高风险偏好', style: 'purple', icon: '🚀' },
  { name: '低波动红利', desc: '低波动+高盈利质量，适合稳健型投资者',
    factors: ['VOL20(-1,权重2)', 'FIN_EARNINGS_QUALITY(>0.5)', 'FIN_CF_TO_NP(>0.5)', 'FIN_BPS(>2元)', 'VOLUME_RATIO'],
    suitable: '稳健型/保守型', style: 'green', icon: '🛡️' },
  { name: '技术动量', desc: '纯技术面选股：趋势跟踪+量价确认，适合短线交易',
    factors: ['MOM20(+1.5)', 'MOM60', 'RSI14(≤70)', 'MACD', 'VOLUME_RATIO', 'BOLL_POS'],
    suitable: '短线/技术面交易者', style: 'orange', icon: '📊' },
  { name: '价值投资', desc: '深度价值：低估值+高质量+高安全边际',
    factors: ['FIN_EARNINGS_YIELD(+1.5)', 'FIN_BPS(>3元)', 'FIN_GROSS_MARGIN(>20%)', 'FIN_CF_QUALITY', 'FIN_DEBT_TO_ASSET(<60%)'],
    suitable: '价值投资者/长期持有', style: 'gold', icon: '💎' },
  { name: '趋势突破', desc: '量价配合的趋势跟踪：强动量+放量确认，捕捉持续上涨个股',
    factors: ['MOM60(>5%,权重2)', 'MOM20(>0%)', 'VOLUME_RATIO(>1,权重1.5)', 'BOLL_POS', 'VPCORR20(>0)', 'VOL20(-0.5)'],
    suitable: '趋势跟踪/波段操作', style: 'red', icon: '📈' },
  { name: '高盈利质量', desc: '聚焦盈利能力强、利润含金量高、财务健康的优质公司',
    factors: ['FIN_ROE(>10%,权重2)', 'FIN_GROSS_MARGIN(>25%)', 'FIN_EARNINGS_QUALITY(>0.8)', 'FIN_CF_TO_NP(>0.8)', 'FIN_DEBT_TO_ASSET(<50%)', 'FIN_NET_MARGIN(>5%)'],
    suitable: '价值成长型/机构投资者', style: 'cyan', icon: '🏆' },
  { name: '成长加速', desc: '营收净利双高增长+趋势加速，捕捉处于高速成长期的公司',
    factors: ['FIN_REVENUE_YOY(>15%,权重2)', 'FIN_NET_PROFIT_YOY(>20%,权重2)', 'PRICE_MOM_ACC(>0)', 'FIN_ROE(>8%)', 'MOM20(>0)', 'SIZE(-0.5)'],
    suitable: '成长型/VC思路', style: 'magenta', icon: '⚡' },
  { name: '低估反转', desc: '超跌低估值个股的均值回归：短期弱势+基本面支撑+估值低位',
    factors: ['REVERSAL5(+1.5)', 'RSI14(≤40,权重1.5)', 'FIN_BPS(>2元)', 'FIN_ROE(>5%)', 'VOL20', 'FIN_DEBT_TO_ASSET(<65%)'],
    suitable: '逆向投资/左侧交易', style: 'volcano', icon: '🔄' },
  { name: '量价异动', desc: '捕捉资金异常涌入迹象：成交量突变+量价背离修复',
    factors: ['VOLUME_SURPRISE(>0.1,权重2)', 'TURNOVER_ANOMALY(>1.2)', 'VROC12(>0)', 'VPCORR20(>0.2)', 'MOM5(>0)', 'SIZE(-0.5)'],
    suitable: '短线博弈/事件驱动', style: 'orange', icon: '🔥' },
  { name: '财务健康', desc: '偿债能力强+营运效率高+现金流充裕，规避财务风险',
    factors: ['FIN_CURRENT_RATIO(>1.5)', 'FIN_DEBT_TO_ASSET(<50%,权重1.5)', 'FIN_CF_TO_REVENUE(>5%)', 'FIN_AR_TURNOVER', 'FIN_ASSETS_TURNOVER', 'VOL20(-0.5)'],
    suitable: '防御型/风控优先', style: 'geekblue', icon: '🏥' },
  { name: '综合打分', desc: '技术+基本面+情绪全覆盖：八因子均衡打分，降低单一因子的随机性',
    factors: ['FIN_ROE', 'FIN_REVENUE_YOY', 'MOM60', 'VOL20', 'SIZE', 'FIN_EARNINGS_QUALITY', 'VOLUME_RATIO', 'RSI14(≤70)'],
    suitable: '平衡型/长期配置', style: 'lime', icon: '🎯' },
];

const PRESET_STYLE_COLORS = {
  blue: '#1677ff', purple: '#722ed1', green: '#52c41a', orange: '#fa8c16',
  gold: '#faad14', red: '#cf1322', cyan: '#13c2c2', magenta: '#eb2f96',
  volcano: '#fa541c', geekblue: '#2f54eb', lime: '#7cb305',
};

export function ManualStrategies() {
  return (
    <section id="strategies" style={{ paddingBottom: 32 }}>
      <Title level={2}><ThunderboltOutlined /> 策略管理</Title>
      <Paragraph>
        策略管理模块用于定义和编辑量化交易策略。用户可以创建自己的选股策略，
        也可以直接使用平台提供的 12 个预设组合进行选股。
        每个组合针对不同的投资风格和风险偏好进行了优化配置。
      </Paragraph>

      <Title level={4}>策略组成（三要素）</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }} title="选股规则">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              定义选股的条件，如市值范围、行业分布、估值要求等。
              因子方向：<Text code>direction=1</Text> 表示因子值越大越好（正向），
              <Text code>direction=-1</Text> 表示因子值越小越好（反向）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="因子权重">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              设置各因子的权重，用于综合评分排序。
              权重越大，该因子对最终得分影响越大。
              权重和不一定为1（系统会自动归一化）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="持仓管理">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              设置最大持仓数量、个股仓位限制等。
              同时支持设置买入条件（技术面+估值面建议价）和止盈止损（基于ATR）。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* 12 预设组合 */}
      <Title level={4}>12 个预设组合详解</Title>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="使用方法"
        description="选择任意预设组合 → 设置选股日期 → 点击「执行选股」即可获取符合条件的股票列表。括号内数字为权重，方向(-1)表示越小越好，(>N)表示过滤阈值。"
      />
      <Row gutter={[10, 10]}>
        {PRESETS.map(p => (
          <Col xs={24} md={12} lg={8} key={p.name}>
            <Card size="small" type="inner"
              style={{ borderLeft: `4px solid ${PRESET_STYLE_COLORS[p.style] || '#1677ff'}`, height: '100%' }}>
              <Title level={5}>{p.icon} {p.name} <Tag color={p.style}>{p.suitable}</Tag></Title>
              <Paragraph style={{ fontSize: 12, margin: '0 0 6px', color: '#555' }}>{p.desc}</Paragraph>
              <Paragraph style={{ fontSize: 11, margin: 0 }}>
                <Text type="secondary" style={{ fontSize: 11 }}>因子：</Text>
                {p.factors.map((f, i) => (
                  <Tag key={i} color="default" style={{ fontSize: 10, marginBottom: 2 }}>{f}</Tag>
                ))}
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 选股流程 */}
      <Title level={4}>选股流程</Title>
      <Steps
        current={4}
        items={[
          { title: '选择组合', description: '从12个预设组合中选择，或自定义配置因子+权重' },
          { title: '设置日期', description: '选择选股日期（需有行情数据和因子数据）' },
          { title: '执行选股', description: '计算各股票综合得分，按排序输出 top N' },
          { title: '查看结果', description: '展示选中股票列表、得分、买入建议价' },
          { title: '止盈止损', description: '系统根据 ATR(14) 自动计算止盈/止损参考线' },
        ]}
      />

      {/* 买入建议说明 */}
      <Title level={4}>买入建议价说明</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="技术面建议">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>MA 建议</Text>：若股价站上 20 日均线，提供 MA20 作为支撑参考。<br/>
              <Text strong>布林带建议</Text>：若股价在布林中轨以上，提供中轨作为回踩参考。<br/>
              <Text strong>ATR 建议</Text>：结合 ATR(14) 计算合理买入区间。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="估值面建议">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>BPS 建议</Text>：若当前 PB &lt; 行业平均，提供 BPS 作为价值锚。<br/>
              <Text strong>PE 建议</Text>：若 PE 在历史低位区间，提供 PE 作为估值参考。<br/>
              <Text strong>综合</Text>：技术面 + 估值面双维度给出买入区间建议。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert type="warning" showIcon style={{ marginTop: 16 }}
        message="注意"
        description="选股结果仅供参考，不构成投资建议。买入建议价仅为参考，实际买入需结合大盘环境、仓位管理等因素综合判断。"
      />
    </section>
  );
}

export function ManualBacktests() {
  return (
    <section id="backtests" style={{ paddingBottom: 32 }}>
      <Title level={2}>策略回测</Title>
      <Paragraph>
        策略回测模块用于验证策略的历史表现。通过在历史数据上模拟交易，
        可以评估策略的收益率、回撤、夏普比率等关键指标，帮助投资者判断策略的优劣。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="回测核心原则"
        description="回测不是预测未来，而是验证策略在过去是否有效。过拟合（过度优化）是回测中最常见的问题，需要警惕。"
      />

      {/* 回测配置参数 */}
      <Title level={4}>回测配置参数（6 项）</Title>
      <Row gutter={[12, 12]}>
        {[
          { title: '回测区间', tag: 'Period', icon: '📅', color: '#1677ff',
            desc: '设置回测的开始和结束日期。区间越长，样本越丰富，但市场环境变化越大。建议至少覆盖 2 年以上数据。',
            detail: '起止日期必须包含在 stock_daily 数据范围内。区间过长可能包含多个市场风格，需结合宏观背景分析。' },
          { title: '初始资金', tag: 'Init Capital', icon: '💰', color: '#52c41a',
            desc: '设置回测的初始资金金额（单位：元）。默认 1,000,000 元。',
            detail: '资金规模会影响个股仓位计算和流动性判断。资金越小，部分大市值股票可能无法买入。' },
          { title: '基准选择', tag: 'Benchmark', icon: '📊', color: '#fa8c16',
            desc: '选择对标基准：沪深300(000300.SH)、上证指数(000001.SH)、创业板指(399006.SZ)、中证500(000905.SH)、中证1000(000852.SH)等。',
            detail: '基准用于计算 Alpha（超额收益）和跟踪误差。建议选择与策略风格匹配的指数作为基准。' },
          { title: '交易费用', tag: 'Cost', icon: '💸', color: '#722ed1',
            desc: '佣金 ≥ 5 元/笔（双向）+ 印花税 0.1%（仅卖出）+ 过户费 0.02‰（沪深双向）。默认滑点 0.1%。',
            detail: '实盘中佣金可谈更低、印花税为法定不可调。滑点模拟成交价与指令价的偏差，高频策略需加大滑点。' },
          { title: '调仓频率', tag: 'Rebalance', icon: '🔄', color: '#13c2c2',
            desc: '每日(D)、每周(W)、每月(M)调仓。调仓频率由因子衰减速度决定（见因子衰减分析）。',
            detail: '日频成本最高但 Alpha 捕获最多；月频成本低但可能错过中期信号。需根据策略特性选择。' },
          { title: '成交模式', tag: 'Fill Model', icon: '⚡', color: '#eb2f96',
            desc: 'CLOSE（收盘价成交）、NEXT_OPEN（次日开盘价）、VWAP（成交量加权均价）。',
            detail: 'NEXT_OPEN 最接近实盘；CLOSE 会引入未来函数（当日收盘价才能下单，实盘中不可能）；VWAP 需要额外数据支持。' },
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

      {/* 绩效指标 */}
      <Title level={4}>绩效指标（三类 15 项）</Title>

      <Title level={5} style={{ marginTop: 12 }}>📈 收益类指标</Title>
      <Row gutter={[12, 12]}>
        {[
          { name: '年化收益率', formula: '(1 + 总收益率)^(250/交易天数) - 1',
            desc: '将总收益年化后的收益率，消除持仓时间长短的影响，便于不同策略横向比较。',
            standard: '年化 > 15% 优秀；8~15% 较好；< 8% 偏弱（需对比基准）' },
          { name: '总收益率', formula: '(期末净值 - 期初净值) / 期初净值',
            desc: '回测区间内策略的总收益，未年化。展示绝对收益水平。',
            standard: '结合年化一起看，短区间内总收益率参考价值有限' },
          { name: '超额收益', formula: '策略收益 - 基准收益',
            desc: '策略相对于基准的超额收益（Alpha），是衡量选股能力的关键指标。',
            standard: '年化超额 > 5% 优秀；2~5% 较好；< 2% 超额较弱' },
          { name: '胜率', formula: '盈利交易次数 / 总交易次数',
            desc: '所有平仓交易中盈利交易的比例。胜率高不等于收益高（盈亏比同样重要）。',
            standard: '> 50% 是基础要求；趋势策略胜率通常 30~40% 但盈亏比高；均值回归策略胜率可达 60%+' },
        ].map(m => (
          <Col xs={24} md={12} key={m.name}>
            <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
              <Title level={5}>{m.name}</Title>
              <Paragraph style={{ fontSize: 11, margin: '0 0 4px', fontFamily: 'monospace' }}>
                <Text code>{m.formula}</Text>
              </Paragraph>
              <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
              <Text type="secondary" style={{ fontSize: 11 }}>评价：{m.standard}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      <Title level={5} style={{ marginTop: 12 }}>⚠️ 风险类指标</Title>
      <Row gutter={[12, 12]}>
        {[
          { name: '最大回撤', formula: 'max(Peak_i - Valley_j) / Peak_i',
            desc: '回测期间从最高点到之后最低点的最大跌幅。衡量策略在最坏情况下的损失。',
            standard: '< 10% 极优秀；10~20% 优秀；20~30% 较好；> 30% 风险较大' },
          { name: '年化波动率', formula: 'StdDev(日收益率) × √250',
            desc: '策略收益率序列的标准差，反映策略的波动程度。',
            standard: '波动率越低说明策略越稳健；高波动策略需要更高的收益来补偿风险' },
          { name: '下行波动率', formula: 'StdDev(负收益日) × √250',
            desc: '仅计算负收益日的波动率，专注于下跌风险，比总波动率更准确衡量下行风险。',
            standard: '下行波动率 / 年化波动率 越小越好，说明下跌控制得好' },
          { name: 'VaR (95%)', formula: 'Percentile(日收益率, 5%)',
            desc: '在 95% 置信度下，单日最大损失不超过的值。95% VaR = -2.5% 表示 95% 的概率日损失不超过 2.5%。',
            standard: 'VaR 越小越好；结合 CVaR（Expected Shortfall）一起看更准确' },
        ].map(m => (
          <Col xs={24} md={12} key={m.name}>
            <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
              <Title level={5}>{m.name}</Title>
              <Paragraph style={{ fontSize: 11, margin: '0 0 4px', fontFamily: 'monospace' }}>
                <Text code>{m.formula}</Text>
              </Paragraph>
              <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
              <Text type="secondary" style={{ fontSize: 11 }}>评价：{m.standard}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      <Title level={5} style={{ marginTop: 12 }}>⚖️ 风险调整收益类指标</Title>
      <Row gutter={[12, 12]}>
        {[
          { name: '夏普比率 (Sharpe)', formula: '(年化收益 - 无风险利率) / 年化波动率',
            desc: '每承受一单位风险所获得的超额收益。最重要的综合指标之一。',
            standard: '> 2.0 极优秀；1.5~2.0 优秀；1.0~1.5 较好；< 1.0 较弱（无风险利率默认用 3%）' },
          { name: '索提诺比率 (Sortino)', formula: '(年化收益 - 目标收益) / 下行波动率',
            desc: '只考虑下行风险的夏普比率变体，更准确地反映投资者真实体验到的风险。',
            standard: '> 2.0 极优秀；1.5~2.0 优秀；> Sortino > Sharpe 说明下行控制好' },
          { name: 'Calmar 比率', formula: '年化收益 / 最大回撤',
            desc: '年化收益与最大回撤的比值，衡量每单位最大回撤产生的收益。',
            standard: '> 2.0 优秀；1.0~2.0 较好；< 1.0 表示回撤风险大于收益' },
          { name: '卡玛比率 (CAGR/MDD)', formula: '年化收益 / 最大回撤（与 Calmar 等价）',
            desc: '与 Calmar 比率相同表述，是投资者最直观理解的指标：每亏 1 元能赚几元。',
            standard: '年化 20% + 最大回撤 10% → Calmar = 2.0，非常优秀的风险收益特征' },
          { name: '信息比率 (IR)', formula: '超额收益均值 / 跟踪误差',
            desc: '衡量相对于基准的稳定超额收益能力。跟踪误差是超额收益的标准差。',
            standard: '> 0.5 稳定超额；> 1.0 优秀；< 0.3 超额不稳定' },
          { name: '盈亏比', formula: '平均盈利金额 / 平均亏损金额（绝对值）',
            desc: '盈利交易的平均收益与亏损交易的平均损失之比。趋势策略靠高盈亏比盈利。',
            standard: '> 1.5 较好；> 2.0 优秀；配合胜率一起看：30% 胜率 × 3 盈亏比 = 不错的策略' },
        ].map(m => (
          <Col xs={24} md={12} key={m.name}>
            <Card size="small" type="inner" style={{ borderLeft: '4px solid #722ed1' }}>
              <Title level={5}>{m.name}</Title>
              <Paragraph style={{ fontSize: 11, margin: '0 0 4px', fontFamily: 'monospace' }}>
                <Text code>{m.formula}</Text>
              </Paragraph>
              <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
              <Text type="secondary" style={{ fontSize: 11 }}>评价：{m.standard}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 回测报告内容 */}
      <Title level={4}>回测报告内容说明</Title>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="完整回测报告包含以下内容"
        description="绩效概览（核心指标卡片）→ 收益分析（净值曲线、超额曲线）→ 风险分析（回撤曲线、持仓分析）→ 交易记录（每次买卖明细）→ 归因分析（因子暴露与收益归因）"
      />
      <Row gutter={[12, 12]}>
        {[
          { title: '净值曲线', icon: '📈', desc: '展示策略净值随时间的变化，与基准净值对比。曲线越平滑、收益越高，策略越好。' },
          { title: '超额收益曲线', icon: '📊', desc: '策略净值 - 基准净值，反映策略相对于市场的超额收益能力。持续在 0 轴上方说明持续跑赢基准。' },
          { title: '回撤曲线', icon: '📉', desc: '展示从每个历史高点到当时的亏损幅度。回撤越大、持续时间越长，策略风险越高。' },
          { title: '持仓分析', icon: '🏦', desc: '展示各持仓的权重分布、换手率、持仓周期等，帮助了解策略的交易特征。' },
          { title: '交易明细', icon: '📋', desc: '每次买入/卖出的日期、股票、价格、数量、手续费、盈亏金额等完整记录。' },
          { title: '归因分析', icon: '🔬', desc: '分析策略收益中各因子的贡献，解释收益来源，帮助优化因子配置。' },
        ].map(m => (
          <Col xs={24} md={8} key={m.title}>
            <Card size="small" type="inner">
              <Title level={5}>{m.icon} {m.title}</Title>
              <Paragraph style={{ fontSize: 12, margin: 0 }}>{m.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Alert type="warning" showIcon style={{ marginTop: 16 }}
        message="⚠️ 注意：回测结果仅供参考，过往业绩不代表未来表现"
        description={
          <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
            <li>未来函数偏差：使用收盘价下单（CLOSE 模式）会高估收益</li>
            <li>流动性冲击：大额资金无法以收盘价全部成交</li>
            <li>滑点：实际成交价通常劣于回测价（特别是大盘股、小市值策略）</li>
            <li>过拟合：参数优化过度可能导致回测优秀但实盘失效</li>
            <li>市场变化：回测区间内的有效策略在区间外可能失效</li>
          </ul>
        }
      />
    </section>
  );
}

export function ManualBacktestCompare() {
  return (
    <section id="backtest-compare" style={{ paddingBottom: 32 }}>
      <Title level={2}>策略对比</Title>
      <Paragraph>
        策略对比模块用于同时比较多个策略的回测表现，帮助投资者选择最优策略。
        支持选择 2~4 个已完成回测的策略进行横向对比。
      </Paragraph>

      <Title level={4}>对比维度（5 个）</Title>
      <Row gutter={[12, 12]}>
        {[
          { title: '收益对比', icon: '📈', color: '#52c41a',
            metrics: ['年化收益率', '总收益率', '超额收益率', 'Alpha'],
            method: '以柱状图/雷达图展示各策略的收益类指标，优选年化高且稳定、超额持续为正的策略。' },
          { title: '风险对比', icon: '📉', color: '#fa8c16',
            metrics: ['最大回撤', '年化波动率', '下行波动率', 'VaR(95%)'],
            method: '以柱状图/箱线图展示各策略的风险指标，优选回撤小、波动低的策略（与收益对比权衡）。' },
          { title: '风险调整收益对比', icon: '⚖️', color: '#722ed1',
            metrics: ['夏普比率', '索提诺比率', 'Calmar比率', '信息比率'],
            method: '以雷达图综合展示，靠近外圈（数值越大）的策略综合表现越好。' },
          { title: '收益稳定性对比', icon: '📊', color: '#1677ff',
            metrics: ['年化收益标准差', '月度胜率', '季度胜率', '最大连续亏损月数'],
            method: '观察各指标在时间维度上的稳定性，月度/季度收益分布越均匀越好。' },
          { title: '交易成本对比', icon: '💸', color: '#eb2f96',
            metrics: ['总交易次数', '换手率', '累计佣金', '累计印花税'],
            method: '高频策略交易成本占比高，需确认扣除成本后收益仍显著为正。' },
        ].map(m => (
          <Col xs={24} md={12} key={m.title}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color}` }}>
              <Title level={5}>{m.icon} {m.title}</Title>
              <Paragraph style={{ fontSize: 12, margin: '0 4px 4px' }}>
                核心指标：{m.metrics.map(t => <Tag key={t} color="default" style={{ fontSize: 11 }}>{t}</Tag>)}
              </Paragraph>
              <Paragraph style={{ fontSize: 12, margin: 0 }}>{m.method}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Title level={4}>图表解读方法</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="净值曲线叠加图">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              将多个策略的净值曲线绘制在同一坐标系中。<Text strong>最上方曲线</Text>收益最高，
              <Text strong>距离最远</Text>的两条曲线说明策略分化大。
              曲线越平滑说明策略越稳定，交叉越多说明策略风格差异大。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="雷达图（综合评价）">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              以夏普/索提诺/Calmar/IR/胜率等多维指标构建雷达图。
              <Text strong>面积越大</Text>的综合表现越好，
              <Text strong>偏心程度</Text>反映策略的擅长方向（高收益高风险 vs 低回撤低收益）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="回撤对比图">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              将各策略的回撤曲线叠加展示。<Text strong>回撤最小且回撤时间最短</Text>的策略风控最佳。
              若一个策略回撤大但快速恢复（V形），与回撤大且长期低迷（L形）的策略性质不同。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="月度收益分布箱线图">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示各策略月度收益的分布箱线图。
              <Text strong>箱体越窄</Text>说明月度收益越稳定，
              <Text strong>异常值</Text>（远离箱体的点）提示极端行情下的策略表现。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert type="info" showIcon style={{ marginTop: 16 }}
        message="对比选优建议"
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
  );
}

export default { ManualStrategies, ManualBacktests, ManualBacktestCompare };
