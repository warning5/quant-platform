import React, { useState, useEffect } from 'react';
import { Card, Tag, Button, Descriptions, Badge, Space, Typography, Spin, Alert, Row, Col, Statistic, Tooltip } from 'antd';
import {
  RobotOutlined, ThunderboltOutlined, SafetyCertificateOutlined,
  RiseOutlined, FallOutlined, BulbOutlined, WarningOutlined,
  ClockCircleOutlined, DollarOutlined, StockOutlined
} from '@ant-design/icons';
import { llmApi } from '../../api';

const { Title, Text, Paragraph } = Typography;

/** 风险等级颜色 */
const RISK_COLORS = { LOW: 'green', MEDIUM: 'orange', HIGH: 'red' };
const RISK_LABELS = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' };
const REC_COLORS = { BUY: 'red', WATCH: 'orange', SKIP: 'default' };
const REC_LABELS = { BUY: '建议买入', WATCH: '持续观察', SKIP: '暂不介入' };

const LlmAnalysisPage = () => {
  const [analyses, setAnalyses] = useState([]);
  const [loading, setLoading] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetchAnalyses();
  }, []);

  const fetchAnalyses = async () => {
    setLoading(true);
    try {
      const res = await llmApi.getAnalyses();
      setAnalyses(res.data?.data || []);
    } catch (e) {
      setError('获取LLM分析结果失败');
    } finally {
      setLoading(false);
    }
  };

  const triggerAnalysis = async () => {
    setAnalyzing(true);
    setError(null);
    try {
      const res = await llmApi.triggerAnalysis('VALUE_QUALITY', 15);
      const result = res.data?.data;
      if (result) {
        await fetchAnalyses();
      }
    } catch (e) {
      setError('LLM推理失败：' + (e.message || '请检查DeepSeek API配置'));
    } finally {
      setAnalyzing(false);
    }
  };

  const buyCount = analyses.filter(a => a.recommendation === 'BUY').length;
  const watchCount = analyses.filter(a => a.recommendation === 'WATCH').length;

  return (
    <div style={{ padding: 24 }}>
      <Card>
        <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <Title level={4} style={{ margin: 0 }}>
              <RobotOutlined /> AI推理分析
            </Title>
            <Text type="secondary">DeepSeek大模型对候选股的综合推理</Text>
          </Col>
          <Col>
            <Space>
              <Button
                type="primary"
                icon={<ThunderboltOutlined />}
                onClick={triggerAnalysis}
                loading={analyzing}
              >
                {analyzing ? '推理中...' : '执行AI推理'}
              </Button>
              <Button onClick={fetchAnalyses} loading={loading}>刷新</Button>
            </Space>
          </Col>
        </Row>

        {error && <Alert message={error} type="error" showIcon style={{ marginBottom: 16 }} closable onClose={() => setError(null)} />}

        {analyses.length > 0 && (
          <Row gutter={16} style={{ marginBottom: 24 }}>
            <Col span={8}>
              <Card size="small">
                <Statistic title="分析股票数" value={analyses.length} suffix="只" />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic title="建议买入" value={buyCount} valueStyle={{ color: '#cf1322' }} suffix="只" />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic title="持续观察" value={watchCount} valueStyle={{ color: '#fa8c16' }} suffix="只" />
              </Card>
            </Col>
          </Row>
        )}

        {analyzing && (
          <Card style={{ textAlign: 'center', padding: 40 }}>
            <Spin size="large" />
            <Paragraph style={{ marginTop: 16 }}>
              <RobotOutlined /> DeepSeek正在推理中，每只股票约需5~10秒...
            </Paragraph>
            <Text type="secondary">分析因子数据 → 组装Prompt → 调用API → 解析结果</Text>
          </Card>
        )}

        {!analyzing && analyses.length === 0 && !loading && (
          <Card style={{ textAlign: 'center', padding: 60 }}>
            <RobotOutlined style={{ fontSize: 48, color: '#d9d9d9' }} />
            <Paragraph style={{ marginTop: 16, color: '#999' }}>
              暂无LLM分析结果。点击"执行AI推理"对当前候选股进行分析。
            </Paragraph>
            <Text type="secondary">
              需先在application.yml中配置 llm.deepseek.api-key 并设置 enabled=true
            </Text>
          </Card>
        )}

        {!analyzing && analyses.map((a, idx) => (
          <Card
            key={a.id || idx}
            style={{ marginBottom: 16 }}
            title={
              <Space>
                <StockOutlined />
                <span>{a.stockName} ({a.stockCode})</span>
                <Tag color={REC_COLORS[a.recommendation]}>{REC_LABELS[a.recommendation] || a.recommendation}</Tag>
                <Tag color={RISK_COLORS[a.riskLevel]}>{RISK_LABELS[a.riskLevel] || a.riskLevel}</Tag>
              </Space>
            }
            extra={<Text type="secondary">{a.model} · {a.analysisDate}</Text>}
          >
            <Row gutter={[24, 16]}>
              <Col span={8}>
                <Descriptions column={1} size="small" bordered>
                  <Descriptions.Item label={<><DollarOutlined /> 买入价区间</>}>
                    {a.buyPriceLow && a.buyPriceHigh ? (
                      <Text strong style={{ color: '#cf1322', fontSize: 16 }}>
                        ¥{a.buyPriceLow} ~ ¥{a.buyPriceHigh}
                      </Text>
                    ) : 'N/A'}
                  </Descriptions.Item>
                  <Descriptions.Item label={<><FallOutlined /> 止损价</>}>
                    {a.stopLoss ? <Text type="danger">¥{a.stopLoss}</Text> : 'N/A'}
                  </Descriptions.Item>
                  <Descriptions.Item label={<><RiseOutlined /> 目标价</>}>
                    {a.targetPrice ? <Text style={{ color: '#52c41a' }}>¥{a.targetPrice}</Text> : 'N/A'}
                  </Descriptions.Item>
                  <Descriptions.Item label={<><ClockCircleOutlined /> 投资周期</>}>
                    {a.timeHorizon || 'N/A'}
                  </Descriptions.Item>
                  <Descriptions.Item label={<><SafetyCertificateOutlined /> 仓位建议</>}>
                    {a.positionAdvice || 'N/A'}
                  </Descriptions.Item>
                </Descriptions>
              </Col>
              <Col span={16}>
                <div style={{ marginBottom: 12 }}>
                  <Text strong><BulbOutlined /> 投资逻辑</Text>
                  <Paragraph style={{ marginTop: 4, background: '#f6f8fa', padding: 12, borderRadius: 6 }}>
                    {a.logic || '暂无'}
                  </Paragraph>
                </div>
                {a.catalysts && (
                  <div style={{ marginBottom: 8 }}>
                    <Text strong style={{ color: '#52c41a' }}><ThunderboltOutlined /> 催化剂</Text>
                    <div style={{ marginTop: 4 }}>
                      {a.catalysts.split(';').map((c, i) => c.trim() && <Tag key={i} color="green">{c.trim()}</Tag>)}
                    </div>
                  </div>
                )}
                {a.risks && (
                  <div>
                    <Text strong style={{ color: '#ff4d4f' }}><WarningOutlined /> 风险提示</Text>
                    <div style={{ marginTop: 4 }}>
                      {a.risks.split(';').map((r, i) => r.trim() && <Tag key={i} color="red">{r.trim()}</Tag>)}
                    </div>
                  </div>
                )}
              </Col>
            </Row>
          </Card>
        ))}
      </Card>
    </div>
  );
};

export default LlmAnalysisPage;
