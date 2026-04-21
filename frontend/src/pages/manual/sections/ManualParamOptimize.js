import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Steps } from 'antd';
import { ExperimentOutlined, RocketOutlined, CalculatorOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export function ManualParamOptimize() {
  return (
    <section id="param-optimize" style={{ paddingBottom: 32 }}>
      <Title level={2}><ExperimentOutlined /> 参数优化（网格搜索）</Title>
      <Paragraph>
        参数优化模块通过穷举指定参数网格的所有组合，找出使目标函数最优的参数配置。
        页面分为左右两部分：左侧创建优化任务，右侧查看优化历史和结果。
      </Paragraph>

      <Title level={4}>页面布局</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>左侧：任务配置</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              可折叠区域，包含策略选择、日期范围、参数网格配置、目标函数选择。
              点击「开始优化」提交任务。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>右侧：优化历史</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              显示所有优化任务列表，点击「+」展开查看详情。
              详情包含最优结果、热力图、全部参数组合表格。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>优化流程</Title>
      <Steps
        current={3}
        items={[
          { title: '选择策略', description: '选择一个已创建的策略' },
          { title: '设置区间', description: '配置回测日期范围' },
          { title: '参数网格', description: '设置各参数的取值范围' },
          { title: '目标函数', description: '选择优化目标' },
          { title: '执行搜索', description: '自动网格搜索' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>目标函数</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>Sharpe 比率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              衡量风险调整后收益，是最综合的评价指标。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>年化收益率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              直接最大化绝对收益，适合追求高收益的投资者。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #faad14' }}>
            <Title level={5}>Calmar 比率</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              年化收益 ÷ 最大回撤，衡量收益与最大风险的比值。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>参数网格配置</Title>
      <Paragraph>
        支持两种参数设置方式：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner">
            <Title level={5}>范围模式</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              设置最小值、最大值和步长，自动生成等差数列。
              例如：最小值=10、最大值=30、步长=5 → [10, 15, 20, 25, 30]
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner">
            <Title level={5}>列表模式</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              手动输入逗号分隔的数值列表。
              例如：10,20,30 表示仅测试这3个值。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>任务详情（点击展开）</Title>
      <Paragraph>
        点击优化历史中的「+」展开任务详情：
      </Paragraph>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #ffd591' }}>
            <Title level={5}>最优结果</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              显示得分最高的参数组合，黄色高亮背景。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #d9d9d9' }}>
            <Title level={5}>参数网格</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              灰色背景显示各参数的取值范围。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #cf1322' }}>
            <Title level={5}>参数热力图</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              两个参数时显示热力图，颜色越深效果越好。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>全部参数组合</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              表格展示所有参数组合及指标，支持排序和分页。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>运行状态</Title>
      <Paragraph>
        任务提交后自动进入「运行中」状态，详情页面实时显示进度。
        完成后状态自动更新为「已完成」，可查看最优结果和热力图。
      </Paragraph>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 16 }}
        message="组合数爆炸"
        description="总组合数 = 各参数取值数的乘积。建议将总组合数控制在 200 以内，否则耗时过长。"
      />
    </section>
  );
}

export function ManualFromBacktestToTrading() {
  return (
    <section id="from-backtest-to-trading" style={{ paddingBottom: 32 }}>
      <Title level={2}><RocketOutlined /> 从回测到实战</Title>
      <Paragraph>
        本模块帮助投资者将回测验证过的策略应用于实际交易，实现策略的闭环管理。
      </Paragraph>

      <Title level={4}>工作流程</Title>
      <Steps
        current={2}
        items={[
          { title: '策略研发', description: '因子分析、策略构建' },
          { title: '回测验证', description: '历史数据回测' },
          { title: '参数优化', description: '网格搜索最优参数' },
          { title: '模拟交易', description: '验证策略实盘表现' },
          { title: '实盘对接', description: '对接券商API' },
        ]}
        style={{ marginBottom: 16 }}
      />

      <Title level={4}>注意事项</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="过拟合风险">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              避免在单一历史区间上过拟合，建议使用样本外测试验证。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="实盘差异">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              实盘交易需考虑滑点、流动性冲击、交易延迟等因素。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 16 }}
        message="风险提示"
        description="量化策略存在风险，过往业绩不代表未来表现。请根据自身风险承受能力谨慎投资。"
      />
    </section>
  );
}

export function ManualFactorWeightOptimize() {
  return (
    <section id="factor-weight-optimize" style={{ paddingBottom: 32 }}>
      <Title level={2}><CalculatorOutlined /> 因子权重优化</Title>
      <Paragraph>
        因子权重优化模块帮助投资者为多因子组合找到最优的因子权重配置。
        支持三种经典优化方法，适用于不同的投资目标和风险偏好。
      </Paragraph>

      <Title level={4}>三种优化方法</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #8c8c8c' }}>
            <Title level={5}>等权（EQUAL）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              最简单的配置方式，所有因子权重相等（1/n）。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #5470c6' }}>
            <Title level={5}>均值-方差（MARKOWITZ）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              基于 Markowitz 现代投资组合理论，优化使 Sharpe 比率最大化。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
            <Title level={5}>风险平价（RISK_PARITY）</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              使每个因子对组合总风险的贡献相等。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="数据说明"
        description="权重优化使用因子在截面上的 rank_value（归一化因子值）作为收益代理，分析因子间的相关性和收益特征。"
      />
    </section>
  );
}

export default { ManualParamOptimize, ManualFromBacktestToTrading, ManualFactorWeightOptimize };
