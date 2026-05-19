/**
 * InstitutionCoverageTab.jsx — Tab④ 机构跟踪
 *
 * 数据来源：
 *   - stock_research_report：研报覆盖（最权威，2738只股票）
 *   - stock_fund_holder：基金持仓（5188只股票）
 *   - stock_institution_research：机构调研（补充数据）
 *
 * 评分维度：
 *   - 研报覆盖（0-5分）：近1年研报数量
 *   - 基金持仓（0-4分）：合计流通比例
 *   - 机构调研（0-1分）：近90天调研次数
 *   - 综合得分：0-10分
 */
import React from 'react';
import {
  Card, Row, Col, Statistic, Progress, Tag, Table, Divider, Tooltip, Alert,
} from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

const scoreColor = (score, max) => {
  const pct = score / max;
  if (pct >= 0.7) return '#f5222d';
  if (pct >= 0.4) return '#fa8c16';
  return '#1890ff';
};

const ratingColor = (rating) => {
  if (!rating) return 'default';
  if (rating.includes('买入') || rating.includes('强烈')) return 'red';
  if (rating.includes('增持')) return 'orange';
  if (rating.includes('持有') || rating.includes('中性') || rating.includes('维持')) return 'blue';
  if (rating.includes('减持')) return 'green';
  if (rating.includes('卖出')) return 'default';
  return 'default';
};

const coverageColor = (level) => {
  if (!level) return 'default';
  if (level === '非常高') return 'red';
  if (level === '高') return 'orange';
  if (level === '中') return 'blue';
  if (level === '低') return 'purple';
  return 'default';
};

