import React from 'react';
import {
  Card, Row, Col, Table, Tag, Empty, Alert,
  Statistic, Divider, Tooltip, Typography,
} from 'antd';
import { StarFilled, StarOutlined } from '@ant-design/icons';

const { Text } = Typography;

// 星级渲染
const StarRating = ({ stars }) => {
  if (!stars) return '-';
  const s = parseInt(stars) || 0;
  const items = [];
  for (let i = 0; i < 5; i++) {
    if (i < s) {
      items.push(<StarFilled key={i} style={{ marginRight: 2 }} />);
    } else {
      items.push(<StarOutlined key={i} style={{ marginRight: 2, color: '#d9d9d9' }} />);
    }
  }
  return <span style={{ color: '#faad14', fontSize: 14 }}>{items}</span>;
};

// 情感偏向颜色
const sentimentColor = (bias) => {
  if (bias > 0.3) return '#f5222d';
  if (bias < -0.3) return '#52c41a';
  return '#999';
};

const sentimentLabel = (bias) => {
  if (bias > 0.3) return '偏利好';
  if (bias < -0.3) return '偏风险';
  return '中性';
};

// 标签颜色
const tagColorMap = {
  PERFORMANCE: 'blue',
  EXPANSION: 'cyan',
  INDUSTRY_EVENT: 'orange',
  POLICY_RISK: 'red',
  RAW_MATERIAL: 'purple',
  M_A: 'magenta',
  UNLOCK: 'volcano',
  INCENTIVE: 'green',
  GOODWILL: 'gold',
  FUND: 'geekblue',
};

const tagLabelMap = {
  PERFORMANCE: '业绩',
  EXPANSION: '扩产/建厂',
  INDUSTRY_EVENT: '行业事件',
  POLICY_RISK: '政策风险',
  RAW_MATERIAL: '原材料',
  M_A: '并购定增',
  UNLOCK: '解禁减持',
  INCENTIVE: '股权激励',
  GOODWILL: '商誉',
  FUND: '资金流向',
};

// 新闻类型标签
const newsTypeTag = (type) => {
  if (type === 'positive') return <Tag color="red">利好</Tag>;
  if (type === 'negative') return <Tag color="green">风险</Tag>;
  return <Tag color="default">中性</Tag>;
};

// 利好新闻列表列定义
const positiveColumns = [
  {
    title: '星级',
    dataIndex: 'star',
    width: 80,
    align: 'center',
    render: (v) => <StarRating stars={v} />,
  },
  {
    title: '日期',
    dataIndex: 'publish_date',
    width: 130,
    align: 'center',
  },
  {
    title: '标题',
    dataIndex: 'title',
    ellipsis: true,
    render: (v) => (
      <Tooltip title={v}>
        <span>{v}</span>
      </Tooltip>
    ),
  },
  {
    title: '事件标签',
    dataIndex: 'tagLabel',
    width: 120,
    align: 'center',
    render: (v, r) => {
      const tags = r.event_tag ? r.event_tag.split(',') : [];
      return (
        <span>
          {tags.map((t, i) => (
            <Tag key={i} color={tagColorMap[t] || 'default'} style={{ fontSize: 11 }}>
              {tagLabelMap[t] || t}
            </Tag>
          ))}
        </span>
      );
    },
  },
  {
    title: '来源',
    dataIndex: 'source',
    width: 80,
    align: 'center',
  },
];

// 风险新闻列表列定义
const negativeColumns = [
  {
    title: '星级',
    dataIndex: 'star',
    width: 80,
    align: 'center',
    render: (v) => <StarRating stars={v} />,
  },
  {
    title: '日期',
    dataIndex: 'publish_date',
    width: 130,
    align: 'center',
  },
  {
    title: '标题',
    dataIndex: 'title',
    ellipsis: true,
    render: (v) => (
      <Tooltip title={v}>
        <span>{v}</span>
      </Tooltip>
    ),
  },
  {
    title: '事件标签',
    dataIndex: 'tagLabel',
    width: 120,
    align: 'center',
    render: (v, r) => {
      const tags = r.event_tag ? r.event_tag.split(',') : [];
      return (
        <span>
          {tags.map((t, i) => (
            <Tag key={i} color={tagColorMap[t] || 'default'} style={{ fontSize: 11 }}>
              {tagLabelMap[t] || t}
            </Tag>
          ))}
        </span>
      );
    },
  },
  {
    title: '来源',
    dataIndex: 'source',
    width: 80,
    align: 'center',
  },
];

