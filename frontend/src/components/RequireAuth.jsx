import { useMemo } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Result, Button, Spin } from 'antd';
import { useAuthStore } from '../stores/authStore';

/**
 * 路由级权限守卫（数据驱动，无硬编码）。
 *
 * 设计：权限映射完全来自后端 /auth/me 返回的菜单树（每个菜单节点带 path + permission，
 * 与 Controller 类级 @SaCheckPermission 一一对应）。新增/调整菜单权限只改后端 DB，
 * 前端零改动。
 *
 * 匹配规则：对当前路由逐级回退取候选 path（/a/b/c → /a/b/c → /a/b → /a），
 * 命中菜单树中任一节点的 path 即取其 permission 做校验。
 *
 * 未命中任何菜单节点的路由（如 /manual/full 用户手册）fail-open 放行，由后端接口 403 兜底。
 * 菜单尚未加载完成（menus 为空）时也放行，避免启动空窗误挡；加载后 store 变化会触发重渲染拦截。
 */

/** 展平菜单树，构建 path -> permission 映射 */
function buildPermissionMap(menus) {
  const map = new Map();
  const walk = (nodes) => {
    for (const n of nodes || []) {
      if (n.path && n.permission) map.set(n.path, n.permission);
      if (Array.isArray(n.children) && n.children.length) walk(n.children);
    }
  };
  walk(menus);
  return map;
}

/** 当前路径的逐级回退候选（用于匹配菜单节点 path） */
function pathCandidates(pathname) {
  const parts = pathname.split('/').filter(Boolean);
  const res = [];
  for (let i = parts.length; i >= 1; i--) {
    res.push('/' + parts.slice(0, i).join('/'));
  }
  return res;
}

export default function RequireAuth({ children }) {
  const token = useAuthStore((s) => s.token);
  const permissions = useAuthStore((s) => s.permissions);
  const menus = useAuthStore((s) => s.menus);
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const bootstrapped = useAuthStore((s) => s.bootstrapped);
  const location = useLocation();
  const navigate = useNavigate();

  const permMap = useMemo(() => buildPermissionMap(menus), [menus]);

  // 启动引导尚未完成（fetchMe 进行中）：显示 loading，避免内存态为空时误跳登录
  if (!bootstrapped) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!token) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  // 登录态已确认，但菜单/权限尚未从后端加载完成——放行避免启动空窗误挡；
  // 加载完成后若当前路由确无权限，store 变化会触发本组件重渲染并拦截。
  let required = null;
  if (menus.length > 0) {
    for (const c of pathCandidates(location.pathname)) {
      if (permMap.has(c)) {
        required = permMap.get(c);
        break;
      }
    }
  }

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
