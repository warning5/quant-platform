import React, { useState, useEffect, Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Link, useLocation, useParams } from 'react-router-dom';
import { App as AntApp, Layout, Menu, Typography, Space, Badge, Button, Tooltip, Spin, Drawer, Switch, ConfigProvider, theme } from 'antd';
import {
  FundOutlined, FundViewOutlined, ThunderboltOutlined,
  DashboardOutlined, BarChartOutlined, StockOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, FilterOutlined, BookOutlined,
  PartitionOutlined, AccountBookOutlined,
  SearchOutlined,
  AppstoreOutlined, ControlOutlined,
  MoonOutlined, SunOutlined,
} from '@ant-design/icons';

// ── 页面懒加载（React.lazy + Suspense）──
const Dashboard = lazy(() => import('./pages/Dashboard'));
const MarketList = lazy(() => import('./pages/market/MarketList'));
const SectorRanking = lazy(() => import('./pages/market/SectorRanking'));
const FactorList = lazy(() => import('./pages/factors/FactorList'));
const FactorDetail = lazy(() => import('./pages/factors/FactorDetail'));
const FactorEditor = lazy(() => import('./pages/factors/FactorEditor'));
const FactorCorrelation = lazy(() => import('./pages/factors/FactorCorrelation'));
const FactorMonitor = lazy(() => import('./pages/factors/FactorMonitor'));
const FactorIcIrAnalysis = lazy(() => import('./pages/factors/FactorIcIrAnalysis'));
const FactorWeightOptimize = lazy(() => import('./pages/factors/FactorWeightOptimize'));
const StrategyList = lazy(() => import('./pages/strategies/StrategyList'));
const StrategyDetail = lazy(() => import('./pages/strategies/StrategyDetail'));
const StrategyEditor = lazy(() => import('./pages/strategies/StrategyEditor'));
const PaperTradingPage = lazy(() => import('./pages/strategies/PaperTradingPage'));
const BacktestList = lazy(() => import('./pages/backtest/BacktestList'));
const BacktestReport = lazy(() => import('./pages/backtest/BacktestReport'));
const BacktestCreate = lazy(() => import('./pages/backtest/BacktestCreate'));
const BacktestRunning = lazy(() => import('./pages/backtest/BacktestRunning'));
const BacktestCompare = lazy(() => import('./pages/backtest/BacktestCompare'));
const ParamOptimize = lazy(() => import('./pages/backtest/ParamOptimize'));
const WalkForward = lazy(() => import('./pages/backtest/WalkForward.jsx'));
const RecommendationList = lazy(() => import('./pages/recommendation/RecommendationList'));
const LlmAnalysisPage = lazy(() => import('./pages/llm/LlmAnalysisPage'));
const MonitorPage = lazy(() => import('./pages/monitor/MonitorPage'));
const StockScreen = lazy(() => import('./pages/screen/StockScreen'));
const ManualOverviewPage = lazy(() => import('./pages/manual/ManualOverviewPage'));
const ManualDataInfoPage = lazy(() => import('./pages/manual/ManualDataInfoPage'));
const ManualStockAnalysisPage = lazy(() => import('./pages/manual/ManualStockAnalysisPage'));
const ManualMarketThermometerPage = lazy(() => import('./pages/manual/ManualMarketThermometerPage'));
const ManualPaperTradingFullPage = lazy(() => import('./pages/manual/ManualPaperTradingFullPage'));
const ManualFactorPage = lazy(() => import('./pages/manual/ManualFactorPage'));
const ManualFactorTestPage = lazy(() => import('./pages/manual/ManualFactorTestPage'));
const ManualStrategyPage = lazy(() => import('./pages/manual/ManualStrategyPage'));
const ManualBacktestPage = lazy(() => import('./pages/manual/ManualBacktestPage'));
const FinancialData = lazy(() => import('./pages/financial/FinancialData'));
const ResearchData = lazy(() => import('./pages/datadetail/ResearchData'));
const DataUpdate = lazy(() => import('./pages/dataupdate/DataUpdate'));
const ScheduledTasks = lazy(() => import('./pages/dataupdate/ScheduledTasks'));
const StockAnalysis = lazy(() => import('./pages/analysis/StockAnalysis'));
const TradeCalendar = lazy(() => import('./pages/calendar/TradeCalendar'));
const MarketThermometer = lazy(() => import('./pages/analysis/MarketThermometer'));

