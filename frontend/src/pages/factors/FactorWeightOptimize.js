import React, { useState, useEffect } from 'react';
import dayjs from 'dayjs';
import { Card, Row, Col, Select, Button, Tag, Spin, Alert, Space, Typography, Table, Tooltip, Divider, Statistic, DatePicker, Modal } from 'antd';
import { message } from '../../utils/messageUtil';
import {
  BarChartOutlined, ReloadOutlined, AimOutlined, InfoCircleOutlined, CheckCircleOutlined,
} from '@ant-design/icons';
import ReactECharts from '../../components/LazyECharts';
import { factorApi } from '../../api';
import { CATEGORY_LABELS as categoryNames } from './constants';

const { Text, Title } = Typography;
const fmtPct = (v, d = 2) => v != null ? `${(+v * 100).toFixed(d)}%` : '-';
const fmt = (v, d = 3) => v != null ? (+v).toFixed(d) : '-';

// 不适合参与权重优化的因子（分类/方向型因子，factor_val 为离散值）
const WEIGHT_OPTIMIZE_UNSUITABLE = new Set([
  // 已无分类/方向型因子需要排除，如有新增离散值因子可在此添加
]);

// ─── 权重饼图 ──────────────────────────────────────────────────────────────────
function WeightPieChart({ weights, title }) {
  if (!weights || weights.length === 0) return null;
  const option = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: p => `${p.name}: ${(p.value * 100).toFixed(2)}%`,
    },
    legend: { orient: 'horizontal', right: 0, bottom: 10, type: 'scroll' },
    series: [{
      type: 'pie',
      radius: ['40%', '65%'],
      center: ['50%', '34%'],
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
      formatter: params => {
        const idx = params.dataIndex;
        const fp = frontier[idx];
        if (!fp) return '';
        return `波动率: ${(fp.volatility * 100).toFixed(2)}%<br/>收益率: ${(fp.return * 100).toFixed(2)}%<br/>Sharpe: ${fp.sharpe}`;
      },
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
    series: [
      // 蓝线：视觉展示有效前沿弧线
      {
        type: 'line',
        data: frontier.map(p => [p.volatility, p.return]),
        smooth: true,
        symbol: 'none',
        lineStyle: { color: '#1677ff', width: 2, opacity: 0.7 },
      },
      // 散点：负责 tooltip 触发（圆点始终可见）
      {
        type: 'scatter',
        data: frontier.map((p, i) => [p.volatility, p.return, p.sharpe]),
        symbolSize: 10,
        itemStyle: {
          color: params => {
            // params.data = [volatility, return, sharpe]
            const sharpe = (params.data && params.data[2]) || 0;
            const allSharpe = frontier.map(f => f.sharpe);
            const minS = Math.min(...allSharpe);
            const maxS = Math.max(...allSharpe);
            const range = maxS - minS || 0.001;
            // ratio: 0(最差) → 1(最优)，即使全为负也能区分色差
            const ratio = (sharpe - minS) / range;
            // 红(207,19,26) → 绿(82,196,26)
            const r = Math.round(207 * (1 - ratio) + 82 * ratio);
            const g = Math.round(19 * (1 - ratio) + 196 * ratio);
            return `rgb(${r},${g},26)`;
          },
        },
        tooltip: {
          trigger: 'item',
          formatter: p => {
            const idx = p.dataIndex;
            const fp = frontier[idx];
            if (!fp) return '';
            return `波动率: ${(fp.volatility * 100).toFixed(2)}%<br/>收益率: ${(fp.return * 100).toFixed(2)}%<br/>Sharpe: ${fp.sharpe}`;
          },
        },
      },
    ],
  };

  // 同质化检测：前沿点全部挤在一起 → 因子差异太小，优化无意义
  const vols = frontier.map(f => f.volatility);
  const rets = frontier.map(f => f.return);
  const volRange = Math.max(...vols) - Math.min(...vols);
  const retRange = Math.max(...rets) - Math.min(...rets);
  const avgVol = vols.reduce((a, b) => a + b, 0) / vols.length || 0.0001;
  const avgRet = Math.max(Math.abs(rets.reduce((a, b) => a + b, 0) / rets.length), 0.0001);

  // 波动率跨度 < 均值的 5% 且 收益率跨度 < 均值的 10% → 判断为过聚类
  const isClustered = volRange / avgVol < 0.05 && retRange / avgRet < 0.10;

  return (
    <Card
      title={
        <span>
          有效前沿（Markowitz）
          <Tooltip title='有效前沿是所有"最优"投资组合的集合——在相同风险下收益最高，相同收益下风险最低。横轴为年化波动率（风险），纵轴为年化收益率，上边界弧线上的每个点代表给定风险水平下的最高收益组合。弧线内部任何点都不值得持有。'>
            <InfoCircleOutlined style={{ color: '#8c8c8c', marginLeft: 6, cursor: 'help' }} />
          </Tooltip>
        </span>
      }
      size="small"
    >
      {isClustered && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 8, fontSize: 12 }}
          message="因子高度同质化"
          description={
            <span>
              所有前沿点高度聚集（波动率跨度 {(volRange * 100).toFixed(2)}%，
              收益跨度 {(retRange * 100).toFixed(3)}%），
              优化结果几乎没有区分度。<b>建议更换因子组合</b>——选择不同类别、低相关性的因子，
              让有效前沿拉开为明显的左上凸弧线。
            </span>
          }
        />
      )}
      <ReactECharts option={option} style={{ height: 240 }} notMerge={true} />
    </Card>
  );
}

