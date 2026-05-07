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
  const [peerData, setPeerData] = useState(null);
  const [valuationData, setValuationData] = useState(null);
  const [industryCorrData, setIndustryCorrData] = useState(null);
  const [limitUpData, setLimitUpData] = useState(null);
  const [blockTradeData, setBlockTradeData] = useState(null);
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

  // AutoComplete 按 Enter 触发搜索
  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      doSearch(inputCode);
    }
  };

  // 加载研报分析数据
  useEffect(() => {
    if (!overview?.code) { setResearchData(null); return; }
    stockAnalysisApi.getResearchReport(overview.code)
      .then(data => setResearchData(data))
      .catch(() => setResearchData(null));
  }, [overview?.code]);

  // 加载同业对比数据
  useEffect(() => {
    if (!overview?.code) { setPeerData(null); return; }
    stockAnalysisApi.getPeerComparison(overview.code)
      .then(data => {
        console.log('[PeerComparison] data:', data);
        setPeerData(data);
      })
      .catch(e => {
        console.error('[PeerComparison] error:', e);
        setPeerData(null);
      });
  }, [overview?.code]);

  // 加载估值分位数据
  useEffect(() => {
    if (!overview?.code) { setValuationData(null); return; }
    stockAnalysisApi.getValuationPercentile(overview.code, 3)
      .then(data => setValuationData(data))
      .catch(() => setValuationData(null));
  }, [overview?.code]);

  // 加载行业关联数据
  useEffect(() => {
    if (!overview?.code) { setIndustryCorrData(null); return; }
    stockAnalysisApi.getIndustryCorrelation(overview.code)
      .then(data => setIndustryCorrData(data))
      .catch(() => setIndustryCorrData(null));
  }, [overview?.code]);

  // 加载涨跌停分析数据
  useEffect(() => {
    if (!overview?.code) { setLimitUpData(null); return; }
    stockAnalysisApi.getLimitUpAnalysis(overview.code)
      .then(data => setLimitUpData(data))
      .catch(() => setLimitUpData(null));
  }, [overview?.code]);

  // 加载大宗交易分析数据
  useEffect(() => {
    if (!overview?.code) { setBlockTradeData(null); return; }
    stockAnalysisApi.getBlockTradeAnalysis(overview.code)
      .then(data => setBlockTradeData(data))
      .catch(() => setBlockTradeData(null));
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

  // ── Tab 标签（带问号说明）────────────────────────────────
  const tabLabel = (label, tooltip) => (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      {label}
      <Tooltip title={tooltip} placement="top" className="tip-light">
        <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb', cursor: 'pointer' }} />
      </Tooltip>
    </span>
  );

  // Tab 项
  const tabItems = overview ? [
    {
      key: 'tech',
      label: tabLabel('技术面', '通过均线排列、MACD、RSI、缠论信号等技术指标，判断股票短期走势和买卖时机。适合把握趋势和择时。'),
      children: <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'tech')} />,
    },
    {
      key: 'money',
      label: tabLabel('资金面', '通过量比、换手率偏离指标，捕捉资金异动信号。放量上涨通常为积极信号，缩量下跌需警惕。'),
      children: <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'money')} />,
    },
    {
      key: 'sentiment',
      label: tabLabel('事件面', '通过涨停连板、炸板率、阶段涨幅等市场情绪指标，判断当前市场热度和投机氛围，辅助规避炒作风险。'),
      children: <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'sentiment')} />,
    },
    {
      key: 'fundamental',
      label: tabLabel('基本面', '通过PE、PB、ROE、营收/净利增速等财务指标，评估公司内在价值和成长性。适合中长期投资参考。'),
      children: <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'fundamental')} />,
    },
    {
      key: 'research',
      label: tabLabel('研报分析', '汇总券商研报的评级分布、覆盖机构、EPS一致预期，了解专业机构对股票的看法和定价锚点。'),
      children: <ResearchReportTab data={researchData} code={overview.code} />,
    },
    {
      key: 'peers',
      label: tabLabel('同业对比', '将目标股票与同行业可比公司进行PE、PB、市值、涨跌幅的横向对比，快速定位估值相对高低。'),
      children: <PeerComparisonTab data={peerData} code={overview.code} />,
    },
    {
      key: 'valuation',
      label: tabLabel('估值分位', '显示PE/PB在当前股票近N年历史数据中的分位数，判断当前估值处于历史底部还是顶部区域，辅助择时。'),
      children: <ValuationTab data={valuationData} code={overview.code} />,
    },
    {
      key: 'industry-corr',
      label: tabLabel('行业关联', '分析个股与所属行业的Beta暴露和联动关系。Beta>1说明弹性大于行业，相关系数反映走势同步性，帮助理解行业风险敞口。'),
      children: <IndustryCorrelationTab data={industryCorrData} code={overview.code} />,
    },
    {
      key: 'limit-up',
      label: tabLabel('涨跌停', '展示涨停/跌停历史记录、涨停原因统计、炸板情况。涨停是强势信号，但炸板率高说明封板不稳。'),
      children: <LimitUpTab data={limitUpData} code={overview.code} />,
    },
    {
      key: 'block-trade',
      label: tabLabel('大宗交易', '展示大宗交易历史、折价率、买卖营业部统计。大宗交易折价率高可能暗示大股东减持意愿，买方营业部集中说明机构承接。'),
      children: <BlockTradeTab data={blockTradeData} code={overview.code} />,
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
      onKeyDown={handleKeyDown}
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
            <Tooltip title="查看评分规则" className="tip-light">
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
                <Tooltip title={overview.risks || ''} className="tip-light">
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
                <Tooltip title="查看评分规则" className="tip-light">
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
                        {detail.dataRange && (
                          <div style={{ marginTop: 4, borderTop: '1px solid #555', paddingTop: 4 }}>
                            数据范围：{detail.dataRange}
                          </div>
                        )}
                      </div>
                    } className="tip-light">
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

            {/* 反转条件 - 减仓/清仓时显示介入参考 */}
            {overview.reversalConditions && (
              <div style={{ marginTop: 8, padding: '8px 12px', background: '#e6fffb', borderRadius: 4, fontSize: 13, color: '#08979c' }}>
                <span style={{ fontWeight: 500 }}>介入参考：</span>{overview.reversalConditions}
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
        {score !== undefined && maxScore > 0 ? `${score}/${maxScore}` : ''}
      </span>
      <span style={{ flex: 1, fontSize: 12, color: '#999' }}>{desc || ''}</span>
    </div>
  );
}

