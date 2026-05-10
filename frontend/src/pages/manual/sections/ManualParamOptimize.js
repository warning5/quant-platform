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
    {
      key: '5',
      name: '回看天数',
      desc: '计算技术指标时回顾的历史K线数量，决定指标的敏感度',
      why: '回看天数太短指标噪声大；太长则信号滞后。不同策略需要不同的敏感度设置',
      range: '10 ~ 120天',
    },
    {
      key: '6',
      name: '持仓周期',
      desc: '每次买入后持有多少交易日再卖出，即强制持仓时间',
      why: '持仓周期太短无法捕捉趋势；太长则资金利用率低。需与策略逻辑匹配（短线/中线/长线）',
      range: '5 ~ 60天',
    },
    {
      key: '7',
      name: '入选分数阈值',
      desc: '股票综合评分的最低要求，只有评分高于此阈值的股票才会被买入',
      why: '阈值太高选不出足够股票；太低则买入质量差的股票。需与最大持仓数配合使用',
      range: '50 ~ 80分',
    },
    {
      key: '8',
      name: '滑点比例',
      desc: '模拟实际交易时，成交价相对于理想价的偏离比例',
      why: '滑点设置太低回测结果过于乐观；太高则过于悲观。可根据股票流动性和资金规模合理设置',
      range: '0.1% ~ 0.5%',
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
    {
      key: '6',
      metric: '盈亏比',
      excellent: '> 2.5',
      acceptable: '1.5 ~ 2.5',
      warning: '< 1.5',
    },
    {
      key: '7',
      metric: '年化波动率',
      excellent: '< 20%',
      acceptable: '20% ~ 35%',
      warning: '> 35%',
    },
  ];

  return (
    <section id="param-optimize" style={{ paddingBottom: 16 }}>
      <Title level={2}><ExperimentOutlined /> 参数优化（网格搜索）</Title>
      <Paragraph>
        参数优化模块通过<Text strong>穷举指定参数网格的所有组合</Text>，找出使目标函数最优的参数配置。
        本质上是在历史数据上做「压力测试」——把所有可能的参数组合都跑一遍回测，用数据说话，找出哪个组合表现最好。
      </Paragraph>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        style={{ marginBottom: 16 }}
        message="适用场景"
        description="当你已经构建了一个策略，但不确定止损设5%还是10%、持仓选10只还是20只时，用参数优化来寻找答案。不适合用于策略逻辑本身的开发（那是因子分析和策略构建要做的事）。"
      />

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>网格搜索原理</Title>
      <Paragraph>
        网格搜索（Grid Search）是一种<Text strong>穷举式</Text>的参数优化方法。假设你要优化2个参数：
      </Paragraph>
      <Card size="small" style={{ background: '#f6f8fa', marginBottom: 16 }} bordered={false}>
        <Paragraph style={{ fontSize: 13, margin: 0 }}>
          <Text strong>示例：</Text>优化「止损比例」和「最大持仓数」<br/>
          止损比例取值：[5%, 8%, 10%]（3个值）<br/>
          最大持仓数取值：[10, 15, 20]（3个值）<br/>
          <Text strong>总组合数 = 3 × 3 = 9</Text><br/>
          系统会依次运行这9个组合，每个都完整跑一遍回测，最后对比结果找出最优解。
        </Paragraph>
      </Card>
      <Paragraph>
        参数越多、每个参数的取值越多，总组合数呈<Text strong>指数级增长</Text>。
        3个参数各取5个值 = 125种组合；4个参数各取5个值 = 625种组合。
        这也是为什么要把总组合数控制在200以内的原因。
      </Paragraph>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>页面布局</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>左侧：任务配置</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              可折叠区域，包含策略选择、日期范围、参数网格配置、目标函数选择。
              点击「开始优化」提交任务，系统自动后台运行。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>右侧：优化历史</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              显示所有优化任务列表，点击「+」展开查看详情。
              详情包含最优结果、热力图、全部参数组合表格。
              支持按状态筛选（全部/运行中/已完成/失败）。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>完整操作流程</Title>
      <Steps
        direction="vertical"
        size="small"
        items={[
          { key: '1', title: '选择策略', description: '在左侧下拉框选择一个已创建且回测通过的策略。建议先用小范围快速验证，再扩大搜索。' },
          { key: '2', title: '设置回测区间', description: '建议至少2年数据。区间太短结果不可靠；太长则计算量大。可分段优化：用2019-2021数据优化，用2022-2024数据验证。' },
          { key: '3', title: '配置参数网格', description: '为每个待优化参数设置取值范围。建议先用粗网格（少取值）快速扫描，再用细网格（多取值）在优秀区域精细搜索。' },
          { key: '4', title: '选择目标函数', description: '选择优化的目标。首次优化建议用Sharpe比率，它综合考虑收益和风险，是最稳健的指标。' },
          { key: '5', title: '提交并等待', description: '点击「开始优化」，任务进入队列。可在右侧查看进度。100个组合约需3-5分钟（取决于策略复杂度）。' },
          { key: '6', title: '解读结果', description: '查看最优参数组合、热力图、全部结果表格。重点看最优参数是否位于网格边缘（如果是，需扩大搜索范围）。' },
          { key: '7', title: '应用或进一步验证', description: '点击「应用到回测」使用最优参数，或在样本外数据上验证该参数是否依然有效。' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>可优化参数详解</Title>
      <Paragraph>
        以下参数对策略表现影响最大，建议优先进行优化（按影响程度排序）：
      </Paragraph>
      <Table
        columns={paramColumns}
        dataSource={paramData}
        size="small"
        pagination={false}
        rowKey="key"
        style={{ marginBottom: 16 }}
      />
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="参数选择建议"
        description="不要一次性优化太多参数。建议每次只优化2-3个参数，其他参数固定为合理值。这样既能找到较优解，又能控制计算量。等核心参数确定后，再 fine-tune 其他参数。"
      />

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>目标函数选择指南</Title>
      <Paragraph>
        目标函数决定了「什么是最好的参数」。不同投资目标应选择不同的目标函数：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a', height: '100%' }}>
            <Title level={5}>Sharpe 比率（推荐首选）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>公式：</Text> (年化收益 - 无风险利率) / 年化波动率<br/><br/>
              <Text strong>适用场景：</Text>大多数情况，追求风险调整后收益最大化<br/>
              <Text strong>优点：</Text>综合考虑收益和风险，经典且稳健，不易过拟合<br/>
              <Text strong>注意：</Text>对波动率惩罚较重，可能错过高波动高收益机会<br/>
              <Text strong>建议使用场景：</Text>首次优化、稳健型策略
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff', height: '100%' }}>
            <Title level={5}>年化收益率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>公式：</Text> (期末资金 / 期初资金)^(252/交易日数) - 1<br/><br/>
              <Text strong>适用场景：</Text>追求绝对收益最大化，能承受较大波动<br/>
              <Text strong>优点：</Text>直观易懂，直接反映赚钱能力<br/>
              <Text strong>注意：</Text>可能忽视风险，高收益率可能伴随高回撤<br/>
              <Text strong>建议使用场景：</Text>收益型策略、风险承受能力强
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #faad14', height: '100%' }}>
            <Title level={5}>Calmar 比率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              <Text strong>公式：</Text> 年化收益 / 最大回撤<br/><br/>
              <Text strong>适用场景：</Text>对回撤敏感，追求收益与极端风险的平衡<br/>
              <Text strong>优点：</Text>关注极端风险（最大回撤），适合风险厌恶型投资者<br/>
              <Text strong>注意：</Text>只考虑最大回撤，可能忽视其他风险维度<br/>
              <Text strong>建议使用场景：</Text>回撤敏感型策略、产品化策略
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>参数网格配置技巧</Title>
      <Paragraph>
        合理的网格配置能在计算量和精度之间取得平衡：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner">
            <Title level={5}>范围模式（推荐）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              设置最小值、最大值和步长，自动生成等差数列。<br/><br/>
              <Text strong>示例：</Text>最小值=5、最大值=15、步长=5<br/>
              生成：[5, 10, 15]（3个值）<br/><br/>
              <Text strong>技巧：</Text>先用大步长粗搜（如步长=5），找到优秀区域后再用小步长细搜（如步长=1）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner">
            <Title level={5}>列表模式</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              手动输入逗号分隔的数值列表，适合非等间距取值。<br/><br/>
              <Text strong>示例：</Text>5,8,10,12,15<br/>
              表示仅测试这5个特定值。<br/><br/>
              <Text strong>技巧：</Text>适合对参数有先验认识的情况，可以精确指定想要测试的值。
            </Paragraph>
          </Card>
        </Col>
      </Row>
      <Card size="small" style={{ background: '#fffbe6', marginTop: 16 }} bordered={false}>
        <Paragraph style={{ fontSize: 13, margin: 0 }}>
          <InfoCircleOutlined /> <Text strong>两阶段优化策略：</Text><br/>
          第一阶段（粗搜）：用范围模式，大步长，快速找出优秀参数区域。例如止损比例测试 [3%, 5%, 8%, 10%, 15%]。<br/>
          第二阶段（细搜）：在优秀区域用列表模式精确测试。例如发现5%-8%表现最好，则精细测试 [5%, 6%, 7%, 8%]。
        </Paragraph>
      </Card>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>结果解读与判断标准</Title>
      <Paragraph>
        优化完成后，如何判断参数组合是否优秀？参考以下标准（<Text strong>同时满足多项优秀标准</Text>才可考虑采用）：
      </Paragraph>
      <Table
        columns={metricColumns}
        dataSource={metricData}
        size="small"
        pagination={false}
        rowKey="key"
        style={{ marginBottom: 16 }}
      />

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>任务详情说明（点击「+」展开）</Title>
      <Paragraph>
        每个优化任务可以展开查看4个维度的详情：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #ffd591', height: '100%' }}>
            <Title level={5}>✅ 最优结果</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              得分最高的参数组合，<Text strong>黄色高亮</Text>背景。
              包含该组合下的完整回测指标（Sharpe、年化收益、最大回撤、胜率、盈亏比等）。
              点击「应用到回测」可一键使用。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #d9d9d9', height: '100%' }}>
            <Title level={5}>📊 参数网格</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              灰色背景显示各参数的取值范围，方便核对优化空间。
              如果最优参数位于网格边缘（如止损比例最优值为设置的最小值），说明需要<Text strong>扩大该参数的搜索范围</Text>。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #cf1322', height: '100%' }}>
            <Title level={5}>🔥 参数热力图</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              仅当两个参数参与优化时显示。颜色越深表示效果越好。
              <Text strong>平滑的深色区域</Text> = 参数稳定；<Text strong>零散的深色点</Text> = 可能过拟合。
              如果热力图有明显规律，说明这两个参数对策略影响显著。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff', height: '100%' }}>
            <Title level={5}>📋 全部参数组合</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              表格展示<Text strong>所有</Text>参数组合及对应指标，支持按任意列排序。
              可查看次优结果作为备选（最优参数可能因为过拟合而不稳定）。
              建议选择「优秀区域内」表现稳定（而非单一最高）的参数组合。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>结果使用指南</Title>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        style={{ marginBottom: 16 }}
        message="优化结果的正确使用方式"
        description={
          <div>
            <p><Text strong>1. 应用最优参数：</Text>点击「应用到回测」按钮，系统会自动将最优参数填充到回测创建页面，您可以直接运行或进一步微调。</p>
            <p><Text strong>2. 参数稳定性检验：</Text>查看热力图，如果最优参数位于网格边缘，建议扩大该参数的搜索范围；如果最优区域集中且平滑，说明参数稳定。</p>
            <p><Text strong>3. 多目标权衡：</Text>不要只看单一指标。Sharpe最优的参数可能收益平庸，收益最高的参数可能回撤过大。建议综合多个指标选择。</p>
            <p><Text strong>4. 样本外验证（重要）：</Text>将回测区间分为训练集（如前3年）和测试集（如最近1年），用训练集优化参数，在测试集验证效果。如果训练集表现远好于测试集，说明存在过拟合。</p>
            <p><Text strong>5. 滚动窗口验证（进阶）：</Text>将历史数据分为多个滚动窗口，每个窗口都做参数优化，观察最优参数是否稳定。稳定说明参数具有鲁棒性；不稳定说明策略对市场状态敏感。</p>
          </div>
        }
      />

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>过拟合风险与识别</Title>
      <Paragraph>
        参数优化最大的陷阱是<Text strong>过拟合（Overfitting）</Text>——在历史数据上表现完美，但未来实战一塌糊涂。
        以下是识别和避免过拟合的方法：
      </Paragraph>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="⚠️ 过拟合的信号">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              • 最优参数过于极端（如止损=3.1%，精确到小数点后一位）<br/>
              • 训练集表现远好于测试集（差距大于30%）<br/>
              • 热力图上最优区域零散，没有连续的高质量区域<br/>
              • 参数稍有变动，表现就大幅下滑（参数「尖峰」而非「平台」）<br/>
              • 回测收益率异常高（如年化大于100%）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="✅ 如何避免过拟合">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              • 使用足够长的回测区间（至少包含一轮牛熊周期）<br/>
              • 参数取值不要过于精细（步长不宜太小）<br/>
              • 进行样本外测试（训练集优化，测试集验证）<br/>
              • 优先选择参数「平台」而非「尖峰」（参数小范围变动不影响结果）<br/>
              • 关注经济学逻辑：参数是否有合理的解释
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>常见问题（FAQ）</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24}>
          <Card size="small" type="inner">
            <Title level={5}>Q: 优化任务要跑多久？</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              取决于总组合数和策略复杂度。一般规律：1个组合约需2-5秒。100个组合约3-5分钟，200个组合约10分钟。
              如果任务运行时间过长，可以刷新页面查看进度，任务在后台继续运行，不会中断。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24}>
          <Card size="small" type="inner">
            <Title level={5}>Q: 为什么最优参数在边缘？</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              说明当前搜索范围不够大，真实最优值可能在范围之外。建议：1) 扩大该参数的搜索范围；2) 如果扩大后最优值依然在边缘，可能该参数对策略影响不明显，可以考虑固定该参数。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24}>
          <Card size="small" type="inner">
            <Title level={5}>Q: 多个目标函数给出不同的最优参数，选哪个？</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              这是正常现象。不同目标函数关注点不同。建议：1) 如果是首次优化，优先参考Sharpe比率的结果；2) 如果策略对回撤敏感，优先参考Calmar比率的结果；3) 最终选择时综合多个指标，选一个「各方面都不差」的参数组合，而非「某一方面极端好」的组合。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24}>
          <Card size="small" type="inner">
            <Title level={5}>Q: 优化结果在样本外表现不好怎么办？</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              这是过拟合的典型表现。建议：1) 扩大回测区间重新优化；2) 减少优化的参数数量；3) 增大参数步长（降低精度）；4) 考虑策略逻辑本身是否需要调整（可能因子已失效）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24}>
          <Card size="small" type="inner">
            <Title level={5}>Q: 可以中断正在运行的优化任务吗？</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              目前不支持中断。如果设定的组合数过多，只能等待完成。因此建议首次优化时先设少量组合（如20个以内）做测试，确认没问题后再扩大搜索范围。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider dashed style={{ margin: '16px 0' }} />
      <Title level={4}>运行状态说明</Title>
      <Paragraph>
        任务提交后自动进入「运行中」状态，详情页面实时显示进度（已完成的组合数 / 总组合数）。
        完成后状态自动更新为「已完成」，可查看最优结果和热力图。如果运行失败，会显示错误原因（常见原因：回测区间太短、参数设置非法等）。
      </Paragraph>

      <Alert
        type="warning"
        showIcon
        icon={<WarningOutlined />}
        style={{ marginTop: 16, marginBottom: 16 }}
        message="⚠️ 组合数爆炸警告"
        description="总组合数 = 各参数取值数的乘积。例如3个参数各取10个值，总组合数=10×10×10=1000。建议：1) 总组合数控制在200以内；2) 先优化核心参数（如止损、持仓数），再逐步加入其他参数；3) 使用两阶段优化策略（先粗搜再细搜）。"
      />

      <Alert
        type="error"
        showIcon
        style={{ marginTop: 16 }}
        message="🚨 过拟合风险提示"
        description="参数优化本质是在历史数据上寻找最优解，存在过拟合风险。牢记：1) 使用足够长的回测区间（至少2年，最好包含不同市场状态）；2) 进行样本外测试；3) 参数不要过于精细（步长不宜小于1%）；4) 关注参数的经济学逻辑是否合理（不能只看数据）；5) 过去的最优不代表未来最优，定期重新优化很重要。"
      />
    </section>
  );
}

