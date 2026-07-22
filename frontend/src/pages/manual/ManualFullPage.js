import React, { useRef, useState } from 'react';
import { Button, Typography } from 'antd';
import { ExportOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons';

const { Text } = Typography;

/**
 * 完整使用手册页面 —— 内嵌 manual-full.html（v3.0）。
 * 标题栏提供折叠/展开 iframe 内侧边栏的箭头按钮，以及新窗口打开入口。
 */
export default function ManualFullPage() {
  const iframeRef = useRef(null);
  const [collapsed, setCollapsed] = useState(false);

  const toggleSidebar = () => {
    const iframe = iframeRef.current;
    if (iframe && iframe.contentWindow) {
      iframe.contentWindow.postMessage({ type: 'TOGGLE_SIDEBAR' }, '*');
    }
    setCollapsed(prev => !prev);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 56px)' }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '0 20px',
        height: 40,
        flexShrink: 0,
        borderBottom: '1px solid #f0f0f0',
        background: '#fafafa',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Text strong style={{ fontSize: 14 }}>📖 使用手册 v3.0 · 完整版</Text>
          <Button
            type="text"
            size="small"
            icon={collapsed ? <RightOutlined /> : <LeftOutlined />}
            onClick={toggleSidebar}
            title={collapsed ? '展开菜单' : '收起菜单'}
          />
        </div>
        <a href="/manual-full.html" target="_blank" rel="noopener noreferrer" style={{ fontSize: 12 }}>
          <ExportOutlined /> 新窗口打开
        </a>
      </div>
      <iframe
        ref={iframeRef}
        src="/manual-full.html"
        title="使用手册 v3.0"
        style={{ flex: 1, width: '100%', border: 'none' }}
      />
    </div>
  );
}
