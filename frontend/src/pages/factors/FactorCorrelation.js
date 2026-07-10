import React, { useEffect, useState } from 'react';
import { Card, Row, Col, Select, DatePicker, Button, Table, Spin, Alert, Space, Tag, Tooltip, Divider } from 'antd';
import { message } from '../../utils/messageUtil';
import { PartitionOutlined, ReloadOutlined } from '@ant-design/icons';
import ReactECharts from '../../components/LazyECharts';
import dayjs from 'dayjs';
import { factorApi } from '../../api';

const { RangePicker } = DatePicker;
const { Option } = Select;

const fmt6 = v => v != null ? (+v).toFixed(6) : '-';

export default function FactorCorrelation() {
  const [allFactors, setAllFactors] = useState([]);
  const [selectedFactors, setSelectedFactors] = useState([]);
  const [dateRange, setDateRange] = useState([dayjs('2025-01-01'), dayjs('2026-04-16')]);
  const [correlationData, setCorrelationData] = useState([]);
  const [loading, setLoading] = useState(false);
  const [correlationLoading, setCorrelationLoading] = useState(false);

  // 加载所有因子
  useEffect(() => {
    setLoading(true);
    factorApi.list({ page: 0, size: 200 })
      .then(res => {
        // axios 拦截器已将 res.data.data 解包，res 本身即 PageResult，直接取 records
        const factors = res.records || [];
        setAllFactors(factors);
        // 默认选择已知有数据的因子（factor_value表实际计算过的）
        const defaultCodes = ['MOM5', 'MOM20', 'MOM60', 'VOL20', 'SIZE', 'VAL_FCF_YIELD'];
        const codes = factors.map(f => f.factorCode);
        const selected = defaultCodes.filter(c => codes.includes(c));
        if (selected.length >= 2) {
          setSelectedFactors(selected);
        } else if (factors.length > 0) {
          setSelectedFactors(factors.slice(0, 6).map(f => f.factorCode));
        }
      })
      .catch(() => setAllFactors([]))
      .finally(() => setLoading(false));
  }, []);

  // 计算相关性
  const computeCorrelation = () => {
    if (selectedFactors.length < 2) {
      alert('请至少选择2个因子');
      return;
    }

    setCorrelationLoading(true);
    const [start, end] = dateRange;

    factorApi.computeCorrelation({
      factorCodes: selectedFactors,
      startDate: start.format('YYYY-MM-DD'),
      endDate: end.format('YYYY-MM-DD')
    })
      .then(res => {
        // axios 拦截器已解包 res.data.data，res 直接是数组
        const data = Array.isArray(res) ? res : (res.data || []);
        setCorrelationData(data);
        if (data.length > 0) {
          console.log('[Correlation] 返回', data.length, '对, correlation类型:', typeof data[0].correlation, '示例:', data[0]);
        }
        if (data.length === 0) {
          message.info('所选因子在指定日期范围内无数据');
        }
      })
      .catch(err => {
        console.error('相关性计算失败:', err);
        setCorrelationData([]);
        // 优先展示后端返回的具体错误信息
        const errMsg = err?.response?.data?.message || err?.message || 'ClickHouse 连接超时或不可用';
        message.error('相关性计算失败: ' + errMsg, 5);
      })
      .finally(() => setCorrelationLoading(false));
  };

  // 构建相关性矩阵
  const buildCorrelationMatrix = () => {
    if (correlationData.length === 0 || selectedFactors.length === 0) return [];

    const matrix = [];
    const n = selectedFactors.length;

    for (let i = 0; i < n; i++) {
      const row = [];
      for (let j = 0; j < n; j++) {
        const code1 = selectedFactors[i];
        const code2 = selectedFactors[j];

        if (i === j) {
          row.push(1); // 对角线为1
        } else {
          const corr = correlationData.find(c =>
            (c.factorCode1 === code1 && c.factorCode2 === code2) ||
            (c.factorCode1 === code2 && c.factorCode2 === code1)
          );
          const raw = corr ? corr.correlation : 0;
          const val = typeof raw === 'string' ? parseFloat(raw) : Number(raw);
          row.push(isNaN(val) ? 0 : val);
        }
      }
      matrix.push(row);
    }

    return matrix;
  };

  // 热力图配置
  const getHeatmapOption = () => {
    const matrix = buildCorrelationMatrix();

    return {
      title: {
        text: '因子相关性热力图',
        left: 'center'
      },
      tooltip: {
        position: 'top',
        formatter: (params) => {
          const { data } = params;
          const [i, j, value] = data;
          const factor1 = selectedFactors[i];
          const factor2 = selectedFactors[j];
          return `
            <div style="padding: 8px;">
              <div><strong>${factor1}</strong></div>
              <div>vs</div>
              <div><strong>${factor2}</strong></div>
              <Divider style="margin: 4px 0"/>
              <div>相关系数: <strong>${fmt6(value)}</strong></div>
              <div>样本数: ${correlationData.find(c =>
                (c.factorCode1 === factor1 && c.factorCode2 === factor2) ||
                (c.factorCode1 === factor2 && c.factorCode2 === factor1)
              )?.sampleSize || '-'}
              </div>
            </div>
          `;
        }
      },
      grid: {
        height: '70%',
        top: '10%'
      },
      xAxis: {
        type: 'category',
        data: selectedFactors,
        splitArea: { show: true },
        axisLabel: { rotate: 45, interval: 0 }
      },
      yAxis: {
        type: 'category',
        data: selectedFactors,
        splitArea: { show: true }
      },
      visualMap: {
        min: -1,
        max: 1,
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: '5%',
        inRange: {
          color: ['#2f7ed8', '#ffffff', '#e74c3c']
        },
        text: ['正相关 (1.0)', '无相关 (0)', '负相关 (-1.0)']
      },
      series: [{
        name: '相关系数',
        type: 'heatmap',
        data: matrix.map((row, i) =>
          row.map((value, j) => [i, j, value])
        ).flat(),
        label: {
          show: true,
          formatter: (params) => {
            const val = Array.isArray(params.value) ? params.value[2] : params.value;
            return fmt6(val);
          }
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        }
      }]
    };
  };

  // 相关性数据表格
  const correlationColumns = [
    {
      title: '因子1',
      dataIndex: 'factorCode1',
      key: 'factorCode1',
      width: 150
    },
    {
      title: '因子2',
      dataIndex: 'factorCode2',
      key: 'factorCode2',
      width: 150
    },
    {
      title: '相关系数',
      dataIndex: 'correlation',
      key: 'correlation',
      width: 120,
      render: (value) => {
        const absValue = Math.abs(value);
        let color = '#595959';
        if (absValue > 0.7) color = '#cf1322'; // 强相关
        else if (absValue > 0.4) color = '#fa8c16'; // 中等相关
        else if (absValue > 0.2) color = '#faad14'; // 弱相关
        else color = '#389e0d'; // 无相关

        const direction = value > 0 ? '正相关' : value < 0 ? '负相关' : '无相关';

        return (
          <Tag color={color}>
            {fmt6(value)} ({direction})
          </Tag>
        );
      }
    },
    {
      title: '相关性强度',
      key: 'strength',
      width: 100,
      render: (_, record) => {
        const absValue = Math.abs(record.correlation);
        if (absValue > 0.7) return <Tag color="red">强相关</Tag>;
        if (absValue > 0.4) return <Tag color="orange">中等相关</Tag>;
        if (absValue > 0.2) return <Tag color="gold">弱相关</Tag>;
        return <Tag color="green">无相关</Tag>;
      }
    },
    {
      title: '样本数',
      dataIndex: 'sampleSize',
      key: 'sampleSize',
      width: 100
    }
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={
          <Space>
            <PartitionOutlined />
            <span>因子相关性分析</span>
          </Space>
        }
        extra={
          <Space>
            <Button
              type="primary"
              icon={<ReloadOutlined />}
              onClick={computeCorrelation}
              loading={correlationLoading}
              disabled={selectedFactors.length < 2}
            >
              计算相关性
            </Button>
          </Space>
        }
      >
        <Alert
          message="因子相关性分析说明"
          description={
            <div>
              <p><strong>相关性系数解释:</strong></p>
              <ul style={{ marginLeft: '20px', marginTop: '8px' }}>
                <li><strong>|r| ≥ 0.7</strong>: 强相关 - 两个因子高度重叠,不建议同时使用</li>
                <li><strong>0.4 ≤ |r| &lt; 0.7</strong>: 中等相关 - 因子有一定重叠,需谨慎组合</li>
                <li><strong>0.2 ≤ |r| &lt; 0.4</strong>: 弱相关 - 因子有一定独立性,可以组合</li>
                <li><strong>|r| &lt; 0.2</strong>: 无相关 - 因子独立,适合组合使用</li>
              </ul>
              <p style={{ marginTop: '8px' }}><strong>使用建议:</strong></p>
              <ul style={{ marginLeft: '20px' }}>
                <li>选择相关性较低的因子组合可提高策略稳定性</li>
                <li>避免同时使用相关性超过0.7的因子</li>
                <li>不同类型的因子(动量、价值、质量等)通常相关性较低</li>
              </ul>
            </div>
          }
          type="info"
          showIcon
          style={{ marginBottom: '16px' }}
        />

        <Row gutter={[16, 16]}>
          <Col span={24}>
            <Card title="因子选择" size="small">
              <Row gutter={[16, 16]}>
                <Col span={24}>
                  <Select
                    mode="multiple"
                    style={{ width: '100%' }}
                    placeholder="请选择因子(至少选择2个)"
                    value={selectedFactors}
                    onChange={setSelectedFactors}
                    maxTagCount="responsive"
                    loading={loading}
                  >
                    {allFactors.map(factor => (
                      <Option key={factor.factorCode} value={factor.factorCode}>
                        <Space>
                          <span>{factor.factorCode}</span>
                          <span style={{ color: '#8c8c8c' }}>{factor.factorName}</span>
                          <Tag color="blue">{factor.category}</Tag>
                        </Space>
                      </Option>
                    ))}
                  </Select>
                </Col>
                <Col span={12}>
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <span style={{ color: '#8c8c8c' }}>日期范围:</span>
                    <RangePicker
                      style={{ width: '100%' }}
                      value={dateRange}
                      onChange={setDateRange}
                      format="YYYY-MM-DD"
                    />
                  </Space>
                </Col>
              </Row>
            </Card>
          </Col>

          {correlationData.length > 0 && (
            <>
              <Col span={24}>
                <Card title="相关性热力图" size="small">
                  <ReactECharts
                    option={getHeatmapOption()}
                    style={{ height: '600px' }}
                    notMerge={true}
                    lazyUpdate={true}
                  />
                </Card>
              </Col>

              <Col span={24}>
                <Card title="相关性详细数据" size="small">
                  <Table
                    columns={correlationColumns}
                    dataSource={correlationData}
                    rowKey={(record, index) => `${record.factorCode1}-${record.factorCode2}`}
                    pagination={false}
                    scroll={{ x: true }}
                  />
                </Card>
              </Col>
            </>
          )}
        </Row>
      </Card>
    </div>
  );
}
