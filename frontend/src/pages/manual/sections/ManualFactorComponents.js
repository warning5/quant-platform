import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert } from 'antd';
import { FundOutlined, CloudSyncOutlined, SearchOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export function ManualFactors() {
  return (
    <section id="factors" style={{ paddingBottom: 32 }}>
      <Title level={2}><FundOutlined /> 因子基础</Title>
      <Paragraph>
        因子是量化投资的核心概念，用于描述股票的某种属性或特征。平台支持多种类型的因子，包括内置因子和自定义因子。
      </Paragraph>

      <Title level={4}>因子分类</Title>
      <Row gutter={[8, 8]} style={{ marginBottom: 16 }}>
        <Col><Tag color="blue">动量</Tag></Col>
        <Col><Tag color="gold">价值</Tag></Col>
        <Col><Tag color="green">质量</Tag></Col>
        <Col><Tag color="orange">波动率</Tag></Col>
        <Col><Tag color="purple">技术</Tag></Col>
        <Col><Tag color="cyan">基本面</Tag></Col>
        <Col><Tag color="magenta">情绪</Tag></Col>
        <Col><Tag>自定义</Tag></Col>
      </Row>

      <Title level={4}>因子类型</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" title={<><Tag color="blue">BUILTIN</Tag> 内置因子</>}>
            <Paragraph style={{ fontSize: 13 }}>
              平台预置的因子，无法编辑和删除。包括8个常用因子：MOM20、MOM60、VOL20、TURN20、SIZE、RSI5、BOLL_POS、VPCORR20。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" title={<><Tag color="purple">SCRIPTED</Tag> 脚本因子</>}>
            <Paragraph style={{ fontSize: 13 }}>
              通过Groovy脚本语言自定义计算的因子。支持实时语法验证、模板支持、沙箱执行。可以灵活定义各种复杂因子。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" title={<><Tag color="gold">COMPOSITE</Tag> 合成因子</>}>
            <Paragraph style={{ fontSize: 13 }}>
              由多个基础因子组合而成的因子。可以通过线性组合、加权平均等方式构建更复杂的因子逻辑。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>常用内置因子详解</Title>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small">
            <Title level={5}>🚀 动量因子（MOM）</Title>
            <Paragraph>
              <Text strong>含义</Text>：衡量股票价格的历史涨跌趋势，反映市场动量效应。<br/>
              <Text strong>计算公式</Text>：MOM_N = (Close_t - Close_t-N) / Close_t-N<br/>
              <Text strong>参数</Text>：N 为计算周期（如 20 日）<br/>
              <Text strong>使用场景</Text>：动量因子通常用于捕捉趋势延续，值越大表示近期表现越好。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small">
            <Title level={5}>📊 波动率因子（VOL）</Title>
            <Paragraph>
              <Text strong>含义</Text>：衡量股票价格的波动程度，反映风险水平。<br/>
              <Text strong>计算公式</Text>：VOL_N = StdDev(Return_1, Return_2, ..., Return_N)<br/>
              <Text strong>参数</Text>：N 为计算周期（如 20 日）<br/>
              <Text strong>使用场景</Text>：波动率通常与风险正相关，风险厌恶投资者倾向于低波动股票。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small">
            <Title level={5}>💰 市值因子（SIZE）</Title>
            <Paragraph>
              <Text strong>含义</Text>：衡量股票的市场规模，反映公司规模大小。<br/>
              <Text strong>计算公式</Text>：SIZE = log(MarketCap)<br/>
              <Text strong>使用场景</Text>：小市值股票通常具有更高的增长潜力和风险。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}

export function ManualFactorMonitor() {
  return (
    <section id="factor-monitor" style={{ paddingBottom: 32 }}>
      <Title level={2}><CloudSyncOutlined /> 因子计算监控</Title>
      <Paragraph>
        因子计算监控页面用于批量计算因子值。用户可以选择要计算的因子、日期范围，系统自动批量计算并存储到数据库。
      </Paragraph>

      <Title level={4}>计算模式</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="全量模式">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              从指定开始日期计算到结束日期。首次计算时使用全量模式。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="增量模式">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              只计算最新日期的因子值，适合每日收盘后更新。计算速度快，节省资源。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="单因子模式">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              只计算选中的单个因子，用于调试或补充计算某个特定因子。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>技术因子</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="日频技术因子"
        description="技术因子在每个交易日收盘后计算，使用当日及之前的历史数据。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="动量类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              MOM_N（N日收益率）、ROC_N（N日变化率）、DMA（不同周期均线差）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="波动类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              VOL_N（N日波动率）、ATR_N（N日真实波幅）、STD_N（N日标准差）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="量价类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              TURN_N（N日换手率）、VROC_N（N日成交量变化率）、VAMP（成交量均线）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="趋势类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              MA_N（N日均线）、BOLL_POS（布林带位置）、RSI_N（N日RSI）
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}

export function ManualFactorDetail() {
  return (
    <section id="factor-detail" style={{ paddingBottom: 32 }}>
      <Title level={2}><SearchOutlined /> 因子值查看</Title>
      <Paragraph>
        因子值查看页面用于查看单只股票或全市场的因子值分布。支持时间序列分析和截面分布分析。
      </Paragraph>

      <Title level={4}>分析模式</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="⏰ 时间序列分析">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              查看单只股票在不同时间点的因子值变化，以及因子排名（百分位）。可以了解因子的历史表现和稳定性。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="📈 截面分布分析">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              查看某一天所有股票的因子值分布，了解因子值的整体范围和分布情况。可以用于因子值标准化和异常值检测。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>因子值说明</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="归一化处理"
        description="因子值经过横截面Z-Score标准化处理，便于不同因子之间的比较和分析。"
      />
    </section>
  );
}

export default { ManualFactors, ManualFactorMonitor, ManualFactorDetail };
