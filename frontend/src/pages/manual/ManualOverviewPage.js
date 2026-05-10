import React from 'react';
import { Card, Typography, FloatButton } from 'antd';
import ManualOverview from './sections/ManualOverview.js';
import ManualMarket from './sections/ManualMarket.js';
const { Title, Text } = Typography;

export default function ManualOverviewPage() {
  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>使用手册 · 平台概述</Title>
        <Text type="secondary" style={{ fontSize: 13 }}>量化因子平台完整操作指南</Text>
      </div>
      <Card>
        <ManualOverview />
        <ManualMarket />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