/** 滚动回测旧路由重定向到统一回测 */
function OldRollingRedirect() {
  const { id } = useParams();
  if (id) return <Navigate to={`/backtests/${id}/report`} replace />;
  return <Navigate to="/backtests" replace />;
}

/** 懒加载 fallback：页面级 Spin */
function PageLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
      <Spin size="large" tip="加载中..." />
    </div>
  );
}

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

function AppLayout({ isDark, setIsDark }) {
  const [collapsed, setCollapsed] = useState(false);
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState(false);
  const location = useLocation();
  const isMobile = window.innerWidth < 768;

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: <Link to="/">总览</Link> },
    { key: '/market', icon: <FundViewOutlined />, label: <Link to="/market">行情数据</Link> },
    { key: '/market-thermometer', icon: <ControlOutlined />, label: <Link to="/market-thermometer">大盘温度计</Link> },
    { key: '/stock-analysis', icon: <SearchOutlined />, label: <Link to="/stock-analysis">个股分析</Link> },
    {
      key: 'factors',
      icon: <FundOutlined />,
      label: '因子管理',
      children: [
        { key: '/factors', label: <Link to="/factors">因子列表</Link> },
        { key: '/factor-monitor', label: <Link to="/factor-monitor">因子计算</Link> },
        { key: '/factor-correlation', label: <Link to="/factor-correlation">因子相关性</Link> },
        { key: '/factor-weight-optimize', label: <Link to="/factor-weight-optimize">权重优化</Link> },
        { key: '/factor-ic-ir', label: <Link to="/factor-ic-ir">IC管理</Link> },
      ],
    },
    {
      key: 'strategies',
      icon: <ThunderboltOutlined />,
      label: '策略管理',
      children: [
        { key: '/strategies', label: <Link to="/strategies">策略列表</Link> },
        { key: '/backtests', label: <Link to="/backtests">回测列表</Link> },
        { key: '/backtests/compare', label: <Link to="/backtests/compare">策略对比</Link> },
        { key: '/backtests/param-optimize', label: <Link to="/backtests/param-optimize">参数优化</Link> },
        { key: '/backtests/walk-forward', label: <Link to="/backtests/walk-forward">Walk-Forward验证</Link> },
        { key: '/paper-trading', label: <Link to="/paper-trading">模拟盘</Link> },
      ],
    },
    {
      key: 'screen',
      icon: <AppstoreOutlined />,
      label: '选股工具',
      children: [
        { key: '/screen', label: <Link to="/screen">因子选股</Link> },
        { key: '/recommendation', label: <Link to="/recommendation">智能推荐</Link> },
        { key: '/llm', label: <Link to="/llm">AI推理分析</Link> },
        { key: '/monitor', label: <Link to="/monitor">盘中监控</Link> },
        { key: '/calendar', label: <Link to="/calendar">交易日历</Link> },
      ],
    },
    {
      key: 'data-info',
      icon: <AccountBookOutlined />,
      label: '数据信息',
      children: [
        { key: '/data-update', label: <Link to="/data-update">数据更新</Link> },
        { key: '/data-detail/financial', label: <Link to="/data-detail/financial">财务数据</Link> },
        { key: '/data-detail/research', label: <Link to="/data-detail/research">研报数据</Link> },
        { key: '/sector-ranking', label: <Link to="/sector-ranking">行业排行</Link> },
        { key: '/scheduled-tasks', label: <Link to="/scheduled-tasks">定时任务</Link> },
      ],
    },
    {
      key: 'manual',
      icon: <BookOutlined />,
      label: '使用手册',
      children: [
        { key: '/manual/overview', label: <Link to="/manual/overview">平台概述</Link> },
        { key: '/manual/data-info', label: <Link to="/manual/data-info">数据信息</Link> },
        { key: '/manual/stock-analysis', label: <Link to="/manual/stock-analysis">个股分析</Link> },
        { key: '/manual/market-thermometer', label: <Link to="/manual/market-thermometer">大盘温度计</Link> },
        { key: '/manual/factors', label: <Link to="/manual/factors">因子管理</Link> },
        { key: '/manual/strategy', label: <Link to="/manual/strategy">策略管理</Link> },
      ],
    },
  ];

  const selectedKeys = [location.pathname];
  const [openKeys, setOpenKeys] = useState(() => {
    const path = window.location.pathname;
    if (path.startsWith('/factor') || path === '/factor-weight-optimize') return ['factors'];
    if (path.startsWith('/strateg') || path.startsWith('/backtest') || path === '/paper-trading') return ['strategies'];
    if (path.startsWith('/screen') || path.startsWith('/recommendation') || path.startsWith('/llm') || path.startsWith('/monitor') || path.startsWith('/calendar')) return ['screen'];
    if (path.startsWith('/data-detail')) return ['data-info'];
    if (path === '/data-update' || path === '/scheduled-tasks' || path === '/sector-ranking') return ['data-info'];
    if (path.startsWith('/manual/')) return ['manual'];
    return [];
  });

  useEffect(() => {
    const path = location.pathname;
    if (path.startsWith('/factor') || path === '/factor-weight-optimize') setOpenKeys(['factors']);
    else if (path.startsWith('/strateg') || path.startsWith('/backtest') || path === '/paper-trading') setOpenKeys(['strategies']);
    else if (path.startsWith('/screen') || path.startsWith('/recommendation') || path.startsWith('/llm') || path.startsWith('/monitor') || path.startsWith('/calendar')) setOpenKeys(['screen']);
    else if (path.startsWith('/data-detail') || path === '/data-update' || path === '/scheduled-tasks' || path === '/sector-ranking') setOpenKeys(['data-info']);
    else if (path.startsWith('/manual/')) setOpenKeys(['manual']);
    else setOpenKeys([]);
  }, [location.pathname]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* ── 桌面端：固定侧边栏 ── */}
      {!isMobile && (
        <Sider
          className="desktop-sider"
          collapsible
          collapsed={collapsed}
          onCollapse={setCollapsed}
          trigger={null}
          style={{
            background: '#001529',
            transition: 'width 0.2s',
            overflow: 'hidden',
          }}
          width={220}
          collapsedWidth={64}
        >
        {/* Logo 区域 */}
        <div style={{
          height: 56,
          display: 'flex',
          alignItems: 'center',
          justifyContent: collapsed ? 'center' : 'flex-start',
          padding: collapsed ? 0 : '0 20px',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          overflow: 'hidden',
          whiteSpace: 'nowrap',
          transition: 'padding 0.2s',
        }}>
          <BarChartOutlined style={{ color: '#1677ff', fontSize: 20, flexShrink: 0 }} />
          {!collapsed && (
            <Title level={5} style={{
              color: '#fff', margin: '0 0 0 10px', fontSize: 15,
              opacity: collapsed ? 0 : 1, transition: 'opacity 0.2s',
            }}>
              量化因子平台
            </Title>
          )}
        </div>

        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={selectedKeys}
          openKeys={openKeys}
          onOpenChange={(keys) => {
            // 始终只保留最后一个打开的父菜单（手风琴）
            setOpenKeys(keys.length > openKeys.length ? [keys[keys.length - 1]] : []);
          }}
          items={menuItems}
          style={{ marginTop: 4, borderRight: 0 }}
          inlineCollapsed={collapsed}
        />
      </Sider>
      )}

      {/* ── 移动端：Drawer 侧边栏 ── */}
      {isMobile && (
        <Drawer
          title="导航菜单"
          placement="left"
          onClose={() => setMobileDrawerOpen(false)}
          open={mobileDrawerOpen}
          width={260}
          styles={{ body: { padding: 0 } }}
        >
          <Menu
            mode="inline"
            selectedKeys={selectedKeys}
            openKeys={openKeys}
            onOpenChange={(keys) => setOpenKeys(keys.length > openKeys.length ? [keys[keys.length - 1]] : [])}
            items={menuItems}
            onClick={() => setMobileDrawerOpen(false)}
          />
        </Drawer>
      )}

      <Layout style={{ transition: 'all 0.2s' }}>
        <Header style={{
          background: isDark ? '#141414' : '#fff',
          padding: '0 16px 0 0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          boxShadow: '0 1px 4px rgba(0,21,41,.08)',
          height: 56,
          position: 'sticky',
          top: 0,
          zIndex: 100,
        }}>
          {/* 收放触发按钮 */}
          {isMobile ? (
            <Button
              type="text"
              icon={<MenuUnfoldOutlined />}
              onClick={() => setMobileDrawerOpen(true)}
              style={{ width: 48, height: 48, fontSize: 16, borderRadius: 0 }}
            />
          ) : (
            <Tooltip title={collapsed ? '展开菜单' : '收起菜单'} placement="right">
              <Button
                type="text"
                icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                onClick={() => setCollapsed(!collapsed)}
                style={{
                  width: 56, height: 56,
                  fontSize: 16,
                  borderRadius: 0,
                  color: '#595959',
                }}
              />
            </Tooltip>
          )}

          <Space style={{ marginRight: 8 }}>
            <Badge status="processing" text="系统运行正常" />
            <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 16 }}>
              Quant Platform v1.0 · Java 21 + Spring Boot 3
            </Typography.Text>
            {/* ── 暗黑模式切换 ── */}
            <Switch
              checked={isDark}
              onChange={setIsDark}
              checkedChildren={<MoonOutlined />}
              unCheckedChildren={<SunOutlined />}
              style={{ marginLeft: 12 }}
            />
          </Space>
        </Header>

        <Content style={{ margin: 16, minHeight: 280 }}>
          <Suspense fallback={<PageLoading />}>
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/market" element={<MarketList />} />
              <Route path="/data-detail/research" element={<ResearchData />} />
              <Route path="/data-detail/financial" element={<FinancialData />} />
              <Route path="/data-update" element={<DataUpdate />} />
              <Route path="/scheduled-tasks" element={<ScheduledTasks />} />
              <Route path="/factors" element={<FactorList />} />
              <Route path="/factors/new" element={<FactorEditor />} />
              <Route path="/factors/:id" element={<FactorDetail />} />
              <Route path="/factors/:id/edit" element={<FactorEditor />} />
              <Route path="/factor-correlation" element={<FactorCorrelation />} />
              <Route path="/factor-monitor" element={<FactorMonitor />} />
              <Route path="/strategies" element={<StrategyList />} />
              <Route path="/strategies/new" element={<StrategyEditor />} />
              <Route path="/strategies/:id" element={<StrategyDetail />} />
              <Route path="/strategies/:id/edit" element={<StrategyEditor />} />
              <Route path="/paper-trading" element={<PaperTradingPage />} />
              <Route path="/backtests" element={<BacktestList />} />
              <Route path="/backtests/new" element={<BacktestCreate />} />
              <Route path="/backtests/compare" element={<BacktestCompare />} />
              <Route path="/backtests/param-optimize" element={<ParamOptimize />} />
              <Route path="/backtests/walk-forward" element={<WalkForward />} />
              <Route path="/backtests/:taskId/running" element={<BacktestRunning />} />
              <Route path="/backtests/:taskId/report" element={<BacktestReport />} />
              <Route path="/factor-weight-optimize" element={<FactorWeightOptimize defaultFactorCodes={[]} />} />
              <Route path="/factor-ic-ir" element={<FactorIcIrAnalysis />} />
              <Route path="/screen" element={<StockScreen />} />
              <Route path="/recommendation" element={<RecommendationList />} />
              <Route path="/llm" element={<LlmAnalysisPage />} />
              <Route path="/monitor" element={<MonitorPage />} />
              <Route path="/screen/backtest/:id" element={<OldRollingRedirect />} />
              <Route path="/screen/backtest" element={<OldRollingRedirect />} />
              <Route path="/manual/overview" element={<ManualOverviewPage />} />
              <Route path="/manual/data-info" element={<ManualDataInfoPage />} />
              <Route path="/manual/stock-analysis" element={<ManualStockAnalysisPage />} />
              <Route path="/manual/market-thermometer" element={<ManualMarketThermometerPage />} />
              <Route path="/manual/paper-trading" element={<ManualPaperTradingFullPage />} />
              <Route path="/manual/factors" element={<ManualFactorPage />} />
              <Route path="/manual/strategy" element={<ManualStrategyPage />} />
              <Route path="/manual/backtest" element={<ManualBacktestPage />} />
              <Route path="/stock-analysis" element={<StockAnalysis />} />
              <Route path="/market-thermometer" element={<MarketThermometer />} />
              <Route path="/sector-ranking" element={<SectorRanking />} />
              <Route path="/calendar" element={<TradeCalendar />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </Content>
      </Layout>
    </Layout>
  );
}

export default function App() {
  const [isDark, setIsDark] = useState(false);

  // 暗色主题时，给 body 设置 data-theme 属性，CSS 变量自动切换
  useEffect(() => {
    document.body.setAttribute('data-theme', isDark ? 'dark' : 'light');
  }, [isDark]);

  return (
    <BrowserRouter
      future={{
        v7_startTransition: true,
        v7_relativeSplatPath: true,
      }}
    >
      <ConfigProvider
        theme={{
          algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
          token: {
            colorPrimary: '#1677ff',
          },
        }}
      >
        <AntApp>
          <AppLayout isDark={isDark} setIsDark={setIsDark} />
        </AntApp>
      </ConfigProvider>
    </BrowserRouter>
  );
}