// ── 通用评分明细 Tab（消费 overview.scoreDetails[维度].items） ─────────
function ScoreDetailTab({ detail }) {
  if (!detail || !detail.items || detail.items.length === 0) {
    return <Empty description="暂无数据" />;
  }

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

      {/* 所有指标（后端决定顺序和infoOnly标记） */}
      {detail.items.map((it, i) => (
        <IndicatorRow
          key={i}
          label={it.label}
          value={it.value}
          score={it.score}
          maxScore={it.maxScore}
          desc={it.desc}
          color={it.color || 'default'}
        />
      ))}

      {/* 总分 */}
      <div style={{ marginTop: 12, padding: '8px 0', borderTop: '2px solid #e8e8e8' }}>
        <Text strong style={{ fontSize: 14 }}>
          {detail.dimensionName}：{detail.score ?? '-'}/{detail.maxScore}
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

// ── 同业对比 Tab ─────────────────────────────────────────────────────
function PeerComparisonTab({ data, code }) {
  console.log('[PeerComparisonTab] render, data:', data, 'code:', code);
  if (!data) return <Empty description="暂无同业数据" />;

  const peers = data.peers || [];
  const industry = data.industry || '未知';

  // 格式化市值（元 → 亿元）
  const formatCap = (v) => {
    if (v == null || v === '') return '-';
    const num = Number(v);
    if (isNaN(num)) return '-';
    return (num / 1e8).toFixed(1) + '亿';
  };

  // 格式化涨跌幅
  const formatPct = (v) => {
    if (v == null) return '-';
    const num = Number(v);
    if (isNaN(num)) return '-';
    return num.toFixed(2) + '%';
  };

  const columns = [
    { title: '代码', dataIndex: 'code', width: 90,
      render: (val, row) => val === code ?
        <Text strong style={{ color: '#1890ff' }}>{val}</Text> : <a href={`?code=${val}`} style={{ fontSize: 13 }}>{val}</a>,
    },
    { title: '名称', dataIndex: 'name', width: 100, ellipsis: true },
    {
      title: 'PE(TTM)', dataIndex: 'peTtm',
      width: 85, align: 'center', sorter: (a, b) => (Number(a.peTtm) || 0) - (Number(b.peTtm) || 0),
      render: v => v != null ? Number(v).toFixed(1) : '-',
    },
    {
      title: 'PB', dataIndex: 'pb',
      width: 70, align: 'center', sorter: (a, b) => (Number(a.pb) || 0) - (Number(b.pb) || 0),
      render: v => v != null ? Number(v).toFixed(2) : '-',
    },
    {
      title: '总市值(亿)', dataIndex: 'totalMarketCap',
      width: 105, align: 'right', sorter: (a, b) => (Number(a.totalMarketCap) || 0) - (Number(b.totalMarketCap) || 0),
      render: v => formatCap(v),
    },
    {
      title: '涨跌幅', dataIndex: 'changePercent',
      width: 95, align: 'center', sorter: (a, b) => (Number(a.changePercent) || 0) - (Number(b.changePercent) || 0),
      render: v => {
        if (v == null) return '-';
        const n = Number(v);
        return <span style={{ color: n >= 0 ? '#f5222d' : '#52c41a', fontWeight: 500 }}>{n.toFixed(2)}%</span>;
      },
    },
  ];

  return (
    <div>
      <Alert message={`行业：${industry}，共 ${peers.length} 只股票（按市值排序，蓝色高亮为当前股）`} type="info" showIcon style={{ marginBottom: 12 }} />
      <Table
        size="small"
        dataSource={peers}
        columns={columns}
        rowKey="code"
        pagination={{ pageSize: 15, size: 'small' }}
        scroll={{ x: 650 }}
        rowClassName={(r) => r.code === code ? 'ant-table-row-selected' : ''}
      />
    </div>
  );
}

// ── 估值分位 Tab ──────────────────────────────────────────────────────
function ValuationTab({ data, code }) {
  if (!data) return <Empty description="暂无估值分位数据" />;
  if (data.error) return <Alert type="error" message={data.error} showIcon />;

  const pePct = data.pePercentile ?? 0;
  const pbPct = data.pbPercentile ?? 0;

  // 分位→颜色
  const colorOf = (p) =>
    p >= 80 ? '#cf1322' : p >= 50 ? '#fa8c16' : p >= 20 ? '#1890ff' : '#389e0d';

  // 分位→文字标签
  const labelOf = (p) =>
    p >= 80 ? '高估' : p >= 50 ? '偏贵' : p >= 20 ? '合理' : '低估';

  // ── PE 解释 Tooltip ──
  const peTooltip = (
    <div style={{ width: 480, fontSize: 12, lineHeight: '20px', color: '#333' }}>
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6 }}>PE(TTM) 是什么？</div>
      <div style={{ marginBottom: 8 }}>
        <span style={{ fontWeight: 500 }}>市盈率（滚动）</span>＝ 股价 ÷ 近12个月每股收益
      </div>
      <div style={{ marginBottom: 4, fontWeight: 500 }}>怎么看分位？</div>
      <div style={{ color: '#666', marginBottom: 8 }}>
        显示当前PE在<span style={{ color: '#999' }}>过去N年</span>所有交易日中的历史位置。<br/>
        <span style={{ color: '#389e0d' }}>分位越低</span>＝相对历史越便宜；<span style={{ color: '#cf1322' }}>分位越高</span>＝相对历史越贵。
      </div>
      <div style={{ marginBottom: 4, fontWeight: 500 }}>参考意义</div>
      <div style={{ color: '#666' }}>
        • 分位 &lt; 20%：处于历史底部区域，安全边际较高<br/>
        • 分位 20%~50%：估值偏低，可考虑布局<br/>
        • 分位 50%~80%：估值偏高，谨慎追高<br/>
        • 分位 &gt; 80%：处于历史高位，警惕回调风险
      </div>
    </div>
  );

  // ── PB 解释 Tooltip ──
  const pbTooltip = (
    <div style={{ width: 480, fontSize: 12, lineHeight: '20px', color: '#333' }}>
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6 }}>PB 是什么？</div>
      <div style={{ marginBottom: 8 }}>
        <span style={{ fontWeight: 500 }}>市净率</span>＝ 股价 ÷ 每股净资产（账面价值）
      </div>
      <div style={{ marginBottom: 4, fontWeight: 500 }}>怎么看分位？</div>
      <div style={{ color: '#666', marginBottom: 8 }}>
        显示当前PB在<span style={{ color: '#999' }}>过去N年</span>所有交易日中的历史位置。<br/>
        PB适合评估<span style={{ color: '#666' }}>金融、重资产</span>行业（银行/钢铁/煤炭等）。
      </div>
      <div style={{ marginBottom: 4, fontWeight: 500 }}>参考意义</div>
      <div style={{ color: '#666' }}>
        • 分位 &lt; 20%：破净或接近破净，安全边际高<br/>
        • 分位 20%~50%：净资产折价，适合价值投资<br/>
        • 分位 &gt; 80%：市价远超净资产，警惕泡沫
      </div>
    </div>
  );

  // 单个指标行
  const Row = ({ label, tooltip, pct, current, precision, tooltipStyle }) => (
    <div style={{
      display: 'flex', alignItems: 'center',
      padding: '12px 0', borderBottom: '1px solid #f5f5f5', gap: 12,
    }}>
      {/* 指标名 + 问号 */}
      <span style={{ width: 90, flexShrink: 0, fontWeight: 500, color: '#333', display: 'flex', alignItems: 'center', gap: 2 }}>
        {label}
        <Tooltip title={tooltip} placement="right" overlayInnerStyle={{ width: 500, maxWidth: 520, background: '#fff', color: '#333', fontSize: 12, lineHeight: '20px', boxShadow: '0 6px 16px rgba(0,0,0,0.12), 0 3px 6px rgba(0,0,0,0.08)', borderRadius: 10, padding: '10px 14px', border: '1px solid #e8e8e8' }}>
          <QuestionCircleOutlined style={{ fontSize: 12, color: '#bbb', cursor: 'pointer' }} />
        </Tooltip>
      </span>

      {/* 分位数字 */}
      <span style={{
        width: 100, flexShrink: 0, textAlign: 'center',
        fontSize: 26, fontWeight: 600, color: colorOf(pct),
        fontVariantNumeric: 'tabular-nums',
      }}>
        {pct.toFixed(1)}<span style={{ fontSize: 14, fontWeight: 400 }}>%</span>
      </span>

      {/* 当前值 */}
      <span style={{ width: 100, flexShrink: 0, textAlign: 'center', fontSize: 14, color: '#333' }}>
        {current != null ? Number(current).toFixed(precision) : '-'}
      </span>

      {/* 估值水平标签 */}
      <span style={{
        width: 72, flexShrink: 0, textAlign: 'center',
        fontSize: 12, fontWeight: 500,
        color: colorOf(pct),
        background: colorOf(pct) + '12',
        border: `1px solid ${colorOf(pct)}30`,
        borderRadius: 4, padding: '1px 0',
      }}>{labelOf(pct)}</span>
    </div>
  );

  return (
    <div>
      {/* 表头 */}
      <div style={{
        display: 'flex', padding: '4px 0 6px', gap: 12,
        borderBottom: '2px solid #e8e8e8', fontWeight: 600, fontSize: 12, color: '#999',
      }}>
        <span style={{ width: 90, flexShrink: 0 }}>指标</span>
        <span style={{ width: 100, flexShrink: 0, textAlign: 'center' }}>历史分位</span>
        <span style={{ width: 100, flexShrink: 0, textAlign: 'center' }}>当前值</span>
        <span style={{ width: 72, flexShrink: 0, textAlign: 'center' }}>估值水平</span>
      </div>

      <Row label="PE(TTM)" tooltip={peTooltip} pct={pePct} current={data.peCurrent} precision={1} />
      <Row label="PB"       tooltip={pbTooltip}  pct={pbPct} current={data.pbCurrent} precision={2} />

      <div style={{ marginTop: 8, fontSize: 11, color: '#bbb', lineHeight: '18px' }}>
        {code} · 近{data.years || 3}年历史分位 · PE样本{data.peHistoryCount || 0}日 / PB样本{data.pbHistoryCount || 0}日<br/>
        分位定义：当前值在历史数据中的相对位置，&lt;20%低估 · 20%~50%合理 · 50%~80%偏贵 · &gt;80%高估
      </div>
    </div>
  );
}

