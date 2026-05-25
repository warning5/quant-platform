import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  Card, Row, Col, Tabs, Input, AutoComplete, Button, Spin, Empty, Tooltip, Tag, Progress,
  Typography, Alert, Statistic, Table, Descriptions,
} from 'antd';
import {
  QuestionCircleOutlined, SearchOutlined,
  ArrowUpOutlined, ArrowDownOutlined,
  FileTextOutlined, DownloadOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import { useSearchParams, Link } from 'react-router-dom';
import { stockAnalysisApi, silentConfig } from '../../api';
import api from '../../api';
import { useMarketThermometer } from '../../hooks/useMarketThermometer';
import ReactECharts from 'echarts-for-react';
import { NewsEventTab } from './NewsEventTab';
import { BidAskPanel } from './BidAskPanel';
import { InstitutionCoverageTab } from './InstitutionCoverageTab';
import { StockPerformanceTab } from './StockPerformanceTab';
import { ShareholderStructureTab } from './ShareholderStructureTab';
import { TriggerDashboard } from './TriggerDashboard';

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

// ── 最近查询历史（localStorage） ────────────────────────────────────────────
const RECENT_KEY = 'stock_analysis_recent';
const RECENT_MAX = 15;

const loadRecent = () => {
  try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); }
  catch { return []; }
};