export function ManualFromBacktestToTrading() {
  return (
    <section id="from-backtest-to-trading" style={{ paddingBottom: 16 }}>
      <Title level={2}><RocketOutlined /> 从回测到实战</Title>
      <Paragraph>
        本模块帮助投资者将回测验证过的策略应用于实际交易，实现策略的闭环管理。
      </Paragraph>

      <Title level={4}>工作流程</Title>
      <Steps
        current={2}
        items={[
          { key: '1', title: '策略研发', description: '因子分析、策略构建' },
          { key: '2', title: '回测验证', description: '历史数据回测' },
          { key: '3', title: '参数优化', description: '网格搜索最优参数' },
          { key: '4', title: '模拟交易', description: '验证策略实盘表现' },
          { key: '5', title: '实盘对接', description: '对接券商API' },
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
  const methodColumns = [
    { title: '方法', dataIndex: 'method', key: 'method', width: 150 },
    { title: '数学原理', dataIndex: 'math', key: 'math' },
    { title: '适用场景', dataIndex: 'scenario', key: 'scenario' },
    { title: '优点', dataIndex: 'pros', key: 'pros' },
    { title: '缺点', dataIndex: 'cons', key: 'cons' },
  ];

  const methodData = [
    {
      key: '1',
      method: '等权（EQUAL）',
      math: 'w_i = 1/n，每个因子权重相等',
      scenario: '因子数量较少、因子间相关性低、缺乏历史数据时',
      pros: '简单直观，无需估计协方差矩阵，避免估计误差',
      cons: '未考虑因子收益能力和风险贡献差异，可能配置次优',
    },
    {
      key: '2',
      method: '均值-方差（MARKOWITZ）',
      math: 'max Sharpe = (w^T μ) / sqrt(w^T Σ w)，求解最优权重w',
      scenario: '因子收益特征稳定、有足够历史数据、追求风险调整后收益最大化',
      pros: '理论上最优，最大化风险调整后收益',
      cons: '对输入参数敏感，协方差矩阵估计困难，可能产生极端权重',
    },
    {
      key: '3',
      method: '风险平价（RISK_PARITY）',
      math: 'RC_i = w_i (Σw)_i / (w^T Σ w) = 1/n，各因子风险贡献相等',
      scenario: '希望各因子对组合风险贡献均衡、担心单一因子风险暴露过大',
      pros: '风险分散，权重相对稳定，对输入参数不敏感',
      cons: '可能牺牲部分收益，权重分配可能不符合收益预期',
    },
  ];

  const factorColumns = [
    { title: '因子类型', dataIndex: 'type', key: 'type', width: 120 },
    { title: '代表因子', dataIndex: 'factors', key: 'factors' },
    { title: '因子特点', dataIndex: 'feature', key: 'feature' },
    { title: '权重建议', dataIndex: 'weight', key: 'weight' },
  ];

  const factorData = [
    {
      key: '1',
      type: '技术因子',
      factors: 'MA5、RSI、MACD、布林带等',
      feature: '短期有效性强，但容易失效，与市场环境相关',
      weight: '建议占比20%-40%，不宜过高',
    },
    {
      key: '2',
      type: '财务因子',
      factors: 'ROE、净利润增长率、毛利率等',
      feature: '长期有效性强，稳定性好，更新频率低',
      weight: '建议占比30%-50%，可作为核心配置',
    },
    {
      key: '3',
      type: '估值因子',
      factors: 'PE、PB、PS等',
      feature: '均值回归特性，长期有效但短期可能失效',
      weight: '建议占比20%-30%，适合价值型策略',
    },
    {
      key: '4',
      type: '质量因子',
      factors: '资产负债率、现金流比率等',
      feature: '防御性强，在市场下行期表现较好',
      weight: '建议占比10%-20%，用于风险控制',
    },
    {
      key: '5',
      type: '情绪因子',
      factors: '换手率、波动率等',
      feature: '反映市场情绪，短期波动大',
      weight: '建议占比不超过15%，作为辅助',
    },
  ];

  return (
    <section id="factor-weight-optimize" style={{ paddingBottom: 16 }}>
      <Title level={2}><CalculatorOutlined /> 因子权重优化</Title>
      <Paragraph>
        因子权重优化模块帮助投资者为多因子组合找到最优的因子权重配置。
        支持三种经典优化方法，适用于不同的投资目标和风险偏好。
      </Paragraph>

      <Title level={4}>为什么需要因子权重优化？</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner">
            <Title level={5}>因子异质性</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              不同因子具有不同的收益能力、波动性和相关性。等权配置忽视了这些差异，可能导致次优组合。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner">
            <Title level={5}>风险分散</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              合理的权重配置可以降低组合整体波动，提高风险调整后收益（Sharpe比率）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner">
            <Title level={5}>适应市场</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              不同市场环境下各因子表现不同。动态调整权重可以更好地适应市场变化。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>三种优化方法详解</Title>
      <Table 
        columns={methodColumns} 
        dataSource={methodData} 
        size="small" 
        pagination={false}
        rowKey="key"
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>因子类型与权重建议</Title>
      <Paragraph>
        系统中共有107个因子，分为5大类。不同类型因子的特点和配置建议如下：
      </Paragraph>
      <Table 
        columns={factorColumns} 
        dataSource={factorData} 
        size="small" 
        pagination={false}
        rowKey="key"
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>页面操作指南</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="选择因子">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              从左侧因子列表中选择需要优化的因子（建议5-15个）。选择时应考虑：
              <br/>• 因子间的相关性（避免高度相关的因子）
              <br/>• 因子的逻辑合理性
              <br/>• 因子的数据覆盖度
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="设置参数">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              配置优化参数：
              <br/>• <Text strong>观察期：</Text>计算因子收益和相关性的历史窗口（建议60-252天）
              <br/>• <Text strong>优化方法：</Text>选择EQUAL、MARKOWITZ或RISK_PARITY
              <br/>• <Text strong>目标日期：</Text>计算权重的目标日期
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>结果解读</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>权重分布</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              饼图展示各因子的权重占比。MARKOWITZ方法可能产生极端权重（某些因子权重接近0），RISK_PARITY权重相对均衡。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>预期收益与风险</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              基于历史数据估算的组合预期年化收益、年化波动率和Sharpe比率。这是理论值，实际表现可能不同。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #faad14' }}>
            <Title level={5}>风险贡献</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              各因子对组合总风险的贡献比例。RISK_PARITY方法下各因子风险贡献应大致相等。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>权重使用建议</Title>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        style={{ marginTop: 16, marginBottom: 16 }}
        message="如何应用优化后的权重"
        description={
          <div>
            <p><Text strong>1. 应用到选股策略：</Text>点击「应用到选股」按钮，系统会将优化后的权重自动填充到选股策略的因子权重配置中。</p>
            <p><Text strong>2. 定期再平衡：</Text>建议每月或每季度重新运行权重优化，适应最新的市场环境。因子有效性会随时间变化。</p>
            <p><Text strong>3. 多方法对比：</Text>建议同时运行三种优化方法，对比结果后综合决策。不要单一依赖某种方法。</p>
            <p><Text strong>4. 权重约束：</Text>如果某些因子权重过高（如&gt;30%）或过低（如&lt;5%），建议手动调整或检查因子选择是否合理。</p>
          </div>
        }
      />

      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="注意事项"
        description={
          <div>
            <p><Text strong>• 数据质量：</Text>权重优化依赖于因子的历史数据质量。如果某些因子数据缺失较多，优化结果可能不可靠。</p>
            <p><Text strong>• 过拟合风险：</Text>观察期太短或因子数量太多可能导致过拟合。建议观察期至少60天，因子数量不超过15个。</p>
            <p><Text strong>• 市场环境：</Text>权重优化基于历史数据，无法预测未来市场变化。建议结合主观判断调整权重。</p>
            <p><Text strong>• 交易成本：</Text>高频调整权重会产生较高的换手率，增加交易成本。建议适当延长再平衡周期。</p>
          </div>
        }
      />

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="数据说明"
        description="权重优化使用因子在截面上的 rank_value（归一化因子值）作为收益代理，分析因子间的相关性和收益特征。rank_value采用Z-Score标准化和百分位归一化，范围在0-1之间。"
      />
    </section>
  );
}

// 以上函数均已通过 `export function` 命名导出
