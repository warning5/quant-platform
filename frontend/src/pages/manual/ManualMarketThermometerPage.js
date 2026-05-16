import React from 'react';
import { Card, Typography, FloatButton } from 'antd';
import ManualMarketThermometer from './sections/ManualMarketThermometer.js';
const { Title, Text } = Typography;

export default function ManualMarketThermometerPage() {
  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>
          🌡️ 使用手册 · 大盘温度计
        </Title>
        <Text type="secondary" style={{ fontSize: 13 }}>恐慌贪婪指数 · 均线温度 · 股债收益 · QVIX波动率</Text>
      </div>

      <Card>
        <ManualMarketThermometer />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