// ─── 主面板 ────────────────────────────────────────────────────────────────────
// 分类中文名称统一从 constants.js 导入（别名 categoryNames）

// 通过因子列表获取因子的 DB 分类
const getFactorCategory = (code, factorList) => {
  const f = factorList.find(f => f.factorCode === code);
  return f?.category || null;
};

const checkFactorDiversity = (codes, factorList) => {
  const categories = codes.map(c => getFactorCategory(c, factorList)).filter(Boolean);
  if (categories.length < 2) return null;
  
  const uniqueCategories = [...new Set(categories)];
  
  // 情况1：所有因子都是同一类别
  if (uniqueCategories.length === 1 && codes.length > 1) {
    const cnName = categoryNames[uniqueCategories[0]] || uniqueCategories[0];
    return `您选择的因子均为同一类别（${cnName}），建议搭配不同类别的因子（如动量+价值+质量）以获得更好的分散效果`;
  }
  
  // 情况2：某类因子占比过高（>=60%）
  const categoryCounts = {};
  categories.forEach(c => { categoryCounts[c] = (categoryCounts[c] || 0) + 1; });
  const maxCount = Math.max(...Object.values(categoryCounts));
  const maxCategory = Object.keys(categoryCounts).find(k => categoryCounts[k] === maxCount);
  
  if (maxCount >= 2 && maxCount / codes.length >= 0.6) {
    const cnName = categoryNames[maxCategory] || maxCategory;
    return `${maxCount}个因子属于${cnName}类，占比过高。建议搭配不同风格的因子以降低相关性`;
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
    const diversityWarning = checkFactorDiversity(selectedCodes, factorList);
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
            options={factorList.map(f => ({
              value: f.factorCode,
              label: `${f.factorCode} — ${f.factorName}`,
              searchText: `${f.factorCode} ${f.factorName} ${categoryNames[f.category] || ''}`,
              disabled: WEIGHT_OPTIMIZE_UNSUITABLE.has(f.factorCode),
            }))}
            optionRender={(opt) => {
              const f = factorList.find(ff => ff.factorCode === opt.value);
              const cat = f?.category ? categoryNames[f.category] || f.category : null;
              const unsuitable = WEIGHT_OPTIMIZE_UNSUITABLE.has(opt.value);
              return (
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, opacity: unsuitable ? 0.45 : 1 }}>
                  <span style={{ color: unsuitable ? '#999' : undefined }}>
                    <b>{opt.data.value}</b> — {f?.factorName || ''}
                  </span>
                  <Space size={4}>
                    {cat && <Tag style={{ marginLeft: 4, fontSize: 11 }}>{cat}</Tag>}
                    {unsuitable && <Tag color="default" style={{ fontSize: 10 }}>不适合</Tag>}
                  </Space>
                </div>
              );
            }}
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
                <div style={{ color: '#333' }}>
                  <div style={{ marginBottom: 4 }}>⚖️ <b>等权</b>：各因子权重相同（1/n）</div>
                  <div style={{ marginBottom: 4 }}>
                    🎯 <b>均值-方差（最大Sharpe）</b><br/>
                    <span style={{ fontSize: 11, color: '#666' }}>
                      公式：<code>Sharpe = (E[R] - r<sub>f</sub>) / σ</code><br/>
                      E[R]=组合年化收益，r<sub>f</sub>=无风险利率(3%)，σ=年化波动率<br/>
                      目标：找到让 Sharpe 最大的权重配比，即每担 1 份风险能获得最多超额收益
                    </span>
                  </div>
                  <div>⚡ <b>风险平价</b>：各因子对组合风险贡献相等</div>
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

      {/* 跳因子警告 */}
      {result?.warnings && result.warnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message="部分因子被跳过"
          description={result.warnings[0]}
          style={{ marginBottom: 12 }}
        />
      )}

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
          description={checkFactorDiversity(selectedCodes, factorList)}
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
                          <div style={{ fontWeight: 'bold', marginBottom: 6 }}>📈 预期年化收益</div>
                          <div style={{ fontSize: 12, marginBottom: 6 }}>
                            公式：<code>年化收益 = Σ(权重_i × 因子日均收益_i) × 252</code>
                          </div>
                          <div style={{ fontSize: 12, borderTop: '1px solid #eee', paddingTop: 6, lineHeight: 1.8 }}>
                            将各因子的加权日均收益求和后折算为年化（×252交易日）。<br/>
                            日均收益：财务因子用原始 rank 中位数，其他因子用 rank 差分值。<br/>
                            <span style={{ color: '#888' }}>注意：历史回测结果不代表未来表现。</span>
                          </div>
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
                          <div style={{ fontWeight: 'bold', marginBottom: 6 }}>📊 预期年化波动率</div>
                          <div style={{ fontSize: 12, marginBottom: 6 }}>
                            公式：<code>波动率 = √(w<sup>T</sup> × 日协方差矩阵 × w × 252)</code>
                          </div>
                          <div style={{ fontSize: 12, borderTop: '1px solid #eee', paddingTop: 6, lineHeight: 1.8 }}>
                            权重向量 w 通过因子日收益协方差矩阵计算组合方差，<br/>
                            再年化（×252）后开平方根得到年化标准差。<br/>
                            <span style={{ color: '#888' }}>衡量组合收益的波动剧烈程度，越低越稳定。</span>
                          </div>
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
                      styles={{ body: { color: '#333', padding: 12 }, root: { maxWidth: 380 } }}
                      title={
                        <div style={{ color: '#333' }}>
                          <div style={{ fontWeight: 'bold', marginBottom: 4 }}>🎯 Sharpe 比率计算</div>
                          <div style={{ fontSize: 12, marginBottom: 8 }}>
                            公式：<code>Sharpe = (年化收益 − 无风险利率) ÷ 年化波动率</code>
                          </div>
                          <div style={{ fontSize: 12, borderTop: '1px solid #eee', paddingTop: 6 }}>
                            无风险利率 = 3%（年化）<br/>
                            当前组合年化收益 = <b>{(result && (result.portfolioReturn * 100).toFixed(2))}%</b><br/>
                            当前组合年化波动率 = <b>{(result && (result.portfolioVolatility * 100).toFixed(2))}%</b><br/>
                            <span style={{ color: '#1677ff', fontWeight: 'bold' }}>
                              → Sharpe = ({((result && result.portfolioReturn * 100) || 0).toFixed(2)}% − 3%) ÷ {((result && result.portfolioVolatility * 100) || 0).toFixed(2)}% = <b>{result?.sharpe}</b>
                            </span>
                          </div>
                          <div style={{ fontSize: 11, marginTop: 6, color: '#888' }}>
                            Sharpe 越高，每承担 1 份风险获得的超额收益越多
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
                        render: v => <span style={{ fontWeight: 600 }}>{fmtPct(v)}</span>,
                      },
                      {
                        title: (
                          <Tooltip title="该因子在历史期间的年化平均收益率。基于每日 rank_value 差分中位数计算，反映因子选股能力">
                            年化收益 <InfoCircleOutlined style={{ color: '#1677ff', fontSize: 10 }} />
                          </Tooltip>
                        ),
                        dataIndex: 'meanReturn', key: 'ret',
                        render: v => <span style={{ color: +v > 0 ? '#cf1322' : '#3f8600' }}>{fmtPct(v)}</span>,
                      },
                      {
                        title: (
                          <Tooltip title="该因子收益的年化标准差，衡量因子表现的稳定性。波动率越低，因子选股效果越稳定">
                            波动率 <InfoCircleOutlined style={{ color: '#1677ff', fontSize: 10 }} />
                          </Tooltip>
                        ),
                        dataIndex: 'volatility', key: 'vol',
                        render: v => fmtPct(v),
                      },
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
