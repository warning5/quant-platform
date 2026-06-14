/**
 * StockPerformanceTab.jsx — Tab ⑤ 长周期表现
 *
 * P2 新增功能：
 *   - YTD涨幅：年初至今收益率
 *   - 超额收益：相对沪深300的超额收益
 *   - RS Rating：近250日收益排名百分位（0-99），衡量个股在全体股票中的相对强弱
 *   - 行业内排名：该股在所属行业中的20日涨幅排名
 *
 * 数据来源：stock_daily（CH）+ index_daily（CH，沪深300）
 */
import React from 'react';
import {
  Card, Row, Col, Statistic, Progress, Tag, Divider, Tooltip, Alert,
} from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

// RS Rating 颜色
const rsRatingColor = (rating) => {
  if (rating >= 90) return '#f5222d';  // 极强-红
  if (rating >= 80) return '#fa541c';  // 很强-橙红
  if (rating >= 70) return '#fa8c16';  // 较强-橙
  if (rating >= 50) return '#52c41a';  // 中等偏强-绿
  if (rating >= 30) return '#1890ff';  // 中等偏弱-蓝
  if (rating >= 20) return '#722ed1';  // 较弱-紫
  return '#8c8c8c';                     // 极弱-灰
};

// RS Rating 标签
const rsRatingTagColor = (rating) => {
  if (rating >= 90) return 'red';
  if (rating >= 80) return 'orange';
  if (rating >= 70) return 'gold';
  if (rating >= 50) return 'green';
  if (rating >= 30) return 'blue';
  if (rating >= 20) return 'purple';
  return 'default';
};

// 超额收益颜色
const excessColor = (v) => {
  if (v > 0) return '#f5222d';
  if (v < 0) return '#52c41a';
  return '#999';
};

// YTD涨幅颜色
const ytdColor = (v) => {
  if (v > 0) return '#f5222d';
  if (v < 0) return '#52c41a';
  return '#999';
};

