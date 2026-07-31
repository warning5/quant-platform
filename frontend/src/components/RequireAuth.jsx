import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Result, Button } from 'antd';
import { useAuthStore } from '../stores/authStore';

/**
 * 路由 → 所需后端 view 权限
 * key 取路由前两段（/system/* 取两段，其余取首段），与 Controller 类级 @SaCheckPermission 一一对应。
 * 未列出的路由（如 /manual/full 用户手册）保持放行（fail-open），由后端接口 403 兜底。
 */
const ROUTE_PERMISSIONS = {
  '/market': 'market:view',
  '/market-thermometer': 'market:view',
  '/sector-ranking': 'market:view',
  '/data-detail/research': 'research:view',
  '/data-detail/financial': 'financial:view',
  '/data-update': 'data:view',
  '/scheduled-tasks': 'data:view',
  '/data-quality': 'data:view',
  '/factors': 'factor:view',
  '/factor-correlation': 'factor:view',
  '/factor-monitor': 'factor:view',
  '/factor-weight-optimize': 'factor:view',
  '/factor-ic-ir': 'factor:view',
  '/strategies': 'strategy:view',
  '/paper-trading': 'strategy:view',
  '/backtests': 'strategy:view',
  '/screen': 'screen:view',
  '/recommendation': 'recommendation:view',
  '/llm': 'llm:view',
  '/monitor': 'monitor:view',
  '/stock-analysis': 'stock:view',
  '/calendar': 'calendar:view',
  '/system/users': 'system:user:list',
  '/system/roles': 'system:role:list',
  '/system/menus': 'system:menu:list',
  '/audit-logs': 'system:audit:list',
  '/credentials': 'system:credential:list',
};

function routeBase(pathname) {
  const parts = pathname.split('/').filter(Boolean);
  if (parts.length === 0) return '/';
  if (parts[0] === 'system' && parts[1]) return `/${parts[0]}/${parts[1]}`;
  return `/${parts[0]}`;
}

export default function RequireAuth({ children }) {
  const token = useAuthStore((s) => s.token);
  const permissions = useAuthStore((s) => s.permissions);
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const location = useLocation();
  const navigate = useNavigate();

  if (!token) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  // 登录态已确认，但权限尚未从后端加载完成（permissions 为空）——放行避免启动空窗误挡；
  // 加载完成后若当前路由确无权限，store 变化会触发本组件重渲染并拦截。
  const required = ROUTE_PERMISSIONS[routeBase(location.pathname)];
  if (required && permissions.length > 0 && !hasPermission(required)) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="抱歉，您没有访问该页面的权限。"
        extra={
          <Button type="primary" onClick={() => navigate('/')}>
            返回首页
          </Button>
        }
      />
    );
  }

  return children;
}
