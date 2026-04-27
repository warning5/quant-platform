import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Table, Space, Divider } from 'antd';

const { Title, Paragraph, Text } = Typography;

export default function ManualDataUpdate() {
  return (
    <section id="data-update" style={{ paddingBottom: 32 }}>
      <Title level={2}>数据更新</Title>
      <Paragraph>
        数据更新模块负责从外部数据源获取最新的行情、财务等数据，并写入数据库。
        支持一键更新所有数据，也可按需更新特定类型的数据。
      </Paragraph>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="推荐更新时机"
        description="建议在每日收盘后1~2小时执行数据更新，此时当天的行情数据已准备好。"
      />

      {/* 快速操作卡片 */}
      <Title level={4}>快速操作</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} md={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
            <Text strong style={{ fontSize: 14, display: 'block', marginBottom: 8 }}>
              📊 日常行情更新
            </Text>
            <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
              选「全部市场」+「全部数据源」<br/>
              点击「开始更新」即可
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
            <Text strong style={{ fontSize: 14, display: 'block', marginBottom: 8 }}>
              🔄 断点续传
            </Text>
            <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
              勾选「断点续传」<br/>
              跳过已完成的股票继续
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
            <Text strong style={{ fontSize: 14, display: 'block', marginBottom: 8 }}>
              📈 仅更新日线
            </Text>
            <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
              勾选「仅更新日线」<br/>
              不更新股票基本信息
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" bodyStyle={{ padding: '12px 16px' }}>
            <Text strong style={{ fontSize: 14, display: 'block', marginBottom: 8 }}>
              💰 财务数据更新
            </Text>
            <Paragraph style={{ fontSize: 12, margin: 0, color: '#666' }}>
              设置年份范围<br/>
              默认跳过已有数据
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* Tab操作说明 */}
      <Title level={4}>各Tab操作说明</Title>
      
      {/* 股票日线 */}
      <Card 
        size="small" 
        title={
          <Space>
            <span>📊 股票日线</span>
            <Tag color="blue">最常用</Tag>
          </Space>
        }
        style={{ marginBottom: 12 }}
      >
        <Row gutter={[16, 8]}>
          <Col xs={24} md={12}>
            <Text strong>配置选项：</Text>
            <ul style={{ margin: '8px 0', fontSize: 13 }}>
              <li><Text code>市场</Text>：全部市场 / 沪市 / 深市 / 北交所</li>
              <li><Text code>数据源</Text>：全部 / Baostock(沪深) / 腾讯证券(北交所)</li>
              <li><Text code>股票池</Text>：全部 / 沪深300 / 上证50 / 中证500 / 中证1000 / 科创板50</li>
            </ul>
          </Col>
          <Col xs={24} md={12}>
            <Text strong>快捷选项：</Text>
            <ul style={{ margin: '8px 0', fontSize: 13 }}>
              <li><Text code>断点续传</Text>：网络中断后可继续，跳过已有数据的股票</li>
              <li><Text code>排除ST</Text>：过滤掉ST/*ST股票</li>
              <li><Text code>仅更新日线</Text>：只更新量价数据，不更新股票信息</li>
              <li><Text code>仅更新股票信息</Text>：只更新基本信息，不更新日线</li>
            </ul>
          </Col>
        </Row>
        <Alert 
          type="warning" 
          showIcon 
          icon={<></>}
          message="注意：北交所股票需选择「全部数据源」或「腾讯证券」，Baostock不覆盖北交所"
          style={{ marginTop: 8 }}
        />
      </Card>

      {/* 指数日线 */}
      <Card size="small" title={<Space><span>📈 指数日线</span><Tag color="cyan">后台自动</Tag></Space>} style={{ marginBottom: 12 }}>
        <Paragraph style={{ fontSize: 13, margin: 0 }}>
          <Text strong>配置：</Text>仅「断点续传」选项<br/>
          <Text strong>说明：</Text>更新沪深300、中证500、上证50等10个主要指数日线数据<br/>
          <Text strong>覆盖：</Text>Baostock数据源，通常每日自动更新
        </Paragraph>
      </Card>

      {/* 分红除权 */}
      <Card size="small" title={<Space><span>🎁 分红除权</span><Tag color="orange">按需更新</Tag></Space>} style={{ marginBottom: 12 }}>
        <Paragraph style={{ fontSize: 13, margin: 0 }}>
          <Text strong>配置：</Text>仅「跳过已有」选项（默认勾选）<br/>
          <Text strong>说明：</Text>补全历史分红除权数据，首次运行后通常无需再次更新<br/>
          <Text strong>数据源：</Text>巨潮 → 同花顺 → 东方财富（三级自动回退）
        </Paragraph>
      </Card>

      {/* 财务数据 */}
      <Card size="small" title={<Space><span>💼 财务数据</span><Tag color="purple">财报后更新</Tag></Space>} style={{ marginBottom: 12 }}>
        <Row gutter={[16, 8]}>
          <Col xs={24} md={12}>
            <Text strong>配置选项：</Text>
            <ul style={{ margin: '8px 0', fontSize: 13 }}>
              <li><Text code>年份范围</Text>：默认采集近3年数据（如需更早数据请扩大范围）</li>
              <li><Text code>强制重新采集</Text>：覆盖已存在的财务数据（谨慎使用）</li>
            </ul>
          </Col>
          <Col xs={24} md={12}>
            <Text strong>快捷说明：</Text>
            <ul style={{ margin: '8px 0', fontSize: 13 }}>
              <li><Text code>默认行为</Text>：跳过已存在的财务报告</li>
              <li><Text code>何时用</Text>：年报/季报发布后补全数据</li>
              <li><Text code>数据源</Text>：同花顺iFind → 东方财富（自动回退）</li>
            </ul>
          </Col>
        </Row>
      </Card>

      <Divider />

      {/* 数据源说明 */}
      <Title level={4}>数据源说明</Title>
      <Table 
        size="small"
        pagination={false}
        columns={[
          { title: '数据类型', dataIndex: 'type', key: 'type', width: 120 },
          { title: '主数据源', dataIndex: 'primary', key: 'primary', width: 150 },
          { title: '备用数据源', dataIndex: 'backup', key: 'backup' },
          { title: '备注', dataIndex: 'note', key: 'note' },
        ]}
        dataSource={[
          { type: '沪深日线', primary: 'Baostock', backup: 'akshare', note: '覆盖全面，更新及时' },
          { type: '北交所日线', primary: '腾讯证券', backup: '-', note: '仅腾讯覆盖北交所' },
          { type: '指数日线', primary: 'Baostock', backup: '-', note: '10个主要指数' },
          { type: '分红除权', primary: '巨潮', backup: '同花顺→东财', note: '三级回退机制' },
          { type: '财务数据', primary: '同花顺', backup: '东方财富', note: '年报后补全' },
        ]}
        style={{ marginBottom: 16 }}
      />

      {/* 状态说明 */}
      <Title level={4}>任务状态说明</Title>
      <Row gutter={[12, 12]}>
        <Col xs={12} md={6}>
          <Tag color="default">空闲</Tag> 等待开始
        </Col>
        <Col xs={12} md={6}>
          <Tag color="processing">运行中</Tag> 正在采集
        </Col>
        <Col xs={12} md={6}>
          <Tag color="success">已完成</Tag> 成功结束
        </Col>
        <Col xs={12} md={6}>
          <Tag color="error">失败</Tag> 出错中断
        </Col>
        <Col xs={12} md={6}>
          <Tag color="warning">已取消</Tag> 手动停止
        </Col>
      </Row>

      <Divider />

      {/* 常见问题 */}
      <Title level={4}>常见问题</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" title="Q: 网络中断后如何继续？">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              A: 勾选「断点续传」选项后重新开始，系统会自动跳过已完成的股票。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" title="Q: 北交所数据没更新？">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              A: 检查数据源是否选择「全部」或「腾讯证券」，Baostock不覆盖北交所。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" title="Q: 财务数据需要更新吗？">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              A: 平时不需要，年报/季报发布季（4/8/10月）运行一次即可，默认跳过已有数据。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" title="Q: 如何只更新特定指数？">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              A: 当前版本暂不支持指定指数，指数日线会更新全部10个主要指数。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
