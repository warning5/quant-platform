import React, { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Link, useLocation, useParams } from 'react-router-dom';
import { App as AntApp, Layout, Menu, Typography, Space, Badge, Button, Tooltip } from 'antd';
import {
  FundOutlined, FundViewOutlined, ThunderboltOutlined,
  DashboardOutlined, BarChartOutlined, StockOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, FilterOutlined, BookOutlined,
  PartitionOutlined, AccountBookOutlined,
  SearchOutlined, HistoryOutlined,
  AppstoreOutlined, ControlOutlined,
} from '@ant-design/icons';

import Dashboard from './pages/Dashboard';
import MarketList from './pages/market/MarketList';
import FactorList from './pages/factors/FactorList';
import FactorDetail from './pages/factors/FactorDetail';
import FactorEditor from './pages/factors/FactorEditor';
import FactorCorrelation from './pages/factors/FactorCorrelation';
import FactorMonitor from './pages/factors/FactorMonitor';
import StrategyList from './pages/strategies/StrategyList';
import StrategyDetail from './pages/strategies/StrategyDetail';
import StrategyEditor from './pages/strategies/StrategyEditor';
import PaperTradingPage from './pages/strategies/PaperTradingPage';
import BacktestList from './pages/backtest/BacktestList';
import BacktestReport from './pages/backtest/BacktestReport';
import BacktestCreate from './pages/backtest/BacktestCreate';
import BacktestRunning from './pages/backtest/BacktestRunning';
import BacktestCompare from './pages/backtest/BacktestCompare';
import ParamOptimize from './pages/backtest/ParamOptimize';
import FactorWeightOptimize from './pages/factors/FactorWeightOptimize';
import RecommendationList from './pages/recommendation/RecommendationList';
import FactorIcIrAnalysis from './pages/factors/FactorIcIrAnalysis';
import StockScreen from './pages/screen/StockScreen';
import ManualOverviewPage from './pages/manual/ManualOverviewPage';
import ManualDataInfoPage from './pages/manual/ManualDataInfoPage';
import ManualStockAnalysisPage from './pages/manual/ManualStockAnalysisPage';
import ManualMarketThermometerPage from './pages/manual/ManualMarketThermometerPage';
import ManualPaperTradingFullPage from './pages/manual/ManualPaperTradingFullPage';
import ManualFactorPage from './pages/manual/ManualFactorPage';
import ManualFactorTestPage from './pages/manual/ManualFactorTestPage';
import ManualStrategyPage from './pages/manual/ManualStrategyPage';
import ManualBacktestPage from './pages/manual/ManualBacktestPage';
import FinancialData from './pages/financial/FinancialData';
import ResearchData from './pages/datadetail/ResearchData';
import DataUpdate from './pages/dataupdate/DataUpdate';
import ScheduledTasks from './pages/dataupdate/ScheduledTasks';
import StockAnalysis from './pages/analysis/StockAnalysis';

/** 滚动回测旧路由重定向到统一回测 */
function OldRollingRedirect() {
  const { id } = useParams();
  if (id) return <Navigate to={`/backtests/${id}/report`} replace />;
  return <Navigate to="/backtests" replace />;
}
import MarketThermometer from './pages/analysis/MarketThermometer';
import SectorRanking from './pages/market/SectorRanking';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();

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
      ],
    },
    {
      key: 'strategies',
      icon: <ThunderboltOutlined />,
      label: '策略管理',
      children: [
        { key: '/strategies', label: <Link to="/strategies">策略列表</Link> },
        { key: '/backtests/param-optimize', label: <Link to="/backtests/param-optimize">参数优化</Link> },
        { key: '/paper-trading', label: <Link to="/paper-trading">模拟盘</Link> },
      ],
    },
    {
      key: 'backtests',
      icon: <HistoryOutlined />,
      label: '回测管理',
      children: [
        { key: '/backtests', label: <Link to="/backtests">回测列表</Link> },
        { key: '/backtests/compare', label: <Link to="/backtests/compare">策略对比</Link> },
        { key: '/factor-ic-ir', label: <Link to="/factor-ic-ir">IC/IR 分析</Link> },
      ],
    },
    {
      key: 'screen',
      icon: <AppstoreOutlined />,
      label: '选股工具',
      children: [
        { key: '/screen', label: <Link to="/screen">因子选股</Link> },
        { key: '/recommendation', label: <Link to="/recommendation">智能推荐</Link> },
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
        { key: '/manual/backtest', label: <Link to="/manual/backtest">回测管理</Link> },
      ],
    },
  ];

  const selectedKeys = [location.pathname];
  const [openKeys, setOpenKeys] = useState(() => {
    const path = window.location.pathname;
    if (path.startsWith('/factor') || path === '/factor-weight-optimize') return ['factors'];
    if (path.startsWith('/strateg') || path === '/paper-trading' || path === '/backtests/param-optimize') return ['strategies'];
    if (path.startsWith('/backtest')) return ['backtests'];
    if (path === '/screen') return ['screen'];
    if (path.startsWith('/data-detail')) return ['data-info'];
    if (path === '/data-update' || path === '/scheduled-tasks' || path === '/sector-ranking') return ['data-info'];
    if (path.startsWith('/manual/')) return ['manual'];
    return [];
  });

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
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

      <Layout style={{ transition: 'all 0.2s' }}>
        <Header style={{
          background: '#fff',
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

          <Space style={{ marginRight: 8 }}>
            <Badge status="processing" text="系统运行正常" />
            <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 16 }}>
              Quant Platform v1.0 · Java 21 + Spring Boot 3
            </Typography.Text>
          </Space>
        </Header>

        <Content style={{ margin: 16, minHeight: 280 }}>
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
            <Route path="/backtests/:taskId/running" element={<BacktestRunning />} />
            <Route path="/backtests/:taskId/report" element={<BacktestReport />} />
            <Route path="/factor-weight-optimize" element={<FactorWeightOptimize defaultFactorCodes={[]} />} />
            <Route path="/factor-ic-ir" element={<FactorIcIrAnalysis />} />
            <Route path="/screen" element={<StockScreen />} />
            <Route path="/recommendation" element={<RecommendationList />} />
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
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  );
}

export default function App() {
  return (
    <BrowserRouter
      future={{
        v7_startTransition: true,
        v7_relativeSplatPath: true,
      }}
    >
      <AntApp>
        <AppLayout />
      </AntApp>
    </BrowserRouter>
  );
}
