import React, { useEffect, useState, useRef } from 'react';
import { Row, Col, Card, Statistic, Typography, Spin, Tooltip, Badge, Divider, Space } from 'antd';
import {
  ControlOutlined, SwapOutlined,
  DollarOutlined, FundOutlined, BankOutlined, InsuranceOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import ReactEcharts from 'echarts-for-react';
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
                <div style={{ marginTop: 8, color: '#aaa' }}>仅供参考，不构成投资建议。</div>
              </div>
            }
            placement="bottomLeft"
            overlayStyle={{ maxWidth: 400 }}
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

      {/* 资金流向数据来源警告 */}
      <Card
        style={{
          marginBottom: 16,
          background: '#fffbe6',
          border: '1px solid #ffe58f',
        }}
        styles={{ body: { padding: '12px 16px' } }}
      >
        <Space>
          <span style={{ fontSize: 16 }}>⚠️</span>
          <Text style={{ fontSize: 13, color: '#ad6800' }}>
            <strong>资金流向数据说明：</strong>
            数据基于东财接口获取，2026-05-13 起全市场日度更新已暂停（接口级 IP 封锁，持续超过 60 分钟）。
            当前显示数据为封锁前已入库的历史数据，实际金额可能偏小，仅供参考。
          </Text>
        </Space>
      </Card>

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
              {fg.toFixed(0)}
            </div>
            <div style={{ fontSize: 20, color: FEAR_GREED_COLOR(fg), marginTop: 8 }}>
              {data?.fearGreedLabel}
            </div>
            <Text style={{ color: '#666', fontSize: 12 }}>
              恐慌贪婪指数（0极度恐慌 ~ 100极度贪婪）
            </Text>
          </Col>
          <Col xs={24} md={16}>
            <Row gutter={[12, 12]}>
              <Col span={12}>
                <Tooltip title="全市场PE历史分位（近3年）">
                  <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                    <Statistic
                      title="PE分位数"
                      value={data?.pePercentile ?? 0}
                      suffix="%"
                      precision={1}
                      prefix={<FundOutlined />}
                      valueStyle={{ color: '#1677ff', fontSize: 24 }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>近3年历史分位</Text>
                  </Card>
                </Tooltip>
              </Col>
              <Col span={12}>
                <Tooltip title="全市场PB历史分位（近3年）">
                  <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                    <Statistic
                      title="PB分位数"
                      value={data?.pbPercentile ?? 0}
                      suffix="%"
                      precision={1}
                      prefix={<InsuranceOutlined />}
                      valueStyle={{ color: '#722ed1', fontSize: 24 }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>近3年历史分位</Text>
                  </Card>
                </Tooltip>
              </Col>
              <Col span={12}>
                <Tooltip title="沪深300均线多空排列（20日 > 60日 = 多头）">
                  <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                    <Statistic
                      title="均线温度"
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
                </Tooltip>
              </Col>
              <Col span={12}>
                <Tooltip title="沪深300盈利收益率 / 10年国债收益率（>3偏低估，<1.5偏高估）">
                  <Card size="small" style={{ background: 'rgba(255,255,255,0.9)' }}>
                    <Statistic
                      title="股债收益比"
                      value={data?.stockBondRatio ?? 0}
                      precision={2}
                      prefix={<DollarOutlined />}
                      valueStyle={{ color: '#fa8c16', fontSize: 24 }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>10Y国债 {data?.bondYield10Y?.toFixed(2)}%</Text>
                  </Card>
                </Tooltip>
              </Col>
            </Row>
          </Col>
        </Row>
      </Card>

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
                      近5日 &nbsp;
                      <span style={{ color: diff > 0 ? '#52c41a' : diff < 0 ? '#ff4d4f' : '#999' }}>
                        {diffSign}{diff.toFixed(2)}亿
                      </span>
                      &nbsp;vs 前5日
                      <span style={{ marginLeft: 4, color: '#aaa' }}>
                        ({(prev >= 0 ? '+' : '') + prev.toFixed(2)}亿)
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
              <ReactEcharts option={peOption} style={{ height: 200 }} />
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
              <ReactEcharts option={bondOption} style={{ height: 180 }} />
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
              <strong>综合指数计算公式：</strong> PE分位×30% + PB分位×20% + 均线温度×30% + 股债得分×20%
              <br />
              主力资金数据来源：东财 stock_sentiment_moneyflow
              <br />
              仅供参考，不构成投资建议。历史业绩不代表未来表现。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
