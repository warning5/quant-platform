import React from 'react';
import { Card, Typography, FloatButton, Space, Tag } from 'antd';
import {
  ManualFactors,
  ManualFactorMonitor,
  ManualFactorDetail,
  ManualFactorCreate,
} from './sections/ManualFactorComponents.js';
import ManualFactorCalculation from './sections/ManualFactorCalculation.js';
import ManualFactorCorrelation from './sections/ManualFactorCorrelation.js';
import ManualFactorICIR from './sections/ManualFactorICIR.js';
import ManualFactorStrategy from './sections/ManualFactorStrategy.js';
import ManualFactorWeightOptimize from './sections/ManualFactorWeightOptimize.js';
import ManualChanScreen from './sections/ManualChanScreen.js';
const { Title, Text, Paragraph } = Typography;

const factorNav = [
  { id: 'factors',           label: '因子基础',     color: 'blue'    },
  { id: 'factor-calculation', label: '因子计算',     color: 'blue'    },
  { id: 'factor-monitor',     label: '因子监控',     color: 'green'   },
  { id: 'factor-correlation', label: '相关性分析',   color: 'orange'  },
  { id: 'factor-ic-ir',      label: 'IC/IR分析',   color: 'cyan'    },
  { id: 'factor-detail',      label: '因子值查看',   color: 'purple'  },
  { id: 'factor-strategy',    label: '因子策略',     color: 'red'     },
  { id: 'factor-weight-optimize', label: '权重优化', color: 'geekblue' },
  { id: 'factor-create',      label: '新建因子',     color: 'gold'    },
  { id: 'chan-screen',        label: '缠论筛选',     color: 'magenta' },
];

export default function ManualFactorPage() {
  const scrollTo = (id) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>使用手册 · 因子管理</Title>
        <Text type="secondary" style={{ fontSize: 13 }}>因子定义、计算、检测、策略、优化完整说明</Text>
      </div>

      {/* 顶部锚点导航 */}
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: {padding: '8px 12px'} }}>
        <Space size={[4, 4]} wrap>
          {factorNav.map(item => (
            <a key={item.id} onClick={() => scrollTo(item.id)}>
              <Tag color={item.color}>{item.label}</Tag>
            </a>
          ))}
        </Space>
      </Card>

      <Card>
        <ManualFactors />
        <ManualFactorCalculation />
        <ManualFactorMonitor />
        <ManualFactorCorrelation />
        <ManualFactorICIR />
        <ManualFactorDetail />
        <ManualFactorStrategy />
        <ManualFactorWeightOptimize />
        <ManualFactorCreate />
        <ManualChanScreen />
      </Card>
      <FloatButton.BackTop />
    </div>
  );
}
