import React from 'react';
import { Card, Typography, FloatButton, Tabs, Tag, Alert, Row, Col, Statistic, Space } from 'antd';
import { QuestionCircleOutlined, SearchOutlined, FundOutlined } from '@ant-design/icons';
import ManualStockAnalysisDetail from './sections/ManualStockAnalysisDetail.js';
const { Title, Text, Paragraph } = Typography;

const stockAnalysisNav = [
  { id: 'overview',     label: '功能概述',   color: 'blue'      },
  { id: 'search',       label: '搜索股票',   color: 'green'     },
  { id: 'score',        label: '综合评分',   color: 'cyan'      },
  { id: 'technical',    label: '技术面',     color: 'orange'    },
  { id: 'money-flow',   label: '资金面',     color: 'red'       },
  { id: 'event',        label: '事件面',     color: 'purple'    },
  { id: 'fundamental',  label: '基本面',     color: 'geekblue'  },
  { id: 'research',     label: '研报分析',   color: 'gold'      },
  { id: 'peer',         label: '同业对比',   color: 'magenta'   },
  { id: 'valuation',    label: '估值分位',   color: 'volcano'   },
  { id: 'industry-corr',label: '行业关联',   color: 'geekblue'  },
  { id: 'limit-up',     label: '涨跌停',     color: 'red'       },
  { id: 'block-trade',  label: '大宗交易',   color: 'orange'    },
  { id: 'chan-chart',   label: '缠论图谱',   color: 'purple'    },
  { id: 'money-flow-history', label: '资金趋势', color: 'cyan'   },
  { id: 'relative-strength', label: '相对强弱', color: 'blue'    },
];