const saveRecent = (list) => {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); }
  catch { /* quota exceeded, ignore */ }
};

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
  const [newsData, setNewsData] = useState(null);
  const [chanChartData, setChanChartData] = useState(null);
  const [moneyFlowHistoryData, setMoneyFlowHistoryData] = useState(null);
  const [bidAskData, setBidAskData] = useState(null);
  const [institutionCoverageData, setInstitutionCoverageData] = useState(null);
  const [relativeStrengthData, setRelativeStrengthData] = useState(null);
  const [stockPerformanceData, setStockPerformanceData] = useState(null);
  const [bullBearData, setBullBearData] = useState(null);
  const [klineData, setKlineData] = useState(null);
  const [shareholderData, setShareholderData] = useState(null);
  const [error, setError] = useState(null);
  const [rulesVisible, setRulesVisible] = useState(false);
  const [rules, setRules] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const searchTimerRef = useRef(null);
  const { data: thData, status: thStatus } = useMarketThermometer();

  // 最近查询历史
  const [recentQueries, setRecentQueries] = useState(loadRecent);

  // 查询成功后保存到历史
  const addToRecent = useCallback((code, name) => {
    if (!code || !name) return;
    setRecentQueries(prev => {
      const filtered = prev.filter(item => item.code !== code);
      const next = [{ code, name }, ...filtered].slice(0, RECENT_MAX);
      saveRecent(next);
      return next;
    });
  }, []);

  const removeRecent = useCallback((code) => {
    setRecentQueries(prev => {
      const next = prev.filter(item => item.code !== code);
      saveRecent(next);
      return next;
    });
  }, []);

  // 从 URL 读取初始股票代码
  const urlCode = searchParams.get('code') || '';

  // 页面加载时自动查询 URL 中的股票
  useEffect(() => {
    if (urlCode) {
      setInputCode(urlCode);
      doSearch(urlCode);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 查询成功后记录历史
  useEffect(() => {
    if (overview?.code && overview?.name) {
      addToRecent(overview.code, overview.name);
    }
  }, [overview?.code]); // eslint-disable-line react-hooks/exhaustive-deps

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
      .catch(() => {
        setError('查询失败，请稍后重试');
        setOverview(null);
      })
      .finally(() => setLoading(false));

    // 同时加载K线数据
    stockAnalysisApi.getKLine(code, 60)
      .then(data => setKlineData(data))
      .catch(() => setKlineData(null));
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
    stockAnalysisApi.getResearchReport(overview.code, silentConfig)
      .then(data => setResearchData(data))
      .catch(() => setResearchData(null));
  }, [overview?.code]);

  // 加载同业对比数据
  useEffect(() => {
    if (!overview?.code) { setPeerData(null); return; }
    stockAnalysisApi.getPeerComparison(overview.code, silentConfig)
      .then(data => {
        setPeerData(data);
      })
      .catch(() => {
        setPeerData(null);
      });
  }, [overview?.code]);

  // 加载估值分位数据
  useEffect(() => {
    if (!overview?.code) { setValuationData(null); return; }
    stockAnalysisApi.getValuationPercentile(overview.code, 3, silentConfig)
      .then(data => setValuationData(data))
      .catch(() => setValuationData(null));
  }, [overview?.code]);

  // 加载行业关联数据
  useEffect(() => {
    if (!overview?.code) { setIndustryCorrData(null); return; }
    stockAnalysisApi.getIndustryCorrelation(overview.code, silentConfig)
      .then(data => setIndustryCorrData(data))
      .catch(() => setIndustryCorrData(null));
  }, [overview?.code]);

  // 加载涨跌停分析数据
  useEffect(() => {
    if (!overview?.code) { setLimitUpData(null); return; }
    stockAnalysisApi.getLimitUpAnalysis(overview.code, silentConfig)
      .then(data => setLimitUpData(data))
      .catch(() => setLimitUpData(null));
  }, [overview?.code]);

  // 加载大宗交易分析数据
  useEffect(() => {
    if (!overview?.code) { setBlockTradeData(null); return; }
    stockAnalysisApi.getBlockTradeAnalysis(overview.code, silentConfig)
      .then(data => setBlockTradeData(data))
      .catch(() => setBlockTradeData(null));
  }, [overview?.code]);

  // 加载新闻事件数据
  useEffect(() => {
    if (!overview?.code) { setNewsData(null); return; }
    stockAnalysisApi.getNewsAnalysis(overview.code, silentConfig)
      .then(data => setNewsData(data))
      .catch(() => setNewsData(null));
  }, [overview?.code]);

  // 加载缠论K线图数据
  useEffect(() => {
    if (!overview?.code) { setChanChartData(null); return; }
    stockAnalysisApi.getChanChart(overview.code, silentConfig)
      .then(data => setChanChartData(data))
      .catch(() => setChanChartData(null));
  }, [overview?.code]);

  // 加载资金流向历史
  useEffect(() => {
    if (!overview?.code) { setMoneyFlowHistoryData(null); return; }
    stockAnalysisApi.getMoneyFlowHistory(overview.code, 120, silentConfig)
      .then(data => setMoneyFlowHistoryData(data))
      .catch(() => setMoneyFlowHistoryData(null));
  }, [overview?.code]);

  // 加载内外盘比数据
  useEffect(() => {
    if (!overview?.code) { setBidAskData(null); return; }
    stockAnalysisApi.getBidAskAnalysis(overview.code, silentConfig)
      .then(data => setBidAskData(data?.data || data || null))
      .catch(() => setBidAskData(null));
  }, [overview?.code]);

  // 加载机构覆盖度数据（Tab④）
  useEffect(() => {
    if (!overview?.code) { setInstitutionCoverageData(null); return; }
    stockAnalysisApi.getInstitutionCoverage(overview.code, silentConfig)
      .then(data => setInstitutionCoverageData(data?.data || data || null))
      .catch(() => setInstitutionCoverageData(null));
  }, [overview?.code]);

  // 加载相对强弱数据
  useEffect(() => {
    if (!overview?.code) { setRelativeStrengthData(null); return; }
    stockAnalysisApi.getRelativeStrength(overview.code, silentConfig)
      .then(data => setRelativeStrengthData(data))
      .catch(() => setRelativeStrengthData(null));
  }, [overview?.code]);

  // 加载长周期表现数据（P2：YTD、超额收益、RS Rating、行业内排名）
  useEffect(() => {
    if (!overview?.code) { setStockPerformanceData(null); return; }
    stockAnalysisApi.getStockPerformance(overview.code, silentConfig)
      .then(data => setStockPerformanceData(data))
      .catch(() => setStockPerformanceData(null));
  }, [overview?.code]);

  // 加载多空辩论数据
  useEffect(() => {
    if (!overview?.code) { setBullBearData(null); return; }
    api.get(`/analysis/bull-bear?code=${overview.code}`, { ...silentConfig })
      .then(data => setBullBearData(data))
      .catch(() => setBullBearData(null));
  }, [overview?.code]);

  // 加载股东结构数据
  useEffect(() => {
    if (!overview?.code) { setShareholderData(null); return; }
    stockAnalysisApi.getShareholderStructure(overview.code, silentConfig)
      .then(data => setShareholderData(data))
      .catch(() => setShareholderData(null));
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
      label: tabLabel('技术面', '通过均线排列、MACD、RSI等技术指标评分 + K线走势图 + 缠论信号，综合判断短期趋势和买卖时机。'),
      children: (
        <div>
          <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'tech')} />
          <div style={{ marginTop: 16 }}>
            {klineData && klineData.length > 0 ? (
              <Card size="small" title="近60日K线" style={{ marginBottom: 0 }}>
                <KLineChart data={klineData} />
              </Card>
            ) : (
              <Alert type="info" message="暂无K线数据" showIcon />
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'fundamental',
      label: tabLabel('基本面', '通过PE、PB、ROE、营收/净利增速等财务指标，评估公司内在价值和成长性。适合中长期投资参考。'),
      children: <ScoreDetailTab
        detail={overview.scoreDetails?.find(d => d.dimension === 'fundamental')}
        reportPeriod={overview.fundamentalSignal?.endDate}
      />,
    },
    {
      key: 'money',
      label: tabLabel('资金全景', '量比/换手率评分 + 120日主力资金趋势 + 内外盘比，综合评估资金异动和流向变化。'),
      children: (
        <div>
          <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'money')} />
          {moneyFlowHistoryData && (
            <div style={{ marginTop: 16 }}>
              <MoneyFlowHistoryTab data={moneyFlowHistoryData} bidAskData={bidAskData} code={overview.code} />
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'sentiment',
      label: tabLabel('事件面', '通过涨停连板、炸板率、阶段涨幅等市场情绪指标，判断当前市场热度和投机氛围，辅助规避炒作风险。'),
      children: (
        <div>
          <ScoreDetailTab detail={overview.scoreDetails?.find(d => d.dimension === 'sentiment')} />
          <TriggerDashboard
            tech={overview.techSignal}
            money={overview.moneySignal}
            sentiment={overview.sentimentSignal}
            fundamental={overview.fundamentalSignal}
            tailRisks={overview.tailRisks}
            catalysts={overview.catalysts}
            price={overview.price}
          />
        </div>
      ),
    },
    {
      key: 'news-event',
      label: tabLabel('新闻事件', '基于东方财富个股新闻，提取利好/风险分类、事件标签（业绩/扩产/政策/解禁等）、情感评分，辅助捕捉基本面催化信息。'),
      children: <NewsEventTab data={newsData} code={overview.code} catalysts={overview?.catalysts} />,
    },
    {
      key: 'institution-coverage',
      label: tabLabel('机构跟踪', '综合研报覆盖（近1年）、基金持仓（合计流通比例）、机构调研（近90天）三维评估机构关注度。综合得分0-10分，研报≥30篇/基金合计≥30%流通为高覆盖。'),
      children: <InstitutionCoverageTab data={institutionCoverageData} code={overview.code} />,
    },
    {
      key: 'shareholder-structure',
      label: tabLabel('股东结构', '股东人数趋势反映筹码集中度变化；基金持仓明细展示机构资金流向。股东户数减少=筹码集中，通常利好。'),
      children: <ShareholderStructureTab data={shareholderData} code={overview.code} />,
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
    {
      key: 'chan-chart',
      label: tabLabel('缠论图谱', '基于缠论理论实时计算K线合并、笔、中枢、买卖点，可视化展示股票的技术结构。红色标记买点，绿色标记卖点。'),
      children: <ChanChartTab data={chanChartData} code={overview.code} />,
    },
    {
      key: 'relative-strength',
      label: tabLabel('相对强弱', '对比个股与同行业等权组合的累计收益，计算RS Ratio。RS>1表示跑赢行业，<1表示跑输。'),
      children: <RelativeStrengthTab data={relativeStrengthData} code={overview.code} />,
    },
    {
      key: 'stock-performance',
      label: tabLabel('长周期', 'YTD涨幅、超额收益（vs沪深300）、RS Rating（250日全市场排名）、行业内排名。'),
      children: <StockPerformanceTab data={stockPerformanceData} code={overview.code} />,
    },
    {
      key: 'bull-bear',
      label: tabLabel('多空辩论', '基于规则引擎生成多空双方论据，汇总看多和看空因素，辅助判断多空力量对比。'),
      children: bullBearData ? (
        <div>
          {/* 多空力量对比雷达图（按维度） */}
          {((bullBearData.bullArguments?.length > 0 || bullBearData.bearArguments?.length > 0) && (
            <BullBearChart bull={bullBearData.bullArguments || []} bear={bullBearData.bearArguments || []} />
          ))}
          {/* 多空论据列表 */}
          <Row gutter={24}>
            <Col span={12}>
              <Card title={"多方论据 (" + (bullBearData.bullArguments?.length || 0) + ")"} size="small">
                {bullBearData.bullArguments?.length > 0 ? bullBearData.bullArguments.map((arg, i) => (
                  <div key={i} style={{ marginBottom: 8 }}>
                    <Text style={{ color: arg.strength >= 4 ? '#f5222d' : arg.strength >= 3 ? '#fa8c16' : '#999' }}>
                      {'★'.repeat(arg.strength)}{'☆'.repeat(5 - arg.strength)}
                    </Text>
                    <Text strong style={{ marginLeft: 8 }}>{arg.rule}</Text>
                    <div style={{ fontSize: 12, color: '#666', marginLeft: 24 }}>{arg.description}</div>
                  </div>
                )) : <Text type="secondary">暂无多方论据</Text>}
              </Card>
            </Col>
            <Col span={12}>
              <Card title={"空方论据 (" + (bullBearData.bearArguments?.length || 0) + ")"} size="small">
                {bullBearData.bearArguments?.length > 0 ? bullBearData.bearArguments.map((arg, i) => (
                  <div key={i} style={{ marginBottom: 8 }}>
                    <Text style={{ color: arg.strength >= 4 ? '#f5222d' : arg.strength >= 3 ? '#fa8c16' : '#999' }}>
                      {'★'.repeat(arg.strength)}{'☆'.repeat(5 - arg.strength)}
                    </Text>
                    <Text strong style={{ marginLeft: 8 }}>{arg.rule}</Text>
                    <div style={{ fontSize: 12, color: '#666', marginLeft: 24 }}>{arg.description}</div>
                  </div>
                )) : <Text type="secondary">暂无空方论据</Text>}
              </Card>
            </Col>
          </Row>
        </div>
      ) : (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ),
    },
    /* ── P0 尾部风险暴露度 ───────────────────────────────────── */
    {
      key: 'tail-risk',
      label: tabLabel('尾部风险', '基于财务指标（商誉/存货/负债/应收账款/流动性）计算的尾部风险暴露度矩阵，含概率/影响/潜在跌幅，辅助极端风险识别。'),
      children: overview?.tailRisks?.length > 0 ? (
        <Table
          dataSource={overview.tailRisks}
          rowKey={(_, i) => i}
          size="small"
          pagination={false}
          columns={[
            { title: '尾部风险', dataIndex: 'name', key: 'name', width: 120,
              render: (t, r) => <Tooltip title={r.metric} className="tip-light"><Text strong>{t}</Text></Tooltip>,
            },
            { title: '概率', dataIndex: 'probability', key: 'probability', width: 80,
              render: t => <Tag color="red">{t}</Tag>,
            },
            { title: '影响', dataIndex: 'impact', key: 'impact', width: 80,
              render: t => <Text type={t === '致命' ? 'danger' : t === '毁灭性' ? 'danger' : 'warning'}>{t}</Text>,
            },
            { title: '潜在跌幅', dataIndex: 'potentialDecline', key: 'potentialDecline', width: 90,
              render: t => <Text type="danger">{t}</Text>,
            },
            { title: '触发条件', dataIndex: 'triggerCondition', key: 'triggerCondition' },
          ]}
        />
      ) : (
        <Alert type="info" message="当前股票财务指标未触发尾部风险预警" showIcon />
      ),
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
            {thData && thStatus && (
              <Tooltip title={`大盘${thStatus.label}（${thData.fearGreedIndex?.toFixed(0)}°），${thStatus.action}`}>
                <Tag color={thStatus.label === '极度贪婪' ? 'red' : thStatus.label === '极度恐慌' ? 'green' : 'blue'} style={{ marginRight: 12 }}>
                  <Link to="/market-thermometer" style={{ color: 'inherit' }}>{thStatus.label} {thData.fearGreedIndex?.toFixed(0)}°</Link>
                </Tag>
              </Tooltip>
            )}
            <Tooltip title="查看评分规则" className="tip-light">
              <QuestionCircleOutlined
                style={{ fontSize: 16, cursor: 'pointer' }}
                onClick={showRules}
              />
            </Tooltip>
            {overview?.code && (
              <>
                <Button
                  style={{ marginLeft: 8 }}
                  icon={<FileTextOutlined />}
                  onClick={() => window.open(`/api/analysis/html-report?code=${overview.code}&mode=preview`, '_blank')}
                >
                  预览报告
                </Button>
                <Button
                  style={{ marginLeft: 4 }}
                  icon={<DownloadOutlined />}
                  onClick={() => {
                    const link = document.createElement('a');
                    link.href = `/api/analysis/html-report?code=${overview.code}&mode=download`;
                    link.download = `${overview.code}_report.html`;
                    document.body.appendChild(link);
                    link.click();
                    document.body.removeChild(link);
                  }}
                >
                  下载
                </Button>
              </>
            )}
          </Col>
        </Row>
        {/* ── 最近查询历史 ──────────────────────────────────────────────── */}
        {recentQueries.length > 0 && (
          <div style={{ marginTop: 12, paddingTop: 10, borderTop: '1px solid #f0f0f0' }}>
            <Text type="secondary" style={{ fontSize: 12, marginRight: 8 }}>最近:</Text>
            {recentQueries.map(item => (
              <Tag
                key={item.code}
                closable
                onClose={(e) => { e.preventDefault(); removeRecent(item.code); }}
                style={{ cursor: 'pointer', marginBottom: 4 }}
                onClick={() => {
                  setInputCode(item.code);
                  doSearch(item.code);
                }}
                onMouseEnter={(e) => { e.currentTarget.style.color = '#1890ff'; }}
                onMouseLeave={(e) => { e.currentTarget.style.color = ''; }}
              >
                {item.name}({item.code})
              </Tag>
            ))}
          </div>
        )}
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
            {/* 第一行：股票信息 + 操作建议标签 */}
            <Row gutter={24} align="middle" style={{ marginBottom: 12 }}>
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

            {/* 决策卡片：当前价 / 第一目标价 / 第二目标价 / 止损价 / 极端目标价 / 仓位 */}
            {(overview.targetPrice || overview.stopLossPrice || overview.targetPrice2 || overview.extremeTargetPrice) && (
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(6, 1fr)',
                gap: 0,
                border: '1px solid #f0f0f0',
                borderRadius: 8,
                overflow: 'hidden',
                marginBottom: 16,
                background: '#fafafa',
              }}>
                {[
                  { label: '当前价', value: overview.price || '-', sub: '', color: changePct >= 0 ? '#f5222d' : '#52c41a' },
                  { label: '第一目标价', value: overview.targetPrice || '-', sub: '阻力×1.05', color: '#1890ff' },
                  { label: '第二目标价', value: overview.targetPrice2 || '-', sub: 'PE均值回归', color: '#722ed1' },
                  { label: '止损价', value: overview.stopLossPrice || '-', sub: overview.targetPrice ? `距${((1 - parseFloat(overview.stopLossPrice || 0) / parseFloat(overview.price || 1)) * 100).toFixed(1)}%` : 'ATR×1.5', color: '#f5222d' },
                  { label: '极端目标价', value: overview.extremeTargetPrice || '-', sub: 'PB=1x估值', color: '#eb2f96' },
                  { label: '建议仓位', value: overview.position != null ? overview.position + '%' : '-', sub: overview.confidenceLevel ? `信心:${overview.confidenceLevel}` : '', color: '#333' },
                ].map((item, idx) => (
                  <div key={idx} style={{
                    textAlign: 'center',
                    padding: '10px 8px',
                    borderRight: idx < 5 ? '1px solid #f0f0f0' : 'none',
                  }}>
                    <div style={{ fontSize: 11, color: '#999', marginBottom: 2 }}>{item.label}</div>
                    <div style={{ fontSize: 22, fontWeight: 600, color: item.color, lineHeight: 1 }}>{item.value}</div>
                    {item.sub && <div style={{ fontSize: 10, color: '#bbb', marginTop: 2 }}>{item.sub}</div>}
                  </div>
                ))}
              </div>
            )}

            {/* 第二行：综合评分（数字+雷达图） + 仓位/时机 + 三方分析师 */}
            <Row gutter={24} style={{ marginBottom: 16, textAlign: 'center' }}>
              {/* 左：综合评分数字 */}
              <Col span={4} style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center' }}>
                <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>
                  综合评分
                  <Tooltip title="查看评分规则" className="tip-light">
                    <QuestionCircleOutlined
                      style={{ marginLeft: 4, cursor: 'pointer' }}
                      onClick={showRules}
                    />
                  </Tooltip>
                </div>
                <div style={{ fontSize: 36, fontWeight: 500, color: scoreColor(overview.totalScore, 100), lineHeight: 1 }}>
                  {overview.totalScore}
                </div>
                <div style={{ fontSize: 12, color: '#999', marginTop: 2 }}>/ 100</div>
              </Col>
              {/* 中：雷达图 */}
              <Col span={10}>
                {overview.scoreDetails && overview.scoreDetails.length > 0 && (
                  <ScoreRadarChart scoreDetails={overview.scoreDetails} />
                )}
              </Col>
              {/* 右：操作时机 + 三方分析师三角 */}
              <Col span={10} style={{ display: 'flex', flexDirection: 'column', justifyContent: 'flex-start', alignItems: 'center' }}>
                <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>操作时机</div>
                <Tag
                  color={actionColor(overview.actionName)}
                  style={{ fontSize: 16, padding: '4px 16px', marginTop: 4 }}
                >
                  {overview.timing || '-'}
                </Tag>

                {/* 三方分析师三角 */}
                {(overview.conservativeScore != null || overview.neutralScore != null || overview.aggressiveScore != null) && (
                  <div style={{ marginTop: 16, width: '100%' }}>
                    <div style={{ fontSize: 12, color: '#999', marginBottom: 8, textAlign: 'center' }}>
                      三方分析师独立评分
                      <Tooltip
                        styles={{ root: { maxWidth: 'none' }, body: { padding: 0, width: 650 } }}
                        title={
                          <div style={{ padding: '12px 16px', minWidth: 650 }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                              <thead>
                                <tr style={{ background: '#fafafa' }}>
                                  <th style={{ border: '1px solid #f0f0f0', padding: '6px 10px', textAlign: 'left', fontWeight: 600, width: '72px' }}>分析师</th>
                                  <th style={{ border: '1px solid #f0f0f0', padding: '6px 10px', textAlign: 'left', fontWeight: 600, whiteSpace: 'nowrap' }}>策略</th>
                                  <th style={{ border: '1px solid #f0f0f0', padding: '6px 10px', textAlign: 'left', fontWeight: 600 }}>{overview.code || ''} 结果</th>
                                </tr>
                              </thead>
                              <tbody>
                                <tr>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px', color: '#f5222d', fontWeight: 600, width: '72px' }}>保守</td>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px', color: '#666', whiteSpace: 'nowrap' }}>重估值(PE/PB)和负债率防守，轻趋势</td>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px' }}>
                                    <span style={{ color: '#f5222d', fontWeight: 700, fontSize: 13 }}>{overview.conservativeScore ?? '-'}</span>
                                    <span style={{ color: '#999', marginLeft: 4 }}>分（{overview.conservativeDesc || '-'}）</span>
                                  </td>
                                </tr>
                                <tr>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px', color: '#fa8c16', fontWeight: 600, width: '72px' }}>中性</td>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px', color: '#666', whiteSpace: 'nowrap' }}>四维度均衡加权 = 综合评分 / 13.5 归一化到 0-10</td>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px' }}>
                                    <span style={{ color: '#fa8c16', fontWeight: 700, fontSize: 13 }}>{overview.neutralScore ?? '-'}</span>
                                    <span style={{ color: '#999', marginLeft: 4 }}>分（综合{overview.totalScore ?? '-'}分→{overview.neutralScore ?? '-'}）</span>
                                  </td>
                                </tr>
                                <tr>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px', color: '#52c41a', fontWeight: 600, width: '72px' }}>激进</td>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px', color: '#666', whiteSpace: 'nowrap' }}>重趋势+资金+情绪进攻，容忍高估值</td>
                                  <td style={{ border: '1px solid #f0f0f0', padding: '5px 10px' }}>
                                    <span style={{ color: '#52c41a', fontWeight: 700, fontSize: 13 }}>{overview.aggressiveScore ?? '-'}</span>
                                    <span style={{ color: '#999', marginLeft: 4 }}>分（{overview.aggressiveDesc || '-'}）</span>
                                  </td>
                                </tr>
                              </tbody>
                            </table>
                            <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px solid #f0f0f0', fontSize: 11, color: '#999', lineHeight: 1.8 }}>
                              <div><strong>具体逻辑：</strong></div>
                              <div>● <strong>保守</strong>（起点5）：PE&gt;100 扣3分，PE&gt;50 扣2，PE&gt;30 扣1；PB&gt;8 扣2；负债率&gt;80 扣2；只有缠论BUY信号才+1</div>
                              <div>● <strong>中性</strong>：直接 totalScore / 13.5（135满分→10）</div>
                              <div>● <strong>激进</strong>（起点5）：缠论信号加分多、资金面强势加分多、情绪面研报数量加分</div>
                            </div>
                          </div>
                        }
                      >
                        <QuestionCircleOutlined style={{ marginLeft: 4, cursor: 'pointer', color: '#bbb' }} />
                      </Tooltip>
                    </div>
                    <Row gutter={8} style={{ textAlign: 'center' }}>
                      {/* 保守 */}
                      <Col span={8}>
                        <Tooltip title={overview.conservativeDesc || ''}>
                          <div style={{ fontSize: 11, color: '#999' }}>🔴 保守</div>
                          <div style={{ fontSize: 24, fontWeight: 700, color: '#f5222d', lineHeight: 1 }}>{overview.conservativeScore ?? '-'}</div>
                          <div style={{ fontSize: 10, color: '#666' }}>{overview.conservativePosition || '-'}</div>
                        </Tooltip>
                      </Col>
                      {/* 中性 */}
                      <Col span={8}>
                        <Tooltip title={overview.neutralDesc || ''}>
                          <div style={{ fontSize: 11, color: '#999' }}>🟡 中性</div>
                          <div style={{ fontSize: 24, fontWeight: 700, color: '#fa8c16', lineHeight: 1 }}>{overview.neutralScore ?? '-'}</div>
                          <div style={{ fontSize: 10, color: '#666' }}>{overview.neutralPosition || '-'}</div>
                        </Tooltip>
                      </Col>
                      {/* 激进 */}
                      <Col span={8}>
                        <Tooltip title={overview.aggressiveDesc || ''}>
                          <div style={{ fontSize: 11, color: '#999' }}>🟢 激进</div>
                          <div style={{ fontSize: 24, fontWeight: 700, color: '#52c41a', lineHeight: 1 }}>{overview.aggressiveScore ?? '-'}</div>
                          <div style={{ fontSize: 10, color: '#666' }}>{overview.aggressivePosition || '-'}</div>
                        </Tooltip>
                      </Col>
                    </Row>
                  </div>
                )}
              </Col>
            </Row>

            {/* 风险提示 - 紧凑行内样式 */}
            {overview.risks && (
              <div style={{ marginTop: 12, padding: '8px 12px', background: '#fffbe6', borderRadius: 4, fontSize: 13, color: '#d48806' }}>
                <span style={{ fontWeight: 500 }}>风险提示：</span>{overview.risks}
              </div>
            )}

            {/* 分批执行方案 */}
            {overview.executionPlan && (
              <div style={{ marginTop: 8, padding: '8px 12px', background: '#e6fffb', borderRadius: 4, fontSize: 13, color: '#08979c' }}>
                <span style={{ fontWeight: 500 }}>分批执行：</span>{overview.executionPlan}
              </div>
            )}

            {/* 反转条件 - 减仓/清仓时显示介入参考 */}
            {overview.reversalConditions && (
              <div style={{ marginTop: 8, padding: '8px 12px', background: '#e6fffb', borderRadius: 4, fontSize: 13, color: '#08979c' }}>
                <span style={{ fontWeight: 500 }}>介入参考：</span>{overview.reversalConditions}
              </div>
            )}
          </Card>

          {/* ── 分析结论 ─────────────────────────────────────────────── */}
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

      {/* ── 评分规则悬浮说明（鼠标移开自动关闭）──────────────────────────────── */}
      {rulesVisible && (
        <div
          onMouseLeave={() => setRulesVisible(false)}
          style={{
            position: 'fixed', top: 100, right: 50,
            width: 680, zIndex: 1000,
            boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
            background: '#fff',
            borderRadius: 6,
            border: '1px solid #f0f0f0',
            padding: '12px 16px',
            maxHeight: 'calc(100vh - 140px)',
            overflowY: 'auto',
          }}
        >
          <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 10, color: '#333' }}>评分规则说明</div>
          {rules?.map((rule, idx) => (
            <div key={idx} style={{ marginBottom: 10 }}>
              <Text strong>{rule.dimension}（满分{rule.maxScore}）：</Text>
              <div style={{ fontSize: 13, whiteSpace: 'pre-wrap', marginLeft: 8, color: '#444', lineHeight: 1.7 }}>
                {rule.rule.split('\n').map((line, li) => (
                  <div key={li} style={{ display: 'flex' }}>
                    <span style={{ marginRight: 6, flexShrink: 0, color: '#1890ff' }}>•</span>
                    <span>{line}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── 四维度雷达图 ─────────────────────────────────────────────────────────────
function ScoreRadarChart({ scoreDetails }) {
  if (!scoreDetails || scoreDetails.length === 0) return null;

  const [hoveredIdx, setHoveredIdx] = useState(null);
  const [tooltipLocked, setTooltipLocked] = useState(false);
  // 用 ref 持久保存当前 hover 的数据，避免 onMouseLeave 后数据丢失导致 tooltip 消失
  const hoverRef = useRef(null);

  const cx = 155, cy = 118, R = 82;
  const n = scoreDetails.length;
  const toXY = (i, r) => {
    const a = (Math.PI * 2 * i / n) - Math.PI / 2;
    return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) };
  };

  // 雷达图指标数据
  const dims = scoreDetails.map((d, i) => {
    const max = d.maxScore || 10;
    const score = d.score || 0;
    const r = (score / max) * R;
    const endpoint = toXY(i, r);
    const labelPos = toXY(i, R + 16);
    const angle = (Math.PI * 2 * i / n) - Math.PI / 2;
    // 文本对齐方向
    const tx = Math.cos(angle) > 0.1 ? 'start' : Math.cos(angle) < -0.1 ? 'end' : 'middle';
    return { d, i, r, endpoint, labelPos, angle, tx, score, max };
  });

  // 填充多边形路径
  const fillPath = dims.map(({ r }, i) => {
    const p = toXY(i, r);
    return `${i === 0 ? 'M' : 'L'}${p.x},${p.y}`;
  }).join(' ') + 'Z';

  // 网格圈
  const gridPolygons = [0.25, 0.5, 0.75, 1].map(f => {
    const pts = dims.map((_, i) => { const p = toXY(i, R * f); return `${p.x},${p.y}`; }).join(' ');
    return <polygon key={f} points={pts} fill="none" stroke="#e8e8e8" strokeWidth={1} />;
  });

  // 轴线
  const axisLines = dims.map(({ i }) => {
    const p = toXY(i, R);
    return <line key={i} x1={cx} y1={cy} x2={p.x} y2={p.y} stroke="#e8e8e8" strokeWidth={1} />;
  });

  const hoverData = hoverRef.current;
  const showTooltip = hoverData && (hoveredIdx !== null || tooltipLocked);

  return (
    <div style={{ position: 'relative', height: 245 }}>
      <svg width={310} height={240} style={{ display: 'block', margin: '0 auto' }}>
        <rect x={cx - R - 40} y={cy - R - 28} width={R * 2 + 80} height={R * 2 + 56} fill="transparent" />
        {gridPolygons}
        {axisLines}
        <path d={fillPath} fill="rgba(24,144,255,0.12)" />
        <path d={fillPath} fill="none" stroke="#1890ff" strokeWidth={2} />
        {dims.map(({ i, endpoint, angle, d }) => {
          const tipR = 5; // 固定小圆点标记
          const dx = Math.cos(angle) * 12;
          const dy = Math.sin(angle) * 12;
          return (
            <g key={i}>
              {/* 大的透明感应区：覆盖圆点和文字，防止闪烁 */}
              <circle
                cx={endpoint.x} cy={endpoint.y} r={22}
                fill="transparent"
                style={{ cursor: 'pointer' }}
                onMouseEnter={() => {
                  setHoveredIdx(i);
                  setTooltipLocked(false);
                  hoverRef.current = d;
                }}
                onMouseLeave={() => {
                  if (!tooltipLocked) {
                    setHoveredIdx(null);
                  }
                }}
              />
              <circle
                cx={endpoint.x} cy={endpoint.y} r={tipR}
                fill="#1890ff" fillOpacity={hoveredIdx === i ? 0.85 : 0.5}
                style={{ pointerEvents: 'none', transition: 'fill-opacity 0.15s' }}
              />
              <text x={endpoint.x + dx} y={endpoint.y + dy}
                textAnchor="middle" dominantBaseline="middle"
                fontSize={11} fill="#1890ff" fontWeight={600}
                style={{ pointerEvents: 'none', userSelect: 'none' }}>
                {dims[i].score}
              </text>
            </g>
          );
        })}
        {dims.map(({ i, labelPos, tx, d }) => (
          <text key={i} x={labelPos.x} y={labelPos.y}
            textAnchor={tx} dominantBaseline="middle"
            fontSize={12} fill="#333" fontWeight={500}
            style={{ pointerEvents: 'none', userSelect: 'none' }}>
            {d.dimensionName || d.dimension}
          </text>
        ))}
      </svg>
      {showTooltip && (
        <div
          style={{
            position: 'absolute', top: 8, left: 0,
            background: '#fff',
            border: '1px solid #d9d9d9', borderRadius: 8,
            padding: '8px 12px', fontSize: 12, color: '#333',
            boxShadow: '0 4px 16px rgba(0,0,0,0.12)', zIndex: 100, minWidth: 160,
          }}
          onMouseEnter={() => setTooltipLocked(true)}
          onMouseLeave={() => {
            setTooltipLocked(false);
            setHoveredIdx(null);
            hoverRef.current = null;
          }}
        >
          <div style={{ fontWeight: 600, marginBottom: 4, color: '#1890ff' }}>{hoverData.dimensionName}</div>
          <div style={{ marginBottom: 6 }}>得分：<b>{hoverData.score}</b> / {hoverData.maxScore}</div>
          {(hoverData.items || []).filter(it => it.maxScore > 0).map((it, idx) => (
            <div key={idx} style={{ fontSize: 11, marginTop: 3 }}>
              {it.label}：{it.value} <span style={{ color: '#1890ff' }}>({it.score}/{it.maxScore})</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── K线图 ────────────────────────────────────────────────────────────────────
function KLineChart({ data }) {
  if (!data || data.length === 0) return null;

  const dates = data.map(d => d.date);
  const ohlc = data.map(d => [d.open, d.close, d.low, d.high]);
  const volumes = data.map(d => d.volume || 0);
  const closes = data.map(d => d.close);

  // 计算均线
  const calcMA = (period) => {
    const result = [];
    for (let i = 0; i < closes.length; i++) {
      if (i < period - 1) { result.push('-'); continue; }
      const sum = closes.slice(i - period + 1, i + 1).reduce((a, b) => a + b, 0);
      result.push((sum / period).toFixed(2));
    }
    return result;
  };

  const ma5 = calcMA(5);
  const ma10 = calcMA(10);
  const ma20 = calcMA(20);
  const ma60 = calcMA(60);

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params) => {
        const bar = params.find(p => p.seriesType === 'candlestick');
        if (!bar) return '';
        const idx = bar.dataIndex;
        const d = data[idx];
        const vol = params.find(p => p.seriesName === '成交量');
        return `<b>${d.date}</b><br/>
          开：${d.open}<br/>高：${d.high}<br/>低：${d.low}<br/>收：${d.close}
          ${d.changePercent != null ? `<br/>涨跌：${parseFloat(d.changePercent).toFixed(2)}%` : ''}
          ${vol ? `<br/>成交量：${(d.volume / 10000).toFixed(0)}万` : ''}`;
      },
    },
    legend: { data: ['K线', 'MA5', 'MA10', 'MA20', 'MA60'], bottom: -5, type: 'scroll', itemGap: 16 },
    grid: [{ left: 60, right: 20, top: 20, height: '52%', bottom: '28%' }, { left: 60, right: 20, top: '72%', height: '12%', bottom: '14%' }],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, boundaryGap: false, axisLine: { lineStyle: { color: '#e8e8e8' } }, axisLabel: { show: false } },
      { type: 'category', data: dates, gridIndex: 1, boundaryGap: false, axisLine: { lineStyle: { color: '#e8e8e8' } }, axisLabel: { fontSize: 10 } },
    ],
    yAxis: [
      { scale: true, gridIndex: 0, axisLine: { lineStyle: { color: '#e8e8e8' } }, axisLabel: { fontSize: 10 } },
      { scale: true, gridIndex: 1, axisLine: { lineStyle: { color: '#e8e8e8' } }, axisLabel: { fontSize: 10, formatter: v => (v / 10000).toFixed(0) + '万' } },
    ],
    dataZoom: [{ type: 'inside', xAxisIndex: [0, 1], start: 50, end: 100 }, { type: 'slider', xAxisIndex: [0, 1], start: 50, end: 100 }],
    series: [
      {
        type: 'candlestick',
        name: 'K线',
        data: ohlc,
        itemStyle: { color: '#ef5350', color0: '#26a69a', borderColor: '#ef5350', borderColor0: '#26a69a' },
        xAxisIndex: 0, yAxisIndex: 0,
      },
      { type: 'line', name: 'MA5', data: ma5, smooth: true, lineStyle: { width: 1, color: '#f5d76e' }, symbol: 'none', xAxisIndex: 0, yAxisIndex: 0 },
      { type: 'line', name: 'MA10', data: ma10, smooth: true, lineStyle: { width: 1, color: '#42a5f5' }, symbol: 'none', xAxisIndex: 0, yAxisIndex: 0 },
      { type: 'line', name: 'MA20', data: ma20, smooth: true, lineStyle: { width: 1, color: '#ab47bc' }, symbol: 'none', xAxisIndex: 0, yAxisIndex: 0 },
      { type: 'line', name: 'MA60', data: ma60, smooth: true, lineStyle: { width: 1, color: '#ff7043' }, symbol: 'none', xAxisIndex: 0, yAxisIndex: 0 },
      {
        type: 'bar', name: '成交量', data: volumes,
        xAxisIndex: 1, yAxisIndex: 1,
        itemStyle: { color: params => ohlc[params.dataIndex][1] >= ohlc[params.dataIndex][0] ? '#ef535080' : '#26a69a80' },
      },
    ],
  };

  return <ReactECharts option={option} style={{ height: 420 }} notMerge lazyUpdate />;
}

// ── 多空论点对比图 ──────────────────────────────────────────────────────────
function BullBearChart({ bull, bear }) {
  // 按维度聚合强度
  const dimMap = {};
  const bullIdx = new Set(bull.map((_, i) => i));
  [...bull, ...bear].forEach((arg, idx) => {
    const dim = arg.dimension || '其他';
    if (!dimMap[dim]) dimMap[dim] = { bullScore: 0, bearScore: 0 };
    if (idx < bull.length) dimMap[dim].bullScore += arg.strength;
    else dimMap[dim].bearScore += arg.strength;
  });

  const dims = Object.keys(dimMap);
  if (dims.length === 0) return null;

  const bullScores = dims.map(d => dimMap[d].bullScore);
  const bearScores = dims.map(d => -dimMap[d].bearScore);

  const maxAbs = Math.max(...bullScores.map(Math.abs), ...bearScores.map(Math.abs)) + 2;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const dim = params[0].axisValue;
        const bull = params.find(p => p.seriesName === '多头');
        const bear = params.find(p => p.seriesName === '空头');
        return `<b>${dim}</b><br/>多头：+${bull?.value || 0}分<br/>空头：${bear?.value || 0}分`;
      },
    },
    grid: { left: 60, right: 40, top: 20, bottom: 40 },
    xAxis: { type: 'category', data: dims, axisLine: { lineStyle: { color: '#e8e8e8' } }, axisLabel: { fontSize: 12 } },
    yAxis: { type: 'value', min: -maxAbs, max: maxAbs, axisLine: { lineStyle: { color: '#e8e8e8' } }, axisLabel: { fontSize: 10 } },
    series: [
      {
        type: 'bar', name: '多头', data: bullScores,
        itemStyle: { color: '#ef5350' },
        barMaxWidth: 30,
        label: { show: true, position: 'top', fontSize: 10, color: '#ef5350', formatter: v => v.value > 0 ? '+' + v.value : '' },
      },
      {
        type: 'bar', name: '空头', data: bearScores,
        itemStyle: { color: '#26a69a' },
        barMaxWidth: 30,
        label: { show: true, position: 'bottom', fontSize: 10, color: '#26a69a', formatter: v => v.value < 0 ? v.value : '' },
      },
    ],
  };

  return (
    <div style={{ marginBottom: 16 }}>
      <Text strong style={{ fontSize: 13 }}>多空力量对比（按维度）</Text>
      <ReactECharts option={option} style={{ height: 200 }} notMerge lazyUpdate />
    </div>
  );
}

// ── 通用：指标列表行 ──────────────────────────────────────────────────────
// 每行：指标名 | 值(Tag) | 评分 | 说明
/**
 * 根据指标标签、当前值、得分，生成动态解读文案
 * 说明：不是解释指标"是什么"，而是解读"当前这个值意味着什么"
 */
function getValueInterpretation(label, value, score, maxScore) {
  // ── 空值兜底 ──
  if (value == null || value === '-' || value === '暂无数据' || value === '暂无') {
    return '数据缺失，无法评估。建议等待财报更新或确认数据源。';
  }

  // ═══════════════════════════════════════
  //  技术面
  // ═══════════════════════════════════════

  if (label === '缠论信号') {
    if (value.includes('买入')) return '缠论出现买入信号，中枢完成离开段，短期存在结构买点，但需量能配合确认（放量上涨才有效）。';
    if (value.includes('卖出')) return '缠论出现卖出信号，注意中枢完成返回段带来的回调或趋势转折风险，可适当减仓。';
    return '缠论未给出明确买卖信号，当前处于中枢震荡区间，方向未明，宜观望等待。';
  }

  if (label === '趋势状态') {
    if (value === '上涨') return '股价处于明确上涨趋势中（短/中/长期高点依次抬高），顺势做多胜率更高，不要逆势猜顶。';
    if (value === '下跌') return '股价处于下跌趋势中（短/中/长期低点依次降低），抄底风险大，应等待底部结构出现或趋势反转信号。';
    return '股价在一定区间内上下波动（高低点未明显抬高或降低），方向不明，可区间操作或等待突破确认。';
  }

  if (label === '均线多头') {
    if (value === '是') return 'MA5 > MA10 > MA20 > MA60 多头排列完好，短期成本 > 中期成本 > 长期成本，上升趋势健康，支撑有效。';
    return '均线尚未形成多头排列，价格运行杂乱，趋势方向未统一，震荡概率大，不宜重仓追进。';
  }

  if (label === 'MACD金叉') {
    if (value === '是') return 'DIF 从下穿上 DEA（零轴下方为弱势反弹，零轴上方为强势信号），需结合 RSI 判断是否超买区——若 RSI>70 则冲顶嫌疑大，可信度打折。';
    return 'DIF 与 DEA 尚未形成金叉，多头动能尚在积蓄或已转弱，当前不构成 MACD 维度的买入依据。';
  }

  if (label === 'RSI14') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v < 20) return `RSI=${v}，极端超卖（14日指标极低），市场情绪恐慌尾声，往往是中期底部的信号，可开始关注分批建仓机会。`;
      if (v < 30) return `RSI=${v}，处于超卖区，买方力量开始积累但尚未确认，存在反弹可能，建议观察是否出现底部结构。`;
      if (v > 80) return `RSI=${v}，极端超买，市场情绪极度亢奋，随时可能快速回调，追高风险极大。`;
      if (v > 70) return `RSI=${v}，处于超买区，多方力量接近枯竭，注意回落风险，若 MACD 同时出现顶背离则信号更可靠。`;
      if (v > 55) return `RSI=${v}，处于偏强区间，多头占优，趋势有望延续，但已不属低位布局区间。`;
      if (v >= 45) return `RSI=${v}，处于正常偏多区间（45~55），多空力量相对均衡，趋势方向等待放量确认。`;
      if (v >= 35) return `RSI=${v}，处于偏弱区间（35~45），空方占优但未超卖，若有缩量止跌迹象可少量试探。`;
      return `RSI=${v}，处于弱市区（30~35），空方主导，未达超卖区域，趋势逆转信号尚不充分。`;
    }
  }

  if (label === 'BOLL轨道') {
    if (value.includes('突破上轨')) {
      if (score === 2) return '股价突破布林带上轨，但RSI>70处于超买区，这可能是冲顶诱多信号——表面强势但风险积累，得分从6分降权为2分，不宜追高。';
      return '股价突破布林带上轨且RSI未超买，强势延续信号可信，短期动能强劲。布林带开口扩大+价格沿上轨运行，是典型的强趋势延续形态，但连续突破后仍需关注量能是否跟进。';
    }
    if (value.includes('上轨附近')) {
      if (score === 2) return '股价接近布林上轨，但RSI>70超买区，上行空间有限，随时可能遇阻回落，得分从4分降权为2分。';
      return '股价接近布林上轨，偏强势运行，方向未变，但上方空间逐渐受限。上轨是动态阻力位——价格触及上轨时若放量则有望突破，若缩量则大概率遇阻回落。';
    }
    if (value.includes('中上')) return '股价处于布林带中上区域（0.5~0.8），走势偏强，位于中轨上方运行，多方略占优，但尚未触及上轨压力位，还有一定上行空间。';
    if (value.includes('中下')) return '股价处于布林带中下区域（0.2~0.5），偏弱运行，位于中轨下方，空方略占优，若持续贴近下轨则有破位风险，关注下轨支撑是否有效。';
    if (value.includes('下轨')) {
      if (score === 2) return '股价接近布林下轨且RSI<30超卖区，双重低位信号共振——价格处于布林带下边界且动量指标也超卖，反弹概率积累，是潜在布局机会。';
      return '股价跌破或接近布林下轨，极端弱势，可能是加速赶底阶段。下轨是动态支撑位，价格触及下轨时若缩量企稳则可能反弹，若放量跌破下轨则打开下行空间。';
    }
    return '股价处于布林带中轨附近（0.2~0.8 区间），多空均衡。布林带中轨是多空分水岭——价格在中轨上方偏强，下方偏弱，当前方向未明，等待放量选择突破方向。';
  }

  if (label === 'MACD动能') {
    if (value.includes('红柱扩张')) return '红柱持续放大，多头动能加速释放，上涨趋势正在进行中，顺势持有信号。';
    if (value.includes('红柱微缩')) return '红柱开始收窄，上涨动能有所衰减，虽仍处多头但力度减弱，注意观察是否转为死叉。';
    if (value.includes('红柱衰竭')) return '红柱大幅收缩，上涨动能接近枯竭，警惕趋势转折，若继续收缩可能转为绿柱。';
    if (value.includes('绿柱收窄')) return '绿柱开始收窄，空头力量减弱，可能正在筑底或酝酿反弹，需等待金叉确认。';
    if (value.includes('刚转红柱')) return 'MACD 刚从绿转红，多空转换初期，可信度待验证，需等红柱稳定放大再确认。';
    if (value.includes('绿柱扩张')) return '绿柱持续放大，空头动能加速释放，下跌趋势正在进行，逆势抄底风险大。';
    return 'MACD 动能信号无法解读，请结合柱状图方向和绝对值判断多空力度。';
  }

  if (label === 'MACD位置') {
    if (value.includes('死叉(零轴上)')) return 'MACD 在零轴上方死叉，属于强势回调信号——上涨趋势中的短暂修正，不改变中期多头格局，但短期需谨慎。';
    if (value.includes('死叉(零轴下)')) return 'MACD 在零轴下方死叉，空头主导格局明确，下跌趋势延续信号，减仓/止损为主。';
    if (value.includes('零轴上方')) return 'MACD 柱状图位于零轴以上，多头力量主导，红柱可靠性高，上涨趋势健康。';
    if (value.includes('零轴下方')) return 'MACD 柱状图位于零轴以下，空头力量主导，绿柱为主，下跌趋势中减少逆势操作。';
    return 'MACD 零轴位置信号不明确，结合 DIF/DEA 方向和柱状图综合判断。';
  }

  if (label === '量价背离') {
    if (value == null || value === '-' || value === '暂无数据' || value === '暂无') {
      return '数据缺失，无法评估。建议等待财报更新或确认数据源。';
    }

    // 已触发背离
    if (value.includes('高位背离')) return '价格上涨处于高位，但近5日主力资金净流出，是主力高位派发筹码的典型信号，上涨不可持续，应减仓或观望。';
    if (value.includes('低位背离')) return '价格下跌处于低位，但近5日主力资金净流入，主力可能在悄悄吸筹，下跌可能即将见底，可关注是否出现放量阳线确认。';

    // 未触发：value 格式为 "条件未达 | ret5=+x.xx%/main=+xxxx万(元)"
    const ret5Match = value.match(/ret5=([^\s]+)/);
    const mainMatch = value.match(/main=([^\s]+)/);
    if (ret5Match && mainMatch) {
      const ret5 = ret5Match[1];
      const main = mainMatch[1];
      const ret5Num = parseFloat(ret5);
      // main 格式可能是 "+x.x亿(元)" 或 "+xxxx万(元)"
      const mainYuan = main.includes('亿')
        ? parseFloat(main) * 1_0000_0000
        : parseFloat(main.replace('万(元)', '')) * 10000;

      const reasons = [];
      if (!isNaN(ret5Num)) {
        if (ret5Num >= 3) {
          reasons.push('✓ 涨幅 ' + ret5 + ' ≥ +3%（满足）');
        } else {
          reasons.push('✗ 涨幅 ' + ret5 + '（需 ≥ +3%，差 ' + (3 - ret5Num).toFixed(2) + '% 才满足）');
        }
      } else {
        reasons.push('涨幅数据缺失');
      }

      if (!isNaN(mainYuan)) {
        if (ret5Num >= 0 && mainYuan <= -50_000_000) {
          reasons.push('✓ 主力净流出 ' + main + '（满足高位背离）');
        } else if (ret5Num >= 0) {
          reasons.push('✗ 主力净 ' + main + '（需 ≤ -5000万 才触发高位背离）');
        } else if (ret5Num <= 0 && mainYuan >= 50_000_000) {
          reasons.push('✓ 主力净流入 ' + main + '（满足低位背离）');
        } else if (ret5Num <= 0) {
          reasons.push('✗ 主力净 ' + main + '（需 ≥ +5000万 才触发低位背离）');
        } else {
          reasons.push('主力净 ' + main + '（需根据涨跌方向判断）');
        }
      } else {
        reasons.push('主力资金数据缺失');
      }

      return '当前无量价背离信号。\n\n' + reasons.join('\n') + '。\n\n提示：主力资金以"万元"为单位，近5日累计净流入/流出超过5000万才构成背离条件。';
    }

    if (value.includes('背离')) return '检测到量价背离信号，价格与资金流向出现分歧，建议结合其他指标综合判断方向。';
    return '当前无量价背离信号，量价关系正常。';
  }

  if (label === '趋势判断') {
    if (value.includes('反弹')) return '5日涨幅明显高于20日涨幅，属于短期急涨——可能是超跌反弹而非趋势反转，与"趋势状态"配合判断，若趋势仍为空头则此反弹可信度低。';
    if (value.includes('短强')) return '短期涨幅领先中期，走势节奏健康，上涨有根基，不是孤立急拉，趋势延续性较好。';
    if (value.includes('回撤')) return '短期下跌但中期仍在上行，属于正常回调——不改变中期趋势方向，回调企稳后可考虑加仓。';
    if (value.includes('5日')) return '短期与中期涨幅节奏相近，方向一致，暂无明显背离，趋势平稳运行。';
  }

  if (label === '均线空头') {
    if (value === '是') return 'MA5 < MA10 < MA20 < MA60 空头排列，空方主导，下降趋势明确，顺势做空或观望为主。';
    return '均线未形成空头排列，未触发空头排列惩罚。';
  }

  if (label === 'KDJ(9,3,3)') {
    // 解析 K/D/J 具体数值
    const kMatch = value.match(/K(\d+\.?\d*)/);
    const dMatch = value.match(/D(\d+\.?\d*)/);
    const jMatch = value.match(/J(\d+\.?\d*)/);
    const k = kMatch ? parseFloat(kMatch[1]) : null;
    const d = dMatch ? parseFloat(dMatch[1]) : null;
    const j = jMatch ? parseFloat(jMatch[1]) : null;

    if (k != null && d != null && j != null) {
      // 金叉/死叉判断（K/D交叉关系）
      if (value.includes('金叉')) return `K=${k} 上穿 D=${d}，短线动能转多。J=${j}，若 J>100 则属于极端超买钝化（追高风险大）；若 J<0 则极端超卖钝化（是难得的中期底部信号）。当前金叉建议配合 MACD 方向确认，不单独作为买入依据。`;
      if (value.includes('死叉')) return `K=${k} 下穿 D=${d}，短线动能转空。若 K>80 区域死叉，可信度更高（高位派发信号）；若 K<20 区域死叉，属于空头动能继续释放，不宜抄底。`;

      // 超买/超卖判断
      if (k >= 80 || j >= 90) return `K=${k}/D=${d}/J=${j}，KDJ 处于超买区（K≥80 或 J≥90），动量极强但随时钝化——尤其 J 值超过 100 时属于极端超买，是典型的高位派发阶段，不应追高。`;
      if (k <= 20 || j <= 10) return `K=${k}/D=${d}/J=${j}，KDJ 处于超卖区（K≤20 或 J≤10），反弹概率积累——尤其 J<0 属于极端超卖，是难得的中期底部信号，可开始关注分批建仓。`;

      // 正常区间但 K/D/J 相对关系
      if (k > d && d > j) return `K=${k}>D=${d}>J=${j}，多头排列但力度一般（非极端超买），短线偏多但空间已有限，不宜重仓追进。`;
      if (k < d && d < j) return `K=${k}<D=${d}<J=${j}，空头排列但非极端超卖，短线偏空，若出现放量阳线或底背离可关注。`;
      if (k > d) return `K=${k}>D=${d}（J=${j}），K 上穿 D 形成金叉状态，短线偏多；但当前处于正常区间（非超买/超卖），信号强度中等，需确认量能配合。`;
      if (k < d) return `K=${k}<D=${d}（J=${j}），K 下穿 D 形成死叉状态，短线偏空；但当前处于正常区间（非超买/超卖），信号强度中等。`;
      return `K=${k}/D=${d}/J=${j}，KDJ 三值均处于正常区间（20~80），无明显超买超卖，方向不明确，等待 K/D 交叉信号出现。`;
    }

    // 兜底模式匹配（旧格式兼容）
    if (value.includes('金叉')) return 'K 线从下上穿 D 线，短线动量转多，但 J 线易超买钝化，建议配合 MACD 方向确认，不单独作为买入依据。';
    if (value.includes('死叉')) return 'K 线从上穿过 D 线，短线动量转空，若处于高档区（K>80）死叉可信度更高。';
    if (value.includes('超买')) return 'J>90 或 K>80，KDJ 进入超买区，动量极强但随时可能钝化，见此信号不应追高。';
    if (value.includes('超卖')) return 'J<10 或 K<20，KDJ 进入超卖区，反弹概率逐渐积累，但需等 K/D 拐头向上才确认。';
  }

  if (label === 'DMI(14)') {
    // 解析 +DI/-DI 数值
    const pdiMatch = value.match(/\+DI(\d+\.?\d*)/);
    const ndiMatch = value.match(/-DI(\d+\.?\d*)/);
    const adxMatch = value.match(/ADX([\d.]+)/);
    const pdi = pdiMatch ? parseFloat(pdiMatch[1]) : null;
    const ndi = ndiMatch ? parseFloat(ndiMatch[1]) : null;
    const adx = adxMatch ? parseFloat(adxMatch[1]) : null;

    const spread = (pdi != null && ndi != null) ? Math.abs(pdi - ndi) : null;

    if (value.includes('多头')) {
      if (adx != null) {
        if (adx > 35) return `+DI=${pdi}>-DI=${ndi}，两者差值${spread?.toFixed(1) || ''}，ADX=${adx}——趋势方向极为明确且强度高，属于强势上涨趋势，是 DMI 维度最健康的买入信号，顺势持有。`;
        if (adx > 25) return `+DI=${pdi}>-DI=${ndi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——趋势明确但非极端强势，上涨有根基但力度中等，适合持有。`;
        if (adx >= 20) return `+DI=${pdi}>-DI=${ndi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——趋势方向向上但力度一般（刚好越过 20 阈值），可能震荡上行，信号可信度一般，需配合均线确认。`;
        if (adx >= 15) return `+DI=${pdi}>-DI=${ndi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——趋势方向正确但强度偏弱（低于 20 的弱趋势区），上涨可能不持续，仅适合轻仓试探。`;
        return `+DI=${pdi}>-DI=${ndi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——虽然 +DI 领先但 ADX 极低（<15），说明多空力量差距很小，属于震荡行情中的微弱优势，趋势策略应减少操作。`;
      }
      if (spread != null) {
        if (spread >= 10) return `+DI=${pdi}>-DI=${ndi}，差值${spread.toFixed(1)} 较大，多头优势较为明显。但缺乏 ADX 数据无法判断趋势强度，建议结合 MACD 位置确认。`;
        return `+DI=${pdi}>-DI=${ndi}，差值${spread.toFixed(1)} 较小，多头优势微弱，趋势信号不明确。`;
      }
      return '+DI>-DI，多头方向占优，但 ADX 数据缺失无法判断趋势强度，信号可信度打折。';
    }

    if (value.includes('空头')) {
      if (adx != null) {
        if (adx > 35) return `-DI=${ndi}>+DI=${pdi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——下跌趋势极为明确且力度强，是 DMI 维度的强烈看空信号，应减仓或止损，不逆势抄底。`;
        if (adx > 25) return `-DI=${ndi}>+DI=${pdi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——下跌趋势明确，空方力度强，顺势看空为主。`;
        if (adx >= 20) return `-DI=${ndi}>+DI=${pdi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——趋势方向向下但力度中等，下跌有根基，短期偏空。`;
        if (adx >= 15) return `-DI=${ndi}>+DI=${pdi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——空头方向占优但强度偏弱，下跌可能不持续，注意是否有企稳迹象。`;
        return `-DI=${ndi}>+DI=${pdi}，差值${spread?.toFixed(1) || ''}，ADX=${adx}——虽然 -DI 领先但 ADX 极低（<15），多空力量接近，属于震荡中的微弱空头优势，不宜过度看空。`;
      }
      if (spread != null) {
        if (spread >= 10) return `-DI=${ndi}>+DI=${pdi}，差值${spread.toFixed(1)} 较大，空头优势明显。但缺乏 ADX 数据无法判断趋势强度。`;
        return `-DI=${ndi}>+DI=${pdi}，差值${spread.toFixed(1)} 较小，空头优势微弱。`;
      }
      return '-DI>+DI，空头方向占优，但 ADX 数据缺失无法判断趋势强度。';
    }
  }

  if (label === 'SAR') {
    // 解析 SAR 具体数值
    const sarMatch = value.match(/SAR=(\d+\.?\d*)/);
    const sar = sarMatch ? parseFloat(sarMatch[1]) : null;
    const hasAbove = value.includes('>') && !value.includes('<');
    const hasBelow = value.includes('<') && !value.includes('>');
    const isBull = value.includes('多头') || hasBelow;
    const isBear = value.includes('空头') || hasAbove;

    if (value.includes('翻多')) return 'SAR 从上往下穿越价格，重要反转信号——空头趋势终结，多头启动，是右侧买入确认点。';
    if (value.includes('翻空')) return 'SAR 从下往上穿越价格，重要反转信号——多头趋势终结，空头启动，应减仓或止损。';

    if (isBull && sar != null) {
      return `SAR=${sar}元，在价格下方运行——这是多头持仓保护点，当前处于多头持仓区。SAR 上移代表动态止损跟随价格上涨而上移（趋势越强，SAR 上移越快），应顺势持有。`;
    }
    if (isBear && sar != null) {
      return `SAR=${sar}元，在价格上方运行——这是空头持仓保护点，当前处于空头持仓区。SAR 下移代表动态止损跟随价格下跌而下移，趋势未逆转前不宜抄底。若 SAR 持续下移但股价缩量横盘，可能预示止跌。`;
    }

    // 兜底
    if (value.includes('多头')) return 'SAR 在价格下方运行，多头持仓保护点，持续持有信号；SAR 上移代表动态止损点跟随价格上涨而上移。';
    if (value.includes('空头')) return 'SAR 在价格上方运行，空头持仓保护点；SAR 下移代表动态止损点跟随价格下跌而下移。';
  }

  // ── 惩罚项解读 ──
  if (label === '均线空头') {
    return 'MA5<MA10<MA20<MA60 空头排列，短中长期成本依次向下，下跌趋势明确，技术面扣分3分。空头排列中任何反弹都可能是减仓机会，不宜逆势做多。';
  }
  if (label === 'KDJ死叉') {
    return 'KDJ 死叉：K值从上穿D值转为下穿D值，短期动量由多转空，是短线卖出信号，技术面扣分2分。若死叉发生在K>80超买区，可信度更高（高位派发）。';
  }
  if (label === 'DMI空头') {
    return 'DMI 空头信号：-DI > +DI，下跌力度强于上涨力度，趋势方向偏空，技术面扣分1分。若同时ADX>30，则下跌趋势明确且力度强。';
  }
  if (label === 'SAR翻空') {
    return 'SAR 翻空：抛物线转向从价格下方翻至上方，趋势由多转空的重要反转信号，技术面扣分2分。这是右侧卖出确认点，应果断减仓或止损。';
  }

  if (label === '近高/低(60日)') {
    const scoreDesc = score > 0 ? `技术面加分${score}分。` : score < 0 ? `技术面扣分${Math.abs(score)}分。` : '';
    // 同时解析高低两个值（格式：近高=xx(-x.x%) / 近低=xx(+x.x%)）
    const highMatch = value.match(/近高[=\s]*([\d.]+)\s*\((\-?[\d.]+)%\)/);
    const lowMatch = value.match(/近低[=\s]*([\d.]+)\s*\((\-?[\d.]+)%\)/);
    const highPrice = highMatch ? parseFloat(highMatch[1]) : null;
    const highPct = highMatch ? Math.abs(parseFloat(highMatch[2])) : null; // 距高点还差多少%
    const lowPrice = lowMatch ? parseFloat(lowMatch[1]) : null;
    const lowPct = lowMatch ? Math.abs(parseFloat(lowMatch[2])) : null; // 距低点涨了多少%

    if (highPct != null && lowPct != null && highPrice != null && lowPrice != null) {
      const range = highPrice - lowPrice;
      const currentPos = lowPct / (highPct + lowPct) * 100; // 当前在高低区间内的位置

      if (highPct < 3) {
        if (lowPct > 20) return `${scoreDesc}距60日高点仅${highPct.toFixed(1)}%（高点${highPrice}元），已从前低${lowPrice}元反弹${lowPct.toFixed(1)}%，处于区间上沿（${currentPos.toFixed(0)}%处）。即将挑战前高阻力位——若放量突破则打开上涨空间，若量能不足则大概率冲高回落，短期追高风险极大。`;
        if (lowPct > 10) return `${scoreDesc}距60日高点仅${highPct.toFixed(1)}%（高点${highPrice}元），已从前低${lowPrice}元反弹${lowPct.toFixed(1)}%，处于区间上部（${currentPos.toFixed(0)}%处）。接近阻力位但距前低不远，属于波段反弹中段，关注量能是否持续。`;
        return `${scoreDesc}距60日高点仅${highPct.toFixed(1)}%（高点${highPrice}元），距前低${lowPrice}元仅反弹${lowPct.toFixed(1)}%，处于区间上部但反弹力度有限。前高压力在即，若不能放量突破则可能重回震荡。`;
      }
      if (highPct < 10) {
        if (lowPct > 20) return `${scoreDesc}距60日高点${highPct.toFixed(1)}%（高点${highPrice}元），已从前低${lowPrice}元反弹${lowPct.toFixed(1)}%，处于区间上沿偏下（${currentPos.toFixed(0)}%处）。有一定上行空间但并非关键阻力区域，若能持续放量则有望挑战前高。`;
        if (lowPct > 10) return `${scoreDesc}距60日高点${highPct.toFixed(1)}%（高点${highPrice}元），距前低${lowPrice}元反弹${lowPct.toFixed(1)}%，处于区间中部（${currentPos.toFixed(0)}%处）。上下均有空间，方向取决于量能和突破方向。`;
        return `${scoreDesc}距60日高点${highPct.toFixed(1)}%（高点${highPrice}元），距前低${lowPrice}元仅反弹${lowPct.toFixed(1)}%，处于区间下部但反弹力度弱。关注是否能放量突破区间中轨，否则可能下探前低。`;
      }
      if (lowPct < 5) {
        return `${scoreDesc}距前低${lowPrice}元仅${lowPct.toFixed(1)}%（高点${highPrice}元），距高点${highPct.toFixed(1)}%，处于区间底部（${currentPos.toFixed(0)}%处）。接近强支撑区域，若缩量企稳则可能蓄力反弹；若放量跌破前低则打开下行空间，止损需设在${lowPrice}元下方。`;
      }
      if (lowPct < 10) {
        return `${scoreDesc}距前低${lowPrice}元${lowPct.toFixed(1)}%（高点${highPrice}元），距高点${highPct.toFixed(1)}%，处于区间下部（${currentPos.toFixed(0)}%处）。已接近支撑区域但尚未确认底部，下跌动能已部分释放，赔率逐渐改善。`;
      }
      // 中间位置
      if (currentPos > 60) return `${scoreDesc}距高点${highPct.toFixed(1)}%、距低点${lowPct.toFixed(1)}%，当前处于区间上部（${currentPos.toFixed(0)}%处），偏多但上方空间有限，关注前高${highPrice}元能否突破。`;
      if (currentPos > 40) return `${scoreDesc}距高点${highPct.toFixed(1)}%、距低点${lowPct.toFixed(1)}%，当前处于区间中部（${currentPos.toFixed(0)}%处），方向不明确，等待放量选择方向——向上放量看多，向下放量看空。`;
      return `${scoreDesc}距高点${highPct.toFixed(1)}%、距低点${lowPct.toFixed(1)}%，当前处于区间下部（${currentPos.toFixed(0)}%处），偏空但下跌空间有限，关注前低${lowPrice}元支撑是否有效。`;
    }

    // 单值解析（向后兼容）
    if (value.includes('(-') && value.match(/\(\-[\d.]+%\)/)) {
      const pct = parseFloat(value.match(/\(\-([\d.]+)%\)/)?.[1] || '0');
      if (pct < 3) return `${scoreDesc}距60日高点仅${pct}%，即将挑战前高，若放量突破则打开上涨空间，若量能不足则容易冲高回落。`;
      if (pct < 10) return `${scoreDesc}距60日高点${pct}%，有一定空间但并非关键阻力，若能持续放量则压力不大。`;
      return `${scoreDesc}距60日高点${pct}%，前高位置较远，短期上涨空间充足，暂无明显技术阻力。`;
    }
    if (value.includes('(+')) {
      const pct = parseFloat(value.match(/\(\+([\d.]+)%\)/)?.[1] || '0');
      if (pct < 3) return `${scoreDesc}距60日低点仅${pct}%，接近支撑区域，若缩量企稳则可能蓄力反弹，若放量跌破则打开下行空间。`;
      if (pct < 10) return `${scoreDesc}距60日低点${pct}%，处于低位区域，下跌动能已有释放，但趋势逆转需等待放量阳线确认。`;
      return `${scoreDesc}距60日低点${pct}%，处于相对低位，前期下跌动能已有消化，赔率逐渐改善。`;
    }
    return '近高/低数据无法完整解读，请结合括号内百分比判断当前位置是接近阻力还是支撑。';
  }

  if (label === '量比(5日/20日)') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      const scoreDesc = score > 0 ? `技术面加分${score}分。` : score < 0 ? `技术面扣分${Math.abs(score)}分。` : '';
      if (v >= 3) return `量比=${v}，极度放量（3倍以上），${scoreDesc}是异常信号——可能是重大消息刺激、主力对倒出货或机构大进大出。需高度警惕：仅当放量上涨（股价大涨+量比>3）才可信，若放量下跌则是主力甩货，风险极大。`;
      if (v >= 2) return `量比=${v}，显著放量，${scoreDesc}资金活跃度大幅提升——说明有主力资金在积极参与。关键看方向：放量上涨=真金白银做多，可靠性高；放量下跌=主力出货，是危险信号。`;
      if (v >= 1.5) return `量比=${v}，温和放量，${scoreDesc}是最健康的量价配合状态——有资金持续流入支撑上涨，趋势有望延续，是当前最好的做多窗口。`;
      if (v > 1.2) return `量比=${v}，轻微放量，${scoreDesc}量能略高于正常水平，说明有增量资金在试探性介入，但力度有限，需观察是否继续放大至1.5以上。`;
      if (v > 1.0) return `量比=${v}，刚好略高于基准线（1.0），${scoreDesc}量能处于正常偏强状态——有微弱增量，但不足以确认趋势加速，趋势大概率延续现有方向但力度未增强。`;
      if (v === 1.0) return `量比=1.0，恰好等于基准线——近5日均量与近20日均量完全相等，多空力量处于短期均衡，方向选择在即，需等待放量打破平衡。`;
      if (v >= 0.85) return `量比=${v}，轻微缩量，量能略低于正常水平——多空观望情绪渐浓，趋势可能进入整理阶段，现有趋势力度在衰减。`;
      if (v >= 0.7) return `量比=${v}，轻度缩量，动能明显减弱——若股价仍在上涨，则是无量上涨（虚涨），随时可能回调；若股价下跌，则是无量阴跌（延续）。`;
      if (v >= 0.5) return `量比=${v}，明显缩量，交投清淡——多空双方都在等待，要么是横盘蓄势（变盘前兆），要么是无人问津（冷门股）。关键看后续是否放量选择方向。`;
      return `量比=${v}，极度缩量，${scoreDesc}成交极为清淡——往往是变盘的前兆（地量见地价或地量后加速下跌），但也可能仅仅是市场关注度极低的冷门股。`;
    }
  }

  if (label === 'SMA5均线') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      return `SMA5=${v}元，5日平均持仓成本。SMA5 需结合 MA10/MA20/MA60 综合判断多头排列状态，单独参考意义有限。`;
    }
  }

  if (label === '笔方向') {
    if (value === '向上') return '当前缠论笔方向向上，多方主导，走势以上涨笔为主，短线偏多。';
    if (value === '向下') return '当前缠论笔方向向下，空方主导，走势以下跌笔为主，短线偏空。';
    return '笔方向未确定，走势尚未形成有效笔结构，参考价值有限。';
  }

  if (label === '笔数') {
    const v = parseInt(value, 10);
    if (!isNaN(v)) {
      if (v <= 3) return `近期笔数=${v}，笔数较少，走势简洁有力，趋势方向清晰，信号可靠度较高。`;
      if (v <= 6) return `近期笔数=${v}，笔数适中，走势有一定复杂性，趋势仍在但需注意震荡。`;
      return `近期笔数=${v}，笔数较多，走势震荡频繁，多空反复拉锯，信号杂音大，应降低权重。`;
    }
  }

  // ═══════════════════════════════════════
  //  资金面
  // ═══════════════════════════════════════

  if (label === '主力净流入') {
    if (value.includes('亿')) {
      const numMatch = value.replace(/[^0-9.\-]/g, '');
      const v = parseFloat(numMatch);
      if (v >= 3) return `主力净流入${value}，大资金强力做多，控盘度高，股价短期上涨概率大，是积极信号。`;
      if (v >= 1) return `主力净流入${value}，主力积极入场，态度明确，短期偏多。`;
      if (v > 0) return `主力净流入${value}，主力小幅流入，态度偏多但力度有限。`;
      if (v >= -1) return `主力净流出${value}，主力小幅撤退，但力度不大，暂不影响整体格局。`;
      if (v >= -3) return `主力净流出${value}，主力明显流出，短期承压，若持续流出需警惕。`;
      return `主力净流出${value}，主力大举撤离，是强烈的减仓信号，往往伴随股价下跌。`;
    }
    if (value.includes('万')) return `主力净流入${value}，金额较小，反映当日主力参与度不高，方向参考意义有限。`;
  }

  if (label === '主力净流入占比') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 15) return `净流入占比${v}%，主力高度控盘，强势特征极为明显，短期内多头格局大概率延续。`;
      if (v >= 10) return `净流入占比${v}%，主力控盘度高，强势信号。`;
      if (v >= 5) return `净流入占比${v}%，主力参与积极，态度偏多。`;
      if (v >= 0) return `净流入占比${v}%，主力小幅流入，态度偏多但力度有限。`;
      if (v >= -5) return `净流出占比${v}%，主力温和流出，暂未改变整体趋势，可继续观察。`;
      if (v >= -10) return `净流出占比${v}%，主力明显撤离，短期内多头承压，若持续流出则需减仓。`;
      return `净流出占比${v}%，主力大举出逃，是强烈的卖出预警，往往伴随加速下跌。`;
    }
  }

  if (label === '换手率偏离') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v > 5) return `换手率偏离${v}%，远超正常水平，筹码换手极为频繁，可能是主力对倒或机构大进大出，高换手后往往伴随剧烈波动。`;
      if (v > 0) return `换手率偏离${v}%，略高于正常水平，活跃度提升但不过度，多空博弈积极。`;
      if (v >= -2) return `换手率偏离${v}%，接近正常水平，筹码相对稳定，无明显异动。`;
      return `换手率偏离${v}%，明显低于正常水平，筹码锁定良好（控盘型），但也可能是无人问津。`;
    }
  }

  if (label === '超大单净流入') {
    const v = parseFloat(value.replace(/[^0-9.\-]/g, ''));
    if (!isNaN(v) && v > 0) return `超大单净流入${value}，超级资金（单笔>100万）大举买入，往往是机构级别操作，中期信号意义强。`;
    if (!isNaN(v) && v < 0) return `超大单净流出${value}，超级资金大举撤退，是重要的机构减仓信号，需高度重视。`;
    return '超大单净流入数据无法解读，请查看具体数值。';
  }

  if (label === '大单净流入') {
    const v = parseFloat(value.replace(/[^0-9.\-]/g, ''));
    if (!isNaN(v) && v > 0) return `大单净流入${value}，大户资金（单笔20~100万）积极入场，反映中大户投资者做多意愿较强。`;
    if (!isNaN(v) && v < 0) return `大单净流出${value}，大户资金撤离，反映中大户投资者在减仓，对短期走势有一定压力。`;
    return '大单净流入数据无法解读，请查看具体数值。';
  }

  if (label === '主力资金状态') {
    if (value === '主力流入') return '综合判断当日主力资金方向为流入，大资金整体做多，短期偏乐观。';
    if (value === '主力流出') return '综合判断当日主力资金方向为流出，大资金整体撤退，短期偏谨慎。';
    return '主力资金状态暂无数据。';
  }

  if (label === '当日换手率') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 20) return `换手率${v}%，极高换手，筹码极度活跃，多空博弈激烈，高位高换手风险大。`;
      if (v >= 10) return `换手率${v}%，高换手，筹码换手积极，活跃度高；需结合股价位置判断高位还是低位。`;
      if (v >= 3) return `换手率${v}%，正常换手，筹码交换适度，趋势运行健康。`;
      if (v >= 1) return `换手率${v}%，偏低换手，筹码锁定较好，多空分歧小，走势可能延续现有趋势。`;
      return `换手率${v}%，极低换手，交投清淡，通常出现在横盘或趋势末端（高位低换手=主力控盘，低位低换手=无人问津）。`;
    }
  }

  if (label === '量能状态') {
    if (value === '放量') return '综合量能判断为放量，成交量明显放大，资金积极参与，方向与量能配合时信号可靠。';
    if (value === '温和放量') return '综合量能判断为温和放量，量能逐步放大，量价配合健康，是较为理想的做多状态。';
    if (value === '缩量') return '综合量能判断为缩量，成交量明显萎缩，多空观望情绪浓厚，可能横盘或酝酿变盘。';
    return '量能状态数据不明确。';
  }

  // ═══════════════════════════════════════
  //  事件面
  // ═══════════════════════════════════════

  if (label === '连续涨停') {
    const v = parseInt(value, 10);
    if (!isNaN(v) && v >= 3) return `近10日涨停${v}天，强势连板，市场情绪极度亢奋，是龙头股特征，但高位炸板风险极高，不建议追高。`;
    if (!isNaN(v) && v >= 2) return `近10日涨停${v}天，连续强势涨停，市场关注度高，是强势股特征，注意连板后的分歧风险。`;
    if (!isNaN(v) && v === 1) return `近10日涨停1天，偶发性涨停，市场关注度有所提升，注意观察次日开盘情绪是否持续。`;
    return '近10日无涨停记录，市场情绪相对平稳，个股无明显异动，属于正常交易状态。';
  }

  if (label === '炸板率') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v < 5) return `炸板率${v}%，几乎零炸板，封板极为坚决，多方力量极强，是极为强势的做多信号。`;
      if (v < 15) return `炸板率${v}%，炸板极少，封板坚定，多方占优，当日做多胜率高。`;
      if (v < 30) return `炸板率${v}%，存在一定炸板，封板力度一般，多空有分歧，次日走势存在不确定性。`;
      if (v < 50) return `炸板率${v}%，炸板较多，封板脆弱，抛压较大，当日追板风险极高，次日低开概率大。`;
      return `炸板率${v}%，炸板率极高，封板几乎失败，空方主导，次日大概率低开，应避免参与。`;
    }
  }

  if (label === '强势股') {
    if (value === '是') return '近20日涨幅超过30%，属于强势股，动能强劲，但波动也会加大——适合趋势跟踪，不适合逆势操作。';
    return '近20日涨幅未超30%，走势相对温和，不是当前市场热门标的，适合稳健型操作。';
  }

  if (label === '融资余额变化') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v > 10) return `融资余额增长${v}%，杠杆资金大幅加码，情绪极度乐观，是强烈的做多信号，但也要警惕融资盘踩踏风险。`;
      if (v > 5) return `融资余额增长${v}%，杠杆资金积极做多，情绪乐观，上涨趋势有杠杆资金支撑。`;
      if (v > 0) return `融资余额增长${v}%，杠杆资金小幅加仓，态度偏多但力度有限。`;
      if (v >= -3) return `融资余额下降${v}%，杠杆资金小幅撤退，态度略偏空但影响不大。`;
      if (v >= -10) return `融资余额下降${v}%，杠杆资金明显减仓，情绪转弱，若持续下降则需谨慎。`;
      return `融资余额大幅下降${v}%，杠杆资金大举撤离，是强烈的看空信号，融资踩踏往往加速下跌。`;
    }
  }

  if (label === '龙虎榜') {
    if (value.includes('净买入')) return '龙虎榜机构席位净买入，专业机构参与，看多信号强；机构席位净买入次日继续上涨概率较大。';
    if (value.includes('净卖出')) return '龙虎榜机构席位净卖出，专业机构减仓，是机构看空的警示信号，需注意后续走势。';
    if (value === '未上榜') return '该股未登上龙虎榜，说明当日成交金额或涨跌幅未达到上榜标准，不是热点股。';
    return '龙虎榜数据无法解读。';
  }

  if (label === '龙虎榜机构净买入') {
    const v = parseFloat(value.replace(/[^0-9.\-]/g, ''));
    if (!isNaN(v) && v > 0) return `机构席位龙虎榜净买入${value}，专业机构真金白银参与，是中期看好信号，可信度高。`;
    if (!isNaN(v) && v < 0) return `机构席位龙虎榜净卖出${value}，专业机构减仓离场，是中期看淡警示，需结合基本面判断。`;
    return '龙虎榜机构净买入数据无法解读。';
  }

  if (label === '龙虎榜上榜') {
    if (value.includes('净买入')) return '近期多次登上龙虎榜且机构席位净买入，专业机构持续关注，是中长期看好信号。';
    if (value.includes('净卖出')) return '近期登上龙虎榜但机构席位净卖出，专业机构减仓，需注意机构是否在出货。';
    if (value === '未上榜') return '近期未登上龙虎榜，非市场热点或大资金参与度不高。';
    return '龙虎榜上榜数据无法解读。';
  }

  if (label === '机构调研热度') {
    const v = parseInt(value, 10);
    if (!isNaN(v)) {
      if (v >= 15) return `近90天${v}次机构调研，机构高度关注，是市场热门调研标的，往往对应重大业务变化或业绩拐点。`;
      if (v >= 10) return `近90天${v}次机构调研，机构关注度很高，说明公司有一定关注度和话题性。`;
      if (v >= 5) return `近90天${v}次机构调研，关注度尚可，有一定市场认可，但不算热点。`;
      if (v >= 2) return `近90天${v}次机构调研，关注度偏低，覆盖机构有限。`;
      return `近90天${v}次机构调研，几乎无机构关注，可能是冷门股或问题较多未被机构覆盖。`;
    }
  }

  if (label === '公告事件') {
    if (value.match(/正面(\d+)\/负面(\d+)/)) {
      const pos = parseInt(value.match(/正面(\d+)/)?.[1] || '0');
      const neg = parseInt(value.match(/负面(\d+)/)?.[1] || '0');
      const net = pos - neg;
      if (net >= 5) return `公告正面${pos}项/负面${neg}项，净正面${net}项，正面事件密集，公司近期有较多利好催化，可重点关注。`;
      if (net >= 2) return `公告正面${pos}项/负面${neg}项，净正面${net}项，正面事件占优，基本面有正向催化。`;
      if (net >= 0) return `公告正面${pos}项/负面${neg}项，正负事件基本平衡，暂无明显偏向。`;
      if (net >= -2) return `公告正面${pos}项/负面${neg}项，净负面${Math.abs(net)}项，负面事件偏多，需留意潜在风险。`;
      return `公告正面${pos}项/负面${neg}项，净负面${Math.abs(net)}项，负面事件较多，是较强的风险警示信号。`;
    }
  }

  if (label === '基金持仓集中度') {
    const v = parseFloat(value);
    if (!isNaN(v) && v > 0) {
      if (v >= 20) return `基金持仓占流通股${v.toFixed(2)}%，机构高度重仓，大量筹码被锁定，抛压小，股价稳定性高。机构抱团程度显著，是中长期持有的重要支撑。`;
      if (v >= 10) return `基金持仓占流通股${v.toFixed(2)}%，机构有较强配置，关注度高，股价稳定性较好。`;
      if (v >= 5) return `基金持仓占流通股${v.toFixed(2)}%，有机构关注但配置比例一般，需结合其他指标综合判断。`;
      return `基金持仓占流通股${v.toFixed(2)}%，有少量基金配置，机构关注度偏低。`;
    }
    return '暂无基金持仓数据，可能无机构覆盖，或为冷门股。';
  }

  if (label === '新闻事件') {
    if (value && value.includes('利好')) {
      const match = value.match(/(\d+)利好\/(\d+)风险/);
      if (match) {
        const pos = parseInt(match[1]);
        const neg = parseInt(match[2]);
        const biasMatch = value.match(/([+-]?\d+)%/);
        const biasStr = biasMatch ? `情感偏向${biasMatch[1]}%` : '';
        if (pos >= 5 && neg === 0) return `近30天利好${pos}条/风险${neg}条，舆论明显偏多${biasStr ? '，' + biasStr : ''}，新闻面评分高，基本面有持续催化剂。`;
        if (pos > neg) return `近30天利好${pos}条/风险${neg}条，舆论偏正面${biasStr ? '，' + biasStr : ''}，新闻面中性偏多，作为辅助验证可参考。`;
        if (pos === neg) return `近30天利好${pos}条/风险${neg}条，舆论基本平衡${biasStr ? '，' + biasStr : ''}，新闻面无明显偏向。`;
        return `近30天利好${pos}条/风险${neg}条，舆论偏负面${biasStr ? '，' + biasStr : ''}，新闻面是辅助警示信号。`;
      }
    }
    if (value && value.includes('风险')) {
      const match = value.match(/(\d+)利好\/(\d+)风险/);
      if (match) {
        const pos = parseInt(match[1]);
        const neg = parseInt(match[2]);
        if (neg > pos) return `近30天利好${pos}条/风险${neg}条，舆论偏风险，是基本面潜在利空的警示信号，需结合其他指标综合判断。`;
      }
    }
    return '暂无新闻数据，请先更新新闻数据。';
  }

  // ═══════════════════════════════════════
  //  基本面
  // ═══════════════════════════════════════

  if (label === 'ROE') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 20) return `ROE=${v}%，股东回报极为优秀，是巴菲特最看重的指标，这类公司往往具备强大的竞争优势（护城河）。`;
      if (v >= 15) return `ROE=${v}%，股东回报优秀，属于高质量公司，盈利能力强于大多数上市公司。`;
      if (v >= 10) return `ROE=${v}%，盈利质量良好，ROE>10%是白马股的门槛，公司盈利能力稳定。`;
      if (v >= 5) return `ROE=${v}%，盈利能力中等，行业可能处于成熟期或竞争加剧，ROE 能否提升是关键观察点。`;
      return `ROE=${v}%，盈利能力偏弱，公司可能处于亏损边缘或行业衰退期，需确认是否具有反转逻辑再介入。`;
    }
  }

  if (label === 'PE估值') {
    if (typeof value === 'string' && value.includes('分位')) {
      const match = value.match(/分位([\d.]+)%/);
      const pctVal = match ? parseFloat(match[1]) : null;
      if (pctVal != null) {
        if (pctVal <= 10) return `PE历史分位${pctVal}%，处于极低历史区间，是深度价值区间，安全边际极高（前提是基本面无恶化）。`;
        if (pctVal <= 20) return `PE历史分位${pctVal}%，处于历史低估区间，与过去相比估值偏低，安全边际较高。`;
        if (pctVal <= 30) return `PE历史分位${pctVal}%，估值偏低但不是极端低估，仍有下行空间但已不大。`;
        if (pctVal <= 50) return `PE历史分位${pctVal}%，估值处于历史中位附近，合理估值，等待更好的布局时机。`;
        if (pctVal <= 70) return `PE历史分位${pctVal}%，估值偏高，高于历史中位数，当前价格包含较多乐观预期。`;
        return `PE历史分位${pctVal}%，处于历史高估区间，估值压力较大，除非业绩持续高增长否则性价比低。`;
      }
    }
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v < 10) return `PE=${v}倍，绝对估值极低，可能是深度价值股（银行/地产/周期）或基本面有问题，需区分对待。`;
      if (v < 15) return `PE=${v}倍，绝对估值偏低，具备安全边际，适合价值投资者布局。`;
      if (v < 25) return `PE=${v}倍，绝对估值合理，符合市场一般水平，是A股大多数公司的正常估值区间。`;
      if (v < 40) return `PE=${v}倍，估值偏高，需高增速来消化，当前估值已包含较高增长预期。`;
      if (v < 60) return `PE=${v}倍，绝对估值很高，需要业绩持续高速增长才能支撑，高估值高风险。`;
      return `PE=${v}倍，估值极高（超过60倍），PEG>2 概率大，是典型的成长股或题材股特征，波动极大。`;
    }
  }

  if (label === '营收增速') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 50) return `营收增速${v}%，收入爆发式增长，往往对应新产品放量、市场份额提升或行业高景气，是成长股的标志。`;
      if (v >= 30) return `营收增速${v}%，收入高速增长，成长性突出，公司处于快速扩张期。`;
      if (v >= 20) return `营收增速${v}%，增长稳健，处于成长期中期，是较为理想的内生增长水平。`;
      if (v >= 10) return `营收增速${v}%，增长有所放缓但仍为正，需观察增速是否持续下滑。`;
      if (v >= 0) return `营收增速${v}%，收入增速很低，接近停滞，需判断是行业天花板还是公司竞争力下滑。`;
      return `营收增速${v}%，收入负增长，公司业务面临收缩，是较强的负面信号，需排查原因。`;
    }
  }

  if (label === '净利增速') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 100) return `净利增速${v}%，利润爆发式增长，往往是基数效应或主业进入收获期，注意剔除一次性因素。`;
      if (v >= 50) return `净利增速${v}%，利润高速增长，成色足（需与营收增速对比，差距大可能有非经常损益）。`;
      if (v >= 20) return `净利增速${v}%，盈利增长强劲，基本面向好，是较为理想的增速水平。`;
      if (v >= 0) return `净利增速${v}%，利润增速放缓但仍为正，若持续下滑则需警惕。`;
      return `净利增速${v}%，利润下滑，需排查是成本上升、收入下降还是一次性因素导致。`;
    }
  }

  if (label === '扣非增速') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 30) return `扣非增速${v}%，核心业务盈利能力强且增长扎实，剔除水分后利润质量高。`;
      if (v >= 20) return `扣非增速${v}%，主业增长稳健，盈利有实在根基，增速含金量高。`;
      if (v >= 10) return `扣非增速${v}%，主业盈利增长尚可，但速度一般，需观察是否能持续。`;
      if (v >= 0) return `扣非增速${v}%，主业盈利增长缓慢，竞争力或行业景气度需关注。`;
      return `扣非增速${v}%，主业亏损或大幅下滑，公司利润主要靠非经常性损益（政府补贴/资产出售等），风险较大。`;
    }
  }

  if (label === 'PB估值') {
    if (typeof value === 'string' && value.includes('分位')) {
      const match = value.match(/分位([\d.]+)%/);
      const pctVal = match ? parseFloat(match[1]) : null;
      if (pctVal != null) {
        if (pctVal <= 20) return `PB历史分位${pctVal}%，资产估值处于历史低位，市净率便宜，适合价值投资布局。`;
        if (pctVal <= 40) return `PB历史分位${pctVal}%，资产估值偏低，相较历史有折价。`;
        if (pctVal <= 60) return `PB历史分位${pctVal}%，资产估值中性，合理水平。`;
        if (pctVal <= 80) return `PB历史分位${pctVal}%，资产估值偏高，市场对公司资产给予了溢价。`;
        return `PB历史分位${pctVal}%，资产估值处于历史高位，市场对公司资产极为乐观，溢价高。`;
      }
    }
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v < 1) return `PB=${v}倍，股价低于净资产，属于破净股，往往出现在银行/钢铁/煤炭等重资产行业，需区分是价值陷阱还是真的便宜。`;
      if (v < 2) return `PB=${v}倍，资产估值低，下行空间有限，适合保守型价值投资者。`;
      if (v < 3) return `PB=${v}倍，资产估值合理，符合大多数工业/消费类公司水平。`;
      if (v < 5) return `PB=${v}倍，资产估值偏高，市场对公司资产或轻资产属性给予了溢价。`;
      return `PB=${v}倍，资产估值极高，通常对应高成长预期、轻资产模式（科技/消费）或市场高亢情绪。`;
    }
  }

  if (label === '毛利率') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 60) return `毛利率${v}%，定价权极强，是顶级消费品（白酒/奢侈品/创新药）的典型特征，护城河极深。`;
      if (v >= 40) return `毛利率${v}%，定价权强，竞争优势明显，盈利能力有保障，是好行业/好公司的特征。`;
      if (v >= 25) return `毛利率${v}%，盈利空间较大，可能处于品牌消费或有一定差异化的制造业。`;
      if (v >= 15) return `毛利率${v}%，盈利空间一般，处于竞争较激烈的行业（普通制造业/商贸等）。`;
      return `毛利率${v}%，利润空间极薄，成本控制能力是关键，这类公司依赖规模效应或周转率取胜。`;
    }
  }

  if (label === '现金流质量') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      // 数据库存储为百分比（375.00 = 375% = 3.75倍），需除以100转为倍数
      const vTimes = v / 100;
      if (vTimes >= 1.5) return `经营现金流/净利润=${vTimes.toFixed(2)}倍，盈利质量极为优秀，利润全部有真实现金支撑，是最健康的财务状态。`;
      if (vTimes >= 1.0) return `经营现金流/净利润=${vTimes.toFixed(2)}倍，盈利质量好，利润基本有现金覆盖，财务造假风险低。`;
      if (vTimes >= 0.8) return `经营现金流/净利润=${vTimes.toFixed(2)}倍，盈利质量较好，约八成利润有现金支撑，较为健康。`;
      if (vTimes >= 0.5) return `经营现金流/净利润=${vTimes.toFixed(2)}倍，盈利质量一般，部分利润为账面数字，可能对应应收增加或存货堆积。`;
      if (vTimes > 0) return `经营现金流/净利润=${vTimes.toFixed(2)}倍，现金回流明显弱于账面利润，可能存在大量应收账款，警惕坏账风险。`;
      return `经营现金流为负，公司现金净流出，处于投入期（扩张）或烧钱阶段，需结合行业特性和融资能力判断风险。`;
    }
  }

  if (label === '偿债能力') {
    const crMatch = value.match(/流动=([\d.]+)/);
    const qrMatch = value.match(/速动=([\d.]+)/);
    const cr = crMatch ? parseFloat(crMatch[1]) : null;
    const qr = qrMatch ? parseFloat(qrMatch[1]) : null;
    if (cr != null && qr != null) {
      if (cr >= 2 && qr >= 1.5) return `流动比率${cr}、速动比率${qr}，偿债能力极强，财务风险极低，公司资金充裕，抗风险能力强。`;
      if (cr >= 1.5 && qr >= 1) return `流动比率${cr}、速动比率${qr}，偿债能力优秀，短期债务偿还无忧，资金状况健康。`;
      if (cr >= 1.2 && qr >= 0.8) return `流动比率${cr}、速动比率${qr}，偿债能力基本达标，短期流动性尚可，注意持续监控。`;
      if (cr >= 1.0) return `流动比率${cr}、速动比率${qr}，偿债能力勉强及格，若市场恶化或应收账款回收不力可能面临流动性风险。`;
      return `流动比率${cr}、速动比率${qr}，偿债压力较大，短期资金链紧张，是较强的财务风险信号，需高度警惕。`;
    }
  }

  if (label === '回款质量') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v <= 30) return `应收账款周转${v}天，回款极快，资金使用效率高，议价能力强，是优质企业的标志。`;
      if (v <= 60) return `应收账款周转${v}天，回款较快，资金占用少，整体健康。`;
      if (v <= 90) return `应收账款周转${v}天，回款周期正常，处于行业合理水平。`;
      if (v <= 120) return `应收账款周转${v}天，回款周期偏长，资金被下游客户占用较多，需关注大客户回款节奏。`;
      if (v <= 180) return `应收账款周转${v}天，回款明显偏慢，资金被大量占用，警惕坏账风险和现金流恶化。`;
      return `应收账款周转${v}天，回款极慢（超过半年），资金被严重占用，可能是客户信用恶化或行业话语权极弱，风险极高。`;
    }
  }

  if (label === '研报评级') {
    if (value.includes('买入')) return '机构最新评级为买入，专业机构综合基本面、估值、成长性后给出的最高推荐等级，可作为正向参考。';
    if (value.includes('增持')) return '机构评级为增持，态度积极但低于买入，适合中长期关注。';
    if (value.includes('中性')) return '机构评级为中性，认为当前价格已反映基本面，缺乏明显上行空间，观望为主。';
    if (value.includes('减持') || value.includes('卖出')) return '机构看空，是较强的卖出警示，需结合自身判断是否需要减仓。';
    return '暂无最新研报评级，机构关注度低，可能未被主流机构覆盖，股价走势更多依赖市场情绪和资金面。';
  }

  if (label === '研报覆盖热度') {
    const v = parseInt(value, 10);
    if (!isNaN(v)) {
      if (v >= 20) return `近90天${v}篇研报，机构高度密集覆盖，是市场公认的核心标的，研究最充分，信息最透明。`;
      if (v >= 10) return `近90天${v}篇研报，机构关注度很高，市场共识强，信息较充分。`;
      if (v >= 5) return `近90天${v}篇研报，机构关注度尚可，有一定市场认可。`;
      if (v >= 2) return `近90天${v}篇研报，覆盖度偏低，可能存在认知差——要么被低估值得挖掘，要么有问题未被机构发现。`;
      return `近90天无研报覆盖，机构几乎不关注，需自行深入研究，定价效率可能较低（机会或风险并存）。`;
    }
  }

  // ── 新增基本面指标解读 ──────────────────────────────

  if (label === '净利率') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v >= 30) return `净利率${v}%，盈利能力极强，是顶级商业模式（白酒/医药/软件SaaS）的特征，每一元收入都能转化为高额利润，护城河极深。`;
      if (v >= 20) return `净利率${v}%，盈利能力很强，公司具备明显的竞争优势或定价权，利润转化效率高于大多数公司。`;
      if (v >= 10) return `净利率${v}%，盈利能力良好，处于A股中上游水平，经营效率较高，属于优质公司区间。`;
      if (v >= 5) return `净利率${v}%，盈利能力中等，属传统制造业/零售业的正常水平，依赖规模效应或高周转取胜。`;
      if (v > 0) return `净利率${v}%，盈利能力偏弱，每元收入转化利润很少，需关注成本结构是否有改善空间。`;
      return `净利率${v}%，主营业务已亏损，每一元收入都在消耗利润，需高度警惕经营风险。`;
    }
  }

  if (label === '资产负债率') {
    const v = parseFloat(value);
    if (!isNaN(v)) {
      if (v <= 20) return `资产负债率${v}%，负债极低，财务极为稳健，是轻资产/现金牛公司的典型特征（如白酒/互联网平台）。`;
      if (v <= 40) return `资产负债率${v}%，负债水平健康，财务结构稳健，未来仍有加杠杆扩张的空间。`;
      if (v <= 60) return `资产负债率${v}%，负债水平可接受，处于大多数A股公司的正常区间，需关注有息负债占比而非总负债。`;
      if (v <= 80) return `资产负债率${v}%，负债偏高，财务杠杆较大，利息支出可能侵蚀利润，需关注短债置换能力。`;
      return `资产负债率${v}%，负债极高，存在较大财务风险，可能是房地产/银行等高杠杆行业，也可能是财务困境公司，需重点排查。`;
    }
  }

  if (label === '商誉') {
    if (value.includes('倍)')) {
      const match = value.match(/占净利([\d.]+)倍/);
      if (match) {
        const ratio = parseFloat(match[1]);
        if (ratio > 50) return `商誉占净利润${ratio}倍，商誉减值风险极大！一旦被收购标的业绩不达预期，大额减值将重创当年利润，需高度警惕并查阅最新减值测试公告。`;
        if (ratio > 10) return `商誉占净利润${ratio}倍，商誉减值风险较大，每年末需重点关注减值测试，确认并购标的业绩承诺是否还在履行期。`;
        if (ratio > 3) return `商誉占净利润${ratio}倍，有一定减值风险，建议了解被收购标的经营情况，判断业绩承诺可达性。`;
        return `商誉占净利润${ratio}倍，减值风险可控，商誉规模相对利润而言不显著，对财务报表影响有限。`;
      }
    }
    const vMatch = value.match(/([\d.]+)亿/);
    if (vMatch) {
      const v = parseFloat(vMatch[1]);
      if (v > 50) return `商誉${v}亿元，金额较大，需关注被收购标的业绩表现，防范减值风险。`;
      return `商誉${v}亿元，金额较小，对财务报表影响有限。`;
    }
  }

  if (label === '自由现金流') {
    if (value.includes('亿') || value.includes('万')) {
      const isNegative = value.trim().startsWith('-');
      if (!isNegative) return `自由现金流${value}，为正说明公司在维持现有运营和必要投资后还能产生多余现金，是高品质公司的标志，可用于分红/回购/再投资。`;
      return `自由现金流${value}，为负说明公司需要不断投入资本开支才能维持或扩张，属于重资产或高成长投入期特征，需关注投入能否转化为未来现金流。`;
    }
  }

  if (label === '营收/净利(绝对值)') {
    if (value.includes('营收') && value.includes('净利')) {
      return `最新一期财报的营业收入和归母净利润绝对值。营收规模反映公司市场地位，净利规模反映最终盈利体量。结合净利率可以判断公司的盈利效率。`;
    }
  }

  if (label === '存货') {
    const vMatch = value.match(/([\d.]+)亿/);
    if (vMatch) {
      const v = parseFloat(vMatch[1]);
      if (v > 100) return `存货${value}，金额极大，需结合行业特点判断——地产/制造业存货高可能是风险（滞销/跌价），零售/超市存货高可能是正常备货。`;
      return `存货${value}，需结合营业成本判断存货周转天数，周转慢且存货增长快可能是滞销信号。`;
    }
  }

  if (label === '货币资金') {
    const vMatch = value.match(/([\d.]+)亿/);
    if (vMatch) {
      return `货币资金${value}，是公司短期偿付能力和抗风险能力的核心保障。充裕的货币资金意味着公司有能力应对突发情况、进行研发投入或实施分红回购。`;
    }
  }

  if (label === 'WR(14)') {
    const wrMatch = value.match(/([-\d.]+)/);
    const wrVal = wrMatch ? parseFloat(wrMatch[1]) : null;
    if (wrVal != null) {
      if (wrVal < -80) return `WR=${wrVal}，深度超卖区（<-80），价格处于近期极低位置，反弹概率高，但需等待KDJ金叉或放量确认才入场。`;
      if (wrVal < -50) return `WR=${wrVal}，超卖区（-80~-50），价格相对近期高低点偏低，存在反弹机会，但力度可能弱于深度超卖区。`;
      if (wrVal > -20) return `WR=${wrVal}，超买区（>-20），价格处于近期极高位置，回落风险大，不宜追高，可考虑减仓。`;
      return `WR=${wrVal}，正常区间（-50~-20），无明显超买超卖信号，需结合趋势方向判断。`;
    }
  }

  if (label === 'BOLL带宽') {
    const bwMatch = value.match(/([\d.]+)/);
    const bw = bwMatch ? parseFloat(bwMatch[1]) : null;
    if (bw != null) {
      if (bw < 5) return `带宽=${bw}%，极度收敛（<5%），布林带收口到极致，市场犹豫期即将结束，一旦放量突破方向确立，趋势力度往往很强，重点关注。`;
      if (bw < 10) return `带宽=${bw}%，收敛状态（5%~10%），波动收窄，变盘概率积累中，可密切关注突破方向。`;
      return `带宽=${bw}%，正常/发散状态（>10%），波动充分，趋势已运行一段时间，按现有趋势操作即可。`;
    }
  }

  // ═══════════════════════════════════════
  //  通用兜底（所有指标均需覆盖才生效）
  // ═══════════════════════════════════════
  const s = score != null ? score : 0;
  const m = maxScore != null ? maxScore : 0;
  const pct = m > 0 ? Math.round((s / m) * 100) : 0;
  if (pct >= 80) return `${label}当前值得分率${pct}%，处于领先水平，该指标表现优秀。`;
  if (pct >= 60) return `${label}当前值得分率${pct}%，处于良好水平。`;
  if (pct >= 40) return `${label}当前值得分率${pct}%，处于中等水平，无明显优势。`;
  if (pct >= 20) return `${label}当前值得分率${pct}%，处于偏弱水平，建议关注是否有改善信号。`;
  if (label === 'MA发散/收敛') {
    if (value && value.includes('发散')) {
      if (value.includes('→')) {
        const match = value.match(/([-.\d]+)%%→([-.\d]+)%%/);
        if (match) {
          const [prev, curr] = [parseFloat(match[1]), parseFloat(match[2])];
          const delta = curr - prev;
          if (delta > 2) return `MA间距从${prev.toFixed(2)}%→${curr.toFixed(2)}%（扩大>2%），均线大幅发散，趋势加速信号强，行情大概率延续。`;
          if (curr > 0) return `MA间距从${prev.toFixed(2)}%→${curr.toFixed(2)}%，均线正向发散（MA5在MA20上方并扩大差距），上升趋势正在加速。`;
          return `MA间距从${prev.toFixed(2)}%→${curr.toFixed(2)}%（扩大），均线负向发散（空头排列加速），下跌趋势正在加速——做空动能强。`;
        }
      }
      return `均线发散，MA5远离MA20，趋势加速中，行情大概率延续。`;
    }
    if (value && value.includes('收敛')) {
      if (value.includes('→')) {
        const match = value.match(/([-.\d]+)%%→([-.\d]+)%%/);
        if (match) {
          const [prev, curr] = [parseFloat(match[1]), parseFloat(match[2])];
          const delta = Math.abs(curr - prev);
          if (delta > 2) return `MA间距从${prev.toFixed(2)}%→${curr.toFixed(2)}%（收窄>2%），均线大幅收敛，趋势快速衰竭，变盘概率大增。`;
          return `MA间距从${prev.toFixed(2)}%→${curr.toFixed(2)}%，均线收敛（MA5向MA20靠拢），动能衰减，趋势大概率衰竭或进入横盘整理。`;
        }
      }
      return `均线收敛，MA5向MA20靠拢，动能衰减，趋势大概率衰竭。`;
    }
    if (value && value.includes('稳定')) return `MA间距稳定，多空力量相对均衡，短期方向不明，建议等待信号明确后再操作。`;
    return `均线间距无明显变化，趋势处于稳定期。`;
  }
  return `${label}当前值得分率${pct}%，处于落后水平，是当前主要风险点之一。`;
}

