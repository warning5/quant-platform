import React, { useState, useCallback, useEffect } from 'react';
import {
  Card, Row, Col, Table, Tag, Input, Button, Slider, Checkbox, Space,
  Typography, Spin, Empty, Tooltip, message, Modal, Alert,
} from 'antd';
import { FilterOutlined, ReloadOutlined, StockOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { factorApi } from '../../api';

const { Title, Text, Paragraph } = Typography;

// ── 辅助：格式化因子值 ─────────────────────────────────────────────────────────
const fmtVal = (v, prec = 2) => {
  if (v == null) return '-';
  const n = Number(v);
  if (Number.isNaN(n)) return v;
  return n.toFixed(prec);
};

// ── 辅助：笔方向 Tag ─────────────────────────────────────────────────────────
const penDirTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  return n > 0
    ? <Tag color="red">▲ 上升</Tag>
    : <Tag color="green">▼ 下降</Tag>;
};

// ── 辅助：走势类型 Tag ────────────────────────────────────────────────────────
const trendTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  if (n === 1)  return <Tag color="red">  上涨</Tag>;
  if (n === 0)  return <Tag color="blue"> 盘整</Tag>;
  return <Tag color="green">下跌</Tag>;
};

// ── 辅助：买卖点 Tag ─────────────────────────────────────────────────────────
const buySellTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  if (n > 0) return <Tag color="volcano">{n}买</Tag>;
  if (n < 0) return <Tag color="cyan">  {Math.abs(n)}卖</Tag>;
  return '-';
};

// ── 辅助：根据因子代码返回 Tag 渲染函数 ─────────────────────────────────────────
const getTagRenderer = (code) => {
  const c = code.toUpperCase();
  if (c === 'CHAN_PEN_DIR')   return penDirTag;
  if (c === 'CHAN_TREND')     return trendTag;
  if (c === 'CHAN_BUY_SELL')  return buySellTag;
  return (v) => v == null ? '-' : <Text>{fmtVal(v, 3)}</Text>;
};

