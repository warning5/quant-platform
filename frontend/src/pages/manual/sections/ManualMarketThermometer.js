import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Descriptions } from 'antd';
import { ControlOutlined, ThunderboltOutlined, FundOutlined, LineChartOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export default function ManualMarketThermometer() {
  return (
    <section id="market-thermometer" style={{ paddingBottom: 32 }}>
      <Title level={2}><ControlOutlined /> 大盘温度计</Title>
      <Paragraph>
        大盘温度计（Market Thermometer）是衡量 A 股市场整体情绪的量化工具，
        综合 PE 分位、PB 分位、均线温度、股债收益比、QVIX 波动率五个维度，
        输出综合情绪指数（0~100）和"极度恐慌→极度贪婪"七档标签，
        帮助判断当前市场位置和入场/离场时机。
      </Paragraph>

      <Alert type="success" showIcon style={{ marginBottom: 16 }}
        message="核心价值"
        description="把多个宏观指标和情绪指标融合为一个综合评分，1分钟判断市场当前是「极度恐慌」还是「极度贪婪」，辅助仓位管理和择时决策。" />

      <Divider orientation="left" plain>五维度指标体系</Divider>

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        {[
          {
            dim: 'PE分位',
            weight: '30%',
            icon: '📊',
            color: '#1677ff',
            desc: '沪深300指数 PE-TTM 在历史区间的分位数',
            detail: 'PE分位 < 20% = 极度低估；20~40% = 低估；40~60% = 合理；60~80% = 高估；> 80% = 极度高估',
            formula: 'PE分位 = 当前PE / 历史PE区间 × 100%',
          },
          {
            dim: 'PB分位',
            weight: '20%',
            icon: '📉',
            color: '#13c2c2',
            desc: '沪深300指数 PB 在历史区间的分位数',
            detail: 'PB分位与PE分位结合，排除周期股的PE失真问题',
            formula: 'PB分位 = 当前PB / 历史PB区间 × 100%',
          },
          {
            dim: '均线温度',
            weight: '30%',
            icon: '🌡️',
            color: '#fa8c16',
            desc: '主要均线（MA5/MA20/MA60/MA120）的多头/空头排列程度',
            detail: '均线多头排列越多，分数越高；空头排列越多，分数越低',
            formula: '均线温度 = 多头均线数 / 总均线数 × 100',
          },
          {
            dim: '股债收益比',
            weight: '20%',
            icon: '⚖️',
            color: '#52c41a',
            desc: 'A股盈利收益率（E/P）与国债收益率的比值',
            detail: '股债收益比 > 1 = 股票相对债券更有吸引力；比值越高，市场越被低估',
            formula: '股债收益比 = A股E/P ÷ 10年期国债收益率',
          },
          {
            dim: 'QVIX波动率',
            weight: '（参考）',
            icon: '📈',
            color: '#722ed1',
            desc: '恐慌指数，反映市场对未来30日波动率的预期',
            detail: 'QVIX > 30 = 市场恐慌；QVIX < 15 = 市场极度乐观；当前（300ETF QVIX）均值19.73，波动范围12.9~45.9',
            formula: '数据来源：300ETF期权隐含波动率（若指数QVIX不可用则用ETF QVIX）',
          },
        ].map(d => (
          <Col xs={24} md={12} key={d.dim}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${d.color}` }}>
              <Text strong style={{ fontSize: 14 }}>{d.icon} {d.dim}</Text>
              <Tag color={d.color} style={{ marginLeft: 8 }}>{d.weight}</Tag>
              <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                <Text type="secondary">说明：</Text>{d.desc}
              </Paragraph>
              <Paragraph style={{ fontSize: 11, margin: '2px 0 0', color: d.color }}>
                {d.detail}
              </Paragraph>
              <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>
                <Text type="secondary">公式：</Text><Text code>{d.formula}</Text>
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left" plain>综合指数计算公式</Divider>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="加权综合公式"
        description={
          <div>
            <Text strong>Fear &amp; Greed Index = PE分位 × 30% + PB分位 × 20% + 均线温度 × 30% + 股债得分 × 20%</Text>
            <br />
            <Text type="secondary" style={{ fontSize: 12 }}>
              注：QVIX 作为参考指标，暂不直接纳入综合指数计算
            </Text>
          </div>
        }
      />

      <Descriptions size="small" bordered column={1} style={{ marginBottom: 16 }}>
        <Descriptions.Item label="PE分位计算">
          沪深300指数当前PE-TTM在近5年历史区间（低值~高值）中的分位
        </Descriptions.Item>
        <Descriptions.Item label="均线温度计算">
          MA5/MA20/MA60/MA120 四条均线的排列状态：全部多头=100，全部空头=0，混合状态按比例折算
        </Descriptions.Item>
        <Descriptions.Item label="股债得分">
          将股债收益比映射到0~100分：比值 {'>'} 2 = 100分，1.5~2 = 75分，1.0~1.5 = 50分，{'<'} 1 = 25分
        </Descriptions.Item>
        <Descriptions.Item label="QVIX数据">
          当前使用300ETF QVIX（沪深300指数QVIX接口已失效）；均值19.73，标准差约8.0；与历史波动率相关系数0.702
        </Descriptions.Item>
      </Descriptions>

      <Divider orientation="left" plain>七档情绪标签</Divider>
      <Row gutter={[8, 8]} style={{ marginBottom: 16 }}>
        {[
          { label: '极度恐慌', color: '#f5222d', score: '0~14', desc: '市场严重超跌，估值极具吸引力，适合逆向布局' },
          { label: '恐慌', color: '#fa8c16', score: '15~28', desc: '市场情绪偏空，但可能接近底部区域' },
          { label: '偏恐慌', color: '#faad14', score: '29~42', desc: '情绪偏谨慎，可开始关注超跌优质标的' },
          { label: '中性', color: '#13c2c2', score: '43~56', desc: '市场情绪正常，无明显方向，适合持仓不动' },
          { label: '偏贪婪', color: '#52c41a', score: '57~70', desc: '情绪偏乐观，可适度加仓，但仍需精选个股' },
          { label: '贪婪', color: '#722ed1', score: '71~85', desc: '市场情绪过热，估值偏高，注意仓位控制' },
          { label: '极度贪婪', color: '#eb2f96', score: '86~100', desc: '市场严重泡沫化信号，考虑减仓或对冲' },
        ].map(s => (
          <Col xs={24} md={12} key={s.label}>
            <Card size="small" type="inner" style={{ borderLeft: `4px solid ${s.color}` }}>
              <Tag color={s.color}>{s.label}</Tag>
              <Text type="secondary" style={{ marginLeft: 8 }}>评分 {s.score}</Text>
              <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>{s.desc}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Divider orientation="left" plain>使用场景与操作建议</Divider>
      <Row gutter={[12, 12]}>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }} title="📈 辅助择时"
            extra={<Tag color="blue">仓位管理</Tag>}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>极度恐慌（{'<'} 14分）</Text>：可提升股票仓位至 80%+，增配高弹性标的<br/>
              <Text strong>中性（43~56分）</Text>：维持 50%~60% 仓位，偏向防御<br/>
              <Text strong>极度贪婪（{'>'} 85分）</Text>：降至 30% 以下，考虑对冲
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }} title="⚡ 模拟盘择时"
            extra={<Tag color="green">择时信号</Tag>}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              大盘温度计与模拟盘联动：
              <ul style={{ margin: '4px 0 0', paddingLeft: 16 }}>
                <li>均线温度 = <Text strong>空头</Text> 或 极度恐慌/恐慌 → <Text type="danger">禁止新开仓</Text></li>
                <li>情绪指数 = 极度贪婪 → <Text type="danger">清仓信号</Text></li>
                <li>中性偏多 → 正常交易</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }} title="🔄 长期定投"
            extra={<Tag color="orange">定投策略</Tag>}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              <Text strong>极度恐慌时</Text> → 加大定投金额（双倍/三倍）<br/>
              <Text strong>极度贪婪时</Text> → 暂停定投，甚至考虑止盈<br/>
              <Text strong>其他时间</Text> → 正常定投金额
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>数据说明</Divider>
      <Alert type="warning" showIcon style={{ marginBottom: 8 }}
        message="QVIX 数据来源说明"
        description={
          <div>
            <ul style={{ paddingLeft: 20, marginBottom: 0 }}>
              <li>沪深300指数 QVIX 接口已失效（接口不可用）</li>
              <li>当前使用 <Text strong>300ETF QVIX</Text> 作为替代，数据完整且可用</li>
              <li>300ETF QVIX 均值19.73，波动范围12.9~45.9，与HV相关系数0.702</li>
              <li>当指数QVIX接口恢复时，可平滑切换回指数QVIX</li>
            </ul>
          </div>
        }
      />
      <Alert type="info" showIcon
        message="数据更新频率"
        description="大盘温度计各维度每日收盘后随数据更新而刷新；QVIX 盘中实时更新，综合指数每15分钟刷新一次。" />
    </section>
  );
}
