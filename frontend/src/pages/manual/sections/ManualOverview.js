import React from 'react';
import { Card, Typography, Row, Col, Tag } from 'antd';

const { Title, Paragraph, Text } = Typography;

export default function ManualOverview() {
  return (
    <section id="overview" style={{ paddingBottom: 32 }}>
      <Title level={2}>量化因子研究平台</Title>
      <Paragraph>
        量化因子研究平台是一个集成了股票行情数据、财务数据、因子计算与测试、策略回测的完整量化研究工具。
        平台采用前后端分离架构，后端基于 Spring Boot，前端基于 React + Ant Design。
      </Paragraph>

      <Title level={4}>平台架构</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <Title level={5}>数据层</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              MySQL 数据库存储行情、财务、因子、回测等数据
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <Title level={5}>计算层</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              Python 批处理脚本计算因子值、Baostock/腾讯接口获取行情
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <Title level={5}>服务层</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              Spring Boot 提供 RESTful API，支持回测、参数优化等计算
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>核心功能</Title>
      <Row gutter={[8, 8]} style={{ marginBottom: 16 }}>
        <Col><Tag color="blue">行情数据</Tag></Col>
        <Col><Tag color="green">财务数据</Tag></Col>
        <Col><Tag color="orange">因子管理</Tag></Col>
        <Col><Tag color="purple">因子测试</Tag></Col>
        <Col><Tag color="gold">策略管理</Tag></Col>
        <Col><Tag color="red">策略回测</Tag></Col>
        <Col><Tag color="cyan">参数优化</Tag></Col>
        <Col><Tag color="magenta">因子权重优化</Tag></Col>
      </Row>

      <Title level={4}>技术栈</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" title="后端">
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              <li>Spring Boot 2.7</li>
              <li>MyBatis + MySQL</li>
              <li>Python 3.14（数据更新 + 因子计算）</li>
              <li>Baostock / 腾讯证券 / akshare</li>
            </ul>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" title="前端">
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              <li>React 18 + Ant Design 5</li>
              <li>ECharts 5（图表）</li>
              <li>React Router（路由）</li>
              <li>Axios（HTTP 请求）</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
