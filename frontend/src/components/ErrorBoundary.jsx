import React from 'react';

/**
 * 根级错误边界：捕获任意子组件渲染期异常，避免单点崩溃导致整站白屏。
 * 仅展示通用提示，不向用户泄露异常堆栈等敏感信息。
 */
export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error) {
    // 开发期打印到控制台即可，生产构建会被 drop_console 剔除
    console.error('Uncaught UI error:', error && error.message);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <h2>页面出现错误</h2>
          <p style={{ color: '#888', marginTop: 12 }}>
            页面渲染时发生异常，请刷新重试或联系管理员。
          </p>
          <button
            onClick={() => window.location.reload()}
            style={{ marginTop: 16, padding: '6px 16px', cursor: 'pointer' }}
          >
            刷新页面
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
