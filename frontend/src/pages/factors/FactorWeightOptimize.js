import React, { useState, useEffect } from 'react';
import dayjs from 'dayjs';
import {
  Card, Row, Col, Select, Button, Tag, Spin, Alert, Space,
  Typography, Table, Tooltip, Divider, Statistic, DatePicker, Modal, message,
} from 'antd';
import {
  BarChartOutlined, ReloadOutlined, AimOutlined, InfoCircleOutlined, CheckCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { factorApi } from '../../api';

const { Text, Title } = Typography;
const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 3) => v != null ? (+v).toFixed(d) : '-';

// ─── 权重饼图 ──────────────────────────────────────────────────────────────────
function WeightPieChart({ weights, title }) {
  if (!weights || weights.length === 0) return null;
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: p => `${p.name}: ${(p.value * 100).toFixed(2)}%`,
    },
    legend: { orient: 'vertical', right: 10, top: 'center', type: 'scroll' },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['40%', '50%'],
      data: weights.map(w => ({ name: w.factorCode, value: +w.weight })),
      label: {
        formatter: p => `${p.name}\n${(p.value * 100).toFixed(1)}%`,
        fontSize: 11,
      },
      emphasis: {
        itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' },
      },
    }],
  };
  return (
    <Card title={title} size="small">
      <ReactECharts option={option} style={{ height: 260 }} notMerge={true} />
    </Card>
  );
}

// ─── 相关系数热力图 ────────────────────────────────────────────────────────────
function CorrHeatmapChart({ corrMatrix, factorCodes }) {
  if (!corrMatrix || !factorCodes) return null;

  const data = [];
  for (let i = 0; i < factorCodes.length; i++) {
    for (let j = 0; j < factorCodes.length; j++) {
      data.push([j, i, +(corrMatrix[i]?.[j] ?? 0).toFixed(4)]);
    }
  }

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      formatter: p => `${factorCodes[p.data[1]]} × ${factorCodes[p.data[0]]}: <b>${p.data[2]}</b>`,
    },
    grid: { top: 20, left: 80, right: 100, bottom: 40 },
    xAxis: {
      type: 'category', data: factorCodes, axisLabel: { rotate: 30, fontSize: 11 }, splitArea: { show: true },
    },
    yAxis: {
      type: 'category', data: factorCodes, axisLabel: { fontSize: 11 }, splitArea: { show: true },
    },
    visualMap: {
      min: -1, max: 1, calculable: true,
      orient: 'vertical', right: 0, top: 'center',
      inRange: { color: ['#3f8600', '#f5f5f5', '#cf1322'] },
    },
    series: [{
      type: 'heatmap',
      data,
      label: { show: factorCodes.length <= 8, formatter: p => p.data[2] },
    }],
  };

  return (
    <Card title="因子相关系数矩阵" size="small">
      <ReactECharts option={option} style={{ height: 280 }} notMerge={true} />
    </Card>
  );
}

// ─── 有效前沿 ──────────────────────────────────────────────────────────────────
function EfficientFrontierChart({ frontier }) {
  if (!frontier || frontier.length === 0) return null;

  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: p => `波动率: ${(p.data[0] * 100).toFixed(2)}%<br/>收益率: ${(p.data[1] * 100).toFixed(2)}%<br/>Sharpe: ${p.data[2]}`,
    },
    grid: { top: 20, left: 70, right: 20, bottom: 50 },
    xAxis: { 
      type: 'value', 
      name: '年化波动率', 
      nameLocation: 'middle',
      nameGap: 30,
      axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 11 },
    },
    yAxis: { 
      type: 'value', 
      name: '年化收益率', 
      nameLocation: 'middle',
      nameGap: 40,
      axisLabel: { formatter: v => `${(v * 100).toFixed(0)}%`, fontSize: 11 },
    },
    series: [{
      type: 'scatter',
      data: frontier.map(p => [p.volatility, p.return, p.sharpe]),
      symbolSize: 8,
      itemStyle: {
        color: p => {
          const sharpe = p.data[2];
          const maxS = Math.max(...frontier.map(f => f.sharpe));
          const ratio = Math.max(0, sharpe / maxS);
          const r = Math.round(207 * (1 - ratio) + 82 * ratio);
          const g = Math.round(19 * (1 - ratio) + 196 * ratio);
          return `rgb(${r},${g},26)`;
        },
      },
    }],
  };

  return (
    <Card title="有效前沿（Markowitz）" size="small">
      <ReactECharts option={option} style={{ height: 240 }} notMerge={true} />
    </Card>
  );
}