// 中性新闻列表列定义
const neutralColumns = [
  {
    title: '日期',
    dataIndex: 'publish_date',
    width: 130,
    align: 'center',
  },
  {
    title: '标题',
    dataIndex: 'title',
    ellipsis: true,
    render: (v) => (
      <Tooltip title={v}>
        <span>{v}</span>
      </Tooltip>
    ),
  },
  {
    title: '事件标签',
    dataIndex: 'tagLabel',
    width: 120,
    align: 'center',
    render: (v, r) => {
      const tags = r.event_tag ? r.event_tag.split(',') : [];
      return (
        <span>
          {tags.map((t, i) => (
            <Tag key={i} color={tagColorMap[t] || 'default'} style={{ fontSize: 11 }}>
              {tagLabelMap[t] || t}
            </Tag>
          ))}
        </span>
      );
    },
  },
  {
    title: '来源',
    dataIndex: 'source',
    width: 80,
    align: 'center',
  },
];

// 主组件
export function NewsEventTab({ data, code, catalysts }) {
  if (!data) return <Empty description="暂无新闻数据，请先更新新闻数据" />;
  if (data.error) return <Alert type="warning" message={data.error} showIcon />;

  const {
    total30d = 0,
    positive30d = 0,
    negative30d = 0,
    tagged30d = 0,
    sentimentBias = 0,
    latestDate = null,
    newsScore = 0,
    positiveNews = [],
    negativeNews = [],
    neutralNews = [],
    eventTags = [],
  } = data;

  const biasColor = sentimentColor(sentimentBias);
  const biasLabel = sentimentLabel(sentimentBias);

  return (
    <div>
      {/* 统计卡片 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={4}>
          <Card size="small" styles={{ body: {padding: '10px 12px', textAlign: 'center'} }}>
            <div style={{ fontSize: 11, color: '#999' }}>新闻评分</div>
            <div style={{ fontSize: 22, fontWeight: 600, color: '#1890ff' }}>{newsScore}</div>
            <div style={{ fontSize: 11, color: '#999' }}>/10分</div>
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small" styles={{ body: {padding: '10px 12px', textAlign: 'center'} }}>
            <div style={{ fontSize: 11, color: '#999' }}>近30天</div>
            <div style={{ fontSize: 22, fontWeight: 600, color: '#333' }}>{total30d}</div>
            <div style={{ fontSize: 11, color: '#999' }}>条新闻</div>
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small" styles={{ body: {padding: '10px 12px', textAlign: 'center'} }}>
            <div style={{ fontSize: 11, color: '#999' }}>利好新闻</div>
            <div style={{ fontSize: 22, fontWeight: 600, color: '#f5222d' }}>{positive30d}</div>
            <div style={{ fontSize: 11, color: '#999' }}>条</div>
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small" styles={{ body: {padding: '10px 12px', textAlign: 'center'} }}>
            <div style={{ fontSize: 11, color: '#999' }}>风险新闻</div>
            <div style={{ fontSize: 22, fontWeight: 600, color: '#52c41a' }}>{negative30d}</div>
            <div style={{ fontSize: 11, color: '#999' }}>条</div>
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small" styles={{ body: {padding: '10px 12px', textAlign: 'center'} }}>
            <div style={{ fontSize: 11, color: '#999' }}>事件标签</div>
            <div style={{ fontSize: 22, fontWeight: 600, color: '#722ed1' }}>{tagged30d}</div>
            <div style={{ fontSize: 11, color: '#999' }}>条</div>
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small" styles={{ body: {padding: '10px 12px', textAlign: 'center'} }}>
            <div style={{ fontSize: 11, color: '#999' }}>情感偏向</div>
            <div style={{ fontSize: 18, fontWeight: 600, color: biasColor }}>
              {biasLabel}
            </div>
            <div style={{ fontSize: 11, color: '#999' }}>
              {sentimentBias > 0 ? '+' : ''}{(sentimentBias * 100).toFixed(0)}%
            </div>
          </Card>
        </Col>
      </Row>

      {/* 事件标签汇总 */}
      {eventTags.length > 0 && (
        <Card size="small" title="事件标签分布" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {eventTags.map((item, i) => (
              <Tag
                key={i}
                color={tagColorMap[item.tag] || 'default'}
                style={{ fontSize: 13, padding: '2px 10px' }}
              >
                {item.label || item.tag} ×{item.count}
              </Tag>
            ))}
          </div>
        </Card>
      )}

      {/* 利好新闻 */}
      <Card
        size="small"
        title={<span style={{ color: '#f5222d' }}>利好新闻（{positiveNews.length}条）</span>}
        style={{ marginBottom: 16 }}
      >
        {positiveNews.length > 0 ? (
          <Table
            size="small"
            pagination={{ pageSize: 5, size: 'small' }}
            dataSource={positiveNews}
            rowKey={(r, i) => (r.publish_date || '') + i}
            columns={positiveColumns}
          />
        ) : (
          <Empty description="近30天无利好新闻" />
        )}
      </Card>

      {/* 风险新闻 */}
      <Card
        size="small"
        title={<span style={{ color: '#52c41a' }}>风险新闻（{negativeNews.length}条）</span>}
        style={{ marginBottom: 16 }}
      >
        {negativeNews.length > 0 ? (
          <Table
            size="small"
            pagination={{ pageSize: 5, size: 'small' }}
            dataSource={negativeNews}
            rowKey={(r, i) => (r.publish_date || '') + i}
            columns={negativeColumns}
          />
        ) : (
          <Empty description="近30天无风险新闻" />
        )}
      </Card>

      {/* 中性新闻 */}
      <Card
        size="small"
        title={<span style={{ color: '#999' }}>其他新闻（{neutralNews.length}条）</span>}
      >
        {neutralNews.length > 0 ? (
          <Table
            size="small"
            pagination={{ pageSize: 5, size: 'small' }}
            dataSource={neutralNews}
            rowKey={(r, i) => (r.publish_date || '') + i}
            columns={neutralColumns}
          />
        ) : (
          <Empty description="暂无其他新闻" />
        )}
      </Card>

      {/* ── 催化剂追踪（从后端buildCatalysts提取）─────────────── */}
      {catalysts && catalysts.length > 0 && (
        <Card size="small" title={<span><Text strong>催化剂追踪</Text></span>} style={{ marginBottom: 16 }}>
          <Row gutter={24}>
            <Col span={11} style={{ paddingRight: 8 }}>
              <Card size="small" title="🟢 正面催化剂" style={{ background: '#f6ffed', border: 'none' }} styles={{ body: { padding: 8 } }}>
                {catalysts.filter(c => c.type === 'POSITIVE').map((c, i) => (
                  <div key={i} style={{ marginBottom: 6, padding: '4px 8px', background: '#fff', borderRadius: 4, fontSize: 13 }}>
                    <Text strong>{c.description}</Text>
                    <div style={{ fontSize: 11, color: '#666' }}>触发：{c.trigger} | 重要度：{'★'.repeat(c.importance)}{'☆'.repeat(5 - c.importance)}</div>
                  </div>
                ))}
                {catalysts.filter(c => c.type === 'POSITIVE').length === 0 && <Text type="secondary" style={{ fontSize: 12 }}>暂无</Text>}
              </Card>
            </Col>
            <Col span={11} style={{ paddingLeft: 8 }}>
              <Card size="small" title="🔴 风险事件" style={{ background: '#fff2f0', border: 'none' }} styles={{ body: { padding: 8 } }}>
                {catalysts.filter(c => c.type === 'NEGATIVE').map((c, i) => (
                  <div key={i} style={{ marginBottom: 6, padding: '4px 8px', background: '#fff', borderRadius: 4, fontSize: 13 }}>
                    <Text strong>{c.description}</Text>
                    <div style={{ fontSize: 11, color: '#666' }}>触发：{c.trigger} | 重要度：{'★'.repeat(c.importance)}{'☆'.repeat(5 - c.importance)}</div>
                  </div>
                ))}
                {catalysts.filter(c => c.type === 'NEGATIVE').length === 0 && <Text type="secondary" style={{ fontSize: 12 }}>暂无</Text>}
              </Card>
            </Col>
          </Row>
        </Card>
      )}

      {/* 数据说明 */}
      <div style={{ fontSize: 11, color: '#999', marginTop: 12, paddingLeft: 4 }}>
        数据来源：东方财富个股新闻（akshare）；情感评分基于关键词匹配；事件标签包括：业绩/扩产/政策/原材料/并购/解禁等10类。
      </div>
    </div>
  );
}