export function InstitutionCoverageTab({ data, code }) {
  if (!data) {
    return (
      <Card size="small" title="机构跟踪">
        <div style={{ color: '#999', textAlign: 'center', padding: 20 }}>
          加载中...
        </div>
      </Card>
    );
  }

  if (!data.hasData) {
    return (
      <Card size="small" title="机构跟踪">
        <Alert
          type="warning"
          message="暂无机构覆盖数据"
          description="该股票目前没有研报覆盖、基金持仓或机构调研记录，可能为小市值/冷门股票，机构关注度较低。"
        />
      </Card>
    );
  }

  const {
    totalScore, maxScore = 10, coverageLevel,
    reportCount, institutionCount, latestReportDate,
    reportScore, reportScoreMax = 5,
    fundCount, totalFloatRatio,
    fundScore, fundScoreMax = 4,
    researchCount90d, researchScore,
    recentReports, recentResearch,
  } = data;

  const floatRatioVal = parseFloat(totalFloatRatio) || 0;

  const reportColumns = [
    { title: '日期', dataIndex: 'report_date', key: 'date', width: 100 },
    { title: '评级', dataIndex: 'rating', key: 'rating', width: 80,
      render: (v) => <Tag color={ratingColor(v)}>{v || '-'}</Tag> },
    { title: '机构', dataIndex: 'institution', key: 'inst', width: 120 },
    { title: '标题', dataIndex: 'report_title', key: 'title',
      render: (v) => <span title={v}>{v?.length > 50 ? v.slice(0, 50) + '…' : v}</span> },
  ];

  const researchColumns = [
    { title: '日期', dataIndex: 'report_date', key: 'date', width: 100 },
    { title: '机构', dataIndex: 'org_name', key: 'org', width: 150 },
    { title: '调研摘要', dataIndex: 'content_summary', key: 'content',
      render: (v) => <span title={v}>{v?.length > 60 ? v.slice(0, 60) + '…' : v}</span> },
  ];

  return (
    <div>
      {/* 综合评分卡片 */}
      <Card size="small" title="机构覆盖度综合评分" style={{ marginBottom: 12 }}>
        <Row gutter={[12, 8]} align="middle">
          <Col span={4}>
            <Statistic
              title="综合得分"
              value={totalScore}
              suffix={`/ ${maxScore}`}
              valueStyle={{ color: scoreColor(totalScore, maxScore), fontSize: 28 }}
            />
            <Tag color={coverageColor(coverageLevel)} style={{ marginTop: 4 }}>
              {coverageLevel || '无数据'}
            </Tag>
          </Col>

          <Col span={6}>
            <div style={{ marginBottom: 6 }}>
              <span style={{ fontSize: 12, color: '#666' }}>研报覆盖</span>
              <span style={{ float: 'right', fontSize: 12 }}>
                {reportScore}/{reportScoreMax}分
              </span>
            </div>
            <Progress
              percent={Math.round(reportScore / reportScoreMax * 100)}
              strokeColor={scoreColor(reportScore, reportScoreMax)}
              size="small"
            />
            <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
              近1年 {reportCount} 篇 · {institutionCount} 家机构
              {latestReportDate && latestReportDate !== '-' && ` · 最新 ${latestReportDate}`}
            </div>
          </Col>

          <Col span={6}>
            <div style={{ marginBottom: 6 }}>
              <span style={{ fontSize: 12, color: '#666' }}>基金持仓</span>
              <span style={{ float: 'right', fontSize: 12 }}>
                {fundScore}/{fundScoreMax}分
              </span>
            </div>
            <Progress
              percent={Math.round(fundScore / fundScoreMax * 100)}
              strokeColor={scoreColor(fundScore, fundScoreMax)}
              size="small"
            />
            <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
              {fundCount} 只基金持仓 · 合计占流通 {floatRatioVal.toFixed(1)}%
            </div>
          </Col>

          <Col span={4}>
            <Statistic
              title={
                <span>
                  机构调研
                  <Tooltip title="近90天机构调研次数，数据源有限（仅部分股票有记录）">
                    <QuestionCircleOutlined style={{ marginLeft: 4, color: '#999' }} />
                  </Tooltip>
                </span>
              }
              value={researchScore}
              suffix="/1分"
              valueStyle={{ fontSize: 20, color: researchScore >= 1 ? '#f5222d' : '#999' }}
            />
            <div style={{ fontSize: 11, color: '#999' }}>
              近90天 {researchCount90d} 次调研
            </div>
          </Col>

          <Col span={4}>
            <Statistic
              title="研报数量"
              value={reportCount}
              valueStyle={{ fontSize: 20 }}
            />
            <div style={{ fontSize: 11, color: '#999' }}>
              近1年总计
            </div>
          </Col>
        </Row>

        <Divider style={{ margin: '12px 0 8px' }} />

        <div style={{ fontSize: 11, color: '#999' }}>
          <strong>评分说明：</strong>
          研报覆盖≥30篇=5分/≥15篇=4分/≥8篇=3分/≥3篇=2分/≥1篇=1分 |
          基金合计占流通≥30%=4分/≥20%=3分/≥10%=2分/≥3%=1分 |
          近90天有调研=1分
        </div>
      </Card>

      {/* 研报明细 */}
      {recentReports && recentReports.length > 0 && (
        <Card size="small" title="最新研报" style={{ marginBottom: 12 }}>
          <Table
            dataSource={recentReports}
            columns={reportColumns}
            rowKey={(r, i) => i}
            size="small"
            pagination={{ pageSize: 5, size: 'small' }}
            scroll={{ x: 600 }}
          />
        </Card>
      )}

      {/* 机构调研明细 */}
      {recentResearch && recentResearch.length > 0 && (
        <Card size="small" title="近90天机构调研">
          <Table
            dataSource={recentResearch}
            columns={researchColumns}
            rowKey={(r, i) => i}
            size="small"
            pagination={false}
            scroll={{ x: 700 }}
          />
          <div style={{ fontSize: 11, color: '#ff4d4f', marginTop: 8 }}>
            ⚠ 数据覆盖有限，仅展示已采集的调研记录
          </div>
        </Card>
      )}

      {!recentResearch || recentResearch.length === 0 ? (
        <Card size="small" title="近90天机构调研">
          <div style={{ color: '#999', textAlign: 'center', padding: 12 }}>
            近90天无机构调研记录（该数据源覆盖有限，不代表真实无调研）
          </div>
        </Card>
      ) : null}
    </div>
  );
}
