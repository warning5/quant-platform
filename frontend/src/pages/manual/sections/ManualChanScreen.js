import React from 'react';
import { Typography, Alert } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';

const { Title, Paragraph } = Typography;

/**
 * 缠论结构筛选使用手册章节
 * 2026-07-08: 缠论因子筛选功能已废弃（CHAN_* 因子从推荐管线移除）
 * 缠论可视化图表（个股分析页）仍保留
 */
export function ManualChanScreen() {
  return (
    <section id="chan-screen" style={{ paddingBottom: 16 }}>
      <Title level={2}>缠论结构筛选</Title>
      <Alert
        type="warning"
        showIcon
        icon={<InfoCircleOutlined />}
        message="此功能已废弃"
        description={
          <div>
            <Paragraph>
              缠论因子筛选功能已于 2026-07-08 废弃。原因：CHAN_* 系列因子（笔方向、走势类型、买卖点、中枢位置、笔数量）
              在推荐管线中的 IC 表现极差（季度 IC 正占比不足 25%），无法为选股策略提供有效信号。
            </Paragraph>
            <Paragraph>
              缠论可视化图表（个股分析页的「缠论图谱」Tab）仍保留，用于个股技术结构可视化展示，不参与评分和推荐。
            </Paragraph>
            <Paragraph>
              如需基于技术结构筛选股票，请在「策略管理」中使用趋势、MACD、近高近低等有效技术因子组合构建选股条件。
            </Paragraph>
          </div>
        }
      />
    </section>
  );
}

export default ManualChanScreen;
