import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { Table, Tag, Button, Space, Input, Select, Card, Typography, Popconfirm, Tooltip, Badge, DatePicker, Alert, Modal, Radio } from 'antd';
import { message } from '../../utils/messageUtil';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, PlayCircleOutlined, ClearOutlined, SearchOutlined, CalculatorOutlined, ThunderboltOutlined, DownloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { factorApi } from '../../api';
import { CATEGORY_OPTIONS, CATEGORY_LABELS } from './constants';
import { exportCsv } from '../../utils/exportUtil';
import dayjs from 'dayjs';

const { Title } = Typography;
const { Option } = Select;
const STATUS_COLORS = { DRAFT:'default', TESTING:'processing', ACTIVE:'success', DEPRECATED:'default' };
const STATUS_LABELS = { DRAFT:'草稿', TESTING:'测试中', ACTIVE:'已激活', DEPRECATED:'已废弃' };
const TYPE_LABELS = { BUILTIN:'内置', SCRIPTED:'脚本', COMPOSITE:'合成' };

/**
 * 格式化因子值数量
 */
function formatCount(count) {
  if (count === 0) return '0';
  if (count >= 1000000) return (count / 1000000).toFixed(1) + 'M';
  if (count >= 1000) return (count / 1000).toFixed(1) + 'K';
  return String(count);
}