// ─── 主面板 ────────────────────────────────────────────────────────────────────
// 因子分类映射（用于检测同类因子）
const factorCategories = {
  // 动量类
  MOM5: 'momentum', MOM10: 'momentum', MOM20: 'momentum', MOM60: 'momentum',
  // 波动率类
  VOL5: 'volatility', VOL10: 'volatility', VOL20: 'volatility', VOL60: 'volatility',
  // 价值类
  PE_TTM: 'value', PB: 'value', PS_TTM: 'value', PCF_TTM: 'value', DIVIDEND_YIELD: 'value',
  // 质量类
  ROE: 'quality', ROA: 'quality', ROIC: 'quality', GROSS_MARGIN: 'quality', NET_MARGIN: 'quality',
  // 成长类
  REVENUE_GROWTH: 'growth', PROFIT_GROWTH: 'growth', EPS_GROWTH: 'growth',
};

// 分类中文名称
const categoryNames = {
  momentum: '动量（追涨杀跌）',
  volatility: '波动率',
  value: '价值（低估值）',
  quality: '质量（盈利能力）',
  growth: '成长',
  other: '其他',
};

const getFactorCategory = (code) => factorCategories[code] || 'other';

const checkFactorDiversity = (codes) => {
  const categories = codes.map(getFactorCategory);
  const uniqueCategories = [...new Set(categories)];
  
  // 情况1：所有因子都是同一类别
  if (uniqueCategories.length === 1 && codes.length > 1) {
    const cnName = categoryNames[uniqueCategories[0]] || uniqueCategories[0];
    return '您选择的因子均为同一类别（' + cnName + '），建议搭配不同类别的因子（如动量+价值+质量）以获得更好的分散效果';
  }
  
  // 情况2：某类因子占比过高（>=60%）
  const categoryCounts = {};
  categories.forEach(c => { categoryCounts[c] = (categoryCounts[c] || 0) + 1; });
  const maxCount = Math.max(...Object.values(categoryCounts));
  const maxCategory = Object.keys(categoryCounts).find(k => categoryCounts[k] === maxCount);
  
  if (maxCount >= 2 && maxCount / codes.length >= 0.6) {
    const cnName = categoryNames[maxCategory] || maxCategory;
    return '您选择的因子中，' + maxCount + '个属于' + cnName + '类，占比过高。建议搭配价值、质量等不同风格的因子以降低相关性';
  }
  
  return null;
};

