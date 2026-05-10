import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert } from 'antd';
import { FundOutlined, CloudSyncOutlined, SearchOutlined, PlusOutlined, CodeOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

export function ManualFactors() {
  return (
    <section id="factors" style={{ paddingBottom: 16 }}>
      <Title level={2}><FundOutlined /> 因子基础</Title>
      <Paragraph>
        因子是量化投资的核心概念，用于描述股票的某种属性或特征。平台支持多种类型的因子，包括内置因子和自定义因子。
      </Paragraph>

      <Title level={4}>因子分类</Title>
      <Row gutter={[8, 8]} style={{ marginBottom: 16 }}>
        <Col><Tag color="blue">动量 MOMENTUM</Tag></Col>
        <Col><Tag color="gold">价值 VALUE</Tag></Col>
        <Col><Tag color="green">质量 QUALITY</Tag></Col>
        <Col><Tag color="orange">波动率 VOLATILITY</Tag></Col>
        <Col><Tag color="purple">技术 TECHNICAL</Tag></Col>
        <Col><Tag color="cyan">基本面 FUNDAMENTAL</Tag></Col>
        <Col><Tag color="magenta">情绪 SENTIMENT</Tag></Col>
        <Col><Tag color="red">缠论 CHANTHEORY</Tag></Col>
        <Col><Tag>自定义 CUSTOM</Tag></Col>
      </Row>

      <Title level={4}>因子类型</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" title={<><Tag color="blue">BUILTIN</Tag> 内置因子</>}>
            <Paragraph style={{ fontSize: 13 }}>
              平台预置的因子，无法编辑和删除。包括8个常用因子：MOM20、MOM60、VOL20、TURN20、SIZE、RSI5、BOLL_POS、VPCORR20。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" title={<><Tag color="purple">SCRIPTED</Tag> 脚本因子</>}>
            <Paragraph style={{ fontSize: 13 }}>
              通过Groovy脚本语言自定义计算的因子。支持实时语法验证、模板支持、沙箱执行。可以灵活定义各种复杂因子。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" title={<><Tag color="gold">COMPOSITE</Tag> 合成因子</>}>
            <Paragraph style={{ fontSize: 13 }}>
              由多个基础因子组合而成的因子。可以通过线性组合、加权平均等方式构建更复杂的因子逻辑。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>常用内置因子详解</Title>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small">
            <Title level={5}>🚀 动量因子（MOM）</Title>
            <Paragraph>
              <Text strong>含义</Text>：衡量股票价格的历史涨跌趋势，反映市场动量效应。<br/>
              <Text strong>计算公式</Text>：MOM_N = (Close_t - Close_t-N) / Close_t-N<br/>
              <Text strong>参数</Text>：N 为计算周期（如 20 日）<br/>
              <Text strong>使用场景</Text>：动量因子通常用于捕捉趋势延续，值越大表示近期表现越好。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small">
            <Title level={5}>📊 波动率因子（VOL）</Title>
            <Paragraph>
              <Text strong>含义</Text>：衡量股票价格的波动程度，反映风险水平。<br/>
              <Text strong>计算公式</Text>：VOL_N = StdDev(Return_1, Return_2, ..., Return_N)<br/>
              <Text strong>参数</Text>：N 为计算周期（如 20 日）<br/>
              <Text strong>使用场景</Text>：波动率通常与风险正相关，风险厌恶投资者倾向于低波动股票。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small">
            <Title level={5}>💰 市值因子（SIZE）</Title>
            <Paragraph>
              <Text strong>含义</Text>：衡量股票的市场规模，反映公司规模大小。<br/>
              <Text strong>计算公式</Text>：SIZE = log(MarketCap)<br/>
              <Text strong>使用场景</Text>：小市值股票通常具有更高的增长潜力和风险。
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}

export function ManualFactorMonitor() {
  return (
    <section id="factor-monitor" style={{ paddingBottom: 16 }}>
      <Title level={2}><CloudSyncOutlined /> 因子计算监控</Title>
      <Paragraph>
        因子计算监控页面用于批量计算因子值。用户可以选择要计算的因子、日期范围，系统自动批量计算并存储到数据库。
      </Paragraph>

      <Title level={4}>计算模式</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="全量模式">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              从指定开始日期计算到结束日期。首次计算时使用全量模式。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="增量模式">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              只计算最新日期的因子值，适合每日收盘后更新。计算速度快，节省资源。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card type="inner" size="small" title="单因子模式">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              只计算选中的单个因子，用于调试或补充计算某个特定因子。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>技术因子</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="日频技术因子"
        description="技术因子在每个交易日收盘后计算，使用当日及之前的历史数据。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="动量类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              MOM_N（N日收益率）、ROC_N（N日变化率）、DMA（不同周期均线差）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="波动类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              VOL_N（N日波动率）、ATR_N（N日真实波幅）、STD_N（N日标准差）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="量价类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              TURN_N（N日换手率）、VROC_N（N日成交量变化率）、VAMP（成交量均线）
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="趋势类">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              MA_N（N日均线）、BOLL_POS（布林带位置）、RSI_N（N日RSI）
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}

export function ManualFactorDetail() {
  return (
    <section id="factor-detail" style={{ paddingBottom: 16 }}>
      <Title level={2}><SearchOutlined /> 因子值查看</Title>
      <Paragraph>
        因子值查看页面用于查看单只股票或全市场的因子值分布。支持时间序列分析和截面分布分析。
      </Paragraph>

      <Title level={4}>分析模式</Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="⏰ 时间序列分析">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              查看单只股票在不同时间点的因子值变化，以及因子排名（百分位）。可以了解因子的历史表现和稳定性。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card type="inner" size="small" title="📈 截面分布分析">
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              查看某一天所有股票的因子值分布，了解因子值的整体范围和分布情况。可以用于因子值标准化和异常值检测。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      <Title level={4}>因子值说明</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="归一化处理"
        description="因子值经过横截面Z-Score标准化处理，便于不同因子之间的比较和分析。"
      />
    </section>
  );
}

export function ManualFactorCreate() {
  return (
    <section id="factor-create" style={{ paddingBottom: 16 }}>
      <Title level={2}><PlusOutlined /> 新建因子</Title>
      <Paragraph>
        平台支持通过 Groovy 脚本自定义因子。点击「因子管理」→「新建因子」即可进入因子创建页面。
        新建因子的完整流程如下：
      </Paragraph>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="新建因子的 4 步完整流程"
        description="新增一个完整可用的因子，需要前后端协同：后端实现计算逻辑 → 注册因子 → 前端创建记录 → 触发计算。"
      />

      {/* 步骤卡片 */}
      {[
        {
          n: 1,
          icon: <CodeOutlined />,
          title: '实现计算逻辑（后端 Java）',
          desc: '在因子计算引擎中添加计算方法。例如缠论因子，需在 ChanTheoryCalculator 中新增方法实现算法。技术因子通过 Groovy 脚本直接在页面定义，无需修改后端代码。',
          color: '#1677ff',
        },
        {
          n: 2,
          icon: <PlusOutlined />,
          title: '注册因子（后端 Java，内置因子）',
          desc: '如果是用 Java 实现的内置因子，需要在 FactorComputeEngine 的 registerBuiltin() 中注册（定义代码、名称、分类、描述）。脚本因子则跳过此步。',
          color: '#52c41a',
        },
        {
          n: 3,
          icon: <PlusOutlined />,
          title: '创建因子记录（因子管理页面）',
          desc: '在「因子管理 → 新建因子」页面填写基本信息：因子代码（唯一标识）、因子名称、因子分类（选「缠论 CHANTHEORY」即可纳入缠论筛选）、描述。保存后在因子定义表中创建记录，并设置为「已激活」状态。',
          color: '#faad14',
        },
        {
          n: 4,
          icon: <CloudSyncOutlined />,
          title: '触发计算（因子监控）',
          desc: '在「因子管理 → 因子监控」中，勾选新因子，点击计算按钮。计算完成后，因子值存储在 factor_value 表中，可在筛选、选股、回测等模块中使用。',
          color: '#f5222d',
        },
      ].map(item => (
        <Card
          key={item.n}
          size="small"
          style={{ marginBottom: 12, borderLeft: `3px solid ${item.color}` }}
        >
          <Row gutter={16} align="middle">
            <Col span={1}>
              <span style={{
                background: item.color, color: '#fff',
                borderRadius: '50%', width: 26, height: 26,
                display: 'inline-flex', alignItems: 'center',
                justifyContent: 'center', fontWeight: 'bold',
              }}>
                {item.n}
              </span>
            </Col>
            <Col span={23}>
              <Text strong style={{ fontSize: 14 }}>{item.title}</Text>
              <div style={{ marginTop: 4, color: '#666', fontSize: 13 }}>{item.desc}</div>
            </Col>
          </Row>
        </Card>
      ))}

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 8 }}
        message="关于缠论因子"
        description={
          <div>
            <Paragraph style={{ marginBottom: 8 }}>
              缠论因子属于特殊类别（CHANTHEORY），与普通因子有以下区别：
            </Paragraph>
            <ul style={{ paddingLeft: 20, marginBottom: 0 }}>
              <li><Text strong>计算逻辑</Text>：必须用 Java 代码实现（ChanTheoryCalculator），无法用 Groovy 脚本</li>
              <li><Text strong>自动感知</Text>：新增后会被「缠论结构筛选」页面自动感知——筛选维度和结果列动态出现，无需修改前端代码</li>
              <li><Text strong>筛选控件</Text>：如果新因子的值是枚举型（如 1/0/-1），需要在因子记录的 parameters_json 字段中配置 controlType 和 options</li>
            </ul>
          </div>
        }
      />

      <Alert
        type="success"
        showIcon
        style={{ marginTop: 8 }}
        message="建议：先用策略管理测试"
        description={
          <span>
            如果只是想用某个因子做选股或回测，可以<Text strong>直接</Text>在「策略管理 → 选股条件」中添加因子条件，
            无需新增因子定义。只有当需要「缠论筛选」这样的快捷入口，或需要共享给多策略使用时，才需要新建因子。
          </span>
        }
      />

      <Title level={4}>Groovy 脚本因子示例</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 8 }}
        message="Groovy 脚本因子不需要修改后端代码"
        description="脚本因子通过 Groovy 语言在页面直接编写，无需 Java 开发经验，适合技术因子的快速迭代。"
      />
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card size="small" title="示例：5日均线" style={{ borderLeft: '4px solid #1677ff' }}>
            <Paragraph style={{ fontSize: 12 }}>
              <Text code style={{ fontSize: 11 }}>{`// 计算5日简单均线
double sum = 0;
for (int i = 0; i < 5 && i < n; i++) {
    sum += history.get(n - 1 - i).close.doubleValue();
}
return sum / Math.min(5, n);`}</Text>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" title="可用变量说明" style={{ borderLeft: '4px solid #52c41a' }}>
            <Paragraph style={{ fontSize: 12 }}>
              <Text code>history</Text> - List 历史K线（时间正序）<br/>
              <Text code>bar</Text> - 最新K线 MarketDailyBar<br/>
              <Text code>close</Text> - 最新收盘价 BigDecimal<br/>
              <Text code>n</Text> - 数据条数 int<br/>
              <Text code>symbol</Text> - 股票代码 String<br/>
              <Text code>calcDate</Text> - 计算日期 LocalDate<br/>
              <Text strong>返回：</Text> Number，null 表示无法计算
            </Paragraph>
          </Card>
        </Col>
      </Row>
    </section>
  );
}

// 以上函数均已通过 `export function` 命名导出
