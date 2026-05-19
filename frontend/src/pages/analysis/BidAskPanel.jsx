/**
 * BidAskPanel.jsx - 内外盘比分析面板
 *
 * 数据来源: /api/analysis/bid-ask
 * 采集脚本: update_bid_ask.py (akshare.stock_bid_ask_em)
 *
 * 解读:
 *   外盘(红): 以卖出价成交 = 主动买盘 → 推动上涨
 *   内盘(绿): 以买入价成交 = 主动卖盘 → 推动下跌
 *   内外盘比 = 外盘 / 内盘
 */
import React from 'react';
import { Card, Row, Col, Statistic, Progress, Tag, Tooltip, Divider } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

const trendColor = (trend) => {
  if (!trend) return 'default';
  if (trend === 'BUYER_STRONG') return 'red';
  if (trend === 'BUYER_SLIGHT') return 'volcano';
  if (trend === 'SELLER_STRONG') return 'green';
  if (trend === 'SELLER_SLIGHT') return 'cyan';
  return 'default';
};

const trendLabel = (trend) => {
  if (!trend) return '无数据';
  const map = {
    'BUYER_STRONG': '强势买方',
    'BUYER_SLIGHT': '买方略强',
    'BALANCED': '多空均衡',
    'SELLER_SLIGHT': '卖方略强',
    'SELLER_STRONG': '强势卖方',
    'NO_DATA': '无数据',
  };
  return map[trend] || trend;
};

// 格式化成交量
const formatVol = (v) => {
  if (v == null) return '-';
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿';
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万';
  return v.toLocaleString();
};

export function BidAskPanel({ data }) {
  if (!data) {
    return (
      <Card size="small" title="内外盘比分析">
        <div style={{ color: '#999', textAlign: 'center', padding: 20 }}>
          暂无数据（请先运行 update_bid_ask.py 采集）
        </div>
      </Card>
    );
  }

  const latest = data.latest || {};
  const ratio = data.ratio;
  const score = data.score || 0;
  const trend = data.trend;
  const trendLabel_ = data.trendLabel || trendLabel(trend);
  const avgRatio = data.avgRatio5d;
  const history5d = data.history5d || [];

  // 计算外内盘占比（百分比）
  const outerVol = latest.outer_vol;
  const innerVol = latest.inner_vol;
  const totalVol = (outerVol || 0) + (innerVol || 0);
  const outerPct = totalVol > 0 ? ((outerVol / totalVol) * 100).toFixed(1) : null;
  const innerPct = totalVol > 0 ? ((innerVol / totalVol) * 100).toFixed(1) : null;

  const ratioVal = ratio ? parseFloat(ratio) : null;

  return (
    <Card
      size="small"
      title={
        <span>
          内外盘比分析
          <Tooltip title="外盘=主动买盘（以卖出价成交），内盘=主动卖盘（以买入价成交）。外盘/内盘>1表明买方主导，防庄家对倒造假。数据由 update_bid_ask.py 每日收盘后采集。">
            <QuestionCircleOutlined style={{ marginLeft: 6, color: '#999' }} />
          </Tooltip>
        </span>
      }
      extra={<Tag color={trendColor(trend)}>{trendLabel_}</Tag>}
      style={{ marginBottom: 12 }}
    >
      <Row gutter={[12, 8]}>
        {/* 核心比值 */}
        <Col span={6}>
          <Statistic
            title="内外盘比"
            value={ratioVal != null ? ratioVal.toFixed(3) : '-'}
            suffix={ratioVal != null ? (ratioVal > 1 ? '↑' : ratioVal < 1 ? '↓' : '-') : ''}
            valueStyle={{
              color: ratioVal == null ? '#999'
                : ratioVal > 1.2 ? '#f5222d'
                : ratioVal > 1 ? '#fa8c16'
                : ratioVal >= 0.85 ? '#1890ff'
                : '#52c41a',
              fontSize: 22,
            }}
          />
        </Col>

        {/* 外盘量 */}
        <Col span={5}>
          <Statistic
            title={
              <span style={{ color: '#f5222d' }}>
                外盘（主动买）
              </span>
            }
            value={outerVol != null ? formatVol(outerVol) : '-'}
            suffix={outerPct != null ? <span style={{ fontSize: 12, color: '#f5222d' }}>{outerPct}%</span> : null}
          />
        </Col>

        {/* 内盘量 */}
        <Col span={5}>
          <Statistic
            title={
              <span style={{ color: '#52c41a' }}>
                内盘（主动卖）
              </span>
            }
            value={innerVol != null ? formatVol(innerVol) : '-'}
            suffix={innerPct != null ? <span style={{ fontSize: 12, color: '#52c41a' }}>{innerPct}%</span> : null}
          />
        </Col>

        {/* 5日均比 */}
        <Col span={4}>
          <Statistic
            title="5日均比"
            value={avgRatio != null && avgRatio > 0 ? avgRatio.toFixed(3) : '-'}
            valueStyle={{
              color: avgRatio == null ? '#999'
                : avgRatio > 1.1 ? '#f5222d'
                : avgRatio >= 0.9 ? '#1890ff'
                : '#52c41a',
            }}
          />
        </Col>

        {/* 评分 */}
        <Col span={4}>
          <Statistic
            title="内外盘得分"
            value={score}
            suffix={`/ 3`}
            valueStyle={{ color: score >= 2 ? '#f5222d' : score >= 1 ? '#fa8c16' : '#52c41a' }}
          />
        </Col>
      </Row>

      {/* 进度条：外内盘占比可视化 */}
      {totalVol > 0 && (
        <>
          <Divider style={{ margin: '10px 0 8px' }} />
          <div style={{ marginBottom: 4 }}>
            <span style={{ color: '#f5222d', fontWeight: 600 }}>外盘 {outerPct}%</span>
            <span style={{ color: '#999', margin: '0 8px' }}>|</span>
            <span style={{ color: '#52c41a', fontWeight: 600 }}>内盘 {innerPct}%</span>
          </div>
          <Tooltip title={`外盘 ${outerVol?.toLocaleString()} 手 / 内盘 ${innerVol?.toLocaleString()} 手`}>
            <Progress
              percent={outerPct != null ? parseFloat(outerPct) : 50}
              strokeColor="#f5222d"
              trailColor="#52c41a"
              showInfo={false}
              size="small"
            />
          </Tooltip>
          <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
            红=外盘（主动买），绿=内盘（主动卖）
          </div>
        </>
      )}

      {/* 近5日历史 */}
      {history5d.length > 0 && (
        <>
          <Divider style={{ margin: '10px 0 8px' }} />
          <div style={{ fontSize: 12, color: '#666' }}>
            近{history5d.length}日内外盘比：
            {history5d.map((h, i) => (
              <Tag
                key={i}
                color={
                  parseFloat(h.ratio) > 1.2 ? 'red'
                  : parseFloat(h.ratio) > 1 ? 'orange'
                  : parseFloat(h.ratio) >= 0.85 ? 'blue'
                  : 'green'
                }
                style={{ marginRight: 4 }}
              >
                {h.trade_date?.slice(5)} {parseFloat(h.ratio).toFixed(2)}
              </Tag>
            ))}
          </div>
        </>
      )}

      {data.trend === 'NO_DATA' && (
        <div style={{ marginTop: 8, color: '#ff4d4f', fontSize: 12 }}>
          ⚠ 无当日数据（可能为停牌或接口超时）
        </div>
      )}
    </Card>
  );
}
