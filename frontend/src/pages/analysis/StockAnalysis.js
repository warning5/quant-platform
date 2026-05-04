import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  Card, Row, Col, Tabs, Input, AutoComplete, Button, Spin, Empty, Tooltip, Tag, Progress,
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
  const [researchData, setResearchData] = useState(null);
  const [error, setError] = useState(null);
  const [rulesVisible, setRulesVisible] = useState(false);
  const [rules, setRules] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const searchTimerRef = useRef(null);

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

  // 联想搜索（防抖 300ms）
  const handleSearchInput = useCallback((value) => {
    setInputCode(value);
    if (!value || value.trim().length < 1) {
      setSuggestions([]);
      return;
    }
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => {
      setSearchLoading(true);
      stockAnalysisApi.searchStocks(value.trim())
        .then(data => {
          const opts = (data || []).map(item => ({
            value: item.code,
            label: `${item.code} - ${item.name || ''}`,
          }));
          setSuggestions(opts);
        })
        .catch(() => setSuggestions([]))
        .finally(() => setSearchLoading(false));
    }, 300);
  }, []);

  // 选中联想项
  const handleAutoCompleteSelect = (value) => {
    setInputCode(value);
    doSearch(value);
  };

  const handleSearch = () => doSearch();

  // 加载研报分析数据
  useEffect(() => {
    if (!overview?.code) {
      setResearchData(null);
      return;
    }
    stockAnalysisApi.getResearchReport(overview.code)
      .then(data => setResearchData(data))
      .catch(() => setResearchData(null));
  }, [overview?.code]);

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
    {
      key: 'research',
      label: '研报分析',
      children: <ResearchReportTab data={researchData} code={overview.code} />,
    },
  ] : [];

  const changePct = overview ? parseChangePct(overview.changePercent) : 0;

  return (
    <div style={{ width: '100%', padding: 0 }}>
      {/* ── 搜索栏 ────────────────────────────────────────────────────── */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row align="middle" gutter={12}>
          <Col>
            <AutoComplete
              placeholder="输入股票代码或名称，如 000001 或 平安银行"
              value={inputCode}
              options={suggestions}
              onSearch={handleSearchInput}
              onSelect={handleAutoCompleteSelect}
              onPressEnter={() => doSearch(inputCode)}
              style={{ width: 300 }}
              notFoundContent={searchLoading ? <Spin size="small" /> : '未找到匹配股票'}
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

// ── 研报分析 Tab ─────────────────────────────────────────────────────
function ResearchReportTab({ data, code }) {
  if (!data) return <Empty description="暂无研报分析数据" />;

  // epsForecast 可能是对象(dict)也可能为空，统一转数组按年份排序
  const epsRaw = data.epsForecast;
  const epsList = Array.isArray(epsRaw)
    ? epsRaw
    : (epsRaw ? Object.values(epsRaw) : []);

  // 发布频率柱状图数据
  const reportTrend = (data.reportTrend || []).map(t => ({
    month: t.month || '',
    cnt: t.cnt || t.count || 0,
  }));
  const maxCnt = reportTrend.length > 0 ? Math.max(...reportTrend.map(t => t.cnt)) : 0;

  // 评级摘要 & 覆盖数据
  const rs = data.ratingSummary || {};
  const cov = data.coverage || {};
  const ratingTrendData = data.ratingTrend || [];
  // 覆盖机构数
  const instCount = cov.institutionCount || (cov.institutions?.length || 0);
  // 研报总数
  const reportCount6m = data.reportCount6m || cov.reportCount6m || 0;

  return (
    <div>
      {/* ── 第一行：三栏 ──────────────────────────────────────────── */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        {/* 左：评级共识 */}
        <Col span={8}>
          <Card size="small" title="评级共识" bodyStyle={{ padding: '12px 16px' }}>
            <Tag color="blue" style={{ fontSize: 14, padding: '2px 12px' }}>{rs.latestRating || '-'}</Tag>
            <Text type="secondary" style={{ marginLeft: 8 }}>{rs.consensusDesc}</Text>
            <div style={{ marginTop: 12, marginBottom: 4, fontSize: 13 }}>买入+增持占比</div>
            <Progress percent={rs.buyRatio ?? 0} strokeColor="#f5222d" showInfo format={p => `${p}%`} />
          </Card>
        </Col>

        {/* 中：覆盖强度 */}
        <Col span={7}>
          <Card size="small" title="覆盖强度" bodyStyle={{ padding: '12px 16px' }}>
            <Row gutter={[16, 8]}>
              <Col span={8}><Text type="secondary">覆盖机构</Text></Col>
              <Col span={8}><Text type="secondary">研报总数</Text></Col>
              <Col span={8}><Text type="secondary">首次覆盖</Text></Col>
              <Col span={8} style={{ fontWeight: 600, fontSize: 18 }}>{instCount}<span style={{ fontSize: 13, marginLeft: 2 }}>家</span></Col>
              <Col span={8} style={{ fontWeight: 600, fontSize: 18 }}>{reportCount6m}<span style={{ fontSize: 13, marginLeft: 2 }}>篇</span></Col>
              <Col span={8} style={{ fontWeight: 500, fontSize: 15 }}>{cov.firstCoverageDate || '-'}</Col>
            </Row>
          </Card>
        </Col>

        {/* 右：EPS 一致预期 */}
        <Col span={9}>
          <Card size="small" title="EPS 一致预期" bodyStyle={{ padding: '12px 16px' }}>
            <Row gutter={12}>
              {epsList.map((ep, i) => (
                <Col span={8} key={ep.year || i} style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>{ep.year} 年预测</div>
                  <div style={{ fontWeight: 600, fontSize: 17, color: '#333' }}>
                    ¥{ep.avgEps != null ? Number(ep.avgEps).toFixed(2) : '-'}
                  </div>
                  <div style={{ fontSize: 11, color: '#999' }}>
                    PE: {ep.avgPe != null ? Number(ep.avgPe).toFixed(1) + 'x' : '-'}
                  </div>
                </Col>
              ))}
            </Row>
          </Card>
        </Col>
      </Row>

      {/* ── 第二行：两栏 ─────────────────────────────────────────── */}
      <Row gutter={16}>
        {/* 左：评级趋势表格 */}
        <Col span={12}>
          <Card size="small" title="评级趋势（近6个月）">
            {ratingTrendData.length > 0 ? (
              <Table
                size="small"
                pagination={false}
                columns={[
                  { title: '月份', dataIndex: 'month', width: 90 },
                  {
                    title: '买入', dataIndex: '买入',
                    render: v => v != null && v > 0 ? <Tag color="red">{v}</Tag> : <span>-</span>,
                    align: 'center',
                  },
                  {
                    title: '增持', dataIndex: '增持',
                    render: v => v != null && v > 0 ? <Tag color="blue">{v}</Tag> : <span>-</span>,
                    align: 'center',
                  },
                  {
                    title: '中性/持有', dataIndex: '持有',
                    render: v => v != null && v > 0 ? v : <span>-</span>,
                    align: 'center',
                  },
                ]}
                dataSource={ratingTrendData}
                rowKey="month"
              />
            ) : (
              <Empty description="暂无评级趋势数据" />
            )}
          </Card>
        </Col>

        {/* 右：发布频率柱状图 */}
        <Col span={12}>
          <Card size="small" title="发布频率（近6个月）">
            {reportTrend.length > 0 ? (
              <div style={{ display: 'flex', alignItems: 'flex-end', height: 140, paddingBottom: 12, borderBottom: '1px solid #f0f0f0' }}>
                {reportTrend.map((t, i) => (
                  <div key={i} style={{ flex: 1, textAlign: 'center', height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                    <div style={{
                      background: '#1890ff',
                      margin: '0 4px',
                      height: maxCnt > 0 ? `${Math.max(t.cnt, 1) / maxCnt * 100}%` : '2px',
                      borderRadius: '2px 2px 0 0',
                      minHeight: 2,
                    }} />
                    <div style={{ fontSize: 10, color: '#999', marginTop: 4 }}>{t.month}</div>
                  </div>
                ))}
              </div>
            ) : (
              <Empty description="暂无发布频率数据" />
            )}
          </Card>
        </Col>
      </Row>

      {/* ── 第三行：机构列表 + 研报列表 ──────────────────────────── */}
      <Row gutter={16} style={{ marginTop: 16 }}>
        {/* 左：涉及机构 */}
        <Col span={8}>
          <Card size="small" title={`涉及机构（${instCount} 家）`} bodyStyle={{ padding: 0, maxHeight: 420, overflowY: 'auto' }}>
            {(cov.institutions || []).length > 0 ? (
              <Table
                size="small"
                pagination={false}
                dataSource={cov.institutions}
                rowKey="institution"
                columns={[
                  { title: '机构名称', dataIndex: 'institution', ellipsis: true },
                  {
                    title: '研报数', dataIndex: 'report_count',
                    width: 60, align: 'center',
                    render: v => v != null ? v : '-',
                  },
                  { title: '首次覆盖', dataIndex: 'first_date', width: 100, align: 'center' },
                ]}
              />
            ) : (
              <Empty description="暂无机构数据" style={{ margin: 24 }} />
            )}
          </Card>
        </Col>

        {/* 右：具体研报列表 */}
        <Col span={16}>
          <Card size="small" title={`研报列表（最近 ${data.recentReports?.length || 0} 篇）`} bodyStyle={{ padding: 0, maxHeight: 420, overflowY: 'auto' }}>
            {(data.recentReports || []).length > 0 ? (
              <Table
                size="small"
                pagination={false}
                dataSource={data.recentReports}
                rowKey={(r) => r.reportDate + r.institution}
                columns={[
                  { title: '日期', dataIndex: 'reportDate', width: 105, align: 'center', sorter: (a,b) => (a.reportDate||'').localeCompare(b.reportDate||'') },
                  { title: '机构', dataIndex: 'institution', width: 90, ellipsis: true },
                  {
                    title: '评级', dataIndex: 'rating',
                    width: 70, align: 'center',
                    render: v => {
                      if (!v) return '-';
                      const color = v === '买入' ? 'red' : v === '增持' ? 'blue' : v === '持有' ? 'default' : v === '减持' ? 'green' : v === '卖出' ? '#52c41a' : 'default';
                      return <Tag color={color}>{v}</Tag>;
                    },
                  },
                  { title: '标题', dataIndex: 'reportTitle', ellipsis: true,
                    render: (t, r) => r.pdfUrl ? (
                      <a href={r.pdfUrl} target="_blank" rel="noreferrer" style={{ fontSize: 13 }}>{t}</a>
                    ) : t,
                  },
                ]}
              />
            ) : (
              <Empty description="暂无研报数据" style={{ margin: 24 }} />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}

// ── 研报分析 Tab end ───────────────────────────────────────────────