// ── 主页面 ─────────────────────────────────────────────────────────────────────
export default function ChanScreen() {
  // ── 动态元数据 ───────────────────────────────────────────────────────────
  const [meta, setMeta]                 = useState(null);  // { factors, columns }
  const [metaLoading, setMetaLoading]   = useState(true);

  // ── 筛选条件（keyed by factorCode）───────────────────────────────────────
  const [checkboxFilters, setCheckboxFilters] = useState({});  // { CHAN_PEN_DIR: [1], CHAN_TREND: [1], ... }
  const [rangeFilters, setRangeFilters]       = useState({});  // { CHAN_HUB_POS: [0,1], CHAN_PEN_COUNT: [1,50], ... }
  const [keyword, setKeyword]                 = useState('');
  const [page, setPage]                       = useState(1);
  const [pageSize, setPageSize]               = useState(20);

  const [loading, setLoading]   = useState(false);
  const [data, setData]         = useState(null);
  const [error, setError]       = useState(null);
  const [helpVisible, setHelpVisible] = useState(false);

  // ── 加载元数据 ───────────────────────────────────────────────────────────
  useEffect(() => {
    factorApi.chanScreenMeta()
      .then(res => {
        setMeta(res);
        // 初始化默认筛选范围
        const defaultRanges = {};
        const defaultCheckbox = {};
        (res.factors || []).forEach(f => {
          if (f.controlType === 'slider') {
            defaultRanges[f.code] = [f.min ?? 0, f.max ?? 100];
          } else if (f.controlType === 'checkbox') {
            defaultCheckbox[f.code] = [];
          }
        });
        setRangeFilters(defaultRanges);
        setCheckboxFilters(defaultCheckbox);
      })
      .catch(e => message.error('加载缠论因子元数据失败: ' + (e.message || e)))
      .finally(() => setMetaLoading(false));
  }, []);

  // ── 构建查询参数 ───────────────────────────────────────────────────────────
  const buildParams = useCallback((newPage, newPageSize) => {
    const params = {};

    // 枚举类因子筛选
    Object.entries(checkboxFilters).forEach(([code, vals]) => {
      if (vals && vals.length > 0) {
        // 兼容后端5个硬编码参数名
        const p = code.toUpperCase();
        if (p === 'CHAN_PEN_DIR')   params.penDir   = vals.join(',');
        if (p === 'CHAN_TREND')     params.trend    = vals.join(',');
        if (p === 'CHAN_BUY_SELL')  params.buySell  = vals.join(',');
      }
    });

    // 连续值因子筛选
    Object.entries(rangeFilters).forEach(([code, range]) => {
      if (!range) return;
      const p = code.toUpperCase();
      if (p === 'CHAN_HUB_POS') {
        if (range[0] > 0)  params.hubPosMin   = range[0];
        if (range[1] < 1)  params.hubPosMax   = range[1];
      }
      if (p === 'CHAN_PEN_COUNT') {
        if (range[0] > 1)  params.penCountMin = range[0];
        if (range[1] < 100) params.penCountMax = range[1];
      }
    });

    if (keyword.trim()) params.keyword = keyword.trim();
    params.page = newPage - 1;
    params.size = newPageSize;
    return params;
  }, [checkboxFilters, rangeFilters, keyword]);

  // ── 发起筛选请求 ───────────────────────────────────────────────────────────
  const doSearch = useCallback((newPage = 1, newPageSize = pageSize) => {
    setLoading(true);
    setError(null);
    setPage(newPage);
    setPageSize(newPageSize);

    const params = buildParams(newPage, newPageSize);

    factorApi.chanScreen(params)
      .then(res => setData(res))
      .catch(e => setError(e.message || '筛选失败'))
      .finally(() => setLoading(false));
  }, [buildParams, pageSize]);

  // ── 动态构建表格列 ────────────────────────────────────────────────────────
  const buildColumns = useCallback(() => {
    if (!meta || !meta.columns) return [];

    const baseCols = [
      { title: '代码', dataIndex: 'ts_code', key: 'ts_code', width: 100,
        render: v => <Text strong copyable={{ text: v }}>{v}</Text> },
      { title: '名称', dataIndex: 'name', key: 'name', width: 90, ellipsis: true },
      { title: '数据日期', dataIndex: 'calc_date', key: 'date', width: 110 },
    ];

    const factorCols = (meta.columns || []).map(col => ({
      title: col.title,
      dataIndex: col.dataIndex,
      key: col.key,
      width: 90,
      render: getTagRenderer(col.key)(col.dataIndex === col.key ? undefined : undefined),
      // 实际渲染从 row 数据取
      render: (val, row) => {
        const tagFn = getTagRenderer(col.key);
        // 因子值存在 row[dataIndex] 中
        return tagFn(row[col.dataIndex]);
      },
    }));

    return [...baseCols, ...factorCols];
  }, [meta]);

  // ── 动态渲染筛选控件 ──────────────────────────────────────────────────────
  const renderFilterControls = () => {
    if (!meta || !meta.factors) return null;

    return meta.factors.map(factor => {
      if (factor.controlType === 'checkbox') {
        return (
          <Col span={12} key={factor.code}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
              {factor.name}
            </Text>
            <Checkbox.Group
              options={factor.options || []}
              value={checkboxFilters[factor.code] || []}
              onChange={(vals) => setCheckboxFilters(prev => ({ ...prev, [factor.code]: vals }))}
              style={{ gap: 16, flexWrap: 'wrap' }}
            />
            {!(checkboxFilters[factor.code] || []).length && (
              <Text type="secondary" style={{ fontSize: 12 }}>（不限）</Text>
            )}
          </Col>
        );
      }

      if (factor.controlType === 'slider') {
        const range = rangeFilters[factor.code] || [factor.min ?? 0, factor.max ?? 100];
        const p = factor.code.toUpperCase();
        const isPercent = p === 'CHAN_HUB_POS';
        const isPenCount = p === 'CHAN_PEN_COUNT';

        return (
          <Col span={12} key={factor.code}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
              {factor.name}：{isPercent
                ? `${range[0].toFixed(2)} ~ ${range[1].toFixed(2)}`
                : `${range[0]} ~ ${range[1]}`
              }
            </Text>
            <Slider
              range
              min={factor.min ?? 0}
              max={isPercent ? 1 : (isPenCount ? 100 : 100)}
              step={isPercent ? 0.01 : 1}
              value={range}
              onChange={(vals) => setRangeFilters(prev => ({ ...prev, [factor.code]: vals }))}
              tooltip={{ formatter: v => isPercent ? v.toFixed(2) : v }}
              style={{ maxWidth: 400 }}
            />
          </Col>
        );
      }

      return null;
    });
  };

  const total = data?.total || 0;
  const list  = data?.list  || [];

  return (
    <div>
      {/* ── 标题区 ─────────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>
          <StockOutlined style={{ marginRight: 8 }} />
          缠论结构筛选
        </Title>
        <Tooltip title="查看使用说明">
          <QuestionCircleOutlined
            style={{ fontSize: 16, color: '#1677ff', cursor: 'pointer', flexShrink: 0 }}
            onClick={() => setHelpVisible(true)}
          />
        </Tooltip>
      </div>

      {/* ── 加载元数据中 ───────────────────────────────────────────────── */}
      {metaLoading ? (
        <Card size="small" style={{ marginBottom: 16 }}>
          <Spin tip="加载因子定义..." />
        </Card>
      ) : (
        <>
          {/* ═════════ 筛选面板 ═════════ */}
          <Card size="small" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 12]}>
              {/* 动态筛选控件 */}
              {renderFilterControls()}

              {/* 关键词搜索 + 按钮 */}
              <Col span={12}>
                <Space>
                  <Input
                    placeholder="搜索代码或名称关键词"
                    value={keyword}
                    onChange={e => setKeyword(e.target.value)}
                    onPressEnter={() => doSearch(1)}
                    style={{ width: 220 }}
                    allowClear
                  />
                  <Button
                    type="primary"
                    icon={<FilterOutlined />}
                    onClick={() => doSearch(1)}
                    loading={loading}
                  >
                    筛选
                  </Button>
                  <Button
                    icon={<ReloadOutlined />}
                    onClick={() => {
                      // 重置所有筛选条件
                      const defaults = {};
                      const cbDefaults = {};
                      (meta?.factors || []).forEach(f => {
                        if (f.controlType === 'slider') {
                          defaults[f.code] = [f.min ?? 0, f.max ?? (f.code.toUpperCase() === 'CHAN_HUB_POS' ? 1 : 100)];
                        } else {
                          cbDefaults[f.code] = [];
                        }
                      });
                      setCheckboxFilters(cbDefaults);
                      setRangeFilters(defaults);
                      setKeyword('');
                      setData(null);
                    }}
                  >
                    重置
                  </Button>
                </Space>
              </Col>
            </Row>
          </Card>

          {/* ═════════ 结果面板 ═════════ */}
          <Card
            title={`筛选结果（共 ${total} 只）`}
            size="small"
          >
            <Spin spinning={loading}>
              {!data && !error ? (
                <Empty description="请设置筛选条件后点击「筛选」" />
              ) : error ? (
                <Text type="danger">{error}</Text>
              ) : (
                <Table
                  dataSource={list}
                  columns={buildColumns()}
                  rowKey="ts_code"
                  size="small"
                  pagination={{
                    current:  page,
                    pageSize: pageSize,
                    total:    total,
                    showSizeChanger: true,
                    pageSizeOptions: ['10', '20', '50'],
                    onChange: (p, ps) => doSearch(p, ps),
                  }}
                  scroll={{ x: 700 }}
                />
              )}
            </Spin>
          </Card>
        </>
      )}

      {/* ══════ 使用说明弹窗 ══════ */}
      <Modal
        title="缠论结构筛选 · 使用说明"
        open={helpVisible}
        onCancel={() => setHelpVisible(false)}
        footer={null}
        width={900}
      >
        <div style={{ fontSize: 13, lineHeight: 1.8 }}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="这是做什么的"
            description="缠论结构筛选是基于缠论理论，从全市场股票中筛选出处于特定「结构状态」的标的。
            比如：当前有哪些股票刚出现「1买」买点？哪些处于「上涨走势+中枢上半区」？
            省去逐个翻图分析的时间，直接给出符合条件的股票清单。"
          />

          {meta && meta.factors && meta.factors.length > 0 && (
            <>
              <Title level={5}>筛选维度说明</Title>
              <ul style={{ paddingLeft: 20 }}>
                {meta.factors.map(f => (
                  <li key={f.code}>
                    <Text strong>{f.name}</Text>
                    {f.description && <Text type="secondary">：{f.description}</Text>}
                    {f.options && f.options.length > 0 && (
                      <Text type="secondary">（{f.options.map(o => o.label).join(' / ')}）</Text>
                    )}
                  </li>
                ))}
              </ul>
            </>
          )}

          <Title level={5}>使用流程</Title>
          <ul style={{ paddingLeft: 20 }}>
            <li>确保缠论因子已计算（因子管理 → 因子监控，确认缠论因子有数据）</li>
            <li>设置筛选条件（各条件间为「且」关系，建议先宽松再逐步收紧）</li>
            <li>点击「筛选」查看结果，可用关键词进一步过滤</li>
            <li>结合买卖点信号和中枢位置辅助买卖决策</li>
          </ul>

          <Title level={5}>与因子列表的关系</Title>
          <ul style={{ paddingLeft: 20 }}>
            <li>因子列表 → 缠论分类：查看因子定义和计算公式</li>
            <li>因子监控：触发缠论因子值计算（先计算，筛选才有数据）</li>
            <li>缠论结构筛选：对计算好的因子值设条件，筛选符合条件的股票</li>
          </ul>

          <Title level={5}>与「策略管理 → 选股条件」的关系</Title>
          <Paragraph style={{ fontSize: 13 }}>
            两者本质相同：基于因子值筛选股票，数据来源一致。
            缠论筛选是缠论维度的快捷入口，不可扩展；
            策略管理支持任意因子组合，可保存模板和回测。
            想把缠论条件固化下来用 → 去策略管理中配置。
          </Paragraph>

          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 8 }}
            message="增减缠论因子的影响"
            description="缠论筛选页面已改为动态化，新增/删除/重命名缠论因子后自动生效，无需修改代码。
            但新增因子需要在因子计算引擎中实现计算逻辑，且在「因子管理」中激活后才会出现在筛选中。
            灵活配置请使用「策略管理 → 选股条件」。"
          />
        </div>
      </Modal>
    </div>
  );
}
