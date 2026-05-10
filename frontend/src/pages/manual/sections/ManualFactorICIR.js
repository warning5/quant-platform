import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Table, Tabs } from 'antd';
import { LineChartOutlined, QuestionCircleOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualFactorICIR() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <section id="factor-ic-ir" style={{ paddingBottom: 16 }}>
      <Title level={2}><LineChartOutlined /> 因子检测（IC/IR分析）</Title>
      <Paragraph>
        因子检测用于评估因子的<Text strong>预测能力</Text>和<Text strong>稳定性</Text>。
        IC（信息系数）衡量因子值与未来收益的相关性，IR（信息比率）衡量IC的<Text strong>稳定性</Text>。
      </Paragraph>

      <Alert type="success" showIcon message="核心价值"
        description="IC/IR分析帮你筛选出真正有预测能力的因子，避免将资金投入到无效因子上，提高策略成功率。" />

      <Divider orientation="left" plain>核心概念</Divider>

      {/* IC说明 */}
      <section id="ic-meaning" style={{ marginBottom: 24 }}>
        <Title level={4}>什么是IC（信息系数）？</Title>
        <Paragraph>
          IC（Information Coefficient）是因子值与<Text strong>未来N日收益率</Text>的相关系数（通常是Spearman秩相关系数）。
        </Paragraph>
        <Row gutter={[16, 16]}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📈 IC的含义" style={{ borderLeft: '4px solid #1677ff' }}>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
                  <li><Text strong>IC大于0</Text>：因子值与未来收益正相关（因子值越高，未来收益越高）</li>
                  <li><Text strong>IC小于0</Text>：因子值与未来收益负相关（因子值越高，未来收益越低）</li>
                  <li><Text strong>IC绝对值越大</Text>：因子的预测能力越强</li>
                  <li><Text strong>IC = 0</Text>：因子没有预测能力</li>
                </ul>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🎯 IC评估标准" style={{ borderLeft: '4px solid #52c41a' }}>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
                  <li><Text code>|IC均值| ≥ 0.05</Text>：有效因子</li>
                  <li><Text code>|IC均值| ≥ 0.03</Text>：弱有效因子</li>
                  <li><Text code>|IC均值| 小于 0.03</Text>：无效因子，建议剔除</li>
                  <li><Text code>IC大于0</Text>：做多做空均可；<Text code>IC小于0</Text>：反向使用</li>
                </ul>
            </Card>
          </Col>
        </Row>
      </section>

      <Divider orientation="left" plain>IR说明</Divider>

      {/* IR说明 */}
      <section id="ir-meaning" style={{ marginBottom: 24 }}>
        <Title level={4}>什么是IR（信息比率）？</Title>
        <Paragraph>
          IR（Information Ratio）= <Text strong>IC均值 / IC标准差</Text>。
          它衡量IC的<Text strong>稳定性</Text>（信噪比）。
        </Paragraph>
        <Row gutter={[16, 16]}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📊 IR的含义" style={{ borderLeft: '4px solid #fa8c16' }}>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
                  <li><Text strong>IR越高</Text>：IC越稳定，因子的预测能力越可靠</li>
                  <li><Text strong>IR小于0.5</Text>：IC波动大，因子稳定性差</li>
                  <li><Text strong>IR ≥ 0.5</Text>：IC较稳定，因子可靠性高</li>
                  <li><Text strong>IR ≥ 1.0</Text>：IC非常稳定，因子质量极高</li>
                </ul>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🎯 IR评估标准" style={{ borderLeft: '4px solid #722ed1' }}>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
                  <li><Text code>IR ≥ 1.0</Text>：顶级因子，强烈推荐使用</li>
                  <li><Text code>IR ≥ 0.5</Text>：良好因子，可以正常使用</li>
                  <li><Text code>IR ≥ 0.3</Text>：一般因子，谨慎使用</li>
                  <li><Text code>IR小于0.3</Text>：不稳定，建议优化或剔除</li>
                </ul>
            </Card>
          </Col>
        </Row>
      </section>

      <Divider orientation="left" plain>分组回测</Divider>

      {/* 分组回测说明 */}
      <section id="group-backtest" style={{ marginBottom: 24 }}>
        <Title level={4}>什么是分组回测？</Title>
        <Paragraph>
          分组回测是将股票按照因子值<Text strong>从高到低排序</Text>，分成若干组（通常5组或10组），
          分别回测每组的收益，观察<Text strong>单调性</Text>和<Text strong>区分度</Text>。
        </Paragraph>

        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="📊 分组回测的价值" style={{ borderLeft: '4px solid #13c2c2' }}>
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
                <li><Text strong>验证单调性</Text>：组1收益 超过 组2收益 超过 ... 超过 组5收益，说明因子区分度好</li>
                <li><Text strong>发现非线性</Text>：如果组1和组5收益都高，说明因子可能非线性</li>
                <li><Text strong>评估多头收益</Text>：组1（因子值最高）的累计收益是否显著优于基准</li>
                <li><Text strong>评估空头收益</Text>：组5（因子值最低）的累计收益是否显著低于基准</li>
              </ul>
            </Card>
          </Col>
          <Col xs={24} md={12}>
            <Card size="small" type="inner" title="🎯 如何解读分组收益图" style={{ borderLeft: '4px solid #eb2f96' }}>
              <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
                <li><Text strong>曲线分离度大</Text>：因子区分度好，多头/空头收益差大</li>
                <li><Text strong>曲线重合</Text>：因子无效，各组收益无显著差异</li>
                <li><Text strong>组1长期跑赢</Text>：因子有正向选股能力</li>
                <li><Text strong>组5长期跑输</Text>：因子有反向剔除能力</li>
              </ul>
            </Card>
          </Col>
        </Row>

        <Alert type="info" showIcon message="分组回测示例"
          description="假设按MOM20（20日动量）分成5组：组1（动量最强）应该显著跑赢组5（动量最弱），如果组1收益 超过 组2 超过 组3 超过 组4 超过 组5，说明动量因子有效且单调。" />
      </section>

      <Divider orientation="left" plain>使用方法</Divider>

      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="🚀 如何进行IC/IR分析">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              进入「因子管理 → IC/IR分析」，选择要分析的因子和未来收益计算周期（如20日），
              点击「开始分析」，系统会自动计算IC均值、IR、IC趋势图、分组回测图。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📈 如何解读IC趋势图">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              IC趋势图展示每个历史时点的IC值。如果IC值大部分时间为正（或负），
              说明因子方向稳定；如果IC值剧烈波动，说明因子时效性差。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>评估阈值总结</Divider>
      <Table
        size="small"
        pagination={false}
        rowKey="level"
        columns={[
          { title: '因子质量', dataIndex: 'level', key: 'level', width: 120, render: (t) => <Tag>{t}</Tag> },
          { title: 'IC均值要求', dataIndex: 'ic', key: 'ic', width: 150 },
          { title: 'IR要求', dataIndex: 'ir', key: 'ir', width: 120 },
          { title: '使用建议', dataIndex: 'advice', key: 'advice' },
        ]}
        dataSource={[
          { level: '顶级', ic: '≥ 0.05', ir: '≥ 1.0', advice: '强烈推荐，可作为核心因子' },
          { level: '良好', ic: '≥ 0.03', ir: '≥ 0.5', advice: '可以正常使用，建议组合使用' },
          { level: '一般', ic: '≥ 0.01', ir: '≥ 0.3', advice: '谨慎使用，建议结合其他因子' },
          { level: '无效', ic: '小于0.01', ir: '小于0.3', advice: '建议剔除，不要投入实盘' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Alert type="warning" showIcon message="注意事项"
        description="IC/IR分析需要足够的历史数据（建议不少于60个交易日）。新股或数据不完整的股票会被自动剔除。不同市场阶段因子的IC可能差异很大，建议分时段分析。" />
    </section>
  );
}