function IndicatorRow({ label, value, score, maxScore, desc, color }) {
  const interp = getValueInterpretation(label, value, score, maxScore);
  return (
    <div style={{
      display: 'flex', alignItems: 'center', padding: '6px 0',
      borderBottom: '1px solid #f0f0f0', gap: 12,
    }}>
      <span style={{ width: 120, flexShrink: 0, color: '#333', fontWeight: 500 }}>{label}</span>
      <span style={{ width: 130, flexShrink: 0, textAlign: 'center', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 4 }}>
        <Tag color={color || 'default'} style={{
          margin: 0, fontSize: 13, padding: '2px 8px',
          whiteSpace: 'normal', lineHeight: 1.4, maxWidth: '100%',
        }}>
          {value ?? '-'}
        </Tag>
        <Tooltip
          title={<span style={{ fontSize: 12, lineHeight: '18px' }}>{interp}</span>}
          placement="top"
          overlayStyle={{ maxWidth: 320 }}
        >
          <QuestionCircleOutlined style={{ fontSize: 12, color: '#bfbfbf', cursor: 'pointer' }} />
        </Tooltip>
      </span>
      <span style={{ width: 50, flexShrink: 0, textAlign: 'center', fontSize: 13, color: '#666' }}>
        {score !== undefined && maxScore > 0 ? `${score}/${maxScore}` :
         score !== undefined && score < 0 ? `${score}` : ''}
      </span>
      <span style={{ flex: 1, fontSize: 12, color: '#999' }}>{desc || ''}</span>
    </div>
  );
}

