import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert } from 'antd';

const { Title, Paragraph, Text } = Typography;

export default function ManualFinancial() {
  return (
    <section id="financial" style={{ paddingBottom: 32 }}>
      <Title level={2}>财务数据</Title>
      <Paragraph>
        财务数据模块展示股票的财务信息，包括主要财务指标、利润表、资产负债表、现金流量表等。
        这些数据是基本面分析的重要依据。
      </Paragraph>

      <Title level={4}>财务数据类型</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="主要财务指标">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              包含ROE、毛利率、净利率、营收增速、净利润增速、资产负债率等核心指标。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="利润表">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示营业收入、营业成本、营业利润、净利润等盈利相关数据。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="资产负债表">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示总资产、总负债、净资产、流动资产等资产结构数据。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="现金流量表">
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              展示经营现金流、投资现金流、筹资现金流等资金流向数据。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>数据来源</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="多数据源自动回退"
        description="财务数据优先使用同花顺iFind接口，失败时自动回退到东方财富接口。三级回退机制确保数据完整性。"
      />

      <Title level={4}>筛选功能</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="股票筛选">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              支持按股票代码或名称模糊搜索。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="时间范围">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              选择财报发布的时间范围，支持年度和季度筛选。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="财务类型">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              选择查看哪类财务报表，主要财务指标/利润表/资产负债表/现金流量表。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
