import React, { useState, useCallback, useEffect } from 'react';
import {
  Card, Row, Col, Tabs, Input, Button, Spin, Empty, Tooltip, Tag, Progress,
  Typography, Alert, Statistic, Table, Descriptions,
} from 'antd';
import {
  QuestionCircleOutlined, SearchOutlined,
  ArrowUpOutlined, ArrowDownOutlined,
} from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { stockAnalysisApi } from '../../api';

const { Title, Text, Paragraph } = Typography;

// ── 辅助：操作建议对应的颜色 ─────────────────────────────────────────────
const actionColor = (action) => {
  if (!action) return 'default';
  if (action === '强烈买入') return 'red';
  if (action === '买入') return 'volcano';
  if (action === '持有') return 'blue';
  if (action === '减仓') return 'cyan';
  if (action === '清仓') return 'green';
  return 'default';
};

// ── 辅助：评分进度条颜色 ───────────────────────────────────────────────────
const scoreColor = (score, max) => {
  const pct = score / max;
  if (pct >= 0.8) return '#f5222d';
  if (pct >= 0.6) return '#fa8c16';
  if (pct >= 0.4) return '#1890ff';
  if (pct >= 0.2) return '#52c41a';
  return '#999';
};

// ── 辅助：指标值颜色（涨红跌绿） ────────────────────────────────────────────
const valueColor = (positive) => positive ? '#f5222d' : '#52c41a';

