import { useEffect } from 'react';
import { App } from 'antd';
import { setMessageApi } from '../utils/messageUtil';

/**
 * 挂载 Ant Design 5 的 message API 到全局桥接，
 * 使 api/index.js 等非 React 模块也能使用 context-aware 的 message。
 */
export default function MessageBridge() {
  const { message } = App.useApp();
  useEffect(() => {
    setMessageApi(message);
  }, [message]);
  return null;
}
