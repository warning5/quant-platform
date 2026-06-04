import React, { useState, useEffect, useCallback } from 'react';
import { Card, Table, Button, Tag, Select, Space, Statistic, Row, Col, Typography, Tooltip, Spin, message, Progress } from 'antd';
import { ThunderboltOutlined, ReloadOutlined, LineChartOutlined, StockOutlined, RiseOutlined, FallOutlined, MinusOutlined } from '@ant-design/icons';
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
      render: (v) => <a href={`#/stock-analysis?code=${v}`} target="_blank" rel="noreferrer">{v}</a>,
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
                title="MA20"
                value={indexInfo?.ma20?.toFixed(2) || '-'}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Statistic
                title="MA60"
                value={indexInfo?.ma60?.toFixed(2) || '-'}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
              <Tooltip title={weightInfo?.factorWeight != null
                ? `Regime-Adaptive 动态权重：${REGIME_CONFIG[regime]?.text || regime} 环境下因子与分析得分的融合比例`
                : '旧批次未记录权重信息，重新生成推荐后可显示'}>
                <Statistic
                  title="因子权重"
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
                title="选股范围"
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
          <StockOutlined /> 综合得分 = Regime-Adaptive 动态权重融合 | 牛市因子60%+分析40%，熊市因子40%+分析60%，震荡均衡50:50 | 因子: MOM20/VOL20/PE_TTM/PB/股息率/RSI14/MACD
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {batchId && `批次: ${batchId} | ${recommendations.length} 只`}
        </Text>
      </div>
    </div>
  );
}
