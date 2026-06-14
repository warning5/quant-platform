/**
 * 全局 message/notification 桥接：解决非 React 组件中静态 API 的 Ant Design 5 警告。
 * 由 MessageBridge 组件在 App 挂载时注入 context-aware 实例。
 */

let messageApi = null;
let notificationApi = null;

export const setMessageApi = (api) => {
  messageApi = api;
};

export const setNotificationApi = (api) => {
  notificationApi = api;
};

const noop = () => {};

export const message = {
  success: (content, duration, onClose) => messageApi?.success(content, duration, onClose) ?? noop,
  error: (content, duration, onClose) => messageApi?.error(content, duration, onClose) ?? noop,
  info: (content, duration, onClose) => messageApi?.info(content, duration, onClose) ?? noop,
  warning: (content, duration, onClose) => messageApi?.warning(content, duration, onClose) ?? noop,
  loading: (content, duration, onClose) => messageApi?.loading(content, duration, onClose) ?? noop,
};

export const notification = {
  open: (args) => notificationApi?.open(args) ?? noop,
  success: (args) => notificationApi?.success(args) ?? noop,
  error: (args) => notificationApi?.error(args) ?? noop,
  info: (args) => notificationApi?.info(args) ?? noop,
  warning: (args) => notificationApi?.warning(args) ?? noop,
};
