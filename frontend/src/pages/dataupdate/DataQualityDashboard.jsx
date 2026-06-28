import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card, Row, Col, Statistic, Tag, Typography, Button, Spin, Table, Badge,
  Tooltip, Space, Alert, Empty
} from 'antd';
import {
  CheckCircleOutlined, WarningOutlined, CloseCircleOutlined,
  SyncOutlined, ReloadOutlined, ClockCircleOutlined, ThunderboltOutlined
} from '@ant-design/icons';
import { message } from '../../utils/messageUtil';
import api from '../../api/index';
import dayjs from 'dayjs';

const { Text, Title } = Typography;

const DATA_QUALITY_LABELS = {
  stockDaily: '股票日线',
  factorValue: '因子数据',
  financialIndicator: '财务数据',
};

function DataQualityDashboard() {
  const [loading, setLoading] = useState(false);
  const [freshness, setFreshness] = useState(null);
  const [anomalies, setAnomalies] = useState(null);
  const [factorNulls, setFactorNulls] = useState(null);
  const [finAnomalies, setFinAnomalies] = useState(null);
  const [error, setError] = useState(null);
  const [lastCheck, setLastCheck] = useState(null);
  const intervalRef = useRef(null);

  const fetchAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [freshRes, anomalyRes, fnRes, faRes] = await Promise.all([
        api.get('/data-quality/freshness'),
        api.get('/data-quality/price-anomalies?days=7'),
        api.get('/data-quality/factor-nulls'),
        api.get('/data-quality/financial-anomalies'),
      ]);
      setFreshness(freshRes);
      setAnomalies(anomalyRes);
      setFactorNulls(fnRes);
      setFinAnomalies(faRes);
      setLastCheck(dayjs());
    } catch (e) {
      setError(e.response?.data?.message || e.message || '获取数据失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAll();
    // 每5分钟自动刷新
    intervalRef.current = setInterval(fetchAll, 5 * 60 * 1000);
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [fetchAll]);

  // === 新鲜度解析 ===
  const freshnessCards = ['stockDaily', 'factorValue', 'financialIndicator'];
  const getStatusColor = (stale) => stale ? '#ff4d4f' : '#52c41a';
  const getStatusIcon = (stale) => stale ? <WarningOutlined /> : <CheckCircleOutlined />;
  const getStatusText = (stale) => stale ? '异常' : '正常';

  const overallStatus = freshness && anomalies && factorNulls && finAnomalies
    ? ((freshness.hasWarning || anomalies.hasAnomaly || (factorNulls.hasWarning) || (finAnomalies.hasAnomaly)) ? 'warning' : 'success')
    : 'idle';

  // === 异常列表列 ===
  const anomalyColumns = [
    { title: '代码', dataIndex: 'code', key: 'code', width: 100 },
    { title: '名称', dataIndex: 'name', key: 'name', width: 100 },
    { title: '交易日期', dataIndex: 'tradeDate', key: 'tradeDate', width: 120 },
    {
      title: '涨跌幅(%)', dataIndex: 'changePct', key: 'changePct', width: 120,
      render: (v) => (
        <Tag color={v > 0 ? 'red' : 'green'}>
          {v > 0 ? '+' : ''}{Number(v).toFixed(2)}%
        </Tag>
      ),
    },
    { title: '收盘价', dataIndex: 'closePrice', key: 'closePrice', width: 100 },
    { title: '昨收价', dataIndex: 'preClose', key: 'preClose', width: 100 },
  ];

  const anomalyList = anomalies?.anomalies || [];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>
          <ThunderboltOutlined style={{ marginRight: 8 }} />
          数据质量仪表盘
        </Title>
        <Space>
          {lastCheck && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              上次检查: {lastCheck.format('HH:mm:ss')}
            </Text>
          )}
          <Button
            icon={<ReloadOutlined />}
            onClick={fetchAll}
            loading={loading}
          >
            刷新
          </Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        {/* 总体状态 */}
        <Alert
          type={overallStatus === 'success' ? 'success' : overallStatus === 'warning' ? 'warning' : 'info'}
          message={
            overallStatus === 'success' ? '一切正常 — 数据新鲜度达标，无价格异常'
            : overallStatus === 'warning' ? '发现问题 — 请查看下方详情'
            : '正在检查数据质量...'
          }
          showIcon
          style={{ marginBottom: 16 }}
        />

        {error && (
          <Alert type="error" message={`加载失败: ${error}`} showIcon style={{ marginBottom: 16 }} closable />
        )}

        {/* 数据新鲜度 */}
        <Card
          title={
            <span>
              <ClockCircleOutlined style={{ marginRight: 8 }} />
              数据新鲜度
            </span>
          }
          style={{ marginBottom: 16 }}
        >
          {freshness ? (
            <Row gutter={16}>
              {freshnessCards.map((key) => {
                const item = freshness[key];
                if (!item || item.error) return null;
                return (
                  <Col span={8} key={key}>
                    <Card
                      size="small"
                      style={{
                        borderLeft: `3px solid ${getStatusColor(item.stale)}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <Text strong>{DATA_QUALITY_LABELS[key] || key}</Text>
                        <Badge
                          status={item.stale ? 'error' : 'success'}
                          text={getStatusText(item.stale)}
                        />
                      </div>
                      <div style={{ marginTop: 8 }}>
                        <Row gutter={8}>
                          <Col span={12}>
                            <Statistic
                              title="最新数据日期"
                              value={item.latestDate || 'N/A'}
                              valueStyle={{ fontSize: 14 }}
                            />
                          </Col>
                          <Col span={12}>
                            <Statistic
                              title="落后天数"
                              value={Number(item.daysBehind || 0)}
                              suffix="天"
                              valueStyle={{
                                fontSize: 14,
                                color: item.stale ? '#ff4d4f' : '#52c41a',
                              }}
                            />
                          </Col>
                        </Row>
                      </div>
                    </Card>
                  </Col>
                );
              })}
            </Row>
          ) : (
            <Empty description="暂无新鲜度数据" />
          )}
        </Card>

        {/* 价格异常 */}
        <Card
          title={
            <span>
              <WarningOutlined style={{ marginRight: 8 }} />
              价格异常检测（近7天涨跌幅 &gt;50%）
            </span>
          }
          extra={
            anomalies ? (
              <Tag color={anomalies.hasAnomaly ? 'red' : 'green'}>
                {anomalies.hasAnomaly ? `发现 ${anomalies.anomalyCount} 条` : '未发现异常'}
              </Tag>
            ) : null
          }
          style={{ marginBottom: 16 }}
        >
          {anomalies && anomalyList.length > 0 ? (
            <Table
              dataSource={anomalyList}
              columns={anomalyColumns}
              rowKey={(r) => `${r.code}_${r.tradeDate}`}
              size="small"
              pagination={{ pageSize: 10 }}
            />
          ) : (
            <Empty description="近7天未发现价格异常" />
          )}
        </Card>

        {/* 因子NULL异常 */}
        <Card
          title={
            <span>
              <WarningOutlined style={{ marginRight: 8 }} />
              因子数据 NULL 异常（NULL比例 &gt;50%）
            </span>
          }
          extra={
            factorNulls ? (
              <Tag color={factorNulls.hasWarning ? 'orange' : 'green'}>
                {factorNulls.hasWarning ? `${factorNulls.nullFactorCount || 0} 个因子` : '正常'}
              </Tag>
            ) : null
          }
          style={{ marginBottom: 16 }}
        >
          {factorNulls && factorNulls.nullFactors && factorNulls.nullFactors.length > 0 ? (
            <Table
              dataSource={factorNulls.nullFactors}
              columns={[
                { title: '因子代码', dataIndex: 'factorCode', key: 'factorCode', width: 180 },
                {
                  title: 'NULL比例(%)', dataIndex: 'nullPct', key: 'nullPct', width: 130,
                  render: (v) => <Tag color={Number(v) > 80 ? 'red' : 'orange'}>{v}%</Tag>,
                },
                { title: '总记录数', dataIndex: 'totalCount', key: 'totalCount', width: 100 },
                { title: 'NULL数', dataIndex: 'nullCount', key: 'nullCount', width: 100 },
              ]}
              rowKey={(r) => r.factorCode}
              size="small"
              pagination={{ pageSize: 10 }}
            />
          ) : (
            <Empty description="所有因子NULL比例正常（≤50%）" />
          )}
        </Card>

        {/* 财务数据突变 */}
        <Card
          title={
            <span>
              <WarningOutlined style={{ marginRight: 8 }} />
              财务数据突变（营收/利润环比跳变 &gt;100%）
            </span>
          }
          extra={
            finAnomalies ? (
              <Tag color={finAnomalies.hasAnomaly ? 'red' : 'green'}>
                {finAnomalies.hasAnomaly ? `发现 ${finAnomalies.anomalyCount || 0} 条` : '正常'}
              </Tag>
            ) : null
          }
          style={{ marginBottom: 16 }}
        >
          {finAnomalies && finAnomalies.anomalies && finAnomalies.anomalies.length > 0 ? (
            <Table
              dataSource={finAnomalies.anomalies}
              columns={[
                { title: '代码', dataIndex: 'code', key: 'code', width: 100 },
                { title: '报告期', dataIndex: 'reportDate', key: 'reportDate', width: 120 },
                {
                  title: '营收变化(%)', dataIndex: 'revenueChgPct', key: 'revenueChgPct', width: 120,
                  render: (v) => v > 0 ? <Text type="danger">+{v}%</Text> : <Text>{v}%</Text>,
                },
                {
                  title: '利润变化(%)', dataIndex: 'profitChgPct', key: 'profitChgPct', width: 120,
                  render: (v) => v > 0 ? <Text type="danger">+{v}%</Text> : <Text>{v}%</Text>,
                },
              ]}
              rowKey={(r) => `${r.code}_${r.reportDate}`}
              size="small"
              pagination={{ pageSize: 10 }}
            />
          ) : (
            <Empty description="近18个月未发现财务数据突变" />
          )}
        </Card>

        {/* 上次检查时间 */}
        <Text type="secondary" style={{ display: 'block', textAlign: 'right' }}>
          检查时间: {freshness?.checkTime ? dayjs(freshness.checkTime).format('YYYY-MM-DD HH:mm:ss') : 'N/A'}
        </Text>
      </Spin>
    </div>
  );
}

export default DataQualityDashboard;