// ── 通用评分明细 Tab（消费 overview.scoreDetails[维度].items） ─────────
function ScoreDetailTab({ detail, reportPeriod }) {
  if (!detail || !detail.items || detail.items.length === 0) {
    return <Empty description="暂无数据" />;
  }

  return (
    <div>
      {/* 数据日期标注 */}
      {detail.dataRange && (
        <div style={{
          marginBottom: 8,
          padding: '6px 12px',
          background: '#f6ffed',
          borderRadius: 4,
          fontSize: 12,
          color: '#52c41a',
          display: 'inline-block',
        }}>
          📅 {detail.dataRange}
        </div>
      )}
      {/* 报告期标注 */}
      {reportPeriod && (
        <div style={{
          marginBottom: 8,
          padding: '6px 12px',
          background: '#f0f5ff',
          borderRadius: 4,
          fontSize: 12,
          color: '#1890ff',
          display: 'inline-block',
        }}>
          📅 财务数据报告期：{reportPeriod}
        </div>
      )}

      {/* 表头 */}
      <div style={{
        display: 'flex', padding: '4px 0', gap: 12,
        borderBottom: '2px solid #e8e8e8', fontWeight: 600, fontSize: 12, color: '#999',
      }}>
        <span style={{ width: 120, flexShrink: 0 }}>指标</span>
        <span style={{ width: 130, flexShrink: 0, textAlign: 'center' }}>当前值</span>
        <span style={{ width: 50, flexShrink: 0, textAlign: 'center' }}>评分</span>
        <span style={{ flex: 1 }}>说明</span>
      </div>

      {/* 核心打分指标（含0分项，让用户看到完整计分明细） */}
      {detail.items.filter(it => it.maxScore > 0).map((it, i) => (
        <IndicatorRow
          key={'s' + i}
          label={it.label}
          value={it.value}
          score={it.score}
          maxScore={it.maxScore}
          desc={it.desc}
          color={it.color || 'default'}
        />
      ))}
      {/* 分隔：扣分项 */}
      {detail.items.filter(it => it.score < 0 && it.maxScore === 0).length > 0 && (
        <div style={{ marginTop: 8, padding: '4px 0', borderTop: '1px dashed #ff4d4f', fontSize: 11, color: '#ff4d4f' }}>
          — 扣分项 —
        </div>
      )}
      {detail.items.filter(it => it.score < 0 && it.maxScore === 0).map((it, i) => (
        <IndicatorRow
          key={'p' + i}
          label={it.label}
          value={it.value}
          score={it.score}
          maxScore={0}
          desc={it.desc}
          color={it.color || 'default'}
        />
      ))}
      {/* 分隔：参考指标 */}
      {detail.items.filter(it => it.score === 0 && it.maxScore === 0).length > 0 && (
        <div style={{ marginTop: 8, padding: '4px 0', borderTop: '1px dashed #d9d9d9', fontSize: 11, color: '#999' }}>
          — 参考指标 —
        </div>
      )}
      {detail.items.filter(it => it.score === 0 && it.maxScore === 0).map((it, i) => (
        <IndicatorRow
          key={'r' + i}
          label={it.label}
          value={it.value}
          score={undefined}
          maxScore={0}
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
        pagination={{ defaultPageSize: 15, showSizeChanger: true, pageSizeOptions: ['10', '15', '20', '30', '50'], size: 'small' }}
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
        <Tooltip title={tooltip} placement="right" styles={{ body: { width: 500, maxWidth: 520, background: '#fff', color: '#333', fontSize: 12, lineHeight: '20px', boxShadow: '0 6px 16px rgba(0,0,0,0.12), 0 3px 6px rgba(0,0,0,0.08)', borderRadius: 10, padding: '10px 14px', border: '1px solid #e8e8e8' } }}>
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
            pagination={{ defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: ['10', '20', '30', '50'], size: 'small' }}
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
            pagination={{ defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: ['10', '20', '30', '50'], size: 'small' }}
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

// ══════════════════════════════════════════════════════════════
// P0 新增 Tab 组件
// ══════════════════════════════════════════════════════════════

/**
 * 缠论K线图 Tab — K线 + 笔 + 中枢 + 买卖点
 */
function ChanChartTab({ data, code }) {
  if (!data) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (data.error) return <Alert type="warning" message={data.error} />;

  const { dates, klineData, pens, hubs, buySellPoints, barCount, penCount, hubCount, bsPointCount } = data;

  // 构建中枢矩形标记（markArea）
  const markAreas = (hubs || []).map(h => {
    if (!h.startDate || !h.endDate) return null;
    const startIdx = dates.indexOf(h.startDate);
    const endIdx = dates.indexOf(h.endDate);
    if (startIdx < 0 || endIdx < 0) return null;
    return [
      { coord: [startIdx, h.high], itemStyle: { color: 'rgba(255, 200, 50, 0.12)' } },
      { coord: [endIdx, h.low] },
    ];
  }).filter(Boolean);

  // 笔折线数据（逐点生成，null 间断）
  const penLineData = [];
  if (pens) {
    // 收集所有端点 index→price
    const pointMap = {};
    pens.forEach(p => {
      pointMap[p.startIndex] = p.startPrice;
      pointMap[p.endIndex] = p.endPrice;
    });
    for (let i = 0; i < dates.length; i++) {
      penLineData.push(pointMap[i] !== undefined ? pointMap[i] : null);
    }
  }

  // 买卖点散点
  const buyPoints = (buySellPoints || []).filter(p => p.isBuy);
  const sellPoints = (buySellPoints || []).filter(p => !p.isBuy);

  const typeLabel = (type) => {
    const map = {
      'FIRST_BUY': '一买', 'SECOND_BUY': '二买', 'THIRD_BUY': '三买',
      'FIRST_SELL': '一卖', 'SECOND_SELL': '二卖', 'THIRD_SELL': '三卖',
    };
    return map[type] || type;
  };

  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params) => {
        const idx = params[0]?.dataIndex;
        if (idx == null) return '';
        const date = dates[idx];
        const kline = klineData[idx];
        if (!kline) return date;
        return [
          `<b>${date}</b>`,
          `开: ${kline[0].toFixed(2)}  收: ${kline[1].toFixed(2)}`,
          `低: ${kline[2].toFixed(2)}  高: ${kline[3].toFixed(2)}`,
          `量: ${(kline[4] / 10000).toFixed(0)}万`,
        ].join('<br/>');
      },
    },
    legend: {
      data: ['K线', '笔', '一买', '二买', '三买', '一卖', '二卖', '三卖'],
      bottom: 0,
      textStyle: { fontSize: 11 },
    },
    grid: [
      { left: 60, right: 30, top: 30, height: '58%' },
      { left: 60, right: 30, top: '72%', height: '16%' },
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, axisLabel: { show: false }, axisTick: { show: false }, splitLine: { show: false } },
      { type: 'category', data: dates, gridIndex: 1, axisLabel: { fontSize: 10, rotate: 30 } },
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, scale: true, splitArea: { show: true } },
      { type: 'value', gridIndex: 1, scale: true, splitNumber: 2 },
    ],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: klineData,
        itemStyle: { color: '#ef5350', color0: '#26a69a', borderColor: '#ef5350', borderColor0: '#26a69a' },
      },
      {
        name: '笔',
        type: 'line',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: penLineData,
        connectNulls: false,
        lineStyle: { color: '#1890ff', width: 1.5 },
        symbol: 'circle',
        symbolSize: 4,
        showSymbol: true,
        z: 10,
        markArea: { silent: true, data: markAreas },
      },
      // 买点
      ...['FIRST_BUY', 'SECOND_BUY', 'THIRD_BUY'].map((type, i) => ({
        name: typeLabel(type),
        type: 'scatter',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: buyPoints.filter(p => p.type === type).map(p => [p.index, p.price]),
        symbol: 'triangle',
        symbolSize: 14 + i * 2,
        itemStyle: { color: i === 0 ? '#ff0000' : i === 1 ? '#ff6600' : '#ff9900' },
        label: { show: true, formatter: typeLabel(type), position: 'bottom', fontSize: 10, color: '#ff0000' },
        z: 20,
      })),
      // 卖点
      ...['FIRST_SELL', 'SECOND_SELL', 'THIRD_SELL'].map((type, i) => ({
        name: typeLabel(type),
        type: 'scatter',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: sellPoints.filter(p => p.type === type).map(p => [p.index, p.price]),
        symbol: 'triangle',
        symbolRotate: 180,
        symbolSize: 14 + i * 2,
        itemStyle: { color: i === 0 ? '#00cc00' : i === 1 ? '#009933' : '#006633' },
        label: { show: true, formatter: typeLabel(type), position: 'top', fontSize: 10, color: '#00cc00' },
        z: 20,
      })),
      // 成交量
      {
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: klineData.map(k => ({
          value: k[4],
          itemStyle: { color: k[1] >= k[0] ? '#ef535080' : '#26a69a80' },
        })),
      },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: Math.max(0, 100 - Math.min(120, barCount) / barCount * 100), end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 30, height: 15 },
    ],
  };

  return (
    <div>
      <Row gutter={8} style={{ marginBottom: 12 }}>
        <Col span={6}><Statistic title="K线天数" value={barCount || 0} valueStyle={{ fontSize: 16 }} /></Col>
        <Col span={6}><Statistic title="笔数" value={penCount || 0} valueStyle={{ fontSize: 16 }} /></Col>
        <Col span={6}><Statistic title="中枢数" value={hubCount || 0} valueStyle={{ fontSize: 16 }} /></Col>
        <Col span={6}><Statistic title="买卖点" value={bsPointCount || 0} valueStyle={{ fontSize: 16 }} /></Col>
      </Row>
      <ReactECharts option={option} style={{ height: 600 }} notMerge lazyUpdate />
    </div>
  );
}

