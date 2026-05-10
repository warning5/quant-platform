import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Tabs } from 'antd';
import { ThunderboltOutlined, FundOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualFactorStrategy() {
  return (
    <section id="factor-strategy" style={{ paddingBottom: 16 }}>
      <Title level={2}><ThunderboltOutlined /> 因子策略</Title>
      <Paragraph>
        因子策略是利用<Text strong>一个或多个因子</Text>构建选股/择时规则的投资策略。
        平台支持<Text strong>单因子策略</Text>、<Text strong>多因子组合策略</Text>和<Text strong>因子轮动策略</Text>。
      </Paragraph>

      <Alert type="info" showIcon message="核心价值"
        description="因子策略将量化研究落地为可执行的投资方案，实现从研究到实盘的闭环。" />

      <Divider orientation="left" plain>策略类型</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="📊 单因子策略" style={{ borderLeft: '4px solid #1677ff', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              使用单个因子选股。例如：买入MOM20最高的50只股票，等权持有，
              每月调仓。适合IC/IR较高的优质因子。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="📈 多因子组合" style={{ borderLeft: '4px solid #52c41a', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              将多个因子加权合成综合得分，按得分选股。权重可等权、
              按IC加权、按IR加权或优化求解（参见权重优化）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="🔄 因子轮动" style={{ borderLeft: '4px solid #fa8c16', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              根据市场环境在不同因子之间切换。例如：牛市用动量因子，
              震荡市用反转因子，熊市用低波因子。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>构建流程</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {[
          { step: 1, title: '因子选择', desc: '通过IC/IR分析筛选出有效因子（IC均值≥0.03，IR≥0.5）' },
          { step: 2, title: '权重确定', desc: '等权、IC加权、IR加权或权重优化（参见权重优化章节）' },
          { step: 3, title: '合成得分', desc: '将各因子值按权重加权，得到每只股票的综合得分' },
          { step: 4, title: '选股规则', desc: '按得分排序，选择前N只股票构建组合' },
          { step: 5, title: '回测验证', desc: '使用回测模块验证策略在历史数据上的表现' },
          { step: 6, title: '实盘跟踪', desc: '策略验证通过后，使用模拟盘跟踪策略表现' },
        ].map(item => (
          <Col xs={24} md={8} key={item.step}>
            <Card size="small">
              <Tag color="blue" style={{ marginBottom: 8 }}>Step {item.step}</Tag>
              <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>{item.title}</Text>
              <Paragraph style={{ fontSize: 11, margin: 0, color: '#666' }}>{item.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left" plain>选股方法</Divider>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="🎯 Top-N选股">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              按综合得分排序，选择前N只股票（如50只）。
              适合股票池较大的场景，保证分散度。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📊 阈值选股">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              设定得分阈值（如得分 {'>'} 80），选择超过阈值的股票。
              适合因子区分度高的场景，保证质量。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📈 分层回测">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              将股票按得分分成5层，分别回测每层的收益。
              用于验证因子的区分度和单调性。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="⚖️ 行业中性">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              在选股时控制行业暴露，避免策略过度依赖某个行业。
              提高策略稳健性，降低行业风险。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>使用示例</Divider>
      <Card size="small" style={{ borderLeft: '4px solid #722ed1' }}>
        <Text strong style={{ fontSize: 13 }}>示例：多因子组合策略</Text>
        <Paragraph style={{ fontSize: 12, margin: '8px 0 0' }}>
          1. 选择3个IC/IR通过的因子：MOM20（动量）、SIZE（市值）、ROE（盈利）<br/>
          2. 权重分配：MOM20占50%，SIZE占30%，ROE占20%（可按IC加权）<br/>
          3. 合成综合得分：得分 = 0.5×MOM20_zscore + 0.3×SIZE_zscore + 0.2×ROE_zscore<br/>
          4. 选股规则：选择得分最高的50只股票，等权持有<br/>
          5. 调仓频率：每月第一个交易日调仓<br/>
          6. 回测验证：使用回测模块验证策略表现，关注收益、回撤、夏普比率
        </Paragraph>
      </Card>

      <Divider orientation="left" plain>注意事项</Divider>
      <Alert type="warning" showIcon message="常见陷阱"
        description={
          <div>
            <ul style={{ paddingLeft: 20, marginBottom: 0 }}>
              <li><Text strong>过拟合</Text>：在太多个因子/参数组合中搜索最佳结果，会导致样本内过拟合</li>
              <li><Text strong>数据挖掘偏差</Text>：对同一数据集反复测试，会找到偶然有效的因子</li>
              <li><Text strong>交易成本</Text>：频繁调仓会侵蚀收益，务必在回测中加入交易成本</li>
              <li><Text strong>因子退化</Text>：因子有效性会随时间变化，需要定期重新检验IC/IR</li>
            </ul>
          </div>
        }
      />
    </section>
  );
}