export function StockPerformanceTab({ data, code }) {
  if (!data) {
    return (
      <Card size="small" title="长周期表现">
        <div style={{ color: '#999', textAlign: 'center', padding: 20 }}>
          加载中...
        </div>
      </Card>
    );
  }

  if (data.error || !data.stockYtd) {
    return (
      <Card size="small" title="长周期表现">
        <Alert
          type="warning"
          message="数据不足"
          description={data.error || '该股票历史数据不足，无法计算YTD或RS Rating。'}
        />
      </Card>
    );
  }

  const {
    industry,
    yearStartDate,
    stockYtd,
    hs300Ytd,
    excessReturn,
    rsRating,
    rsRatingLabel,
    industryRank,
    industryTotal,
    industryRankPct,
  } = data;

  const stockYtdVal = parseFloat(stockYtd) || 0;
  const hs300YtdVal = parseFloat(hs300Ytd) || 0;
  const excessVal = parseFloat(excessReturn) || 0;
  const rsRatingVal = parseInt(rsRating) || 0;
  const indRankVal = parseInt(industryRank) || 0;
  const indTotalVal = parseInt(industryTotal) || 0;
  const indRankPctVal = parseFloat(industryRankPct) || 0;

  // 行业内排名进度（越小越好，Top 20% = 20分）
  const indRankProgress = indTotalVal > 0 ? Math.round((1 - indRankPctVal / 100) * 100) : 0;

  return (
    <Card size="small" title="长周期表现" style={{ fontSize: 13 }}>
      {/* 顶部三指标行 */}
      <Row gutter={[12, 8]} style={{ marginBottom: 12 }}>
        {/* YTD涨幅 */}
        <Col span={8}>
          <Card
            size="small"
            style={{
              background: '#fafafa',
              textAlign: 'center',
            }}
            styles={{ body: {padding: '10px 8px'} }}
          >
            <Statistic
              title={<span style={{ fontSize: 11, color: '#666' }}>YTD涨幅（今年至今）</span>}
              value={stockYtdVal}
              precision={2}
              suffix="%"
              valueStyle={{
                fontSize: 18,
                color: ytdColor(stockYtdVal),
                fontWeight: 700,
              }}
            />
            <div style={{ fontSize: 10, color: '#999', marginTop: 2 }}>
              起始日 {yearStartDate}
            </div>
          </Card>
        </Col>

        {/* 超额收益 */}
        <Col span={8}>
          <Card
            size="small"
            style={{
              background: excessVal >= 0 ? '#fff2f0' : '#f6ffed',
              textAlign: 'center',
            }}
            styles={{ body: {padding: '10px 8px'} }}
          >
            <Statistic
              title={
                <span style={{ fontSize: 11, color: '#666' }}>
                  超额收益
                  <Tooltip title="YTD涨幅 - 沪深300 YTD涨幅，正值表示跑赢大盘">
                    {' '}<QuestionCircleOutlined style={{ fontSize: 10 }} />
                  </Tooltip>
                </span>
              }
              value={excessVal}
              precision={2}
              suffix="%"
              prefix={excessVal > 0 ? '+' : ''}
              valueStyle={{
                fontSize: 18,
                color: excessColor(excessVal),
                fontWeight: 700,
              }}
            />
            <div style={{ fontSize: 10, color: '#999', marginTop: 2 }}>
              沪深300 {hs300YtdVal > 0 ? '+' : ''}{hs300YtdVal.toFixed(2)}%
            </div>
          </Card>
        </Col>

        {/* RS Rating */}
        <Col span={8}>
          <Card
            size="small"
            style={{
              background: rsRatingVal >= 80 ? '#fff0f0' : '#fafafa',
              textAlign: 'center',
            }}
            styles={{ body: {padding: '10px 8px'} }}
          >
            <Statistic
              title={
                <span style={{ fontSize: 11, color: '#666' }}>
                  RS Rating（250日）
                  <Tooltip title="近250日收益在全市场股票中的排名百分位（0-99）。90=全市场前10%，99=最强，0=最弱">
                    {' '}<QuestionCircleOutlined style={{ fontSize: 10 }} />
                  </Tooltip>
                </span>
              }
              value={rsRatingVal}
              precision={0}
              suffix="/99"
              valueStyle={{
                fontSize: 20,
                color: rsRatingColor(rsRatingVal),
                fontWeight: 700,
              }}
            />
            <Tag
              color={rsRatingTagColor(rsRatingVal)}
              style={{ fontSize: 10, marginTop: 2 }}
            >
              {rsRatingLabel}
            </Tag>
          </Card>
        </Col>
      </Row>

      <Divider style={{ margin: '8px 0' }} />

      {/* 详细指标 */}
      <Row gutter={[12, 8]}>
        {/* 超额收益进度条 */}
        <Col span={12}>
          <div style={{ marginBottom: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
              <span style={{ color: '#666' }}>相对沪深300 超额收益</span>
              <span style={{ color: excessColor(excessVal), fontWeight: 600 }}>
                {excessVal > 0 ? '+' : ''}{excessVal.toFixed(2)}%
              </span>
            </div>
            <Progress
              percent={50}
              success={{ percent: excessVal >= 0 ? 50 : 50 + excessVal / Math.abs(hs300YtdVal || 1) * 50 }}
              strokeColor={excessVal >= 0 ? '#f5222d' : '#52c41a'}
              trailColor="#e8e8e8"
              showInfo={false}
              size="small"
              style={{ marginBottom: 2 }}
            />
            <div style={{ fontSize: 10, color: '#999' }}>
              <span style={{ color: '#f5222d' }}>■</span> 超额收益{' '}
              <span style={{ color: '#52c41a' }}>■</span> 大盘基准
            </div>
          </div>
        </Col>

        {/* RS Rating 进度条 */}
        <Col span={12}>
          <div style={{ marginBottom: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
              <span style={{ color: '#666' }}>
                RS Rating（越高越强）
                <Tooltip title="RS Rating 衡量个股近250日收益在全体股票中的排名位置。90+为顶级强势股">
                  {' '}<QuestionCircleOutlined style={{ fontSize: 10 }} />
                </Tooltip>
              </span>
              <span style={{ color: rsRatingColor(rsRatingVal), fontWeight: 600 }}>
                {rsRatingVal} / 99
              </span>
            </div>
            <Progress
              percent={rsRatingVal}
              strokeColor={rsRatingColor(rsRatingVal)}
              trailColor="#e8e8e8"
              showInfo={false}
              size="small"
              format={() => rsRatingLabel}
            />
          </div>
        </Col>
      </Row>

      {/* 行业内排名（如果有行业信息） */}
      {industry && indTotalVal > 0 && (
        <>
          <Divider style={{ margin: '8px 0' }} />
          <Row gutter={12}>
            <Col span={24}>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
                  <span style={{ color: '#666' }}>
                    行业内排名（按20日涨幅）
                    <Tooltip title={`该股在「${industry}」行业中按20日涨幅的排名位置，共${indTotalVal}只股票。排名越小越强（Top 20% = 前20名）`}>
                      {' '}<QuestionCircleOutlined style={{ fontSize: 10 }} />
                    </Tooltip>
                  </span>
                  <span style={{ fontWeight: 600 }}>
                    第 {indRankVal} / {indTotalVal} 名
                    <Tag
                      color={indRankPctVal <= 20 ? 'red' : indRankPctVal <= 50 ? 'orange' : 'blue'}
                      style={{ marginLeft: 6, fontSize: 10 }}
                    >
                      Top {indRankPctVal.toFixed(0)}%
                    </Tag>
                  </span>
                </div>
                <Progress
                  percent={indRankProgress}
                  success={{ percent: indRankProgress }}
                  strokeColor={indRankPctVal <= 20 ? '#f5222d' : indRankPctVal <= 50 ? '#fa8c16' : '#1890ff'}
                  trailColor="#e8e8e8"
                  showInfo={false}
                  size="small"
                />
                <div style={{ fontSize: 10, color: '#999', marginTop: 2 }}>
                  行业：{industry}，共 {indTotalVal} 只可比股票（沪深，不含北交所）
                </div>
              </div>
            </Col>
          </Row>
        </>
      )}

      <Divider style={{ margin: '8px 0' }} />

      {/* 指标说明 */}
      <div style={{ fontSize: 11, color: '#999' }}>
        <b>指标说明：</b>
        YTD = 年初至今涨幅；RS Rating（Relative Strength Rating）衡量近250日收益在全体A股中的排名百分位，
        90+代表全市场前10%的强势股；超额收益 = 个股YTD - 沪深300 YTD。
      </div>
    </Card>
  );
}
