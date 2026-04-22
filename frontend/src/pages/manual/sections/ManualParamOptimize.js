import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps, Table, Divider } from 'antd';
import { ExperimentOutlined, RocketOutlined, CalculatorOutlined, InfoCircleOutlined, CheckCircleOutlined, WarningOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export function ManualParamOptimize() {
  const paramColumns = [
    { title: '参数名称', dataIndex: 'name', key: 'name', width: 120 },
    { title: '参数说明', dataIndex: 'desc', key: 'desc' },
    { title: '为什么需要优化', dataIndex: 'why', key: 'why' },
    { title: '建议取值范围', dataIndex: 'range', key: 'range', width: 150 },
  ];

  const paramData = [
    {
      key: '1',
      name: '止损比例',
      desc: '当持仓股票亏损达到该比例时自动卖出，控制单笔交易的最大损失',
      why: '止损太紧会频繁触发导致小额亏损累积；止损太松则单笔亏损过大。不同波动率的股票需要不同的止损设置',
      range: '3% ~ 10%',
    },
    {
      key: '2',
      name: '止盈比例',
      desc: '当持仓股票盈利达到该比例时自动卖出，锁定利润',
      why: '止盈太早会错过大趋势；止盈太晚可能利润回吐。需与止损比例配合形成合理的盈亏比',
      range: '5% ~ 20%',
    },
    {
      key: '3',
      name: '最大持仓数',
      desc: '策略同时持有的最大股票数量，分散投资风险',
      why: '持仓太少风险集中；持仓太多资金分散收益稀释。需与资金规模和选股频率匹配',
      range: '5 ~ 30只',
    },
    {
      key: '4',
      name: '初始资金',
      desc: '回测使用的初始资金规模，影响仓位计算和交易费用',
      why: '资金规模影响单笔交易的绝对收益和费用占比。大资金需要考虑冲击成本，小资金需要考虑交易费用门槛',
      range: '10万 ~ 1000万',
    },
  ];

  const metricColumns = [
    { title: '指标', dataIndex: 'metric', key: 'metric', width: 120 },
    { title: '优秀标准', dataIndex: 'excellent', key: 'excellent' },
    { title: '可接受', dataIndex: 'acceptable', key: 'acceptable' },
    { title: '需警惕', dataIndex: 'warning', key: 'warning' },
  ];

  const metricData = [
    {
      key: '1',
      metric: 'Sharpe比率',
      excellent: '> 2.0',
      acceptable: '1.0 ~ 2.0',
      warning: '< 1.0',
    },
    {
      key: '2',
      metric: '年化收益率',
      excellent: '> 30%',
      acceptable: '15% ~ 30%',
      warning: '< 15% 或 > 100%（过拟合风险）',
    },
    {
      key: '3',
      metric: '最大回撤',
      excellent: '< 15%',
      acceptable: '15% ~ 30%',
      warning: '> 30%',
    },
    {
      key: '4',
      metric: 'Calmar比率',
      excellent: '> 2.0',
      acceptable: '1.0 ~ 2.0',
      warning: '< 1.0',
    },
    {
      key: '5',
      metric: '胜率',
      excellent: '> 55%',
      acceptable: '45% ~ 55%',
      warning: '< 45%',
    },
  ];

  return (
    <section id="param-optimize" style={{ paddingBottom: 32 }}>
      <Title level={2}><ExperimentOutlined /> 参数优化（网格搜索）</Title>
      <Paragraph>
        参数优化模块通过穷举指定参数网格的所有组合，找出使目标函数最优的参数配置。
        页面分为左右两部分：左侧创建优化任务，右侧查看优化历史和结果。
      </Paragraph>

      <Title level={4}>页面布局</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>左侧：任务配置</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              可折叠区域，包含策略选择、日期范围、参数网格配置、目标函数选择。
              点击「开始优化」提交任务。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>右侧：优化历史</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              显示所有优化任务列表，点击「+」展开查看详情。
              详情包含最优结果、热力图、全部参数组合表格。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>优化流程</Title>
      <Steps
        current={3}
        items={[
          { title: '选择策略', description: '选择一个已创建的策略' },
          { title: '设置区间', description: '配置回测日期范围' },
          { title: '参数网格', description: '设置各参数的取值范围' },
          { title: '目标函数', description: '选择优化目标' },
          { title: '执行搜索', description: '自动网格搜索' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>可优化参数详解</Title>
      <Paragraph>
        以下参数对策略表现影响最大，建议优先进行优化：
      </Paragraph>
      <Table 
        columns={paramColumns} 
        dataSource={paramData} 
        size="small" 
        pagination={false}
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>目标函数选择指南</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>Sharpe 比率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>公式：</Text> (年化收益 - 无风险利率) / 年化波动率<br/>
              <Text strong>适用场景：</Text>追求风险调整后收益最大化<br/>
              <Text strong>优点：</Text>综合考虑收益和风险，是最经典的评价指标<br/>
              <Text strong>注意：</Text>对波动率惩罚较重，可能错过高波动高收益机会
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>年化收益率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>公式：</Text> 总收益 / 回测年数<br/>
              <Text strong>适用场景：</Text>追求绝对收益最大化<br/>
              <Text strong>优点：</Text>直观易懂，直接反映赚钱能力<br/>
              <Text strong>注意：</Text>可能忽视风险，高收益率伴随高回撤风险
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #faad14' }}>
            <Title level={5}>Calmar 比率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>公式：</Text> 年化收益 / 最大回撤<br/>
              <Text strong>适用场景：</Text>追求收益与最大风险的平衡<br/>
              <Text strong>优点：</Text>关注极端风险，适合风险厌恶型投资者<br/>
              <Text strong>注意：</Text>只考虑最大回撤，可能忽视其他风险
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>参数网格配置</Title>
      <Paragraph>
        支持两种参数设置方式：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner">
            <Title level={5}>范围模式</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              设置最小值、最大值和步长，自动生成等差数列。
              例如：最小值=10、最大值=30、步长=5 → [10, 15, 20, 25, 30]
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner">
            <Title level={5}>列表模式</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              手动输入逗号分隔的数值列表。
              例如：10,20,30 表示仅测试这3个值。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>结果解读与判断标准</Title>
      <Paragraph>
        优化完成后，如何判断参数组合是否优秀？参考以下标准：
      </Paragraph>
      <Table 
        columns={metricColumns} 
        dataSource={metricData} 
        size="small" 
        pagination={false}
        style={{ marginBottom: 16 }}
      />

      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        style={{ marginBottom: 16 }}
        message="结果使用指南"
        description={
          <div>
            <p><Text strong>1. 应用最优参数：</Text>点击「应用到回测」按钮，系统会自动将最优参数填充到回测创建页面，您可以直接使用或进一步微调。</p>
            <p><Text strong>2. 参数稳定性检验：</Text>查看热力图，如果最优参数位于网格边缘，建议扩大该参数的搜索范围；如果最优区域集中且平滑，说明参数稳定。</p>
            <p><Text strong>3. 多目标权衡：</Text>不要只看单一指标。Sharpe最优的参数可能收益平庸，收益最高的参数可能回撤过大。建议综合多个指标选择。</p>
            <p><Text strong>4. 样本外验证：</Text>将回测区间分为训练集和测试集，用训练集优化参数，在测试集验证效果，避免过拟合。</p>
          </div>
        }
      />

      <Title level={4}>任务详情（点击展开）</Title>
      <Paragraph>
        点击优化历史中的「+」展开任务详情：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #ffd591' }}>
            <Title level={5}>最优结果</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              显示得分最高的参数组合，黄色高亮背景。包含该参数组合下的所有回测指标。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #d9d9d9' }}>
            <Title level={5}>参数网格</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              灰色背景显示各参数的取值范围，方便核对优化空间。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #cf1322' }}>
            <Title level={5}>参数热力图</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              两个参数时显示热力图，颜色越深表示该参数组合效果越好，可直观看出参数敏感性。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>全部参数组合</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              表格展示所有参数组合及指标，支持排序和分页。可查看次优结果作为备选。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>运行状态</Title>
      <Paragraph>
        任务提交后自动进入「运行中」状态，详情页面实时显示进度。
        完成后状态自动更新为「已完成」，可查看最优结果和热力图。
      </Paragraph>

      <Alert
        type="warning"
        showIcon
        icon={<WarningOutlined />}
        style={{ marginTop: 16 }}
        message="组合数爆炸警告"
        description="总组合数 = 各参数取值数的乘积。例如3个参数各取10个值，总组合数=10×10×10=1000。建议将总组合数控制在 200 以内，否则耗时过长。"
      />

      <Alert
        type="error"
        showIcon
        style={{ marginTop: 16 }}
        message="过拟合风险提示"
        description="参数优化本质是在历史数据上寻找最优解，存在过拟合风险。建议：1) 使用足够长的回测区间（至少2年）；2) 进行样本外测试；3) 参数不要过于精细；4) 关注参数的经济学逻辑是否合理。"
      />
    </section>
  );
}

export function ManualFromBacktestToTrading() {
  return (
    <section id="from-backtest-to-trading" style={{ paddingBottom: 32 }}>
      <Title level={2}><RocketOutlined /> 从回测到实战</Title>
      <Paragraph>
        本模块帮助投资者将回测验证过的策略应用于实际交易，实现策略的闭环管理。
      </Paragraph>

      <Title level={4}>工作流程</Title>
      <Steps
        current={2}
        items={[
          { title: '策略研发', description: '因子分析、策略构建' },
          { title: '回测验证', description: '历史数据回测' },
          { title: '参数优化', description: '网格搜索最优参数' },
          { title: '模拟交易', description: '验证策略实盘表现' },
          { title: '实盘对接', description: '对接券商API' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>注意事项</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="过拟合风险">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              避免在单一历史区间上过拟合，建议使用样本外测试验证。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="实盘差异">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              实盘交易需考虑滑点、流动性冲击、交易延迟等因素。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 16 }}
        message="风险提示"
        description="量化策略存在风险，过往业绩不代表未来表现。请根据自身风险承受能力谨慎投资。"
      />
    </section>
  );
}

export function ManualFactorWeightOptimize() {
  return (
    <section id="factor-weight-optimize" style={{ paddingBottom: 32 }}>
      <Title level={2}><CalculatorOutlined /> 因子权重优化</Title>
      <Paragraph>
        因子权重优化模块帮助投资者为多因子组合找到最优的因子权重配置。
        支持三种经典优化方法，适用于不同的投资目标和风险偏好。
      </Paragraph>

      <Title level={4}>三种优化方法</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #8c8c8c' }}>
            <Title level={5}>等权（EQUAL）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              最简单的配置方式，所有因子权重相等（1/n）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #5470c6' }}>
            <Title level={5}>均值-方差（MARKOWITZ）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              基于 Markowitz 现代投资组合理论，优化使 Sharpe 比率最大化。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
            <Title level={5}>风险平价（RISK_PARITY）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              使每个因子对组合总风险的贡献相等。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="数据说明"
        description="权重优化使用因子在截面上的 rank_value（归一化因子值）作为收益代理，分析因子间的相关性和收益特征。"
      />
    </section>
  );
}

export default { ManualParamOptimize, ManualFromBacktestToTrading, ManualFactorWeightOptimize };
