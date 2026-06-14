import React from 'react';
import { Card, Typography, FloatButton, Space, Tag, Divider, Row, Col, Alert, Descriptions } from 'antd';
import { ThunderboltOutlined, SafetyCertificateOutlined, WarningOutlined, ControlOutlined, FundOutlined } from '@ant-design/icons';
import ManualPaperTradingCore from './sections/ManualPaperTradingCore.js';
import ManualPaperTradingTiming from './sections/ManualPaperTradingTiming.js';
import ManualPaperTradingAlert from './sections/ManualPaperTradingAlert.js';
import ManualPaperTradingRisk from './sections/ManualPaperTradingRisk.js';
const { Title, Text } = Typography;

const paperTradingNav = [
  { id: 'paper-core',         label: '基础使用',   color: 'blue'    },
  { id: 'paper-timing',       label: '择时信号',   color: 'cyan'    },
  { id: 'paper-alert',        label: '持仓预警',   color: 'orange'  },
  { id: 'paper-risk',         label: '风控配置',   color: 'red'     },
];

export default function ManualPaperTradingFullPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          <ThunderboltOutlined /> 使用手册 · 模拟盘
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>基础使用 · 择时信号 · 持仓预警 · 风控配置</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: {padding: '8px 12px'} }}>
        <Space size={[4, 4]} wrap>
          {paperTradingNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        <ManualPaperTradingCore />
        <ManualPaperTradingTiming />
        <ManualPaperTradingAlert />
        <ManualPaperTradingRisk />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
