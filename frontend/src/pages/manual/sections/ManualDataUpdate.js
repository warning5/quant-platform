import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert } from 'antd';

const { Title, Paragraph, Text } = Typography;

export default function ManualDataUpdate() {
  return (
    <section id="data-update" style={{ paddingBottom: 32 }}>
      <Title level={2}>数据更新</Title>
      <Paragraph>
        数据更新模块负责从外部数据源获取最新的行情、财务等数据，并写入数据库。
        支持一键更新所有数据，也可按需更新特定类型的数据。
      </Paragraph>

      <Title level={4}>数据更新内容</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="股票日线数据">
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>更新频率</Text>：每日收盘后<br/>
              <Text strong>数据源</Text>：Baostock（沪深）、腾讯证券（北交所）<br/>
              <Text strong>覆盖范围</Text>：5490只股票，包含OHLCV、财务附注等
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="指数日线数据">
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>更新频率</Text>：每日收盘后<br/>
              <Text strong>数据源</Text>：Baostock<br/>
              <Text strong>覆盖范围</Text>：沪深300、中证500、上证50等10个主要指数
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="分红除权数据">
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>更新频率</Text>：按需<br/>
              <Text strong>数据源</Text>：巨潮→同花顺→东方财富（自动回退）<br/>
              <Text strong>覆盖范围</Text>：97.9%的沪深股票分红除权信息
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="财务数据">
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>更新频率</Text>：财报发布后<br/>
              <Text strong>数据源</Text>：同花顺iFind→东方财富（自动回退）<br/>
              <Text strong>覆盖范围</Text>：5490只股票的利润表、资产负债表、现金流量表
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>使用说明</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="推荐更新时机"
        description="建议在每日收盘后1~2小时执行数据更新，此时当天的行情数据已准备好。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="一键更新">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              运行 <Text code>python update_data/update_stock_data.py</Text> 更新所有数据。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="分类更新">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              可通过参数选择只更新特定数据：<Text code>--skip-daily</Text>、<Text code>--skip-info</Text> 等。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="断点续传">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              使用 <Text code>--resume</Text> 参数跳过已有数据的股票，适合网络中断后继续。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