export default function FactorList() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState({ records: [], total: 0 });
  const [params, setParams] = useState({ page: 0, size: 15, keyword: '', category: undefined, status: undefined });
  const [factorStatus, setFactorStatus] = useState({});

  // 缺失因子查询状态
  const [missingDate, setMissingDate] = useState(null);
  const [missingFactors, setMissingFactors] = useState([]);
  const [missingLoading, setMissingLoading] = useState(false);
  const [showMissing, setShowMissing] = useState(false);
  const [computing, setComputing] = useState(false);

  // 批量计算 Modal 状态
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);
  const [computeModalVisible, setComputeModalVisible] = useState(false);
  const [computeDateRange, setComputeDateRange] = useState([null, null]);
  const [computeMode, setComputeMode] = useState('incremental');
  const [batchComputing, setBatchComputing] = useState(false);

  const selectedFactors = useMemo(() => {
    return (data.records || []).filter(r => selectedRowKeys.includes(r.id));
  }, [data.records, selectedRowKeys]);

  const fetchData = useCallback((p) => {
    setLoading(true);
    factorApi.list(p).then(res => {
      setData(res);
      // 批量查询因子计算状态
      const codes = (res.records || []).map(r => r.factorCode);
      if (codes.length > 0) {
        factorApi.batchStatus(codes).then(sRes => {
          setFactorStatus(prev => ({ ...prev, ...sRes }));
        }).catch(() => {});
      }
    }).catch(() => {}).finally(() => setLoading(false));
  }, []);

  // 初始化加载（使用 params 初始值）
  useEffect(() => { fetchData(params); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** 查询指定日期缺失因子值的因子 */
  const handleSearchMissing = useCallback(() => {
    if (!missingDate) {
      message.warning('请先选择日期');
      return;
    }
    setMissingLoading(true);
    setShowMissing(true);
    factorApi.missingByDate(missingDate.format('YYYY-MM-DD'))
      .then(res => {
        setMissingFactors(res || []);
        if (res && res.length > 0) {
          message.info(`共 ${res.length} 个因子在 ${missingDate.format('YYYY-MM-DD')} 缺失数据`);
        } else {
          message.success('该日期所有因子均有数据');
        }
      })
      .catch(() => message.error('查询失败'))
      .finally(() => setMissingLoading(false));
  }, [missingDate]);

  /** 一键补算缺失因子 */
  const handleComputeMissing = useCallback(() => {
    if (!missingDate || missingFactors.length === 0) return;
    const codes = missingFactors.map(f => f.factorCode);
    setComputing(true);
    factorApi.batchCompute(codes, missingDate.format('YYYY-MM-DD'), missingDate.format('YYYY-MM-DD'), false, true)
      .then(res => {
        const submitted = res?.submitted?.length ?? 0;
        const skipped = res?.skipped?.length ?? 0;
        message.success(`已提交 ${submitted} 个因子计算，跳过 ${skipped} 个`);
      })
      .catch(() => message.error('提交计算失败'))
      .finally(() => setComputing(false));
  }, [missingDate, missingFactors]);

  /** 打开批量计算弹窗 */
  const handleOpenBatchCompute = useCallback(() => {
    if (selectedRowKeys.length === 0) {
      message.warning('请先选择因子');
      return;
    }
    // 默认范围：去年年初 到今天（后端增量计算会自动跳过已有数据的日期）
    const defaultStart = dayjs().subtract(1, 'year').startOf('year');
    const defaultEnd = dayjs();
    setComputeDateRange([defaultStart, defaultEnd]);
    setComputeModalVisible(true);
  }, [selectedRowKeys.length]);

  /** 提交批量计算 —— 先跳转再异步提交，避免预加载K线耗时导致超时 */
  const handleBatchCompute = useCallback(() => {
    if (!computeDateRange[0] || !computeDateRange[1]) {
      message.warning('请选择计算日期范围');
      return;
    }
    const codes = selectedFactors.map(f => f.factorCode);
    const startDate = computeDateRange[0].format('YYYY-MM-DD');
    const endDate = computeDateRange[1].format('YYYY-MM-DD');
    const isIncremental = computeMode === 'incremental';

    // 立即关闭弹窗、清空选择、跳转监控页（不等待API）
    setComputeModalVisible(false);
    setSelectedRowKeys([]);
    navigate('/factor-monitor');

    // 后台异步提交，预加载K线耗时久不阻塞UI
    setBatchComputing(true);
    factorApi.batchCompute(codes, startDate, endDate, isIncremental, !isIncremental)
      .then(res => {
        const submitted = res?.submitted?.length ?? 0;
        const skipped = res?.skipped?.length ?? 0;
        message.success(`已提交 ${submitted} 个因子${isIncremental ? '增量' : '强制'}计算（${startDate} ~ ${endDate}），跳过 ${skipped} 个`);
      })
      .catch(err => {
        message.error('提交计算失败: ' + (err?.message || '未知错误'));
      })
      .finally(() => setBatchComputing(false));
  }, [computeDateRange, computeMode, selectedFactors, navigate]);

  const handleDelete = (id) => {
    factorApi.delete(id).then(() => { message.success('删除成功'); fetchData(params); });
  };

  const handleDeleteValues = (record) => {
    factorApi.deleteValues(record.id).then(res => {
      message.success(`已删除 ${res?.deleted ?? 0} 条因子值`);
      fetchData(params);
    });
  };

  const handleActivate = (record) => {
    const newStatus = record.status === 'ACTIVE' ? 'DRAFT' : 'ACTIVE';
    factorApi.changeStatus(record.id, newStatus).then(() => {
      message.success(`状态已更新为 ${STATUS_LABELS[newStatus]}`);
      fetchData(params);
    });
  };

  /**
   * 渲染因子计算状态
   */
  const renderCalcStatus = (_, record) => {
    const status = factorStatus[record.factorCode];
    const valueCount = status?.valueCount ?? null;
    const testCount = status?.testCount ?? null;
    const minDate = status?.minDate ?? null;
    const maxDate = status?.maxDate ?? null;

    const items = [];

    if (valueCount !== null) {
      if (valueCount > 0) {
        const dateRange = minDate && maxDate
          ? `${minDate} ~ ${maxDate}`
          : null;
        // Tag 内展示日期跨度，同一年时只显示一次年份
        const dateSpanLabel = minDate && maxDate ? (() => {
          if (minDate === maxDate) return ` · ${minDate}`;
          if (minDate.slice(0, 4) === maxDate.slice(0, 4)) {
            return ` · ${minDate}~${maxDate.slice(5)}`;
          }
          return ` · ${minDate}~${maxDate}`;
        })() : '';
        const tooltipTitle = dateRange
          ? `${valueCount.toLocaleString()} 条因子值\n${dateRange}`
          : `${valueCount.toLocaleString()} 条因子值`;
        items.push(
          <Tooltip key="v" title={tooltipTitle}>
            <Tag color="green" style={{ cursor: 'pointer' }}>
              <Badge status="success" /> 因子值 {formatCount(valueCount)}{dateSpanLabel}
            </Tag>
          </Tooltip>
        );
      } else {
        items.push(
          <Tag key="v" color="default">
            <Badge status="default" /> 无因子值
          </Tag>
        );
      }
    } else {
      items.push(<Tag key="v" color="default">-</Tag>);
    }

    if (testCount !== null) {
      if (testCount > 0) {
        items.push(
          <Tooltip key="t" title={`${testCount} 次检测`}>
            <Tag color="blue" style={{ cursor: 'pointer' }} onClick={() => navigate(`/factors/${record.id}`)}>
              <Badge status="processing" /> 检测 {testCount}
            </Tag>
          </Tooltip>
        );
      } else {
        items.push(
          <Tag key="t" color="default">
            <Badge status="default" /> 未检测
          </Tag>
        );
      }
    } else {
      items.push(<Tag key="t" color="default">-</Tag>);
    }

    // 如果有因子值但无检测 → 脏数据警告
    if (valueCount > 0 && testCount === 0) {
      items.push(
        <Tooltip key="warn" title="有因子值但未检测，建议清理或执行检测">
          <Tag color="warning" style={{ cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); handleDeleteValues(record); }}>
            <ClearOutlined /> 清理
          </Tag>
        </Tooltip>
      );
    }

    return <Space size={4} wrap>{items}</Space>;
  };

  const columns = [
    { title: '因子代码', dataIndex: 'factorCode', key: 'code', width: 220, render: v => <Tag color="blue">{v}</Tag> },
    { title: '因子名称', dataIndex: 'factorName', key: 'name', width: 150, ellipsis: true },
    { title: '分类', dataIndex: 'category', key: 'cat', width: 110, render: v => <Tag>{CATEGORY_LABELS[v] || v}</Tag> },
    { title: '计算状态', key: 'calcStatus', width: 300, render: renderCalcStatus },
    {
      title: '状态', dataIndex: 'status', key: 'st', width: 80,
      render: v => <Tag color={STATUS_COLORS[v]}>{STATUS_LABELS[v] || v}</Tag>
    },
    { title: '创建人', dataIndex: 'author', key: 'author', width: 80 },
    {
      title: '操作', key: 'action', width: 180, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Tooltip title="查看详情">
            <Button size="small" icon={<EyeOutlined />} onClick={() => navigate(`/factors/${record.id}`)} />
          </Tooltip>
          {record.factorType !== 'BUILTIN' && (
            <Tooltip title="编辑">
              <Button size="small" icon={<EditOutlined />} onClick={() => navigate(`/factors/${record.id}/edit`)} />
            </Tooltip>
          )}
          <Tooltip title={record.status === 'ACTIVE' ? '停用' : '激活'}>
            <Button size="small" type={record.status === 'ACTIVE' ? 'default' : 'primary'}
                    icon={<PlayCircleOutlined />} onClick={() => handleActivate(record)} />
          </Tooltip>
          {record.factorType !== 'BUILTIN' && (
            <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
              <Tooltip title="删除">
                <Button size="small" danger icon={<DeleteOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>因子列表</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/factors/new')}>
          新建因子
        </Button>
      </div>

      <Card style={{ marginBottom: 16, border: '1px solid #d9d9d9' }}>
        <Space wrap>
          <Input.Search
            placeholder="搜索因子代码/名称"
            allowClear
            style={{ width: 240 }}
            onSearch={v => { const p = { ...params, keyword: v, page: 0 }; setParams(p); fetchData(p); }}
          />
          <Select
            placeholder="因子分类" allowClear style={{ width: 140 }}
            onChange={v => { const p = { ...params, category: v, page: 0 }; setParams(p); fetchData(p); }}>
            {CATEGORY_OPTIONS.map(c => <Option key={c} value={c}>{CATEGORY_LABELS[c] || c}</Option>)}
          </Select>
          <Select
            placeholder="状态" allowClear style={{ width: 110 }}
            onChange={v => { const p = { ...params, status: v, page: 0 }; setParams(p); fetchData(p); }}>
            {Object.entries(STATUS_LABELS).map(([k, v]) => <Option key={k} value={k}>{v}</Option>)}
          </Select>
          <span style={{ color: '#999', marginLeft: 8 }}>|</span>
          <DatePicker
            placeholder="选择日期筛选缺失因子"
            value={missingDate}
            onChange={d => setMissingDate(d)}
            allowClear
            disabledDate={current => current && current > new Date()}
          />
          <Button
            icon={<SearchOutlined />}
            loading={missingLoading}
            onClick={handleSearchMissing}
          >
            筛选缺失
          </Button>
        </Space>

        {showMissing && (
          <div style={{ marginTop: 12 }}>
            {missingFactors.length > 0 ? (
              <>
                <Alert
                  type="warning"
                  showIcon
                  message={
                    <Space>
                      <span>{missingDate.format('YYYY-MM-DD')} 共 {missingFactors.length} 个因子缺失数据</span>
                      <Button
                        type="primary"
                        size="small"
                        icon={<CalculatorOutlined />}
                        loading={computing}
                        onClick={handleComputeMissing}
                      >
                        一键补算
                      </Button>
                      <Button size="small" onClick={() => setShowMissing(false)}>收起</Button>
                    </Space>
                  }
                  style={{ marginBottom: 8 }}
                />
                <div style={{ maxHeight: 200, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 4, padding: 4 }}>
                  <Space wrap size={4}>
                    {missingFactors.map(f => (
                      <Tag key={f.factorCode} color="orange" style={{ margin: 0 }}>
                        {f.factorCode} - {f.factorName}
                      </Tag>
                    ))}
                  </Space>
                </div>
              </>
            ) : (
              <Alert
                type="success"
                showIcon
                message={`${missingDate?.format('YYYY-MM-DD')} 所有激活因子均有数据`}
                closable
                onClose={() => setShowMissing(false)}
              />
            )}
          </div>
        )}
      </Card>

      <Card style={{ border: '1px solid #d9d9d9' }} extra={
        <Button size="small" icon={<DownloadOutlined />} onClick={() => exportCsv({ data: data?.records || [], columns, filename: '因子列表' })} disabled={!data?.records?.length}>导出CSV</Button>
      }>
        {selectedRowKeys.length > 0 && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message={
              <Space>
                <span>已选择 {selectedRowKeys.length} 个因子</span>
                <Button
                  type="primary"
                  size="small"
                  icon={<ThunderboltOutlined />}
                  onClick={handleOpenBatchCompute}
                >
                  批量计算
                </Button>
                <Button
                  size="small"
                  onClick={() => setSelectedRowKeys([])}
                >
                  取消选择
                </Button>
              </Space>
            }
          />
        )}
        <Table
          dataSource={data.records}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
          rowSelection={{
            selectedRowKeys,
            onChange: setSelectedRowKeys,
            selections: [
              Table.SELECTION_ALL,
              Table.SELECTION_INVERT,
              Table.SELECTION_NONE,
            ],
          }}
          pagination={{
            total: data.total,
            pageSize: params.size,
            current: params.page + 1,
            showSizeChanger: true,
            pageSizeOptions: ['15', '30', '50', '100'],
            showTotal: t => `共 ${t} 条`,
            onChange: (page, pageSize) => {
              const p = { ...params, page: page - 1, size: pageSize };
              setParams(p);
              fetchData(p);
            },
          }}
        />
      </Card>

      {/* 批量计算弹窗 */}
      <Modal
        title={
          <Space>
            <ThunderboltOutlined />
            <span>批量计算因子</span>
          </Space>
        }
        open={computeModalVisible}
        onOk={handleBatchCompute}
        onCancel={() => setComputeModalVisible(false)}
        confirmLoading={batchComputing}
        okText="开始计算"
        cancelText="取消"
        width={480}
      >
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 8, color: '#666' }}>
            已选因子（{selectedFactors.length} 个）：
          </div>
          <div style={{ maxHeight: 120, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 4, padding: 8 }}>
            <Space wrap size={4}>
              {selectedFactors.map(f => (
                <Tag key={f.factorCode} color="blue">{f.factorCode}</Tag>
              ))}
            </Space>
          </div>
        </div>
        <div>
          <div style={{ marginBottom: 8, color: '#666' }}>计算模式：</div>
          <Radio.Group value={computeMode} onChange={e => setComputeMode(e.target.value)} style={{ marginBottom: 12 }}>
            <Radio value="incremental">增量计算</Radio>
            <Radio value="force">强制计算</Radio>
          </Radio.Group>
          <div style={{ marginBottom: 8, color: '#666' }}>计算日期范围：</div>
          <DatePicker.RangePicker
            style={{ width: '100%' }}
            value={computeDateRange}
            onChange={dates => setComputeDateRange(dates)}
            allowClear
            disabledDate={current => current && current > dayjs()}
            presets={[
              { label: '最近1天', value: [dayjs().subtract(1, 'day'), dayjs().subtract(1, 'day')] },
              { label: '最近3天', value: [dayjs().subtract(3, 'day'), dayjs().subtract(1, 'day')] },
              { label: '最近7天', value: [dayjs().subtract(7, 'day'), dayjs().subtract(1, 'day')] },
              { label: '最近30天', value: [dayjs().subtract(30, 'day'), dayjs().subtract(1, 'day')] },
            ]}
          />
          <div style={{ marginTop: 8, fontSize: 12, color: '#999' }}>
            {computeMode === 'incremental'
              ? '增量模式：仅计算选中日期范围内尚未有因子值的日期。日期超过7天时最多同时计算8个因子。'
              : '强制模式：清空已有数据并重新计算选中日期范围内所有日期。耗时较长，请谨慎使用。'}
          </div>
        </div>
      </Modal>
    </div>
  );
}
