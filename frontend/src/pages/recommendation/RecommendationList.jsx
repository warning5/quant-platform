import React, { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { Card, Table, Button, Tag, Select, Space, Statistic, Row, Col, Typography, Tooltip, Spin, message, Progress } from 'antd';
import { ThunderboltOutlined, ReloadOutlined, LineChartOutlined, StockOutlined, RiseOutlined, FallOutlined, MinusOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { recommendationApi } from '../../api';

const { Title, Text } = Typography;

// ── 市场环境配色 ──
const REGIME_CONFIG = {
  BULL:     { color: '#cf1322', bg: '#fff1f0', text: '牛市', icon: <RiseOutlined /> },
  BEAR:     { color: '#3f8600', bg: '#f6ffed', text: '熊市', icon: <FallOutlined /> },
  SIDEWAYS: { color: '#597ef7', bg: '#f0f5ff', text: '震荡', icon: <MinusOutlined /> },
  NEUTRAL:  { color: '#597ef7', bg: '#f0f5ff', text: '中性', icon: <MinusOutlined /> }, // 兼容旧数据
};

// ── 操作建议配色 ──
const ACTION_CONFIG = {
  BUY:  { color: 'red',    text: '买入' },
  HOLD: { color: 'blue',   text: '持有' },
  SELL: { color: 'green',  text: '卖出' },
};

// ── 市值格式化 ──
function formatMarketCap(val) {
  if (!val) return '-';
  if (val >= 1e12) return (val / 1e12).toFixed(1) + '万亿';
  if (val >= 1e8) return (val / 1e8).toFixed(1) + '亿';
  if (val >= 1e4) return (val / 1e4).toFixed(1) + '万';
  return val.toFixed(0);
}

export default function RecommendationList() {
  const [recommendations, setRecommendations] = useState([]);
  const [batchId, setBatchId] = useState(null);
  const [batchList, setBatchList] = useState([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [regime, setRegime] = useState(null);
  const [indexInfo, setIndexInfo] = useState(null);
  const [weightInfo, setWeightInfo] = useState(null); // Phase 2: 动态权重

  // 加载批次列表
  const loadBatches = useCallback(async () => {
    try {
      const batches = await recommendationApi.getBatches(30);
      setBatchList(Array.isArray(batches) ? batches : []);
    } catch { /* ignore */ }
  }, []);

  // 加载推荐数据
  const loadRecommendations = useCallback(async (bid) => {
    setLoading(true);
    try {
      let data;
      if (bid) {
        data = await recommendationApi.getByBatch(bid);
      } else {
        data = await recommendationApi.getLatest();
      }
      const list = Array.isArray(data) ? data : [];
      setRecommendations(list);
      if (list.length > 0) {
        setBatchId(list[0].batchId);
        setRegime(list[0].regime);
        setIndexInfo({
          close: list[0].indexClose,
          ma20: list[0].indexMa20,
          ma60: list[0].indexMa60,
        });
        setWeightInfo({
          factorWeight: list[0].factorWeight,
          analysisWeight: list[0].analysisWeight,
        });
      } else {
        setRegime(null);
        setIndexInfo(null);
      }
    } catch { /* ignore */ }
    setLoading(false);
  }, []);

  useEffect(() => {
    loadBatches();
    loadRecommendations(null);
  }, [loadBatches, loadRecommendations]);

  // 生成推荐
  const handleGenerate = async () => {
    setGenerating(true);
    try {
      const result = await recommendationApi.generate(null, 20);
      message.success(`推荐列表生成成功: ${result.count} 只`);
      await loadBatches();
      await loadRecommendations(null);
    } catch (e) {
      message.error('生成失败: ' + (e.message || '未知错误'));
    }
    setGenerating(false);
  };

  // 切换批次
  const handleBatchChange = (value) => {
    loadRecommendations(value);
  };

  const rc = regime ? REGIME_CONFIG[regime] : null;

  const columns = [
    {
      title: '#',
      dataIndex: 'rankNum',
      width: 45,
      fixed: 'left',
      render: (v) => <Text strong>{v}</Text>,
    },
    {
      title: '代码',
      dataIndex: 'stockCode',
      width: 80,
      fixed: 'left',
      render: (v) => <Link to={`/stock-analysis?code=${v}`}>{v}</Link>,
    },
    {
      title: '名称',
      dataIndex: 'stockName',
      width: 90,
      fixed: 'left',
      ellipsis: true,
    },
    {
      title: '综合得分',
      dataIndex: 'finalScore',
      width: 85,
      sorter: (a, b) => a.finalScore - b.finalScore,
      defaultSortOrder: 'descend',
      render: (v) => {
        const pct = v * 100;
        let color = '#597ef7';
        if (pct >= 80) color = '#cf1322';
        else if (pct >= 60) color = '#fa8c16';
        else if (pct < 30) color = '#8c8c8c';
        return (
          <div>
            <Text strong style={{ color }}>{(v * 100).toFixed(1)}</Text>
            <Progress percent={pct} showInfo={false} size="small" strokeColor={color} style={{ marginTop: 2 }} />
          </div>
        );
      },
    },
    {
      title: <Tooltip title="多因子选股百分位得分(0~100)，越高越好">因子得分</Tooltip>,
      dataIndex: 'factorScore',
      width: 75,
      render: (v) => v != null ? <Text type="secondary">{(v * 100).toFixed(1)}</Text> : '-',
    },
    {
      title: <Tooltip title="个股四维度综合得分（满分109）">分析得分</Tooltip>,
      dataIndex: 'analysisScore',
      width: 75,
      render: (v) => v != null ? <Text>{v}/109</Text> : '-',
    },
    {
      title: <Tooltip title="技术面得分（满分30）：缠论信号+MACD+RSI+均线">技术</Tooltip>,
      dataIndex: 'technicalScore',
      width: 55,
      render: (v) => v != null ? <Text type="secondary">{v}</Text> : '-',
    },
    {
      title: <Tooltip title="资金面得分（满分25）：主力净流入+换手率+资金流向">资金</Tooltip>,
      dataIndex: 'capitalScore',
      width: 55,
      render: (v) => v != null ? <Text type="secondary">{v}</Text> : '-',
    },
    {
      title: <Tooltip title="事件面得分（满分25）：涨停炸板率+龙虎榜+舆情">事件</Tooltip>,
      dataIndex: 'eventScore',
      width: 55,
      render: (v) => v != null ? <Text type="secondary">{v}</Text> : '-',
    },
    {
      title: <Tooltip title="基本面得分（满分29）：PE/PB估值+盈利质量+财务健康">基本面</Tooltip>,
      dataIndex: 'fundamentalScore',
      width: 60,
      render: (v) => v != null ? <Text type="secondary">{v}</Text> : '-',
    },
    {
      title: '建议',
      dataIndex: 'actionTag',
      width: 60,
      render: (v) => {
        const cfg = ACTION_CONFIG[v];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : '-';
      },
    },
    {
      title: '行业',
      dataIndex: 'industry',
      width: 80,
      ellipsis: true,
      render: (v) => v || '-',
    },
    {
      title: '市值',
      dataIndex: 'marketCap',
      width: 80,
      render: (v) => formatMarketCap(v),
    },
    {
      title: '现价',
      dataIndex: 'closePrice',
      width: 70,
      render: (v) => v != null ? v.toFixed(2) : '-',
    },
    {
      title: '买入理由',
      dataIndex: 'buyReason',
      width: 200,
      ellipsis: true,
      render: (v) => v ? <Tooltip title={v}><Text type="secondary" style={{ fontSize: 12 }}>{v}</Text></Tooltip> : '-',
    },
    // 追踪字段（Phase 2 填充）
    {
      title: <Tooltip title="推荐次日收盘相对推荐日收盘的涨跌幅">次日</Tooltip>,
      dataIndex: 'nextDayReturn',
      width: 65,
      render: (v, rec) => {
        if (v == null) {
          const isToday = rec.recommendDate === new Date().toISOString().slice(0, 10);
          return (
            <Tooltip title={isToday ? '推荐当日，需次日收盘后计算' : '数据积累中，稍后可追踪'}>
              <Text type="secondary" style={{ fontSize: 11 }}>{isToday ? '当日' : '待追踪'}</Text>
            </Tooltip>
          );
        }
        const color = v > 0 ? '#cf1322' : v < 0 ? '#3f8600' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}%</Text>;
      },
    },
    {
      title: <Tooltip title="推荐后第5个交易日收盘相对推荐日收盘的涨跌幅">一周</Tooltip>,
      dataIndex: 'nextWeekReturn',
      width: 65,
      render: (v) => {
        if (v == null) return (
          <Tooltip title="需至少5个交易日后才能计算">
            <Text type="secondary" style={{ fontSize: 11 }}>待追踪</Text>
          </Tooltip>
        );
        const color = v > 0 ? '#cf1322' : v < 0 ? '#3f8600' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}%</Text>;
      },
    },
    {
      title: <Tooltip title="推荐后第22个交易日收盘相对推荐日收盘的涨跌幅">一月</Tooltip>,
      dataIndex: 'nextMonthReturn',
      width: 65,
      render: (v) => {
        if (v == null) return (
          <Tooltip title="需至少22个交易日后才能计算">
            <Text type="secondary" style={{ fontSize: 11 }}>待追踪</Text>
          </Tooltip>
        );
        const color = v > 0 ? '#cf1322' : v < 0 ? '#3f8600' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{v > 0 ? '+' : ''}{v.toFixed(2)}%</Text>;
      },
    },
  ];

  return (
    <div style={{ padding: '0 0 24px' }}>
      {/* 头部 */}
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Title level={4} style={{ margin: 0 }}>
            <ThunderboltOutlined /> 智能推荐
            <Tooltip
              overlayStyle={{ maxWidth: 380 }}
              title={
                <div style={{ fontSize: 12, lineHeight: '20px' }}>
                  <div style={{ fontWeight: 'bold', marginBottom: 6, fontSize: 13 }}>推荐生成流程</div>
                  <div style={{ fontFamily: 'monospace', marginBottom: 8 }}>
                    全市场 ~5000只 A股<br />
                    &nbsp;→ <b>多因子筛选</b>（12因子综合排名，取Top 50）<br />
                    &nbsp;&nbsp;→ <b>个股深度分析</b>（四维度：技术/资金/事件/基本面）<br />
                    &nbsp;&nbsp;&nbsp;→ <b>Regime-Adaptive融合</b>（市场环境自适应权重）<br />
                    &nbsp;&nbsp;&nbsp;&nbsp;→ <b>行业分散化</b>（同行业≤3只）
                  </div>
                  <div style={{ fontWeight: 'bold', marginBottom: 4, fontSize: 13 }}>12个筛选因子</div>
                  <table style={{ borderCollapse: 'collapse', fontSize: 11 }}>
                    <tbody>
                      {[
                        ['动量', 'MOM20', '+', '20日涨幅'],
                        ['波动', 'VOL20', '-', '年化波动率（低波优先）'],
                        ['价值', 'VAL_PE_TTM', '-', '市盈率TTM'],
                        ['价值', 'VAL_PB', '-', '市净率'],
                        ['价值', 'VAL_DIVIDEND_YIELD', '+', '股息率'],
                        ['技术', 'RSI14', '+', '14日RSI'],
                        ['技术', 'MACD', '+', 'MACD离差值'],
                        ['流动性', 'TURN20', '-', '20日换手率（低换手优先）'],
                        ['财务', 'FIN_EARNINGS_QUALITY', '+', '盈利质量（经营现金流/净利润）'],
                        ['财务', 'FIN_DEBT_TO_ASSET', '-', '财务健康（资产负债率，越低越好）'],
                        ['财务', 'FIN_REVENUE_QUALITY', '+', '营收质量'],
                        ['成长', 'FIN_NET_PROFIT_YOY', '+', '净利润同比增长率'],
                      ].map(([cat, code, dir, desc]) => (
                        <tr key={code}>
                          <td style={{ padding: '1px 6px 1px 0', color: '#8c8c8c' }}>{cat}</td>
                          <td style={{ padding: '1px 6px', fontFamily: 'monospace', fontWeight: 500 }}>{code}</td>
                          <td style={{ padding: '1px 6px', color: dir === '+' ? '#cf1322' : '#3f8600' }}>{dir === '+' ? '正向' : '反向'}</td>
                          <td style={{ padding: '1px 0', color: '#595959' }}>{desc}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              }
            >
              <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 14, marginLeft: 6, cursor: 'help' }} />
            </Tooltip>
          </Title>
          {rc && (
            <Tag icon={rc.icon} color={rc.color} style={{ fontSize: 13 }}>
              {rc.text}
            </Tag>
          )}
        </Space>
        <Space>
          <Select
            value={batchId || ''}
            onChange={handleBatchChange}
            style={{ width: 160 }}
            placeholder="选择批次"
            options={batchList.map(b => ({ label: b, value: b }))}
          />
          <Button
            type="primary"
            icon={<ReloadOutlined spin={generating} />}
            loading={generating}
            onClick={handleGenerate}
          >
            生成推荐
          </Button>
        </Space>
      </div>

      {/* 市场环境概览 */}
      {regime && (
        <Row gutter={12} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Statistic
                title="市场环境"
                value={REGIME_CONFIG[regime]?.text || regime}
                valueStyle={{ color: REGIME_CONFIG[regime]?.color || '#597ef7', fontSize: 20 }}
                prefix={REGIME_CONFIG[regime]?.icon}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Statistic
                title="沪深300"
                value={indexInfo?.close?.toFixed(2) || '-'}
                prefix={<LineChartOutlined />}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Statistic
                title={<span>MA20 <Tooltip title="沪深300指数过去20个交易日的移动平均收盘价。指数收盘 > MA20 意味着中期趋势偏强，部分策略（如大盘择时）会以此判断是否适合做多。这里显示的数值为你参考趋势强弱用"><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                value={indexInfo?.ma20?.toFixed(2) || '-'}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Statistic
                title={<span>MA60 <Tooltip title="沪深300指数过去60个交易日的移动平均收盘价，代表长期趋势。指数收盘 > MA60 通常被视为中长期牛市信号。MA20 和 MA60 的相对位置（金叉/死叉）也是市场环境判断的重要参考"><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                value={indexInfo?.ma60?.toFixed(2) || '-'}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Tooltip
                overlayStyle={{ maxWidth: 300 }}
                title={weightInfo?.factorWeight != null
                  ? (
                    <div style={{ fontSize: 12, lineHeight: '18px' }}>
                      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>各市场环境权重分配</div>
                      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid #434343' }}>
                            <th style={{ padding: '2px 8px', textAlign: 'left' }}>环境</th>
                            <th style={{ padding: '2px 8px', textAlign: 'center' }}>因子</th>
                            <th style={{ padding: '2px 8px', textAlign: 'center' }}>分析</th>
                            <th style={{ padding: '2px 8px', textAlign: 'left' }}>策略</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr style={{ color: '#cf1322' }}>
                            <td style={{ padding: '2px 8px' }}>牛市</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>60%</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>40%</td>
                            <td style={{ padding: '2px 8px' }}>动量因子占优</td>
                          </tr>
                          <tr style={{ color: '#597ef7' }}>
                            <td style={{ padding: '2px 8px' }}>震荡</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>50%</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>50%</td>
                            <td style={{ padding: '2px 8px' }}>攻守均衡</td>
                          </tr>
                          <tr style={{ color: '#3f8600' }}>
                            <td style={{ padding: '2px 8px' }}>熊市</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>40%</td>
                            <td style={{ padding: '2px 8px', textAlign: 'center' }}>60%</td>
                            <td style={{ padding: '2px 8px' }}>偏防守反弹</td>
                          </tr>
                        </tbody>
                      </table>
                      <div style={{ marginTop: 6, color: '#8c8c8c' }}>
                        综合得分 = 因子得分 × {weightInfo.factorWeight != null ? (weightInfo.factorWeight * 100).toFixed(0) : '?'}% + 分析得分 × {weightInfo.analysisWeight != null ? (weightInfo.analysisWeight * 100).toFixed(0) : '?'}%
                      </div>
                    </div>
                  )
                  : '旧批次未记录权重信息，重新生成推荐后可显示'}>
                <Statistic
                  title={<span>因子权重 <QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></span>}
                  value={weightInfo?.factorWeight != null ? `${(weightInfo.factorWeight * 100).toFixed(0)}%` : '未设置'}
                  valueStyle={{ fontSize: 20 }}
                  suffix={weightInfo?.analysisWeight != null ? `/ ${(weightInfo.analysisWeight * 100).toFixed(0)}%分析` : ''}
                  titleStyle={{ fontSize: 12 }}
                />
              </Tooltip>
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Statistic
                title={<span>选股范围 <Tooltip title="从全市场约5000+只A股中，先用12个多因子（动量/波动/估值/技术/流动性/财务质量/成长）筛选出Top50候选，再对其中N只做深度四维度分析，最终经行业分散化后输出推荐结果"><QuestionCircleOutlined style={{ color: '#8c8c8c', fontSize: 12, marginLeft: 2 }} /></Tooltip></span>}
                value={recommendations.length}
                suffix="只"
                prefix={<ThunderboltOutlined />}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
        </Row>
      )}

      {/* 推荐列表 */}
      <Card size="small" bodyStyle={{ padding: 0 }}>
        <Spin spinning={loading}>
          <Table
            dataSource={recommendations}
            columns={columns}
            rowKey="id"
            size="small"
            scroll={{ x: 1400 }}
            pagination={false}
            locale={{ emptyText: recommendations.length === 0 && !loading
              ? '暂无推荐数据，点击「生成推荐」开始'
              : '加载中...'
            }}
          />
        </Spin>
      </Card>

      {/* 底部说明 */}
      <div style={{ marginTop: 12, display: 'flex', justifyContent: 'space-between' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          <StockOutlined /> 综合得分 = Regime-Adaptive 动态权重融合 | 牛市因子60%+分析40%，熊市因子40%+分析60%，震荡均衡50:50 | 12因子: 动量/波动/价值×3/技术×2/换手率/质量×3/成长
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {batchId && `批次: ${batchId} | ${recommendations.length} 只`}
        </Text>
      </div>
    </div>
  );
}
