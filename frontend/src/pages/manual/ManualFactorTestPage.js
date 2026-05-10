import React from 'react';
import { Card, Typography, FloatButton } from 'antd';
import {
  ManualFactorTest,
  ManualFactorOrthogonalize,
  ManualFactorDecay,
  ManualFactorCorrelation,
  ManualFactorStrategy,
} from './sections/ManualFactorTest.js';
import { ManualFactorWeightOptimize } from './sections/ManualParamOptimize.js';
const { Title, Text } = Typography;

export default function ManualFactorTestPage() {
  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>使用手册 · 因子测试与优化</Title>
        <Text type="secondary" style={{ fontSize: 13 }}>因子测试、正交化、相关性、权重优化</Text>
      </div>
      <Card>
        <ManualFactorTest />
        <ManualFactorOrthogonalize />
        <ManualFactorDecay />
        <ManualFactorCorrelation />
        <ManualFactorStrategy />
        <ManualFactorWeightOptimize />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
