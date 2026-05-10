import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Table } from 'antd';
import { ApartmentOutlined, InfoCircleOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualFactorCorrelation() {
  return (
    <section id="factor-correlation" style={{ paddingBottom: 16 }}>
      <Title level={2}><ApartmentOutlined /> 因子相关性分析</Title>
      <Paragraph>
        因子相关性分析用于检测<Text strong>因子之间的线性相关程度</Text>。
        高相关性（|r| 大于 0.7）意味着因子包含相似信息，同时存在会导致<Text strong>多重共线性</Text>问题。
      </Paragraph>

      <Alert type="warning" showIcon message="核心价值"
        description="通过相关性分析，可以剔除冗余因子，降低策略过拟合风险，提高因子组合的稳健性。" />

      <Divider orientation="left" plain>相关性解读</Divider>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {[
          { range: '|r| = 0 ~ 0.3', level: '低相关', color: 'green', desc: '因子包含独立信息，可以同时使用' },
          { range: '|r| = 0.3 ~ 0.7', level: '中等相关', color: 'orange', desc: '包含部分重叠信息，建议谨慎同时使用' },
          { range: '|r| = 0.7 ~ 1.0', level: '高相关', color: 'red', desc: '高度冗余，建议只保留其中一个' },
        ].map(item => (
          <Col xs={24} md={8} key={item.range}>
            <Card size="small" style={{ borderLeft: `4px solid ${item.color}` }}>
              <Text strong style={{ fontSize: 13 }}>{item.level}</Text>
              <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                <Text code>{item.range}</Text><br/>
                {item.desc}
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left" plain>使用方法</Divider>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📊 查看相关性矩阵">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              进入「因子管理 → 因子相关性」，选择要计算相关性的因子列表，
              系统会计算所有因子两两之间的 Pearson 相关系数，并以热力图展示。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="🔍 解读热力图">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              颜色越红表示正相关越强，越蓝表示负相关越强。
              建议优先保留 IC 较高且相关性较低的因子组合。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>常见高相关因子对</Divider>
      <Table
        size="small"
        pagination={false}
        rowKey="pair"
        columns={[
          { title: '因子A', dataIndex: 'factorA', key: 'factorA', width: 120 },
          { title: '因子B', dataIndex: 'factorB', key: 'factorB', width: 120 },
          { title: '典型相关系数', dataIndex: 'corr', key: 'corr', width: 120 },
          { title: '说明', dataIndex: 'desc', key: 'desc' },
        ]}
        dataSource={[
          { factorA: 'MOM20', factorB: 'MOM60', corr: '0.75~0.85', desc: '同为动量因子，短期和长期动量高度相关' },
          { factorA: 'VOL20', factorB: 'ATR20', corr: '0.80~0.90', desc: '同为波动率因子，计算逻辑相似' },
          { factorA: 'MA20', factorB: 'MOM20', corr: '0.60~0.70', desc: '均线反映的趋势与动量高度相关' },
          { factorA: 'SIZE', factorB: 'LIQUIDITY', corr: '-0.50~-0.30', desc: '大市值股票通常流动性更好（负相关）' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Alert type="info" showIcon message="处理建议"
        description="发现高相关因子对后，可以：1）删除 IC 较低的因子；2）使用正交化处理；3）使用因子加权合成一个新因子。" />
    </section>
  );
}
