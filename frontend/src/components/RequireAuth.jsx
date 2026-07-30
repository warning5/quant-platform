import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';

/**
 * 路由守卫：未登录跳转到 /login
 */
export default function RequireAuth({ children }) {
  const token = useAuthStore((s) => s.token);
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return children;
}