export default function FactorWeightOptimizePanel({ defaultFactorCodes = [] }) {
  const [factorList, setFactorList] = useState([]);
  const [selectedCodes, setSelectedCodes] = useState(defaultFactorCodes);
  const [method, setMethod] = useState('MARKOWITZ');
  const [startDate, setStartDate] = useState('2025-01-01');
  const [endDate, setEndDate] = useState('2025-12-31');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [applyModalVisible, setApplyModalVisible] = useState(false);
  const [diversityWarningVisible, setDiversityWarningVisible] = useState(false);
  const [pendingOptimize, setPendingOptimize] = useState(false);

  useEffect(() => {
    factorApi.getAllDefinitions()
      .then(res => setFactorList(res.records || []))
      .catch(e => console.error('加载因子定义失败:', e));
  }, []);

  // 实际执行优化
  const doOptimize = () => {
    setLoading(true);
    setResult(null);
    console.log('开始优化:', { codes: selectedCodes, startDate, endDate, method });
    factorApi.weightOptimize(selectedCodes, startDate, endDate, method)
      .then(res => {
        console.log('优化结果:', res);
        // 后端直接返回数据对象，不是 {data: ...} 包装格式
        const data = res.data || res;
        if (data && data.weights) {
          setResult(data);
        } else {
          setError('返回数据为空或格式错误');
        }
      })
      .catch(e => {
        console.error('优化失败:', e);
        setError(e.response?.data?.message || e.message || '优化失败');
      })
      .finally(() => setLoading(false));
  };

  const handleOptimize = () => {
    if (selectedCodes.length < 2) { setError('至少选择2个因子'); return; }
    
    // 检查因子多样性
    const diversityWarning = checkFactorDiversity(selectedCodes);
    if (diversityWarning) {
      // 显示确认弹框
      setDiversityWarningVisible(true);
      setPendingOptimize(true);
    } else {
      setError(null);
      doOptimize();
    }
  };

  // 确认继续优化（因子相关性高时）
  const handleConfirmOptimize = () => {
    setDiversityWarningVisible(false);
    setError('⚠️ 警告：您选择的因子相关性较高，可能影响分散效果');
    doOptimize();
  };

  // 取消优化
  const handleCancelOptimize = () => {
    setDiversityWarningVisible(false);
    setPendingOptimize(false);
  };

  const methodLabels = { EQUAL: '等权', MARKOWITZ: '均值-方差（最大Sharpe）', RISK_PARITY: '风险平价' };

  // 生成配置JSON
  const generateConfig = () => {
    if (!result) return null;
    return {
      name: `多因子组合_${result.method}_${new Date().toISOString().slice(0, 10)}`,
      description: `基于${methodLabels[result.method]}优化的因子权重配置`,
      factors: result.weights.map(w => ({
        code: w.factorCode,
        weight: Math.round(w.weight * 100),
      })),
      expectedReturn: result.portfolioReturn,
      expectedVolatility: result.portfolioVolatility,
      sharpeRatio: result.sharpeRatio,
    };
  };

  // 评估是否建议转化为因子策略
  const evaluateStrategy = () => {
    if (!result) return null;
    const { sharpeRatio, portfolioReturn, portfolioVolatility } = result;
    
    // 评估标准
    if (sharpeRatio >= 1.5) {
      return { 
        level: 'excellent', 
        text: 'Sharpe比率优秀（≥1.5），建议立即转化为因子策略',
        color: '#52c41a',
        recommend: true
      };
    } else if (sharpeRatio >= 1.0) {
      return { 
        level: 'good', 
        text: 'Sharpe比率良好（≥1.0），建议转化为因子策略',
        color: '#1677ff',
        recommend: true
      };
    } else if (sharpeRatio >= 0.5) {
      return { 
        level: 'fair', 
        text: 'Sharpe比率一般（≥0.5），可考虑转化但需谨慎',
        color: '#fa8c16',
        recommend: true
      };
    } else {
      return { 
        level: 'poor', 
        text: 'Sharpe比率较低（<0.5），不建议转化为因子策略',
        color: '#cf1322',
        recommend: false
      };
    }
  };

  // 应用配置（跳转到选股页面）
  const applyConfig = () => {
    const config = generateConfig();
    if (config) {
      localStorage.setItem('factorWeightConfig', JSON.stringify(config));
      message.success('配置已保存，正在跳转到选股页面...');
      setTimeout(() => {
        window.location.href = '/screen';
      }, 1000);
    }
  };

  return (
    <Card
      title={
        <Space>
          <AimOutlined />
          因子组合权重优化
          <Tooltip
            placement="right"
            color="#fff"
            styles={{ body: { color: '#333', padding: 16, fontSize: 13 }, root: { maxWidth: 480 } }}
            title={
              <div>
                <div style={{ fontWeight: 'bold', marginBottom: 12, color: '#1677ff', fontSize: 14, borderBottom: '1px solid #eee', paddingBottom: 8 }}>
                  📊 如何解读优化结果
                </div>
                <div style={{ marginBottom: 10, color: '#333' }}>
                  <b style={{ color: '#1677ff' }}>权重分配</b><br/>
                  <span style={{ fontSize: 12, color: '#666' }}>各因子在组合中的资金占比。例如：MOM20权重40%，表示用40%资金按20日动量选股</span>
                </div>
                <div style={{ marginBottom: 10, color: '#333' }}>
                  <b style={{ color: '#cf1322' }}>相关系数矩阵</b><br/>
                  <span style={{ fontSize: 12, color: '#666' }}>🔴 红色=正相关（同涨同跌） 🟢 绿色=负相关（此消彼长）<br/>选择相关性低的因子组合，可以在不降低收益的情况下减少整体波动</span>
                </div>
                <div style={{ color: '#333' }}>
                  <b style={{ color: '#52c41a' }}>有效前沿</b><br/>
                  <span style={{ fontSize: 12, color: '#666' }}>横轴=风险（波动率），纵轴=收益（年化）<br/>曲线上的每个点代表一种权重配置，左上方的点代表「低风险高收益」的理想组合</span>
                </div>
              </div>
            }
          >
            <InfoCircleOutlined style={{ color: '#1677ff', cursor: 'help' }} />
          </Tooltip>
        </Space>
      }
      style={{ marginBottom: 16 }}
    >
      {/* 控制区 */}
      <Row gutter={8} style={{ marginBottom: 12 }} align="bottom">
        <Col span={8}>
          <Text strong>因子</Text>
          <Select
            mode="multiple"
            value={selectedCodes}
            onChange={setSelectedCodes}
            style={{ width: '100%', marginTop: 4 }}
            placeholder="选择 2-10 个因子"
            maxTagCount={4}
            showSearch
            optionFilterProp="label"
            options={factorList.map(f => ({ value: f.factorCode, label: `${f.factorCode} — ${f.factorName}` }))}
          />
        </Col>
        <Col span={5}>
          <Text strong>
            优化方法
            <Tooltip
              placement="top"
              color="#fff"
              styles={{ body: { color: '#333', padding: 12 }, root: { maxWidth: 360 } }}
              title={
                <div>
                  <div style={{ marginBottom: 4, color: '#333' }}>⚖️ <b>等权</b>：各因子权重相同（1/n）</div>
                  <div style={{ marginBottom: 4, color: '#333' }}>🎯 <b>均值-方差</b>：最大化夏普比率（收益/风险）</div>
                  <div style={{ color: '#333' }}>⚡ <b>风险平价</b>：各因子对组合风险贡献相等</div>
                </div>
              }
            >
              <InfoCircleOutlined style={{ color: '#1677ff', marginLeft: 4, cursor: 'help' }} />
            </Tooltip>
          </Text>
          <Select value={method} onChange={setMethod} style={{ width: '100%', marginTop: 4 }}>
            <Select.Option value="EQUAL">等权</Select.Option>
            <Select.Option value="MARKOWITZ">均值-方差（最大Sharpe）</Select.Option>
            <Select.Option value="RISK_PARITY">风险平价</Select.Option>
          </Select>
        </Col>
        <Col span={7}>
          <Text strong>日期范围</Text>
          <DatePicker.RangePicker
            value={[startDate ? dayjs(startDate) : null, endDate ? dayjs(endDate) : null]}
            onChange={dates => {
              if (dates && dates[0] && dates[1]) {
                setStartDate(dates[0].format('YYYY-MM-DD'));
                setEndDate(dates[1].format('YYYY-MM-DD'));
              }
            }}
            style={{ width: '100%', marginTop: 4 }}
          />
        </Col>
        <Col span={4}>
          <Button
            type="primary"
            block
            icon={<AimOutlined />}
            onClick={handleOptimize}
            loading={loading}
            style={{ marginTop: 20 }}
          >
            开始优化
          </Button>
        </Col>
      </Row>

      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 12 }} />}

      {/* 因子多样性警告弹框 */}
      <Modal
        title="⚠️ 因子相关性警告"
        open={diversityWarningVisible}
        onOk={handleConfirmOptimize}
        onCancel={handleCancelOptimize}
        okText="继续优化"
        cancelText="取消"
      >
        <Alert
          type="warning"
          showIcon
          message="因子选择建议"
          description={checkFactorDiversity(selectedCodes)}
          style={{ marginBottom: 12 }}
        />
        <div style={{ color: '#666', fontSize: 13 }}>
          选择相关性较高的因子可能导致组合分散效果不佳。建议：
          <ul style={{ marginTop: 8, paddingLeft: 20 }}>
            <li>搭配不同类别的因子（如动量+价值+质量）</li>
            <li>避免选择同类型的多个相似因子</li>
            <li>可参考相关系数矩阵选择低相关性组合</li>
          </ul>
        </div>
      </Modal>

      {/* 保存因子组合弹框 */}
      <Modal
        title="保存为因子组合"
        open={applyModalVisible}
        onOk={() => {
          const config = generateConfig();
          if (config) {
            // 保存到localStorage，供选股页面使用
            const savedCombos = JSON.parse(localStorage.getItem('factorCombos') || '[]');
            savedCombos.push({
              ...config,
              id: Date.now(),
              createTime: new Date().toISOString(),
            });
            localStorage.setItem('factorCombos', JSON.stringify(savedCombos));
            message.success('因子组合已保存');
            setApplyModalVisible(false);
          }
        }}
        onCancel={() => setApplyModalVisible(false)}
        okText="保存"
        cancelText="取消"
      >
        {result && (
          <div>
            <Alert
              type="info"
              showIcon
              message={evaluateStrategy()?.text}
              style={{ marginBottom: 16 }}
            />
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontWeight: 'bold', marginBottom: 8 }}>组合名称</div>
              <div style={{ padding: 8, background: '#f5f5f5', borderRadius: 4 }}>
                多因子组合_{result.method}_{new Date().toISOString().slice(0, 10)}
              </div>
            </div>
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontWeight: 'bold', marginBottom: 8 }}>因子权重配置</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {result.weights.map(w => (
                  <Tag key={w.factorCode} color="blue">
                    {w.factorCode}: {(w.weight * 100).toFixed(1)}%
                  </Tag>
                ))}
              </div>
            </div>
            <div style={{ color: '#888', fontSize: 12 }}>
              保存后可在选股页面的「我的组合」中使用此配置
            </div>
          </div>
        )}
      </Modal>

      <Spin spinning={loading} tip="正在优化因子权重...">
        {result && (
          <>
            {/* 策略评估提示 */}
            {(() => {
              const evalResult = evaluateStrategy();
              if (!evalResult) return null;
              return (
                <Alert
                  type={evalResult.recommend ? 'info' : 'warning'}
                  showIcon
                  style={{ marginBottom: 12 }}
                  message={
                    <Space>
                      <span style={{ color: evalResult.color, fontWeight: 'bold' }}>{evalResult.text}</span>
                      {evalResult.recommend && (
                        <Button type="primary" size="small" onClick={() => setApplyModalVisible(true)}>
                          保存为因子组合
                        </Button>
                      )}
                    </Space>
                  }
                />
              );
            })()}

            {/* 预期指标 */}
            <Row gutter={12} style={{ marginBottom: 12 }}>
              <Col span={5}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 11, color: '#888' }}>优化方法</div>
                  <Tag color="blue" style={{ marginTop: 4 }}>{methodLabels[result.method] || result.method}</Tag>
                </Card>
              </Col>
              <Col span={5}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>
                    预期年化收益
                    <Tooltip
                      placement="top"
                      color="#fff"
                      styles={{ body: { color: '#333', padding: 12 }, root: { maxWidth: 320 } }}
                      title={
                        <div style={{ color: '#333' }}>
                          <div style={{ fontWeight: 'bold', marginBottom: 4 }}>📈 预期年化收益</div>
                          <div style={{ fontSize: 12 }}>基于历史数据回测，该因子组合在未来一年的预期收益率。注意：历史表现不代表未来收益。</div>
                        </div>
                      }
                    >
                      <InfoCircleOutlined style={{ color: '#1677ff', marginLeft: 4, cursor: 'help', fontSize: 10 }} />
                    </Tooltip>
                  </div>
                  <div style={{ fontSize: 16, fontWeight: 'bold', color: +result.portfolioReturn > 0 ? '#cf1322' : '#3f8600' }}>
                    {fmtPct(result.portfolioReturn)}
                  </div>
                </Card>
              </Col>
              <Col span={5}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>
                    预期年化波动率
                    <Tooltip
                      placement="top"
                      color="#fff"
                      styles={{ body: { color: '#333', padding: 12 }, root: { maxWidth: 320 } }}
                      title={
                        <div style={{ color: '#333' }}>
                          <div style={{ fontWeight: 'bold', marginBottom: 4 }}>📊 预期年化波动率</div>
                          <div style={{ fontSize: 12 }}>衡量组合收益波动的剧烈程度。波动率越高，短期盈亏波动越大。一般而言，波动率&lt;20%为低风险，20%-30%为中风险，&gt;30%为高风险。</div>
                        </div>
                      }
                    >
                      <InfoCircleOutlined style={{ color: '#1677ff', marginLeft: 4, cursor: 'help', fontSize: 10 }} />
                    </Tooltip>
                  </div>
                  <div style={{ fontSize: 16, fontWeight: 'bold', color: '#fa8c16' }}>
                    {fmtPct(result.portfolioVolatility)}
                  </div>
                </Card>
              </Col>
              <Col span={5}>
                <Card size="small" style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>
                    预期Sharpe比率
                    <Tooltip
                      placement="top"
                      color="#fff"
                      styles={{ body: { color: '#333', padding: 12 }, root: { maxWidth: 320 } }}
                      title={
                        <div style={{ color: '#333' }}>
                          <div style={{ fontWeight: 'bold', marginBottom: 4 }}>🎯 预期Sharpe比率</div>
                          <div style={{ fontSize: 12 }}>风险调整后收益指标 = 年化收益 / 年化波动率。衡量每承担一份风险所获得的超额收益。</div>
                          <div style={{ fontSize: 12, marginTop: 4, borderTop: '1px solid #eee', paddingTop: 4 }}>
                            <span style={{ color: '#52c41a' }}>≥1.5 优秀</span> | 
                            <span style={{ color: '#1677ff' }}> ≥1.0 良好</span> | 
                            <span style={{ color: '#fa8c16' }}> ≥0.5 一般</span> | 
                            <span style={{ color: '#cf1322' }}> &lt;0.5 较差</span>
                          </div>
                        </div>
                      }
                    >
                      <InfoCircleOutlined style={{ color: '#1677ff', marginLeft: 4, cursor: 'help', fontSize: 10 }} />
                    </Tooltip>
                  </div>
                  <div style={{ fontSize: 16, fontWeight: 'bold', color: +result.sharpeRatio > 1 ? '#52c41a' : '#262626' }}>
                    {fmt(result.sharpeRatio)}
                  </div>
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small" style={{ textAlign: 'center', height: '100%' }}>
                  <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>操作</div>
                  <Space direction="vertical" size="small" style={{ width: '100%' }}>
                    <Button type="primary" size="small" block icon={<CheckCircleOutlined />} onClick={applyConfig}>
                      应用配置
                    </Button>
                  </Space>
                </Card>
              </Col>
            </Row>



            <Row gutter={12}>
              {/* 权重饼图 */}
              <Col span={8}>
                <WeightPieChart weights={result.weights} title={`权重分配（${methodLabels[result.method] || result.method}）`} />
              </Col>

              {/* 权重明细表 */}
              <Col span={8}>
                <Card title="权重明细" size="small">
                  <Table
                    dataSource={result.weights}
                    rowKey="factorCode"
                    size="small"
                    pagination={false}
                    columns={[
                      { title: '因子', dataIndex: 'factorCode', key: 'code', render: v => <Tag color="geekblue">{v}</Tag> },
                      {
                        title: '权重', dataIndex: 'weight', key: 'weight',
                        render: v => (
                          <Space>
                            <span style={{ fontWeight: 600 }}>{fmtPct(v)}</span>
                            <div style={{ display: 'inline-block', width: +(v * 80).toFixed(0), height: 8, background: '#1677ff', borderRadius: 4, verticalAlign: 'middle' }} />
                          </Space>
                        ),
                      },
                      { title: '年化收益', dataIndex: 'meanReturn', key: 'ret', render: v => <span style={{ color: +v > 0 ? '#cf1322' : '#3f8600' }}>{fmtPct(v)}</span> },
                      { title: '波动率', dataIndex: 'volatility', key: 'vol', render: v => fmtPct(v) },
                    ]}
                  />
                </Card>
              </Col>

              {/* 相关系数 */}
              <Col span={8}>
                <Tooltip title="红色=正相关（同向波动），绿色=负相关（对冲效果）。低相关性因子组合可降低整体风险">
                  <CorrHeatmapChart corrMatrix={result.correlationMatrix} factorCodes={result.factorCodes} />
                </Tooltip>
              </Col>
            </Row>

            {/* 有效前沿（仅 Markowitz 时显示） */}
            {result.efficientFrontier && (
              <div style={{ marginTop: 12 }}>
                <Tooltip title="有效前沿展示了所有可能的风险-收益组合。横轴是风险（波动率），纵轴是收益。曲线左上方的点代表更优的配置">
                  <EfficientFrontierChart frontier={result.efficientFrontier} />
                </Tooltip>
              </div>
            )}
          </>
        )}

        {!result && !loading && (
          <div style={{ textAlign: 'center', padding: '24px 0', color: '#8c8c8c' }}>
            <BarChartOutlined style={{ fontSize: 36, marginBottom: 8 }} />
            <div>选择多个因子，选择优化方法，点击「开始优化」计算最优因子权重组合</div>
          </div>
        )}
      </Spin>
    </Card>
  );
}
