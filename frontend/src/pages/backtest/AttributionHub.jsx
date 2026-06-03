import React, { useEffect, useState, useMemo } from 'react';
import {
  Card, Row, Col, Typography, Tag, Table, Alert, Spin, Divider, Statistic,
  Badge, Tooltip, Modal, Button, Space, Empty, Result,
} from 'antd';
import {
  InfoCircleOutlined, RiseOutlined, FallOutlined, BarChartOutlined,
  PieChartOutlined, QuestionCircleOutlined, UpOutlined, DownOutlined,
  CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined,
  ClockCircleOutlined, DollarOutlined, FundOutlined,
  LineChartOutlined, AlertOutlined, DashboardOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { backtestApi } from '../../api';

const { Title, Text } = Typography;

// ─── 工具函数 ────────────────────────────────────────────────────────────────
const fmtPct = (v) => {
  if (v == null || isNaN(v)) return '-';
  return (v * 100).toFixed(2) + '%';
};
const fmt = (v, d = 2) => (v != null && !isNaN(v) ? v.toFixed(d) : '-');
const fmtMoney = (v) => {
  if (v == null || isNaN(v)) return '-';
  const abs = Math.abs(v);
  if (abs >= 1e8) return (v / 1e8).toFixed(2) + '亿';
  if (abs >= 1e4) return (v / 1e4).toFixed(2) + '万';
  return v.toFixed(2);
};

// ─── 颜色常量（中国股市红涨绿跌 + 通用） ─────────────────────────────────────
const RED = '#cf1322', GREEN = '#3f8600';
const COLOR_UP = RED, COLOR_DOWN = GREEN;

// ══════════════════════════════════════════════════════════════════════════════
// 归因分析 Hub（主入口）
// ══════════════════════════════════════════════════════════════════════════════
export default function AttributionHub({ taskId }) {
  const [strategy, setStrategy] = useState(null);
  const [tradeAnalysis, setTradeAnalysis] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [brinsonOpen, setBrinsonOpen] = useState(false);
  const [factorOpen, setFactorOpen] = useState(false);
  const [ff3Open, setFF3Open] = useState(false);
  const [alphaMonitorOpen, setAlphaMonitorOpen] = useState(false);
  const [styleMonitorOpen, setStyleMonitorOpen] = useState(false);

  useEffect(() => {
    if (!taskId) return;
    setLoading(true);
    setError(null);

    Promise.all([
      backtestApi.getAttributionStrategy(taskId).catch(err => {
        console.warn('归因策略推荐加载失败:', err);
        return null;
      }),
      backtestApi.getTradeAnalysis(taskId).catch(err => {
        console.warn('交易分析加载失败:', err);
        return null;
      }),
    ]).then(([strategyRes, tradeRes]) => {
      setStrategy(strategyRes);
      setTradeAnalysis(tradeRes);
    }).catch(err => {
      setError(err.message || '加载失败');
    }).finally(() => setLoading(false));
  }, [taskId]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 40 }}>
        <Spin size="large" tip="加载归因分析..." />
        <div style={{ marginTop: 8, color: '#999', fontSize: 13 }}>正在计算 Brinson + 因子归因 + 交易分析...</div>
      </div>
    );
  }

  if (error && !strategy && !tradeAnalysis) {
    return <Alert type="error" showIcon message="归因分析加载失败" description={error} />;
  }

  const modelRecommendation = strategy?.recommendedModel;

  return (
    <div>
      {/* ── 1. 综合归因结论（最外层，综合组合归因 + 交易诊断） ── */}
      <GeneralConclusion strategy={strategy} recommendation={modelRecommendation} tradeAnalysis={tradeAnalysis} />

      {/* ── 2. 归因模型对比（仅 Brinson / Factor / FF3） ── */}
      <ModelComparisonCard strategy={strategy} />

      {/* ── 3. 详细归因分析（弹窗入口） ── */}
      <Card size="small" title="详细归因分析" style={{ marginTop: 16 }}>
        <Space wrap>
          <Button type="primary" ghost icon={<PieChartOutlined />}
            onClick={() => setBrinsonOpen(true)}
            disabled={!strategy?.modelComparison?.BRINSON?.available}
          >
            Brinson 行业归因
            {strategy?.modelComparison?.BRINSON?.available
              ? ((strategy.modelComparison.BRINSON.explanationRatio ?? -1) >= 0
                ? <Tag color="blue" style={{marginLeft:8,fontSize:10}}>解释力 {fmtPct(strategy.modelComparison.BRINSON.explanationRatio)}</Tag>
                : <Tag color="warning" style={{marginLeft:8,fontSize:10}}>数据异常</Tag>)
              : <Tag color="default" style={{marginLeft:8,fontSize:10}}>不可用</Tag>
            }
          </Button>
          <Button type="primary" ghost icon={<BarChartOutlined />}
            onClick={() => setFactorOpen(true)}
            disabled={!strategy?.modelComparison?.FACTOR?.available}
          >
            因子风格归因
            {strategy?.modelComparison?.FACTOR?.available
              ? ((strategy.modelComparison.FACTOR.explanationRatio ?? -1) >= 0
                ? <Tag color="green" style={{marginLeft:8,fontSize:10}}>解释力 {fmtPct(strategy.modelComparison.FACTOR.explanationRatio)}</Tag>
                : <Tag color="warning" style={{marginLeft:8,fontSize:10}}>数据异常</Tag>)
              : <Tag color="default" style={{marginLeft:8,fontSize:10}}>不可用</Tag>
            }
          </Button>
          <Button type="primary" ghost icon={<FundOutlined />}
            onClick={() => setFF3Open(true)}
            disabled={!strategy?.modelComparison?.FF3?.available}
          >
            FF3 风格归因
            {strategy?.modelComparison?.FF3?.available
              ? ((strategy.modelComparison.FF3.explanationRatio ?? -1) >= 0
                ? <Tag color="purple" style={{marginLeft:8,fontSize:10}}>解释力 {fmtPct(strategy.modelComparison.FF3.explanationRatio)}</Tag>
                : <Tag color="warning" style={{marginLeft:8,fontSize:10}}>数据异常</Tag>)
              : <Tag color="default" style={{marginLeft:8,fontSize:10}}>不可用</Tag>
            }
            <Tooltip overlayStyle={{maxWidth:400}} title="Fama-French 三因子模型：用市场(MKT)、规模(SMB)、价值(HML)三个标准因子回归组合超额收益，诊断策略风格暴露。">
              <QuestionCircleOutlined style={{marginLeft:4,color:'#8c8c8c',cursor:'help'}} />
            </Tooltip>
          </Button>
        </Space>
      </Card>

      {/* ════════════════════════════════════════════════════════════════
          交易诊断（与上面的组合归因分开，单独一组）
          ════════════════════════════════════════════════════════════════ */}
      <Divider orientation="left" plain style={{ marginTop: 24, marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 13 }}>交易诊断</Text>
      </Divider>

      {/* ── 4. 持仓周期分析 ── */}
      {tradeAnalysis?.holdingPeriods && tradeAnalysis.holdingPeriods.length > 0 && (
        <HoldingPeriodPanel periods={tradeAnalysis.holdingPeriods} totalTrades={tradeAnalysis.totalPairedTrades} />
      )}

      {/* ── 5. 关键交易分析 ── */}
      {tradeAnalysis?.tradeAttribution && tradeAnalysis.tradeAttribution.totalTrades > 0 && (
        <TradeAttributionPanel attr={tradeAnalysis.tradeAttribution} />
      )}

      {/* 交易分析不可用时：给出明确提示 */}
      {!loading && tradeAnalysis && tradeAnalysis.error && (
        <Card size="small" style={{marginBottom:16}}>
          <Result status="info" title="交易分析数据不可用"
            subTitle={tradeAnalysis.error} />
        </Card>
      )}
      {!loading && tradeAnalysis && !tradeAnalysis.error && !tradeAnalysis.holdingPeriods && !tradeAnalysis.tradeAttribution && (
        <Card size="small" style={{marginBottom:16}}>
          <Result status="info" title="暂无交易分析数据"
            subTitle="该回测任务无交易日志数据（tradeLog 为空），无法生成持仓周期分析和关键交易透视。请联系策略开发人员检查 tradeLog 记录。" />
        </Card>
      )}

      {/* ════════════════════════════════════════════════════════════════
          策略监控
          ════════════════════════════════════════════════════════════════ */}
      <Divider orientation="left" plain style={{ marginTop: 24, marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 13 }}>策略监控</Text>
      </Divider>

      <Card size="small" title={<><DashboardOutlined style={{marginRight:6}}/>Alpha 与风格监控</>}>
        <Space wrap>
          <Button icon={<LineChartOutlined />}
            onClick={() => setAlphaMonitorOpen(true)}
          >
            Alpha 滚动监控
            <Tooltip overlayStyle={{maxWidth:680}} title={
              <div style={{maxWidth:680,maxHeight:520,overflowY:'auto',lineHeight:1.7,fontSize:13}}>
                <p style={{margin:0,fontWeight:600,fontSize:14}}>Alpha 滚动窗口监控</p>
                <p style={{margin:'6px 0'}}><b>用途：</b>追踪策略「选股能力」是否随时间退化。当因子被市场发现、风格切换或过拟合失效时，Alpha 会从高位持续下滑——滚动监控能最早发现这一趋势。</p>
                <p style={{margin:'8px 0 4px',fontWeight:600,color:'#1677ff'}}>为什么多窗口能预警衰减？</p>
                <p style={{margin:'2px 0'}}>短期窗口反应快但噪声大，长期窗口最稳定但迟钝。当三条线出现"分叉"——短期线大幅低于长期线——说明策略 Alpha 正在衰减：</p>
                <table style={{width:'100%',borderCollapse:'collapse',fontSize:12,lineHeight:1.5,marginTop:6}}>
                  <thead><tr style={{background:'#fafafa'}}>
                    <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>窗口</th>
                    <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>特性</th>
                    <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>预警角色</th>
                  </tr></thead>
                  <tbody>
                    <tr><td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#91caff',fontWeight:600}}>60天</td>
                      <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>反应最快，噪声最大</td>
                      <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#ff4d4f'}}>最早发现异常（先锋）</td></tr>
                    <tr><td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#1677ff',fontWeight:600}}>120天</td>
                      <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>平衡，兼顾灵敏度与稳定性</td>
                      <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#fa8c16'}}>确认趋势（中军）</td></tr>
                    <tr><td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#cf1322',fontWeight:600}}>252天</td>
                      <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>最稳定，历史数据稀释新变化</td>
                      <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>长期基准（参考线）</td></tr>
                  </tbody>
                </table>
                <p style={{margin:'8px 0 4px',fontWeight:600,color:'#fa8c16'}}>计算方式</p>
                <p style={{margin:'2px 0'}}>每个窗口内对「策略超额收益 ~ 策略自身因子」做 OLS 回归，截距项即为该窗口 Alpha。每天向前推一天，形成滚动序列。衰减预警：近 25% 期 Alpha 均值较历史中位数下降超 50%。</p>
                <p style={{margin:'8px 0 0',color:'#8c8c8c',fontStyle:'italic'}}>注意：当前回测仅约 80~90 个交易日，只有 60 天线有效。120 天和 252 天线需 ≥1 年数据才能启动完整对比。</p>
              </div>
            }>
              <QuestionCircleOutlined style={{marginLeft:4,color:'#8c8c8c',cursor:'help'}} />
            </Tooltip>
          </Button>
          <Button icon={<AlertOutlined />}
            onClick={() => setStyleMonitorOpen(true)}
          >
            风格β 漂移监控
            <Tooltip overlayStyle={{maxWidth:400}} title="FF3 滚动窗口 SMB/HML beta 序列分析 + 风格漂移预警">
              <QuestionCircleOutlined style={{marginLeft:4,color:'#8c8c8c',cursor:'help'}} />
            </Tooltip>
          </Button>
        </Space>
      </Card>

      {/* ── 归因详情弹窗 ── */}
      <Modal title={<>
        <PieChartOutlined style={{marginRight:8}}/>Brinson 行业归因详情
        <Tooltip overlayStyle={{ maxWidth: 520 }} title={
          <div style={{maxWidth:520,lineHeight:1.8}}>
            <p style={{margin:0,fontWeight:600}}>Brinson 归因模型</p>
            <p style={{margin:'4px 0'}}><b>思路：</b>将策略超额收益分解为「行业配置」（是否选对行业）+「行业内选股」（是否选对个股）+「交互效应」（两者是否同向）。适合行业集中度高、持仓周期长的低频策略。</p>
            <p style={{margin:'4px 0'}}><b>核心指标：</b></p>
            <ul style={{margin:'2px 0',paddingLeft:16}}>
              <li><b>配置效应</b> = Σ(策略行业权重−基准权重)×(行业基准收益−基准总收益)</li>
              <li><b>选股效应</b> = Σ 基准权重×(策略行业内收益−行业基准收益)</li>
              <li><b>交互效应</b> = Σ(权重差)×(选股收益差)</li>
              <li><b>解释力</b> = 1−|残差|/|超额|，衡量三效应能否解释超额收益</li>
            </ul>
          </div>
        }>
          <QuestionCircleOutlined style={{marginLeft:6,fontSize:14,color:'#8c8c8c',cursor:'help'}} />
        </Tooltip>
      </>}
        open={brinsonOpen} onCancel={() => setBrinsonOpen(false)}
        width={1100} footer={null} destroyOnClose
        style={{top:20}}
        styles={{ body: { maxHeight: 'calc(90vh - 120px)', overflowY: 'auto', overflowX: 'hidden', paddingRight: 4 } }}
      >
        <BrinsonDetail taskId={taskId} />
      </Modal>

      {/* 因子风格归因 弹窗 */}
      <Modal title={<>
        <BarChartOutlined style={{marginRight:8}}/>因子风格归因详情
        <Tooltip overlayStyle={{ maxWidth: 560 }} title={
          <div style={{maxWidth:560,lineHeight:1.8}}>
            <p style={{margin:0,fontWeight:600}}>因子风格归因模型</p>
            <p style={{margin:'4px 0'}}><b>思路：</b>将策略超额收益对动量/波动率/市值/换手率四个风格因子做多元回归（OLS），拆解为各因子的 β 暴露 × 因子收益。衡量「赚的是因子贝塔的钱，还是选股能力的 α」。适合高换手率、因子驱动的量化策略。</p>
            <p style={{margin:'4px 0'}}><b>核心指标：</b></p>
            <ul style={{margin:'2px 0',paddingLeft:16}}>
              <li><b>β 暴露</b>：策略对各因子的敏感度。β&gt;0 = 偏好高因子值股票</li>
              <li><b>因子收益</b>：因子多空组合（Top 20% − Bottom 20%）的累计收益</li>
              <li><b>贡献</b> = β × 因子收益。各因子对超额收益的独立贡献</li>
              <li><b>R²</b>：四因子对超额收益的整体解释力</li>
              <li><b>α</b>：截距项，因子无法解释的残差收益，反映纯选股能力</li>
            </ul>
          </div>
        }>
          <QuestionCircleOutlined style={{marginLeft:6,fontSize:14,color:'#8c8c8c',cursor:'help'}} />
        </Tooltip>
      </>}
        open={factorOpen} onCancel={() => setFactorOpen(false)}
        width={1100} footer={null} destroyOnClose
        style={{top:20}}
        styles={{ body: { maxHeight: 'calc(90vh - 120px)', overflowY: 'auto', overflowX: 'hidden', paddingRight: 4 } }}
      >
        <FactorDetail taskId={taskId} />
      </Modal>

      {/* FF3 风格归因 弹窗 */}
      <Modal title={<>
        <FundOutlined style={{marginRight:8}}/>FF3 三因子风格归因
        <Tooltip overlayStyle={{ maxWidth: 680 }} title={
          <div style={{maxWidth:680,lineHeight:1.8}}>
            <p style={{margin:0,fontWeight:600}}>Fama-French 三因子模型</p>
            <p style={{margin:'4px 0'}}><b>思路：</b>用市场(MKT)、规模(SMB)、价值(HML)三个标准因子回归组合超额收益，诊断"赚的是市场Beta还是规模/价值溢价"，评估风格暴露的合理性。</p>
            <p style={{margin:'4px 0'}}><b>核心指标：</b></p>
            <ul style={{margin:'2px 0',paddingLeft:16}}>
              <li><b>MKT β</b>：市场因子暴露，衡量组合与大盘的联动程度</li>
              <li><b>SMB β</b>：规模因子暴露，β&gt;0 = 偏向小盘股，β&lt;0 = 偏向大盘股</li>
              <li><b>HML β</b>：价值因子暴露，β&gt;0 = 偏向价值股，β&lt;0 = 偏向成长股</li>
              <li><b>α</b>：截距项，三因子无法解释的残差收益</li>
            <li><b>R²</b>：三因子对超额收益的整体解释力</li>
          </ul>
          <p style={{margin:'8px 0 4px',fontWeight:600,color:'#fa8c16'}}>R² 解读标准：</p>
          <table style={{width:'100%',borderCollapse:'collapse',fontSize:13,lineHeight:1.6}}>
            <thead><tr style={{background:'#fafafa'}}>
              <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>R² 范围</th>
              <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>解读</th>
            </tr></thead>
            <tbody>
              <tr>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#ff4d4f',fontWeight:600}}>&lt; 30%</td>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#ff4d4f'}}>三因子几乎无法解释该策略 — 赚钱逻辑不在市场/市值/估值框架内，风格标签无意义</td>
              </tr>
              <tr>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#fa8c16',fontWeight:600}}>30% ~ 50%</td>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#fa8c16'}}>解释力偏弱 — 风格诊断仅供参考，并非定论</td>
              </tr>
              <tr>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',fontWeight:600}}>50% ~ 70%</td>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>解释力一般 — 风格诊断有参考价值</td>
              </tr>
              <tr>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#52c41a',fontWeight:600}}>&ge; 70%</td>
                <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#52c41a'}}>解释力强 — 风格标签可信，特征明确</td>
              </tr>
            </tbody>
          </table>
          <p style={{margin:'10px 0 4px',fontWeight:600,color:'#52c41a'}}>MKT / SMB / HML 如何计算</p>
          <p style={{margin:'2px 0'}}>不是外部下载，而是每天从全市场 5000+ 只 A 股 <b>实时计算</b>：</p>
          <ul style={{margin:'2px 0',paddingLeft:16}}>
            <li><b>MKT</b> = 所有股票日收益的<b>等权平均</b>（数据：ClickHouse stock_daily）</li>
            <li><b>SMB</b> = 按市值排序，底30%小盘股平均收益 − 顶30%大盘股平均收益（数据：stock_info.total_market_cap）</li>
            <li><b>HML</b> = 按 PB 排序，底30%低估值平均收益 − 顶30%高估值平均收益（数据：stock_info.pb）</li>
          </ul>
          <p style={{margin:'4px 0 0',fontSize:12,color:'#8c8c8c'}}>因子口径与策略数据完全一致，无外部数据偏差。</p>
          </div>
        }>
          <QuestionCircleOutlined style={{marginLeft:6,fontSize:14,color:'#8c8c8c',cursor:'help'}} />
        </Tooltip>
      </>}
        open={ff3Open} onCancel={() => setFF3Open(false)}
        width={1100} footer={null} destroyOnClose
        style={{top:20}}
        styles={{ body: { maxHeight: 'calc(90vh - 120px)', overflowY: 'auto', overflowX: 'hidden', paddingRight: 4 } }}
      >
        <FF3Detail taskId={taskId} />
      </Modal>

      {/* Alpha 滚动监控 弹窗 */}
      <Modal title={<>
        <LineChartOutlined style={{marginRight:8}}/>Alpha 滚动窗口监控
        <Tooltip overlayStyle={{ maxWidth: 680 }} title={
          <div style={{maxWidth:680,maxHeight:500,overflowY:'auto',lineHeight:1.7,fontSize:13}}>
            <p style={{margin:0,fontWeight:600,fontSize:14}}>Alpha 滚动窗口监控</p>
            <p style={{margin:'6px 0'}}><b>用途：</b>追踪策略「选股能力」是否随时间退化。因子被市场发现、风格切换或过拟合失效时，Alpha 会从高位下滑——多窗口对比能最早发现。</p>
            <p style={{margin:'8px 0 4px',fontWeight:600,color:'#1677ff'}}>多窗口预警原理</p>
            <p style={{margin:'2px 0'}}>每个窗口对「策略超额收益 ~ 策略自身因子」做 OLS 回归，截距即为 Alpha。短期窗口反应快，长期窗口迟钝——三条线"分叉"越大，衰减越确定：</p>
            <table style={{width:'100%',borderCollapse:'collapse',fontSize:12,lineHeight:1.5,marginTop:6}}>
              <thead><tr style={{background:'#fafafa'}}>
                <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>窗口</th>
                <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>特性</th>
                <th style={{border:'1px solid #e8e8e8',padding:'4px 8px',textAlign:'left'}}>预警角色</th>
              </tr></thead>
              <tbody>
                <tr><td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#91caff',fontWeight:600}}>60天</td>
                  <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>反应最快，噪声最大</td>
                  <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#ff4d4f'}}>最早发现异常（先锋）</td></tr>
                <tr><td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#1677ff',fontWeight:600}}>120天</td>
                  <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>平衡，兼顾灵敏度与稳定性</td>
                  <td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#fa8c16'}}>确认趋势（中军）</td></tr>
                <tr><td style={{border:'1px solid #e8e8e8',padding:'4px 8px',color:'#cf1322',fontWeight:600}}>252天</td>
                  <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>最稳定，历史数据稀释新变化</td>
                  <td style={{border:'1px solid #e8e8e8',padding:'4px 8px'}}>长期基准（参考线）</td></tr>
              </tbody>
            </table>
            <p style={{margin:'8px 0 4px',fontWeight:600,color:'#fa8c16'}}>Alpha 如何计算</p>
            <p style={{margin:'2px 0'}}>每个窗口内 OLS 回归：<code>策略超额收益 = α + 各因子β × 因子值</code>，α（截距）就是该窗口的 Alpha。它表示"扣除所有因子暴露后，策略还能赚到的纯α"。每天向前推一天重复回归，形成滚动序列。</p>
            <p style={{margin:'8px 0 4px',fontWeight:600,color:'#52c41a'}}>MKT / SMB / HML 从哪里来</p>
            <p style={{margin:'2px 0'}}>不是外部下载，而是<b>每天从全市场 5000+ 只 A 股实时计算</b>：</p>
            <ul style={{margin:'2px 0',paddingLeft:16}}>
              <li><b>MKT</b> = 全市场所有股票日收益的<b>等权平均</b>（数据：ClickHouse stock_daily）</li>
              <li><b>SMB</b> = 市值<b>底30%</b>小票平均收益 − <b>顶30%</b>大票平均收益（数据：stock_info.total_market_cap）</li>
              <li><b>HML</b> = PB<b>底30%</b>低估值平均收益 − <b>顶30%</b>高估值平均收益（数据：stock_info.pb）</li>
            </ul>
            <p style={{margin:'8px 0 0',color:'#8c8c8c',fontStyle:'italic'}}>注意：当前回测数据不足 120 天，只有 60 天线有效。120 天和 252 天线需 ≥1 年数据才能启动完整多窗口对比。</p>
          </div>
        }>
          <QuestionCircleOutlined style={{marginLeft:6,fontSize:14,color:'#8c8c8c',cursor:'help'}} />
        </Tooltip>
      </>}
        open={alphaMonitorOpen} onCancel={() => setAlphaMonitorOpen(false)}
        width={1100} footer={null} destroyOnClose
        style={{top:20}}
        styles={{ body: { maxHeight: 'calc(90vh - 120px)', overflowY: 'auto', overflowX: 'hidden', paddingRight: 4 } }}
      >
        <AlphaMonitorPanel taskId={taskId} />
      </Modal>

      {/* 风格β 漂移监控 弹窗 */}
      <Modal title={<>
        <AlertOutlined style={{marginRight:8}}/>风格β 漂移监控
      </>}
        open={styleMonitorOpen} onCancel={() => setStyleMonitorOpen(false)}
        width={1100} footer={null} destroyOnClose
        style={{top:20}}
        styles={{ body: { maxHeight: 'calc(90vh - 120px)', overflowY: 'auto', overflowX: 'hidden', paddingRight: 4 } }}
      >
        <StyleMonitorPanel taskId={taskId} />
      </Modal>

    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 1. 策略概况
// ══════════════════════════════════════════════════════════════════════════════
// ══════════════════════════════════════════════════════════════════════════════
// 1. 综合归因结论（最外层：组合归因 + 交易诊断 + 策略概要）
// ══════════════════════════════════════════════════════════════════════════════
function GeneralConclusion({ strategy, recommendation, tradeAnalysis }) {
  if (!strategy) return null;

  const alertType = recommendation === 'UNCLEAR' ? 'warning' : 'info';
  const mc = strategy.modelComparison || {};

  // ── 解释力标签 ──
  const quickTags = [];
  const addTag = (label, er, colors) => {
    if (er == null) { quickTags.push(<Tag key={label} color="default" style={{fontSize:11}}>{label} 不可用</Tag>); return; }
    const color = er >= 0.5 ? colors[0] : er >= 0.15 ? colors[1] : 'default';
    quickTags.push(<Tag key={label} color={color} style={{fontSize:11}}>{label} {fmtPct(er)}</Tag>);
  };
  addTag('Brinson', mc.BRINSON?.available ? mc.BRINSON?.explanationRatio : null, ['blue', 'geekblue']);
  addTag('因子', mc.FACTOR?.available ? mc.FACTOR?.explanationRatio : null, ['green', 'lime']);
  addTag('FF3', mc.FF3?.available ? mc.FF3?.explanationRatio : null, ['purple', 'gold']);

  // ── 策略特征标签（轻量内联） ──
  const metaTags = [];
  if (strategy.avgDailyTurnover != null)
    metaTags.push(<Tag key="turnover" style={{fontSize:11}}>换手 {fmtPct(strategy.avgDailyTurnover)}</Tag>);
  if (strategy.avgHoldingDays != null)
    metaTags.push(<Tag key="hold" style={{fontSize:11}}>持仓 {strategy.avgHoldingDays}天</Tag>);
  if (strategy.industryConcentration != null)
    metaTags.push(
      <Tooltip key="hhi-tip"
        overlayInnerStyle={{ maxWidth: 420 }}
        title={
          <div style={{ lineHeight: 1.8 }}>
            <p style={{ margin: 0, fontWeight: 600 }}>HHI（赫芬达尔指数）</p>
            <p style={{ margin: '4px 0' }}>各股持仓权重平方和，衡量组合的<b>行业/个股集中度</b>。</p>
            <p style={{ margin: '4px 0' }}>
              <b>范围：</b>0~1，越高越集中。<br/>
              <b>等权1只</b> → 1.0 &nbsp;|&nbsp; <b>等权5只</b> → 0.2 &nbsp;|&nbsp; <b>等权10只</b> → 0.1 &nbsp;|&nbsp; <b>等权20只</b> → 0.05
            </p>
            <p style={{ margin: '4px 0', color: strategy.industryConcentration > 0.3 ? '#ff4d4f' : '#52c41a' }}>
              当前 HHI={fmt(strategy.industryConcentration)}：{
                strategy.industryConcentration > 0.3 ? '高度集中，收益过度依赖少数行业——行业配置是核心矛盾，Brinson 归因更有价值。'
                : strategy.industryConcentration > 0.15 ? '适度集中，行业选择有一定影响但不过度依赖。'
                : '高度分散，不会因押错一两个行业翻车，因子暴露才是收益主因。'
              }
            </p>
          </div>
        }
      >
        <Tag key="hhi" style={{fontSize:11, cursor:'help'}}>HHI {fmt(strategy.industryConcentration)}</Tag>
      </Tooltip>
    );

  // ── 策略画像（综合换手+HHI+持仓天数） ──
  const buildStrategyProfile = () => {
    const t = strategy.avgDailyTurnover;
    const hhi = strategy.industryConcentration;
    const hold = strategy.avgHoldingDays;
    if (t == null) return null;

    const highTO = t > 0.5 || (hold != null && hold < 5);
    const highConc = hhi != null && hhi > 0.3;

    if (highTO && !highConc)
      return `策略画像：${fmtPct(t)}换手 + 持仓约${hold}天 + HHI=${hhi != null ? fmt(hhi) : 'N/A'} =「高频分散型」——每次调仓大换血但从不重仓押注，典型量化因子轮动策略。收益不靠赌行业，全靠因子信号驱动。`;
    if (highConc && !highTO)
      return `策略画像：${fmtPct(t)}换手 + HHI=${fmt(hhi)} =「低频集中型」——持股集中且拿得久，收益高度依赖所选的少数行业/个股，适合用 Brinson 拆解行业贡献。`;
    if (highTO && highConc)
      return `策略画像：${fmtPct(t)}换手 + HHI=${fmt(hhi)} =「高频集中型」——换得快又押得重，策略激进，行业和因子两条归因都很关键，建议交叉验证。`;
    return `策略画像：${fmtPct(t)}换手 + 持仓约${hold}天 + HHI=${hhi != null ? fmt(hhi) : 'N/A'} =「低频分散型」——持股均匀、换手温和，策略偏稳健，行业归因和因子归因均可适用。`;
  };
  const strategyProfile = buildStrategyProfile();
  let tradeSummary = '';
  if (tradeAnalysis?.holdingPeriods?.length > 0) {
    const best = tradeAnalysis.holdingPeriods.reduce((a, b) =>
      Math.abs(a.contributionPct) > Math.abs(b.contributionPct) ? a : b);
    tradeSummary = `交易诊断：最优持仓周期「${best.bucket}」（贡献${fmtPct(Math.abs(best.contributionPct || 0)/100)}），`;
    if (tradeAnalysis?.tradeAttribution?.top10Contribution != null) {
      tradeSummary += `Top10交易贡献了${fmtPct(tradeAnalysis.tradeAttribution.top10Contribution)}的总收益。`;
    }
  }

  return (
    <Alert type={alertType} showIcon
      message={<span>综合归因结论 {quickTags} {metaTags.length > 0 && <span style={{color:'#8c8c8c', fontSize:11}}>{metaTags}</span>}</span>}
      description={
        <div style={{ whiteSpace: 'pre-line', fontSize: 13, lineHeight: 1.8 }}>
          {strategy.reason}
          {strategyProfile && <><br/><br/><Text strong style={{ color: '#1890ff' }}>{strategyProfile}</Text></>}
          {tradeSummary && <><br/><br/><Text type="secondary">{tradeSummary}</Text></>}
        </div>
      }
      style={{ marginBottom: 16 }}
    />
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 2. 模型对比（仅组合归因三模型）
// ══════════════════════════════════════════════════════════════════════════════
function ModelComparisonCard({ strategy }) {
  if (!strategy) return null;

  const mc = strategy.modelComparison || {};

  return (
    <Card size="small" style={{ marginBottom: 16 }}
      title={<><InfoCircleOutlined style={{ marginRight: 6 }} />归因模型对比（组合归因）</>}>
      <Table
        size="small"
        pagination={false}
        dataSource={[
          {
            key: 'brinson',
            model: 'Brinson 行业归因',
            explanation: mc.BRINSON?.explanationRatio,
            available: mc.BRINSON?.available,
            desc: '拆解行业配置 vs 行业内选股能力：回答「超额来自哪个行业」',
          },
          {
            key: 'factor',
            model: '因子风格归因',
            explanation: mc.FACTOR?.explanationRatio,
            available: mc.FACTOR?.available,
            desc: '回归动量/市值/波动率等因子：回答「策略暴露了什么逻辑」',
          },
          {
            key: 'ff3',
            model: 'FF3 三因子归因',
            explanation: mc.FF3?.explanationRatio,
            available: mc.FF3?.available,
            desc: '剥离 MKT/SMB/HML 被动收益：回答「比被动因子投资多赚多少」',
          },
        ]}
        columns={[
          { title: '归因模型', dataIndex: 'model', width: 140, render: v => <b>{v}</b> },
          {
            title: '解释力', dataIndex: 'explanation', width: 90,
            render: (v) => {
              if (v == null) return <Text type="secondary">-</Text>;
              const ratio = v;
              const color = ratio > 0.5 ? '#52c41a' : ratio > 0.2 ? '#fa8c16' : ratio < 0 ? '#8c8c8c' : '#ff4d4f';
              return <Text strong style={{ color }}>{fmtPct(Math.max(0, ratio))}</Text>;
            },
          },
          {
            title: '状态', dataIndex: 'available', width: 85,
            render: v => v ? <Badge status="success" text="可用" /> : <Badge status="default" text="不可用" />,
          },
          { title: '说明', dataIndex: 'desc', ellipsis: true },
        ]}
      />
    </Card>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 交易诊断：持仓周期分析
// ══════════════════════════════════════════════════════════════════════════════
function HoldingPeriodPanel({ periods, totalTrades }) {
  const chartOption = useMemo(() => {
    const labels = periods.map(p => p.bucket);
    const avgReturns = periods.map(p => p.avgReturn * 100);
    const winRates = periods.map(p => p.winRate * 100);
    const contributions = periods.map(p => p.contributionPct);

    return {
      tooltip: {
        trigger: 'axis',
        formatter: function(params) {
          const p = periods[params[0].dataIndex];
          return `<b>${p.bucket}</b><br/>
            交易数: ${p.tradeCount}<br/>
            等权平均收益: ${fmtPct(p.avgReturn)}<br/>
            胜率: ${fmtPct(p.winRate)}<br/>
            总盈亏: ${fmtMoney(p.totalPnl)}<br/>
            每笔均盈亏: ${fmtMoney(p.avgPnl)}<br/>
            贡献占比: ${fmt(p.contributionPct)}%<br/>
            <span style="color:#8c8c8c">${p.summary}</span>`;
        },
      },
      legend: { data: ['等权平均收益%', '胜率%', '贡献占比%'], top: 0 },
      grid: { left: 60, right: 40, top: 30, bottom: 75 },
      xAxis: { type: 'category', data: labels, axisLabel: { fontSize: 11 } },
      yAxis: [
        { type: 'value', name: '%', axisLabel: { formatter: '{value}' } },
      ],
      series: [
        {
          name: '等权平均收益%', type: 'bar', data: avgReturns,
          itemStyle: {
            color: function(params) {
              return avgReturns[params.dataIndex] >= 0 ? RED : GREEN;
            },
          },
          barMaxWidth: 40,
        },
        {
          name: '胜率%', type: 'line', data: winRates,
          lineStyle: { color: '#1677ff', width: 2 }, symbol: 'circle',
          yAxisIndex: 0,
        },
        {
          name: '贡献占比%', type: 'line', data: contributions,
          lineStyle: { color: '#fa8c16', width: 2, type: 'dashed' },
          symbol: 'diamond', yAxisIndex: 0,
        },
      ],
    };
  }, [periods]);

  const columns = [
    { title: '持仓周期', dataIndex: 'bucket', width: 90, render: v => <b>{v}</b> },
    { title: '交易笔数', dataIndex: 'tradeCount', width: 75, align: 'right' },
    { title: '占比', width: 85, align: 'right',
      render: (_, r) => fmtPct(r.tradeCount / totalTrades) },
    { title: '等权均收益', width: 95, align: 'right',
      render: (_, r) => {
        const showPct = fmtPct(r.avgReturn);
        return <Text style={{ color: r.avgReturn >= 0 ? RED : GREEN, fontWeight: 700 }}>{showPct}</Text>;
      }},
    { title: '每笔均盈亏', dataIndex: 'avgPnl', width: 100, align: 'right',
      render: v => <Text style={{ color: v >= 0 ? RED : GREEN }}>{fmtMoney(v)}</Text> },
    { title: '胜率', dataIndex: 'winRate', width: 70, align: 'right',
      render: v => fmtPct(v) },
    { title: '总盈亏', dataIndex: 'totalPnl', width: 100, align: 'right',
      render: v => <Text style={{ color: v >= 0 ? RED : GREEN }}>{fmtMoney(v)}</Text> },
    { title: '贡献占比', dataIndex: 'contributionPct', width: 100, align: 'right',
      render: v => <Text strong style={{ color: Math.abs(v) > 50 ? '#ff4d4f' : '#8c8c8c' }}>{fmt(v)}%</Text> },
    { title: '评估', dataIndex: 'summary', ellipsis: true,
      render: v => <Text style={{ fontSize: 11, color: '#8c8c8c' }}>{v}</Text> },
  ];

  // ── 诊断计算 ──
  const totalPnl = periods.reduce((s, p) => s + p.totalPnl, 0);
  const losers = periods.filter(p => p.totalPnl < 0);
  const winners = periods.filter(p => p.totalPnl > 0);
  const worstLoss = losers.length > 0 ? losers.reduce((a, b) => a.totalPnl < b.totalPnl ? a : b) : null;
  const bestWin = winners.length > 0 ? winners.reduce((a, b) => a.totalPnl > b.totalPnl ? a : b) : null;

  // 亏损交易总笔数和占比
  const totalLosingTrades = losers.reduce((s, p) => s + p.tradeCount, 0);
  const losingRatio = totalTrades > 0 ? totalLosingTrades / totalTrades : 0;

  // 周期集中度：最高贡献占比（绝对值）
  const maxContrib = periods.reduce((max, p) => Math.max(max, Math.abs(p.contributionPct)), 0);

  // 是否有"等权平均收益为正但总盈亏为负"的周期（歧义周期）
  const misleadingPeriods = periods.filter(p => p.avgReturn > 0 && p.totalPnl < 0);

  // ── 调整建议生成 ──
  const suggestions = useMemo(() => {
    const tips = [];
    // 建议1: 最大亏损源
    if (worstLoss && worstLoss.contributionPct < -30) {
      tips.push({
        level: 'critical',
        capability: 'partially_adjust',
        capabilityLabel: '半支持',
        capabilityColor: '#d48806',
        path: '步骤1：策略管理 → 策略列表 → 编辑策略 → 调仓频率改为 WEEKLY → 回测验证\n步骤2：若胜率仍 <40%，回测管理 → IC/IR 分析 → 标出 IC<0.02 或 IR<0.3 的因子\n步骤3：策略管理 → 策略列表 → 编辑策略 → 因子配置 Tab → 降低或移除这些因子',
        limitation: '⚠️ (1) 因子 IC 值不在策略编辑页中，需到「回测管理 → IC/IR 分析」查看；(2) 策略编辑器只显示因子权重，不能直接看到 IC，需手动交叉对照。',
        text: `【核心问题】"${worstLoss.bucket}"周期贡献了 ${fmt(Math.abs(worstLoss.contributionPct))}% 的亏损（${fmtMoney(worstLoss.totalPnl)}），${worstLoss.tradeCount}笔交易中胜率仅 ${fmtPct(worstLoss.winRate)}。选股因子在该周期段大面积失效，调模拟盘止损参数治标不治本。`,
        action: worstLoss.winRate < 0.3
          ? '调仓频率 DAILY→WEEKLY 是最快可执行的验证手段。如果改后短周期胜率仍低于 40%，则需到「回测管理 → IC/IR 分析」查看各因子 Rank IC 均值，标出 IC<0.02 或 IR<0.3 的因子，再到「策略编辑 → 因子配置 Tab」降低或移除。' +
            '\n注意：当前数据无法判断胜率低是因为"卖早了"还是"选不准"，只能通过实际调整分步验证。'
          : '审视该周期的选股逻辑，排查是否因子在该周期段失效。编辑策略 → 因子配置 Tab → 调整因子权重。',
      });
    }
    // 建议2: 最好的周期被严重低估
    if (bestWin && bestWin.contributionPct > 50 && bestWin.tradeCount < totalTrades * 0.3) {
      tips.push({
        level: 'warning',
        capability: 'cannot_adjust',
        capabilityLabel: '暂不支持',
        capabilityColor: '#cf1322',
        path: '当前系统没有「信号按持仓周期偏好滤波」的能力。',
        limitation: '需要新增能力：在因子打分后、生成买入信号前，加一个「持仓周期偏好滤镜」——根据历史回测数据，优先保留在有利周期（如30天以上）表现好的股票信号，过滤掉在亏损周期（如16-30天）的信号。',
        text: `【机会点】"${bestWin.bucket}"周期贡献了 ${fmt(bestWin.contributionPct)}% 的总收益，但交易笔数仅占 ${fmtPct(bestWin.tradeCount / totalTrades)}。`,
        action: '如果能把30天以上周期的信号权重提升到 50%+，策略收益有大幅提升空间。需要在策略引擎中增加「持仓周期偏好滤波器」——在 Signal 生成环节按目标持有天数过滤。',
      });
    }
    // 建议3: 亏损交易占比过高
    if (losingRatio > 0.6) {
      tips.push({
        level: 'critical',
        capability: 'can_adjust',
        capabilityLabel: '可调整',
        capabilityColor: '#389e0d',
        path: '步骤1：回测管理 → IC/IR 分析 → 选因子+日期 → 点"分析" → 在结果表格中点击蓝色因子代码，下方展开 IC 趋势图 → 看累计 IC 曲线是持续下降（长期失效）还是近期才掉（暂时波动）\n步骤2：检查该因子与备选因子的相关系数，确认有低相关（<0.3）的替代品才值得替换\n步骤3：策略管理 → 模拟盘 → 新建副本策略 → 修改因子配置 → 跑同期回测 A/B 对比 → 夏普/回撤都改善再正式替换',
        limitation: '⚠️ IC低≠去掉就好：该因子可能在组合中起噪声抵消或分散化作用，去掉反而更差。系统暂无自动 A/B 对比功能，需手动在模拟盘建副本验证。IC/IR 分析中的因子趋势图可辅助判断是否真的失效。',
        text: `【因子整体失效】${fmtPct(losingRatio)} 的交易（${totalLosingTrades}/${totalTrades}笔）全线亏损，不仅限于特定持有周期——说明选股因子在样本期内大面积失效，不是调仓频率能解决的。`,
        action: 'IC 低不代表替换一定有效。先看趋势（近期突变=可能暂时波动，长期失效=确实该换），再看因子相关性（有低相关替代品才值得换），最后必须跑 A/B 回测——只有夏普和回撤都改善才确认有效。盲目按 IC 排序删因子可能更差。',
      });
    }
    // 建议4: 高度集中风险
    if (maxContrib > 80) {
      tips.push({
        level: 'warning',
        capability: 'partially_adjust',
        capabilityLabel: '半支持',
        capabilityColor: '#d48806',
        path: '策略管理 → 模拟盘 → 点击进入详情 → 风控配置 Tab → 单股仓位上限 15%',
        limitation: '可以调仓位上限间接缓解，但没有「单周期贡献占比上限」的直接参数。需要新增风控配置项 max_period_contribution_pct。',
        text: `【集中风险】单一周期贡献占比超过 ${fmt(maxContrib)}%，策略严重依赖特定持仓时长。`,
        action: '先收紧单股仓位上限至 15%，再考虑在 paper_risk_config 表新增 max_period_contribution_pct 字段实现直接约束。',
      });
    }
    // 建议5: 等权平均收益与每笔均盈亏方向不一致
    if (misleadingPeriods.length > 0) {
      const mp = misleadingPeriods[0];
      tips.push({
        level: 'info',
        capability: 'not_needed',
        capabilityLabel: '无需调整',
        capabilityColor: '#1677ff',
        path: '',
        limitation: '',
        text: `【数据解读】"${mp.bucket}"周期等权平均收益 ${fmtPct(mp.avgReturn)}（正），但每笔均盈亏 ${fmtMoney(mp.avgPnl)}（负）。`,
        action: '说明该周期内少数大赚交易拉高了平均值，但大多数交易实际在亏钱。不建议仅凭"平均收益"来判断周期好坏，需结合每笔均盈亏和胜率。',
      });
    }
    return tips;
  }, [periods, totalTrades]);

  const levelConfig = {
    critical: { color: '#cf1322', bg: '#fff1f0', icon: <CloseCircleOutlined /> },
    warning: { color: '#d48806', bg: '#fffbe6', icon: <ExclamationCircleOutlined /> },
    info: { color: '#1677ff', bg: '#f0f5ff', icon: <InfoCircleOutlined /> },
  };

  return (
    <Card size="small" title={<><ClockCircleOutlined style={{ marginRight: 6 }} />持仓周期 vs 收益分布</>}
      style={{ marginBottom: 16 }}>

      {/* ── 诊断摘要 ── */}
      <Alert
        type={losingRatio > 0.6 ? 'error' : 'warning'}
        message={<b>诊断摘要：总盈亏 {fmtMoney(totalPnl)} · 亏损交易占比 {fmtPct(losingRatio)} · 主要亏损源「{worstLoss?.bucket || '-'}」</b>}
        description={
          <div style={{ lineHeight: 1.8 }}>
            {worstLoss && <p style={{ margin: 0 }}>
              {losingRatio > 0.6
                ? `${fmtPct(losingRatio)} 的交易在亏损，其中"${worstLoss.bucket}"周期造成最大损失（${fmtMoney(worstLoss.totalPnl)}，贡献 ${fmt(worstLoss.contributionPct)}%），是拖垮总收益的主因。`
                : `主要亏损集中在"${worstLoss.bucket}"周期（${fmtMoney(worstLoss.totalPnl)}）。`}
            </p>}
            {bestWin && bestWin.contributionPct > 0 && <p style={{ margin: '4px 0 0' }}>
              唯一盈利周期是「{bestWin.bucket}」—— {bestWin.tradeCount} 笔交易贡献 {fmtMoney(bestWin.totalPnl)}，
              {bestWin.tradeCount < totalTrades * 0.3 ? '但仅占总交易量的 ' + fmtPct(bestWin.tradeCount / totalTrades) + '，盈利面过窄。' : ''}
            </p>}
          </div>
        }
        style={{ marginBottom: 12 }}
        showIcon
      />

      {/* ── 图表 ── */}
      <div style={{ fontSize: 11, color: '#8c8c8c', marginBottom: 4 }}>
        ⓘ 「等权平均收益」为各笔收益率算术均值，不含仓位金额加权。判断周期优劣请结合「每笔均盈亏」和「贡献占比」。
      </div>
      <ReactECharts option={chartOption} style={{ height: 300 }} />

      {/* ── 表格 ── */}
      <Table size="small" dataSource={periods} columns={columns} pagination={false}
        style={{ marginTop: 12 }} rowKey="bucket"
        onRow={(r) => {
          if (r.tradeCount <= 1) return {};
          const absC = Math.abs(r.contributionPct);
          if (absC > 80) return { style: { background: '#fff1f0' } };
          if (absC > 50) return { style: { background: '#fff7e6' } };
          return {};
        }}
      />

      {/* ── 调整建议 ── */}
      {suggestions.length > 0 && (
        <Card size="small" title="调整建议" style={{ marginTop: 12, background: '#fafafa' }}>
          {suggestions.map((s, i) => {
            const cfg = levelConfig[s.level];
            const capBadge = s.capabilityLabel ? (
              <Tag color={s.capabilityColor} style={{ fontSize: 10, lineHeight: '16px', marginLeft: 4 }}>
                {s.capabilityLabel}
              </Tag>
            ) : null;
            return (
              <div key={i} style={{ marginBottom: i < suggestions.length - 1 ? 10 : 0, padding: '8px 12px', background: cfg.bg, borderRadius: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                  <span style={{ color: cfg.color, fontSize: 14 }}>{cfg.icon}</span>
                  <Text style={{ color: cfg.color, fontWeight: 600 }}>{s.text}</Text>
                  {capBadge}
                </div>
                <div style={{ paddingLeft: 22 }}>
                  <Text style={{ color: '#555', fontSize: 12 }}>{s.action}</Text>
                  {s.path && (
                    <div style={{ marginTop: 4 }}>
                      <Tag color="geekblue" style={{ fontSize: 11, marginRight: 0, fontFamily: 'monospace', whiteSpace: 'pre-line', lineHeight: 1.6 }}>
                        {s.path}
                      </Tag>
                    </div>
                  )}
                  {s.limitation && (
                    <div style={{ marginTop: 4 }}>
                      <Text style={{ color: '#8c8c8c', fontSize: 11 }}>{s.limitation}</Text>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </Card>
      )}
    </Card>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 交易诊断：关键交易分析
// ══════════════════════════════════════════════════════════════════════════════
function TradeAttributionPanel({ attr }) {
  // 帕累托图：累计贡献曲线
  const allTrades = useMemo(() => {
    // 合并 winner + loser，按 pnl 绝对值降序排列
    const combined = [
      ...(attr.topWinners || []).map(t => ({ ...t, type: 'win' })),
      ...(attr.topLosers || []).map(t => ({ ...t, type: 'loss' })),
    ];
    // 去重后按 pnl 绝对值降序
    return [...new Map(combined.map(t => [
      t.symbol + '_' + t.buyDate + '_' + t.sellDate, t
    ])).values()]
      .sort((a, b) => Math.abs(b.pnl) - Math.abs(a.pnl));
  }, [attr]);

  const paretoOption = useMemo(() => {
    const data = allTrades.slice(0, 30); // Top 30 for chart
    const labels = data.map(t => t.symbol + (t.name ? ` ${t.name}` : ''));
    const pnlValues = data.map(t => Math.round(t.pnl));
    const cumulative = [];
    let cum = 0;
    const totalAbs = Math.abs(attr.totalPnl);
    data.forEach(t => {
      cum += t.pnl;
      cumulative.push(totalAbs > 0 ? Math.round((cum / totalAbs) * 100) : 0);
    });

    return {
      tooltip: {
        trigger: 'axis',
        formatter: function(params) {
          const d = data[params[0].dataIndex];
          return `<b>${d.symbol} ${d.name || ''}</b><br/>
            买入: ${d.buyDate} 卖出: ${d.sellDate}<br/>
            持仓: ${d.holdingDays}天<br/>
            盈亏: ${fmtMoney(d.pnl)} (${fmtPct(d.pnlPct)})<br/>
            类型: ${d.type === 'win' ? '盈利' : '亏损'}`;
        },
      },
      legend: { data: ['单笔盈亏', '累计贡献%'], top: 0 },
      grid: { left: 70, right: 60, top: 30, bottom: 75 },
      xAxis: {
        type: 'category', data: labels,
        axisLabel: { rotate: 35, fontSize: 10 },
      },
      yAxis: [
        { type: 'value', name: '盈亏(元)', axisLabel: { formatter: v => fmtMoney(v) } },
        { type: 'value', name: '%', axisLabel: { formatter: '{value}%' } },
      ],
      series: [
        {
          name: '单笔盈亏', type: 'bar', data: pnlValues,
          itemStyle: {
            color: function(params) {
              return pnlValues[params.dataIndex] >= 0 ? RED : GREEN;
            },
          },
          barMaxWidth: 30,
        },
        {
          name: '累计贡献%', type: 'line', yAxisIndex: 1,
          data: cumulative,
          lineStyle: { color: '#1677ff', width: 2 },
          symbol: 'circle',
        },
      ],
    };
  }, [allTrades, attr.totalPnl]);

  // 帕累托总结
  const paretoSummary = useMemo(() => {
    const top3 = attr.top3Contribution || 0;
    const top10 = attr.top10Contribution || 0;
    if (top3 > 0.5) {
      return <Tag color="volcano">Top 3 贡献 {(top3 * 100).toFixed(0)}% 利润 — 极度集中，收益靠少数交易驱动</Tag>;
    }
    if (top10 > 0.6) {
      return <Tag color="orange">Top 10 贡献 {(top10 * 100).toFixed(0)}% 利润 — 较为集中，需关注尾部风险</Tag>;
    }
    return <Tag color="green">收益来源分散 — 策略系统性较强</Tag>;
  }, [attr]);

  const tradeColumns = [
    { title: '股票', width: 140,
      render: (_, r) => <span><b>{r.symbol}</b> <Text type="secondary" style={{fontSize:10}}>{r.name}</Text></span> },
    { title: '买入', dataIndex: 'buyDate', width: 90, render: v => <Text style={{fontSize:11}}>{v}</Text> },
    { title: '卖出', dataIndex: 'sellDate', width: 90, render: v => <Text style={{fontSize:11}}>{v}</Text> },
    { title: '持仓', width: 55, align: 'right',
      render: (_, r) => <span>{r.holdingDays}<Text style={{fontSize:10,color:'#999'}}>天</Text></span> },
    { title: '盈亏', width: 95, align: 'right',
      render: (_, r) => <Text strong style={{color: r.pnl>=0?RED:GREEN}}>{fmtMoney(r.pnl)}</Text> },
    { title: '收益率', dataIndex: 'pnlPct', width: 75, align: 'right',
      render: v => <Text style={{color: v>=0?RED:GREEN, fontWeight:600}}>{fmtPct(v)}</Text> },
  ];

  return (
    <Card size="small" title={<><DollarOutlined style={{ marginRight: 6 }} />关键交易贡献分析</>}
      style={{ marginBottom: 16 }}>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={6}><Statistic title="总配对交易" value={attr.totalTrades} suffix="笔" /></Col>
        <Col span={6}>
          <Statistic title="盈利笔数"
            value={attr.winnerCount}
            suffix={`/${attr.totalTrades}`}
            valueStyle={{ color: RED }}
          />
        </Col>
        <Col span={6}>
          <Statistic title="最大单笔盈利" value={fmtMoney(attr.maxWin)}
            valueStyle={{ color: RED, fontSize: 16 }}
          />
        </Col>
        <Col span={6}>
          <Statistic title="最大单笔亏损" value={fmtMoney(attr.maxLoss)}
            valueStyle={{ color: GREEN, fontSize: 16 }}
          />
        </Col>
      </Row>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={6}><Statistic title="平均盈利" value={fmtMoney(attr.avgWin)} valueStyle={{ color: RED, fontSize: 14 }} /></Col>
        <Col span={6}><Statistic title="平均亏损" value={fmtMoney(attr.avgLoss)} valueStyle={{ color: GREEN, fontSize: 14 }} /></Col>
        <Col span={6}>
          <Statistic title="盈亏比" value={attr.avgLoss !== 0 ? fmt(Math.abs(attr.avgWin / attr.avgLoss)) : '-'}
            valueStyle={{ fontSize: 14 }} />
        </Col>
        <Col span={6}>
          <Statistic title="Top 3 贡献" value={fmtPct(attr.top3Contribution)}
            suffix={attr.top3Contribution > 0.5 ? <Tag color="volcano">集中</Tag> : null}
          />
        </Col>
      </Row>

      <div style={{ marginBottom: 8 }}>{paretoSummary}</div>

      <ReactECharts option={paretoOption} style={{ height: 350 }} />

      <Divider orientation="left" plain style={{ fontSize: 13 }}>
        <RiseOutlined style={{ color: RED }} /> Top 10 盈利交易
      </Divider>
      <Table size="small" dataSource={attr.topWinners || []} columns={tradeColumns}
        pagination={false} rowKey={(r) => r.symbol + r.buyDate + r.sellDate}
        locale={{ emptyText: '无盈利交易数据' }}
      />

      <Divider orientation="left" plain style={{ fontSize: 13 }}>
        <FallOutlined style={{ color: GREEN }} /> Bottom 10 亏损交易
      </Divider>
      <Table size="small" dataSource={attr.topLosers || []} columns={tradeColumns}
        pagination={false} rowKey={(r) => r.symbol + r.buyDate + r.sellDate}
        locale={{ emptyText: '无亏损交易数据' }}
      />
    </Card>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 5. Brinson 归因详情（完整版：汇总 + 曲线 + 瀑布图 + 行业表 + 结论）
// ══════════════════════════════════════════════════════════════════════════════

// 简单的符号→颜色
const signCol = (v) => v > 0 ? RED : v < 0 ? GREEN : '#262626';

function BrinsonDetail({ taskId }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!taskId) return;
    backtestApi.getAttribution(taskId)
      .then(setData)
      .catch(err => setError(err.message || 'Brinson归因加载失败'))
      .finally(() => setLoading(false));
  }, [taskId]);

  // hooks 必须在早期 return 之前
  const summary = data?.summary || {};
  const periods = data?.periods || [];
  const cumulativeChart = data?.cumulativeChart || [];
  const industrySummary = data?.industrySummary || [];

  // ── 累计归因曲线图 ──
  const cumChartOption = useMemo(() => {
    if (!cumulativeChart.length) return null;
    const labels = cumulativeChart.map(d => d.period?.split(' ~ ')[1] || d.startDate);
    return {
      tooltip: {
        trigger: 'axis',
        formatter: params => {
          let html = `<div style="font-weight:600;margin-bottom:4px">${params[0].name}</div>`;
          params.forEach(p => {
            const colorMap = { '配置效应': '#1677ff', '选股效应': '#52c41a', '交互效应': '#fa8c16', '超额收益': '#cf1322' };
            html += `<div><span style="color:${colorMap[p.seriesName]||'#999'}">●</span> ${p.seriesName}：<b>${(p.value>=0?'+':'')}${(+p.value).toFixed(2)}%</b></div>`;
          });
          return html;
        },
      },
      legend: { data: ['配置效应', '选股效应', '交互效应', '超额收益'], top: 0 },
      grid: { left: 56, right: 16, top: 32, bottom: 30 },
      xAxis: { type: 'category', data: labels, axisLabel: { rotate: 30, fontSize: 10 }, boundaryGap: false },
      yAxis: { type: 'value', axisLabel: { formatter: v => `${v>0?'+':''}${v.toFixed(1)}%` }, splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } } },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 4 }],
      series: [
        { name: '配置效应', type: 'line', data: cumulativeChart.map(d => +(d.cumAllocation * 100).toFixed(4)), smooth: false, lineStyle: { color: '#1677ff', width: 1.5 }, symbol: 'none', areaStyle: { color: 'rgba(22,119,255,0.06)' } },
        { name: '选股效应', type: 'line', data: cumulativeChart.map(d => +(d.cumSelection * 100).toFixed(4)), smooth: false, lineStyle: { color: '#52c41a', width: 1.5 }, symbol: 'none', areaStyle: { color: 'rgba(82,196,26,0.06)' } },
        { name: '交互效应', type: 'line', data: cumulativeChart.map(d => +(d.cumInteraction * 100).toFixed(4)), smooth: false, lineStyle: { color: '#fa8c16', width: 1.5 }, symbol: 'none' },
        { name: '超额收益', type: 'line', data: cumulativeChart.map(d => +(d.cumExcess * 100).toFixed(4)), smooth: false, lineStyle: { color: '#cf1322', width: 2, type: 'dashed' }, symbol: 'none' },
      ],
    };
  }, [cumulativeChart]);

  // ── 逐期瀑布图 ──
  const waterfallOption = useMemo(() => {
    if (!periods.length) return null;
    const allAbs = [];
    periods.forEach(p => {
      allAbs.push(Math.abs(p.allocationEffect * 100));
      allAbs.push(Math.abs(p.selectionEffect * 100));
      allAbs.push(Math.abs(p.interactionEffect * 100));
      allAbs.push(Math.abs(p.excessReturn * 100));
    });
    allAbs.sort((a, b) => a - b);
    const maxAbs = allAbs[allAbs.length - 1];
    const p90 = allAbs[Math.floor(allAbs.length * 0.92)];
    // ⚠️ 用 maxAbs 兜底，避免极端单期值（如 2025-3-3 暴跌）被 p90 裁剪
    const yMax = Math.min(Math.max(p90 * 1.3, maxAbs * 1.05, 3), 600);

    const rawLabels = periods.map(p => p.period?.split(' ~ ')[1] || p.startDate);
    const labelInterval = rawLabels.length > 60 ? 2 : rawLabels.length > 30 ? 1 : 0;

    return {
      tooltip: {
        trigger: 'axis',
        formatter: params => {
          const p = periods[params[0].dataIndex];
          return `<div style="font-weight:600">${p.period}</div>
            <div>配置效应: ${fmtPct(p.allocationEffect)}</div>
            <div>选股效应: ${fmtPct(p.selectionEffect)}</div>
            <div>交互效应: ${fmtPct(p.interactionEffect)}</div>
            <div>超额收益: ${fmtPct(p.excessReturn)}</div>`;
        },
      },
      legend: { data: ['配置效应', '选股效应', '交互效应', '超额收益'], top: 0 },
      grid: { left: 56, right: 16, top: 32, bottom: 30 },
      xAxis: { type: 'category', data: rawLabels, axisLabel: { rotate: 45, fontSize: 10, interval: labelInterval } },
      yAxis: { type: 'value', min: -yMax, max: yMax, axisLabel: { formatter: v => `${v.toFixed(1)}%` }, splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } } },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 4 }],
      series: [
        { name: '配置效应', type: 'bar', stack: 'attr', data: periods.map(p => +(p.allocationEffect * 100).toFixed(4)), barMaxWidth: 30 },
        { name: '选股效应', type: 'bar', stack: 'attr', data: periods.map(p => +(p.selectionEffect * 100).toFixed(4)), barMaxWidth: 30 },
        { name: '交互效应', type: 'bar', stack: 'attr', data: periods.map(p => +(p.interactionEffect * 100).toFixed(4)), barMaxWidth: 30 },
        { name: '超额收益', type: 'bar', data: periods.map(p => +(p.excessReturn * 100).toFixed(4)), barMaxWidth: 30,
          itemStyle: { color: params => params.value >= 0 ? RED : GREEN } },
      ],
    };
  }, [periods]);

  if (loading) return <div style={{textAlign:'center',padding:40}}><Spin tip="加载 Brinson 归因..." /></div>;
  if (error) return <Alert type="error" message={error} showIcon />;
  if (!data) return <Alert type="info" message="暂无 Brinson 归因数据" showIcon />;

  return (
    <div>
      {/* ── 概要指标 ── */}
      <Row gutter={[4, 4]} style={{marginBottom:16,background:'#fafafa',borderRadius:8,padding:'12px 4px'}}>
        {[
          { label: '配置效应', value: summary.totalAllocationEffect,
            tip: 'Σ(策略行业权重−基准权重)×(行业基准收益−基准总收益)。正值=超配强势行业/低配弱势行业。' },
          { label: '选股效应', value: summary.totalSelectionEffect,
            tip: 'Σ 基准权重×(策略行业内收益−行业基准收益)。正值=所选个股跑赢行业均值。' },
          { label: '交互效应', value: summary.totalInteractionEffect,
            tip: 'Σ(权重差)×(选股收益差)。正值=权重方向与选股方向一致（乘数放大）。' },
          { label: '超额收益', value: summary.totalExcessReturn,
            tip: '策略累计收益 − 基准累计收益 ≈ 配置+选股+交互三项之和（差值为残差）。' },
          { label: '解释力', value: summary.explanationRatio, fmt: v => v != null ? `${(+v * 100).toFixed(1)}%` : '-', color: (summary.explanationRatio||0) > 0.5 ? '#52c41a' : (summary.explanationRatio||0) > 0.2 ? '#fa8c16' : '#ff4d4f',
            tip: '1 − |残差| / |超额收益|。>50%=可靠，20%~50%=部分解释，<20%=参考价值有限。' },
          { label: '残差', value: summary.residual,
            tip: '超额 − (配置+选股+交互)。多期累加时各期权重变化不闭合产生。' },
          { label: '估算成本', value: summary.estimatedTransactionCost, color: '#fa541c', fmt: v => v != null ? `-${(v * 100).toFixed(2)}%` : '-',
            tip: '基于调仓换手率×佣金+印花税估算。实际成本因滑点等因素可能有差异。' },
          { label: '净超额', value: summary.netExcessReturn,
            tip: '超额收益 − 估算交易成本。正值=扣除成本后仍跑赢基准。' },
        ].map((m, i) => (
          <Col key={i} span={3} style={{textAlign:'center',padding:'4px 8px',borderRight:i<7?'1px solid #e8e8e8':'none'}}>
            <Tooltip title={m.tip} placement="top" overlayStyle={{ maxWidth: 420 }}>
              <div style={{fontSize:11,color:'#888',cursor:'help'}}>
                {m.label} <span style={{fontSize:10,color:'#bbb'}}>ⓘ</span>
              </div>
            </Tooltip>
            <div style={{fontSize:14,fontWeight:600,color:m.color||signCol(m.value)}}>
              {m.fmt ? m.fmt(m.value) : fmtPct(m.value)}
            </div>
          </Col>
        ))}
      </Row>

      {/* ── 累计归因曲线 ── */}
      {cumChartOption && (
        <Card size="small" title="累计归因曲线" style={{marginBottom:16}}>
          <ReactECharts option={cumChartOption} style={{height:320}} />
        </Card>
      )}

      {/* ── 逐期瀑布图 ── */}
      {waterfallOption && (
        <Card size="small" title={`逐期归因分解（${periods.length}期）`} style={{marginBottom:16}}>
          <ReactECharts option={waterfallOption} style={{height:300}} />
        </Card>
      )}

      {/* ── 行业汇总表 ── */}
      {industrySummary.length > 0 && (
        <Card size="small" title={`行业归因汇总（${industrySummary.length}个行业）`} style={{marginBottom:16}}>
          <Table size="small" dataSource={industrySummary} pagination={false} rowKey="industry"
            scroll={{x:680, y: Math.min(industrySummary.length * 42, 380)}}
            columns={[
              { title: '行业', dataIndex: 'industry', width: 90, fixed: 'left' },
              { title: '配置效应', width: 90, render: (_,r) => <Text style={{color:signCol(r.totalAllocation)}}>{fmtPct(r.totalAllocation)}</Text> },
              { title: '选股效应', width: 90, render: (_,r) => <Text style={{color:signCol(r.totalSelection)}}>{fmtPct(r.totalSelection)}</Text> },
              { title: '交互效应', width: 90, render: (_,r) => <Text style={{color:signCol(r.totalInteraction)}}>{fmtPct(r.totalInteraction)}</Text> },
              { title: '总贡献', width: 100, render: (_,r) => <Text strong style={{color:signCol(r.totalContribution)}}>{fmtPct(r.totalContribution)}</Text>,
                sorter: (a,b) => a.totalContribution - b.totalContribution, defaultSortOrder: 'descend' },
              { title: '贡献分布', width: 160, render: (_,r) => {
                const maxAbs = Math.max(...industrySummary.map(d => Math.abs(d.totalContribution)), 1e-8);
                const pct = Math.abs(r.totalContribution) / maxAbs * 100;
                return <div style={{display:'flex',alignItems:'center',gap:4}}>
                  <div style={{flex:1,height:8,background:'#f0f0f0',borderRadius:4,overflow:'hidden'}}>
                    <div style={{width:`${pct}%`,height:'100%',background:r.totalContribution>=0?'#52c41a':'#ff4d4f',borderRadius:4}}/>
                  </div>
                </div>;
              }},
            ]}
          />
        </Card>
      )}

      {/* ── 完整归因结论（含公式卡片 + 行动建议） ── */}
      <BrinsonConclusion summary={summary} industrySummary={industrySummary} periods={periods} />
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 5b. Brinson 归因结论（含效应分解公式卡片、贡献/拖累行业、行动建议）
// 从 BacktestReport.BrinsonConclusion 迁移，略微调整以适配 BrinsonDetail 数据结构
// ══════════════════════════════════════════════════════════════════════════════
function BrinsonConclusion({ summary, industrySummary, periods }) {
  if (!summary || summary.totalExcessReturn == null) return null;

  const fmtPctV = (v) => v != null ? `${(v * 100).toFixed(2)}%` : '-';
  const fmtSigned = (v) => v >= 0 ? `+${(v * 100).toFixed(2)}` : (v * 100).toFixed(2);
  const alloc = summary.totalAllocationEffect != null ? +summary.totalAllocationEffect : 0;
  const select = summary.totalSelectionEffect != null ? +summary.totalSelectionEffect : 0;
  const interaction = summary.totalInteractionEffect != null ? +summary.totalInteractionEffect : 0;
  const excess = summary.totalExcessReturn != null ? +summary.totalExcessReturn : 0;
  const netExcess = summary.netExcessReturn != null ? +summary.netExcessReturn : 0;
  const cost = summary.estimatedTransactionCost != null ? +summary.estimatedTransactionCost : 0;
  const residual = summary.residual != null ? +summary.residual : 0;
  const inds = Array.isArray(industrySummary) ? industrySummary : [];
  const perds = Array.isArray(periods) ? periods : [];
  const hasIndData = inds.length > 0;

  function getAvgByField(x, field) {
    var avgField = 'avg' + field.charAt(0).toUpperCase() + field.slice(1);
    if (x[avgField] != null) return +x[avgField];
    var totalField = 'total' + field.charAt(0).toUpperCase() + field.slice(1);
    return perds.length > 0 ? (+x[totalField] || 0) / perds.length : (+x[totalField] || 0);
  }
  const worstSelectors = [...inds].sort((a, b) => getAvgByField(a,'Selection') - getAvgByField(b,'Selection')).slice(0, 3);
  const worstAllocators = [...inds].sort((a, b) => getAvgByField(a,'Allocation') - getAvgByField(b,'Allocation')).slice(0, 3);
  const worstContributors = [...inds].sort((a, b) => getAvgByField(a,'Contribution') - getAvgByField(b,'Contribution')).slice(0, 3);
  const topContributors = [...inds].sort((a, b) => getAvgByField(b,'Contribution') - getAvgByField(a,'Contribution')).slice(0, 3);

  function getContribAvg(x) {
    if (x.avgContribution != null) return +x.avgContribution;
    return perds.length > 0 ? (+x.totalContribution || 0) / perds.length : (+x.totalContribution || 0);
  }
  function fmtAvg(x) { return fmtPctV(getContribAvg(x)); }

  var indSummaryText = '';
  if (hasIndData) {
    var parts = [];
    if (topContributors.length > 0 && getContribAvg(topContributors[0]) > 0.0001)
      parts.push('[贡献] ' + topContributors.map(x => x.industry + '(+' + fmtAvg(x) + '/期)').join(' / '));
    if (worstContributors.length > 0 && getContribAvg(worstContributors[0]) < -0.0001)
      parts.push('[拖累] ' + worstContributors.map(x => x.industry + '(' + fmtAvg(x) + '/期)').join(' / '));
    indSummaryText = parts.join('  ');
  }

  // 从 summary 读取策略配置
  let factors = [];
  let rebalanceFreq = '?';
  let weightMode = '?';
  try {
    const cfgJson = summary?.screenConfigJson || '{}';
    const cfg = typeof cfgJson === 'string' ? JSON.parse(cfgJson) : cfgJson;
    factors = Array.isArray(cfg.factors) ? cfg.factors : [];
    rebalanceFreq = summary?.rebalanceFreq || '?';
    weightMode = summary?.weightMode || '?';
  } catch (e) {}
  const hasFactors = factors.length > 0;

  const effects = [
    { name: '配置效应', value: alloc },
    { name: '选股效应', value: select },
    { name: '交互效应', value: interaction },
  ];
  effects.sort((a, b) => Math.abs(b.value) - Math.abs(a.value));
  const posCount = effects.filter(e => e.value > 0.005).length;
  const negCount = effects.filter(e => e.value < -0.005).length;
  const allNeg = negCount === 3 && effects.every(e => e.value < -0.01);
  const costErosion = Math.abs(excess) > 0.001 ? Math.abs(cost / excess) : 0;

  var overall = '', overallColor = '';
  if (Math.abs(excess) < 0.005) { overall = '不确定（超额接近零）'; overallColor = '#8c8c8c'; }
  else if (excess > 0) {
    if (posCount >= 2 && netExcess > 0) { overall = '好（有效）'; overallColor = '#52c41a'; }
    else if (posCount >= 1) { overall = '一般（单项驱动）'; overallColor = '#d48806'; }
    else { overall = '不好（仅靠残差）'; overallColor = '#cf1322'; }
  } else {
    if (allNeg) { overall = '不好（全线亏损）'; overallColor = '#cf1322'; }
    else if (negCount >= 2) { overall = '不好（多效应拖累）'; overallColor = '#cf1322'; }
    else { overall = '一般（部分效应为负）'; overallColor = '#d48806'; }
  }

  const avgAlloc = perds.length > 0 ? alloc / perds.length : alloc;
  const avgSelect = perds.length > 0 ? select / perds.length : select;
  const avgInter = perds.length > 0 ? interaction / perds.length : interaction;

  function wpct(v) { return v != null ? (+v * 100).toFixed(1) : '-'; }
  function wdPct(x) { return wpct(x.avgWeightDiff); }
  function rePct(x) { return wpct(x.avgBenchmarkReturnExcess); }
  function srPct(x) { return wpct(x.avgSelectionReturn); }

  function buildAllocCard() {
    const abs = Math.abs(alloc);
    const title = '配置效应：' + fmtSigned(alloc) + '（平均每期 ' + fmtSigned(avgAlloc) + '）';
    if (abs < 0.005) return { title, verdict: '贡献极小，可忽略', posFactors: [], negFactors: [],
      formula: 'A = Σ [ (wp−wb) × (rb−R) ]',
      formulaLegend: 'wp=策略行业权重, wb=基准行业权重, rb=基准行业收益, R=基准总收益' };
    const posFactors = [], negFactors = [];
    const sorted = [...inds].sort((a,b) => Math.abs(getAvgByField(b,'Allocation')) - Math.abs(getAvgByField(a,'Allocation')));
    sorted.slice(0, 6).forEach(x => {
      const avgA = getAvgByField(x, 'Allocation');
      const wd = x.avgWeightDiff != null ? +x.avgWeightDiff : 0;
      const re = x.avgBenchmarkReturnExcess != null ? +x.avgBenchmarkReturnExcess : 0;
      const item = {
        name: x.industry, effect: avgA,
        reason: wd > 0 && re > 0 ? '超配' + wdPct(x) + '%且行业跑赢' + rePct(x) + '%' :
                wd < 0 && re < 0 ? '低配' + wdPct(x) + '%且行业跑输' + rePct(x) + '%' :
                wd > 0 ? '超配' + wdPct(x) + '%但行业跑输' + rePct(x) + '%' :
                '低配' + wdPct(x) + '%但行业跑赢' + rePct(x) + '%'
      };
      if (avgA > 0) posFactors.push(item); else negFactors.push(item);
    });
    return { title, verdict: alloc > 0 ? '行业配置方向正确：超配跑赢行业/低配跑输行业' : '行业配置方向错误：超配跑输行业/低配跑赢行业',
      posFactors, negFactors,
      formula: 'A = Σ [ (wp−wb) × (rb−R) ]',
      formulaLegend: 'wp=策略行业权重, wb=基准行业权重, rb=基准行业收益, R=基准总收益' };
  }

  function buildSelectCard() {
    const abs = Math.abs(select);
    const title = '选股效应：' + fmtSigned(select) + '（平均每期 ' + fmtSigned(avgSelect) + '）';
    if (abs < 0.005) return { title, verdict: '贡献极小，可忽略', posFactors: [], negFactors: [],
      formula: 'S = Σ [ wb × (rp−rb) ]',
      formulaLegend: 'wb=基准行业权重, rp=策略行业收益, rb=基准行业收益' };
    const posFactors = [], negFactors = [];
    const sorted = [...inds].sort((a,b) => Math.abs(getAvgByField(b,'Selection')) - Math.abs(getAvgByField(a,'Selection')));
    sorted.slice(0, 6).forEach(x => {
      const avgS = getAvgByField(x, 'Selection');
      const sr = x.avgSelectionReturn != null ? +x.avgSelectionReturn : 0;
      const item = {
        name: x.industry, effect: avgS,
        reason: sr > 0 ? '选股跑赢行业' + srPct(x) + '%' : '选股跑输行业' + srPct(x) + '%'
      };
      if (avgS > 0) posFactors.push(item); else negFactors.push(item);
    });
    return { title, verdict: select > 0 ? '选股能力有效：多数行业选股跑赢行业均值' : '选股跑输行业均值：所选个股整体弱于行业平均',
      posFactors, negFactors,
      formula: 'S = Σ [ wb × (rp−rb) ]',
      formulaLegend: 'wb=基准行业权重, rp=策略行业收益, rb=基准行业收益' };
  }

  function buildInteractCard() {
    const abs = Math.abs(interaction);
    const title = '交互效应：' + fmtSigned(interaction) + '（平均每期 ' + fmtSigned(avgInter) + '）';
    if (abs < 0.005) return { title, verdict: '贡献极小，可忽略', posFactors: [], negFactors: [],
      formula: 'I = Σ [ (wp−wb) × (rp−rb) ]',
      formulaLegend: 'wp=策略权重, wb=基准权重, rp=策略行业收益, rb=基准行业收益' };
    const posFactors = [], negFactors = [];
    const sorted = [...inds].sort((a,b) => Math.abs(getAvgByField(b,'Interaction')) - Math.abs(getAvgByField(a,'Interaction')));
    let insight = '';
    if (abs > 0.02) {
      const bigBoth = sorted.filter(x => Math.abs(+x.avgWeightDiff) > 0.02 && Math.abs(+x.avgSelectionReturn) > 0.02);
      insight = '交互效应绝对值较大，根因是有 ' + bigBoth.length + ' 个行业「权重偏离大 + 选股偏离大」。';
      insight += interaction > 0
        ? '\n正值：权重方向与选股方向一致，乘积效应放大了收益。'
        : '\n负值：权重方向与选股方向相反，产生对冲效果。';
      insight += '\n\n💡 同一行业在配置效应和交互效应中正负可能不同：';
      insight += '\n   配置效应用 rb−R（行业相对市场收益），交互效应用 rp−rb（策略选股相对行业收益），口径不同导致符号可能相反。';
    }
    sorted.slice(0, 6).forEach(x => {
      const avgI = getAvgByField(x, 'Interaction');
      const wd = x.avgWeightDiff != null ? +x.avgWeightDiff : 0;
      const sr = x.avgSelectionReturn != null ? +x.avgSelectionReturn : 0;
      const item = {
        name: x.industry, effect: avgI,
        reason: wd>0 && sr>0 ? '超配'+wdPct(x)+'% × 选股赢'+srPct(x)+'% = 正向协同' :
                wd<0 && sr<0 ? '低配'+wdPct(x)+'% × 选股输'+srPct(x)+'% = 正向协同' :
                wd>0 ? '超配'+wdPct(x)+'% × 选股输'+srPct(x)+'% = 互相抵消' :
                '低配'+wdPct(x)+'% × 选股赢'+srPct(x)+'% = 互相抵消'
      };
      if (avgI > 0) posFactors.push(item); else negFactors.push(item);
    });
    return { title, verdict: interaction > 0 ? '正向协同：权重与选股方向一致，乘积效应放大收益' : '互相抵消：权重与选股方向相反，产生对冲',
      posFactors, negFactors, insight,
      formula: 'I = Σ [ (wp−wb) × (rp−rb) ]',
      formulaLegend: 'wp=策略权重, wb=基准权重, rp=策略行业收益, rb=基准行业收益 — 注意与配置效应A的变量不同！' };
  }

  const effectCards = [buildAllocCard(), buildSelectCard(), buildInteractCard()];

  // 行动建议
  const actionables = [], verifications = [], missingCaps = [];
  const makeAction = (label, body) => ({ label, body });
  const makeVerify = (label, body) => ({ label, body });
  const makeMissing = (label, body) => ({ label, body });
  const factorNames = hasFactors ? factors.map(f => f.name || f.factorCode || '').filter(Boolean) : [];

  if (hasIndData && worstSelectors.length > 0) {
    const wSelNames = worstSelectors.filter(x => (+x.totalSelection||0) < -0.002)
      .map(x => x.industry + '(' + fmtPctV(x.totalSelection) + ')');
    if (wSelNames.length > 0) {
      let selBody = '以下行业选股跑输行业平均：' + wSelNames.join('、') + '。';
      if (hasFactors && factorNames.length > 0) {
        selBody += '\n• 当前因子：' + factorNames.slice(0,5).join('、') + (factorNames.length>5?' 等'+factorNames.length+'个':'');
        selBody += '\n• 操作：按行业筛选查看这些行业内各因子 IC 均值，移除 IC 为负的因子';
        selBody += '\n• 如所有因子 IC 均为负 → 考虑为该行业添加质量因子（FIN_ROE_TTM、PE_TTM）';
      } else {
        selBody += '\n• 在策略编辑页为这些行业添加质量/价值因子（如 FIN_ROE_TTM、PE_TTM）';
      }
      actionables.push(makeAction('因子调整 — 修复选股拖累', selBody));
      verifications.push(makeVerify('选股修复验证', '移除负IC因子后重跑回测 → 预期这些行业选股效应回升到 -1% 以内。'));
    }
  }

  if (hasIndData && worstAllocators.length > 0) {
    const wAllocNames = worstAllocators.filter(x => (+x.totalAllocation||0) < -0.002)
      .map(x => x.industry + '(' + fmtPctV(x.totalAllocation) + ')');
    if (wAllocNames.length > 0) {
      let allocBody = '以下行业配置方向亏损：' + wAllocNames.join('、') + '。';
      if (weightMode !== 'EQUAL' && weightMode !== '?') {
        allocBody += '\n• 当前 weightMode=' + weightMode + '（主动行业偏离）';
        allocBody += '\n• 操作：在策略编辑页将 weightMode 改为 EQUAL（等权模式下无主动择时风险）';
      } else {
        allocBody += '\n• 当前已是等权模式，配置拖累来自 Top N 选股后这些行业的股票自然偏多';
        allocBody += '\n• 操作：缩小候选池宽度，减少被迫暴露的行业数量';
      }
      actionables.push(makeAction('权重模式 — 修复配置拖累', allocBody));
      verifications.push(makeVerify('配置修复验证',
        weightMode !== 'EQUAL' && weightMode !== '?' ? '改为 EQUAL 后重跑 → 预期配置效应绝对值 < 2%。' :
        '缩小 Top N 后重跑 → 预期配置效应绝对值显著缩小。'
      ));
    }
  }

  if (costErosion > 0.3 && Math.abs(excess) > 0.005) {
    const suggestedFreq = rebalanceFreq === 'WEEKLY' ? 'BIWEEKLY' : rebalanceFreq === 'BIWEEKLY' ? 'MONTHLY' : '更低的频率';
    let costBody = '交易成本 ' + fmtPctV(cost) + ' 侵蚀了超额收益的 ' + (costErosion*100).toFixed(0) + '%。';
    costBody += '\n• 当前调仓频率：' + rebalanceFreq;
    costBody += '\n• 操作：在策略编辑页将调仓频率改为 ' + suggestedFreq + '（预期成本降低约40%）';
    actionables.push(makeAction('调仓频率 — 降低交易成本', costBody));
    verifications.push(makeVerify('成本优化验证', '改为 ' + suggestedFreq + ' 后重跑 → 预期净超额从 ' + fmtSigned(netExcess) + ' 回升到正值区间。'));
  }

  if (allNeg) {
    actionables.push(makeAction('全线亏损 — 从零重建策略',
      '三效应全部负贡献，当前因子组合在回测期内全面失效。建议：\n'
      + '• 新建最小化策略：仅保留1-2个基本面因子（FIN_ROE_TTM + PE_TTM）\n'
      + '• weightMode 设为 EQUAL，调仓频率设为 MONTHLY\n'
      + '• 跑一遍回测看选股效应是否转正，然后逐个加回原来的因子'
    ));
  }

  if (hasIndData) {
    const sameBothWrong = worstContributors.filter(x => (+x.totalAllocation||0) < -0.002 && (+x.totalSelection||0) < -0.002);
    if (sameBothWrong.length > 0) {
      missingCaps.push(makeMissing('行业择时信号',
        sameBothWrong.map(x => x.industry).join('、') + ' 配置和选股双输 — 整体走势判断错误，需独立行业择时能力：\n'
        + '• 行业轮动因子：过去N日行业指数收益率截面排名\n'
        + '• 与选股因子独立计算，各自贡献权重'
      ));
    }
  }

  if (!hasFactors && negCount > 0) {
    missingCaps.push(makeMissing('因子配置数据', '未解析到策略因子配置（screenConfigJson 为空），行动建议仅为通用方向。'));
  }

  const lowCredibility = summary.explanationRatio != null && +summary.explanationRatio < 0.3;
  const modelExplained = lowCredibility ? (+summary.explanationRatio * 100).toFixed(1) : null;
  const [showActions, setShowActions] = useState(!lowCredibility);

  return (
    <Card size="small" style={{ marginTop: 16, borderLeft: '3px solid ' + overallColor }} title={<span style={{fontSize:14}}>归因结论</span>}>
      {/* 关键指标概览 */}
      <div style={{display:'flex',flexWrap:'wrap',gap:0,marginBottom:12,background:'#fafafa',padding:'4px 0',borderRadius:4}}>
        {[
          { label: '超额收益', val: excess, fmt: fmtSigned,
            tip: '策略累计收益 − 基准累计收益。三效应之和 ≈ 超额（差值为残差）。' },
          { label: '配置效应', val: alloc, fmt: fmtSigned,
            tip: '公式: Σ(策略权重−基准权重)×(行业基准收益−基准总收益)。正数 = 超配强势行业/低配弱势行业。' },
          { label: '选股效应', val: select, fmt: fmtSigned,
            tip: '公式: Σ 基准权重×(策略行业内收益−行业基准收益)。正数 = 所选个股跑赢行业均值。' },
          { label: '交互效应', val: interaction, fmt: fmtSigned,
            tip: '公式: Σ(权重差)×(选股收益差)。正数 = 权重方向与选股方向一致（乘数效应放大）。' },
          Math.abs(residual) > 0.001 ? { label: '残差', val: residual, fmt: fmtSigned,
            tip: '超额 − (配置+选股+交互)。多期累加时由于各期权重变化不闭合产生残差。' } : null,
          summary.explanationRatio != null ? { label: '解释力', val: (+summary.explanationRatio * 100), fmt: v => v.toFixed(1)+'%',
            tip: '1 − |残差| / |超额收益|。<30% 时模型参考价值有限。' } : null,
        ].filter(Boolean).map((item, idx, arr) => {
          const isExpl = item.label === '解释力';
          const valColor = isExpl
            ? (item.val >= 70 ? '#52c41a' : item.val < 30 ? '#cf1322' : '#faad14')
            : (item.val >= 0 ? '#52c41a' : '#cf1322');
          return (
            <div key={idx} style={{textAlign:'center',padding:'2px 10px',borderRight:idx<arr.length-1?'1px solid #e8e8e8':'none',whiteSpace:'nowrap',cursor:item.tip?'help':'default'}}>
              <Tooltip title={item.tip} placement="top" overlayStyle={{ maxWidth: 420 }}>
                <div style={{fontSize:12,color:'#888'}}>
                  {item.label}{item.tip && <span style={{marginLeft:2,color:'#bbb',fontSize:10}}>ⓘ</span>}
                </div>
              </Tooltip>
              <div style={{fontSize:14,fontWeight:600,color:valColor}}>{item.fmt(item.val)}</div>
            </div>
          );
        })}
      </div>

      {/* 行业摘要 */}
      {indSummaryText && (
        <div style={{background:'#f0f5ff',padding:'6px 10px',borderRadius:4,border:'1px solid #c6daff',marginBottom:10,fontSize:12,lineHeight:1.6,color:'#2b4acb'}}>
          {indSummaryText}
        </div>
      )}

      {/* 总体判断 */}
      <div style={{
        background: overallColor==='#52c41a'?'#f6ffed':overallColor==='#d48806'?'#fff7e6':overallColor==='#8c8c8c'?'#fafafa':'#fff2f0',
        padding:'10px 12px',borderRadius:4,
        border:'1px solid '+(overallColor==='#52c41a'?'#b7eb8f':overallColor==='#d48806'?'#ffd591':overallColor==='#8c8c8c'?'#d9d9d9':'#ffccc7'),
        marginBottom:12,fontWeight:700,fontSize:14
      }}>
        总体判断：<span style={{color:overallColor}}>{overall}</span>
        {summary.explanationRatio != null && +summary.explanationRatio < 0.3 && (
          <span style={{fontWeight:400,fontSize:12,color:'#8c8c8c'}}>
            {' （注意：模型解释力仅 ' + (+summary.explanationRatio*100).toFixed(1) + '%，归因参考价值有限）'}
          </span>
        )}
      </div>

      {/* 低解释力警告 */}
      {summary.explanationRatio != null && +summary.explanationRatio < 0.3 && (
        <Alert type="warning" showIcon style={{marginBottom:12,fontSize:12}}
          message="此策略收益来源不在行业配置/选股维度，Brinson 归因参考价值有限"
          description={
            <div style={{fontSize:12,lineHeight:1.8}}>
              当前模型解释力仅 <b>{(+summary.explanationRatio*100).toFixed(1)}%</b>，
              超额收益主要来自行业配置以外的因素（动量/波动率/换手择时等因子暴露）。
              建议打开「因子风格归因」弹窗查看 β 暴露分析。
            </div>
          }
        />
      )}

      {/* 三效应关系说明（当有大正值抵消大负值时） */}
      {(() => {
        const absAlloc = Math.abs(alloc), absSelect = Math.abs(select), absInter = Math.abs(interaction);
        const hasBigOffset = (absAlloc>0.05&&absInter>0.05)||(absSelect>0.05&&absInter>0.05)||(absAlloc>0.05&&absSelect>0.05);
        if (!hasBigOffset) return null;
        const modelSum = alloc + select + interaction;
        const parts = [];
        if (Math.abs(alloc)>0.005) parts.push('配置 '+fmtSigned(alloc));
        if (Math.abs(select)>0.005) parts.push('选股 '+fmtSigned(select));
        if (Math.abs(interaction)>0.005) parts.push('交互 '+fmtSigned(interaction));
        let relation = '三效应加法关系：' + parts.join(' + ') + ' ≈ ' + fmtSigned(modelSum) + '。';
        if (Math.abs(residual)>0.05) relation += ' 实际超额 = ' + fmtSigned(excess) + '，含残差 ' + fmtSigned(residual) + '。';
        return <div style={{background:'#f9f0ff',padding:'8px 12px',borderRadius:4,border:'1px solid #d3adf7',marginBottom:12,fontSize:12,lineHeight:1.8,color:'#531dab',whiteSpace:'pre-wrap'}}>{relation}</div>;
      })()}

      {/* 三效应详情卡片 */}
      <div style={{display:'flex',gap:8,marginBottom:8}}>
        {[effectCards[0], effectCards[1]].map((card, idx) => (
          <div key={idx} style={{flex:1,minWidth:0}}>
            <Card size="small" type="inner" title={<span style={{fontWeight:700,fontSize:13}}>{card.title}</span>} style={{background:'#fff'}}>
              <div style={{fontSize:12,color:'#888',marginBottom:4}}>{card.verdict}</div>
              <div style={{fontSize:11,color:'#aaa',marginBottom:4,fontFamily:'monospace',background:'#fafafa',padding:'2px 6px',borderRadius:3}}>{card.formula}</div>
              {card.formulaLegend && <div style={{fontSize:10,color:'#bbb',marginBottom:8,paddingLeft:6,lineHeight:1.5}}>{card.formulaLegend}</div>}
              {card.posFactors?.length > 0 && (
                <div style={{marginBottom:4}}>
                  <Tag color="success" style={{marginRight:4,fontSize:10}}>正贡献</Tag>
                  {card.posFactors.map((f,fi) => <div key={fi} style={{fontSize:12,margin:'2px 0',paddingLeft:16,lineHeight:1.6}}><b>{f.name}</b>: {fmtPctV(f.effect)}/期 — {f.reason}</div>)}
                </div>
              )}
              {card.negFactors?.length > 0 && (
                <div>
                  <Tag color="error" style={{marginRight:4,fontSize:10}}>负贡献</Tag>
                  {card.negFactors.map((f,fi) => <div key={fi} style={{fontSize:12,margin:'2px 0',paddingLeft:16,lineHeight:1.6}}><b>{f.name}</b>: {fmtPctV(f.effect)}/期 — {f.reason}</div>)}
                </div>
              )}
              {!card.posFactors?.length && !card.negFactors?.length && <div style={{fontSize:12,color:'#999'}}>无显著行业贡献</div>}
            </Card>
          </div>
        ))}
      </div>
      {/* 交互效应独占一行 */}
      {(() => {
        const card = effectCards[2];
        return (
          <Card size="small" type="inner" title={<span style={{fontWeight:700,fontSize:13}}>{card.title}</span>} style={{marginBottom:8,background:'#fff'}}>
            <div style={{fontSize:12,color:'#888',marginBottom:4}}>{card.verdict}</div>
            <div style={{fontSize:11,color:'#aaa',marginBottom:4,fontFamily:'monospace',background:'#fafafa',padding:'2px 6px',borderRadius:3}}>{card.formula}</div>
            {card.formulaLegend && <div style={{fontSize:10,color:'#bbb',marginBottom:8,paddingLeft:6,lineHeight:1.5}}>{card.formulaLegend}</div>}
            {card.insight && <div style={{fontSize:12,color:'#d48806',background:'#fffbe6',padding:'6px 8px',borderRadius:3,border:'1px solid #ffe58f',marginBottom:8,lineHeight:1.6,whiteSpace:'pre-wrap'}}>{card.insight}</div>}
            {card.posFactors?.length > 0 && (
              <div style={{marginBottom:4}}>
                <Tag color="success" style={{marginRight:4,fontSize:10}}>正贡献</Tag>
                {card.posFactors.map((f,fi) => <div key={fi} style={{fontSize:12,margin:'2px 0',paddingLeft:16,lineHeight:1.6}}><b>{f.name}</b>: {fmtPctV(f.effect)}/期 — {f.reason}</div>)}
              </div>
            )}
            {card.negFactors?.length > 0 && (
              <div>
                <Tag color="error" style={{marginRight:4,fontSize:10}}>负贡献</Tag>
                {card.negFactors.map((f,fi) => <div key={fi} style={{fontSize:12,margin:'2px 0',paddingLeft:16,lineHeight:1.6}}><b>{f.name}</b>: {fmtPctV(f.effect)}/期 — {f.reason}</div>)}
              </div>
            )}
            {!card.posFactors?.length && !card.negFactors?.length && <div style={{fontSize:12,color:'#999'}}>无显著行业贡献</div>}
          </Card>
        );
      })()}

      {/* 行动建议（可折叠） */}
      {actionables.length > 0 ? (
        <div style={{marginBottom:12}}>
          <div onClick={() => setShowActions(!showActions)} style={{
            display:'flex',alignItems:'center',gap:8,cursor:'pointer',
            padding:'8px 12px',borderRadius:4,
            background:lowCredibility?'#fafafa':'#fff2f0',
            border:'1px solid '+(lowCredibility?'#d9d9d9':'#ffccc7'),userSelect:'none',
          }}>
            <span style={{fontSize:13,fontWeight:700,color:lowCredibility?'#595959':'#cf1322'}}>
              {lowCredibility ? '观察到的问题 & 推断性建议' : '可立即操作（策略编辑页直接改）'}
              {lowCredibility && <Tag color="warning" style={{marginLeft:6,fontSize:10}}>低可信度</Tag>}
            </span>
            <span style={{flex:1,fontSize:11,color:'#8c8c8c'}}>
              {lowCredibility ? 'Brinson 模型解释力仅 '+modelExplained+'%，行业/选股维度无法有效解析此策略' : '以下操作可直接提升策略表现'}
            </span>
            <span style={{fontSize:11,color:'#8c8c8c'}}>{showActions ? '收起 ▲' : '展开 ▼'}</span>
          </div>
          {showActions && (
            <div style={{marginTop:8}}>
              {lowCredibility && (
                <div style={{background:'#fff7e6',padding:'8px 12px',borderRadius:4,border:'1px solid #ffd591',marginBottom:12,fontSize:12,lineHeight:1.7,color:'#ad6800'}}>
                  <b>归因模型解释力仅 {modelExplained}%：</b>绿色=事实观察（可信），黄色=模型推断（仅供参考）
                </div>
              )}
              <div style={{marginBottom:12}}>
                {actionables.map((a,i) => (
                  <div key={i} style={{background:lowCredibility?'#fafafa':'#fff2f0',padding:'8px 10px',borderRadius:4,border:'1px solid '+(lowCredibility?'#d9d9d9':'#ffccc7'),marginBottom:6,fontSize:12,lineHeight:1.7}}>
                    <div style={{fontWeight:700,marginBottom:2,color:lowCredibility?'#595959':'#262626'}}>{a.label}</div>
                    <div style={{whiteSpace:'pre-wrap',color:'#434343'}}>{a.body}</div>
                  </div>
                ))}
              </div>
              {verifications.length > 0 && (
                <div style={{marginBottom:12}}>
                  <div style={{fontWeight:700,marginBottom:6,color:lowCredibility?'#8c8c8c':'#0958d9',fontSize:13}}>
                    如何验证修复有效{lowCredibility && <Tag color="warning" style={{marginLeft:6,fontSize:10}}>低可信度</Tag>}
                  </div>
                  {verifications.map((v,i) => (
                    <div key={i} style={{background:lowCredibility?'#fafafa':'#e6f4ff',padding:'6px 10px',borderRadius:4,border:'1px solid '+(lowCredibility?'#d9d9d9':'#91caff'),marginBottom:4,fontSize:12,lineHeight:1.7}}>
                      <span style={{fontWeight:600,color:lowCredibility?'#8c8c8c':'#262626'}}>{v.label}：</span>
                      <span style={{color:lowCredibility?'#8c8c8c':'#434343'}}>{v.body}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      ) : null}

      {/* 需新增的能力 */}
      {missingCaps.length > 0 && (
        <div style={{marginBottom:12}}>
          <div style={{fontWeight:700,marginBottom:6,color:'#8c8c8c',fontSize:13}}>需新增的策略能力（建议后续开发）</div>
          {missingCaps.map((m,i) => (
            <div key={i} style={{background:'#fafafa',padding:'6px 10px',borderRadius:4,border:'1px solid #d9d9d9',marginBottom:4,fontSize:12,lineHeight:1.7}}>
              <div style={{fontWeight:600,marginBottom:2,color:'#595959'}}>{m.label}</div>
              <div style={{whiteSpace:'pre-wrap',color:'#8c8c8c'}}>{m.body}</div>
            </div>
          ))}
        </div>
      )}

      <Divider style={{margin:'8px 0'}}/>
      <Text type="secondary" style={{fontSize:11}}>
        多期 Brinson 分解：配置效应 A = Σ(wp−wb)×(rb−R)，选股效应 S = Σwb×(rp−rb)，交互效应 I = Σ(wp−wb)×(rp−rb)。
        基准默认为全市场行业等权组合。残差来自多期几何/算术不匹配。
      </Text>
    </Card>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 6. 因子归因详情（含结论组件）
// ══════════════════════════════════════════════════════════════════════════════

// 已知因子描述映射（当后端返回的 description 为空时兜底）
const KNOWN_FACTOR_DESC = {
  MOM20: '20日动量 — 追涨杀跌收益',
  VOL20: '20日波动率 — 高波动股短期溢价',
  SIZE: '总市值 — 小盘股溢价',
  TURN20: '20日换手率 — 流动性溢价',
  MOM60: '60日动量 — 中期趋势跟随',
  VOL60: '60日波动率 — 中长期波动溢价',
};

function FactorDetail({ taskId }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!taskId) return;
    backtestApi.getFactorAttribution(taskId)
      .then(setData)
      .catch(err => setError(err.message || '因子归因加载失败'))
      .finally(() => setLoading(false));
  }, [taskId]);

  // ⚠️ hooks 必须在早期 return 之前，确保每次渲染调用顺序一致
  const summary = data?.summary || {};
  const regressionDetail = data?.regressionDetail || {};
  const rawBetas = data?.factorContributions || [];
  const contributions = data?.periodContributions || [];
  const observationDays = data?.observationDays || 252;

  // 计算年化贡献和贡献占比 + 补齐 description
  const betas = useMemo(() => {
    if (!rawBetas.length) return [];
    const totalContrib = summary.totalFactorContribution || 0;
    const absTotal = Math.abs(totalContrib);
    return rawBetas.map(b => ({
      ...b,
      // 描述 fallback：后端返回 → 前端映射 → factorName → factorCode
      description: b.description || KNOWN_FACTOR_DESC[b.factorCode] || b.factorName || '',
      annualizedContribution: (b.contribution || 0) / Math.max(observationDays, 1) * 252,
      contributionRatio: absTotal > 1e-8 ? (b.contribution || 0) / totalContrib : 0,
    }));
  }, [rawBetas, summary.totalFactorContribution, observationDays]);

  // Beta 暴露柱状图
  const betaChartOption = useMemo(() => {
    if (!betas.length) return {};
    const factorNames = betas.map(b => b.factorName || b.factorCode || '');
    const betaVals = betas.map(b => (b.beta || 0));
    const tStats = betas.map(b => Math.abs(b.tStat || 0));
    const colors = betaVals.map(v => v >= 0 ? RED : GREEN);

    // 防止柱子+标签超出图表区域
    const maxAbs = Math.max(...betaVals.map(Math.abs), 0.01);
    const yMax = maxAbs * 1.3;

    return {
      tooltip: {
        trigger: 'axis',
        formatter: function(params) {
          const b = betas[params[0].dataIndex];
          return `<b>${b.factorName || b.factorCode}</b><br/>
            ${b.description ? b.description + '<br/>' : ''}
            β = ${fmt(b.beta, 4)}<br/>
            t = ${fmt(b.tStat, 2)}<br/>
            显著: ${Math.abs(b.tStat || 0) >= 1.96 ? '是 ★' : '否'}<br/>
            因子收益: ${fmtPct(b.totalFactorReturn)}<br/>
            年化贡献: ${fmtPct(b.annualizedContribution)}<br/>
            贡献占比: ${fmtPct(b.contributionRatio)}`;
        },
      },
      grid: { left: 70, right: 30, top: 48, bottom: 32 },
      xAxis: {
        type: 'category', data: factorNames,
        axisLabel: { fontSize: 12 },
      },
      yAxis: { type: 'value', name: 'β 系数', min: -yMax, max: yMax },
      series: [{
        type: 'bar', data: betaVals,
        itemStyle: { color: function(p) { return colors[p.dataIndex]; } },
        barMaxWidth: 50,
        markLine: {
          silent: true,
          data: [{ yAxis: 0, lineStyle: { color: '#999', type: 'dashed' } }],
        },
        label: {
          show: true, position: 'top', fontSize: 10,
          formatter: function(p) {
            const t = tStats[p.dataIndex];
            return t >= 1.96 ? '★' : '';
          },
          color: '#fa8c16',
        },
      }],
    };
  }, [betas]);

  if (loading) return <div style={{textAlign:'center',padding:40}}><Spin tip="加载因子归因..." /></div>;
  if (error) return <Alert type="error" message={error} showIcon />;
  if (!data) return <Alert type="info" message="暂无因子归因数据" showIcon />;

  return (
    <div>
      {/* ── 归因结论（关键指标 + 总体判断） ── */}
      <FactorConclusionHeader betas={betas} summary={summary} regressionDetail={regressionDetail} />

      {/* ── 因子暴露柱状图 ── */}
      <ReactECharts option={betaChartOption} style={{ height: 250 }} />
      <Text type="secondary" style={{ fontSize: 11, display: 'block', textAlign: 'center', marginTop: 4 }}>
        ★ 表示 t ≥ 1.96（95% 置信显著）的因子
      </Text>

      <Table
        size="small" style={{ marginTop: 12 }}
        dataSource={betas} pagination={false}
        rowKey={(r) => r.factorCode}
        columns={[
          { title: '因子', dataIndex: 'factorName', width: 130, render: (v, r) => {
            const sig = Math.abs(r.tStat || 0) >= 1.96;
            return <span>{v || r.factorCode}{sig && <Tag color="orange" style={{marginLeft:4,fontSize:10,padding:'0 4px',lineHeight:'16px'}}>显著</Tag>}</span>;
          }},
          { title: '含义', dataIndex: 'description', width: 220, ellipsis: true,
            render: v => <Text type="secondary" style={{fontSize:11}}>{v}</Text> },
          { title: (<Tooltip overlayStyle={{maxWidth:460}} title={
                <div style={{lineHeight:1.9,fontSize:12}}>
                  <p style={{margin:'0 0 6px',fontWeight:700}}>β 暴露（Beta Exposure）</p>
                  <p style={{margin:'2px 0'}}><b>含义：</b>策略对单个因子的敏感度。β=0.5 表示该因子值每变化 1 个标准差，策略日超额收益平均变化 0.5 个标准差。</p>
                  <p style={{margin:'2px 0'}}><b>价值：</b>β 回答了「策略到底暴露在什么逻辑上」。比如 MOM β 高 → 策略追涨杀跌；VOL β 负 → 策略避开高波动股。</p>
                  <p style={{margin:'2px 0'}}><b>阈值：</b><br/>
                    · |β| &lt; 0.1 = 几乎无暴露<br/>
                    · 0.1~0.5 = 温和暴露<br/>
                    · 0.5~1.5 = 明显暴露<br/>
                    · &gt; 1.5 = 高度集中暴露（风险集中度高）</p>
                  <p style={{margin:'2px 0'}}><b>解读：</b>β 为正 → 偏好高因子值股票（如 MOM 正=追涨）；β 为负 → 偏好低因子值股票（如 SIZE 负=大盘偏好）。</p>
                  <p style={{margin:'2px 0'}}><b>⚠ 注意：</b>β 必须结合 t 值判断——|t|&lt;1.96 的 β 可能只是噪声，不代表真实暴露。</p>
                </div>
              }><span style={{cursor:'help'}}>β 暴露 <QuestionCircleOutlined style={{fontSize:10,color:'#8c8c8c'}}/></span></Tooltip>), dataIndex: 'beta', width: 85, align: 'right',
            render: v => <Text strong style={{color: (v||0)>=0?RED:GREEN}}>{fmt(v, 4)}</Text> },
          { title: 't 值', dataIndex: 'tStat', width: 70, align: 'right',
            render: v => <Text strong style={{color: Math.abs(v||0)>=1.96?'#fa8c16':undefined}}>{fmt(v, 2)}</Text> },
          { title: '因子累计收益', dataIndex: 'totalFactorReturn', width: 100, align: 'right',
            render: v => <Text style={{fontWeight:600}}>{fmtPct(v)}</Text> },
          { title: '年化贡献', dataIndex: 'annualizedContribution', width: 90, align: 'right',
            render: v => <Text strong style={{color:(v||0)>=0?RED:GREEN}}>{fmtPct(v)}</Text> },
          { title: '贡献占比', dataIndex: 'contributionRatio', width: 85, align: 'right',
            render: v => <Text style={{fontWeight:Math.abs(v||0)>0.3?700:400}}>{fmtPct(v)}</Text> },
        ]}
      />

      {/* ── 因子贡献分解 + 可操作建议 ── */}
      <FactorConclusionBody betas={betas} summary={summary} regressionDetail={regressionDetail}
        observationDays={observationDays} />
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 6b. 因子归因结论头部（关键指标 + 总体判断）
// ══════════════════════════════════════════════════════════════════════════════
function FactorConclusionHeader({ betas, summary, regressionDetail }) {
  if (!betas.length) return null;

  const fmtPctV = (v) => v != null ? `${(v * 100).toFixed(2)}%` : '-';
  const fmtSigned = (v) => v >= 0 ? `+${(v * 100).toFixed(2)}` : (v * 100).toFixed(2);

  const totalExcess = summary.totalExcessReturn != null ? +summary.totalExcessReturn : 0;
  const totalContrib = summary.totalFactorContribution != null ? +summary.totalFactorContribution : 0;
  const residual = summary.residual != null ? +summary.residual : 0;
  const rSquared = regressionDetail.rSquared != null ? +regressionDetail.rSquared : 0;
  const alpha = regressionDetail.annualizedAlpha != null ? +regressionDetail.annualizedAlpha : 0;
  const dailyAlpha = (alpha / 252) || 0;

  // 显著因子
  const significantBetas = betas.filter(b => Math.abs(b.tStat || 0) >= 1.96);

  // 按贡献绝对值排序
  const rankedByContrib = [...betas].sort((a, b) =>
    Math.abs(b.annualizedContribution || 0) - Math.abs(a.annualizedContribution || 0));
  const topContributor = rankedByContrib[0];
  const worstContributor = rankedByContrib[rankedByContrib.length - 1];

  // 按 β 绝对值排序
  const rankedByBeta = [...betas].sort((a, b) => Math.abs(b.beta || 0) - Math.abs(a.beta || 0));
  const highestBeta = rankedByBeta[rankedByBeta.length - 1];
  const lowestBeta = rankedByBeta[0];

  // ═══ 总体判断 ═══
  const absExcess = Math.abs(totalExcess);
  const highR2 = rSquared > 0.3;
  const mediumR2 = rSquared >= 0.15 && rSquared <= 0.3;
  const lowR2 = rSquared < 0.15;
  const alphaPositive = alpha > 0.01;
  const alphaNegative = alpha < -0.01;
  const alphaNeutral = Math.abs(alpha) <= 0.01;
  const hasSigFactors = significantBetas.length > 0;

  let overall = '', overallColor = '#8c8c8c', overallDesc = '';

  if (absExcess < 0.005) {
    overall = '不确定（超额接近零）'; overallColor = '#8c8c8c';
    overallDesc = '策略超额收益接近于零，归因分析参考价值有限。';
  } else if (highR2) {
    if (hasSigFactors) {
      overall = '因子驱动型策略（有效）'; overallColor = '#52c41a';
      const sigNames = significantBetas.map(b => b.factorName || b.factorCode).join('、');
      overallDesc = `R²=${(rSquared*100).toFixed(1)}%，策略超额收益主要由因子暴露驱动。显著因子：${sigNames}。`;
    } else {
      overall = '高拟合但无显著因子（矛盾）'; overallColor = '#fa8c16';
      overallDesc = 'R²高但所有因子t值均未达显著水平，可能存在共线性问题（因子之间高度相关）。';
    }
  } else if (mediumR2) {
    overall = '部分因子驱动（混合型）'; overallColor = '#fa8c16';
    overallDesc = `R²=${(rSquared*100).toFixed(1)}%，因子解释了部分超额，α 占比 ${Math.abs(residual) > 0.001 ? ((residual/totalExcess)*100).toFixed(0) : 0}%。`;
  } else {
    overall = '非因子驱动（α 主导）'; overallColor = alphaPositive ? '#cf1322' : '#ff4d4f';
    overallDesc = `R²=${(rSquared*100).toFixed(1)}%，四因子几乎无法解释超额收益。收益来自因子模型外的选股能力或未建模因子。`;
  }

  // ═══ 各因子贡献卡片 ═══
  const buildFactorCard = (b) => {
    const sig = Math.abs(b.tStat || 0) >= 1.96;
    const betaVal = b.beta || 0;
    const contrib = b.annualizedContribution || 0;
    const contribRatio = b.contributionRatio || 0;
    const factorRet = b.totalFactorReturn || 0;
    const absContrib = Math.abs(contrib);
    const absRatio = Math.abs(contribRatio);

    let verdict = '', verdictColor = '';
    if (!sig) {
      verdict = '不显著：因子暴露不稳定，统计上无显著偏离零';
      verdictColor = '#8c8c8c';
    } else if (contrib > 0.01 && absRatio > 0.3) {
      verdict = `核心贡献因子：贡献 ${fmtPctV(contrib)}/年，占因子解释的 ${(absRatio*100).toFixed(0)}%`;
      verdictColor = '#52c41a';
    } else if (contrib > 0.005) {
      verdict = '次要正贡献';
      verdictColor = '#52c41a';
    } else if (contrib < -0.01) {
      verdict = `拖累：年化拖累 ${fmtPctV(contrib)}，β方向与因子收益方向冲突`;
      verdictColor = '#cf1322';
    } else {
      verdict = '贡献微弱';
      verdictColor = '#8c8c8c';
    }

    return { factor: b, sig, betaVal, contrib, contribRatio, factorRet, absContrib, absRatio, verdict, verdictColor };
  };

  const factorCards = betas.map(b => buildFactorCard(b)).sort((a, b) =>
    Math.abs(b.contrib) - Math.abs(a.contrib));

  // ═══ 行动建议 ═══
  const actionables = [], verifications = [];
  const makeAction = (label, body) => ({ label, body });
  const makeVerify = (label, body) => ({ label, body });

  // 1. 检查拖累因子
  if (lowR2 || mediumR2) {
    const dragFactors = factorCards.filter(f => f.contrib < -0.005);
    if (dragFactors.length > 0) {
      let body = '以下因子产生年化负贡献：\n';
      dragFactors.forEach(f => {
        body += `• ${f.factor.factorName || f.factor.factorCode}：贡献 ${fmtPctV(f.contrib)}/年 (β=${fmt(f.betaVal,3)}, t=${fmt(f.factor.tStat,2)})`;
        if (f.factor.factorRet < 0) body += ' — 因子本身在亏钱';
        else body += ' — 因子在赚钱但策略暴露方向反了';
        body += '\n';
      });
      body += '\n操作：在策略编辑页中调整这些因子的方向（如动量因子设为 DESC 改为 ASC），或直接移除此因子。';
      actionables.push(makeAction('因子调整 — 修复负贡献因子', body));
      verifications.push(makeVerify('移除/反转拖累因子', `重跑回测 → 预期 α 收敛到 ±0.5% 以内，R² 应提升。`));
    }
  }

  // 2. α 主导时建议扩展因子
  if (lowR2 && absExcess > 0.01) {
    let body = `四因子仅解释 ${(rSquared*100).toFixed(1)}% 超额，${(alpha*100).toFixed(2)}% 的 α 说明策略有显著的因子外选股能力。\n`;
    body += '建议扩展因子维度以定位真实收益源：\n';
    body += '• 质量因子：FIN_ROE_TTM（ROE）、FIN_GROSS_MARGIN（毛利率）\n';
    body += '• 价值因子：PE_TTM（市盈率）、PB_LF（市净率）\n';
    body += '• 情绪因子：资金流向、机构持仓变化\n';
    body += '• 扩展后重跑因子归因，观察 R² 是否提升到 30%+';
    actionables.push(makeAction('因子扩展 — 定位 α 真实来源', body));
  }

  // 3. 因子暴露集中警告
  const concentratedBeta = factorCards.filter(f => Math.abs(f.betaVal) > 1.5);
  if (concentratedBeta.length > 0) {
    let body = '以下因子 β 绝对值 > 1.5，策略在该因子上暴露过大：\n';
    concentratedBeta.forEach(f => {
      body += `• ${f.factor.factorName}：β=${fmt(f.betaVal,3)}\n`;
    });
    body += '\n操作：在策略编辑页降低高风险因子的权重（如从 0.5 降到 0.2），降低集中暴露。\n';
    body += '⚠️ 改完后必须在模拟盘建副本做 A/B 回测对比，确认夏普和回撤确实改善再正式替换。';
    actionables.push(makeAction('风控 — 降低因子暴露集中度', body));
  }

  // 4. 无显著因子但 R² 还行 → 可能有共线性
  if (mediumR2 && !hasSigFactors) {
    let body = `R²=${(rSquared*100).toFixed(1)}% 但无单个因子显著 — 典型的多重共线性症状。\n`;
    body += '可能原因：MOM 和 TURN 高度正相关（高动量股同时也高换手），Vol 和 MOM 负相关（强趋势时波动小）。\n';
    body += '\n操作：\n';
    body += '• 检查各因子之间的相关系数矩阵\n';
    body += '• 将高度相关的因子合并为一个复合因子\n';
    body += '• 或改用 Ridge 回归（惩罚共线变量）替代 OLS';
    actionables.push(makeAction('方法 — 处理因子共线性', body));
  }

  // 5. 显著因子优化
  if (hasSigFactors) {
    const posSig = significantBetas.filter(b => (b.annualizedContribution || 0) > 0.01);
    if (posSig.length > 0) {
      let body = '以下因子贡献显著且正向，建议保留并增强：\n';
      posSig.forEach(f => {
        body += `• ${f.factorName || f.factorCode}：贡献 ${fmtPctV(f.annualizedContribution)}/年 (t=${fmt(f.tStat,2)})\n`;
      });
      body += '\n操作：在策略编辑页适当提高这些因子的权重上限。';
      actionables.push(makeAction('因子优化 — 强化有效因子', body));
    }
  }

  // ═══ 判断值得信任程度 ═══
  const trustLevel = highR2 ? '高' : mediumR2 ? '中' : '低';
  const trustColor = highR2 ? '#52c41a' : mediumR2 ? '#fa8c16' : '#ff4d4f';

  return (
    <Card size="small" style={{ marginTop: 16, borderLeft: '3px solid ' + overallColor }}
      title={<span style={{fontSize:14}}>归因结论</span>}>

      {/* 关键指标概览 */}
      <div style={{display:'flex',flexWrap:'wrap',gap:0,marginBottom:12,background:'#fafafa',padding:'4px 0',borderRadius:4}}>
        {[
          { label: '超额收益', val: totalExcess, fmt: fmtSigned,
            tip: (
              <div style={{maxWidth:560,lineHeight:1.9,fontSize:13}}>
                <p style={{margin:'0 0 6px',fontWeight:700}}>超额收益（Excess Return）</p>
                <p style={{margin:'2px 0'}}><b>含义：</b>策略累计收益 − 基准累计收益，衡量策略是否跑赢基准。</p>
                <p style={{margin:'2px 0'}}><b>价值：</b>超额收益是归因分析的「被解释变量」——整篇报告围绕「超额从哪来」展开。</p>
                <p style={{margin:'2px 0'}}><b>公式：</b>Σ(策略日收益 − 基准日收益)，累计复利。</p>
                <p style={{margin:'2px 0'}}><b>阈值：</b>±2% 以内 = 贴近基准；±2%~10% = 有一定超额；±10% 以上 = 显著超额（正或负）。</p>
                <p style={{margin:'2px 0'}}><b>当前：</b>{fmtSigned(totalExcess)}，属于 {
                  Math.abs(totalExcess) < 0.02 ? '贴近基准，策略与大盘走势几乎一致' :
                  Math.abs(totalExcess) < 0.1 ? '有一定超额，策略有独立于大盘的表现' :
                  '显著超额，策略与基准走势差异较大'
                }。</p>
                <p style={{margin:'2px 0'}}><b>判断：</b>超额为正 → 跑赢基准；为负 → 未跑赢。但如果 R² 很低，正超额可能只是运气而非可复制的策略能力。</p>
              </div>
            ) },
          { label: '因子贡献', val: totalContrib, fmt: fmtSigned,
            tip: (
              <div style={{maxWidth:560,lineHeight:1.9,fontSize:13}}>
                <p style={{margin:'0 0 6px',fontWeight:700}}>因子贡献（Factor Contribution）</p>
                <p style={{margin:'2px 0'}}><b>含义：</b>四个因子 β × 因子收益的总和，即模型能解释的超额收益部分。</p>
                <p style={{margin:'2px 0'}}><b>价值：</b>度量策略收益中「赚因子暴露的钱」的占比。这是可复制、可量化的收益源。</p>
                <p style={{margin:'2px 0'}}><b>公式：</b>Σ(β_f × FactorReturn_f)，f ∈ [MOM, VOL, SIZE, TURN]</p>
                <p style={{margin:'2px 0'}}><b>阈值：</b>|因子贡献/超额收益| &gt; 70% = 因子主导；30%~70% = 混合；&lt; 30% = α 主导。</p>
                <p style={{margin:'2px 0'}}><b>当前：</b>{fmtSigned(totalContrib)}，占超额的 {Math.abs(totalExcess) > 1e-6 ? (Math.abs(totalContrib/totalExcess)*100).toFixed(0) : 0}%，属于 {
                  Math.abs(totalExcess) < 1e-6 ? '超额为零无法判断' :
                  Math.abs(totalContrib/totalExcess) > 0.7 ? '因子主导型 — 收益主要来自因子暴露' :
                  Math.abs(totalContrib/totalExcess) >= 0.3 ? '混合型 — 因子和 α 各占一部分' :
                  'α 主导型 — 因子只能解释少量超额'
                }。</p>
                <p style={{margin:'2px 0'}}><b>判断：</b>因子贡献占比越高，策略的可解释性和可复制性越强。低的因子贡献不一定是坏事，但需要确认 α 来源是否可持续。</p>
              </div>
            ) },
          { label: '年化α', val: alpha, fmt: fmtSigned,
            tip: (
              <div style={{maxWidth:560,lineHeight:1.9,fontSize:13}}>
                <p style={{margin:'0 0 6px',fontWeight:700}}>年化 α（Annualized Alpha）</p>
                <p style={{margin:'2px 0'}}><b>含义：</b>回归截距项 × 252 = 超额收益中<b>四因子完全解释不了的部分</b>。通俗讲：扣掉市场涨跌、动量风格、市值偏好、换手率偏好之后，策略靠自己选股多赚（或少赚）的钱。</p>
                <p style={{margin:'2px 0'}}><b>价值：</b>α 是策略「纯选股能力」的计量器。正 α = 策略选股超越了其因子暴露应有的收益——<b>这是主动管理最值钱的部分</b>。</p>
                <p style={{margin:'2px 0'}}><b>公式：</b>日 α = 回归截距项（OLS）；年化 α = 日 α × 252。</p>
                <p style={{margin:'2px 0'}}><b>阈值分级：</b><br/>
                  · |α| &lt; 2%/年 → <b>α 不显著</b>：策略收益几乎全被因子解释，几乎没有独立选股能力<br/>
                  · 2%~5%/年 → <b>温和正 α</b>：因子之外有额外选股加成，但幅度不大<br/>
                  · 5%~10%/年 → <b>明显正 α</b>：策略有明显独立选股能力，值得深挖 α 来源<br/>
                  · &gt; 10%/年 → <b>强 α</b>：需警惕——可能是未建模因子（质量/情绪/行业）带来的，不是纯 Alpha</p>
                <p style={{margin:'2px 0'}}><b>当前：</b>{fmtSigned(alpha)}，属于 {
                  Math.abs(alpha) < 0.02 ? 'α 不显著 — 策略收益几乎全被因子解释，几乎没有独立选股能力' :
                  Math.abs(alpha) < 0.05 ? (alpha > 0 ? '温和正 α — 因子之外有额外选股加成，但幅度不大' : '温和负 α — 选股能力略低于因子预期') :
                  Math.abs(alpha) < 0.1 ? (alpha > 0 ? '明显正 α — 策略有显著独立选股能力，建议用 FF3 进一步剥离风格因子确认' : '明显负 α — 选股拖累策略，因子暴露后的收益反而更低') :
                  (alpha > 0 ? '强 α — 远超因子解释范围，建议检查是否遗漏了关键因子（如质量/情绪/行业）' : '强负 α — 策略严重跑输因子预期，需排查选股逻辑')
                }。</p>
                <p style={{margin:'2px 0'}}><b>实战判断：</b>正 α + 高 R²（&gt;30%）= 最理想 — 因子驱动 + 选股加成，双重优势。正 α + 低 R² = 收益来源不透明，因子模型覆盖不足，建议用 FF3 扩展风格因子。负 α + 高 R² = 选股在拖后腿，因子暴露不错但选股太差。</p>
              </div>
            ) },
          { label: 'R²', val: rSquared * 100, fmt: v => v.toFixed(1) + '%',
            tip: (
              <div style={{maxWidth:560,lineHeight:1.9,fontSize:13}}>
                <p style={{margin:'0 0 6px',fontWeight:700}}>拟合优度 R²</p>
                <p style={{margin:'2px 0'}}><b>含义：</b>多元回归的决定系数，四因子对超额收益日变化的解释比例。</p>
                <p style={{margin:'2px 0'}}><b>价值：</b>R² 是归因分析可信度的「北星指标」。R² 低 → 归因结论参考价值有限。</p>
                <p style={{margin:'2px 0'}}><b>公式：</b>R² = 1 − SS_residual / SS_total，即 1 − 无法解释的方差 / 总方差。</p>
                <p style={{margin:'2px 0'}}><b>阈值：</b>&gt; 30% = 较可靠（量化策略理想值）；15%~30% = 部分解释；&lt; 15% = 参考价值有限（四因子不够用）。</p>
                <p style={{margin:'2px 0'}}><b>当前：</b>{(rSquared*100).toFixed(1)}%，属于 {
                  highR2 ? '较可靠 — 四因子能较好解释策略超额，归因结论可信' :
                  mediumR2 ? '中等 — 因子能解释一部分，但还有较大 α 空间' :
                  '偏低 — 四因子不足以解释策略超额，建议扩展因子维度（质量/价值/情绪等）'
                }。</p>
                <p style={{margin:'2px 0'}}><b>判断：</b>R² 反映「模型覆盖度」。R²=100% 不现实（意味着策略纯被动跟随因子）；R²=0% 说明策略收益与四因子完全无关。</p>
              </div>
            ) },
          { label: '显著因子', val: significantBetas.length + '/' + betas.length, fmt: v => v,
            tip: (
              <div style={{maxWidth:560,lineHeight:1.9,fontSize:13}}>
                <p style={{margin:'0 0 6px',fontWeight:700}}>显著因子比例</p>
                <p style={{margin:'2px 0'}}><b>含义：</b>|t| ≥ 1.96 的因子数 / 总因子数。t 检验衡量 β 是否统计上显著不为零。</p>
                <p style={{margin:'2px 0'}}><b>价值：</b>显著因子是「真实有暴露」的因子，不显著的因子 β 可能只是噪声。</p>
                <p style={{margin:'2px 0'}}><b>公式：</b>t = β / SE(β)；|t| ≥ 1.96 = 95% 置信显著。样本量越大 t 值越可靠。</p>
                <p style={{margin:'2px 0'}}><b>阈值：</b>≥ 2 个显著 = 策略因子结构清晰；1 个显著 = 单一因子策略；0 个 = 无显著暴露（需检查策略逻辑）。</p>
                <p style={{margin:'2px 0'}}><b>当前：</b>{significantBetas.length}/{betas.length} 个显著，属于 {
                  significantBetas.length >= 2 ? '因子结构清晰 — 多个因子有显著暴露，策略因子定位明确' :
                  significantBetas.length === 1 ? '单因子驱动 — 策略可能过度依赖一个因子' :
                  '无显著暴露 — 策略可能缺乏明确的因子偏好，或因子暴露随时间大幅波动导致 t 值不显著'
                }。</p>
                <p style={{margin:'2px 0'}}><b>判断：</b>显著因子多 + R² 高 = 策略因子驱动且稳定。显著因子少 + R² 还行 = 可能有共线性（检查因子间相关系数）。</p>
              </div>
            ) },
          { label: '可信度', val: trustLevel, fmt: v => v,
            tip: (
              <div style={{maxWidth:560,lineHeight:1.9,fontSize:13}}>
                <p style={{margin:'0 0 6px',fontWeight:700}}>归因可信度</p>
                <p style={{margin:'2px 0'}}><b>含义：</b>基于 R² 和显著因子的综合可信度评估，告诉你这份归因报告能多大程度指导决策。</p>
                <p style={{margin:'2px 0'}}><b>价值：</b>避免对不可靠的归因结论做出错误决策——低可信时结论仅供参考。</p>
                <p style={{margin:'2px 0'}}><b>判断标准：</b><br/>
                  · <b>高</b>（R² &gt; 30%）：因子模型较好解释超额，结论可作为因子调整的直接依据。<br/>
                  · <b>中</b>（R² 15%~30%）：部分解释，结论参考使用，建议先扩展因子维度提高 R²。<br/>
                  · <b>低</b>（R² &lt; 15%）：模型覆盖不足，结论参考价值有限，不应用于重大策略调整。</p>
                <p style={{margin:'2px 0'}}><b>当前：</b>{trustLevel}可信度 — {
                  highR2 ? '结论可靠，可作为因子调整的直接依据' :
                  mediumR2 ? '结论参考使用，建议扩展因子维度后再确认' :
                  '结论仅供参考，不建议据此做大调整。先扩展因子维度（质量/价值/情绪）提升 R² 后再做决策'
                }。</p>
                <p style={{margin:'2px 0'}}><b>判断：</b>可信度低 ≠ 策略差，只说明当前四因子模型无法充分解释策略表现。需要更多因子或更细维度的分析。</p>
              </div>
            ) },
        ].map((item, idx, arr) => {
          const valColor = item.label === '可信度' ? trustColor
            : item.label === 'R²' ? (item.val >= 30 ? '#52c41a' : item.val < 15 ? '#cf1322' : '#faad14')
            : (typeof item.val === 'number' && item.val >= 0 ? '#52c41a' : '#cf1322');
          return (
            <div key={idx} style={{textAlign:'center',padding:'2px 10px',borderRight:idx<arr.length-1?'1px solid #e8e8e8':'none',whiteSpace:'nowrap',cursor:item.tip?'help':'default'}}>
              <Tooltip title={item.tip} placement="top" overlayStyle={{ maxWidth: 560 }}>
                <div style={{fontSize:12,color:'#888'}}>
                  {item.label}{item.tip && <span style={{marginLeft:2,color:'#bbb',fontSize:10}}>ⓘ</span>}
                </div>
              </Tooltip>
              <div style={{fontSize:14,fontWeight:600,color:valColor}}>{item.fmt(item.val)}</div>
            </div>
          );
        })}
      </div>

      {/* 总体判断 */}
      <div style={{
        background: overallColor==='#52c41a'?'#f6ffed':overallColor==='#fa8c16'?'#fff7e6':overallColor==='#8c8c8c'?'#fafafa':'#fff2f0',
        padding:'10px 12px',borderRadius:4,
        border:'1px solid '+(overallColor==='#52c41a'?'#b7eb8f':overallColor==='#fa8c16'?'#ffd591':overallColor==='#8c8c8c'?'#d9d9d9':'#ffccc7'),
        marginBottom:12,fontWeight:700,fontSize:14
      }}>
        总体判断：<span style={{color:overallColor}}>{overall}</span>
      </div>
      <div style={{fontSize:13,color:'#434343',marginBottom:12,lineHeight:1.7}}>{overallDesc}</div>

      {/* 低 R² 警告 */}
      {lowR2 && absExcess > 0.005 && (
        <Alert type="warning" showIcon style={{marginTop:12,fontSize:12}}
          message={`四因子模型解释力仅 ${(rSquared*100).toFixed(1)}% — 策略非因子驱动`}
          description={
            <div style={{fontSize:12,lineHeight:1.8}}>
              策略超额收益主要来自因子模型外的 α（{(alpha*100).toFixed(2)}%/年），
              可能来自质量/价值/情绪等未纳入的因子，或策略有真正的选股能力。
            </div>
          }
        />
      )}
    </Card>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 6c. 因子归因内容（因子分解 + 行动建议）
// ══════════════════════════════════════════════════════════════════════════════
function FactorConclusionBody({ betas, summary, regressionDetail, observationDays }) {
  if (!betas.length) return null;

  const fmtPctV = (v) => v != null ? `${(v * 100).toFixed(2)}%` : '-';
  const fmtSigned = (v) => v >= 0 ? `+${(v * 100).toFixed(2)}` : (v * 100).toFixed(2);

  const totalExcess = summary.totalExcessReturn != null ? +summary.totalExcessReturn : 0;
  const totalContrib = summary.totalFactorContribution != null ? +summary.totalFactorContribution : 0;
  const residual = summary.residual != null ? +summary.residual : 0;
  const rSquared = regressionDetail.rSquared != null ? +regressionDetail.rSquared : 0;
  const alpha = regressionDetail.annualizedAlpha != null ? +regressionDetail.annualizedAlpha : 0;
  const dailyAlpha = (alpha / 252) || 0;

  // 显著因子
  const significantBetas = betas.filter(b => Math.abs(b.tStat || 0) >= 1.96);

  const absExcess = Math.abs(totalExcess);
  const highR2 = rSquared > 0.3;
  const mediumR2 = rSquared >= 0.15 && rSquared <= 0.3;
  const lowR2 = rSquared < 0.15;
  const hasSigFactors = significantBetas.length > 0;

  // ═══ 各因子贡献卡片 ═══
  const buildFactorCard = (b) => {
    const sig = Math.abs(b.tStat || 0) >= 1.96;
    const betaVal = b.beta || 0;
    const contrib = b.annualizedContribution || 0;
    const contribRatio = b.contributionRatio || 0;
    const factorRet = b.totalFactorReturn || 0;

    let verdict = '', verdictColor = '';
    if (!sig) {
      verdict = '不显著：因子暴露不稳定，统计上无显著偏离零';
      verdictColor = '#8c8c8c';
    } else if (contrib > 0.01 && Math.abs(contribRatio) > 0.3) {
      verdict = `核心贡献因子：贡献 ${fmtPctV(contrib)}/年，占因子解释的 ${(Math.abs(contribRatio)*100).toFixed(0)}%`;
      verdictColor = '#52c41a';
    } else if (contrib > 0.005) {
      verdict = '次要正贡献';
      verdictColor = '#52c41a';
    } else if (contrib < -0.01) {
      verdict = `拖累：年化拖累 ${fmtPctV(contrib)}，β方向与因子收益方向冲突`;
      verdictColor = '#cf1322';
    } else {
      verdict = '贡献微弱';
      verdictColor = '#8c8c8c';
    }

    return { factor: b, sig, betaVal, contrib, contribRatio, factorRet, verdict, verdictColor };
  };

  const factorCards = betas.map(b => buildFactorCard(b)).sort((a, b) =>
    Math.abs(b.contrib) - Math.abs(a.contrib));

  // ═══ 行动建议 ═══
  const actionables = [], verifications = [];
  const makeAction = (label, body) => ({ label, body });
  const makeVerify = (label, body) => ({ label, body });

  // 1. 检查拖累因子
  if (lowR2 || mediumR2) {
    const dragFactors = factorCards.filter(f => f.contrib < -0.005);
    if (dragFactors.length > 0) {
      let body = '以下因子产生年化负贡献：\n';
      dragFactors.forEach(f => {
        body += `• ${f.factor.factorName || f.factor.factorCode}：贡献 ${fmtPctV(f.contrib)}/年 (β=${fmt(f.betaVal,3)}, t=${fmt(f.factor.tStat,2)})`;
        if (f.factorRet < 0) body += ' — 因子本身在亏钱';
        else body += ' — 因子在赚钱但策略暴露方向反了';
        body += '\n';
      });
      body += '\n操作：在策略编辑页中调整这些因子的方向（如动量因子设为 DESC 改为 ASC），或直接移除此因子。';
      actionables.push(makeAction('因子调整 — 修复负贡献因子', body));
      verifications.push(makeVerify('移除/反转拖累因子', `重跑回测 → 预期 α 收敛到 ±0.5% 以内，R² 应提升。`));
    }
  }

  // 2. α 主导时建议扩展因子
  if (lowR2 && absExcess > 0.01) {
    let body = `四因子仅解释 ${(rSquared*100).toFixed(1)}% 超额，${(alpha*100).toFixed(2)}% 的 α 说明策略有显著的因子外选股能力。\n`;
    body += '建议扩展因子维度以定位真实收益源：\n';
    body += '• 质量因子：FIN_ROE_TTM（ROE）、FIN_GROSS_MARGIN（毛利率）\n';
    body += '• 价值因子：PE_TTM（市盈率）、PB_LF（市净率）\n';
    body += '• 情绪因子：资金流向、机构持仓变化\n';
    body += '• 扩展后重跑因子归因，观察 R² 是否提升到 30%+';
    actionables.push(makeAction('因子扩展 — 定位 α 真实来源', body));
  }

  // 3. 因子暴露集中警告
  const concentratedBeta = factorCards.filter(f => Math.abs(f.betaVal) > 1.5);
  if (concentratedBeta.length > 0) {
    let body = '以下因子 β 绝对值 > 1.5，策略在该因子上暴露过大：\n';
    concentratedBeta.forEach(f => {
      body += `• ${f.factor.factorName}：β=${fmt(f.betaVal,3)}\n`;
    });
    body += '\n操作：在策略编辑页降低高风险因子的权重（如从 0.5 降到 0.2），降低集中暴露。\n';
    body += '⚠️ 改完后必须在模拟盘建副本做 A/B 回测对比，确认夏普和回撤确实改善再正式替换。';
    actionables.push(makeAction('风控 — 降低因子暴露集中度', body));
  }

  // 4. 无显著因子但 R² 还行 → 可能有共线性
  if (mediumR2 && !hasSigFactors) {
    let body = `R²=${(rSquared*100).toFixed(1)}% 但无单个因子显著 — 典型的多重共线性症状。\n`;
    body += '可能原因：MOM 和 TURN 高度正相关（高动量股同时也高换手），Vol 和 MOM 负相关（强趋势时波动小）。\n';
    body += '\n操作：\n';
    body += '• 检查各因子之间的相关系数矩阵\n';
    body += '• 将高度相关的因子合并为一个复合因子\n';
    body += '• 或改用 Ridge 回归（惩罚共线变量）替代 OLS';
    actionables.push(makeAction('方法 — 处理因子共线性', body));
  }

  // 5. 显著因子优化
  if (hasSigFactors) {
    const posSig = significantBetas.filter(b => (b.annualizedContribution || 0) > 0.01);
    if (posSig.length > 0) {
      let body = '以下因子贡献显著且正向，建议保留并增强：\n';
      posSig.forEach(f => {
        body += `• ${f.factorName || f.factorCode}：贡献 ${fmtPctV(f.annualizedContribution)}/年 (t=${fmt(f.tStat,2)})\n`;
      });
      body += '\n操作：在策略编辑页适当提高这些因子的权重上限。';
      actionables.push(makeAction('因子优化 — 强化有效因子', body));
    }
  }

  return (
    <div style={{marginTop:16}}>
      <div style={{display:'flex',flexWrap:'wrap',gap:8,marginBottom:12}}>
        {factorCards.map((card, idx) => (
          <div key={idx} style={{flex:'1 1 calc(50% - 8px)',minWidth:360}}>
            <Card size="small" type="inner" style={{background:'#fff'}}
              title={
                <span style={{fontWeight:700,fontSize:13}}>
                  {card.factor.factorName || card.factor.factorCode}
                  {card.sig && <Tag color="orange" style={{marginLeft:6,fontSize:10}}>显著</Tag>}
                </span>
              }>
              <div style={{fontSize:11,color:'#999',marginBottom:6}}>{card.factor.description}</div>
              <div style={{display:'flex',justifyContent:'space-between',flexWrap:'wrap',marginBottom:8}}>
                <div style={{fontSize:12,color:'#888'}}>
                  β: <b style={{color:card.betaVal>=0?RED:GREEN}}>{fmt(card.betaVal,4)}</b>
                </div>
                <div style={{fontSize:12,color:'#888'}}>
                  t: <b style={{color:card.sig?'#fa8c16':'#8c8c8c'}}>{fmt(card.factor.tStat,2)}</b>
                </div>
                <div style={{fontSize:12,color:'#888'}}>
                  因子收益: <b>{fmtPctV(card.factorRet)}</b>
                </div>
                <div style={{fontSize:12,color:'#888'}}>
                  年化贡献: <b style={{color:card.contrib>=0?RED:GREEN}}>{fmtPctV(card.contrib)}</b>
                </div>
              </div>
              <div style={{fontSize:12,color:card.verdictColor,background:card.verdictColor==='#52c41a'?'#f6ffed':card.verdictColor==='#cf1322'?'#fff2f0':'#fafafa',padding:'6px 8px',borderRadius:4,border:'1px solid '+(card.verdictColor==='#52c41a'?'#b7eb8f':card.verdictColor==='#cf1322'?'#ffccc7':'#d9d9d9')}}>
                {card.verdict}
              </div>
            </Card>
          </div>
        ))}
      </div>

      {/* 模型公式 */}
      <div style={{fontSize:11,color:'#999',marginBottom:12,fontFamily:'monospace',background:'#fafafa',padding:'6px 10px',borderRadius:4}}>
        R_strategy − R_benchmark = α + β₁×MOM + β₂×VOL + β₃×SIZE + β₄×TURN + ε<br/>
        where α = 选股能力(日) = {fmtSigned(dailyAlpha)}，年化 = {fmtSigned(alpha)}
      </div>

      {/* 行动建议 */}
      {actionables.length > 0 && (
        <div style={{marginBottom:12}}>
          <div style={{fontWeight:700,marginBottom:8,fontSize:13,color:'#262626'}}>可操作建议</div>
          {actionables.map((a,i) => (
            <div key={i} style={{background:'#fff2f0',padding:'8px 10px',borderRadius:4,border:'1px solid #ffccc7',marginBottom:6,fontSize:12,lineHeight:1.7}}>
              <div style={{fontWeight:700,marginBottom:2,color:'#262626'}}>{a.label}</div>
              <div style={{whiteSpace:'pre-wrap',color:'#434343'}}>{a.body}</div>
            </div>
          ))}
          {verifications.length > 0 && (
            <div style={{marginTop:8}}>
              <div style={{fontWeight:700,marginBottom:4,color:'#0958d9',fontSize:13}}>如何验证效果</div>
              {verifications.map((v,i) => (
                <div key={i} style={{background:'#e6f4ff',padding:'6px 10px',borderRadius:4,border:'1px solid #91caff',marginBottom:4,fontSize:12,lineHeight:1.7}}>
                  <span style={{fontWeight:600,color:'#262626'}}>{v.label}：</span>
                  <span style={{color:'#434343'}}>{v.body}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <Divider style={{margin:'8px 0'}}/>
      <Text type="secondary" style={{fontSize:11}}>
        因子归因模型：R = α + Σ(β_f × F_f) + ε。α = 无法被因子解释的残差年化值。
        因子收益 = 每日从 ClickHouse 读取 A股全市场各因子值，分 Top 20% / Bottom 20% 多空组合计算。
      </Text>
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// FF3 三因子风格归因详情
// ══════════════════════════════════════════════════════════════════════════════
function FF3Detail({ taskId }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!taskId) return;
    backtestApi.getFF3Attribution(taskId)
      .then(setData)
      .catch(err => setError(err.message || 'FF3 归因加载失败'))
      .finally(() => setLoading(false));
  }, [taskId]);

  const styleContributions = data?.styleContributions || [];
  const regressionDetail = data?.regressionDetail || {};
  const summary = data?.summary || {};
  const styleBias = data?.styleBias || '';
  const alphaInterp = data?.alphaInterpretation || {};

  // 风格β 柱状图
  const betaChartOption = useMemo(() => {
    if (!styleContributions.length) return {};
    const names = styleContributions.map(s => s.styleName || s.factorName);
    const betas = styleContributions.map(s => (s.beta || 0));
    const tStats = styleContributions.map(s => Math.abs(s.tStat || 0));
    const colors = betas.map(v => v >= 0 ? RED : GREEN);
    const maxAbs = Math.max(...betas.map(Math.abs), 0.01);
    return {
      tooltip: {
        trigger: 'axis',
        formatter: function(params) {
          const s = styleContributions[params[0].dataIndex];
          return `<b>${s.styleName || s.factorCode}</b><br/>
            ${s.description ? s.description + '<br/>' : ''}
            β = ${fmt(s.beta, 4)}<br/>
            t = ${fmt(s.tStat, 2)}<br/>
            显著: ${Math.abs(s.tStat||0)>=1.96?'是 ★':'否'}<br/>
            因子累计收益: ${fmtPct(s.totalFactorReturn)}<br/>
            贡献: ${fmtPct(s.contribution)} (${fmt(s.contributionPct,1)}%)`;
        },
      },
      grid: { left: 70, right: 30, top: 48, bottom: 32 },
      xAxis: { type: 'category', data: names, axisLabel: { fontSize: 12 } },
      yAxis: { type: 'value', name: 'β 系数', min: -maxAbs * 1.3, max: maxAbs * 1.3 },
      series: [{
        type: 'bar', data: betas, barMaxWidth: 60,
        itemStyle: { color: function(p) { return colors[p.dataIndex]; } },
        markLine: { silent: true, data: [{ yAxis: 0, lineStyle: { color: '#999', type: 'dashed' } }] },
        label: { show: true, position: 'top', fontSize: 10,
          formatter: p => tStats[p.dataIndex] >= 1.96 ? '★' : '',
          color: '#fa8c16' },
      }],
    };
  }, [styleContributions]);

  // 贡献占比饼图
  const contribPieOption = useMemo(() => {
    if (!styleContributions.length) return {};
    const pieData = styleContributions.map(s => ({
      name: s.styleName || s.factorName,
      value: Math.abs(s.contribution || 0),
    }));
    // 添加残差
    if (summary.residual != null) {
      pieData.push({ name: '残差(Alpha)', value: Math.abs(summary.residual || 0) });
    }
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie', radius: ['40%', '70%'], data: pieData,
        label: { formatter: '{b}\n{d}%' },
        emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' } },
      }],
    };
  }, [styleContributions, summary.residual]);

  if (loading) return <div style={{textAlign:'center',padding:40}}><Spin tip="加载 FF3 归因..." /></div>;
  if (error) return <Alert type="error" message={error} showIcon />;
  if (!data) return <Alert type="info" message="暂无 FF3 归因数据" showIcon />;

  return (
    <div>
      {/* 风格偏向结论 */}
      {styleBias && (() => {
        const r2 = regressionDetail.rSquared || 0;
        let type = 'success', msg = '风格诊断';
        if (r2 < 0.30) { type = 'error'; msg = '风格诊断（模型无法解释，结论不可信）'; }
        else if (r2 < 0.50) { type = 'warning'; msg = '风格诊断（仅供参考）'; }
        else if (r2 < 0.70) { type = 'info'; msg = '风格诊断'; }
        return (
          <Alert type={type} message={msg} description={styleBias}
            showIcon style={{marginBottom:16}} />
        );
      })()}

      {/* 关键指标 */}
      <Row gutter={12} style={{marginBottom:12}}>
        <Col span={6}>
          <Card size="small"><Statistic title="R²" value={fmtPct(regressionDetail.rSquared || 0)} valueStyle={{fontSize:20}} suffix={
            <Text style={{fontSize:11}} type="secondary">（解释力）</Text>
          } /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title="日 Alpha" value={fmtPct(regressionDetail.alpha || 0)}
            valueStyle={{fontSize:20, color: (regressionDetail.alpha||0)>=0?RED:GREEN}} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title="年化 Alpha" value={fmtPct(regressionDetail.annualizedAlpha || 0)}
            valueStyle={{fontSize:20, color: (regressionDetail.annualizedAlpha||0)>=0?RED:GREEN}} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title="α t 值" value={fmt(regressionDetail.alphaTStat, 2)}
            valueStyle={{fontSize:20, color: Math.abs(regressionDetail.alphaTStat||0)>=1.96?'#fa8c16':undefined}}
            suffix={Math.abs(regressionDetail.alphaTStat||0)>=1.96 ? <Tag color="orange" style={{fontSize:10,padding:'0 4px',lineHeight:'16px'}}>显著</Tag> : ''} /></Card>
        </Col>
      </Row>

      {/* Alpha 解读 (A1) */}
      {alphaInterp.interpretation && (() => {
        const r2 = regressionDetail.rSquared || 0;
        let alertType = alphaInterp.alphaSignificant ? 'success' : 'info';
        let alertMsg = alphaInterp.alphaSignificant ? 'Alpha 显著' : 'Alpha 不显著';
        if (r2 < 0.30) { alertType = 'warning'; alertMsg = 'Alpha 解读（模型解释力弱）'; }
        return (
          <Alert type={alertType} message={alertMsg}
            description={alphaInterp.interpretation}
            showIcon style={{marginBottom:16}} />
        );
      })()}

      {/* β 柱状图 */}
      <ReactECharts option={betaChartOption} style={{ height: 220 }} />
      <Text type="secondary" style={{ fontSize: 11, display: 'block', textAlign: 'center', marginTop: 4 }}>
        ★ 表示 t ≥ 1.96（95% 置信显著）的因子
      </Text>

      {/* 贡献饼图 */}
      <Row gutter={16} style={{marginTop:12}}>
        <Col span={12}>
          <ReactECharts option={contribPieOption} style={{ height: 260 }} />
        </Col>
        <Col span={12}>
          <Table size="small" dataSource={styleContributions} pagination={false}
            rowKey={r => r.factorCode}
            columns={[
              { title: '因子', dataIndex: 'factorCode', width: 60 },
              { title: <Tooltip overlayStyle={{maxWidth:460}} title={
                <div style={{lineHeight:1.9,fontSize:12}}>
                  <p style={{margin:'0 0 6px',fontWeight:700}}>β（风格 Beta）</p>
                  <p style={{margin:'2px 0'}}><b>含义：</b>策略对 MKT/SMB/HML 三个被动风格因子的暴露。β_MKT 衡量大盘联动，β_SMB 衡量大小盘偏好，β_HML 衡量价值/成长倾向。</p>
                  <p style={{margin:'2px 0'}}><b>价值：</b>告诉你是靠大盘涨跌赚钱（β_MKT大），还是靠风格轮动（SMB/HML），还是真有独立选股能力（α）。</p>
                  <p style={{margin:'2px 0'}}><b>阈值：</b><br/>
                    · |β| &lt; 0.1 = 几乎无暴露<br/>
                    · 0.1~0.5 = 温和暴露<br/>
                    · 0.5~1.0 = 明显暴露<br/>
                    · &gt; 1.0 = 高度暴露（风格押注大）</p>
                  <p style={{margin:'2px 0'}}><b>解读：</b>MKT β 高=跟大盘走；SMB β 正=小盘偏好；HML β 正=价值股偏好（爱捡便宜货）；HML β 负=成长股偏好（愿付溢价）。</p>
                  <p style={{margin:'2px 0'}}><b>⚠ 注意：</b>需结合 t 值看显著性和 R² 看整体解释力。单看 β 不看 t 值，可能被噪声误导。</p>
                </div>
              }><span style={{cursor:'help'}}>β <QuestionCircleOutlined style={{fontSize:10,color:'#8c8c8c'}}/></span></Tooltip>, dataIndex: 'beta', width: 60, align: 'right',
                render: v => <Text strong style={{color:(v||0)>=0?RED:GREEN}}>{fmt(v,4)}</Text> },
              { title: 't', dataIndex: 'tStat', width: 50, align: 'right',
                render: v => <Text style={{color:Math.abs(v||0)>=1.96?'#fa8c8c':undefined}}>{fmt(v,2)}</Text> },
              { title: '因子收益', dataIndex: 'annualizedFactorReturn', width: 80, align: 'right',
                render: v => <Text>{fmtPct(v)}</Text> },
              { title: '贡献', dataIndex: 'contribution', width: 80, align: 'right',
                render: v => <Text strong style={{color:(v||0)>=0?RED:GREEN}}>{fmtPct(v)}</Text> },
            ]}
          />
        </Col>
      </Row>

      {/* 总结 */}
      <Divider style={{margin:'8px 0'}}/>
      <Text type="secondary" style={{fontSize:11}}>
        FF3 归因模型：R_excess = α + β_MKT×MKT + β_SMB×SMB + β_HML×HML + ε。
        MKT = 全市场等权日收益，SMB = 小市值(底30%)−大市值(顶30%)，HML = 低PB(底30%)−高PB(顶30%)。
      </Text>
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// Alpha 滚动窗口监控
// ══════════════════════════════════════════════════════════════════════════════
function AlphaMonitorPanel({ taskId }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!taskId) return;
    backtestApi.getAlphaRolling(taskId)
      .then(setData)
      .catch(err => setError(err.message || 'Alpha 监控加载失败'))
      .finally(() => setLoading(false));
  }, [taskId]);

  // 合并多窗口数据为图表
  const chartOption = useMemo(() => {
    if (!data) return {};
    const wn = { rolling60: '60天', rolling120: '120天', rolling252: '252天' };
    const series = [];
    const colors = { rolling60: '#91caff', rolling120: '#1677ff', rolling252: '#cf1322' };
    let allDates = new Set();

    Object.entries(wn).forEach(([key, name]) => {
      const pts = data[key] || [];
      if (!pts.length) return;
      const dates = pts.map(p => p.date);
      dates.forEach(d => allDates.add(d));
      series.push({
        name, type: 'line', data: pts.map(p => p.annualizedAlpha * 100),
        smooth: true, symbol: 'none',
        lineStyle: { color: colors[key], width: key === 'rolling252' ? 2.5 : 1.5 },
        itemStyle: { color: colors[key] },
      });
    });
    const sortedDates = [...allDates].sort();
    series.forEach(s => {
      // xAxis uses sortedDates, data aligned by index
    });
    return {
      tooltip: { trigger: 'axis',
        formatter: function(ps) { return ps.map(p => `${p.seriesName}: ${parseFloat(p.value).toFixed(2)}%`).join('<br/>'); }
      },
      legend: { data: series.map(s => s.name), bottom: 0 },
      grid: { left: 60, right: 20, top: 48, bottom: 74 },
      xAxis: { type: 'category', data: sortedDates, axisLabel: { fontSize: 10, rotate: 30 } },
      yAxis: { type: 'value', name: '年化 Alpha (%)',
        axisLabel: { formatter: v => v.toFixed(2) + '%' } },
      series: series.map(s => {
        const pts = data[s.name === '60天' ? 'rolling60' : s.name === '120天' ? 'rolling120' : 'rolling252'] || [];
        const dateMap = new Map(pts.map(p => [p.date, p.annualizedAlpha * 100]));
        return { ...s, data: sortedDates.map(d => dateMap.get(d) ?? null) };
      }),
    };
  }, [data]);

  if (loading) return <div style={{textAlign:'center',padding:40}}><Spin tip="加载 Alpha 监控..." /></div>;
  if (error) return <Alert type="error" message={error} showIcon />;
  if (!data) return <Alert type="info" message="暂无 Alpha 监控数据" showIcon />;

  return (
    <div>
      {/* 衰减预警 */}
      {data.decayWarning && data.decayWarning.includes('数据不足') ? (
        <Alert type="warning" showIcon message="Alpha 监控提示" description={data.decayWarning} style={{marginBottom:16}} />
      ) : data.decayAlert ? (
        <Alert type="error" showIcon message="⚠ Alpha 衰减预警" description={data.decayWarning} style={{marginBottom:16}} />
      ) : (
        data.decayWarning && <Alert type="success" showIcon message="Alpha 状态" description={data.decayWarning} style={{marginBottom:16}} />
      )}

      {/* 关键指标 */}
      <Row gutter={12} style={{marginBottom:12}}>
        <Col span={8}>
          <Card size="small"><Statistic title="历史 Alpha 均值" value={fmtPct(data.historicalMean || 0)}
            valueStyle={{fontSize:20, color: (data.historicalMean||0)>=0?'#52c41a':'#ff4d4f'}} /></Card>
        </Col>
        <Col span={8}>
          <Card size="small"><Statistic title="近期 Alpha 均值" value={fmtPct(data.recentMean || 0)}
            valueStyle={{fontSize:20, color: (data.recentMean||0)>=0?'#52c41a':'#ff4d4f'}} /></Card>
        </Col>
        {!data.decayWarning?.includes('数据不足') && (
        <Col span={8}>
          <Card size="small"><Statistic title="近期趋势" value={data.slope != null ? data.slope.toFixed(6) : '-'}
            valueStyle={{fontSize:20, color: (data.slope||0)<0?'#fa8c16':'#52c41a'}} suffix="斜率" /></Card>
        </Col>
        )}
      </Row>

      {/* Alpha 曲线 */}
      <ReactECharts option={chartOption} style={{ height: 350 }} />

      <Divider style={{margin:'8px 0'}}/>
      <Text type="secondary" style={{fontSize:11,lineHeight:1.7}}>
        每个窗口内对「策略超额收益 ~ 策略自身因子」做 OLS 回归，截距项即为窗口 Alpha，年化 Alpha = α × 252。<br/>
        历史均值 = 全部窗口 Alpha 的平均值；近期均值 = 最后 25% 期窗口 Alpha 的平均值。<br/>
        趋势判断：计算最近 5 个点的斜率，斜率 &lt; 0 且近期均值 &lt; 历史均值 → Alpha 有下行趋势。<br/>
        <span style={{color:'#fa8c16'}}>注意：需 ≥20 个滚动窗口才启动趋势分析。当前数据不足时仅展示曲线。</span>
      </Text>
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// 风格β 漂移监控
// ══════════════════════════════════════════════════════════════════════════════
function StyleMonitorPanel({ taskId }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!taskId) return;
    backtestApi.getStyleRolling(taskId)
      .then(setData)
      .catch(err => setError(err.message || '风格监控加载失败'))
      .finally(() => setLoading(false));
  }, [taskId]);

  // SMB beta 曲线
  const smbChartOption = useMemo(() => {
    if (!data) return {};
    const wn = { rolling60: '60天', rolling120: '120天', rolling252: '252天' };
    const colors = { rolling60: '#91caff', rolling120: '#1677ff', rolling252: '#cf1322' };
    let allDates = new Set();
    const series = Object.entries(wn).map(([key, name]) => {
      const pts = data[key] || [];
      pts.forEach(p => allDates.add(p.date));
      return { name, key, pts };
    });
    const sortedDates = [...allDates].sort();
    return {
      tooltip: { trigger: 'axis',
        formatter: ps => ps.map(p => `${p.seriesName}: SMB β=${parseFloat(p.value).toFixed(4)}`).join('<br/>')
      },
      legend: { data: series.map(s => s.name), bottom: 0 },
      grid: { left: 60, right: 20, top: 20, bottom: 74 },
      xAxis: { type: 'category', data: sortedDates, axisLabel: { fontSize: 10, rotate: 30 } },
      yAxis: { type: 'value', name: 'SMB β', axisLabel: { formatter: v => v.toFixed(3) } },
      series: series.map(s => ({
        name: s.name, type: 'line', smooth: true, symbol: 'none',
        lineStyle: { color: colors[s.key], width: s.key==='rolling252'?2.5:1.5 },
        itemStyle: { color: colors[s.key] },
        data: (() => { const m = new Map(s.pts.map(p => [p.date, p.smbBeta])); return sortedDates.map(d => m.get(d)??null); })(),
        markLine: s.key === 'rolling252' ? { silent: true, data: [{ yAxis: 0, lineStyle: { color: '#999', type: 'dashed' } }] } : undefined,
      })),
    };
  }, [data]);

  // HML beta 曲线
  const hmlChartOption = useMemo(() => {
    if (!data) return {};
    const wn = { rolling60: '60天', rolling120: '120天', rolling252: '252天' };
    const colors = { rolling60: '#91caff', rolling120: '#1677ff', rolling252: '#3f8600' };
    let allDates = new Set();
    const series = Object.entries(wn).map(([key, name]) => {
      const pts = data[key] || [];
      pts.forEach(p => allDates.add(p.date));
      return { name, key, pts };
    });
    const sortedDates = [...allDates].sort();
    return {
      tooltip: { trigger: 'axis',
        formatter: ps => ps.map(p => `${p.seriesName}: HML β=${parseFloat(p.value).toFixed(4)}`).join('<br/>')
      },
      legend: { data: series.map(s => s.name), bottom: 0 },
      grid: { left: 60, right: 20, top: 20, bottom: 74 },
      xAxis: { type: 'category', data: sortedDates, axisLabel: { fontSize: 10, rotate: 30 } },
      yAxis: { type: 'value', name: 'HML β', axisLabel: { formatter: v => v.toFixed(3) } },
      series: series.map(s => ({
        name: s.name, type: 'line', smooth: true, symbol: 'none',
        lineStyle: { color: colors[s.key], width: s.key==='rolling252'?2.5:1.5 },
        itemStyle: { color: colors[s.key] },
        data: (() => { const m = new Map(s.pts.map(p => [p.date, p.hmlBeta])); return sortedDates.map(d => m.get(d)??null); })(),
        markLine: s.key === 'rolling252' ? { silent: true, data: [{ yAxis: 0, lineStyle: { color: '#999', type: 'dashed' } }] } : undefined,
      })),
    };
  }, [data]);

  if (loading) return <div style={{textAlign:'center',padding:40}}><Spin tip="加载风格监控..." /></div>;
  if (error) return <Alert type="error" message={error} showIcon />;
  if (!data) return <Alert type="info" message="暂无风格监控数据" showIcon />;

  const drift = data.smbDrift || data.hmlDrift;

  // 动态 Tooltip：风格漂移监控说明
  const driftTooltipContent = (() => {
    const smbDelta = Math.abs((data.smbRecentMean||0) - (data.smbHistoricalMean||0));
    const hmlDelta = Math.abs((data.hmlRecentMean||0) - (data.hmlHistoricalMean||0));

    // 推算主窗口（从标准差 + 历史/近期均值反推）
    const primaryWindow = Object.entries({
      '252天': { sv: (data.rolling252 || []).length, smb: data.smbHistoricalMean, hml: data.hmlHistoricalMean },
      '120天': { sv: (data.rolling120 || []).length, smb: data.smbHistoricalMean, hml: data.hmlHistoricalMean },
      '60天':  { sv: (data.rolling60 || []).length,  smb: data.smbHistoricalMean, hml: data.hmlHistoricalMean },
    }).find(([_, v]) => v.sv >= 10);
    const winLabel = primaryWindow ? primaryWindow[0] : '未知';

    const smbStdDeviations = (data.smbStd > 1e-8) ? (smbDelta / data.smbStd) : 0;
    const hmlStdDeviations = (data.hmlStd > 1e-8) ? (hmlDelta / data.hmlStd) : 0;

    return (
      <div style={{maxWidth:680,lineHeight:1.9,fontSize:13}}>
        <p style={{margin:0,fontWeight:700,fontSize:14}}>风格β 漂移监控</p>

        <p style={{margin:'8px 0 4px',fontWeight:600}}>一、作用</p>
        <p style={{margin:'0 0 8px'}}>监控策略的选股偏好是否发生结构性变化。当 SMB（规模）或 HML（价值）beta 显著偏离历史水平时预警——说明策略选股风格正在从一种模式切换到另一种。</p>

        <p style={{margin:'8px 0 4px',fontWeight:600}}>二、判定逻辑</p>
        <p style={{margin:0}}>1. 通过 {winLabel} 滚动窗口做 FF3 回归，得到每个窗口的 SMB/HML beta</p>
        <p style={{margin:0}}>2. 计算 <b>历史均值</b> = 所有窗口 beta 的平均</p>
        <p style={{margin:0}}>3. 计算 <b>近期均值</b> = 最后 25% 窗口的 beta 平均</p>
        <p style={{margin:0}}>4. 计算 <b>历史标准差</b>（衡量 beta 的正常波动范围）</p>
        <p style={{margin:'0 0 8px'}}>5. 若 <b>|近期均值 − 历史均值| > 1.0 × 标准差</b> → 触发预警</p>

        <p style={{margin:'8px 0 4px',fontWeight:600}}>三、预警原因（基于当前数据）</p>
        <div style={{background:'#fafafa',padding:'6px 10px',borderRadius:4,marginBottom:4}}>
          <table style={{width:'100%',borderCollapse:'collapse'}}>
            <thead>
              <tr style={{borderBottom:'1px solid #d9d9d9'}}>
                <th style={{padding:'4px 6px',textAlign:'left'}}></th>
                <th style={{padding:'4px 6px',textAlign:'right'}}>SMB（规模）</th>
                <th style={{padding:'4px 6px',textAlign:'right'}}>HML（价值）</th>
              </tr>
            </thead>
            <tbody>
              <tr style={{borderBottom:'1px solid #f0f0f0'}}>
                <td style={{padding:'3px 6px'}}>历史均值</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace'}}>{data.smbHistoricalMean.toFixed(4)}</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace'}}>{data.hmlHistoricalMean.toFixed(4)}</td>
              </tr>
              <tr style={{borderBottom:'1px solid #f0f0f0'}}>
                <td style={{padding:'3px 6px'}}>近期均值</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace',color:data.smbDrift?'#ff4d4f':undefined}}>{data.smbRecentMean.toFixed(4)}</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace',color:data.hmlDrift?'#ff4d4f':undefined}}>{data.hmlRecentMean.toFixed(4)}</td>
              </tr>
              <tr style={{borderBottom:'1px solid #f0f0f0'}}>
                <td style={{padding:'3px 6px'}}>历史波动(Std)</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace'}}>{(data.smbStd ?? 0).toFixed(4)}</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace'}}>{(data.hmlStd ?? 0).toFixed(4)}</td>
              </tr>
              <tr style={{borderBottom:'1px solid #d9d9d9'}}>
                <td style={{padding:'3px 6px'}}>|近期−历史| 偏移</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace'}}>{smbDelta.toFixed(4)}</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace'}}>{hmlDelta.toFixed(4)}</td>
              </tr>
              <tr>
                <td style={{padding:'3px 6px',fontWeight:600}}>偏离 标准差倍数</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace',fontWeight:700,color:smbStdDeviations>1?'#ff4d4f':'#52c41a'}}>{smbStdDeviations.toFixed(3)}σ {(smbStdDeviations>1?'⚠ 超标':'正常')}</td>
                <td style={{padding:'3px 6px',textAlign:'right',fontFamily:'monospace',fontWeight:700,color:hmlStdDeviations>1?'#ff4d4f':'#52c41a'}}>{hmlStdDeviations.toFixed(3)}σ {(hmlStdDeviations>1?'⚠ 超标':'正常')}</td>
              </tr>
            </tbody>
          </table>
        </div>
        {drift ? (
          <p style={{margin:0,color:'#ff4d4f'}}>
            <b>触发预警原因：</b>
            {data.smbDrift &&
              <>SMB 近期均值 {((data.smbRecentMean||0)).toFixed(2)} 偏离历史 {((data.smbHistoricalMean||0)).toFixed(2)}，
                偏移 {smbDelta.toFixed(3)}{(data.smbStd != null) ? <>（标准差 {(data.smbStd).toFixed(3)}）=
                {(smbDelta/(data.smbStd||1)).toFixed(1)}σ > 1.0σ 阈值</> : '（后端未返回标准差，请重启后端）'}</>
            }
            {data.smbDrift && data.hmlDrift && '；'}
            {data.hmlDrift &&
              <>HML 近期均值 {((data.hmlRecentMean||0)).toFixed(2)} 偏离历史 {((data.hmlHistoricalMean||0)).toFixed(2)}，
                  偏移 {hmlDelta.toFixed(3)}{(data.hmlStd != null) ? <>（标准差 {(data.hmlStd).toFixed(3)}）=
                {(hmlDelta/(data.hmlStd||1)).toFixed(1)}σ > 1.0σ 阈值</> : '（后端未返回标准差，请重启后端）'}</>
            }
          </p>
        ) : (
          <p style={{margin:0,color:'#52c41a'}}>
            当前 SMB/HML 偏移均未超过 1.0σ 阈值，策略风格稳定。
          </p>
        )}
      </div>
    );
  })();

  return (
    <div>
      {/* 说明 Tooltip */}
      <div style={{marginBottom:12,display:'flex',alignItems:'center',justifyContent:'flex-end'}}>
        <Tooltip overlayStyle={{ maxWidth: 700 }} title={driftTooltipContent}>
          <Button size="small" type="text" icon={<QuestionCircleOutlined style={{fontSize:14,color:'#8c8c8c'}}/>}>
            监控原理 &amp; 判定逻辑
          </Button>
        </Tooltip>
      </div>

      {/* 漂移预警 */}
      {drift ? (
        <Alert type="error" showIcon message="⚠ 风格漂移预警" description={data.driftWarning} style={{marginBottom:16}} />
      ) : (
        <Alert type="success" showIcon message="风格稳定" description={data.driftWarning || '未检测到显著风格漂移'} style={{marginBottom:16}} />
      )}

      {/* 关键指标 */}
      <Row gutter={12} style={{marginBottom:12}}>
        <Col span={6}>
          <Card size="small"><Statistic title="SMB 历史均值" value={fmt(data.smbHistoricalMean, 4)}
            valueStyle={{fontSize:18, color: (data.smbHistoricalMean||0)>=0?RED:GREEN}} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title="SMB 近期均值" value={fmt(data.smbRecentMean, 4)}
            valueStyle={{fontSize:18, color: (data.smbRecentMean||0)>=0?RED:GREEN}} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title="HML 历史均值" value={fmt(data.hmlHistoricalMean, 4)}
            valueStyle={{fontSize:18, color: (data.hmlHistoricalMean||0)>=0?RED:GREEN}} /></Card>
        </Col>
        <Col span={6}>
          <Card size="small"><Statistic title="HML 近期均值" value={fmt(data.hmlRecentMean, 4)}
            valueStyle={{fontSize:18, color: (data.hmlRecentMean||0)>=0?RED:GREEN}} /></Card>
        </Col>
      </Row>

      {/* SMB 曲线 */}
      <Card size="small" title="SMB（规模因子）β 滚动序列" style={{marginBottom:12}}>
        <ReactECharts option={smbChartOption} style={{ height: 250 }} />
      </Card>

      {/* HML 曲线 */}
      <Card size="small" title="HML（价值因子）β 滚动序列">
        <ReactECharts option={hmlChartOption} style={{ height: 250 }} />
      </Card>

      <Divider style={{margin:'8px 0'}}/>
      <Text type="secondary" style={{fontSize:11}}>
        FF3 滚动回归：每个窗口内 R_excess = α + β_MKT×MKT + β_SMB×SMB + β_HML×HML + ε。
        漂移阈值：近期均值偏离历史均值超过 1 个标准差。
      </Text>
    </div>
  );
}
