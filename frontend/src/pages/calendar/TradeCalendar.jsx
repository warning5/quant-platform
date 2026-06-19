import React, { useState, useEffect, useCallback } from 'react';
import { Card, Calendar, Tag, Button, Select, Modal, Form, Input, Switch, message, Spin, Space, Divider, Tooltip } from 'antd';
import { LeftOutlined, RightOutlined, CalendarOutlined, EditOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { calendarApi } from '../../api';

/**
 * 交易日历管理页面 v5
 *
 * 数据模型：trade_calendar 表只存例外日（非交易日）
 * - 周末 → 前端 dayjs.day() 自动判断，不入库
 * - 普通工作日 → 默认交易日，不入库
 * - 节假日(weekday) → 入库 is_trading=0 + reason
 * - 手动标记 → 入库 source=MANUAL
 * - 调休/补班 → 不入库，周末统一按非交易日处理
 */

const TradeCalendar = () => {
  const [currentDate, setCurrentDate] = useState(dayjs());
  const [loading, setLoading] = useState(false);
  const [calendarData, setCalendarData] = useState([]);
  const [markModalVisible, setMarkModalVisible] = useState(false);
  const [selectedDate, setSelectedDate] = useState(null);
  const [form] = Form.useForm();

  const displayYear = currentDate.year();

  // 加载某年的例外日数据
  const loadYearData = useCallback(async (y) => {
    setLoading(true);
    try {
      const data = await calendarApi.getByYear(y);
      setCalendarData(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('加载日历数据失败:', err);
      setCalendarData([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadYearData(displayYear);
  }, [displayYear, loadYearData]);

  // 构建日期映射
  const dateMap = {};
  calendarData.forEach(item => {
    if (item && item.tradeDate) {
      dateMap[item.tradeDate] = item;
    }
  });

  /**
   * 判断日期类型
   * - 'weekend': 周末，灰底，不可点
   * - 'holiday': 节假日，红底+原因文字，可点修改
   * - 'trading': 普通交易日，正常显示，可点标记
   */
  const getDateInfo = (date) => {
    const dateStr = date.format('YYYY-MM-DD');
    const dow = date.day();
    const isWeekend = dow === 0 || dow === 6;
    const dbRecord = dateMap[dateStr];

    if (dbRecord && !dbRecord.isTrading) {
      return { type: 'holiday', label: dbRecord.reason || '休市', clickable: true, dbRecord };
    }

    if (isWeekend) {
      return { type: 'weekend', label: dow === 0 ? '周日' : '周六', clickable: false, dbRecord: null };
    }

    return { type: 'trading', label: '', clickable: true, dbRecord };
  };

  // ── 导航头部 ──
  const headerRender = ({ value, onChange }) => (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
        <Space size={2}>
          <Button size="small" onClick={(e) => { e.stopPropagation(); onChange(value.subtract(1, 'year')); }} title="上一年">
            <LeftOutlined /> <span style={{ fontSize: 11 }}>年</span>
          </Button>
          <Button size="small" onClick={(e) => { e.stopPropagation(); onChange(value.subtract(1, 'month')); }} title="上一月">
            <LeftOutlined />
          </Button>
          <Select
            size="small"
            value={value.year()}
            onChange={(y) => onChange(value.year(y).startOf('month'))}
            style={{ width: 80 }}
            options={[2024,2025,2026,2027,2028,2029,2030].map(y => ({ label: `${y}年`, value: y }))}
          />
          <Select
            size="small"
            value={value.month() + 1}
            onChange={(m) => onChange(value.month(m - 1).startOf('month'))}
            style={{ width: 65 }}
            options={[1,2,3,4,5,6,7,8,9,10,11,12].map(m => ({ label: `${m}月`, value: m }))}
          />
          <Button size="small" onClick={(e) => { e.stopPropagation(); onChange(value.add(1, 'month')); }} title="下一月">
            <RightOutlined />
          </Button>
          <Button size="small" onClick={(e) => { e.stopPropagation(); onChange(value.add(1, 'year')); }} title="下一年">
            <RightOutlined /> <span style={{ fontSize: 11 }}>年</span>
          </Button>
        </Space>
        <Divider type="vertical" />
        <Button size="small" onClick={(e) => { e.stopPropagation(); onChange(dayjs()); }}>今天</Button>
      </div>
    </div>
  );

  // ── 单元格渲染 ──
  const cellRender = (date) => {
    const info = getDateInfo(date);
    const { type, label, clickable, dbRecord } = info;
    const dow = date.day();

    // ── 周末：灰底 + 文字 ──
    if (type === 'weekend') {
      return (
        <div style={{
          background: '#fafafa',
          borderRadius: 6,
          minHeight: 44,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'default',
        }}>
          <span style={{ fontSize: 12, color: '#bfbfbf' }}>{label}</span>
        </div>
      );
    }

    // ── 节假日：整格红底 + 居中原因文字 ──
    if (type === 'holiday') {
      return (
        <Tooltip title={`${label}${dbRecord?.source === 'MANUAL' ? ' (手动)' : ''}`}>
          <div
            style={{
              background: '#fff1f0',
              borderRadius: 6,
              minHeight: 44,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
            }}
            onClick={(e) => { e.stopPropagation(); openMarkModal(date); }}
          >
            <span style={{
              fontSize: 12,
              color: '#cf1322',
              fontWeight: 600,
              whiteSpace: 'nowrap',
            }}>
              {label}
            </span>
          </div>
        </Tooltip>
      );
    }

    // ── 普通交易日：无背景，可点击标记 ──
    return (
      <div
        style={{
          borderRadius: 6,
          minHeight: 44,
          cursor: 'pointer',
        }}
        onClick={(e) => { e.stopPropagation(); openMarkModal(date); }}
      />
    );
  };

  // 打开标记弹窗
  const openMarkModal = (date) => {
    const dateStr = date.format('YYYY-MM-DD');
    setSelectedDate(dateStr);

    const info = getDateInfo(date);
    if (info.dbRecord) {
      form.setFieldsValue({
        isTrading: info.dbRecord.isTrading,
        reason: info.dbRecord.reason || '',
      });
    } else {
      // 普通交易日默认显示为“交易日”
      form.setFieldsValue({ isTrading: true, reason: '' });
    }
    setMarkModalVisible(true);
  };

  // 提交标记
  const handleMark = async (values) => {
    try {
      await calendarApi.markDay(selectedDate, values.isTrading, values.reason || '');
      message.success('标记成功');
      setMarkModalVisible(false);
      loadYearData(displayYear);
    } catch (err) {
      message.error('标记失败');
    }
  };

  return (
    <Card
      title={<span><CalendarOutlined /> 交易日历</span>}
      extra={
        <Space size="small">
          <Tag color="#cf1322">节假日</Tag>
          <Tag style={{ borderColor: '#d9d9d9', color: '#999', background: '#fafafa' }}>周末</Tag>
          <Tag>交易日</Tag>
          <Tag color="blue"><EditOutlined /> 点击可手动标记</Tag>
        </Space>
      }
    >
      <Spin spinning={loading}>
        <Calendar
          value={currentDate}
          onPanelChange={(date) => setCurrentDate(date)}
          headerRender={headerRender}
          cellRender={cellRender}
        />
      </Spin>

      <Modal
        title={`手动标记: ${selectedDate}`}
        open={markModalVisible}
        onCancel={() => setMarkModalVisible(false)}
        onOk={() => form.submit()}
        okText="确认"
        cancelText="取消"
        destroyOnHidden
      >
        <Form form={form} onFinish={handleMark} layout="vertical">
          <Form.Item name="isTrading" label="交易日" valuePropName="checked">
            <Switch checkedChildren="是" unCheckedChildren="否" />
          </Form.Item>
          <Form.Item name="reason" label="原因说明">
            <Input placeholder="如：临时休市等（选填）" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

export default TradeCalendar;
