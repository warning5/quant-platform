import React, { useState, useEffect, Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Link, useLocation, useParams, useNavigate } from 'react-router-dom';
import { App as AntApp, Layout, Menu, Typography, Space, Badge, Button, Tooltip, Spin, Drawer, Switch, ConfigProvider, theme, Dropdown, Avatar } from 'antd';
import {
  BarChartOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined,
  MoonOutlined, SunOutlined,
  LogoutOutlined, AppstoreOutlined,
  DashboardOutlined, UserOutlined, SettingOutlined,
} from '@ant-design/icons';
import { useAuthStore } from './stores/authStore';
import { ICON_MAP } from './utils/iconMap';
import RequireAuth from './components/RequireAuth';
import monitorApi from './api/monitor';

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
const ManualFullPage = lazy(() => import('./pages/manual/ManualFullPage'));
const FinancialData = lazy(() => import('./pages/financial/FinancialData'));
const ResearchData = lazy(() => import('./pages/datadetail/ResearchData'));
const DataUpdate = lazy(() => import('./pages/dataupdate/DataUpdate'));
const ScheduledTasks = lazy(() => import('./pages/dataupdate/ScheduledTasks'));
const DataQuality = lazy(() => import('./pages/dataupdate/DataQualityDashboard'));
const TaskRunHistory = lazy(() => import('./pages/dataupdate/TaskRunHistory'));
const StockAnalysis = lazy(() => import('./pages/analysis/StockAnalysis'));
const TradeCalendar = lazy(() => import('./pages/calendar/TradeCalendar'));
const MarketThermometer = lazy(() => import('./pages/analysis/MarketThermometer'));
const Login = lazy(() => import('./pages/login/Login'));
const SystemUserManage = lazy(() => import('./pages/system/UserManage'));
const SystemRoleManage = lazy(() => import('./pages/system/RoleManage'));
const SystemMenuManage = lazy(() => import('./pages/system/MenuManage'));
const AuditLog = lazy(() => import('./pages/system/AuditLog'));
const CredentialManage = lazy(() => import('./pages/system/CredentialManage'));
const DataPermissionManage = lazy(() => import('./pages/system/DataPermissionManage'));
const DepartmentManage = lazy(() => import('./pages/system/DepartmentManage'));
const DictManage = lazy(() => import('./pages/system/DictManage'));
const ConfigCenter = lazy(() => import('./pages/system/ConfigCenter'));
const SystemMonitor = lazy(() => import('./pages/system/SystemMonitor'));
const OnlineUser = lazy(() => import('./pages/system/OnlineUser'));

// 在菜单树中查找当前路由对应的所有祖先目录 key（用于自动展开）
function findOpenKeys(nodes, pathname, trail = []) {
  for (const n of nodes || []) {
    const cur = [...trail, 'sys-' + n.id];
    if (n.path && pathname === n.path) {
      return trail; // 命中叶子，返回其上层目录 key
    }
    if (n.children && n.children.length) {
      const res = findOpenKeys(n.children, pathname, cur);
      if (res) return res;
    }
  }
  return null;
}