// ── 行业关联 Tab ──────────────────────────────────────────────────────
function IndustryCorrelationTab({ data, code }) {
  if (!data) return <Empty description="暂无行业关联数据" />;
  if (data.error) return <Alert type="warning" message={data.error} showIcon />;

  const beta = data.beta ?? 0;
  const corr = data.correlation ?? 0;
  const sampleDays = data.sampleDays || 0;
  const industry = data.industry || '-';
  const dist = data.industryDist || {};
  const recentAlign = data.recentAlignment || [];

  // Beta 颜色
  const betaColor = beta > 1.5 ? '#f5222d' : beta > 1.0 ? '#fa8c16' : beta > 0.5 ? '#1890ff' : '#52c41a';
  // 相关系数颜色
  const corrColor = corr > 0.7 ? '#f5222d' : corr > 0.4 ? '#fa8c16' : corr > 0.2 ? '#1890ff' : '#52c41a';

  const total = Number(dist.total || 0);
  const upCount = Number(dist.upCount || 0);
  const downCount = Number(dist.downCount || 0);

  return (
    <div>
      {/* 第一行：三指标卡 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999', marginBottom: 4, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 4 }}>
              Beta系数
              <Tooltip title="衡量个股相对于所属行业的波动敏感度。Beta>1 表示个股波动大于行业（高弹性），Beta<1 表示波动小于行业（防御性）。例如 Beta=0.63 意味着行业涨跌1%时，个股平均波动0.63%。">
                <QuestionCircleOutlined style={{ fontSize: 11, color: '#bbb', cursor: 'pointer' }} />
              </Tooltip>
            </div>
            <div style={{ fontSize: 28, fontWeight: 600, color: betaColor }}>{beta}</div>
            <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>{data.betaDesc || '-'}</div>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999', marginBottom: 4, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 4 }}>
              行业相关系数
              <Tooltip title="衡量个股与行业指数价格走势的线性相关程度，范围 -1 到 1。>0.7 强联动（几乎同步），0.3~0.7 中等相关，<0.3 弱相关（有独立行情）。例如 0.4 表示个股不完全跟随行业，有自己的独立逻辑。">
                <QuestionCircleOutlined style={{ fontSize: 11, color: '#bbb', cursor: 'pointer' }} />
              </Tooltip>
            </div>
            <div style={{ fontSize: 28, fontWeight: 600, color: corrColor }}>{corr}</div>
            <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>{data.corrDesc || '-'}</div>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999', marginBottom: 4, display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 4 }}>
              行业分布（今日）
              <Tooltip title="所属行业今日整体涨跌分布。涨/跌家数反映板块情绪，可用于判断个股是否跑赢板块内多数股票。">
                <QuestionCircleOutlined style={{ fontSize: 11, color: '#bbb', cursor: 'pointer' }} />
              </Tooltip>
            </div>
            <div style={{ fontSize: 16, fontWeight: 500 }}>
              <span style={{ color: '#f5222d' }}>{upCount}涨</span>
              <span style={{ color: '#999', margin: '0 6px' }}>·</span>
              <span style={{ color: '#52c41a' }}>{downCount}跌</span>
            </div>
            <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>
              {industry} · 共{total}只
            </div>
          </Card>
        </Col>
      </Row>

      {/* 近5日联动 */}
      {recentAlign.length > 0 && (
        <Card size="small" title="近5日超额收益（vs行业）" style={{ marginBottom: 16 }}>
          <Table
            size="small"
            pagination={false}
            dataSource={recentAlign}
            rowKey="dayIndex"
            style={{ border: '1px solid #f0f0f0', borderRadius: 6 }}
            className="industry-excess-table"
            columns={[
              {
                title: '第N日', dataIndex: 'dayIndex', width: 80, align: 'center',
                render: (v, row) => (
                  <div>
                    <div>第{v}日</div>
                    <div style={{ fontSize: 11, color: '#999' }}>{row.tradeDate || '-'}</div>
                  </div>
                ),
              },
              {
                title: '个股收益%', dataIndex: 'stockRet',
                width: 100, align: 'center',
                render: v => <span style={{ color: v >= 0 ? '#f5222d' : '#52c41a' }}>{v?.toFixed(2)}%</span>,
              },
              {
                title: '行业收益%', dataIndex: 'industryRet',
                width: 100, align: 'center',
                render: v => <span style={{ color: v >= 0 ? '#f5222d' : '#52c41a' }}>{v?.toFixed(2)}%</span>,
              },
              {
                title: '超额收益%', dataIndex: 'excessRet',
                width: 100, align: 'center',
                render: v => <span style={{ color: v >= 0 ? '#f5222d' : '#52c41a', fontWeight: 600 }}>{v >= 0 ? '+' : ''}{v?.toFixed(2)}%</span>,
              },
              {
                title: '解读', dataIndex: 'excessRet',
                width: 120, align: 'center',
                render: v => {
                  if (v == null) return '-';
                  const t = Number(v);
                  if (t > 3) return <span style={{ color: '#f5222d' }}>大幅跑赢</span>;
                  if (t > 1) return <span style={{ color: '#fa8c16' }}>跑赢行业</span>;
                  if (t > -1) return <span style={{ color: '#999' }}>基本同步</span>;
                  if (t > -3) return <span style={{ color: '#1890ff' }}>跑输行业</span>;
                  return <span style={{ color: '#52c41a' }}>大幅跑输</span>;
                },
              },
            ]}
          />
        </Card>
      )}

      <div style={{ fontSize: 11, color: '#bbb' }}>
        {code} · 行业: {industry} · 样本: {sampleDays}日 · Beta基于个股收益对行业等权收益回归
      </div>
      <style>{`
        .industry-excess-table .ant-table-thead > tr > th,
        .industry-excess-table .ant-table-tbody > tr > td {
          border-right: 1px solid #f0f0f0;
        }
        .industry-excess-table .ant-table-thead > tr > th:last-child,
        .industry-excess-table .ant-table-tbody > tr > td:last-child {
          border-right: none;
        }
        .industry-excess-table .ant-table-tbody > tr > td {
          padding: 6px 8px;
        }
      `}</style>
    </div>
  );
}

