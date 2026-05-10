import React from 'react';
import { Card, Typography, Row, Col, Tag, Alert, Descriptions } from 'antd';
import {
  StockOutlined, InfoCircleOutlined, FilterOutlined,
  CheckCircleOutlined, WarningOutlined, BarChartOutlined,
} from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

/**
 * 缠论结构筛选使用手册章节
 *
 * 位置关系：
 * - 因子列表里的「缠论」分类（CHAN_PEN_DIR / CHAN_TREND / CHAN_BUY_SELL / CHAN_HUB_POS / CHAN_PEN_COUNT）
 *   是这5个因子的「定义」和「计算状态」查看入口
 * - 缠论结构筛选页面是对这5个因子值的「联合筛选」工具
 *   即：先通过因子监控计算因子值 → 再到本页面按因子值组合条件筛选股票
 */
export function ManualChanScreen() {
  return (
    <section id="chan-screen" style={{ paddingBottom: 16 }}>
      <Title level={2}><StockOutlined /> 缠论结构筛选</Title>
      <Paragraph>
        缠论结构筛选是基于缠论理论，从全市场股票中筛选出处于特定「结构状态」的标的。
        比如：当前有哪些股票刚出现「1买」买点？哪些处于「上涨走势+中枢上半区」的加速段？
        省去逐个翻图分析的时间，直接给出符合条件的股票清单，辅助选股和择时决策。
      </Paragraph>

      {/* ── 与因子列表的关系 ─────────────────────────────── */}
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        style={{ marginBottom: 16 }}
        message="与因子列表「缠论」分类的关系"
        description={
          <div>
            <Paragraph style={{ marginBottom: 8 }}>
              缠论结构筛选和因子列表中的缠论因子是<Text strong>同一套数据</Text>的不同入口：
            </Paragraph>
            <ul style={{ paddingLeft: 20, marginBottom: 8 }}>
              <li><Text strong>因子列表 → 缠论分类</Text>：查看因子定义、计算公式、计算状态</li>
              <li><Text strong>因子监控</Text>：触发缠论因子值计算（先计算，筛选才有数据）</li>
              <li><Text strong>缠论结构筛选</Text>：对计算好的因子值设条件，筛选符合条件的股票</li>
            </ul>
            <Paragraph style={{ marginBottom: 0 }}>
              简言之：<Text strong>因子列表看定义 → 因子监控算数据 → 缠论筛选用数据</Text>
            </Paragraph>
          </div>
        }
      />

      {/* ── 与因子策略选股的关系 ────────────────────────── */}
      <Alert
        type="success"
        showIcon
        icon={<BarChartOutlined />}
        style={{ marginBottom: 16 }}
        message="与因子策略选股的关系"
        description={
          <div>
            <Paragraph style={{ marginBottom: 8 }}>
              缠论结构筛选和「策略管理 → 选股条件」<Text strong>本质相同</Text>：都是基于因子值筛选股票，
              数据来源完全一致（factor_value 表）。
              区别在于使用方式和灵活性：
            </Paragraph>
            <Row gutter={[12, 12]}>
              <Col xs={24} md={12}>
                <Card size="small" title="缠论结构筛选" style={{ marginBottom: 0 }}>
                  <Paragraph style={{ fontSize: 12, margin: 0 }}>
                    <Text strong>优点：</Text>操作直观，5个缠论维度开箱即用，无需配置<br/>
                    <Text strong>局限：</Text>维度固定为5个，不可扩展，不支持保存为模板
                  </Paragraph>
                </Card>
              </Col>
              <Col xs={24} md={12}>
                <Card size="small" title="策略管理 → 选股条件" style={{ marginBottom: 0 }}>
                  <Paragraph style={{ fontSize: 12, margin: 0 }}>
                    <Text strong>优点：</Text>任意因子自由组合，可保存策略模板，可回测、可优化<br/>
                    <Text strong>适合：</Text>正式选股流程、多因子组合、回测验证
                  </Paragraph>
                </Card>
              </Col>
            </Row>
            <Paragraph style={{ marginTop: 8, marginBottom: 0 }}>
              <Text type="secondary">互补使用建议：</Text>
              缠论筛选适合快速探索某个结构想法（如"找所有1买"）；
              想法成熟后，迁移到策略管理中保存为模板进行回测和参数优化。
            </Paragraph>
          </div>
        }
      />

      {/* ── 增减缠论因子的影响 ─────────────────────────── */}
      <Alert
        type="success"
        showIcon
        icon={<CheckCircleOutlined />}
        style={{ marginBottom: 16 }}
        message="增减缠论因子对筛选页面的影响（已改为动态化）"
        description={
          <div>
            <Paragraph style={{ marginBottom: 8 }}>
              缠论结构筛选页面已改为<Text strong>动态化</Text>——它从因子定义表实时读取所有激活的缠论因子，
              自动构建筛选维度和表格列。<Text strong>无需修改代码</Text>即可感知因子变化：
            </Paragraph>
            <ul style={{ paddingLeft: 20 }}>
              <li><Text strong>新增缠论因子</Text>：因子定义表中有记录且状态为「已激活」后，
                筛选页面会自动出现对应的筛选维度和结果列</li>
              <li><Text strong>删除/停用缠论因子</Text>：因子状态改为非 ACTIVE 后，
                筛选页面自动不再显示该因子（数据仍保留在 factor_value 表中）</li>
              <li><Text strong>重命名因子代码</Text>：SQL pivot 动态匹配，重命名后自动生效</li>
            </ul>
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 8 }}
              message="计算逻辑必须用代码实现"
              description={
                <span>
                  动态化只解决「数据定义层」的自动感知问题。
                  新增缠论因子<Text strong>必须</Text>在因子计算引擎（Java）中实现计算逻辑，
                  无法通过配置文件完成。具体步骤见「因子管理 → 新建因子页面的问号说明」。
                </span>
              }
            />
          </div>
        }
      />

      {/* ── 页面布局 ─────────────────────────────────────── */}
      <Title level={4}>页面布局</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>上部：筛选条件面板</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              设置5个维度的筛选条件：笔方向、走势类型、买卖点信号、
              中枢位置范围、笔数量范围，以及关键词搜索。
              设置完毕后点击「筛选」按钮执行。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" type="inner" style={{ borderLeft: '4px solid #52c41a' }}>
            <Title level={5}>下部：筛选结果表格</Title>
            <Paragraph style={{ fontSize: 13, margin: 0 }}>
              展示符合条件的股票列表，包含各因子的当前值。
              支持分页浏览，点击表头可排序（由后端支持时）。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* ── 5个筛选维度详解 ────────────────────────────── */}
      <Title level={4}>五个筛选维度详解</Title>
      <Paragraph>
        缠论结构筛选提供5个维度的组合条件，各维度含义及使用场景如下：
      </Paragraph>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={12}>
          <Card size="small" style={{ borderLeft: '4px solid #ff4d4f' }}>
            <Title level={5}>① 笔方向（CHAN_PEN_DIR）</Title>
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>含义：</Text>当前K线笔的方向。+1表示上升笔（高点不断抬高），
              -1表示下降笔（低点不断降低）。<br/>
              <Text strong>使用场景：</Text>筛选「上升笔」找到强势股，筛选「下降笔」找到待反转标的。<br/>
              <Text strong>操作：</Text>勾选「上升笔 ▲」和/或「下降笔 ▼」，不勾选表示不限。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" style={{ borderLeft: '4px solid #1677ff' }}>
            <Title level={5}>② 走势类型（CHAN_TREND）</Title>
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>含义：</Text>当前走势的类型。1=上涨走势（含中枢的上升），
              0=盘整走势（中枢震荡），-1=下跌走势。<br/>
              <Text strong>使用场景：</Text>上涨走势中持股，盘整中高抛低吸，下跌走势中观望或做空备选。<br/>
              <Text strong>操作：</Text>勾选「上涨」/「盘整」/「下跌」中的一个或多个。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" style={{ borderLeft: '4px solid #fa8c16' }}>
            <Title level={5}>③ 买卖点信号（CHAN_BUY_SELL）</Title>
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>含义：</Text>缠论买卖点信号。1买/2买/3买为买入信号（数字越大越安全），
              -1卖/-2卖/-3卖为卖出信号。<br/>
              <Text strong>使用场景：</Text>1买是趋势反转点（风险最大收益最高），3买是趋势确认后的回调买点（最安全）。
              卖点反之。<br/>
              <Text strong>操作：</Text>勾选关注的买卖点信号，可多选。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" style={{ borderLeft: '4px solid #722ed1' }}>
            <Title level={5}>④ 中枢相对位置（CHAN_HUB_POS）</Title>
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>含义：</Text>当前价格相对于最近中枢的归一化位置，范围 [0, 1]。
              0=在中枢下沿，1=在中枢上沿，0.5=中枢中心。<br/>
              <Text strong>使用场景：</Text>位置 &lt; 0.3 表示价格在中枢底部附近（潜在买点），
              位置 &gt; 0.7 表示价格在中枢顶部附近（潜在卖点）。<br/>
              <Text strong>操作：</Text>拖动双滑块设置范围，如 [0.00, 0.30] 筛选中枢底部标的。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={24}>
          <Card size="small" style={{ borderLeft: '4px solid #13c2c2' }}>
            <Title level={5}>⑤ 笔数量（CHAN_PEN_COUNT）</Title>
            <Paragraph style={{ fontSize: 13 }}>
              <Text strong>含义：</Text>当前走势中笔的数量，反映走势的复杂度和波动活跃度。
              笔数量多=走势复杂、震荡频繁；笔数量少=走势简洁、趋势明确。<br/>
              <Text strong>使用场景：</Text>笔数量 ≤ 3 的标的走势简洁适合趋势跟踪；
              笔数量 ≥ 10 的标的处于复杂震荡，适合短线高抛低吸。<br/>
              <Text strong>操作：</Text>拖动双滑块设置范围，默认 [1, 50]。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* ── 操作流程图 ──────────────────────────────────── */}
      <Title level={4}>典型使用流程</Title>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ textAlign: 'center' }}>
            <Title level={5}>① 确保数据就绪</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              前往「因子管理 → 因子监控」，
              确认缠论5因子已计算（有因子值）。
              若无数据，先执行因子计算。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ textAlign: 'center' }}>
            <Title level={5}>② 设置筛选条件</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              在筛选面板中勾选笔方向、走势类型、
              买卖点信号，设置中枢位置和笔数量范围。
              条件之间是「且」关系。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ textAlign: 'center' }}>
            <Title level={5}>③ 执行筛选</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              点击「筛选」按钮。
              结果展示符合条件的股票数量及明细列表。
              可用关键词进一步过滤结果。
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card size="small" type="inner" style={{ textAlign: 'center' }}>
            <Title level={5}>④ 辅助决策</Title>
            <Paragraph style={{ fontSize: 12, margin: 0 }}>
              结合买卖点信号和中枢位置判断入场/出场时机。
              可将筛选结果加入自选（后续版本支持），
              或作为策略选股的条件之一。
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* ── 实用组合示例 ──────────────────────────────── */}
      <Title level={4}>实用筛选组合示例</Title>
      <Paragraph>
        以下是几个常见场景的筛选条件组合，可直接参考使用：
      </Paragraph>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small" title="场景一：捕捉1买反转" style={{ borderLeft: '4px solid #ff4d4f' }}>
            <Paragraph style={{ fontSize: 12 }}>
              <Text strong>目标：</Text>找到趋势底部反转的标的<br/>
              <Text strong>笔方向：</Text>上升笔（刚形成上升笔）<br/>
              <Text strong>买卖点：</Text>勾选「1买」<br/>
              <Text strong>中枢位置：</Text>0.00 ~ 0.30（底部区域）<br/>
              <Text strong>注意：</Text>1买风险最高，建议分批建仓
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" title="场景二：趋势确认后入场" style={{ borderLeft: '4px solid #52c41a' }}>
            <Paragraph style={{ fontSize: 12 }}>
              <Text strong>目标：</Text>在趋势确认后安全入场<br/>
              <Text strong>走势类型：</Text>上涨<br/>
              <Text strong>买卖点：</Text>勾选「3买」（最安全）<br/>
              <Text strong>笔方向：</Text>上升笔<br/>
              <Text strong>中枢位置：</Text>0.50 ~ 1.00（上半区）<br/>
            </Paragraph>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" title="场景三：盘整区间高抛低吸" style={{ borderLeft: '4px solid #faad14' }}>
            <Paragraph style={{ fontSize: 12 }}>
              <Text strong>目标：</Text>在盘整股中做波段<br/>
              <Text strong>走势类型：</Text>盘整<br/>
              <Text strong>中枢位置：</Text>0.00 ~ 0.20 或 0.80 ~ 1.00<br/>
              <Text strong>笔数量：</Text>≥ 5（确保有足够波动）<br/>
              <Text strong>注意：</Text>需配合成交量放大确认突破
            </Paragraph>
          </Card>
        </Col>
      </Row>

      {/* ── 结果说明 ──────────────────────────────────── */}
      <Title level={4}>筛选结果字段说明</Title>
      <Descriptions size="small" bordered column={1} style={{ marginBottom: 16 }}>
        <Descriptions.Item label="代码"><Text code>ts_code</Text></Descriptions.Item>
        <Descriptions.Item label="名称"><Text code>name</Text></Descriptions.Item>
        <Descriptions.Item label="笔方向">
          <Tag color="red">▲ 上升</Tag> 或 <Tag color="green">▼ 下降</Tag>，null 表示数据不足
        </Descriptions.Item>
        <Descriptions.Item label="走势">
          <Tag color="red">上涨</Tag> / <Tag color="blue">盘整</Tag> / <Tag color="green">下跌</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="买卖点">
          <Tag color="volcano">1买</Tag>~<Tag color="volcano">3买</Tag> 或
          <Tag color="cyan">1卖</Tag>~<Tag color="cyan">3卖</Tag>，-（无信号）
        </Descriptions.Item>
        <Descriptions.Item label="中枢位置">0~1 的小数，保留3位。越小越接近中枢下沿</Descriptions.Item>
        <Descriptions.Item label="笔数量">整数，当前走势中的笔数</Descriptions.Item>
        <Descriptions.Item label="数据日期"><Text code>calc_date</Text>，因子值对应的计算日期</Descriptions.Item>
      </Descriptions>

      <Alert
        type="warning"
        showIcon
        icon={<WarningOutlined />}
        style={{ marginBottom: 16 }}
        message="注意事项"
        description={
          <div>
            <p><Text strong>1. 数据前置依赖：</Text>缠论筛选依赖已计算的因子值。如果筛选结果为空，
            请先前往「因子管理 → 因子监控」计算缠论因子（CHAN_PEN_DIR 等5个）。</p>
            <p><Text strong>2. 条件为「且」关系：</Text>多个勾选项之间是 AND 关系，条件越精细结果越少。
            建议先宽松筛选，再逐步加条件。</p>
            <p><Text strong>3. 数据时效性：</Text>因子值取决于最后一次因子计算的时间。
            建议筛选前先更新因子值，确保使用最新数据。</p>
            <p><Text strong>4. 不构成投资建议：</Text>缠论筛选仅提供技术结构参考，
            实际投资决策需结合基本面、资金面、市场环境综合判断。</p>
          </div>
        }
      />

      <Alert
        type="success"
        showIcon
        icon={<CheckCircleOutlined />}
        message="与回测策略联动"
        description="筛选结果可以作为选股策略的候选池。在「策略管理」中创建策略时，
        可以将缠论条件（如「CHAN_BUY_SELL = 1」）写入策略的选股条件，
        实现缠论信号的程序化交易。"
      />
    </section>
  );
}

export default ManualChanScreen;