/**
 * 资金流向历史趋势 Tab
 */
function MoneyFlowHistoryTab({ data, bidAskData, code }) {
  if (!data) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (data.error) return <Alert type="warning" message={data.error} />;

  const { history, days, avgNetMain, avgNetMainPct, avgMoneyScore, inflowDays, inflowRatio, latestDate } = data;
  const dates = (history || []).map(h => h.tradeDate?.substring(5));
  const netMainArr = (history || []).map(h => h.netMain ? h.netMain / 1e8 : 0);
  const scoreArr = (history || []).map(h => h.moneyScore || 0);

  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['主力净流入(亿)', '资金面评分', '净流入占比(%)'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: [
      { left: 70, right: 70, top: 30, height: '52%' },
      { left: 70, right: 70, top: '70%', height: '20%' },
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, axisLabel: { show: false }, axisTick: { show: false } },
      { type: 'category', data: dates, gridIndex: 1, axisLabel: { fontSize: 10, rotate: 45 } },
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, name: '净流入(亿)', axisLabel: { formatter: v => v.toFixed(1) } },
      { type: 'value', gridIndex: 1, min: 0, max: 25, name: '评分', splitNumber: 3 },
    ],
    series: [
      {
        name: '主力净流入(亿)',
        type: 'bar',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: netMainArr.map(v => ({
          value: Math.round(v * 100) / 100,
          itemStyle: { color: v >= 0 ? '#ef5350' : '#26a69a' },
        })),
        barMaxWidth: 5,
      },
      {
        name: '资金面评分',
        type: 'line',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: scoreArr,
        lineStyle: { color: '#ff9800', width: 2 },
        itemStyle: { color: '#ff9800' },
        symbol: 'none',
        z: 10,
      },
      {
        name: '净流入占比(%)',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: (history || []).map(h => ({
          value: h.netMainPct ? Math.abs(h.netMainPct) : 0,
          itemStyle: { color: (h.netMainPct || 0) >= 0 ? '#ef535080' : '#26a69a80' },
        })),
        barMaxWidth: 4,
      },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1] },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 28, height: 12 },
    ],
  };

  return (
    <div>
      {/* 数据日期提示 */}
      {latestDate && (
        <div style={{ marginBottom: 12, color: '#888', fontSize: 12 }}>
          数据日期：{latestDate}
        </div>
      )}

      {/* 内外盘比面板（独立展示） */}
      <BidAskPanel data={bidAskData} />

      <Row gutter={8} style={{ marginBottom: 12 }}>
        <Col span={4}><Statistic title="天数" value={days || 0} valueStyle={{ fontSize: 15 }} /></Col>
        <Col span={5}><Statistic title="日均净流入" value={avgNetMain || 0} suffix="亿" precision={2} valueStyle={{ fontSize: 15 }} /></Col>
        <Col span={5}><Statistic title="日均占比" value={avgNetMainPct || 0} suffix="%" precision={2} valueStyle={{ fontSize: 15 }} /></Col>
        <Col span={5}><Statistic title="平均评分" value={avgMoneyScore || 0} suffix="/25" precision={1} valueStyle={{ fontSize: 15 }} /></Col>
        <Col span={5}><Statistic title="流入占比" value={inflowRatio || 0} suffix="%" precision={1} valueStyle={{ fontSize: 15 }} /></Col>
      </Row>
      <ReactECharts option={option} style={{ height: 500 }} notMerge lazyUpdate />
    </div>
  );
}

