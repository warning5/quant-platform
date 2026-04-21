import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert } from 'antd';

const { Title, Paragraph, Text } = Typography;

export default function ManualMarket() {
  return (
    <section id="market" style={{ paddingBottom: 32 }}>
      <Title level={2}>行情数据</Title>
      <Paragraph>
        行情数据页面展示沪深北三市的股票、指数、期货等行情信息。支持多维度筛选、实时数据查看、历史走势分析。
      </Paragraph>

      <Title level={4}>市场分类</Title>
      <Row gutter={[8, 8]} style={{ marginBottom: 16 }}>
        <Col><Tag color="red">沪市</Tag></Col>
        <Col><Tag color="blue">深市</Tag></Col>
        <Col><Tag color="purple">北交所</Tag></Col>
      </Row>

      <Title level={4}>筛选条件</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="股票代码/名称">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              支持模糊匹配，输入股票代码或名称的一部分即可搜索。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="日期范围">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              选择历史区间的起始和结束日期，默认显示最近20个交易日。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="指标类型">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              可选择显示成交量、成交额、换手率、市盈率、市净率等字段。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>数据说明</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="数据来源"
        description="行情数据来源于 Baostock（沪深）、腾讯证券（北交所）。每日收盘后1~2小时更新。"
      />

      <Title level={4}>功能特点</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="实时行情">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              查看最新交易日的开盘、收盘、最高、最低、成交量、成交额等。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="历史走势">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              查看任意历史区间的K线走势，支持多时间周期切换。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="涨跌幅排序">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              按涨跌幅、换手率、成交量等指标排序，快速发现异动股票。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card type="inner" size="small" title="指数数据">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              支持沪深300、中证500、上证指数、深证成指等主要指数。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
