import React, { useEffect, useState } from 'react';
import { Card, Table, Tag, Space, Typography, Row, Col, Statistic, Input, Button, Popconfirm, Spin, Tooltip, DatePicker } from 'antd';
import { message } from '../../utils/messageUtil';
import { SearchOutlined, ReloadOutlined, FileTextOutlined, BankOutlined, CalendarOutlined, QuestionCircleOutlined, DeleteOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { researchApi, silentConfig } from '../../api';

const { Title, Text } = Typography;

// 评级颜色映射
const RATING_COLORS = {
  '买入': 'red',
  '强烈推荐': 'volcano',
  '推荐': 'orange',
  '增持': 'gold',
  '中性': 'blue',
  '持有': 'cyan',
  '减持': 'green',
  '卖出': 'purple',
};

// 评级文案
const epsTip = `EPS（Earnings Per Share，每股收益）= 净利润 ÷ 总股本，反映每股盈利水平，越高越好。\n数值对应各年度的券商预测值，负值表示预期亏损。`;
const peTip = `PE（Price-to-Earnings，市盈率）= 股价 ÷ EPS，反映投资回本年限，越低通常越便宜。\n数值对应各年度的券商预测值。`;

// 解析 eps_forecast JSON
function parseForecast(epsForecast) {
  if (!epsForecast) return {};
  try {
    return typeof epsForecast === 'string' ? JSON.parse(epsForecast) : epsForecast;
  } catch { return {}; }
}

function ResearchData() {
  const [overview, setOverview] = useState(null);
  const [loading, setLoading] = useState(true);
  const [reports, setReports] = useState([]);
  const [tableLoading, setTableLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [keyword, setKeyword] = useState('');
  const [dateRange, setDateRange] = useState(null);
  const [checkCode, setCheckCode] = useState('');
  const [checkResult, setCheckResult] = useState(null);
  const [checkLoading, setCheckLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);
  const [deleteLoading, setDeleteLoading] = useState(false);

  // 加载概览数据
  const fetchOverview = () => {
    setLoading(true);
    researchApi.getOverview(silentConfig)
      .then(data => {
        if (data && data.totalCount !== undefined) {
          setOverview(data);
        } else {
          setOverview({ totalCount: 0, stockCount: 0, latestDate: null });
        }
      })
      .catch(() => {
        setOverview({ totalCount: 0, stockCount: 0, latestDate: null });
      })
      .finally(() => setLoading(false));
  };

  // 单条记录归一化（兼容 snake_case / camelCase）
  const norm = (r) => ({
    ...r,
    reportTitle: r.reportTitle ?? r["report_title"] ?? '',
    rating: r.rating ?? r["rating"] ?? '',
    institution: r.institution ?? r["institution"] ?? '',
    industry: r.industry ?? r["industry"] ?? '',
    epsForecast: parseForecast(r.epsForecast ?? r["eps_forecast"] ?? null),
  });

  // 加载研报列表
  const fetchReports = (pageNum = 1, size = pageSize) => {
    setTableLoading(true);
    const params = { page: pageNum, size, keyword };
    if (dateRange && dateRange[0] && dateRange[1]) {
      params.startDate = dateRange[0].format('YYYY-MM-DD');
      params.endDate = dateRange[1].format('YYYY-MM-DD');
    }
    researchApi.getList(params, silentConfig)
      .then(data => {
        const normalized = (data.list || []).map(norm).filter(r => r.reportTitle && r.reportTitle.trim());
        setReports(normalized);
        setTotal(data.total || 0);
        setPage(pageNum);
        setSelectedRowKeys([]);
      })
      .catch(() => {})
      .finally(() => setTableLoading(false));
  };

  // 检查单只股票
  const handleCheck = () => {
    if (!checkCode.trim()) {
      message.warning('请输入股票代码');
      return;
    }
    setCheckLoading(true);
    setCheckResult(null);
    researchApi.checkStock(checkCode.trim(), silentConfig)
      .then(data => {
        if (data.reports) data.reports = data.reports.map(norm);
        setCheckResult(data);
      })
      .catch(() => message.error('未找到该股票的研报数据'))
      .finally(() => setCheckLoading(false));
  };

  // 批量删除
  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) {
      message.warning('请选择要删除的记录');
      return;
    }
    setDeleteLoading(true);
    try {
      const res = await fetch('/api/research/batch-delete', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids: selectedRowKeys }),
      });
      const data = await res.json();
      if (data.code === 200) {
        const result = data.data;
        message.success(`删除完成: ${result.deleted} 条`);
        fetchReports(page, pageSize);
        fetchOverview();
      } else {
        message.error(data.message || '删除失败');
      }
    } catch (e) {
      message.error('删除失败，请稍后重试');
    } finally {
      setDeleteLoading(false);
    }
  };

  useEffect(() => {
    fetchOverview();
    fetchReports();
  }, []);

  // 研报列表列定义（数据检查子表格用，无选择列和操作列）
  const checkColumns = [
    { title: '股票', dataIndex: 'code', width: 120,
      render: (code, record) => <span><strong>{code}</strong> {record.name}</span> },
    { title: '报告标题', dataIndex: 'reportTitle', width: 250, ellipsis: true,
      render: (title) => title || '-' },
    { title: '评级', dataIndex: 'rating', width: 80, align: 'center',
      render: (rating) => rating ? <Tag color={RATING_COLORS[rating] || 'default'}>{rating}</Tag> : '-' },
    { title: '机构', dataIndex: 'institution', width: 120, ellipsis: true },
    { title: '报告日期', dataIndex: 'reportDate', width: 100 },
  ];

  // 主列表列定义
  const reportColumns = [
    { title: '股票', key: 'stock', width: 150, fixed: 'left',
      render: (_, record) => <span><strong>{record.code}</strong> {record.name}</span> },
    { title: '报告标题', dataIndex: 'reportTitle', width: 280, ellipsis: true,
      render: (title) => title || '-' },
    { title: '评级', dataIndex: 'rating', width: 100, align: 'center',
      render: (rating) => rating ? <Tag color={RATING_COLORS[rating] || 'default'}>{rating}</Tag> : '-' },
    { title: '机构', dataIndex: 'institution', width: 150, ellipsis: true },
    { title: '行业', dataIndex: 'industry', width: 120, ellipsis: true },
    { title: <Tooltip title={epsTip} overlayClassName="tip-light" styles={{ root: { maxWidth: 420 } }}><span style={{ cursor: 'pointer' }}>EPS预测<QuestionCircleOutlined style={{ fontSize: 12, marginLeft: 4 }} /></span></Tooltip>, key: 'eps', width: 220, align: 'right',
      render: (_, record) => {
        const f = record.epsForecast;
        if (!f || Object.keys(f).length === 0) return '-';
        const years = Object.keys(f).sort();
        return (
          <span style={{ fontSize: 12 }}>
            {years.map(y => (
              <span key={y}>
                <span style={{ color: '#999' }}>{y.slice(2)}:</span>{' '}
                <strong>{f[y].eps?.toFixed(2) ?? '-'}</strong>
                {y !== years[years.length - 1] ? ' / ' : ''}
              </span>
            ))}
          </span>
        );
      }},
    { title: <Tooltip title={peTip} overlayClassName="tip-light" styles={{ root: { maxWidth: 420 } }}><span style={{ cursor: 'pointer' }}>PE预测<QuestionCircleOutlined style={{ fontSize: 12, marginLeft: 4 }} /></span></Tooltip>, key: 'pe', width: 220, align: 'right',
      render: (_, record) => {
        const f = record.epsForecast;
        if (!f || Object.keys(f).length === 0) return '-';
        const years = Object.keys(f).sort();
        return (
          <span style={{ fontSize: 12 }}>
            {years.map(y => (
              <span key={y}>
                <span style={{ color: '#999' }}>{y.slice(2)}:</span>{' '}
                <strong>{f[y].pe?.toFixed(1) ?? '-'}</strong>
                {y !== years[years.length - 1] ? ' / ' : ''}
              </span>
            ))}
          </span>
        );
      }},
    { title: '报告日期', dataIndex: 'reportDate', width: 110 },
    { title: '操作', key: 'action', width: 80, fixed: 'right',
      render: (_, record) => record.pdfUrl ? (
        <a href={record.pdfUrl} target="_blank" rel="noopener noreferrer">PDF</a>
      ) : '-' },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 16 }}>
        <FileTextOutlined /> 研报数据
        <Button size="small" icon={<ReloadOutlined />} onClick={fetchOverview} loading={loading}
          style={{ marginLeft: 12, verticalAlign: 'middle' }}>刷新概览</Button>
      </Title>

      {/* 数据概览 */}
      <Spin spinning={loading}>
        {overview ? (
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={8}>
              <Card size="small">
                <Statistic title="研报总数" value={overview.totalCount || 0} suffix="条"
                  valueStyle={{ fontSize: 20, color: '#1677ff' }} prefix={<FileTextOutlined />} />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic title="覆盖股票" value={overview.stockCount || 0} suffix="只"
                  valueStyle={{ fontSize: 20, color: '#52c41a' }} prefix={<BankOutlined />} />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic title="最新日期" value={overview.latestDate || '--'}
                  valueStyle={{ fontSize: 16 }} prefix={<CalendarOutlined />} />
              </Card>
            </Col>
          </Row>
        ) : (
          <div style={{ textAlign: 'center', padding: 24, color: '#999', marginBottom: 16 }}>
            概览数据加载失败
            <Button type="link" icon={<ReloadOutlined />} onClick={fetchOverview} loading={loading}>重试</Button>
          </div>
        )}
      </Spin>

      {/* 研报列表 */}
      <Card title="研报列表" size="small"
        extra={
          <Space>
            <Input placeholder="搜索股票代码/名称/标题" value={keyword}
              onChange={e => setKeyword(e.target.value)} onPressEnter={() => fetchReports(1)}
              style={{ width: 200 }} prefix={<SearchOutlined />} allowClear />
            <DatePicker.RangePicker value={dateRange} onChange={setDateRange}
              allowClear style={{ width: 240 }} />
            <Button type="primary" icon={<SearchOutlined />} onClick={() => fetchReports(1)}>搜索</Button>
            <Button icon={<ReloadOutlined />} onClick={() => { setKeyword(''); setDateRange(null); fetchReports(1); }}>重置</Button>
            {selectedRowKeys.length > 0 && (
              <Popconfirm title={`确定删除 ${selectedRowKeys.length} 条记录？`} onConfirm={handleBatchDelete}
                okText="确定" cancelText="取消" okButtonProps={{ danger: true }}>
                <Button danger icon={<DeleteOutlined />} loading={deleteLoading}>
                  删除 ({selectedRowKeys.length})
                </Button>
              </Popconfirm>
            )}
          </Space>
        }>
        <Table dataSource={reports} columns={reportColumns} rowKey="id" size="small"
          loading={tableLoading} scroll={{ x: 1380 }}
          rowSelection={{
            selectedRowKeys,
            onChange: setSelectedRowKeys,
          }}
          pagination={{
            current: page, pageSize, total, showSizeChanger: true,
            showTotal: (t) => <span style={{ fontSize: 12 }}>共 {t} 条</span>,
            onChange: (p, s) => { setPageSize(s); fetchReports(p, s); },
          }} />
      </Card>

      {/* 数据检查 */}
      <Card title="数据检查" size="small" style={{ marginTop: 16 }}>
        <Space style={{ marginBottom: checkResult ? 12 : 0 }}>
          <Input placeholder="输入股票代码，如 000001" value={checkCode}
            onChange={e => setCheckCode(e.target.value)} onPressEnter={handleCheck} style={{ width: 200 }} />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleCheck} loading={checkLoading}>
            查询
          </Button>
          {checkResult && (
            <Button type="text" onClick={() => setCheckResult(null)}>清除结果</Button>
          )}
        </Space>
        {checkResult && (
          <Spin spinning={checkLoading}>
            <Card size="small" style={{ marginTop: 8 }}>
              <Row gutter={16} style={{ marginBottom: 12 }}>
                <Col><Text><strong>股票代码：</strong>{checkResult.code} {checkResult.name}</Text></Col>
                <Col><Text><strong>研报数量：</strong>{checkResult.reportCount || 0} 条</Text></Col>
                <Col><Text><strong>最新研报日期：</strong>{checkResult.latestDate || '-'}</Text></Col>
              </Row>
              {checkResult.reports && checkResult.reports.length > 0 && (
                <Table dataSource={checkResult.reports} columns={checkColumns}
                  rowKey="id" size="small" pagination={false} scroll={{ x: 800 }} />
              )}
            </Card>
          </Spin>
        )}
      </Card>
    </div>
  );
}

export default ResearchData;