// ── 主页面 ──────────────────────────────────────────────────────────────────
export default function StockAnalysis() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [inputCode, setInputCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [overview, setOverview] = useState(null);
  const [error, setError] = useState(null);
  const [rulesVisible, setRulesVisible] = useState(false);
  const [rules, setRules] = useState(null);

  // 从 URL 读取初始股票代码
  const urlCode = searchParams.get('code') || '';

  // 页面加载时自动查询 URL 中的股票
  useEffect(() => {
    if (urlCode) {
      setInputCode(urlCode);
      doSearch(urlCode);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 搜索
  const doSearch = useCallback((c) => {
    const code = (c || inputCode).trim();
    if (!code) return;
    setSearchParams({ code });
    setLoading(true);
    setError(null);
    stockAnalysisApi.getOverview(code)
      .then(data => {
        setOverview(data);
      })
      .catch(e => {
        setError(e.message || '查询失败');
        setOverview(null);
      })
      .finally(() => setLoading(false));
  }, [inputCode, setSearchParams]);

  const handleSearch = () => doSearch();

  // 加载评分规则
  const showRules = useCallback(() => {
    if (rules) {
      setRulesVisible(true);
      return;
    }
    stockAnalysisApi.getScoreRules()
      .then(data => {
        setRules(data);
        setRulesVisible(true);
      })
      .catch(() => setRulesVisible(true));
  }, [rules]);

  // 解析涨跌幅数字
  const parseChangePct = (s) => {
    if (!s) return 0;
    const n = parseFloat(s.toString().replace('%', ''));
    return isNaN(n) ? 0 : n;
  };

  // Tab 项
  const tabItems = overview ? [
    {
      key: 'tech',
      label: '技术面',
      children: <TechTab data={overview.techSignal} />,
    },
    {
      key: 'money',
      label: '资金面',
      children: <MoneyFlowTab data={overview.moneySignal} />,
    },
    {
      key: 'sentiment',
      label: '事件面',
      children: <SentimentTab data={overview.sentimentSignal} />,
    },
    {
      key: 'fundamental',
      label: '基本面',
      children: <FundamentalTab data={overview.fundamentalSignal} />,
    },
  ] : [];

  const changePct = overview ? parseChangePct(overview.changePercent) : 0;

  return (
    <div style={{ width: '100%', padding: 0 }}>
      {/* ── 搜索栏 ────────────────────────────────────────────────────── */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row align="middle" gutter={12}>
          <Col>
            <Input
              placeholder="输入股票代码，如 000001"
              value={inputCode}
              onChange={e => setInputCode(e.target.value)}
              onPressEnter={handleSearch}
              style={{ width: 240 }}
            />
          </Col>
          <Col>
            <Button
              type="primary"
              icon={<SearchOutlined />}
              onClick={handleSearch}
              loading={loading}
            >
              查询
            </Button>
          </Col>
          <Col flex="auto" style={{ textAlign: 'right' }}>
            <Tooltip title="查看评分规则">
              <QuestionCircleOutlined
                style={{ fontSize: 16, cursor: 'pointer' }}
                onClick={showRules}
              />
            </Tooltip>
          </Col>
        </Row>
      </Card>

      {/* ── 加载/错误 ──────────────────────────────────────────────────── */}
      {loading && (
        <Card><div style={{ textAlign: 'center', padding: 40 }}><Spin size="large" /></div></Card>
      )}
      {error && (
        <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />
      )}

      {/* ── 总览卡片 ───────────────────────────────────────────────────── */}
      {overview && !loading && (
        <>
          <Card style={{ marginBottom: 16 }}>
            {/* 第一行：股票信息 + 综合评分 */}
            <Row gutter={24} align="middle" style={{ marginBottom: 16 }}>
              <Col>
                <Title level={4} style={{ margin: 0 }}>
                  {overview.name} ({overview.code})
                </Title>
              </Col>
              <Col>
                <Text
                  style={{
                    fontSize: 20,
                    color: changePct >= 0 ? '#f5222d' : '#52c41a',
                    fontWeight: 'bold',
                  }}
                >
                  {overview.price ? parseFloat(overview.price).toFixed(2) : '-'}&nbsp;
                  {changePct >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
                  {Math.abs(changePct).toFixed(2)}%
                </Text>
              </Col>
              <Col flex="auto" style={{ textAlign: 'right' }}>
                <Tooltip title={overview.risks || ''}>
                  <Tag
                    color={actionColor(overview.actionName)}
                    style={{ fontSize: 16, padding: '4px 16px' }}
                  >
                    {overview.actionName || '-'}
                  </Tag>
                </Tooltip>
              </Col>
            </Row>

            {/* 第二行：综合评分 + 仓位 + 操作时机（紧凑行内样式，无大框） */}
            <Row gutter={24} style={{ marginBottom: 16, textAlign: 'center' }}>
              <Col span={8}>
                <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>
                  综合评分
                  <Tooltip title="点击查看评分规则">
                    <QuestionCircleOutlined
                      style={{ marginLeft: 4, cursor: 'pointer' }}
                      onClick={showRules}
                    />
                  </Tooltip>
                </div>
                <div style={{ fontSize: 32, fontWeight: 500, color: scoreColor(overview.totalScore, 100) }}>
                  {overview.totalScore}<span style={{ fontSize: 16, color: '#999' }}> / 100</span>
                </div>
              </Col>
              <Col span={8}>
                <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>建议仓位</div>
                <div style={{ fontSize: 32, fontWeight: 500, color: '#333' }}>
                  {overview.position}<span style={{ fontSize: 16, color: '#999' }}>%</span>
                </div>
              </Col>
              <Col span={8}>
                <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>操作时机</div>
                <Tag
                  color={actionColor(overview.actionName)}
                  style={{ fontSize: 16, padding: '4px 16px' }}
                >
                  {overview.timing || '-'}
                </Tag>
              </Col>
            </Row>

            {/* 第三行：维度评分条 */}
            {overview.scoreDetails && overview.scoreDetails.length > 0 && (
              <Row gutter={16}>
                {overview.scoreDetails.map((detail, idx) => (
                  <Col span={6} key={detail.dimension || idx}>
                    <Tooltip title={
                      <div style={{ fontSize: 12 }}>
                        {detail.items?.map((item, i) => (
                          <div key={i}>{item.label}: {item.value} ({item.score}/{item.maxScore})</div>
                        ))}
                      </div>
                    }>
                      <div>
                        <Text style={{ fontSize: 12 }}>{detail.dimensionName}：{detail.score}/{detail.maxScore}</Text>
                        <Progress
                          percent={Math.round(detail.score / detail.maxScore * 100)}
                          strokeColor={scoreColor(detail.score, detail.maxScore)}
                          showInfo={false}
                          size="small"
                          style={{ marginBottom: 4 }}
                        />
                      </div>
                    </Tooltip>
                  </Col>
                ))}
              </Row>
            )}

            {/* 风险提示 - 紧凑行内样式 */}
            {overview.risks && (
              <div style={{ marginTop: 12, padding: '8px 12px', background: '#fffbe6', borderRadius: 4, fontSize: 13, color: '#d48806' }}>
                <span style={{ fontWeight: 500 }}>风险提示：</span>{overview.risks}
              </div>
            )}
          </Card>

          {/* ── 分析结论 - 紧凑行内样式 ───────────────────────────────── */}
          {overview.conclusion && (
            <div style={{ marginBottom: 16, padding: '10px 12px', background: '#e6f7ff', borderRadius: 4, fontSize: 13, color: '#096dd9' }}>
              <span style={{ fontWeight: 500 }}>分析结论：</span>{overview.conclusion}
            </div>
          )}

          {/* ── 四维度 Tab ─────────────────────────────────────────────── */}
          <Card>
            <Tabs items={tabItems} />
          </Card>
        </>
      )}

      {/* ── 空状态 ────────────────────────────────────────────────────── */}
      {!overview && !loading && (
        <Card>
          <Empty description="请输入股票代码，点击查询开始分析" />
        </Card>
      )}

      {/* ── 评分规则 Panel ────────────────────────────────────────────── */}
      {rulesVisible && (
        <Card
          title="评分规则说明"
          extra={<Button type="text" onClick={() => setRulesVisible(false)}>关闭</Button>}
          style={{ position: 'fixed', top: 100, right: 50, width: 500, zIndex: 1000, boxShadow: '0 4px 12px rgba(0,0,0,0.15)' }}
        >
          {rules?.map((rule, idx) => (
            <div key={idx} style={{ marginBottom: 12 }}>
              <Text strong>{rule.dimension}（满分{rule.maxScore}）：</Text>
              <div style={{ fontSize: 13, whiteSpace: 'pre-wrap', marginLeft: 8 }}>
                {rule.rule}
              </div>
            </div>
          ))}
        </Card>
      )}
    </div>
  );
}

// ── 通用：指标列表行 ──────────────────────────────────────────────────────
// 每行：指标名 | 值(Tag) | 评分 | 说明
function IndicatorRow({ label, value, score, maxScore, desc, color }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', padding: '6px 0',
      borderBottom: '1px solid #f0f0f0', gap: 12,
    }}>
      <span style={{ width: 100, flexShrink: 0, color: '#333', fontWeight: 500 }}>{label}</span>
      <span style={{ width: 80, flexShrink: 0, textAlign: 'center' }}>
        <Tag color={color || 'default'} style={{ margin: 0, fontSize: 14, padding: '2px 10px' }}>
          {value ?? '-'}
        </Tag>
      </span>
      <span style={{ width: 60, flexShrink: 0, textAlign: 'center', fontSize: 13, color: '#666' }}>
        {score !== undefined ? `${score}/${maxScore}` : ''}
      </span>
      <span style={{ flex: 1, fontSize: 12, color: '#999' }}>{desc || ''}</span>
    </div>
  );
}

// ── 技术面 Tab ─────────────────────────────────────────────────────────────
function TechTab({ data }) {
  if (!data) return <Empty description="暂无技术面数据" />;

  const getChanSignalText = (sig) => {
    if (sig === 'BUY') return '买入';
    if (sig === 'SELL') return '卖出';
    return '持有';
  };
  const getChanSignalColor = (sig) => {
    if (sig === 'BUY') return 'red';
    if (sig === 'SELL') return 'green';
    return 'default';
  };

  const getTrendText = (trend) => {
    if (trend === 'BULLISH') return '上涨';
    if (trend === 'BEARISH') return '下跌';
    if (trend === 'SIDEWAYS') return '盘整';
    return '-';
  };
  const getTrendColor = (trend) => {
    if (trend === 'BULLISH') return 'red';
    if (trend === 'BEARISH') return 'green';
    return 'blue';
  };

  const getPenDirText = (dir) => {
    if (dir === '1' || dir === 1 || dir === 'UP') return '向上';
    if (dir === '-1' || dir === -1 || dir === 'DOWN') return '向下';
    return '-';
  };
  const isPenUp = (dir) => dir === '1' || dir === 1 || dir === 'UP';

  const rsiVal = data.rsi ?? 0;
  const rsiColor = rsiVal > 70 ? 'red' : rsiVal < 30 ? 'green' : 'blue';

  return (
    <div>
      {/* 表头 */}
      <div style={{
        display: 'flex', padding: '4px 0', gap: 12,
        borderBottom: '2px solid #e8e8e8', fontWeight: 600, fontSize: 12, color: '#999',
      }}>
        <span style={{ width: 100, flexShrink: 0 }}>指标</span>
        <span style={{ width: 80, flexShrink: 0, textAlign: 'center' }}>当前值</span>
        <span style={{ width: 60, flexShrink: 0, textAlign: 'center' }}>评分</span>
        <span style={{ flex: 1 }}>说明</span>
      </div>
      <IndicatorRow
        label="缠论信号" value={getChanSignalText(data.chanSignal)} color={getChanSignalColor(data.chanSignal)}
        score={data.chanSignal === 'BUY' ? 12 : data.chanSignal === 'SELL' ? -5 : 0} maxScore={12}
        desc="缠论买卖点信号。BUY=买点(12分), SELL=卖点(-5分), HOLD=持有(0分)"
      />
      <IndicatorRow
        label="趋势状态" value={getTrendText(data.trend)} color={getTrendColor(data.trend)}
        score={data.trend === 'BULLISH' ? 8 : data.trend === 'SIDEWAYS' ? 4 : 0} maxScore={8}
        desc="走势类型判断。BULLISH=上涨(8分), SIDEWAYS=盘整(4分), BEARISH=下跌(0分)"
      />
      <IndicatorRow
        label="笔方向" value={getPenDirText(data.penDir)} color={isPenUp(data.penDir) ? 'red' : 'green'}
        desc="当前笔的方向。向上笔=多方主导，向下笔=空方主导"
      />
      <IndicatorRow
        label="笔数" value={data.penCount ?? '-'} color='default'
        desc="近期笔的数量。笔数少=走势简洁趋势明确，笔数多=震荡频繁"
      />
      <IndicatorRow
        label="均线多头" value={data.maBullish ? '是' : '否'} color={data.maBullish ? 'red' : 'default'}
        score={data.maBullish ? 5 : 0} maxScore={5}
        desc="MA5>MA10>MA20>MA60 呈多头排列=5分，表示中短期趋势向上"
      />
      <IndicatorRow
        label="MACD金叉" value={data.macdGolden ? '是' : '否'} color={data.macdGolden ? 'red' : 'default'}
        score={data.macdGolden ? 5 : 0} maxScore={5}
        desc="DIF从下方穿越DEA=5分，是经典的短期看多信号"
      />
      <IndicatorRow
        label="RSI14" value={data.rsi?.toFixed(1) ?? '-'} color={rsiColor}
        desc="14日相对强弱指标。>70超买(红色)，<30超卖(绿色)，30~70正常(蓝色)"
      />
      {/* 总分 */}
      <div style={{ marginTop: 12, padding: '8px 0', borderTop: '2px solid #e8e8e8' }}>
        <Text strong style={{ fontSize: 14 }}>
          技术面得分：{data.techScore ?? '-'}/30
        </Text>
      </div>
    </div>
  );
}

// ── 资金面 Tab ─────────────────────────────────────────────────────────────
function MoneyFlowTab({ data }) {
  if (!data) return <Empty description="暂无资金面数据" />;

  const vrVal = data.volumeRatio ?? 0;
  const vrColor = vrVal >= 2.0 ? 'red' : vrVal >= 1.5 ? 'volcano' : 'green';
  const vrScore = vrVal >= 2.0 ? 12 : vrVal >= 1.5 ? 8 : vrVal >= 1.0 ? 4 : 0;

  const tdVal = data.turnoverDeviation ?? 0;
  const tdColor = tdVal > 3.0 ? 'red' : tdVal > 0 ? 'volcano' : 'green';
  const tdScore = tdVal > 0 ? 13 : tdVal > -2.0 ? 8 : 5;

  return (
    <div>
      <div style={{
        display: 'flex', padding: '4px 0', gap: 12,
        borderBottom: '2px solid #e8e8e8', fontWeight: 600, fontSize: 12, color: '#999',
      }}>
        <span style={{ width: 100, flexShrink: 0 }}>指标</span>
        <span style={{ width: 80, flexShrink: 0, textAlign: 'center' }}>当前值</span>
        <span style={{ width: 60, flexShrink: 0, textAlign: 'center' }}>评分</span>
        <span style={{ flex: 1 }}>说明</span>
      </div>
      <IndicatorRow
        label="量比" value={data.volumeRatio?.toFixed(2) ?? '-'} color={vrColor}
        score={vrScore} maxScore={12}
        desc="今日成交额/近5日均值。>=2.0放量(12分)，>=1.5温和(8分)，>=1.0正常(4分)"
      />
      <IndicatorRow
        label="换手率偏离" value={data.turnoverDeviation ? `${data.turnoverDeviation.toFixed(2)}%` : '-'} color={tdColor}
        score={tdScore} maxScore={13}
        desc="今日换手率与20日均值的偏离。>0%活跃提升(13分)，>-2%正常(8分)，<=-2%低迷(5分)"
      />
      <IndicatorRow
        label="当日换手率" value={data.turnoverRate ? `${data.turnoverRate.toFixed(2)}%` : '-'} color='default'
        desc="当日成交量/流通股本。高换手=交投活跃，低换手=交易清淡"
      />
      <IndicatorRow
        label="量能状态" value={
          data.volumeStatus === 'HIGH' ? '放量' :
          data.volumeStatus === 'MEDIUM' ? '温和放量' :
          data.volumeStatus === 'LOW' ? '缩量' : '-'
        } color={
          data.volumeStatus === 'HIGH' ? 'red' :
          data.volumeStatus === 'MEDIUM' ? 'volcano' : 'green'
        }
        desc="综合量比和换手率的量能判断。放量=资金积极介入，缩量=观望情绪浓厚"
      />
      <div style={{ marginTop: 12, padding: '8px 0', borderTop: '2px solid #e8e8e8' }}>
        <Text strong style={{ fontSize: 14 }}>
          资金面得分：{data.moneyScore ?? '-'}/25
        </Text>
      </div>
    </div>
  );
}

// ── 事件面 Tab ─────────────────────────────────────────────────────────────
function SentimentTab({ data }) {
  if (!data) return <Empty description="暂无事件面数据" />;

  const luDays = data.limitUpDays ?? 0;
  const brRate = data.brokenLimitUpRate ?? 0;

  return (
    <div>
      <div style={{
        display: 'flex', padding: '4px 0', gap: 12,
        borderBottom: '2px solid #e8e8e8', fontWeight: 600, fontSize: 12, color: '#999',
      }}>
        <span style={{ width: 100, flexShrink: 0 }}>指标</span>
        <span style={{ width: 80, flexShrink: 0, textAlign: 'center' }}>当前值</span>
        <span style={{ width: 60, flexShrink: 0, textAlign: 'center' }}>评分</span>
        <span style={{ flex: 1 }}>说明</span>
      </div>
      <IndicatorRow
        label="连续涨停" value={`${luDays}天`} color={luDays >= 2 ? 'red' : luDays >= 1 ? 'volcano' : 'default'}
        score={luDays >= 2 ? 10 : luDays >= 1 ? 5 : 0} maxScore={10}
        desc="连续涨停天数。2天及以上=强势(10分)，1天=较强(5分)"
      />
      <IndicatorRow
        label="炸板率" value={data.brokenLimitUpRate?.toFixed(1) ? `${data.brokenLimitUpRate.toFixed(1)}%` : '-'}
        color={brRate < 10 ? 'green' : brRate > 30 ? 'red' : 'volcano'}
        score={brRate < 10 ? 8 : 0} maxScore={8}
        desc="20日内炸板次数/涨停次数。<10%=封板强(8分)，>30%=封板弱"
      />
      <IndicatorRow
        label="强势股" value={data.isStrongStock ? '是' : '否'} color={data.isStrongStock ? 'red' : 'default'}
        score={data.isStrongStock ? 7 : 0} maxScore={7}
        desc="20日涨幅>30%=强势(7分)。强势股惯性上涨概率更高"
      />
      <div style={{ marginTop: 12, padding: '8px 0', borderTop: '2px solid #e8e8e8' }}>
        <Text strong style={{ fontSize: 14 }}>
          事件面得分：{data.sentimentScore ?? '-'}/25
        </Text>
      </div>
    </div>
  );
}

// ── 基本面 Tab ─────────────────────────────────────────────────────────────
function FundamentalTab({ data }) {
  if (!data) return <Empty description="暂无基本面数据" />;

  const peVal = data.peTtm ?? 0;
  const roeVal = data.roe ?? 0;
  const pbVal = data.pb ?? 0;
  const revVal = data.revenueYoy ?? 0;
  const npVal = data.netProfitYoy ?? 0;
  const gmVal = data.grossMargin ?? 0;

  // 评分逻辑（与后端 TradingSignalEngine.calcFundamentalScore 一致）
  const peScore = peVal > 0 && peVal < 15 ? 3 : peVal < 40 ? 2 : peVal < 100 ? 1 : 0;
  const peMax = 3;
  const roeScore = roeVal > 10 ? 4 : roeVal > 5 ? 2 : 0;
  const roeMax = 4;
  const pbScore = pbVal > 0 && pbVal < 3 ? 3 : pbVal < 5 ? 2 : 0;
  const pbMax = 3;
  const revScore = revVal > 20 ? 3 : revVal > 10 ? 2 : revVal > 0 ? 1 : 0;
  const revMax = 3;
  const npScore = npVal > 20 ? 4 : npVal > 10 ? 3 : npVal > 0 ? 2 : 0;
  const npMax = 4;
  const gmScore = gmVal >= 40 ? 3 : gmVal >= 20 ? 2 : gmVal > 0 ? 1 : 0;
  const gmMax = 3;

  return (
    <div>
      <div style={{
        display: 'flex', padding: '4px 0', gap: 12,
        borderBottom: '2px solid #e8e8e8', fontWeight: 600, fontSize: 12, color: '#999',
      }}>
        <span style={{ width: 100, flexShrink: 0 }}>指标</span>
        <span style={{ width: 80, flexShrink: 0, textAlign: 'center' }}>当前值</span>
        <span style={{ width: 60, flexShrink: 0, textAlign: 'center' }}>评分</span>
        <span style={{ flex: 1 }}>说明</span>
      </div>
      <IndicatorRow
        label="PE(TTM)" value={data.peTtm?.toFixed(2) ?? '-'}
        color={peVal > 0 && peVal < 15 ? 'green' : peVal >= 40 ? 'red' : 'default'}
        score={peScore} maxScore={peMax}
        desc="滚动市盈率。<15低估(3分), 15~40合理(2分), 40~100偏高(1分), >100极高(0分)"
      />
      <IndicatorRow
        label="PB" value={data.pb?.toFixed(2) ?? '-'}
        color={pbVal > 0 && pbVal < 3 ? 'green' : pbVal >= 5 ? 'red' : 'default'}
        score={pbScore} maxScore={pbMax}
        desc="市净率。<3低风险(3分), 3~5适中(2分), >5偏高(0分)"
      />
      <IndicatorRow
        label="ROE" value={data.roe ? `${data.roe.toFixed(2)}%` : '-'}
        color={roeVal > 10 ? 'green' : 'default'}
        score={roeScore} maxScore={roeMax}
        desc="净资产收益率。>10%(4分), >5%(2分), 其余(0分)"
      />
      <IndicatorRow
        label="营收增速" value={data.revenueYoy ? `${data.revenueYoy.toFixed(2)}%` : '-'}
        color={revVal > 0 ? 'red' : 'green'}
        score={revScore} maxScore={revMax}
        desc="营收同比增速。>20%(3分), >10%(2分), >0%(1分), 负值(0分)"
      />
      <IndicatorRow
        label="净利增速" value={data.netProfitYoy ? `${data.netProfitYoy.toFixed(2)}%` : '-'}
        color={npVal > 0 ? 'red' : 'green'}
        score={npScore} maxScore={npMax}
        desc="归母净利润同比增速。>20%(4分), >10%(3分), >0%(2分), 负值(0分)"
      />
      <IndicatorRow
        label="毛利率" value={data.grossMargin ? `${data.grossMargin.toFixed(2)}%` : '-'}
        color={gmVal >= 40 ? 'green' : gmVal >= 20 ? 'default' : 'default'}
        score={gmScore} maxScore={gmMax}
        desc="毛利率=(营收-成本)/营收。≥40%(3分), ≥20%(2分), >0%(1分)"
      />
      <div style={{ marginTop: 12, padding: '8px 0', borderTop: '2px solid #e8e8e8' }}>
        <Text strong style={{ fontSize: 14 }}>
          基本面得分：{data.fundamentalScore ?? '-'}/20
        </Text>
      </div>
    </div>
  );
}
