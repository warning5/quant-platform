import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Divider, Steps } from 'antd';
import { QuestionCircleOutlined, BulbOutlined } from '@ant-design/icons';
const { Title, Paragraph, Text } = Typography;

export default function ManualStockAnalysisDetail() {
  return (
    <section id="analysis-detail" style={{ paddingBottom: 32 }}>
      <Title level={2}><QuestionCircleOutlined /> 个股分析使用详解</Title>
      <Paragraph>
        本节详细介绍如何使用个股分析模块进行<Text strong>股票全方位体检</Text>，
        包括操作流程、评分案例、多维度决策方法等。
      </Paragraph>

      <Divider orientation="left" plain>操作流程</Divider>
      <Steps
        direction="vertical"
        size="small"
        items={[
          { title: '搜索股票', description: '在搜索框输入股票代码或名称，支持防抖联想（300ms延迟）' },
          { title: '查看综合评分', description: '系统自动计算四维度评分（技术面30+资金面25+事件面20+基本面25）' },
          { title: '分析各维度详情', description: '展开技术面/资金面/事件面/基本面标签，查看详细评分依据' },
          { title: '查看研报舆情', description: '滚动到研报舆情部分，查看机构评级和情绪调查' },
          { title: '同业对比', description: '查看该股票在行业中的估值和盈利位置' },
          { title: '决策参考', description: '综合四维度评分和案例，做出投资决策（仅供参考）' },
        ]}
        style={{ marginBottom: 24 }}
      />

      <Divider orientation="left" plain>评分案例</Divider>
      <Paragraph>以下通过两个案例说明如何解读四维度评分：</Paragraph>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} md={12}>
          <Card size="small" title="📈 案例A：强势成长股" style={{ borderLeft: '4px solid #f5222d' }}>
            <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
              <li><Text strong>综合评分</Text>：85分（重点关注）</li>
                <li><Text strong>技术面</Text>：75分（均线多头排列+MACD金叉+RSI未超买）</li>
                <li><Text strong>资金面</Text>：20分（主力连续3日净流入+量比超过1.5）</li>
                <li><Text strong>事件面</Text>：16分（买入评级占比70%+近期有机构调研）</li>
              <li><Text strong>基本面</Text>：20分（ROE=18%，营收增速=25%，现金流健康）</li>
              <li><Text type="success">决策参考</Text>：四维度均高分，可重点关注，建议等待技术面回调至布林中轨附近介入</li>
            </ul>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" title="📉 案例B：弱势价值股" style={{ borderLeft: '4px solid #52c41a' }}>
            <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, lineHeight: 1.8 }}>
              <li><Text strong>综合评分</Text>：55分（可纳入观察池）</li>
              <li><Text strong>技术面</Text>：30分（均线空头排列，但出现缠论「底背弛」信号）</li>
              <li><Text strong>资金面</Text>：10分（主力净流出，但流出量在减少）</li>
              <li><Text strong>事件面</Text>：12分（买入评级占比50%，近期无调研）</li>
              <li><Text strong>基本面</Text>：22分（ROE=12%，营收增速=5%，但PB=0.8，低估）</li>
              <li><Text type="warning">决策参考</Text>：技术面出现反弹信号，基本面低估，可小仓位试探，止损设在近期低点</li>
            </ul>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>多维度决策方法</Divider>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="🎯 维度权重调整" style={{ borderLeft: '4px solid #1677ff' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              不同投资者可关注不同维度：
              <ul style={{ margin: '4px 0 0', paddingLeft: 16, fontSize: 11, lineHeight: 1.8 }}>
                <li><Text strong>趋势交易者</Text>：技术面权重最高（关注均线+MACD+缠论信号）</li>
                <li><Text strong>价值投资者</Text>：基本面权重最高（关注ROE+营收增速+估值分位）</li>
                <li><Text strong>事件驱动者</Text>：事件面权重最高（关注研报评级+机构调研+大宗交易）</li>
                <li><Text strong>资金流派</Text>：资金面权重最高（关注主力净流入+量比+换手率）</li>
              </ul>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" title="📊 评分组合策略" style={{ borderLeft: '4px solid #fa8c16' }}>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              可以自定义评分组合规则：
                <ul style={{ margin: '4px 0 0', paddingLeft: 16, fontSize: 11, lineHeight: 1.8 }}>
                  <li><Text strong>保守策略</Text>：综合评分≥80 且 四维度均≥15</li>
                  <li><Text strong>激进策略</Text>：综合评分≥60 且 技术面≥50</li>
                  <li><Text strong>价值策略</Text>：综合评分≥60 且 基本面≥18 且 PB分位小于30%</li>
                  <li><Text strong>反弹策略</Text>：技术面小于30 但出现缠论「底背弛」信号</li>
                </ul>
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Divider orientation="left" plain>注意事项</Divider>
      <Alert type="warning" showIcon message="使用注意事项"
        description={
          <div>
            <ul style={{ paddingLeft: 20, marginBottom: 0 }}>
              <li><Text strong>评分是参考工具</Text>：四维度评分是量化工具，不构成投资建议，请结合自己的投资体系使用</li>
              <li><Text strong>避免单一维度决策</Text>：单一维度高分不代表可买入，需综合多维度判断</li>
              <li><Text strong>关注评分变化</Text>：评分是动态的，建议定期（每周）重新分析持仓股票</li>
              <li><Text strong>结合定性分析</Text>：量化评分无法捕捉所有信息（如政策变化、行业拐点），需结合定性分析</li>
              <li><Text strong>风险控制</Text>：即使四维度评分高分，也要设置止损位，控制单只股票仓位≤10%</li>
            </ul>
          </div>
        }
        style={{ marginBottom: 16 }}
      />

      <Divider orientation="left" plain>常见问题</Divider>
      <Row gutter={[16, 16]}>
        {[
          { q: '为什么某股票无基本面评分？', a: '可能该股票缺少财务数据（新上市或财务数据未更新），请先运行财务数据更新。' },
          { q: '技术面评分突然大幅下降？', a: '可能出现了死叉、跌破关键均线或缠论笔方向改变，建议查看技术面详情确认。' },
          { q: '资金面评分和日涨跌不一致？', a: '资金面看的是主力净流入（大单），而日涨跌受所有交易影响，两者不一定一致。' },
          { q: '如何导出分析结果？', a: '当前版本不支持导出，建议截图或手动记录关键评分和结论。' },
          { q: '评分可以作为买卖点吗？', a: '评分是股票体检工具，不是择时工具。建议结合技术面图表（如均线、布林带）确定买卖点。' },
        ].map((item, i) => (
          <Col xs={24} md={12} key={i}>
            <Card size="small" type="inner">
              <Text strong>Q: {item.q}</Text>
              <Paragraph style={{ fontSize: 12, margin: '4px 0 0' }}>A: {item.a}</Paragraph>
            </Card>
          </Col>
        ))}
      </Row>
    </section>
  );
}
