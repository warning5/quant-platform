import React, { useEffect, useState, useRef } from 'react';
import { Row, Col, Card, Statistic, Typography, Spin, Tooltip, Badge, Divider, Space } from 'antd';
import {
  ControlOutlined, SwapOutlined,
  DollarOutlined, FundOutlined, BankOutlined, InsuranceOutlined,
  ThunderboltOutlined,
  QuestionCircleOutlined,
  TrophyOutlined, BuildOutlined,
} from '@ant-design/icons';
import ReactECharts from '../../components/LazyECharts';
import { stockAnalysisApi } from '../../api';

const { Title, Text, Paragraph } = Typography;

// 恐慌贪婪配色
const FEAR_GREED_COLOR = (score) => {
  if (score >= 70) return '#52c41a';
  if (score >= 55) return '#73d13d';
  if (score >= 45) return '#faad14';
  if (score >= 30) return '#ff7a45';
  return '#ff4d4f';
};

const FEAR_GREED_BG = (score) => {
  if (score >= 70) return 'linear-gradient(135deg, #f6ffed 0%, #d9f7be 100%)';
  if (score >= 55) return 'linear-gradient(135deg, #fcffe6 0%, #f0ffb8 100%)';
  if (score >= 45) return 'linear-gradient(135deg, #fffbe6 0%, #fff1b8 100%)';
  if (score >= 30) return 'linear-gradient(135deg, #fff2e8 0%, #ffd8bf 100%)';
  return 'linear-gradient(135deg, #fff1f0 0%, #ffccc7 100%)';
};

