import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps, Table, Divider } from 'antd';

const { Title, Paragraph, Text } = Typography;

export function ManualFactorTest() {
  return (
    <section id="factor-test" style={{ paddingBottom: 32 }}>
      <Title level={2}>因子测试</Title>
      <Paragraph>
        因子测试模块用于评估因子的预测能力和有效性。通过 IC（信息系数）和分组回测两大方法，结合多种统计指标和可视化图表，帮助投资者判断因子是否具有 Alpha。
      </Paragraph>

      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="测试目标"
        description="判断一个因子是否有投资价值，核心看两点：① 因子值与下期收益是否显著相关（IC 法）；② 因子大小排序能否区分股票好坏（分组法）。"
      />

      {/* ── IC 法 ── */}
      <Title level={3}>一、IC 法（信息系数法）</Title>
      <Paragraph>
        IC（Information Coefficient，信息系数）衡量因子值与下期收益率之间的相关系数，是衡量因子预测能力的核心指标。
      </Paragraph>

      <Card size="small" style={{ marginBottom: 16, background: '#f5f5f5' }}>
        <Title level={5}>IC 计算公式</Title>
        <Paragraph style={{ margin: 0, fontFamily: 'monospace', fontSize: 13 }}>
          <Text code>IC_t = corr( factor_value_t, return_t+1 )</Text>
          <br/>其中 factor_value_t 为 t 日因子值（Z-Score 标准化），return_t+1 为 t+1 日收益率。
          corr() 为 Pearson 相关系数，取值范围 [-1, 1]。
        </Paragraph>
      </Card>

      <Title level={4}>6 个 IC 指标详解</Title>
      <Row gutter={[12, 12]}>
        {[
          { title: 'IC 均值', tag: 'IC Mean', color: 'blue',
            desc: '所有交易日 IC 的算术平均。均值 > 0 表示因子整体有正向预测能力。',
            standard: '|IC| > 0.03 有意义；> 0.05 较强；> 0.08 极强' },
          { title: 'IC 标准差', tag: 'IC Std', color: 'orange',
            desc: 'IC 序列的标准差，衡量 IC 的波动程度。标准差越小，因子越稳定。',
            standard: '配合 IC 均值计算 ICIR，单独意义有限' },
          { title: 'ICIR', tag: 'ICIR', color: 'green',
            desc: 'ICIR = IC均值 ÷ IC标准差。衡量因子每承受单位波动所产生的 IC 收益。',
            standard: '> 0.5 表示稳定有效；> 1.0 表示非常优秀' },
          { title: 'IC 正值率', tag: 'IC+ Rate', color: 'purple',
            desc: 'IC > 0 的交易日占比。衡量因子预测方向正确的概率。',
            standard: '> 55% 有意义；> 60% 较好；> 70% 优秀。注意：正值率高但均值低说明方向不稳定' },
          { title: 'IC t 统计量', tag: 'IC t-stat', color: 'cyan',
            desc: 'IC均值 / (IC标准差 / √N)，其中 N 为交易日数量。用于检验 IC 是否统计显著。',
            standard: '|t| > 1.96 表示 95% 置信度显著；> 2.58 表示 99% 显著' },
          { title: 'IC 显著性', tag: 'IC p-value', color: 'magenta',
            desc: 'IC 均值对应的 p 值。p 越小，因子相关性越显著。',
            standard: 'p < 0.05 表示显著；p < 0.01 表示高度显著' },
        ].map(m => (
          <Col xs={24} md={12} key={m.tag}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color === 'blue' ? '#1677ff' : m.color === 'orange' ? '#fa8c16' : m.color === 'green' ? '#52c41a' : m.color === 'purple' ? '#722ed1' : m.color === 'cyan' ? '#13c2c2' : '#eb2f96'}` }}>
              <Title level={5}>{m.title} <Tag color={m.color}>{m.tag}</Tag></Title>
              <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
              <Text type="secondary" style={{ fontSize: 11 }}>判断标准：{m.standard}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      {/* ── 分组法 ── */}
      <Divider />
      <Title level={3}>二、分组回测法</Title>
      <Paragraph>
        分组法将股票按因子值从大到小分成 N 组，持有一定期限后比较各组收益。若因子有效，分组收益应呈单调递增趋势。
      </Paragraph>

      <Title level={4}>分组测试指标</Title>
      <Row gutter={[12, 12]}>
        {[
          { title: '单调性', tag: 'Monotonicity', color: 'blue',
            desc: '检验各分组收益是否单调递增。使用分组收益序列的秩相关系数衡量，取值 [-1, 1]，越接近 1 单调性越好。',
            standard: '> 0.8 优秀；0.5~0.8 较好；< 0.5 较弱或无效' },
          { title: '分组 IR', tag: 'Group IR', color: 'green',
            desc: '分组 IR = (Top 组年化收益 - Bottom 组年化收益) ÷ 多空收益标准差。衡量分组收益的稳定性。',
            standard: '> 0.5 稳定有效；> 1.0 非常优秀' },
          { title: '多空年化收益', tag: 'Long-Short Ann.Ret', color: 'orange',
            desc: '做多 Top 组、做空 Bottom 组的年化收益差。直接反映因子对冲组合的收益能力。',
            standard: '> 5% 有意义；> 10% 较好；> 20% 优秀' },
          { title: '分组显著性', tag: 'p-value', color: 'purple',
            desc: 'Top 组与 Bottom 组收益差异的 t 检验 p 值，衡量差异的统计显著性。',
            standard: 'p < 0.05 显著；p < 0.01 高度显著' },
          { title: '主动收益', tag: 'Active Return', color: 'cyan',
            desc: 'Top 组收益 - 基准收益（等权或加权指数），反映因子选股的超额收益能力。',
            standard: '正值越大越好；持续 > 0 说明因子持续有效' },
          { title: 'Top 组夏普', tag: 'Top Group Sharpe', color: 'magenta',
            desc: 'Top 组收益的夏普比率（年化收益 ÷ 年化波动率），衡量因子选出的股票组合的风险调整收益。',
            standard: '> 0.5 有意义；> 1.0 优秀' },
        ].map(m => (
          <Col xs={24} md={12} key={m.tag}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color === 'blue' ? '#1677ff' : m.color === 'green' ? '#52c41a' : m.color === 'orange' ? '#fa8c16' : m.color === 'purple' ? '#722ed1' : m.color === 'cyan' ? '#13c2c2' : '#eb2f96'}` }}>
              <Title level={5}>{m.title} <Tag color={m.color}>{m.tag}</Tag></Title>
              <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
              <Text type="secondary" style={{ fontSize: 11 }}>判断标准：{m.standard}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      {/* ── 判断标准 ── */}
      <Divider />
      <Title level={3}>三、有效因子判断标准（8 条）</Title>
      <Paragraph>满足以下任意 3 条以上，可认为因子具有一定参考价值：</Paragraph>
      <Row gutter={[8, 8]}>
        {[
          { n: '1', text: 'IC 均值 > 0.03（绝对值）', color: '#1677ff' },
          { n: '2', text: 'ICIR > 0.5', color: '#1677ff' },
          { n: '3', text: 'IC 正值率 > 55%', color: '#1677ff' },
          { n: '4', text: 'IC t 统计量绝对值 > 1.96', color: '#1677ff' },
          { n: '5', text: '分组单调性 > 0.5', color: '#52c41a' },
          { n: '6', text: '分组 IR > 0.5', color: '#52c41a' },
          { n: '7', text: '多空年化收益 > 5%', color: '#fa8c16' },
          { n: '8', text: 'Top 组 vs Bottom 组显著性 p < 0.05', color: '#722ed1' },
        ].map(r => (
          <Col xs={24} md={12} key={r.n}>
            <Card size="small" style={{ borderLeft: `4px solid ${r.color}`, padding: '8px 12px' }}>
              <Text><Text strong>{r.n}.</Text> {r.text}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      <Alert type="warning" showIcon style={{ marginTop: 16 }}
        message="注意事项"
        description="IC 法和分组法结论可能不一致（如 IC 显著但分组单调性差），应以分组法为主，因子最终要能选出好股票。"
      />

      {/* ── 完整流程 ── */}
      <Divider />
      <Title level={3}>四、完整测试流程</Title>
      <Steps
        current={5}
        items={[
          { title: '选择因子', description: '从因子库选取待测试因子，支持同时测试多个' },
          { title: '设置参数', description: '选择市场、日期范围、分组数量、持有期' },
          { title: '计算 IC', description: '横截面计算每日 IC，汇总统计指标' },
          { title: '分组回测', description: '按因子值分组，持有期结束后计算各组收益' },
          { title: '分析结果', description: '对比 IC 指标与分组指标，判断因子有效性' },
          { title: '选择调仓周期', description: '结合因子衰减分析，确定最优调仓频率' },
        ]}
      />

      {/* ── 图表说明 ── */}
      <Divider />
      <Title level={3}>五、图表解读</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="IC 时间序列">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示 IC 值随时间的变化。<Text strong>红色虚线</Text>为 IC 均值，
              <Text strong>绿色/红色柱</Text>分别表示正/负 IC。
              IC 在 0 轴上方越多、波动越小，因子越稳定。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="分组累计收益曲线">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              每条线代表一个分组的累计收益。<Text strong>红线（Top）</Text>
              在最上方、<Text strong>绿线（Bottom）</Text>在最下方、
              各组线不相交（单调性完美）。若线条纠缠说明因子无效。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="IC 分布直方图">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示 IC 序列的分布形态。若均值明显偏右（正值区）且集中，说明因子稳定有效；
              若均值接近 0 或正负对称分布，说明因子无明显预测能力。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="因子衰减曲线">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示 IC 随持有期（N = 1, 5, 10, 20 天）的变化趋势。
              IC 随 N 增大而快速衰减的因子，适合短期调仓；
              衰减慢的因子适合中长周期调仓。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}

export function ManualFactorOrthogonalize() {
  return (
    <section id="factor-orthogonalize" style={{ paddingBottom: 32 }}>
      <Title level={2}>因子正交化</Title>
      <Paragraph>
        因子正交化模块用于去除因子之间的共线性。当多个因子高度相关时，它们携带的信息高度重叠，
        会导致加权评分时重复计算同类信息，降低组合效率。通过正交处理，可以提取各因子的独立信息，
        让每个因子发挥最大增量作用。
      </Paragraph>

      <Title level={4}>正交化方法详解</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>施密特正交化（Gram-Schmidt）</Title>
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              按给定顺序依次对因子进行正交处理。对每个因子，将其投影到已处理因子的正交空间中，
              剩余分量即为正交因子。顺序不同结果不同，一般按因子重要性从高到低排列。
            </Paragraph>
            <Text type="secondary" style={{ fontSize: 11 }}>
              适用场景：需要明确因子优先级，且因子间相关性明确。
            </Text>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>对称正交化（SVD）</Title>
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              基于矩阵奇异值分解（SVD），在统计意义上最小化因子间的平均相关性。
              不依赖因子顺序，结果唯一，适合不知道哪个因子更重要时使用。
            </Paragraph>
            <Text type="secondary" style={{ fontSize: 11 }}>
              适用场景：因子重要性相近，希望结果客观公正。
            </Text>
          </Card>
        </Col>
      </Row>

      <Title level={4}>相关性检验标准</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="强相关（需处理）">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              |corr| &gt; 0.7：两因子信息高度重叠，保留预测能力更强的一个，或进行正交化处理。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #faad14' }} title="中等相关（可处理）">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              0.4 &lt; |corr| ≤ 0.7：存在一定共线性，但各有增量信息。可根据组合需要决定是否正交化。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #d9d9d9' }} title="弱相关（可保留）">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              |corr| ≤ 0.4：因子携带相对独立的信息，通常不需要正交化，可直接加入组合。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert type="info" showIcon style={{ marginTop: 16 }}
        message="使用建议"
        description="正交化会改变因子的原始含义，导致难以解释因子暴露。正交化后建议重新做因子测试，验证正交因子的有效性。"
      />
      <Alert type="warning" showIcon style={{ marginTop: 8 }}
        message="注意事项"
        description="正交化顺序会影响结果。一般按因子 IC 显著性或投资逻辑重要性排序先处理；也可以使用对称正交化避免主观选择。"
      />
    </section>
  );
}

export function ManualFactorDecay() {
  return (
    <section id="factor-decay" style={{ paddingBottom: 32 }}>
      <Title level={2}>因子衰减分析</Title>
      <Paragraph>
        因子衰减分析用于研究因子预测能力随持有期延长的变化规律。
        所有因子都存在衰减现象（Alpha 会被市场逐渐套利消失），
        了解衰减速度有助于确定最优调仓频率，在信息时效性和交易成本之间取得平衡。
      </Paragraph>

      <Card size="small" style={{ marginBottom: 16, background: '#f5f5f5' }}>
        <Title level={5}>衰减原理</Title>
        <Paragraph style={{ margin: 0, fontSize: 13 }}>
          因子预测的是下期收益。若因子在 t 日有效（IC &gt; 0），到 t+5 日时，
          很多投资者已基于相同信息交易，价格被修正，IC 逐渐趋近于 0。
          <Text strong>衰减越快 → 调仓越频繁；衰减越慢 → 可降低调仓频率节省成本。</Text>
        </Paragraph>
      </Card>

      <Title level={4}>核心分析指标</Title>
      <Row gutter={[12, 12]}>
        {[
          { title: '各持有期 IC', tag: 'IC by Horizon', color: 'blue',
            desc: '计算 IC(N) = corr(factor_t, return_t+N)，N = 1, 5, 10, 20 天，展示 IC 随持有期的变化。',
            insight: '若 IC(1) ≈ IC(20)，说明因子信息持久；若 IC(20) << IC(1)，说明信息衰减快' },
          { title: 'IC 衰减曲线', tag: 'IC Decay Curve', color: 'green',
            desc: '以横轴为持有天数、纵轴为 IC 均值的曲线图。衰减越平缓的因子越适合长周期。',
            insight: '曲线在第 5 天就降到 0 轴 → 因子适合日频调仓；曲线在 20 天仍 > 0.03 → 可周频调仓' },
          { title: '半衰期', tag: 'Half-Life', color: 'orange',
            desc: 'IC 降至峰值一半所需的交易日数。是衡量衰减速度的核心量化指标。',
            insight: '半衰期 < 5 天：适合日频；5~15 天：周频；> 20 天：双周/月频' },
          { title: '调仓频率建议', tag: 'Rebalance Freq', color: 'purple',
            desc: '综合 IC 衰减曲线和半衰期，给出最优调仓频率建议，平衡 Alpha 捕获与交易成本。',
            insight: '考虑印花税（0.1%）、佣金（0.03%）等显性成本，以及冲击成本' },
        ].map(m => (
          <Col xs={24} md={12} key={m.tag}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${m.color === 'blue' ? '#1677ff' : m.color === 'green' ? '#52c41a' : m.color === 'orange' ? '#fa8c16' : '#722ed1'}` }}>
              <Title level={5}>{m.title} <Tag color={m.color}>{m.tag}</Tag></Title>
              <Paragraph style={{ fontSize: 12, margin: '0 0 4px' }}>{m.desc}</Paragraph>
              <Text type="secondary" style={{ fontSize: 11 }}>解读：{m.insight}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      <Title level={4}>5 个应用场景</Title>
      <Row gutter={[8, 8]}>
        {[
          { n: '1', title: '确定调仓频率', desc: '根据因子半衰期选择日/周/月调仓，避免频繁调仓增加成本，或调仓过慢错过 Alpha' },
          { n: '2', title: '因子分层', desc: '衰减快的短期因子（IC 半衰期 < 5 天）和衰减慢的长期因子（半衰期 > 20 天）分层管理' },
          { n: '3', title: '组合因子配比', desc: '衰减快的因子权重不宜过高（信息很快消失），衰减慢的价值/质量因子可配置更高权重' },
          { n: '4', title: '动态调整', desc: '市场波动大时（如牛熊转换）衰减加快，可缩短调仓周期；市场平稳时可适度延长' },
          { n: '5', title: '因子生命周期管理', desc: '跟踪因子半衰期变化，若半衰期持续缩短说明因子被市场充分定价，考虑替换' },
        ].map(r => (
          <Col xs={24} md={12} key={r.n}>
            <Card size="small" style={{ borderLeft: '4px solid #1677ff', padding: '8px 12px' }}>
              <Text><Text strong>{r.n}. {r.title}</Text>：{r.desc}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      <Title level={4}>访问路径与操作步骤</Title>
      <Steps
        current={3}
        size="small"
        items={[
          { title: '进入因子管理' },
          { title: '点击因子测试' },
          { title: '选择因子和日期范围' },
          { title: '查看衰减曲线与半衰期' },
        ]}
      />

      <Alert type="warning" showIcon style={{ marginTop: 16 }}
        message="注意"
        description="衰减分析结果与测试区间密切相关。牛市期间动量因子衰减慢、趋势强；熊市或震荡市中反转因子衰减快。建议使用多个不同市场环境的区间做衰减分析，取稳健结论。"
      />
    </section>
  );
}

export function ManualFactorCorrelation() {
  return (
    <section id="factor-correlation" style={{ paddingBottom: 32 }}>
      <Title level={2}>因子相关性分析</Title>
      <Paragraph>
        因子相关性分析用于研究不同因子之间的相关性，帮助避免因子冗余、提高组合效率。
        高相关因子携带重复信息，同时纳入会导致某些信息被过度暴露、其他信息被稀释。
      </Paragraph>

      <Card size="small" style={{ marginBottom: 16, background: '#f5f5f5' }}>
        <Title level={5}>相关性矩阵（热力图）说明</Title>
        <Paragraph style={{ margin: 0, fontSize: 13 }}>
          热力图中每个单元格代表两个因子的 Pearson 相关系数：
          <Text strong> 颜色越深（红）</Text>表示正相关越强；
          <Text strong> 颜色越深（蓝）</Text>表示负相关越强；
          <Text strong> 颜色接近白</Text>表示相关性弱。
        </Paragraph>
      </Card>

      <Title level={4}>相关性等级解读</Title>
      <Row gutter={[12, 12]}>
        {[
          { level: '强正相关', range: '0.7 ~ 1.0', color: '#f51m32', action: '仅保留一个，或合并/正交化',
            bg: '#fff1f0', border: '#ffccc7', icon: '🔴' },
          { level: '中等正相关', range: '0.4 ~ 0.7', color: '#fa8c16', action: '可保留，注意权重分配',
            bg: '#fff7e6', border: '#ffd591', icon: '🟠' },
          { level: '弱相关', range: '-0.4 ~ 0.4', color: '#52c41a', action: '可直接保留',
            bg: '#f6ffed', border: '#b7eb8f', icon: '🟢' },
          { level: '中等负相关', range: '-0.7 ~ -0.4', color: '#722ed1', action: '可形成对冲，保留',
            bg: '#f9f0ff', border: '#d3adf7', icon: '🟣' },
          { level: '强负相关', range: '-1.0 ~ -0.7', color: '#1677ff', action: '强对冲效果，可少量配置',
            bg: '#e6f4ff', border: '#91caff', icon: '🔵' },
        ].map(r => (
          <Col xs={24} md={12} key={r.level} lg={8}>
            <Card size="small" type="inner" style={{ background: r.bg, borderLeft: `4px solid ${r.border}` }}>
              <Title level={5}>{r.icon} {r.level} <Text code>{r.range}</Text></Title>
              <Paragraph style={{ fontSize: 12, margin: 0 }}>{r.action}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Title level={4}>热力图解读步骤（4 步）</Title>
      <Steps
        current={3}
        size="small"
        items={[
          { title: '定位深色格', description: '红色格表示高正相关，找出 > 0.7 的因子对' },
          { title: '判断处理方式', description: '高相关因子对选择：保留 / 合并 / 正交化（见上方等级表）' },
          { title: '检查结构化冗余', description: '动量类（MOM5/20/60）、波动类（VOL5/20/60）内部通常高度相关' },
          { title: '验证正交效果', description: '正交化后重新生成热力图，确认相关性显著下降' },
        ]}
      />

      <Title level={4}>数据表字段说明</Title>
      <Table
        size="small"
        bordered
        rowKey="field"
        dataSource={[
          { field: 'factor_a', type: 'VARCHAR', desc: '因子代码 A' },
          { field: 'factor_b', type: 'VARCHAR', desc: '因子代码 B' },
          { field: 'corr_coef', type: 'DOUBLE', desc: 'Pearson 相关系数，取值 [-1, 1]' },
          { field: 'p_value', type: 'DOUBLE', desc: '相关系数显著性 p 值，< 0.05 表示显著相关' },
          { field: 'sample_count', type: 'INT', desc: '计算相关系数所使用的样本数（交易日 × 股票数）' },
          { field: 'calc_date', type: 'DATE', desc: '相关性矩阵的计算日期' },
        ]}
        columns={[
          { title: '字段名', dataIndex: 'field', width: 140, render: t => <Text code>{t}</Text> },
          { title: '类型', dataIndex: 'type', width: 100, render: t => <Text code>{t}</Text> },
          { title: '说明', dataIndex: 'desc' },
        ]}
        pagination={false}
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>5 个应用场景</Title>
      <Row gutter={[8, 8]}>
        {[
          { n: '1', title: '因子去重', desc: '高相关因子对（|corr| > 0.7）只保留 IC 更高或更稳定的一个，避免重复暴露同类风险' },
          { n: '2', title: '组合构建', desc: '选择相关性低的因子加入组合，可分散风险、提升 IR（信息比率）' },
          { n: '3', title: '因子分层', desc: '按相关性将因子聚类（强相关归为一类），每类选择一个代表因子，减少因子数量' },
          { n: '4', title: '正交化决策', desc: '高相关因子对做正交化处理，提取独立因子成分后再加权（见因子正交化章节）' },
          { n: '5', title: '监控因子漂移', desc: '定期更新相关性矩阵，监测因子间关系是否发生结构性变化（如因子拥挤度上升）' },
        ].map(r => (
          <Col xs={24} md={12} key={r.n}>
            <Card size="small" style={{ borderLeft: '4px solid #1677ff', padding: '8px 12px' }}>
              <Text><Text strong>{r.n}. {r.title}</Text>：{r.desc}</Text>
            </Card>
          </Col>
        ))}
      </Row>

      <Alert type="info" showIcon style={{ marginTop: 16 }}
        message="提示"
        description="技术因子之间（如 MOM5 / MOM20 / VOL5 / VOL20）天然相关性高；基本面因子与情绪因子的相关性通常较低。选因子时应优先选择低相关因子以获得信息增量。"
      />
    </section>
  );
}

export function ManualFactorStrategy() {
  return (
    <section id="factor-strategy" style={{ paddingBottom: 32 }}>
      <Title level={2}>因子策略</Title>
      <Paragraph>
        因子策略模块用于构建基于多因子的选股策略。通过组合多个因子并设置权重，
        形成完整的量化选股逻辑，在每个调仓日输出应持有的股票列表。
      </Paragraph>

      <Title level={4}>策略核心配置（3 张卡片）</Title>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }} title="因子选择">
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              从因子库中选择用于选股的因子。选因子时应关注：
              IC 显著、与其他已选因子低相关、各因子逻辑互补。
            </Paragraph>
            <Text type="secondary" style={{ fontSize: 11 }}>
              建议：选择 3~8 个因子，覆盖不同维度（动量/价值/质量/技术）
            </Text>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="因子权重">
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              设置各因子的权重（总和为 1 或按重要性分配）。权重决定了各因子对综合得分的影响程度。
            </Paragraph>
            <Text type="secondary" style={{ fontSize: 11 }}>
              可用因子权重优化模块（见菜单）自动寻找最优权重组合
            </Text>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="数据处理">
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              <Text strong>极值处理</Text>：Winsorize（截尾处理），将超出 N 倍标准差的因子值拉回边界，避免极端值主导评分。
            </Paragraph>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>标准化</Text>：Z-Score 或 Rank 归一化，使不同量纲的因子可比。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>选股流程（6 步）</Title>
      <Steps
        current={5}
        items={[
          { title: '数据准备', description: '获取候选股票池，计算各因子在截面上的值' },
          { title: '因子预处理', description: '极值处理 + Z-Score 标准化，使因子可比' },
          { title: '均线位置过滤（可选）', description: '要求股价站在指定均线上方，排除下降趋势中的标的' },
          { title: '加权求和', description: '综合得分 = Σ(因子权重 × 标准化因子值)' },
          { title: '排序筛选', description: '按综合得分降序排列，选取 top N（如 30 只）' },
          { title: '风控过滤', description: '剔除 ST 股、涨跌停、流动性差的股票' },
        ]}
      />

      {/* 均线位置过滤 */}
      <Title level={4}>均线位置过滤</Title>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="多头趋势过滤器 — 在因子选股之前做一层趋势预筛选"
        description="仅保留当前价格站上指定移动平均线的股票，排除处于下降通道中的标的。这是一个可选的前置过滤条件，可与任意预设组合配合使用。"
      />
      <Row gutter={[12, 12]}>
        <Col xs={24} md={16}>
          <Card size="small" type="inner" title="三种均线选项">
            <Paragraph style={{ fontSize: 12 }}>
              系统提供三个可独立勾选的均线条件（可多选）：
            </Paragraph>
            <Table
              size="small"
              pagination={false}
              rowKey="tag"
              dataSource={[
                { tag: 'MA30', desc: '收盘价高于 30 日均线', span: '约 1.5 个月', scene: '短期偏多，宽松过滤' },
                { tag: 'MA60', desc: '收盘价高于 60 日均线', span: '约 3 个月（季度）', scene: '中期趋势确认' },
                { tag: 'MA100', desc: '收盘价高于 100 日均线', span: '约 5 个月', scene: '强趋势过滤，最严格' },
              ]}
              columns={[
                { title: '选项', dataIndex: 'tag', key: 'option',
                  render: v => <Tag color="blue">价格 &gt; {v}</Tag> },
                { title: '含义', dataIndex: 'desc', key: 'desc' },
                { title: '时间跨度', dataIndex: 'span', key: 'span' },
                { title: '适用场景', dataIndex: 'scene', key: 'scene' },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="使用建议" style={{ height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              <Text strong>多选逻辑：</Text>同时勾选多个条件时取交集（AND），即价格必须同时满足所有勾选的均线条件。
            </Paragraph>
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              <Text strong>动量策略推荐：</Text>至少开启 MA60 或 MA100 过滤，确保选出的股票处于明确上升趋势中。
            </Paragraph>
            <Paragraph style={{ fontSize: 12, margin: '0 0 8px' }}>
              <Text strong>价值反转策略：</Text>不建议使用此功能，因为价值股往往在下跌后被低估。
            </Paragraph>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>计算方式：</Text>后端通过 PriceAdvisorService.batchCalcMaPositions() 批量计算候选股票的 MA 值和位置关系，无数据的股票自动剔除。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Card size="small" style={{ marginTop: 16, background: '#f5f5f5' }}>
        <Title level={5}>综合得分计算公式</Title>
        <Paragraph style={{ margin: 0, fontFamily: 'monospace', fontSize: 13 }}>
          Score_i = Σ(w_j × z_ij)，其中 w_j 为第 j 个因子权重，z_ij 为第 i 只股票第 j 个因子的标准化值（Z-Score）
          <br/>
          <Text type="secondary">例：2 因子等权组合，MOM20 权重 0.5、RSI14 权重 0.5 → Score = 0.5 × z_MOM20 + 0.5 × z_RSI14</Text>
        </Paragraph>
      </Card>

      <Alert type="warning" showIcon style={{ marginTop: 16 }}
        message="调仓频率选择"
        description="调仓频率取决于因子衰减速度（见因子衰减分析）。日频调仓可捕获更多 Alpha 但成本高；周频/月频适合衰减较慢的因子。一般动量因子适合周频，价值/质量因子适合月频。"
      />
    </section>
  );
}

// 以上函数均已通过 `export function` 命名导出