export default function ManualStockAnalysisPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          ⚡ 使用手册 · 个股分析
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>四维度评分 · 研报舆情 · 同业对比 · 缠论图谱</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} bodyStyle={{ padding: '8px 12px' }}>
        <Space size={[4, 4]} wrap>
          {stockAnalysisNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        {/* 功能概述 */}
        <section id="overview" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>功能概述</Title>
          <Paragraph>
            个股分析模块对单只股票进行<Text strong>四维度量化评分</Text>（技术面30 + 资金面25 + 事件面20 + 基本面25 = 满分100），
            结合研报舆情、同业对比、估值分位、缠论图谱等多维数据，提供综合性的投资参考。
          </Paragraph>
          <Alert type="success" showIcon message="核心价值"
            description="把缠论信号、均线趋势、资金流向、研报评级、财务健康度浓缩成一个百分制评分，1分钟完成一只股票的全方位体检。" />
        </section>

        {/* 搜索股票 */}
        <section id="search" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #52c41a', paddingLeft: 12, marginBottom: 16 }}>搜索股票</Title>
          <Row gutter={[16, 12]}>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
                <Text strong>联想搜索（防抖300ms）</Text>
                <Paragraph style={{ fontSize: 12, margin: '4px 0 0' }}>
                  输入股票代码或名称，系统自动联想匹配。选股后 URL 自动更新（可分享链接）。
                  支持直接访问 <Text code>/stock-analysis?code=600519</Text> 打开指定股票。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                <Text strong>快速操作建议</Text>
                <Paragraph style={{ fontSize: 12, margin: '4px 0 0' }}>
                  首次使用建议搜索龙头股（如 600519 茅台、000001 平安），
                  熟悉各维度评分后再扩展至全市场扫描。
                </Paragraph>
              </Card>
            </Col>
          </Row>
        </section>

        {/* 综合评分 */}
        <section id="score" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #13c2c2', paddingLeft: 12, marginBottom: 16 }}>综合评分规则（满分100）</Title>
          <Row gutter={[12, 12]}>
            {[
              { dim: '技术面', weight: 30, icon: '📊', color: '#1677ff',
                factors: '缠论笔方向 + 均线趋势 + MACD/RSI + 布林带位置 + 成交量比',
                conclusion: '技术面 > 70分 = 强势股；< 30分 = 弱势股；缠论「三买」信号额外加分' },
              { dim: '资金面', weight: 25, icon: '💰', color: '#fa8c16',
                factors: '主力净流入（当日+5日） + 量比 + 换手率偏离度',
                conclusion: '资金面 > 18分 = 主力积极介入；连续3日净流入是最强买入信号之一' },
              { dim: '事件面', weight: 20, icon: '📰', color: '#722ed1',
                factors: '研报评级 + 机构调研频次 + 大宗交易折溢价',
                conclusion: '事件面 > 14分 = 机构关注度高；买入评级占比 > 60% 是强信号' },
              { dim: '基本面', weight: 25, icon: '📑', color: '#52c41a',
                factors: 'ROE + 营收增速 + 净利润增速 + 毛利率 + 负债率 + 盈利含金量',
                conclusion: '基本面 > 18分 = 财务健康；ROE > 15% 且营收增速 > 10% = 成长股特征' },
            ].map(d => (
              <Col xs={24} md={12} key={d.dim}>
                <Card size="small" type="inner" style={{ borderLeft: `4px solid ${d.color}` }}>
                  <Text strong style={{ fontSize: 14 }}>{d.icon} {d.dim}（权重{d.weight}分）</Text>
                  <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                    <Text type="secondary">指标：</Text>{d.factors}
                  </Paragraph>
                  <Paragraph style={{ fontSize: 11, margin: '4px 0 0', color: d.color }}>
                    <Text strong>关键结论：</Text>{d.conclusion}
                  </Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
          <Alert type="info" showIcon style={{ marginTop: 12 }}
            message="评分使用建议"
            description="综合评分 ≥ 80：重点关注；60~80：可纳入观察池；< 60：建议回避或仅做反弹。评分是参考工具，不构成投资建议。" />
        </section>

        {/* 技术面详情 */}
        <section id="technical" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>技术面评分详情</Title>
          <Paragraph>技术面由 <Text strong>5个指标</Text> 综合评分，实时从 ClickHouse 计算：</Paragraph>
          <Row gutter={[12, 8]}>
            {[
              { name: '缠论笔方向', score: '0~8分', rule: '上行笔=8分，下行笔=0分，盘整=4分；出现「三买」额外+2分' },
              { name: '均线趋势', score: '0~6分', rule: 'MA5>MA20>MA60 多头排列=6分；反之空头排列=0分；金叉/死叉状态影响评分' },
              { name: 'MACD/RSI', score: '0~6分', rule: 'MACD柱>0 且 RSI(14)在30~70之间=满分；RSI<30超卖加分，>70超买减分' },
              { name: '布林带位置', score: '0~5分', rule: '股价在布林中轨以上=3分；突破上轨=5分；在中轨以下=0~2分' },
              { name: '成交量比', score: '0~5分', rule: '量比>1.5=5分；1.0~1.5=3分；<0.8=0分（缩量）' },
            ].map((item, i) => (
              <Col xs={24} md={12} key={i}>
                <Card size="small" type="inner">
                  <Text strong>{item.name}</Text> <Tag>{item.score}</Tag>
                  <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>{item.rule}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 资金面详情 */}
        <section id="money-flow" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #f5222d', paddingLeft: 12, marginBottom: 16 }}>资金面评分详情</Title>
          <Paragraph>资金面数据来自 <Text strong>东方财富真实主力净流入</Text>（非代理数据），覆盖全市场5000+只股票：</Paragraph>
          <Row gutter={[12, 8]}>
            {[
              { name: '主力净流入（当日）', weight: 10, rule: '净流入>5亿=10分；>1亿=7分；>0=5分；>-1亿=2分；≤-1亿=0分' },
              { name: '主力净流入占比', weight: 8, rule: '占比>10%=8分；>5%=6分；>0%=4分；>-5%=2分；≤-5%=0分' },
              { name: '量比', weight: 4, rule: '量比>2.0=4分；>1.5=3分；>1.0=2分；≤1.0=0~1分' },
              { name: '换手率偏离度', weight: 3, rule: '换手率>行业均值2倍=3分；>1.5倍=2分；正常范围内=1分；异常低=0分' },
            ].map((item, i) => (
              <Col xs={24} md={12} key={i}>
                <Card size="small" type="inner" style={{ borderLeft: '3px solid #fa8c16' }}>
                  <Text strong>{item.name}</Text> <Tag color="orange">权重{item.weight}</Tag>
                  <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>{item.rule}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
          <Alert type="warning" showIcon style={{ marginTop: 8 }}
            message="关键结论"
            description="主力连续3日净流入 + 量比>1.5 + 换手率突增 = 资金面满分特征，是短线最强买入信号组合。" />
        </section>

        {/* 事件面详情 */}
        <section id="event" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #722ed1', paddingLeft: 12, marginBottom: 16 }}>事件面评分详情</Title>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '3px solid #722ed1' }}>
                <Text strong>研报评级（权重12分）</Text>
                  <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                    买入/增持评级占比 × 12分；评级机构数量加权。
                    <Text strong>买入占比超过60%</Text> = 事件面高分核心信号。
                  </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '3px solid #722ed1' }}>
                <Text strong>机构调研（权重5分）</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  近90天接待调研次数排名分位 × 5分。
                  高频调研 = 机构关注度高的信号，往往先于股价表现。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '3px solid #722ed1' }}>
                <Text strong>大宗交易（权重3分）</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  折溢价率分析。溢价成交 = 机构看好（加分）；
                  大幅折价 = 机构出货信号（扣分）。
                </Paragraph>
              </Card>
            </Col>
          </Row>
        </section>

        {/* 基本面详情 */}
        <section id="fundamental" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #2f54eb', paddingLeft: 12, marginBottom: 16 }}>基本面评分详情</Title>
          <Paragraph>基本面数据来自 MySQL <Text code>stock_financial_indicator</Text> 表，覆盖近3年财报数据：</Paragraph>
          <Row gutter={[12, 8]}>
            {[
              { name: 'ROE（净资产收益率）', rule: 'ROE > 15% = 满分；10~15% = 及格；< 5% = 较差' },
              { name: '营收同比增速', rule: '营收增速 > 20% = 高成长；10~20% = 稳健；< 0% = 衰退' },
              { name: '净利润同比增速', rule: '净利增速 > 20% = 高成长；需结合营收增速判断质量（是否靠降本）' },
              { name: '毛利率', rule: '毛利率 > 30% = 有护城河；< 10% = 竞争激烈的行业' },
              { name: '资产负债率', rule: '负债率 < 50% = 安全；50~70% = 警惕；> 70% = 高风险' },
              { name: '盈利含金量', rule: '经营现金流/净利润 > 0.8 = 高质量盈利；< 0.5 = 应收账款风险' },
            ].map((item, i) => (
              <Col xs={24} md={8} key={i}>
                <Card size="small" type="inner">
                  <Text strong style={{ fontSize: 12 }}>{item.name}</Text>
                  <Paragraph style={{ fontSize: 11, margin: '2px 0 0' }}>{item.rule}</Paragraph>
                </Card>
              </Col>
            ))}
          </Row>
        </section>

        {/* 研报舆情 */}
        <section id="research" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #faad14', paddingLeft: 12, marginBottom: 16 }}>研报舆情分析</Title>
          <Paragraph>
            展示该股票近90天的 <Text strong>研究报告</Text>（来源：`stock_research_report` 表）
            和 <Text strong>舆情调查</Text>（来源：`stock_sentiment_survey` 表）。
          </Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="研报数据说明">
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>每份研报包含：评级（买入/增持/中性/减持）、目标价、发布机构</li>
                  <li>「买入」评级占比 = 事件面评分的核心输入</li>
                  <li>近90天无研报覆盖 = 机构关注度低，需谨慎</li>
                </ul>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" title="舆情数据说明">
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>
                  <li>调查日期、投资者情绪（看多/看空/中性）</li>
                  <li>情绪多空比超过 2:1 = 市场情绪极度乐观（反向指标参考）</li>
                </ul>
              </Card>
            </Col>
          </Row>
        </section>

        {/* 同业对比 */}
        <section id="peer" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #eb2f96', paddingLeft: 12, marginBottom: 16 }}>同业对比</Title>
          <Paragraph>
            基于 <Text code>stock_industry</Text> 表中的行业分类，将该股票与同业公司进行
            <Text strong>估值（PE/PB）和盈利能力（ROE/营收增速）</Text> 对比。
          </Paragraph>
          <Alert type="info" showIcon message="使用方法"
            description="在个股分析页点击「同业对比」Tab，系统自动展示同业公司的估值分位和盈利对比，快速判断该股票在行业中的相对位置。" />
          <Alert type="warning" showIcon style={{ marginTop: 8 }}
            message="注意"
            description="同业对比仅在股票有行业分类数据时可展示；若显示「暂无同业数据」，请先执行行业分类更新。" />
        </section>

        {/* 估值分位 */}
        <section id="valuation" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa541c', paddingLeft: 12, marginBottom: 16 }}>估值分位</Title>
          <Paragraph>基于近N年历史数据，计算当前 <Text strong>PE(TTM)</Text> 和 <Text strong>PB</Text> 在历史上的分位数（0~100%）：</Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #cf1322' }}>
                <Text strong>PE(TTM) 分位</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  市盈率（滚动）＝ 股价 ÷ 近12个月每股收益。<br/>
                  分位越低越便宜（&lt;20% 低估），分位越高越贵（&gt;80% 高估）。<br/>
                  <Text type="secondary">适用：绝大多数行业</Text>
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #389e0d' }}>
                <Text strong>PB 分位</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  市净率 ＝ 股价 ÷ 每股净资产。<br/>
                  适合评估金融、重资产行业（银行/钢铁/煤炭等）。<br/>
                  <Text type="secondary">破净或接近破净 = 安全边际高</Text>
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message="分位解读"
            description="＜20% 低估（安全边际高）；20%~50% 合理；50%~80% 偏贵；＞80% 高估（警惕回调）。建议结合基本面评分综合判断。" />
        </section>

        {/* 行业关联 */}
        <section id="industry-corr" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #2f54eb', paddingLeft: 12, marginBottom: 16 }}>行业关联</Title>
          <Paragraph>分析个股与所属行业的 <Text strong>Beta 系数</Text> 和 <Text strong>相关系数</Text>，理解个股的行业风险敞口：</Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }}>
                <Text strong>Beta ＞ 1.5</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  高弹性：行业涨跌1%，个股平均波动＞1.5%。<br/>
                  适合激进投资者，但下跌时亏损也更大。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Text strong>Beta 1.0~1.5</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  中等弹性：基本跟随行业波动。<br/>
                  最常见的个股风险水平。
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                <Text strong>Beta ＜ 0.5</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  防御性：波动小于行业，甚至逆势。<br/>
                  适合避险或对冲行业风险。
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message="近5日超额收益"
            description="展示个股与行业等权组合的近5日累计收益对比。RS Ratio ＞ 1 表示跑赢行业，＜ 1 表示跑输。关注「大幅跑赢」信号作为买入参考。" />
        </section>

        {/* 涨跌停 */}
        <section id="limit-up" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #f5222d', paddingLeft: 12, marginBottom: 16 }}>涨跌停分析</Title>
          <Paragraph>展示该股票的 <Text strong>涨停/跌停历史记录</Text>、涨停原因统计、炸板情况：</Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }}>
                <Text strong>涨停次数</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  涨停 = 强势信号，但需区分<br/>
                  首次涨停（启动）vs 连续涨停（高潮）
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Text strong>炸板率</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  涨停后打开 = 封板不稳<br/>
                  高炸板率 = 诱多信号，需警惕
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                <Text strong>跌停次数</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  跌停 = 强烈卖出信号<br/>
                  连续跌停 = 流动性危机或重大利空
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="warning" showIcon style={{ marginTop: 8 }}
            message="使用建议"
            description="涨停原因Top统计帮助判断涨停性质（政策利好/业绩预增/题材炒作）。首次涨停且原因明确，后续上涨概率更高。" />
        </section>

        {/* 大宗交易 */}
        <section id="block-trade" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #fa8c16', paddingLeft: 12, marginBottom: 16 }}>大宗交易分析</Title>
          <Paragraph>展示该股票的 <Text strong>大宗交易历史</Text>、折价率、买卖营业部统计：</Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Text strong>折价率分析</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  折价率 ＜ -10% = 大幅折价，可能暗示大股东减持意愿<br/>
                  溢价成交 = 机构看好，愿意以高于市价接盘
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
                <Text strong>营业部集中度</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  买方营业部集中 = 单一机构大举建仓（强势信号）<br/>
                  卖方营业部集中 = 单一股东大举减持（警惕信号）
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message="关键结论"
            description="大宗交易折价率高且连续发生 = 机构出货信号（扣分）。溢价成交且买方营业部集中 = 机构看好（加分），是事件面评分的输入之一。" />
        </section>

        {/* 缠论图谱 */}
        <section id="chan-chart" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #722ed1', paddingLeft: 12, marginBottom: 16 }}>缠论图谱</Title>
          <Paragraph>
            基于缠论理论实时计算 K线合并、笔、中枢、买卖点，<Text strong>可视化展示</Text>股票的技术结构。
            红色标记 <Text strong>买点</Text>，绿色标记 <Text strong>卖点</Text>。
          </Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }}>
                <Text strong>一买（底背弛）</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  趋势最低点，最安全买点<br/>
                  但等待确认需要时间
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa541c' }}>
                <Text strong>二买</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  一买后的第一次回调<br/>
                  风险收益比最优的买点
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #faad14' }}>
                <Text strong>三买（最强势）</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  中枢上方的买点<br/>
                  趋势加速阶段，短线最强信号
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={6}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                <Text strong>中枢（震荡区）</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  缠论核心价值：识别震荡区间<br/>
                  中枢突破 = 趋势延续信号
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="success" showIcon style={{ marginTop: 8 }}
            message="实战建议"
            description="三买出现 = 技术面额外+2分。中枢突破+三买组合 = 短线最强信号。图谱支持缩放查看近120天K线细节。" />
        </section>

        {/* 资金趋势 */}
        <section id="money-flow-history" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #13c2c2', paddingLeft: 12, marginBottom: 16 }}>资金趋势（近120日）</Title>
          <Paragraph>展示 <Text strong>近120日主力资金净流入/净流出趋势</Text> 及每日资金面评分（满分25），追踪大资金动向变化：</Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
                <Text strong>主力净流入趋势</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  连续5日净流入 = 强势信号<br/>
                  连续5日净流出 = 回避信号
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Text strong>资金面评分趋势</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  评分持续上升 = 资金面改善<br/>
                  评分从高位回落 = 资金撤离信号
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                <Text strong>净流入占比</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  占比持续为正 = 机构建仓<br/>
                  占比持续为负 = 机构出货
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="warning" showIcon style={{ marginTop: 8 }}
            message="关键结论"
            description="资金趋势比单日资金面评分更有参考价值。连续净流入 + 评分持续上升 = 最强买入信号组合。趋势反转（由正转负）是卖出预警信号。" />
        </section>

        {/* 相对强弱 */}
        <section id="relative-strength" style={{ paddingBottom: 16 }}>
          <Title level={4} style={{ borderLeft: '4px solid #1677ff', paddingLeft: 12, marginBottom: 16 }}>相对强弱（vs 行业）</Title>
          <Paragraph>对比个股与 <Text strong>同行业等权组合</Text> 的累计收益，计算 RS Ratio：</Paragraph>
          <Row gutter={[12, 8]}>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #f5222d' }}>
                <Text strong>RS Ratio ＞ 1.2</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  大幅跑赢行业<br/>
                  个股有独立强势逻辑
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #fa8c16' }}>
                <Text strong>RS Ratio 0.8~1.2</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  基本同步行业<br/>
                  无明显的相对强弱优势
                </Paragraph>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
                <Text strong>RS Ratio ＜ 0.8</Text>
                <Paragraph style={{ fontSize: 11, margin: '4px 0 0' }}>
                  跑输行业<br/>
                  即使行业上涨，个股也可能下跌
                </Paragraph>
              </Card>
            </Col>
          </Row>
          <Alert type="info" showIcon style={{ marginTop: 8 }}
            message="使用建议"
            description="RS Ratio 持续上升 = 个股相对行业越来越强，是超额收益的来源。选择在 RS Ratio ＞ 1 且处于上升通道的个股，胜率更高。" />
        </section>

        {/* 使用详解 */}
        <ManualStockAnalysisDetail />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