// ── 涨跌停 Tab ──────────────────────────────────────────────────────
function LimitUpTab({ data, code }) {
  if (!data) return <Empty description="暂无涨跌停数据" />;
  if (data.error) return <Alert type="warning" message={data.error} showIcon />;

  const records = data.records || [];
  const stats = data.stats || {};
  const topReasons = data.topReasons || [];

  const ztTypeMap = { zt: '涨停', dt: '跌停', zbgc: '炸板' };
  const ztColorMap = { zt: 'red', dt: 'green', zbgc: 'volcano' };

  const formatMoney = (v) => {
    if (v == null) return '-';
    return (Number(v) / 1e8).toFixed(2) + '亿';
  };

  return (
    <div>
      {/* 统计卡 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>涨停次数</div>
            <div style={{ fontSize: 24, fontWeight: 600, color: '#f5222d' }}>{stats.limitUpCount || 0}</div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>跌停次数</div>
            <div style={{ fontSize: 24, fontWeight: 600, color: '#52c41a' }}>{stats.limitDownCount || 0}</div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>炸板次数</div>
            <div style={{ fontSize: 24, fontWeight: 600, color: '#fa8c16' }}>{stats.brokenCount || 0}</div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>统计区间</div>
            <div style={{ fontSize: 13, fontWeight: 500, color: '#333' }}>
              {stats.firstDate ? stats.firstDate.toString().slice(0,10) : '-'} ~ {stats.lastDate ? stats.lastDate.toString().slice(0,10) : '-'}
            </div>
          </Card>
        </Col>
      </Row>

      {/* 涨停原因 Top */}
      {topReasons.length > 0 && (
        <Card size="small" title="涨停原因（Top10）" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {topReasons.map((r, i) => (
              <Tag key={i} color="red" style={{ fontSize: 13 }}>
                {r.reason} ({r.count}次)
              </Tag>
            ))}
          </div>
        </Card>
      )}

      {/* 历史记录表 */}
      <Card size="small" title="涨跌停记录（近30条）">
        {records.length > 0 ? (
          <Table
            size="small"
            pagination={{ pageSize: 10, size: 'small' }}
            dataSource={records}
            rowKey={(r, i) => r.tradeDate + r.ztType + i}
            columns={[
              { title: '日期', dataIndex: 'tradeDate', width: 110, align: 'center' },
              {
                title: '类型', dataIndex: 'ztType', width: 70, align: 'center',
                render: v => <Tag color={ztColorMap[v] || 'default'}>{ztTypeMap[v] || v}</Tag>,
              },
              {
                title: '涨跌幅%', dataIndex: 'changePct', width: 90, align: 'center',
                render: v => v != null ? <span style={{ color: Number(v) >= 0 ? '#f5222d' : '#52c41a' }}>{Number(v).toFixed(2)}%</span> : '-',
              },
              { title: '收盘价', dataIndex: 'closePrice', width: 80, align: 'center',
                render: v => v != null ? Number(v).toFixed(2) : '-',
              },
              { title: '原因', dataIndex: 'reason', ellipsis: true,
                render: v => v || '-',
              },
            ]}
          />
        ) : (
          <Empty description="该股无涨跌停记录" />
        )}
      </Card>
    </div>
  );
}