/**
 * 相对强弱 Tab — 个股 vs 行业累计收益 + RS Ratio
 */
function RelativeStrengthTab({ data, code }) {
  if (!data) return <Spin style={{ display: 'block', margin: '40px auto' }} />;
  if (data.error) return <Alert type="warning" message={data.error} />;

  const { dates, stockCumRet, indCumRet, rsRatio, totalDays, latestStockCumRet,
          latestIndCumRet, latestExcessRet, latestRsRatio, exceedDays, exceedRatio, rsDesc, industry } = data;

  const shortDates = (dates || []).map(d => d?.substring(5));

  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: [code || '个股', `${industry || '行业'}等权`, 'RS Ratio'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: [
      { left: 70, right: 70, top: 30, height: '52%' },
      { left: 70, right: 70, top: '70%', height: '20%' },
    ],
    xAxis: [
      { type: 'category', data: shortDates, gridIndex: 0, axisLabel: { show: false }, axisTick: { show: false } },
      { type: 'category', data: shortDates, gridIndex: 1, axisLabel: { fontSize: 10, rotate: 45 } },
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, name: '累计收益(%)', axisLabel: { formatter: v => v.toFixed(1) + '%' } },
      { type: 'value', gridIndex: 1, name: 'RS Ratio', splitNumber: 3 },
    ],
    series: [
      {
        name: code || '个股',
        type: 'line',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: stockCumRet,
        lineStyle: { color: '#ef5350', width: 2 },
        itemStyle: { color: '#ef5350' },
        symbol: 'none',
      },
      {
        name: `${industry || '行业'}等权`,
        type: 'line',
        xAxisIndex: 0,
        yAxisIndex: 0,
        data: indCumRet,
        lineStyle: { color: '#1890ff', width: 2 },
        itemStyle: { color: '#1890ff' },
        symbol: 'none',
      },
      {
        name: 'RS Ratio',
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: rsRatio,
        lineStyle: { color: '#ff9800', width: 1.5 },
        itemStyle: { color: '#ff9800' },
        symbol: 'none',
        markLine: {
          silent: true,
          data: [{ yAxis: 1, lineStyle: { color: '#999', type: 'dashed' }, label: { formatter: 'RS=1' } }],
        },
      },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1] },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 28, height: 12 },
    ],
  };

  return (
    <div>
      <Row gutter={8} style={{ marginBottom: 12 }}>
        <Col span={4}><Statistic title="行业" value={industry || '-'} valueStyle={{ fontSize: 14 }} /></Col>
        <Col span={5}><Statistic title="个股累计" value={latestStockCumRet || 0} suffix="%" precision={2}
          valueStyle={{ fontSize: 15, color: (latestStockCumRet || 0) >= 0 ? '#ef5350' : '#26a69a' }} /></Col>
        <Col span={5}><Statistic title="行业累计" value={latestIndCumRet || 0} suffix="%" precision={2}
          valueStyle={{ fontSize: 15, color: (latestIndCumRet || 0) >= 0 ? '#ef5350' : '#26a69a' }} /></Col>
        <Col span={5}><Statistic title="超额收益" value={latestExcessRet || 0} suffix="%" precision={2}
          valueStyle={{ fontSize: 15, color: (latestExcessRet || 0) >= 0 ? '#ef5350' : '#26a69a' }} /></Col>
        <Col span={5}><Statistic title="RS Ratio" value={latestRsRatio || 0} precision={2}
          valueStyle={{ fontSize: 15, color: (latestRsRatio || 0) >= 1 ? '#ef5350' : '#26a69a' }} /></Col>
      </Row>
      <Alert
        type={latestExcessRet > 0 ? 'success' : 'warning'}
        message={rsDesc || '-'}
        showIcon
        style={{ marginBottom: 12 }}
        description={`RS Ratio ${latestRsRatio || 0}，超额收益 ${latestExcessRet || 0}%，跑赢行业 ${exceedRatio || 0}% 的交易日`}
      />
      <ReactECharts option={option} style={{ height: 500 }} notMerge lazyUpdate />
    </div>
  );
}
