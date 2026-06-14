import { useEffect } from 'react';
import { App } from 'antd';
import { setMessageApi, setNotificationApi } from '../utils/messageUtil';

/**
 * 挂载 Ant Design 5 的 message/notification API 到全局桥接，
 * 使 api/index.js 等非 React 模块也能使用 context-aware 的实例。
 */
export default function MessageBridge() {
  const { message, notification } = App.useApp();
  useEffect(() => {
    setMessageApi(message);
    setNotificationApi(notification);
  }, [message, notification]);
  return null;
}