// ── 大宗交易 Tab ──────────────────────────────────────────────────────
function BlockTradeTab({ data, code }) {
  if (!data) return <Empty description="暂无大宗交易数据" />;
  if (data.error) return <Alert type="warning" message={data.error} showIcon />;

  const records = data.records || [];
  const stats = data.stats || {};
  const topBuy = data.topBuyBranches || [];
  const topSell = data.topSellBranches || [];

  const formatAmt = (v) => {
    if (v == null) return '-';
    const n = Number(v);
    if (n >= 1e8) return (n / 1e8).toFixed(2) + '亿';
    if (n >= 1e4) return (n / 1e4).toFixed(0) + '万';
    return n.toFixed(0) + '元';
  };
  const formatVolume = (v) => {
    if (v == null) return '-';
    const n = Number(v);
    if (n >= 1e8) return (n / 1e8).toFixed(2) + '亿股';
    if (n >= 1e4) return (n / 1e4).toFixed(2) + '万股';
    return n.toFixed(0) + '股';
  };

  const avgDiscount = stats.avgDiscountRate != null ? Number(stats.avgDiscountRate) : null;

  return (
    <div>
      {/* 统计卡 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>交易笔数</div>
            <div style={{ fontSize: 24, fontWeight: 600, color: '#333' }}>{stats.totalCount || 0}</div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>累计金额</div>
            <div style={{ fontSize: 20, fontWeight: 600, color: '#333' }}>{formatAmt(stats.totalAmount)}</div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>平均折价率</div>
            <div style={{ fontSize: 24, fontWeight: 600, color: avgDiscount != null && avgDiscount < 0 ? '#52c41a' : '#f5222d' }}>
              {avgDiscount != null ? (avgDiscount * 100).toFixed(2) + '%' : '-'}
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px', textAlign: 'center' }}>
            <div style={{ fontSize: 12, color: '#999' }}>统计区间</div>
            <div style={{ fontSize: 13, fontWeight: 500, color: '#333' }}>
              {stats.firstDate ? stats.firstDate.toString().slice(0,10) : '-'} ~ {stats.lastDate ? stats.lastDate.toString().slice(0,10) : '-'}
            </div>
          </Card>
        </Col>
      </Row>

      {/* 买卖营业部 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card size="small" title="买方营业部（Top10）" bodyStyle={{ padding: 0 }}>
            {topBuy.length > 0 ? (
              <Table size="small" pagination={false} dataSource={topBuy} rowKey="branch"
                columns={[
                  { title: '营业部', dataIndex: 'branch', ellipsis: true },
                  { title: '次数', dataIndex: 'count', width: 50, align: 'center' },
                  { title: '金额', dataIndex: 'totalAmount', width: 80, align: 'right', render: v => formatAmt(v) },
                ]}
              />
            ) : <Empty description="暂无数据" style={{ margin: 16 }} />}
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small" title="卖方营业部（Top10）" bodyStyle={{ padding: 0 }}>
            {topSell.length > 0 ? (
              <Table size="small" pagination={false} dataSource={topSell} rowKey="branch"
                columns={[
                  { title: '营业部', dataIndex: 'branch', ellipsis: true },
                  { title: '次数', dataIndex: 'count', width: 50, align: 'center' },
                  { title: '金额', dataIndex: 'totalAmount', width: 80, align: 'right', render: v => formatAmt(v) },
                ]}
              />
            ) : <Empty description="暂无数据" style={{ margin: 16 }} />}
          </Card>
        </Col>
      </Row>

      {/* 交易记录表 */}
      <Card size="small" title="大宗交易记录（近50笔）">
        {records.length > 0 ? (
          <Table
            size="small"
            pagination={{ pageSize: 10, size: 'small' }}
            dataSource={records}
            rowKey={(r, i) => r.tradeDate + r.price + i}
            scroll={{ x: 900 }}
            columns={[
              { title: '日期', dataIndex: 'tradeDate', width: 110, align: 'center' },
              { title: '成交价', dataIndex: 'price', width: 80, align: 'center',
                render: v => v != null ? Number(v).toFixed(2) : '-',
              },
              { title: '成交量', dataIndex: 'volume', width: 90, align: 'right',
                render: v => formatVolume(v),
              },
              { title: '成交额', dataIndex: 'amount', width: 90, align: 'right',
                render: v => formatAmt(v),
              },
              {
                title: '折价率', dataIndex: 'discountRate', width: 80, align: 'center',
                render: v => v != null ? <span style={{ color: Number(v) < 0 ? '#52c41a' : '#f5222d' }}>{(Number(v) * 100).toFixed(2)}%</span> : '-',
              },
              { title: '占流通股%', dataIndex: 'pctOfFloat', width: 90, align: 'center',
                render: v => v != null ? (Number(v) * 100).toFixed(3) + '%' : '-',
              },
              { title: '买方', dataIndex: 'buyBranch', width: 120, ellipsis: true },
              { title: '卖方', dataIndex: 'sellBranch', width: 120, ellipsis: true },
            ]}
          />
        ) : (
          <Empty description="该股无大宗交易记录" />
        )}
      </Card>
    </div>
  );
}
