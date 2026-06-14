import React, { useState } from 'react';
import { Card, Typography, Menu, FloatButton } from 'antd';
import {
  BarChartOutlined, FundOutlined, ThunderboltOutlined,
  StockOutlined, CloudSyncOutlined, AccountBookOutlined,
  MenuUnfoldOutlined, DashboardOutlined,
} from '@ant-design/icons';

// 导入章节组件
import ManualOverview from './sections/ManualOverview';
import ManualMarket from './sections/ManualMarket';
import ManualDataUpdate from './sections/ManualDataUpdate';
import ManualFinancial from './sections/ManualFinancial';
import {
  ManualFactors,
  ManualFactorMonitor,
  ManualFactorDetail,
  ManualFactorCreate,
} from './sections/ManualFactorComponents';
import {
  ManualFactorTest,
  ManualFactorOrthogonalize,
  ManualFactorDecay,
  ManualFactorCorrelation,
  ManualFactorStrategy,
} from './sections/ManualFactorTest';
import {
  ManualStrategies,
  ManualBacktests,
  ManualBacktestCompare,
} from './sections/ManualStrategies';
import {
  ManualParamOptimize,
  ManualFromBacktestToTrading,
  ManualFactorWeightOptimize,
} from './sections/ManualParamOptimize';
import { ManualChanScreen } from './sections/ManualChanScreen';

const { Title, Text } = Typography;

export default function Manual() {
  const [menuCollapsed, setMenuCollapsed] = useState(false);

  const scrollToSection = (id) => {
    const element = document.getElementById(id);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  const menuItems = [
    {
      key: 'overview',
      icon: <BarChartOutlined />,
      label: '平台概述',
      onClick: () => scrollToSection('overview'),
    },
    {
      key: 'dashboard',
      icon: <DashboardOutlined />,
      label: '总览仪表盘',
      onClick: () => scrollToSection('dashboard'),
    },
    {
      key: 'market',
      icon: <StockOutlined />,
      label: '行情数据',
      onClick: () => scrollToSection('market'),
    },
    {
      key: 'data-update',
      icon: <CloudSyncOutlined />,
      label: '数据更新',
      onClick: () => scrollToSection('data-update'),
    },
    {
      key: 'financial',
      icon: <AccountBookOutlined />,
      label: '财务数据',
      onClick: () => scrollToSection('financial'),
    },
    {
      key: 'factors-group',
      icon: <FundOutlined />,
      label: '因子管理',
      children: [
        { key: 'factors', label: '因子基础', onClick: () => scrollToSection('factors') },
        { key: 'factor-monitor', label: '因子计算', onClick: () => scrollToSection('factor-monitor') },
        { key: 'factor-detail', label: '因子值查看', onClick: () => scrollToSection('factor-detail') },
        { key: 'factor-test', label: '因子测试', onClick: () => scrollToSection('factor-test') },
        { key: 'factor-orthogonalize', label: '因子正交化', onClick: () => scrollToSection('factor-orthogonalize') },
        { key: 'factor-decay', label: '因子衰减分析', onClick: () => scrollToSection('factor-decay') },
        { key: 'factor-correlation', label: '因子相关性分析', onClick: () => scrollToSection('factor-correlation') },
        { key: 'factor-strategy', label: '因子策略', onClick: () => scrollToSection('factor-strategy') },
        { key: 'factor-weight-optimize', label: '因子权重', onClick: () => scrollToSection('factor-weight-optimize') },
        { key: 'factor-create', label: '新建因子', onClick: () => scrollToSection('factor-create') },
        { key: 'chan-screen', label: '缠论结构筛选', onClick: () => scrollToSection('chan-screen') },
      ],
    },
    {
      key: 'strategies-group',
      icon: <ThunderboltOutlined />,
      label: '策略管理',
      children: [
        { key: 'strategies', label: '策略定义', onClick: () => scrollToSection('strategies') },
        { key: 'backtests', label: '回测管理', onClick: () => scrollToSection('backtests') },
        { key: 'backtest-compare', label: '策略对比', onClick: () => scrollToSection('backtest-compare') },
        { key: 'param-optimize', label: '参数优化', onClick: () => scrollToSection('param-optimize') },
        { key: 'from-backtest-to-trading', label: '从回测到实战', onClick: () => scrollToSection('from-backtest-to-trading') },
      ],
    },
  ];

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', padding: '12px 24px 24px' }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 12
      }}>
        <Title level={3} style={{ margin: 0, fontSize: 20 }}>使用手册</Title>
        <Text type="secondary" style={{ fontSize: 13 }}>量化因子平台完整操作指南</Text>
      </div>

      {/* 顶部导航菜单 */}
      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: {padding: '8px 12px'} }}>
        <Menu
          mode="horizontal"
          selectedKeys={[]}
          defaultOpenKeys={[]}
          items={menuItems}
          style={{ border: 'none' }}
          overflowedIndicator={<MenuUnfoldOutlined />}
        />
      </Card>

      {/* 内容区域 */}
      <Card>
        <ManualOverview />
        <ManualMarket />
        <ManualDataUpdate />
        <ManualFinancial />
        <ManualFactors />
        <ManualFactorMonitor />
        <ManualFactorDetail />
        <ManualFactorTest />
        <ManualFactorOrthogonalize />
        <ManualFactorDecay />
        <ManualFactorCorrelation />
        <ManualFactorStrategy />
        <ManualStrategies />
        <ManualBacktests />
        <ManualBacktestCompare />
        <ManualParamOptimize />
        <ManualFromBacktestToTrading />
        <ManualFactorWeightOptimize />
        <ManualFactorCreate />
        <ManualChanScreen />
      </Card>

      <FloatButton.BackTop />
    </div>
  );
}
