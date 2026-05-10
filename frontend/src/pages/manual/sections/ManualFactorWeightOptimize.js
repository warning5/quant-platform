import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Table } from 'antd';
import { FundOutlined, AimOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualFactorWeightOptimize() {
  return (
    <section id="factor-weight-optimize" style={{ paddingBottom: 16 }}>
      <Title level={2}><AimOutlined /> 因子权重优化</Title>
      <Paragraph>
        因子权重优化是<Text strong>多因子策略</Text>的核心环节。
        通过优化算法确定各因子的最佳权重，使策略在历史数据上表现最优（如最大化夏普比率或IC）。
      </Paragraph>

      <Alert type="success" showIcon message="核心价值"
        description="合理的权重分配能充分发挥各因子的优势，避免低效因子拖累整体表现，提高策略稳健性。" />

      <Divider orientation="left" plain>权重确定方法</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="⚖️ 等权加权" style={{ borderLeft: '4px solid #1677ff', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              所有因子权重相等。简单但在因子IC差异大时不是最优。
              适合因子IC/IR相近的场景。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="📊 IC加权" style={{ borderLeft: '4px solid #52c41a', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              按因子IC均值占比分配权重。IC越高的因子权重越大。
              适合因子IC稳定且差异明显的场景。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="🎯 IR加权" style={{ borderLeft: '4px solid #fa8c16', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              按因子IR（IC均值/IC标准差）分配权重。同时考虑IC大小和稳定性。
              比IC加权更稳健。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="🔧 优化求解" style={{ borderLeft: '4px solid #722ed1', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              以最大化夏普比率或IC为目标，使用优化算法（如均值方差优化）求解最优权重。
              平台提供权重优化功能。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="📈 滚动优化" style={{ borderLeft: '4px solid #13c2c2', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              在每个调仓周期重新运行优化，适应因子有效性的变化。
              比固定权重更灵活，但容易过拟合。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" title="🎚️ 风险平价" style={{ borderLeft: '4px solid #eb2f96', height: '100%' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              使各因子对组合风险的贡献相等。适合风险分散目标。
              需要计算因子协方差矩阵。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>平台权重优化功能</Divider>
      <Paragraph>
        平台提供<Text strong>因子权重优化</Text>功能，入口：「因子管理 → 权重优化」。
      </Paragraph>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📊 使用方法">
            <ol style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
              <li>选择要优化的因子（建议3~8个）</li>
              <li>设置优化目标（最大化IC或夏普比率）</li>
              <li>设置约束（如权重范围、行业中性等）</li>
              <li>点击「开始优化」，等待计算完成</li>
              <li>查看优化结果（权重分配、预期IC/夏普）</li>
              <li>保存权重方案或直接激活使用</li>
            </ol>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📈 优化结果解读">
            <ul style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.8 }}>
              <li><Text strong>权重分配</Text>：各因子的最优权重（总和为1或100%）</li>
              <li><Text strong>预期IC</Text>：优化后组合因子的预期IC值</li>
              <li><Text strong>预期夏普</Text>：优化后策略的预期夏普比率</li>
              <li><Text strong>有效前沿</Text>：展示风险-收益权衡曲线</li>
            </ul>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>权重优化示例</Divider>
      <Card size="small" style={{ borderLeft: '4px solid #fa8c16' }}>
        <Text strong style={{ fontSize: 13 }}>示例：3因子权重优化</Text>
        <Paragraph style={{ fontSize: 12, margin: '8px 0 0' }}>
          假设选择3个因子：MOM20（IC=0.04）、SIZE（IC=0.03）、ROE（IC=0.05）<br/>
          <Text strong>等权加权</Text>：各占33.3%，组合IC ≈ 0.037<br/>
          <Text strong>IC加权</Text>：MOM20占30.8%，SIZE占23.1%，ROE占38.5%，组合IC ≈ 0.039<br/>
          <Text strong>优化求解</Text>：考虑因子相关性后，可能分配MOM20占40%，SIZE占10%，ROE占50%，组合IC ≈ 0.042<br/>
          <Text type="secondary">注意：优化求解结果依赖于历史数据，未来可能退化，需要定期重新优化。</Text>
        </Paragraph>
      </Card>

      <Divider orientation="left" plain>评估与监控</Divider>
      <Table
        size="small"
        pagination={false}
        rowKey="metric"
        columns={[
          { title: '评估指标', dataIndex: 'metric', key: 'metric', width: 120, render: (t) => <Text strong>{t}</Text> },
          { title: '说明', dataIndex: 'desc', key: 'desc' },
          { title: '目标值', dataIndex: 'target', key: 'target', width: 150 },
        ]}
        dataSource={[
          { metric: '组合IC', desc: '优化后组合因子的IC均值', target: '≥ 0.05（有效）' },
          { metric: '组合IR', desc: '优化后组合因子的IR', target: '≥ 0.5（稳定）' },
          { metric: '夏普比率', desc: '策略回测的夏普比率', target: '≥ 1.0（良好）' },
          { metric: '最大回撤', desc: '策略回测的最大回撤', target: '< 20%（可控）' },
          { metric: '换手率', desc: '策略调仓的换手率', target: '< 50%/月（可控成本）' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Divider orientation="left" plain>注意事项</Divider>
      <Alert type="warning" showIcon message="权重优化陷阱"
        description={
          <div>
            <ul style={{ paddingLeft: 20, marginBottom: 0 }}>
              <li><Text strong>过拟合</Text>：优化算法会在历史数据上找到最优权重，但未来可能失效。务必使用样本外数据验证。</li>
              <li><Text strong>因子相关性</Text>：高相关因子会导致权重不稳定（小变化导致权重大变化）。优化前先处理高相关因子。</li>
              <li><Text strong>权重集中</Text>：优化可能导致权重集中在少数因子上，丧失分散效果。建议加权重约束（如单因子权重≤50%）。</li>
              <li><Text strong>重新优化频率</Text>：建议每3~6个月重新优化一次，适应因子有效性的变化，但不要过于频繁。</li>
            </ul>
          </div>
        }
      />
    </section>
  );
}
