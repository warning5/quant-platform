import React from 'react';
import { Card, Typography, FloatButton, Space, Tag } from 'antd';
import {
  ManualStrategies,
  ManualBacktests,
  ManualBacktestCompare,
} from './sections/ManualStrategies.js';
import {
  ManualParamOptimize,
  ManualFromBacktestToTrading,
} from './sections/ManualParamOptimize.js';
import { ManualPaperTrading } from './sections/ManualPaperTrading.js';
const { Title, Text, Paragraph } = Typography;

const strategyNav = [
  { id: 'strategies',            label: '策略管理', color: 'blue'     },
  { id: 'backtests',             label: '策略回测', color: 'green'    },
  { id: 'backtest-compare',      label: '策略对比', color: 'orange'   },
  { id: 'paper-trading',         label: '模拟盘交易', color: 'cyan'     },
  { id: 'param-optimize',        label: '参数优化', color: 'purple'   },
  { id: 'from-backtest-to-trading', label: '实盘对接', color: 'red'      },
];

export default function ManualStrategyPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>使用手册 · 策略与回测</Title>
        <Text type="secondary" style={{ fontSize: 13 }}>策略定义、回测、参数优化、实盘对接</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} bodyStyle={{ padding: '8px 12px' }}>
        <Space size={[4, 4]} wrap>
          {strategyNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        <ManualStrategies />
        <ManualPaperTrading />
        <ManualBacktests />
        <ManualBacktestCompare />
        <ManualParamOptimize />
        <ManualFromBacktestToTrading />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