export default function MarketThermometer() {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const load = () => {
    setLoading(true);
    stockAnalysisApi.getMarketThermometer()
      .then(d => { setData(d); setError(null); })
      .catch(e => { setError(e.message); })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);
  // 每5分钟自动刷新
  useEffect(() => { const t = setInterval(load, 5 * 60 * 1000); return () => clearInterval(t); }, []);

  if (loading && !data) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;

  const fg = data?.fearGreedIndex ?? 50;
  const fgColor = FEAR_GREED_COLOR(fg);

  // PE分位图配置
  const peChartData = (data?.pePercentileHistory || []).map(h => ({
    date: h.date?.slice(5) || '',
    value: h.percentile,
  })).reverse();

  const peOption = {
    tooltip: { trigger: 'axis', formatter: p => `PE分位: ${p[0].value?.toFixed(1)}%` },
    grid: { left: 50, right: 20, top: 10, bottom: 25 },
    xAxis: { type: 'category', data: peChartData.map(d => d.date), axisLabel: { rotate: 0, fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: v => `${v}%` } },
    series: [{ data: peChartData.map(d => d.value), type: 'line', smooth: true, lineStyle: { width: 2 }, symbol: 'circle', symbolSize: 4 }],
  };

  // 股债比图配置
  const bondChartData = (data?.bondRatioHistory || []).map(h => ({
    date: h.date?.slice(5) || '',
    ratio: h.ratio,
  })).reverse();
  const bondOption = {
    tooltip: { trigger: 'axis', formatter: p => `股债收益比: ${p[0].value?.toFixed(2)}` },
    grid: { left: 50, right: 20, top: 10, bottom: 25 },
    xAxis: { type: 'category', data: bondChartData.map(d => d.date), axisLabel: { rotate: 0, fontSize: 10 } },
    yAxis: { type: 'value' },
    series: [
      { data: bondChartData.map(d => d.ratio), type: 'line', smooth: true, lineStyle: { width: 2, color: '#fa8c16' }, symbol: 'circle', symbolSize: 4 },
      { data: bondChartData.map(() => 3.0), type: 'line', lineStyle: { color: '#52c41a', type: 'dashed', width: 1 }, symbol: 'none' },
      { data: bondChartData.map(() => 1.5), type: 'line', lineStyle: { color: '#ff4d4f', type: 'dashed', width: 1 }, symbol: 'none' },
    ],
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Space>
          <Title level={4} style={{ margin: 0 }}>大盘温度计</Title>
          <Tooltip
            title={
              <div style={{ fontSize: 12, lineHeight: 1.8 }}>
                <div style={{ fontWeight: 600, marginBottom: 6 }}>📊 大盘温度计有什么用？</div>
                <div>综合评估市场估值、趋势、资金情绪，输出 0-100 的恐慌贪婪指数，帮助判断当前市场整体水位。</div>
                <div style={{ marginTop: 8 }}><strong>核心价值：</strong></div>
                <div>• <strong>仓位管理</strong>：极度恐慌(0-20)→加仓，极度贪婪(80-100)→减仓</div>
                <div>• <strong>择时参考</strong>：股债收益比&gt;3股票低估，&lt;1.5高估</div>
                <div>• <strong>过滤信号</strong>：结合因子选股，温度&lt;30时做多，&gt;70时停止开仓</div>
                <div style={{ marginTop: 8 }}><strong>配合功能：</strong></div>
                <div>• <strong>因子选股</strong>：大盘低估值时优先执行价值/质量因子信号</div>
                <div>• <strong>模拟盘</strong>：大盘温度&gt;70时自动暂停买入信号执行</div>
                <div>• <strong>个股分析</strong>：个股估值与大盘分位对比，判断相对高低估</div>
                <div style={{ marginTop: 8 }}><strong>综合指数计算公式（5维，v3）：</strong></div>
                <div>PE分位×25% + PB分位×15% + 均线温度×25% + 股债得分×20% + 波动率指数×15%</div>
                <div style={{ marginTop: 4, color: '#888' }}>波动率指数（QVIX）：akshare index_option_300etf_qvix()，归一化分位得分</div>

                <div style={{ marginTop: 10, borderTop: '1px solid #eee', paddingTop: 8 }}>
                  <div style={{ fontWeight: 600, marginBottom: 4 }}>🎨 风格/大小盘判断</div>
                  <div>根据价值/成长指数、大盘/小盘指数近20日涨幅差，自动判断当前市场风格。</div>
                  <div style={{ marginTop: 4 }}><strong>意义：</strong>帮你判断当前市场应该用什么策略、选什么类型的股票。</div>
                  <table style={{ fontSize: 11, marginTop: 4, borderCollapse: 'collapse', width: '100%' }}>
                    <thead><tr><th style={{ textAlign: 'left', padding: '2px 4px', borderBottom: '1px solid #ddd' }}>判断</th><th style={{ textAlign: 'left', padding: '2px 4px', borderBottom: '1px solid #ddd' }}>含义</th><th style={{ textAlign: 'left', padding: '2px 4px', borderBottom: '1px solid #ddd' }}>怎么做</th></tr></thead>
                    <tbody>
                      <tr><td style={{ padding: '2px 4px', fontWeight: 600 }}>成长占优</td><td style={{ padding: '2px 4px' }}>市场偏爱高增长故事股</td><td style={{ padding: '2px 4px' }}>优先技术/资金因子（动量、波动率），看中小盘</td></tr>
                      <tr><td style={{ padding: '2px 4px', fontWeight: 600 }}>价值占优</td><td style={{ padding: '2px 4px' }}>市场回归基本面</td><td style={{ padding: '2px 4px' }}>优先估值/质量因子（PE、ROE），看红利/银行</td></tr>
                      <tr><td style={{ padding: '2px 4px', fontWeight: 600 }}>小盘强势</td><td style={{ padding: '2px 4px' }}>资金炒小票</td><td style={{ padding: '2px 4px' }}>优选中小市值，注意流动性，控制单票仓位</td></tr>
                      <tr><td style={{ padding: '2px 4px', fontWeight: 600 }}>大盘强势</td><td style={{ padding: '2px 4px' }}>资金抱团蓝筹</td><td style={{ padding: '2px 4px' }}>优选沪深300成分股，加大单票仓位</td></tr>
                    </tbody>
                  </table>
                </div>

                <div style={{ marginTop: 8, color: '#aaa' }}>仅供参考，不构成投资建议。历史业绩不代表未来表现。</div>
              </div>
            }
            placement="bottomLeft"
            styles={{ root: {maxWidth: 480} }}
          >
            <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 16 }} />
          </Tooltip>
        </Space>
        <Space>
          <Text type="secondary">数据日期：{data?.tradeDate}</Text>
          <Text type="secondary">更新时间：{data?.updateTime}</Text>
          <a onClick={load}>刷新</a>
        </Space>
      </div>

      {error && <Card><Text type="danger">加载失败：{error}</Text></Card>}

      {/* 资金流向数据来源警告（数据日期 <= 前天时显示） */}
      {(() => {
        const mfDate = data?.moneyflowMaxDate;
        if (!mfDate) return null;
        const today = new Date();
        const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
        const mf = new Date(mfDate + 'T00:00:00');
        // 显示警告条件：数据日期 < 昨天
        const showWarn = mf < yesterday;
        if (!showWarn) return null;
        return (
          <Card
            style={{ marginBottom: 16, background: '#fffbe6', border: '1px solid #ffe58f' }}
            styles={{ body: { padding: '12px 16px' } }}
          >
            <Space>
              <span style={{ fontSize: 16 }}>⚠️</span>
              <Text style={{ fontSize: 13, color: '#ad6800' }}>
                <strong>资金流向数据说明：</strong>
                数据基于东财接口获取，最新数据日期为 {mfDate}，此后全市场日度更新已暂停（接口级 IP 封锁）。
                当前显示数据为封锁前已入库的历史数据，实际金额可能偏小，仅供参考。
              </Text>
            </Space>
          </Card>
        );
      })()}

      {/* 综合恐慌贪婪指数 - 大卡片 */}
      <Card
        style={{
          marginBottom: 16,
          background: FEAR_GREED_BG(fg),
          border: 'none',
        }}
        styles={{ body: { padding: '32px 32px 24px' } }}
      >
        <Row gutter={24} align="middle">
          <Col xs={24} md={8} style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 72, fontWeight: 700, color: FEAR_GREED_COLOR(fg), lineHeight: 1 }}>
              {fg.toFixed(1)}
            </div>
            <div style={{ fontSize: 20, color: FEAR_GREED_COLOR(fg), marginTop: 8 }}>
              {data?.fearGreedLabel}
            </div>
            <Space size={4}>
              <Text style={{ color: '#666', fontSize: 12 }}>
                恐慌贪婪指数（0极度恐慌 ~ 100极度贪婪）
              </Text>
              <Tooltip
                title={
                  (() => {
                    const pe = data?.pePercentile ?? 0;
                    const pb = data?.pbPercentile ?? 0;
                    const ma = data?.maTemperature ?? 0;
                    const ratio = data?.stockBondRatio ?? 0;
                    const bondScore = 0; // 股债比 < 1.5，该项贡献为0
                    const qvix = data?.qvixScore ?? 50;
                    const peW = pe * 0.25;
                    const pbW = pb * 0.15;
                    const maW = ma * 0.25;
                    const bondW = bondScore * 0.20;
                    const qvixW = qvix * 0.15;
                    return (
                      <div style={{ fontSize: 12, lineHeight: 1.8, minWidth: 320 }}>
                        <div style={{ fontWeight: 600, marginBottom: 8 }}>📐 综合指数计算过程</div>
                        <div><strong>各维度得分（满分100）：</strong></div>
                        <div>• PE分位数：{pe.toFixed(1)} × 25% = {peW.toFixed(1)}</div>
                        <div>• PB分位数：{pb.toFixed(1)} × 15% = {pbW.toFixed(1)}</div>
                        <div>• 均线温度：{ma.toFixed(1)} × 25% = {maW.toFixed(1)}</div>
                        <div>• 股债收益比得分：{bondScore.toFixed(1)} × 20% = {bondW.toFixed(1)}</div>
                        <div style={{ paddingLeft: 12, color: '#fa8c16', fontSize: 11 }}>（⚠️ 股债比当前为 {ratio.toFixed(2)}，低于阈值1.5，该项得分为0）</div>
                        <div>• 波动率指数得分：{qvix.toFixed(1)} × 15% = {qvixW.toFixed(1)}</div>
                        <div style={{ marginTop: 8, padding: '6px 10px', background: '#f5f5f5', borderRadius: 4 }}>
                          <strong>综合指数 = </strong>
                          {peW.toFixed(1)} + {pbW.toFixed(1)} + {maW.toFixed(1)} + {bondW.toFixed(1)} + {qvixW.toFixed(1)}
                          <strong> = {fg.toFixed(1)}</strong>
                        </div>
                      </div>
                    );
                  })()
                }
                styles={{ root: {maxWidth: 420} }}
              >
                <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 13 }} />
              </Tooltip>
            </Space>
          </Col>
          <Col xs={24} md={16}>
            {/* 第一行：PE + PB + 均线温度 */}
            <Row gutter={[12, 12]}>
              <Col span={8}>
                <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                  <Statistic
                    title={
                      <Space size={4}>
                        <span>PE分位数</span>
                        <Tooltip
                          title={
                            <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 360 }}>
                              <div style={{ fontWeight: 600, marginBottom: 6 }}>📊 PE分位数 — 计算逻辑</div>
                              <div><strong>数据源：</strong>全市场A股（剔除 PE≤0 或 PE≥500 的异常值）</div>
                              <div><strong>当前值：</strong>当日全市场 PE 等权均值</div>
                              <div><strong>分位计算：</strong>过去 3 年每日 PE 均值组成的序列中，当前值排在第几位</div>
                              <div><strong>解读：</strong>0% = 历史最低估值，100% = 历史最高估值</div>
                              <div style={{ marginTop: 6, padding: '4px 8px', background: '#f5f5f5', borderRadius: 4 }}>
                                <strong>公式：</strong>PE分位 = count(历史PE均值 ≤ 当前PE均值) / 总天数 × 100%
                              </div>
                              <div style={{ marginTop: 6, color: '#aaa', fontSize: 11 }}>数据来源：stock_daily（ClickHouse）</div>
                            </div>
                          }
                          styles={{ root: {maxWidth: 400} }}
                        >
                          <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 13 }} />
                        </Tooltip>
                      </Space>
                    }
                    value={data?.pePercentile ?? 0}
                    suffix="%"
                    precision={1}
                    prefix={<FundOutlined />}
                    valueStyle={{ color: '#1677ff', fontSize: 24 }}
                  />
                  <Text type="secondary" style={{ fontSize: 11 }}>近3年历史分位</Text>
                </Card>
              </Col>
              <Col span={8}>
                <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                  <Statistic
                    title={
                      <Space size={4}>
                        <span>PB分位数</span>
                        <Tooltip
                          title={
                            <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 360 }}>
                              <div style={{ fontWeight: 600, marginBottom: 6 }}>📊 PB分位数 — 计算逻辑</div>
                              <div><strong>数据源：</strong>全市场A股（剔除 PB≤0 或 PB≥50 的异常值）</div>
                              <div><strong>当前值：</strong>当日全市场 PB 等权均值</div>
                              <div><strong>分位计算：</strong>过去 3 年每日 PB 均值组成的序列中，当前值排在第几位</div>
                              <div><strong>解读：</strong>0% = 历史最低估值，100% = 历史最高估值</div>
                              <div style={{ marginTop: 6, padding: '4px 8px', background: '#f5f5f5', borderRadius: 4 }}>
                                <strong>公式：</strong>PB分位 = count(历史PB均值 ≤ 当前PB均值) / 总天数 × 100%
                              </div>
                              <div style={{ marginTop: 6, color: '#aaa', fontSize: 11 }}>数据来源：stock_daily（ClickHouse）</div>
                            </div>
                          }
                          styles={{ root: {maxWidth: 400} }}
                        >
                          <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 13 }} />
                        </Tooltip>
                      </Space>
                    }
                    value={data?.pbPercentile ?? 0}
                    suffix="%"
                    precision={1}
                    prefix={<InsuranceOutlined />}
                    valueStyle={{ color: '#722ed1', fontSize: 24 }}
                  />
                  <Text type="secondary" style={{ fontSize: 11 }}>近3年历史分位</Text>
                </Card>
              </Col>
              <Col span={8}>
                <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                  <Statistic
                    title={
                      <Space size={4}>
                        <span>均线温度</span>
                        <Tooltip
                          title={
                            <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 360 }}>
                              <div style={{ fontWeight: 600, marginBottom: 6 }}>🌡️ 均线温度 — 计算逻辑</div>
                              <div><strong>数据源：</strong>沪深300指数日收盘价</div>
                              <div><strong>比较基准：</strong>MA20（20日均线） vs MA60（60日均线）</div>
                              <div><strong>温度计算：</strong>收盘价偏离 MA60 的幅度，归一化到 0-100</div>
                              <div><strong>趋势判定：</strong></div>
                              <div>&nbsp;&nbsp;• MA20 &gt; MA60 → 多头（温度 &gt; 50）</div>
                              <div>&nbsp;&nbsp;• MA20 &lt; MA60 → 空头（温度 &lt; 50）</div>
                              <div style={{ marginTop: 6, padding: '4px 8px', background: '#f5f5f5', borderRadius: 4 }}>
                                <strong>公式：</strong>均线温度 = (收盘价 - MA60) / MA60 × 50 + 50（归一化）<br/>
                                <strong>趋势：</strong>MA20 &gt; MA60 = 多头 | MA20 &lt; MA60 = 空头
                              </div>
                            </div>
                          }
                          styles={{ root: {maxWidth: 400} }}
                        >
                          <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 13 }} />
                        </Tooltip>
                      </Space>
                    }
                    value={data?.maTemperature ?? 50}
                    suffix="%"
                    precision={1}
                    prefix={<SwapOutlined />}
                    valueStyle={{ color: fgColor, fontSize: 24 }}
                  />
                  <Badge
                    status={data?.maTrend === '多头' ? 'success' : data?.maTrend === '空头' ? 'error' : 'warning'}
                    text={<Text type="secondary" style={{ fontSize: 11 }}>{data?.maTrend || '震荡'}</Text>}
                  />
                </Card>
              </Col>
            </Row>
            {/* 第二行：股债收益比 + QVIX 波动率指数 */}
            <Row gutter={[12, 12]} style={{ marginTop: 16 }}>
              <Col span={8}>
                <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                  <Statistic
                    title={
                      <Space size={4}>
                        <span>股债收益比</span>
                        <Tooltip
                          title={
                            <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 360 }}>
                              <div style={{ fontWeight: 600, marginBottom: 6 }}>📊 股债收益比 — 计算逻辑</div>
                              <div><strong>分子（股票）：</strong>沪深300盈利收益率 = 1 / PE_TTM（等权平均）</div>
                              <div><strong>分母（债券）：</strong>10年国债到期收益率（中债登发布）</div>
                              <div><strong>阈值信号：</strong></div>
                              <div>&nbsp;&nbsp;• 比值 &gt; 3.0 → 股票显著低估（贪婪信号，得分 90+）</div>
                              <div>&nbsp;&nbsp;• 比值 &lt; 1.5 → 股票显著高估（恐慌信号，得分 10）</div>
                              <div style={{ marginTop: 6, padding: '4px 8px', background: '#f5f5f5', borderRadius: 4 }}>
                                <strong>公式：</strong>股债收益比 = 沪深300盈利收益率 / 10年国债收益率<br/>
                                <strong>股债得分：</strong>10 + (比值 - 1.5) / 1.5 × 80（映射到 0-100）
                              </div>
                              <div style={{ marginTop: 6, color: '#aaa', fontSize: 11 }}>数据来源：沪深300指数 PE + 中债估值</div>
                            </div>
                          }
                          styles={{ root: {maxWidth: 400} }}
                        >
                          <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 13 }} />
                        </Tooltip>
                      </Space>
                    }
                    value={data?.stockBondRatio ?? 0}
                    precision={2}
                    prefix={<DollarOutlined />}
                    valueStyle={{ color: '#fa8c16', fontSize: 22 }}
                  />
                  <Text type="secondary" style={{ fontSize: 11 }}>10Y国债 {data?.bondYield10Y?.toFixed(2)}%</Text>
                </Card>
              </Col>
              <Col span={8}>
                <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                  <Statistic
                    title={
                      <Space size={4}>
                        <span>波动率指数</span>
                        <Tooltip
                          title={
                            <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 360 }}>
                              <div style={{ fontWeight: 600, marginBottom: 6 }}>📊 波动率指数（QVIX）— 计算逻辑</div>
                              <div><strong>数据源：</strong>中金所沪深300ETF期权（MO系列）隐含波动率</div>
                              <div><strong>获取方式：</strong>akshare index_option_300etf_qvix()</div>
                              <div><strong>当前值：</strong>今日 QVIX = {data?.qvix != null ? data.qvix.toFixed(2) : '-'}</div>
                              <div><strong>历史均值：</strong>{data?.qvixMean?.toFixed(2) ?? '19.73'}（中性分位）</div>
                              <div><strong>解读：</strong>QVIX &gt; 均值 → 市场预期未来波动加大（恐慌）</div>
                              <div style={{ marginTop: 6, padding: '4px 8px', background: '#f5f5f5', borderRadius: 4 }}>
                                <strong>公式：</strong>波动率得分 = (QVIX - 5%分位) / (95%分位 - 5%分位) × 100%<br/>
                                <strong>信号：</strong>QVIX &lt; 15 = 极度贪婪，QVIX &gt; 28 = 极度恐慌
                              </div>
                              <div style={{ marginTop: 6, color: '#aaa', fontSize: 11 }}>数据来源：中金所（akshare）</div>
                            </div>
                          }
                          styles={{ root: {maxWidth: 400} }}
                        >
                          <QuestionCircleOutlined style={{ color: '#999', cursor: 'pointer', fontSize: 13 }} />
                        </Tooltip>
                      </Space>
                    }
                    value={data?.qvixScore ?? 50}
                    precision={1}
                    prefix={<ThunderboltOutlined />}
                    valueStyle={{ color: '#f5222d', fontSize: 22 }}
                  />
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    QVIX {data?.qvix != null ? data.qvix.toFixed(2) : '-'} | 均值 {data?.qvixMean?.toFixed(2) ?? '19.73'}
                  </Text>
                </Card>
              </Col>
            </Row>
          </Col>
        </Row>
      </Card>

      {/* 主要指数行情 */}
      {(() => {
        const indices = data?.majorIndices;
        if (!indices || indices.length === 0) return null;
        return (
          <Card size="small" style={{ marginBottom: 16 }} styles={{ body: { padding: '12px 20px' } }}>
            <Row gutter={[16, 8]} align="middle">
              {indices.map(idx => {
                const pct = idx.changePct ?? 0;
                const color = pct > 0 ? '#cf1322' : pct < 0 ? '#3f8600' : '#999';
                const sign = pct > 0 ? '+' : '';
                return (
                  <Col key={idx.code} flex="1" style={{ minWidth: 100, textAlign: 'center' }}>
                    <div style={{ fontSize: 12, color: '#888', marginBottom: 2 }}>{idx.name}</div>
                    <div style={{ fontSize: 18, fontWeight: 600, color, lineHeight: 1.3 }}>
                      {idx.close?.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                    </div>
                    <div style={{ fontSize: 13, color, fontWeight: 500 }}>
                      {sign}{pct.toFixed(2)}%
                    </div>
                  </Col>
                );
              })}
            </Row>
          </Card>
        );
      })()}

      {/* 风格/大小盘 regime (P1-1) */}
      {(() => {
        const styleRegime = data?.styleRegime;
        const sizeRegime = data?.sizeRegime;
        if (!styleRegime && !sizeRegime) return null;

        const styleLabel = styleRegime === 'GROWTH' ? '成长占优' : styleRegime === 'VALUE' ? '价值占优' : '风格均衡';
        const styleColor = styleRegime === 'GROWTH' ? '#722ed1' : styleRegime === 'VALUE' ? '#1677ff' : '#999';
        const styleBg = styleRegime === 'GROWTH' ? '#f9f0ff' : styleRegime === 'VALUE' ? '#e6f4ff' : '#fafafa';

        const sizeLabel = sizeRegime === 'SMALL' ? '小盘强势' : sizeRegime === 'LARGE' ? '大盘强势' : '大小均衡';
        const sizeColor = sizeRegime === 'SMALL' ? '#fa8c16' : sizeRegime === 'LARGE' ? '#cf1322' : '#999';
        const sizeBg = sizeRegime === 'SMALL' ? '#fff7e6' : sizeRegime === 'LARGE' ? '#fff2f0' : '#fafafa';

        return (
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} md={12}>
              <Card size="small" style={{ background: styleBg, border: `1px solid ${styleColor}20` }}>
                <Space>
                  <TrophyOutlined style={{ fontSize: 20, color: styleColor }} />
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>风格判断</Text>
                    <div>
                      <Text strong style={{ fontSize: 18, color: styleColor }}>{styleLabel}</Text>
                      {data?.valueGrowthSpread != null && (
                        <Text type="secondary" style={{ fontSize: 11, marginLeft: 8 }}>
                          价成差 {data.valueGrowthSpread > 0 ? '+' : ''}{data.valueGrowthSpread}%
                        </Text>
                      )}
                    </div>
                  </div>
                  <Tooltip
                    title={
                      <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 280 }}>
                        <div style={{ fontWeight: 600, marginBottom: 4 }}>价值/成长风格</div>
                        <div>国证价值（399371）vs 国证成长（399370）近20日涨幅差：</div>
                        <div>{'>'}2% → 价值占优 | {'<'}-2% → 成长占优 | -2%~+2% → 风格均衡</div>
                        <div style={{ marginTop: 6, color: '#aaa', fontSize: 11 }}>
                          参考指标：因子选股中，成长占优时优先技术/资金因子，价值占优时优先基本面因子。
                        </div>
                      </div>
                    }
                  >
                    <QuestionCircleOutlined style={{ color: '#bbb', cursor: 'pointer', fontSize: 12 }} />
                  </Tooltip>
                </Space>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" style={{ background: sizeBg, border: `1px solid ${sizeColor}20` }}>
                <Space>
                  <BuildOutlined style={{ fontSize: 20, color: sizeColor }} />
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>大小盘风格</Text>
                    <div>
                      <Text strong style={{ fontSize: 18, color: sizeColor }}>{sizeLabel}</Text>
                      {data?.sizeSpread != null && (
                        <Text type="secondary" style={{ fontSize: 11, marginLeft: 8 }}>
                          大盘-小盘 {data.sizeSpread > 0 ? '+' : ''}{data.sizeSpread}%
                        </Text>
                      )}
                    </div>
                  </div>
                  <Tooltip
                    title={
                      <div style={{ fontSize: 12, lineHeight: 1.8, maxWidth: 280 }}>
                        <div style={{ fontWeight: 600, marginBottom: 4 }}>大小盘风格</div>
                        <div>沪深300（大盘）vs 中证1000（小盘）近20日涨幅差：</div>
                        <div>{'>'}2% → 大盘强势 | {'<'}-2% → 小盘强势 | -2%~+2% → 大小均衡</div>
                        <div style={{ marginTop: 6, color: '#aaa', fontSize: 11 }}>
                          参考指标：小盘强势时优先中小市值策略，大盘强势时优先大盘蓝筹策略。
                        </div>
                      </div>
                    }
                  >
                    <QuestionCircleOutlined style={{ color: '#bbb', cursor: 'pointer', fontSize: 12 }} />
                  </Tooltip>
                </Space>
              </Card>
            </Col>
          </Row>
        );
      })()}

      {/* 分项指标卡片 */}
      <Row gutter={[16, 16]}>
        {/* 融资余额变化 */}
        <Col xs={24} md={8}>
          <Card title={<><BankOutlined /> 主力资金净流入（近5日）</>} size="small">
            <div style={{ textAlign: 'center' }}>
              {(() => {
                const v = data?.marginChange ?? 0;
                // 优先用后端返回的 prevChange，兼容旧版本（从前10日历史推算）
                let prev = data?.prevChange;
                let histCount = 0;
                if (Array.isArray(data?.marginHistory) && data.marginHistory.length >= 10) {
                  const hist = data.marginHistory;
                  let prevSum = 0;
                  for (let i = 5; i < Math.min(10, hist.length); i++) {
                    prevSum += (hist[i]?.netMain || 0);
                  }
                  prev = Math.round(prevSum / 100000000 * 100) / 100;
                  histCount = Math.min(5, hist.length);
                }
                prev = prev ?? 0;
                const diff = Math.round((v - prev) * 100) / 100;
                const diffSign = diff >= 0 ? '+' : '';
                // 数据不完整提示
                const incomplete = histCount < 5;
                return (
                  <>
                    <div style={{
                      fontSize: 32, fontWeight: 700,
                      color: v > 0 ? '#52c41a' : v < 0 ? '#ff4d4f' : '#faad14',
                      lineHeight: 1.2
                    }}>
                      {v >= 0 ? '+' : ''}{v.toFixed(2)}<span style={{ fontSize: 16 }}>亿</span>
                    </div>
                    <Badge
                      style={{ marginTop: 12 }}
                      status={data?.marginTrend?.includes('流入') ? 'success' : data?.marginTrend?.includes('流出') ? 'error' : 'warning'}
                      text={<span style={{ fontSize: 13 }}>{data?.marginTrend || '平稳'}</span>}
                    />
                    <div style={{ marginTop: 8, fontSize: 12, color: '#999' }}>
                      vs 前5日 &nbsp;
                      <span style={{ color: '#aaa' }}>
                        {(prev >= 0 ? '+' : '') + prev.toFixed(2)}亿
                      </span>
                      &nbsp;
                      <span style={{ color: diff > 0 ? '#52c41a' : diff < 0 ? '#ff4d4f' : '#999' }}>
                        →&nbsp;{diffSign}{diff.toFixed(2)}亿
                      </span>
                      <span style={{ marginLeft: 4, fontStyle: 'italic', color: '#bbb' }}>
                        {diff > 0 ? '流出收窄' : diff < 0 ? '流出扩大' : ''}
                      </span>
                      {incomplete && (
                        <Tooltip title="数据来源不完整（东财接口近期受限），实际金额可能偏小，仅供参考">
                          <span style={{ marginLeft: 6, color: '#faad14', fontWeight: 600 }}>⚠</span>
                        </Tooltip>
                      )}
                    </div>
                  </>
                );
              })()}
            </div>
          </Card>
        </Col>

        {/* PE分位历史 */}
        <Col xs={24} md={16}>
          <Card title={<><FundOutlined /> PE分位趋势（近30交易日）</>} size="small">
            {peChartData.length > 0 ? (
              <ReactECharts option={peOption} style={{ height: 200 }} />
            ) : (
              <div style={{ height: 200, textAlign: 'center', lineHeight: '200px' }}>
                <Text type="secondary">暂无数据</Text>
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {/* 股债收益比趋势 */}
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24}>
          <Card title={<><DollarOutlined /> 股债收益比趋势</>} size="small">
            <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
              沪深300盈利收益率 ÷ 10年国债收益率 | 绿色虚线(3.0)=低估区间 | 红色虚线(1.5)=高估区间
            </Paragraph>
            {bondChartData.length > 0 ? (
              <ReactECharts option={bondOption} style={{ height: 180 }} />
            ) : (
              <div style={{ height: 180, textAlign: 'center', lineHeight: '180px' }}>
                <Text type="secondary">暂无数据</Text>
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {/* 解读说明 */}
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24}>
          <Card size="small" title="指标说明">
            <Row gutter={[24, 16]}>
              <Col xs={24} md={12}>
                <Title level={5} style={{ fontSize: 14 }}>PE/PB分位数</Title>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  全市场A股 PE/PB 等权均值，在近3年历史中的分位。{'>'}80% = 偏高估，{'<'}20% = 偏低估。
                </Text>
              </Col>
              <Col xs={24} md={12}>
                <Title level={5} style={{ fontSize: 14 }}>均线温度</Title>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  基于沪深300指数，MA20 {'>'} MA60 = 多头排列（{'>'}70分），MA20 {'<'} MA60 = 空头排列（{'<'}30分）。
                </Text>
              </Col>
              <Col xs={24} md={12}>
                <Title level={5} style={{ fontSize: 14 }}>股债收益比</Title>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  沪深300盈利收益率 ÷ 10年国债收益率。{'>'}3.0 表示股票相对债券显著低估（贪婪信号），{'<'}1.5 表示股票偏高估（恐慌信号）。
                </Text>
              </Col>
              <Col xs={24} md={12}>
                <Title level={5} style={{ fontSize: 14 }}>主力资金净流入</Title>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  近5日全市场主力净流入（亿元）。正值 = 主力买入（情绪偏贪婪），负值 = 主力卖出（情绪偏恐慌）。
                </Text>
              </Col>
            </Row>
            <Divider />
            <Paragraph type="secondary" style={{ fontSize: 12 }}>
              仅供参考，不构成投资建议。历史业绩不代表未来表现。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