// 把后端菜单树转换为 antd Menu items（仅目录/菜单参与，按权限过滤已在后端完成）
function buildSystemMenuItems(nodes) {
  return (nodes || []).map((node) => {
    const icon = ICON_MAP[node.icon] || <AppstoreOutlined />;
    if (node.children && node.children.length > 0) {
      return { key: 'sys-' + node.id, icon, label: node.menuName, children: buildSystemMenuItems(node.children) };
    }
    const key = node.path || 'sys-' + node.id;
    return {
      key,
      icon,
      label: node.path ? <Link to={node.path}>{node.menuName}</Link> : node.menuName,
    };
  });
}

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
      <Spin size="large"><div style={{ minHeight: 200 }} /></Spin>
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
  const navigate = useNavigate();
  const menus = useAuthStore((s) => s.menus);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);

  // 侧边栏菜单完全由后端 menus 渲染（已按当前用户角色权限过滤）
  const systemMenuItems = buildSystemMenuItems(menus);
  // 兜底：若后端未返回菜单（如角色尚未分配），至少保留「总览」可访问
  const fallbackItems = [{ key: '/', icon: <DashboardOutlined />, label: <Link to="/">总览</Link> }];
  const fullMenuItems = menus && menus.length ? systemMenuItems : fallbackItems;

  const selectedKeys = [location.pathname];
  const [openKeys, setOpenKeys] = useState(() =>
    findOpenKeys(menus, window.location.pathname) || []
  );

  // 菜单树是登录后异步加载的：menus 到达前 findOpenKeys 会算成 []，
  // 必须把 menus 纳入依赖，菜单一到位就重算父目录展开，避免当前页菜单项被藏进折叠态。
  useEffect(() => {
    const keys = findOpenKeys(menus, location.pathname);
    if (keys) setOpenKeys(keys);
  }, [location.pathname, menus]);

  // 行为统计埋点：路由切换时上报当前页面路径（失败静默，不影响导航）
  useEffect(() => {
    if (location.pathname && location.pathname !== '/login') {
      monitorApi.track(location.pathname).catch(() => {});
    }
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
          items={fullMenuItems}
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
            items={fullMenuItems}
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
            {/* ── 用户区 ── */}
            {user && (
              <Dropdown
                menu={{
                  items: [
                    { key: 'user', label: user.nickname || user.username, disabled: true },
                    { type: 'divider' },
                    {
                      key: 'logout',
                      icon: <LogoutOutlined />,
                      label: '退出登录',
                      onClick: async () => {
                        await logout();
                        navigate('/login');
                      },
                    },
                  ],
                }}
              >
                <Space style={{ cursor: 'pointer', marginLeft: 12 }}>
                  <Avatar size="small" src={user.avatar} icon={<UserOutlined />} />
                  <span>{user.nickname || user.username}</span>
                </Space>
              </Dropdown>
            )}
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
              <Route path="/data-quality" element={<DataQuality />} />
              <Route path="/task-monitor" element={<TaskRunHistory />} />
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
              <Route path="/manual/full" element={<ManualFullPage />} />
              <Route path="/stock-analysis" element={<StockAnalysis />} />
              <Route path="/market-thermometer" element={<MarketThermometer />} />
              <Route path="/sector-ranking" element={<SectorRanking />} />
              <Route path="/calendar" element={<TradeCalendar />} />
              <Route path="/system/users" element={<SystemUserManage />} />
              <Route path="/system/roles" element={<SystemRoleManage />} />
              <Route path="/system/menus" element={<SystemMenuManage />} />
              <Route path="/system/audit-logs" element={<AuditLog />} />
              <Route path="/system/credentials" element={<CredentialManage />} />
              <Route path="/system/data-permissions" element={<DataPermissionManage />} />
              {/* 兼容旧路由：重定向到统一的 /system/* 命名 */}
              <Route path="/audit-logs" element={<Navigate to="/system/audit-logs" replace />} />
              <Route path="/credentials" element={<Navigate to="/system/credentials" replace />} />
              <Route path="/data-permissions" element={<Navigate to="/system/data-permissions" replace />} />
              <Route path="/system/departments" element={<DepartmentManage />} />
              <Route path="/system/dict" element={<DictManage />} />
              <Route path="/system/config" element={<ConfigCenter />} />
              <Route path="/system/monitor" element={<SystemMonitor />} />
              <Route path="/system/online" element={<OnlineUser />} />
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

  // 启动恢复登录态：本地存在 token 时拉取用户信息（失败由拦截器清理并跳登录）
  useEffect(() => {
    if (useAuthStore.getState().token) {
      useAuthStore.getState().fetchMe().catch(() => {});
    }
  }, []);

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
          <Suspense fallback={<PageLoading />}>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route
                path="/*"
                element={
                  <RequireAuth>
                    <AppLayout isDark={isDark} setIsDark={setIsDark} />
                  </RequireAuth>
                }
              />
            </Routes>
          </Suspense>
        </AntApp>
      </ConfigProvider>
    </BrowserRouter>
  );
}
