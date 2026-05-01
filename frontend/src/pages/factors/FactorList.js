import React, { useEffect, useState, useCallback } from 'react';
import { Table, Tag, Button, Space, Input, Select, Card, Typography, Popconfirm, message, Tooltip, Badge } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, PlayCircleOutlined, ClearOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { factorApi } from '../../api';
import { CATEGORY_OPTIONS, CATEGORY_LABELS } from './constants';

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
        </Space>
      </Card>

      <Card style={{ border: '1px solid #d9d9d9' }}>
        <Table
          dataSource={data.records}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
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
    </div>
  );
}
