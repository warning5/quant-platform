/**
 * 全局 message 桥接：解决 api/index.js 等非 React 组件中静态 message 的 Ant Design 5 警告。
 * 由 MessageBridge 组件在 App 挂载时注入 messageApi 实例。
 */

let messageApi = null;

export const setMessageApi = (api) => {
  messageApi = api;
};

const noop = () => {};

export const message = {
  success: (content, duration, onClose) => messageApi?.success(content, duration, onClose) ?? noop,
  error: (content, duration, onClose) => messageApi?.error(content, duration, onClose) ?? noop,
  info: (content, duration, onClose) => messageApi?.info(content, duration, onClose) ?? noop,
  warning: (content, duration, onClose) => messageApi?.warning(content, duration, onClose) ?? noop,
  loading: (content, duration, onClose) => messageApi?.loading(content, duration, onClose) ?? noop,
};
