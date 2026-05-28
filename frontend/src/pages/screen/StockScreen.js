import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import {
  Card, Button, Space, Typography, Table, Tag, InputNumber, Select,
  DatePicker, Row, Col, Statistic, Divider, Tooltip, Badge,
  Empty, Spin, Progress, Alert, Form, Popover, Modal, Input, Slider, Tabs, Checkbox, App,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, PlayCircleOutlined, FilterOutlined,
  InfoCircleOutlined, ReloadOutlined, SwapOutlined, QuestionCircleOutlined,
  SaveOutlined, CopyOutlined, StarOutlined, WarningOutlined,
  SafetyCertificateOutlined, ArrowUpOutlined, ArrowDownOutlined,
  PlusSquareOutlined, MinusSquareOutlined, ThunderboltOutlined, LineChartOutlined, FundOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, RiseOutlined, StockOutlined,
  HistoryOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import api, { factorApi } from '../../api';
import { Link, useSearchParams } from 'react-router-dom';
import { useMarketThermometer } from '../../hooks/useMarketThermometer';
import RollingBacktestModal from './RollingBacktestModal';

const { Title, Text, Paragraph } = Typography;
const { Option } = Select;
const { TextArea } = Input;

/* ── 常量 ─────────────────────────────────────────────────────────── */
const CATEGORY_COLOR = {
  MOMENTUM: 'blue', VALUE: 'gold', QUALITY: 'green', VOLATILITY: 'orange',
  TECHNICAL: 'purple', FINANCIAL: 'green', SENTIMENT: 'magenta',
  LIQUIDITY: 'volcano', VOLUME_PRICE: 'geekblue', CUSTOM: 'default',
};
const CATEGORY_LABEL = {
  MOMENTUM: '动量', VALUE: '价值', QUALITY: '质量', VOLATILITY: '波动率',
  TECHNICAL: '技术', FINANCIAL: '财务', SENTIMENT: '情绪',
  LIQUIDITY: '流动性', VOLUME_PRICE: '量价', CUSTOM: '自定义',
};

const OUTLIER_OPTIONS = [
  { value: 'NONE', label: '不处理' },
  { value: 'MAD', label: '中位数去极值法' },
  { value: 'SIGMA3', label: '3σ法' },
  { value: 'PERCENTILE', label: '百分位截断' },
];
const NORMALIZE_OPTIONS = [
  { value: 'NONE', label: '不处理' },
  { value: 'ZSCORE', label: '标准化法 (Z-Score)' },
  { value: 'MINMAX', label: 'Min-Max 归一化' },
  { value: 'RANK', label: '百分位排名' },
];
const ORTHOGONAL_OPTIONS = [
  { value: 'NONE', label: '不正交化' },
  { value: 'SCHMIDT', label: '施密特正交化 (Gram-Schmidt)' },
];
const FILTER_OP_OPTIONS = [
  { value: 'NONE', label: '无' },
  { value: 'GT', label: '大于 (>)' },
  { value: 'GTE', label: '大于等于 (≥)' },
  { value: 'LT', label: '小于 (<)' },
  { value: 'LTE', label: '小于等于 (≤)' },
];

/* ── 预设组合说明数据 ────────────────────────────────────────────── */
const PRESET_DESCRIPTIONS = {
  '经典多因子': {
    goal: '通过多维度因子均衡配置，在各市场环境下获取稳健超额收益，降低单因子失效风险。',
    scenario: '适合中长期持有、不希望押注单一风格、追求稳健跑赢指数的投资者。牛市跟随趋势，熊市靠低波动+质量因子防御。',
    calibration: '等权配置哲学：7个因子各司其职，除RSI作为软约束给半权外，其余因子权重均为1，确保任一维度都不会过度主导排名。等权的核心假设是：市场风格无法预判，不预设立场就是最好的立场。RSI≤70是行为金融学的经典阈值——超过70意味着短期情绪过热，回调概率显著上升，但70并非硬卖点而是"谨慎追入"的分界线。',
    factors: [
      { name: '小盘溢价（SIZE, w=1, 反向）', role: 'A股长期存在小市值效应，小盘股弹性更大、超额收益显著。' },
      { name: '趋势跟踪（MOM20, w=1）', role: '20日动量捕捉中短期趋势，顺势而为，避免逆势抄底。' },
      { name: '低波动（VOL20, w=1, 反向）', role: '学术研究证明低波动股票长期夏普比率更高，是经典风险调整因子。' },
      { name: '流动性补偿（AMIHUD, w=1, 反向）', role: '低流动性股票存在流动性溢价，市场对其定价偏低，持有可获补偿收益。' },
      { name: 'TTM ROE（FIN_ROE_TTM, w=1）', role: '滚动12个月ROE反映最新盈利质量，过滤单季异常波动，选真正赚钱的公司。' },
      { name: 'TTM 营收增速（FIN_REVENUE_TTM_YOY, w=1）', role: '营收TTM同比反映持续成长性，比单季数据更稳定、更少噪音。' },
      { name: 'RSI 超买控制（RSI14, w=0.5, ≤70）', role: 'RSI≤70剔除短期过热标的。半权是因为这是约束条件而非信号来源——它不产生阿尔法，只负责防止追高。70的阈值比学术常用的80更保守，A股波动大、情绪化严重，取70能更早规避过热风险。' },
    ],
  },
  '小盘成长': {
    goal: '在小市值池中筛选高成长标的，利用小盘溢价+业绩爆发双重驱动，追求高弹性回报。',
    scenario: '适合风险偏好较高、能承受较大波动、看好中小盘结构性机会的投资者。在流动性宽裕、市场风险偏好上行阶段表现最佳。',
    calibration: '核心权重分配逻辑："小盘定位（w=2）>>> 成长验证（w=1.5）>>> 辅助确认（w=1/0.5）"。SIZE给双倍权重是为了确保组合始终保持小盘风格，避免大盘成长股涌入稀释策略纯度。营收增速>10%的门槛设在一倍GDP增速以上——低于10%的企业本质上只是随经济同步扩张，没有"超额成长"。ROE>8%是社会平均回报率的下限，过滤"增收不增利"。动量只给0.5权重：成长股的核心驱动力是基本面而非价格趋势，动量只是确认市场已经在买单。',
    factors: [
      { name: '小市值（SIZE, w=2, 反向）', role: '核心因子。双倍权重确保组合保持小盘风格，不被大盘股稀释。' },
      { name: 'TTM 营收增速（>10%, w=1.5）', role: '硬过滤+高权重。10%约为一倍GDP增速，低于此线只是在随经济同步扩张，没有"超额成长"。TTM消除季节性干扰。' },
      { name: 'TTM ROE（>8%, w=1）', role: '硬过滤。8%是社会平均资本回报率底线，确保盈利质量。' },
      { name: 'TTM 净利润增速（w=1）', role: '打分因子。利润增速越高排名越靠前，筛选出盈利爆发力最强的标的。' },
      { name: '20日动量（w=0.5）', role: '低权趋势确认。半权是因为动量只是"市场已经买单"的佐证而非核心逻辑，核心逻辑是基本面成长。' },
    ],
  },
  '低波动红利': {
    goal: '通过低波动+高盈利质量双保险，构建防御型组合，在控制回撤的前提下获取稳健回报。',
    scenario: '适合风险厌恶型投资者、熊市或震荡市中的防御配置。当市场不确定性高、波动率攀升时，低波动组合通常表现更强。',
    calibration: '核心权重分配："低波动锚定（w=2）>>> 质量过滤（w=1.5/1）>>> 活跃度验证（w=0.5）"。VOL20双倍权重是因为低波动是整个策略的"锚"——去掉它就不再是低波动策略。盈余质量>0.5意味着应计利润不超过经营现金流的一半，这是学术界验证的财务质量分界线。CF/NP>0.5表示每1元净利润至少有5毛现金支撑，低于这个水平说明利润质量堪忧。BPS>2元是A股"有资产底"的朴素标准。量比只给0.5权重——策略不需要追逐热点，但要排除无人问津的僵尸股。',
    factors: [
      { name: '低波动（VOL20, w=2, 反向）', role: '核心因子。双倍权重锚定策略身份——这不是一个附带低波动的多因子策略，这就是一个低波动策略。' },
      { name: '盈余质量（>0.5, w=1.5）', role: '核心质量过滤。>0.5的学术标准确保经营现金流与会计利润匹配度超50%，大幅降低财务造假风险。高权重配合高阈值，质量验证是此策略的第二支柱。' },
      { name: '现金流/净利润（>0.5, w=1）', role: '现金转化率刚性门槛。每1元利润至少5毛变现金，低于此线意味着应收账款或存货虚增利润。与盈余质量形成双维度交叉验证。' },
      { name: '每股净资产（>2元, w=1）', role: '资产安全边际。A股中BPS>2元的公司破净风险可控，为股价提供"硬地板"支撑。' },
      { name: '量比（w=0.5）', role: '半权辅助验证有资金关注即可，不追求热门股。低波动策略不需要高换手高关注。' },
    ],
  },
  '技术动量': {
    goal: '纯技术面驱动，通过多时间维度趋势确认+超买回避，捕捉短线强势股的持续上涨阶段。',
    scenario: '适合短线交易者（持仓1-4周），在趋势明显的市场环境中效果最佳。震荡市或急涨急跌中表现会打折扣。',
    calibration: '此策略是纯技术面的"趋势确认链"：短周期（MOM20 w=1.5）> 中周期（MOM60 w=1）= 动能指标（MACD w=1）= 量能（VOLUME_RATIO w=1）> 位置（BOLL_POS w=0.5）。MOM20权重最高因短周期是交易的核心决策窗口。RSI≤60（而非70）是一个偏保守的选择——技术动量追求的是"已经启动但尚未过热"，60的阈值比70更早拦截，避免追入已在高位的标的。MOM20/MOM60>10%的阈值排除了微涨但无意义的噪音，MACD>0.5与RSI≤60配合形成"动能充沛但不超买"的黄金区间。量比>0.8是略低于平均水平（1.0）的温和门槛——不要求极端放量，但排除明显缩量的标的。布林位置只给半权，因为它是辅助性的"位置确认"而非独立的信号源。',
    factors: [
      { name: '20日动量（>10%, w=1.5）', role: '核心交易信号。20日窗口与A股短线交易周期匹配，>10%排除无意义的微涨噪音。最高权重因它是直接决策依据。' },
      { name: '60日动量（>10%, w=1）', role: '中长期趋势背景。确认不在下跌通道中做短线反弹——跌势中的反弹是最危险的陷阱。' },
      { name: 'RSI（≤60, w=1, 反向）', role: '偏保守的超买回避。60比学术标准的70更早拦截，确保在"已经启动但尚未过热"区间介入。反向加权意为RSI越低越好。' },
      { name: 'MACD（>0.5, w=1）', role: '动能充沛确认。MACD>0表示多头控盘，>0.5表示动能充足而非勉强翻红。与RSI≤60互补：MACD要求动能强，RSI要求不过热。' },
      { name: '量比（>0.8, w=1）', role: '量能配合。0.8是温和门槛——不要求天量，但排除缩量上涨的虚假突破。' },
      { name: '布林位置（>40%, w=0.5）', role: '辅助位置确认。在中轨以上运行表示处于多头区域，半权因这是位置验证而非独立信号。' },
    ],
  },
  '价值投资': {
    goal: '挖掘市场定价偏低但基本面扎实的公司，以安全边际为核心，等待价值回归。',
    scenario: '适合长期价值投资者（持仓6个月以上），在价值风格占优、高估值板块回落的阶段表现突出。低换手、低交易成本。',
    calibration: '经典的格雷厄姆-多德价值框架："便宜程度（E/P w=1.5）> 安全边际（BPS/毛利率 w=1）> 风险排除（现金流质量/负债率 w=1/0.5）"。E/P收益率是核心价值锚，权重1.5因为它直接量化"便宜程度"——相比P/E，E/P避免了盈利为负时的异常值。BPS>3元比低波动策略的2元更高，因为价值股可能面临更长的等待期，需要更厚的安全垫。毛利率>20%不是极端门槛——价值股不一定是高毛利企业，但低于20%意味着在价格战中挣扎，价值陷阱概率大增。现金流质量阈值设为>0.3（低于质量策略的0.8）——价值股的现金流可能更不稳定，适度放宽。资产负债率<60%（而非50%）同样是价值股的特殊调整：重资产行业的价值股合理负债率本身就偏高。',
    factors: [
      { name: '盈利收益率（E/P, w=1.5）', role: '核心价值锚。最高权重因为它直接度量"便宜程度"。E/P越高=越便宜。用E/P而非P/E避免了E≤0时P/E无意义的数学缺陷。' },
      { name: '每股净资产（>3元, w=1）', role: '高门槛安全边际。3元比一般策略的2元更高——价值策略等待周期更长，需要更厚的安全垫防止时间换不来价值回归。' },
      { name: '毛利率（>20%, w=1）', role: '护城河代理变量。20%是温和标准——价值股不要求高毛利，但低于20%说明缺乏定价权，容易陷入价值陷阱。' },
      { name: '现金流质量（>0.3, w=1）', role: '适度门槛。0.3比质量策略的0.8低——价值股的现金流更不稳定，过严会排除大量周期股。但必须有基本的现金流真实性。' },
      { name: '资产负债率（<60%, w=0.5, 反向）', role: '半权财务安全阀。60%比质量策略的50%更宽——重资产行业价值股合理负债率偏高（如钢铁、化工），但超过60%在利率上行时风险激增。' },
      { name: '小市值偏好（SIZE, w=1, 反向）', role: '小盘价值双重溢价。小盘+低估是Fama-French三因子框架下已被验证的长期超额来源。' },
    ],
  },
  '趋势突破': {
    goal: '捕捉已经走出明确上升趋势、且有量能配合的个股，在趋势中途介入，赚取趋势延续的利润。',
    scenario: '适合趋势交易者，在单边上涨行情中效率最高。不适合震荡或下跌市——趋势策略在这些环境容易反复止损。',
    calibration: '趋势交易的核心信条："长期趋势决定方向，短期趋势决定时机，量能决定可信度"。MOM60以w=2锚定长期方向——没有60日趋势支撑的突破往往是假突破。MOM20以w=1.5确认突破时机——长期趋势向上且短期也在涨才是真正的"突破"。量比>1（w=1.5）是关键门槛：必须放量。趋势交易中最怕的就是缩量上涨——那往往是存量资金出货而非增量资金进场。布林位置>40%是趋势股的标准姿态——价格沿布林上轨爬升是强势特征。量价相关性>0.3（w=1）是"上涨放量+下跌缩量"的量化表达。VOL20<=55%（w=0.5）是波动率上限约束——波动过大的趋势容易突然反转。',
    factors: [
      { name: '60日动量（>10%, w=2）', role: '核心方向锚。双倍权重因为长期趋势是一切的基础——没有它，短期突破都是噪音。>10%确认不是横盘震荡。' },
      { name: '20日动量（>10%, w=1.5）', role: '短期突破确认。MOM60确保方向对，MOM20确保时机对。两者同时>10%是"中长期+短期共振"的信号。' },
      { name: '量比（>1, w=1.5）', role: '放量刚性要求。>1意味着成交量超过近期均值，这是增量资金进场的核心信号。等权于MOM20说明量能与趋势同等重要。' },
      { name: '布林位置（>40%, w=1）', role: '趋势位置确认。>40%即在中轨以上，标准权重是合理的辅助信号。' },
      { name: '量价相关性（>0.3, w=1）', role: '量价结构验证。>0.3正相关说明上涨时放量、回调时缩量，是健康的量价结构。' },
      { name: '低波动（≤55%, w=0.5, 反向）', role: '半权约束。不是所有低波动都好，但超过55%的波动率在趋势策略中容易导致突然反转。半权恰好平衡"允许波动"和"控制极端"。' },
    ],
  },
  '高盈利质量': {
    goal: '筛选真正高质量赚钱的公司——不仅ROE高，而且利润是现金支撑的、毛利有壁垒的、杠杆可控的。',
    scenario: '适合价值成长型投资者，追求"好公司"而非"便宜公司"。在业绩披露期前后和熊市防御中表现优异。',
    calibration: '阶梯式权重反映质量因子的层次："盈利能力（ROE w=2）>>> 竞争壁垒+利润真实性（毛利率/盈余质量/现金流 w=1.5/1）>>> 辅助指标（净利率 w=0.5）"。ROE双倍权重因为它是质量的"总开关"——高ROE是结果，其他因子是解释。>10%是巴菲特明确提出的选股门槛。毛利率>25%（w=1.5）是"有定价权"的信号——25%意味着每100元收入有25元毛利覆盖费用，是可持续盈利的基准线。盈余质量和CF/NP都设在>0.8（w=1.5/1），80%是极高标准——意味着利润几乎完全由现金支撑，排除应收账款虚增和存货积压。资产负债率<50%（w=1）比价值策略更严——高质量公司不应该靠高杠杆驱动。净利率>5%（w=0.5）是轻量辅助——高ROE的公司通常净利率也不低，所以只给半权避免多重计算。',
    factors: [
      { name: 'TTM ROE（>10%, w=2）', role: '核心质量锚。双倍权重+巴菲特门槛。ROE>10%是"好生意"的基本定义，TTM方式消除单季扰动。' },
      { name: '毛利率（>25%, w=1.5）', role: '竞争壁垒代理。>25%意味着有定价权而非价格战，高权重因它是利润质量的源头——没有高毛利，后面的"利润真实性"都无从谈起。' },
      { name: '盈余质量（>0.8, w=1.5）', role: '极高门槛。>0.8意味着经营现金流至少覆盖80%的会计利润，是财务质量的金标准。高权重因这是"真赚钱"的核心验证。' },
      { name: '现金流/净利润（>0.8, w=1）', role: '现金转化率刚性门槛。80%转化率意味着利润几乎不依赖应收账款和存货，赚的是"真钱"而非"纸面利润"。' },
      { name: '资产负债率（<50%, w=1, 反向）', role: '财务保守标准。真正的好公司不需要靠借新还旧维持ROE。50%比价值策略的60%更严苛。' },
      { name: '净利率（>5%, w=0.5）', role: '半权辅助。高ROE公司净利率通常也高，半权避免与ROE的多重计数，但仍保留对"高收入低净利"模式（如零售）的惩罚。' },
    ],
  },
  '成长加速': {
    goal: '捕捉处于业绩爆发期的成长股——营收和净利润双双高速增长，且股价正在加速上涨。这是组合中最具进攻性的策略。',
    scenario: '适合高风险偏好的成长型投资者，在成长风格占优、流动性充裕的市场中爆发力最强。不适合价值风格周期或流动性紧缩环境。',
    calibration: '激进的双引擎驱动设计："营收增速（w=2）= 利润增速（w=2）>>> 加速+质量（w=1.5）>>> 风控约束（w=0.5/1）"。营收和利润双w=2是这个策略的灵魂——它不是在选"成长股"，而是在选"营收和利润都在爆炸式增长的股票"。营收>15%是GDP增速的约3倍，利润>20%是加速增长的标志——净利润增速超过营收增速意味着规模效应正在释放。PRICE_MOM_ACC以w=1.5捕捉"加速度"——股票不仅在涨，且涨得越来越快，这是趋势策略中最肥美的一段。ROE>8%（w=1.5）是质量底线——烧钱换增长的企业即便营收增速再高也进不来。MOM20反向w=0.5是策略的"逆人性"设计——不在最热的时候追入，让回调为建仓留出空间。SIZE反向w=1放大小盘成长的弹性。',
    factors: [
      { name: 'TTM 营收增速（>15%, w=2）', role: '核心驱动之一。15%约3倍GDP增速——这不仅是"成长"而是"爆发"。双倍权重+TTM数据，避免季节性误判。' },
      { name: 'TTM 净利润增速（>20%, w=2）', role: '核心驱动之二。利润增速>营收增速意味着规模效应释放，是最健康的增长形态。双倍权重与营收增速形成双引擎。' },
      { name: '价格动量加速度（>0, w=1.5）', role: '捕捉趋势"拐点后的加速段"——不是已经涨了很久，而是涨得越来越快。高权重因这段是收益最肥美的部分。' },
      { name: 'TTM ROE（>8%, w=1.5）', role: '质量底线。高权重确保增长有盈利质量支撑。8%是社会平均回报率，是"真正的成长"和"烧钱换增长"的分界线。' },
      { name: '20日动量（w=0.5, 反向）', role: '逆人性约束。反向权重不是为了看跌，而是避免在短期过热时追入——回调建仓比追涨有更好的风险收益比。半权恰到好处：有约束但不妨碍选入强势股。' },
      { name: '小市值（SIZE, w=1, 反向）', role: '小盘弹性放大器。小盘+高成长是A股最暴利的组合之一，标准权重不会喧宾夺主。' },
    ],
  },
  '低估反转': {
    goal: '捕捉超跌个股的均值回归机会。在短期大跌后，如果基本面有支撑，市场过度悲观往往创造买入窗口。',
    scenario: '适合逆向投资者，在大盘急跌后或个股因非基本面因素暴跌时介入。需要耐心等待回归，不适合追求即时收益。',
    calibration: '双反转信号确认+基本面托底设计："超跌程度（REVERSAL5 w=1.5 = RSI<=40 w=1.5）>>> 安全垫（BPS w=1.5）>>> 质量过滤（ROE/低波动 w=1）>>> 风险排除（负债率 w=0.5）"。REVERSAL5和RSI<=40并列为最高权重——短期跌幅+超卖指标双重确认，避免单维度信号误判。RSI阈值设为40而非更极端的30：30以下往往是"还有更深层问题的崩盘"，40~30是"过度悲观但仍健康"的甜区。BPS>2元（w=1.5）高权重——反转策略最怕的是"下跌有理"，高BPS确保公司有硬资产支撑。ROE>5%——不是门槛低，而是反转策略的对象可能处于盈利低谷，只要仍在赚钱（哪怕微利）就保留了反转的基本面前提。资产负债率<65%（w=0.5）比一般策略宽松——困境反转企业负债率可能阶段性偏高，但不设限又会选入资不抵债标的。',
    factors: [
      { name: '5日反转（REVERSAL5, w=1.5）', role: '核心反转信号。短期跌幅越大反弹概率和幅度越大，行为金融学的过度反应理论支撑。最高权重因它是直接信号源。' },
      { name: 'RSI超卖（≤40, w=1.5, 反向）', role: '技术超卖确认。40（非30）是"过度悲观但仍有基本面支撑"的区间——30以下往往是基本面恶化的崩盘，反转逻辑不适用。' },
      { name: '每股净资产（>2元, w=1.5）', role: '高权重安全垫。反转策略的核心风险是"下跌有因"，高BPS确保资产不会归零，为价值回归提供底部参考。' },
      { name: 'TTM ROE（>5%, w=1）', role: '盈利底线而非盈利追求。反转的对象可能处于利润低谷，5%意味着"仍在赚钱"——只要盈利就保留了反转的前提。标准权重不过分强调盈利。' },
      { name: '低波动（w=1, 反向）', role: '稳定性偏好。反转后的修复过程，低波动股票更容易走出持续回升而非暴涨暴跌。标准权重合理。' },
      { name: '资产负债率（<65%, w=0.5, 反向）', role: '半权宽松阀。65%比质量策略的50%宽——困境企业负债率可能阶段性偏高。半权+宽松阈值：设了限但不作为核心否决项。' },
    ],
  },
  '量价异动': {
    goal: '捕捉资金异动信号——成交量和换手率的突变往往是大资金进场的前兆，在量变到价变的时间窗口中抢先介入。',
    scenario: '适合短线博弈者（1-5天持仓），对盘感和市场情绪有一定要求。在题材轮动活跃期效果最佳，流动性枯竭时信号噪音大。',
    calibration: '"量在价先"的量化表达："量能异常（VOLUME_SURPRISE w=2）>>> 换手突变+量能加速度（w=1.5）>>> 量价结构+方向确认（w=1）>>> 小盘放大（w=0.5）"。成交量惊喜w=2是绝对核心——量能突变超过日常波动范围是最直接的大资金信号。换手率异常>1.2倍（w=1.5）：20%的换手率偏离在统计学上有意义但又不过于极端——换手率翻倍往往伴随着异常事件而非正常建仓。VROC12>0（w=1.5）同权于换手率异常——不仅量大，而且在持续放大，排除脉冲式的一次性放量。量价相关性>0.2（w=1）阈值较低——量价关系在短期内噪音大，设0.2避免过度过滤。MOM5>0确保方向是向上而非放量下跌。SIZE只给0.5——小盘对资金更敏感，但不是核心逻辑。',
    factors: [
      { name: '成交量惊喜（>0.1, w=2）', role: '核心信号。量能突变超过日常波动范围=大资金进场的直接证据。双倍权重因为这是策略存在的理由——没有量能异常，就没有"异动"。' },
      { name: '换手率异常（>1.2倍, w=1.5）', role: '换手突变确认。1.2倍=超过正常水平20%，在统计学上有意义但不极端。翻倍换手率往往是事件驱动而非正常建仓。' },
      { name: '12日量变速率（>0, w=1.5）', role: '量能加速度。>0意味着量能还在放大而非衰减——这是区别"脉冲放量"和"持续流入"的关键。与换手率异常同权。' },
      { name: '量价相关性（>0.2, w=1）', role: '方向过滤。0.2的低阈值因短期内量价关系噪音大，设高了会过度过滤有效信号。只要基本是正相关即可。' },
      { name: '5日动量（>0, w=1）', role: '方向确认。已经启动+量能异动=强信号。>0是底线要求，不要求大幅上涨——量能异动本身就是价格的领先指标。' },
      { name: '小市值（SIZE, w=0.5, 反向）', role: '弹性放大器。半权因小盘只是放大信号而非产生信号——大资金在小盘股上建仓的痕迹更明显。' },
    ],
  },
  '财务健康': {
    goal: '用多维财务指标筛选资产负债表健康、运营效率高、现金流充裕的公司，从根源上规避财务暴雷风险。',
    scenario: '适合防御型配置，在信用紧缩、去杠杆周期中尤为重要。是"先确保不踩雷，再看收益"的审慎策略。',
    calibration: '三大支柱各w=1.5的均衡设计："短期偿债（流动比率）+ 长期杠杆（负债率）+ 现金流质量（CF/营收）"三者等权重，因为它们分别回答了不同维度的健康问题：能不能还短期债？总杠杆是否可控？赚的钱是不是真金白银？流动比率>1.5：流动资产覆盖1.5倍流动负债是教科书级安全标准，低于1.5在信贷收紧时可能触发流动性危机。资产负债率<50%是保守型企业的标准线——负债不到总资产一半，利息负担可控。CF/营收>5%：每100元营收产生5元经营现金流是健康企业的最低标准——低于此线意味着营运资金在吞噬利润。应收账款周转率和总资产周转率各w=1——运营效率是财务健康的"软实力"层面，标准权重合理。低波动w=0.5作为辅助——财务健康的公司往往更低波动，但这不是因果逻辑，所以只给半权。',
    factors: [
      { name: '流动比率（>1.5, w=1.5）', role: '短期偿债能力核心。1.5倍覆盖是教科书级安全标准——能应对正常的营运资金波动和短期债务到期。高权重因这直接关系"会不会突然死掉"。' },
      { name: '资产负债率（<50%, w=1.5, 反向）', role: '长期杠杆安全锚。50%是保守型企业的分界线——负债不到总资产一半，利率上升时利息负担可控。与流动比率同权重构成"短期+长期"完整偿债画像。' },
      { name: '现金流/营收（>5%, w=1.5）', role: '营收含金量。5%=每100元营收至少5元变成现金。低于此线说明卖货收不到钱，营收是纸面上的。三大核心指标之一，同权重。' },
      { name: '应收账款周转率（w=1）', role: '回款速度。周转越快=客户付款越积极=议价力越强。标准权重作为运营效率的第一维度。' },
      { name: '总资产周转率（w=1）', role: '资产运营效率。每1元资产产生多少收入，衡量管理层"用资产赚钱"的能力。与应收账款周转率互补覆盖运营效率。' },
      { name: '低波动（w=0.5, 反向）', role: '半权辅助。财务健康的公司波动率自然偏低，半权避免与财务指标的多重计数。' },
    ],
  },
  '综合打分': {
    goal: '不押注单一风格，通过技术面+基本面+情绪面八因子均衡打分，在多维度中寻找综合得分最高的标的。',
    scenario: '适合不确定市场风格方向的投资者，作为"底仓"策略。各风格轮动时组合都能有对应因子贡献得分，降低风格误判的代价。',
    calibration: '微倾斜的均衡框架："质量+成长（w=1.2）> 技术面+低波动+小盘（w=1）> 活跃度+风控（w=0.8）"。基本面因子权1.2略高于技术面的1——这是"综合打分"而非"技术选股"，基本面锚定提供更长的持有逻辑。但只给1.2而非1.5，因为不预判市场风格——如果质量因子权重太高，在市场炒作题材时就会失效。RSI<=70是经典阈值——超过70在A股几乎一定有回调，是性价比最高的风控门槛。RSI和量比给w=0.8略低于其他因子，因为它们的作用是"约束"而非"贡献得分"——RSI的作用是防止追涨，量比的作用是排除僵尸股，它们不主动产生阿尔法。',
    factors: [
      { name: 'TTM ROE（w=1.2）', role: '质量风格贡献。略高于平均水平（1.2>1），给予基本面一定的权重倾斜，但不极端——风格不明时均衡才是王道。' },
      { name: 'TTM 营收增速（w=1.2）', role: '成长风格贡献。与ROE同权重（1.2），质量+成长作为两个基本面锚等量齐观——不单独押注质量或成长。' },
      { name: '60日动量（w=1）', role: '趋势风格贡献。标准权重，与基本面形成"基本面+价格"双轮驱动。用60日而非20日——综合打分调仓频率低，中长期趋势更适配。' },
      { name: '20日波动率（w=1, 反向）', role: '风险调整贡献。标准权重，在多因子框架中低波动是稳健的"压舱石"——不主导、不缺席。' },
      { name: '小市值（SIZE, w=1, 反向）', role: '规模溢价。A股小盘长期跑赢的系统性收益来源，标准权重。' },
      { name: '盈余质量（w=1）', role: '基本面风控。在综合打分中与其说它是超额来源，不如说是"底线守护"——排除靠财技维持利润的公司。' },
      { name: '量比（w=0.8）', role: '活跃度验证。0.8略低于标准权重——它保证选出的不是僵尸股，但不应该主导得分。' },
      { name: 'RSI（≤70, w=0.8, 反向）', role: '技术面风控。0.8半风控权重——RSI≤70是性价比最高的"不追高"约束，但不产生正向阿尔法，所以权重略低于信号因子。' },
    ],
  },
  '经典技术指标': {
    goal: '用最经典的四套技术指标（均线趋势+MACD+RSI+布林带），构建经过时间检验的技术选股体系，简洁透明、不依赖复杂模型。',
    scenario: '适合相信技术分析、偏好简洁规则的中短线交易者。这套组合是技术分析的"基础款"，逻辑清晰、容易理解、回测时间长。',
    calibration: '经典三驾马车+辅助验证的权重结构："趋势（MOM20+MOM60+MACD 各w=1.5）>>> 位置+风控（RSI+布林 w=1）>>> 量价+波动（w=0.8/0.7）"。三个趋势指标各1.5等权——MOM20看短期、MOM60看中期、MACD看动能，三者从不同时间维度和计算方式交叉验证趋势，不存在哪个更重要的问题。RSI≤60（w=1）是技术分析界公认的"合理介入区间"——>70过热，<30超卖，50-60是强势但不极端的甜区。布林位置>40%验证价格在多头区域。VPCORR20>0.3是量价健康的最低标准。VOL20<=55%（w=0.7）是波动率上限容忍——经典技术指标对趋势质量要求高，过高的波动率意味着假突破概率大增。对于这个纯技术策略，波动率做辅助约束但不主导。',
    factors: [
      { name: '20日动量（>10%, w=1.5）', role: '短期均线趋势。三大核心之一，高权重。>10%过滤微涨噪音——20日均线走平或微微向上不构成交易信号。' },
      { name: '60日动量（>10%, w=1.5）', role: '中期均线趋势。与MOM20同权重同阈值——趋势策略中短期和中期同等重要，缺少任何一个都是"跛脚"的趋势判断。' },
      { name: 'MACD（>0.5, w=1.5）', role: '动量指标核心。与均线趋势等权重——MACD是均线的派生指标但提供了不同视角（DIF/DEA的差值），三者互补而非重复。' },
      { name: 'RSI（≤60, w=1, 反向）', role: '经典超买回避。60而非70——这个策略追求"稳健的趋势"，而非"最强的趋势"。最强的趋势往往伴随最高的回调风险。' },
      { name: '布林位置（>40%, w=1）', role: '多头区域确认。中轨以上+沿上轨爬升=强势。标准权重——布林带是均线的衍生，权重不宜与均线本身等同。' },
      { name: '量价相关性（>0.3, w=0.8）', role: '量价结构验证。0.8略低权重——量价关系作为辅助确认而非主要信号。>0.3是健康趋势的最低要求。' },
      { name: '低波动（≤55%, w=0.7, 反向）', role: '最低权重辅助。趋势策略中波动率控制是"锦上添花"而非"雪中送炭"。0.7的权重恰好反映这个定位。' },
    ],
  },
};

/* ── 默认单条因子配置 ──────────────────────────────────────────────── */
const DEFAULT_FACTOR = (factorCode) => ({
  factorCode,
  direction: 1,
  weight: 1,
  filterOp: 'NONE',
  filterValue: null,
  outlierMethod: null,
  normalizeMethod: null,
});

function scoreColor(v) {
  if (v >= 0.8) return '#52c41a';
  if (v >= 0.6) return '#1677ff';
  if (v >= 0.4) return '#faad14';
  return '#ff4d4f';
}

function riskColor(level) {
  if (level === 'low') return '#52c41a';
  if (level === 'medium') return '#faad14';
  return '#ff4d4f';
}

function riskLabel(level) {
  if (level === 'low') return '低风险';
  if (level === 'medium') return '中风险';
  if (level === 'high') return '高风险';
  return '未知';
}

function fmt(v, digits = 2) {
  if (v == null || isNaN(v)) return '-';
  return Number(v).toFixed(digits);
}

function fmtPct(v) {
  if (v == null || isNaN(v)) return '-';
  const n = Number(v);
  return n >= 0 ? `+${n.toFixed(2)}%` : `${n.toFixed(2)}%`;
}

/* ── 预设说明浮动框组件 ──────────────────────────────────────────── */
function PresetDescPopover({ presetName }) {
  const info = PRESET_DESCRIPTIONS[presetName];
  if (!info) return null;

  return (
    <Popover
      title={<Space><StarOutlined /> {presetName}</Space>}
      content={
        <div style={{ width: 480, maxHeight: 420, overflowY: 'auto' }}>
          <div style={{ marginBottom: 12 }}>
            <Text strong>🎯 组合目标</Text>
            <Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 13 }}>{info.goal}</Paragraph>
          </div>
          <div style={{ marginBottom: 12 }}>
            <Text strong>📈 适用场景</Text>
            <Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 13 }}>{info.scenario}</Paragraph>
          </div>
          <div style={{ marginBottom: 12 }}>
            <Text strong>🔍 因子选择理由</Text>
            {info.factors.map((f, i) => (
              <div key={i} style={{ marginTop: 8, paddingLeft: 8, borderLeft: '3px solid #1677ff' }}>
                <Text strong style={{ fontSize: 13 }}>{f.name}</Text>
                <Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>{f.role}</Paragraph>
              </div>
            ))}
          </div>
          <div>
            <Text strong>⚖️ 阈值与权重设定理由</Text>
            <Paragraph type="secondary" style={{ fontSize: 12, lineHeight: 1.8, whiteSpace: 'pre-wrap' }}>{info.calibration}</Paragraph>
          </div>
        </div>
      }
      trigger="hover"
      placement="rightTop"
      getPopupContainer={() => document.body}
      styles={{ root: { zIndex: 99999 } }}
    >
      <InfoCircleOutlined style={{ color: '#1677ff', cursor: 'pointer', marginLeft: 4 }} />
    </Popover>
  );
}

/* ── 展开行：买入价建议 + 止盈止损 + 风险提示 ─────────────────────── */
function ExpandedRow({ record }) {
  const { techLevels, valuationLevels, risks, buyReason, riskLevel } = record;

  return (
    <div style={{ padding: '8px 16px', background: '#fafbfc' }}>
      <Row gutter={24}>
        {/* 买入理由 */}
        <Col span={24} style={{ marginBottom: 8 }}>
          <Text strong style={{ color: '#1677ff' }}>
            <ThunderboltOutlined /> 买入理由：
          </Text>
          <Text>{buyReason || '暂无'}</Text>
        </Col>

        {/* 风险提示 */}
        <Col span={24} style={{ marginBottom: 12 }}>
          <Space wrap>
            <Tag color={riskColor(riskLevel)} style={{ fontSize: 12, padding: '2px 8px' }}>
              <WarningOutlined /> {riskLabel(riskLevel)}
            </Tag>
            {risks && risks.map((r, i) => (
              <Tag key={i} color="warning" style={{ fontSize: 11, maxWidth: 400, whiteSpace: 'normal' }}>
                {r}
              </Tag>
            ))}
          </Space>
        </Col>

        {/* 技术支撑位 */}
        <Col span={12}>
          <Card size="small" title={<Space><LineChartOutlined /> 技术支撑位</Space>} style={{ margin: 0 }}>
            <table style={{ width: '100%', fontSize: 12 }}>
              <tbody>
                {techLevels && Object.entries(techLevels).map(([k, v]) => (
                  <tr key={k}>
                    <td style={{ padding: '2px 8px', color: '#8c8c8c' }}>
                      {k === 'suggestTechPrice' ? '综合技术面价格' :
                       k === 'MA5' ? 'MA5' : k === 'MA10' ? 'MA10' :
                       k === 'MA20' ? 'MA20' : k === 'bollLower' ? '布林带下轨' :
                       k === 'low20' ? '近20日最低' : k === 'low60' ? '近60日最低' :
                       k === 'atrStop' ? 'ATR止损位' : k === 'atr14' ? 'ATR(14)' : k}
                    </td>
                    <td style={{ padding: '2px 8px', textAlign: 'right', fontWeight: k === 'suggestTechPrice' ? 600 : 400 }}>
                      {fmt(v)} 元
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </Col>

        {/* 估值支撑位 */}
        <Col span={12}>
          <Card size="small" title={<Space><FundOutlined /> 估值支撑位</Space>} style={{ margin: 0 }}>
            <table style={{ width: '100%', fontSize: 12 }}>
              <tbody>
                {valuationLevels && Object.entries(valuationLevels).map(([k, v]) => {
                  if (v == null) return null;
                  const label = k === 'suggestValuationPrice' ? '综合估值面价格' :
                    k === 'bpsPrice' ? 'BPS支撑价' : k === 'pbPrice' ? 'PB估值支撑' :
                    k === 'pePrice' ? 'PE估值支撑' : k === 'bps' ? '每股净资产' :
                    k === 'eps' ? '每股收益' : k === 'industry' ? '所属行业' :
                    k === 'industryPbMedian' ? '行业PB中位数' :
                    k === 'industryPeMedian' ? '行业PE中位数' : k;
                  return (
                    <tr key={k}>
                      <td style={{ padding: '2px 8px', color: '#8c8c8c' }}>{label}</td>
                      <td style={{ padding: '2px 8px', textAlign: 'right', fontWeight: k === 'suggestValuationPrice' ? 600 : 400 }}>
                        {typeof v === 'number' ? fmt(v) + ' 元' : String(v)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
        </Col>
      </Row>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════ */
export default function StockScreen() {
  const { message } = App.useApp();
  /* ── 可用因子 ──────────────────────────────────────────────────── */
  const [availableFactors, setAvailableFactors] = useState([]);
  const [loadingFactors, setLoadingFactors] = useState(false);

  /* ── 预设组合 ──────────────────────────────────────────────────── */
  const [presets, setPresets] = useState([]);
  const [selectedPresetId, setSelectedPresetId] = useState(null);
  const pendingRestorePresetName = useRef(null);
  const [saveModalVisible, setSaveModalVisible] = useState(false);
  const [saveForm] = Form.useForm();

  /* ── 因子配置列表 ─────────────────────────────────────────────── */
  const [factors, setFactors] = useState([]);

  /* ── 全局处理配置 ─────────────────────────────────────────────── */
  const [globalOutlier, setGlobalOutlier] = useState('MAD');
  const [globalNormalize, setGlobalNormalize] = useState('ZSCORE');
  const [orthogonalMethod, setOrthogonalMethod] = useState('NONE');

  /* ── 选股参数 ─────────────────────────────────────────────────── */
  const [screenDate, setScreenDate] = useState(null);
  const [screenDateRange, setScreenDateRange] = useState(null); // [startDate, endDate] 多日平均模式
  const [useMultiDayMode, setUseMultiDayMode] = useState(false); // 是否使用多日平均模式
  const [topN, setTopN] = useState(10);
  const [direction, setDirection] = useState('LONG');
  const [excludeSt, setExcludeSt] = useState(true);
  const [valuationWeight, setValuationWeight] = useState(40);
  const [customSqlWhere, setCustomSqlWhere] = useState('');
  const [maAbove30, setMaAbove30] = useState(false);
  const [maAbove60, setMaAbove60] = useState(false);
  const [maAbove100, setMaAbove100] = useState(false);

  /* ── 结果 ─────────────────────────────────────────────────────── */
  const [result, setResult] = useState(null);
  const [running, setRunning] = useState(false);
  const [resultPageSize, setResultPageSize] = useState(20);
  const [backtestModalVisible, setBacktestModalVisible] = useState(false);

  /* ── 左侧面板折叠 ────────────────────────────────────────────── */
  const [collapsed, setCollapsed] = useState(false);

  /* ── 左侧面板展开/折叠的过渡样式 ───────────────────────────────── */

  /* ── 加载可用因子 + 预设组合 + 最新日期 ───────────────────────── */
  useEffect(() => {
    setLoadingFactors(true);
    api.get('/screen/factors')
      .then(res => setAvailableFactors(res || []))
      .catch(() => {})
      .finally(() => setLoadingFactors(false));

    api.get('/screen/latest-date')
      .then(res => { if (res) setScreenDate(dayjs(res)); })
      .catch(() => { setScreenDate(dayjs('2024-12-31')); });

    api.get('/screen/presets')
      .then(res => setPresets(res || []))
      .catch(() => {});

    // 检查是否有从权重优化页面传来的配置
    const savedConfig = localStorage.getItem('factorWeightConfig');
    if (savedConfig) {
      try {
        const config = JSON.parse(savedConfig);
        // 清除配置，避免重复加载
        localStorage.removeItem('factorWeightConfig');
        
        // 应用配置
        if (config.factors && config.factors.length > 0) {
          setFactors(config.factors.map(f => ({
            factorCode: f.code,
            direction: 1,
            weight: f.weight / 100, // 百分比转小数
            filterOp: 'NONE',
            filterValue: null,
            outlierMethod: null,
            normalizeMethod: null,
          })));
          message.success(`已加载「${config.name}」的因子权重配置`);
        }
      } catch (e) {
        console.error('解析权重配置失败:', e);
      }
    }
  }, []);

  /* ── 从回测报告页跳转回填配置 ─────────────────────────────────── */
  const [searchParams, setSearchParams] = useSearchParams();
  useEffect(() => {
    const restoreParam = searchParams.get('__restore');
    console.log('[restore] __restore 参数:', restoreParam ? '有值(长度' + restoreParam.length + ')' : '无/空');
    if (!restoreParam) return;

    try {
      const decoded = decodeURIComponent(atob(restoreParam));
      const config = JSON.parse(decoded);
      console.log('[restore] 解析后 config keys:', Object.keys(config), 'presetName:', config.presetName);

      // 清除 URL 参数（避免刷新重复回填）
      setSearchParams({}, { replace: true });

      // 回填因子列表
      if (Array.isArray(config.factors) && config.factors.length > 0) {
        const factorMap = {};
        availableFactors.forEach(af => { factorMap[af.factorCode] = af; });
        const restoredFactors = config.factors.map(f => {
          const base = factorMap[f.factorCode] || { factorCode: f.factorCode };
          return {
            factorCode: f.factorCode,
            name: base.name || f.factorCode,
            direction: f.direction ?? 1,
            weight: f.weight ?? 1,
            filterOp: f.filterOp || 'NONE',
            filterValue: f.filterValue != null ? f.filterValue : null,
            outlierMethod: f.outlierMethod || null,
            normalizeMethod: f.normalizeMethod || null,
          };
        });
        setFactors(restoredFactors);
      }

      // 回填基本参数
      if (config.direction) setDirection(config.direction);
      if (config.topN != null) setTopN(config.topN);
      if (config.excludeSt !== undefined) setExcludeSt(config.excludeSt);
      if (config.globalOutlierMethod) setGlobalOutlier(config.globalOutlierMethod);
      if (config.globalNormalizeMethod) setGlobalNormalize(config.globalNormalizeMethod);
      if (config.orthogonalizationMethod) setOrthogonalMethod(config.orthogonalizationMethod);
      if (config.valuationWeight != null) setValuationWeight(Math.round(config.valuationWeight * 100));
      if (config.customSqlWhere) setCustomSqlWhere(config.customSqlWhere);

      // 回填选股日期
      if (config.screenStartDate && config.screenEndDate) {
        setUseMultiDayMode(true);
        setScreenDateRange([dayjs(config.screenStartDate), dayjs(config.screenEndDate)]);
      } else if (config.screenDate) {
        setUseMultiDayMode(false);
        setScreenDate(dayjs(config.screenDate));
      }

      // 回填 MA 过滤
      if (config.maPositionFilter) {
        setMaAbove30(!!config.maPositionFilter.aboveMA30);
        setMaAbove60(!!config.maPositionFilter.aboveMA60);
        setMaAbove100(!!config.maPositionFilter.aboveMA100);
      }

      // 回填策略组合名称（presets 可能还没加载完，先存到 ref，等 presets 就绪后再匹配）
      if (config.presetName) {
        console.log('[restore] 收到 presetName:', config.presetName, '当前presets数量:', presets.length);
        pendingRestorePresetName.current = config.presetName;
        // 立即尝试匹配一次（presets 已加载时）
        const matched = presets.find(p => p.presetName === config.presetName);
        if (matched) {
          setSelectedPresetId(matched.id);
          pendingRestorePresetName.current = null;
        }
      }

      message.success('已从回测报告自动回填策略配置');
    } catch (e) {
      console.error('回填配置失败:', e);
      message.error('回填配置解析失败');
      setSearchParams({}, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams.get('__restore')]);

  /* ── presets 加载后延迟匹配策略组合回填 ───────────────────── */
  useEffect(() => {
    if (!pendingRestorePresetName.current || presets.length === 0) return;
    const name = pendingRestorePresetName.current;
    console.log('[restore-preset] 尝试匹配 presetName:', name, '当前presets:', presets.map(p => ({ id: p.id, name: p.presetName })));
    const matched = presets.find(p => p.presetName === name);
    console.log('[restore-preset] 匹配结果:', matched);
    if (matched) {
      setSelectedPresetId(matched.id);
      pendingRestorePresetName.current = null;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presets]);

  /* ── 选择预设组合 ─────────────────────────────────────────────── */
  const handlePresetSelect = useCallback((presetId) => {
    setSelectedPresetId(presetId);
    if (!presetId) {
      setFactors([]);
      return;
    }
    const preset = presets.find(p => p.id === presetId);
    if (!preset || !preset.factorConfig) return;
    try {
      const config = JSON.parse(preset.factorConfig);
      setFactors(config.map(c => ({
        factorCode: c.factorCode,
        direction: c.direction ?? 1,
        weight: c.weight ?? 1,
        filterOp: c.filterOp || 'NONE',
        filterValue: c.filterValue ?? null,
        outlierMethod: null,
        normalizeMethod: null,
      })));
      message.success(`已加载「${preset.presetName}」组合`);
    } catch (e) {
      message.error('预设组合解析失败，请检查数据格式');
    }
  }, [presets]);

  /* ── 复制预设为自定义 ─────────────────────────────────────────── */
  const handleCopyPreset = useCallback(() => {
    if (!selectedPresetId) { message.warning('请先选择一个预设组合'); return; }
    const preset = presets.find(p => p.id === selectedPresetId);
    if (!preset) return;
    saveForm.setFieldsValue({
      presetName: `${preset.presetName}（副本）`,
      description: preset.description || '',
    });
    setSaveModalVisible(true);
  }, [selectedPresetId, presets, saveForm]);

  /* ── 保存自定义组合 ───────────────────────────────────────────── */
  const handleSavePreset = useCallback(() => {
    if (factors.length === 0) { message.warning('请至少添加一个因子'); return; }
    saveForm.validateFields().then(values => {
      const payload = {
        presetName: values.presetName,
        description: values.description || '',
        factorConfig: JSON.stringify(factors.map(f => ({
          factorCode: f.factorCode,
          direction: f.direction,
          weight: f.weight,
          filterOp: f.filterOp,
          filterValue: f.filterOp !== 'NONE' ? f.filterValue : null,
        }))),
      };
      api.post('/screen/presets', payload)
        .then(res => {
          message.success('组合已保存');
          setSaveModalVisible(false);
          saveForm.resetFields();
          // 刷新预设列表
          return api.get('/screen/presets');
        })
        .then(res => { if (res) setPresets(res || []); })
        .catch(() => {});
    });
  }, [factors, saveForm]);

  /* ── 删除自定义组合 ───────────────────────────────────────────── */
  const handleDeletePreset = useCallback((id) => {
    Modal.confirm({
      title: '确认删除',
      content: '确定要删除这个自定义组合吗？',
      okType: 'danger',
      onOk: () => {
        api.delete(`/screen/presets/${id}`)
          .then(() => {
            message.success('已删除');
            if (selectedPresetId === id) {
              setSelectedPresetId(null);
              setFactors([]);
            }
            return api.get('/screen/presets');
          })
          .then(res => { if (res) setPresets(res || []); })
          .catch(() => {});
      },
    });
  }, [selectedPresetId]);

  /* ── 因子增删改 ───────────────────────────────────────────────── */
  const addFactor = () => {
    const used = new Set(factors.map(f => f.factorCode));
    const next = availableFactors.find(f => !used.has(f.factorCode));
    if (!next) { message.info('所有可用因子已添加'); return; }
    setFactors(prev => [...prev, DEFAULT_FACTOR(next.factorCode)]);
  };

  const removeFactor = (idx) =>
    setFactors(prev => prev.filter((_, i) => i !== idx));

  const updateFactor = (idx, patch) =>
    setFactors(prev => prev.map((f, i) => i === idx ? { ...f, ...patch } : f));

  const autoWeight = () => {
    if (factors.length === 0) return;
    const avg = +(1 / factors.length).toFixed(4);
    setFactors(prev => prev.map(f => ({ ...f, weight: avg })));
  };

  const totalWeight = useMemo(() =>
    factors.reduce((s, f) => s + Math.abs(f.weight || 0), 0), [factors]);

  /* ── 执行选股 ─────────────────────────────────────────────────── */
  const handleRun = useCallback(() => {
    if (factors.length === 0) { message.warning('请至少添加一个因子'); return; }
    if (totalWeight === 0)    { message.warning('因子权重合计不能为0'); return; }

    setRunning(true);
    setResult(null);

    const payload = {
      // 多日平均模式：传 screenStartDate + screenEndDate；单日模式：传 screenDate
      ...(useMultiDayMode && screenDateRange?.[0] && screenDateRange?.[1]
        ? {
            screenStartDate: screenDateRange[0].format('YYYY-MM-DD'),
            screenEndDate:   screenDateRange[1].format('YYYY-MM-DD'),
          }
        : { screenDate: screenDate ? screenDate.format('YYYY-MM-DD') : null }),
      globalOutlierMethod: globalOutlier,
      globalNormalizeMethod: globalNormalize,
      orthogonalizationMethod: orthogonalMethod,
      topN,
      direction,
      excludeSt,
      valuationWeight: valuationWeight / 100,
      customSqlWhere: customSqlWhere || null,
      presetId: selectedPresetId || null,
      maPositionFilter: (maAbove30 || maAbove60 || maAbove100) ? {
        aboveMA30:  maAbove30  || null,
        aboveMA60:  maAbove60  || null,
        aboveMA100: maAbove100 || null,
      } : null,
      factors: factors.map(f => ({
        factorCode: f.factorCode,
        direction: f.direction,
        weight: f.weight,
        filterOp: f.filterOp,
        filterValue: f.filterOp !== 'NONE' ? f.filterValue : null,
        outlierMethod: f.outlierMethod || null,
        normalizeMethod: f.normalizeMethod || null,
      })),
    };

    api.post('/screen/run', payload, { timeout: 180000 })
      .then(res => {
        setResult(res);
        setCollapsed(true);
        message.success(`选股完成，共选出 ${res?.stocks?.length ?? 0} 只股票`);
      })
      .catch((err) => {
        if (err.message && !err.response) {
          message.error('请求超时或网络异常，请稍后重试（MA均线过滤计算量较大，可能需要2-3分钟）');
        }
      })
      .finally(() => setRunning(false));
  }, [factors, screenDate, screenDateRange, useMultiDayMode, topN, direction, excludeSt, globalOutlier, globalNormalize, orthogonalMethod, totalWeight, valuationWeight, selectedPresetId, customSqlWhere, maAbove30, maAbove60, maAbove100]);

  /* ── 构建当前选股配置（供回测弹窗使用） ──────────────────────── */
  const buildScreenRequest = useCallback(() => ({
    ...(useMultiDayMode && screenDateRange?.[0] && screenDateRange?.[1]
      ? {
          screenStartDate: screenDateRange[0].format('YYYY-MM-DD'),
          screenEndDate:   screenDateRange[1].format('YYYY-MM-DD'),
        }
      : { screenDate: screenDate ? screenDate.format('YYYY-MM-DD') : null }),
    globalOutlierMethod: globalOutlier,
    globalNormalizeMethod: globalNormalize,
    orthogonalizationMethod: orthogonalMethod,
    topN,
    direction,
    excludeSt,
    valuationWeight: valuationWeight / 100,
    customSqlWhere: customSqlWhere || null,
    presetId: selectedPresetId || null,
    presetName: selectedPresetId
      ? (presets.find(p => p.id === selectedPresetId)?.presetName || null)
      : null,
    maPositionFilter: (maAbove30 || maAbove60 || maAbove100) ? {
      aboveMA30:  maAbove30  || null,
      aboveMA60:  maAbove60  || null,
      aboveMA100: maAbove100 || null,
    } : null,
    factors: factors.map(f => ({
      factorCode: f.factorCode,
      direction: f.direction,
      weight: f.weight,
      filterOp: f.filterOp,
      filterValue: f.filterOp !== 'NONE' ? f.filterValue : null,
      outlierMethod: f.outlierMethod || null,
      normalizeMethod: f.normalizeMethod || null,
    })),
  }), [factors, screenDate, screenDateRange, useMultiDayMode, topN, direction, excludeSt, globalOutlier, globalNormalize, orthogonalMethod, totalWeight, valuationWeight, selectedPresetId, customSqlWhere, maAbove30, maAbove60, maAbove100]);

  /* ── 结果表格列 ───────────────────────────────────────────────── */
  const isMultiDay = !!(result?.screenStartDate && result?.screenEndDate);
  const factorColumns = useMemo(() => (result?.factors || []).map(fw => ({
    title: (
      <Tooltip title={`权重: ${fw.weight}  方向: ${fw.direction >= 0 ? '正向' : '反向'}`}>
        <span>{fw.factorCode} <InfoCircleOutlined style={{ fontSize: 11, color: '#aaa' }} /></span>
      </Tooltip>
    ),
    key: fw.factorCode,
    width: isMultiDay ? 120 : 100,
    align: 'center',
    render: (_, row) => {
      const rank = row.factorRanks?.[fw.factorCode];
      const val = row.factorValues?.[fw.factorCode];
      const trend = row.factorTrends?.[fw.factorCode]; // 多日模式才有
      if (rank == null) {
        // 多日模式下 rank 为空通常是因为 CV 稳定性过滤
        const hint = isMultiDay ? '因子值波动过大(CV过滤)，已从该因子排名排除' : '暂无因子数据';
        if (val != null) {
          // 有原始值但无排名 → CV 过滤（保留原始值展示）
          return (
            <Tooltip title={`${hint}｜原始值: ${Number(val).toFixed(4)}`}>
              <Text type="secondary">{Number(val).toFixed(2)}</Text>
            </Tooltip>
          );
        }
        return <Tooltip title={hint}><Text type="secondary">-</Text></Tooltip>;
      }
      const pct = Math.min(100, Math.max(0, Math.round(rank * 100)));
      // 趋势高亮逻辑：|trend| > 0.3 认为变化显著
      const absTrend = trend != null ? Math.abs(trend) : 0;
      const trendSignificant = absTrend > 0.3;
      let trendColor = '#888';
      if (trendSignificant) trendColor = trend > 0 ? '#52c41a' : '#ff4d4f';
      return (
        <div>
          <Tooltip title={`原始值: ${val != null ? Number(val).toFixed(4) : '-'}`}>
            <Progress
              percent={pct}
              size="small"
              strokeColor={pct >= 70 ? '#52c41a' : pct >= 40 ? '#1677ff' : '#ff4d4f'}
              format={p => `${p}%`}
            />
          </Tooltip>
          {trend != null && isMultiDay && (
            <Tooltip title={`趋势动量: ${(trend * 100).toFixed(1)}% (${trend > 0 ? '改善' : '恶化'})`}>
              <span style={{
                fontSize: 10, color: trendColor,
                fontWeight: trendSignificant ? 600 : 400,
                display: 'inline-block',
                background: trendSignificant ? (trend > 0 ? '#f6ffed' : '#fff2f0') : 'transparent',
                padding: '0 3px', borderRadius: 3, marginTop: 2
              }}>
                {trend > 0 ? '↑' : '↓'}{Math.abs(trend * 100).toFixed(1)}%
              </span>
            </Tooltip>
          )}
        </div>
      );
    },
  })), [result, isMultiDay]);

  const resultColumns = useMemo(() => [
    {
      title: '排名', dataIndex: 'rank', key: 'rank', width: 55, align: 'center', fixed: 'left',
      render: v => <Tag color={v <= 3 ? 'red' : v <= 10 ? 'orange' : 'default'} style={{ minWidth: 28, textAlign: 'center' }}>{v}</Tag>,
    },
    {
      title: '股票代码', dataIndex: 'symbol', key: 'symbol', width: 110, fixed: 'left',
      render: v => <Tag color="geekblue">{v}</Tag>,
    },
    { title: '股票名称', dataIndex: 'name', key: 'name', width: 90, ellipsis: true, fixed: 'left' },
    {
      title: '综合得分', dataIndex: 'compositeScore', key: 'score', width: 110,
      sorter: (a, b) => b.compositeScore - a.compositeScore,
      render: v => (
        <Progress
          percent={Math.round(v * 100)}
          size="small"
          strokeColor={scoreColor(v)}
          style={{ width: 70 }}
        />
      ),
    },
    {
      title: '当前价', dataIndex: 'currentPrice', key: 'currentPrice', width: 75, align: 'right',
      render: v => v != null ? <Text strong>¥{fmt(v)}</Text> : <Text type="secondary">-</Text>,
    },
    {
      title: (
        <Tooltip title="综合估值面(40%)和技术面(60%)计算的建议买入区间">
          <Space size={4}><SafetyCertificateOutlined /> 建议买入价</Space>
        </Tooltip>
      ),
      key: 'suggestPrice', width: 140, align: 'center',
      render: (_, row) => {
        if (!row.suggestPrice) return <Text type="secondary">-</Text>;
        const low = row.suggestPriceLow ?? row.suggestPrice;
        const high = row.suggestPriceHigh ?? row.suggestPrice;
        const deviation = row.currentPrice
          ? ((row.currentPrice - row.suggestPrice) / row.currentPrice * 100).toFixed(1)
          : null;
        return (
          <Tooltip title={deviation != null ? `距当前价偏离: ${Number(deviation) >= 0 ? '+' : ''}${deviation}%` : ''}>
            <div>
              <Text strong style={{ color: '#1677ff', fontSize: 13 }}>{fmt(row.suggestPrice)}</Text>
              <br />
              <Text type="secondary" style={{ fontSize: 11 }}>{fmt(low)} ~ {fmt(high)}</Text>
            </div>
          </Tooltip>
        );
      },
    },
    {
      title: (
        <Tooltip title="基于 ATR(14) 计算：止损=当前价-2×ATR，第一止盈=+2×ATR，第二止盈=+3×ATR">
          <Space size={4}><WarningOutlined /> 止损</Space>
        </Tooltip>
      ),
      key: 'stopLoss', width: 75, align: 'center',
      render: (_, row) => {
        if (!row.stopLoss) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`止损幅度: ${fmtPct(row.stopLossPercent)}`}>
            <Text type="danger">{fmt(row.stopLoss)}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: '止盈1', key: 'tp1', width: 75, align: 'center',
      render: (_, row) => {
        if (!row.takeProfit1) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`止盈幅度: ${fmtPct(row.takeProfit1Percent)}`}>
            <Text type="success">{fmt(row.takeProfit1)}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: '止盈2', key: 'tp2', width: 75, align: 'center',
      render: (_, row) => {
        if (!row.takeProfit2) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`止盈幅度: ${fmtPct(row.takeProfit2Percent)}`}>
            <Text style={{ color: '#52c41a' }}>{fmt(row.takeProfit2)}</Text>
          </Tooltip>
        );
      },
    },
    {
      title: (
        <span>
          风险
          <Tooltip
            title={
              <div style={{ maxWidth: 360, lineHeight: 1.7 }}>
                <div style={{ fontWeight: 600, marginBottom: 6 }}>风险等级判定逻辑</div>
                <div>基于<strong>波动率(σ)</strong>和<strong>最大回撤</strong>两个维度，将候选股票分为低/中/高三档：</div>
                <div style={{ marginTop: 6 }}>
                  <Tag color="green" size="small">低风险</Tag> 年化波动率 &lt; 25% 且近20日最大回撤 &lt; 10%
                </div>
                <div style={{ marginTop: 4 }}>
                  <Tag color="orange" size="small">中风险</Tag> 年化波动率 25%~40% 或近20日最大回撤 10%~20%
                </div>
                <div style={{ marginTop: 4 }}>
                  <Tag color="red" size="small">高风险</Tag> 年化波动率 &gt; 40% 或近20日最大回撤 &gt; 20%
                </div>
                <Divider style={{ margin: '8px 0' }} />
                <div style={{ fontSize: 12, color: '#888' }}>
                  💡 高风险 ≠ 不能买。波动率高的股票弹性大，适合短线/波段策略；低风险适合稳健持仓。建议结合「止损」列和自身风险承受能力决策。
                </div>
              </div>
            }
          >
            <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4, fontSize: 12, cursor: 'pointer' }} />
          </Tooltip>
        </span>
      ),
      key: 'risk', width: 80, align: 'center',
      render: (_, row) => {
        if (!row.riskLevel) return <Text type="secondary">-</Text>;
        return <Tag color={riskColor(row.riskLevel)} style={{ fontSize: 11 }}>{riskLabel(row.riskLevel)}</Tag>;
      },
    },
    ...factorColumns,
  ], [result, factorColumns]);

  /* ── 内置/自定义预设分组 ─────────────────────────────────────── */
  const builtinPresets = presets.filter(p => p.isBuiltin === 1);
  const customPresets = presets.filter(p => p.isBuiltin !== 1);

  /* ── 大盘温度计提示 ──────────────────────── */
  const { data: thData, status: thStatus } = useMarketThermometer();

  /* ══════════════════════════════════════════════════════════════ */
  return (
    <div style={{ width: '100%' }}>
      {/* 页头 */}
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>因子策略</Title>
        <Space>
          <Badge
            color={totalWeight > 0 ? (Math.abs(totalWeight - 1) < 0.01 ? 'green' : 'orange') : 'red'}
            text={`权重合计: ${totalWeight.toFixed(2)}`}
          />
          {thData && thStatus && (
            <Tooltip title={`大盘${thStatus.label}（${thData.fearGreedIndex?.toFixed(0)}°），${thStatus.action}`}>
              <Tag color={thStatus.label === '极度贪婪' ? 'red' : thStatus.label === '极度恐慌' ? 'green' : 'blue'}>
                <Link to="/market-thermometer" style={{ color: 'inherit' }}>{thStatus.label} {thData.fearGreedIndex?.toFixed(0)}°</Link>
              </Tag>
            </Tooltip>
          )}
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            size="small"
            loading={running}
            onClick={handleRun}
            disabled={factors.length === 0}
          >
            {running ? '选股中' : '执行选股'}
          </Button>
        </Space>
      </div>

      <Tabs
        defaultActiveKey="multifactor"
        size="small"
        items={[
          {
            key: 'multifactor',
            label: <span><FilterOutlined /> 多因子选股</span>,
            children: (
      <div style={{ display: 'flex', gap: 16, width: '100%' }}>
        {/* ══ 左侧配置区（通过 width 过渡折叠）══ */}
        <div style={{
          flexShrink: 0,
          width: collapsed ? 0 : '54.17%',
          overflow: 'hidden',
          opacity: collapsed ? 0 : 1,
          transition: 'width 0.4s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.25s ease 0.15s',
          pointerEvents: collapsed ? 'none' : 'auto',
        }}>
          {/* ── 预设组合选择 ─────────────────────────────────────── */}
          <Card
            title={<Space><StarOutlined /> 策略组合</Space>}
            style={{ marginBottom: 16 }}
            styles={{ body: { padding: '12px 16px 4px' } }}
          >
            <Row gutter={[12, 12]} align="middle">
              <Col flex="auto">
                <Select
                  value={selectedPresetId}
                  onChange={handlePresetSelect}
                  style={{ width: '100%' }}
                  size="small"
                  placeholder="选择一个预设组合快速开始"
                  allowClear
                  popupMatchSelectWidth={false}
                >
                  {builtinPresets.length > 0 && (
                    <Select.OptGroup label="内置组合">
                      {builtinPresets.map(p => (
                        <Option key={p.id} value={p.id}>
                          <Space size={4}>
                            <span>{p.presetName}</span>
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              ({p.description || '—'})
                            </Text>
                            <PresetDescPopover presetName={p.presetName} />
                          </Space>
                        </Option>
                      ))}
                    </Select.OptGroup>
                  )}
                  {customPresets.length > 0 && (
                    <Select.OptGroup label="我的组合">
                      {customPresets.map(p => (
                        <Option key={p.id} value={p.id}>
                          <Space size={4}>
                            <span>{p.presetName}</span>
                            <Button
                              type="text" size="small" danger
                              icon={<DeleteOutlined />}
                              onClick={(e) => { e.stopPropagation(); handleDeletePreset(p.id); }}
                              style={{ fontSize: 10 }}
                            />
                          </Space>
                        </Option>
                      ))}
                    </Select.OptGroup>
                  )}
                </Select>
              </Col>
              <Col flex="none">
                <div style={paramLabelStyle}> </div>
                <Space size={4} style={{ height: 24, alignItems: 'center' }}>
                  <Tooltip title="复制当前选中的预设组合，修改后另存为新组合">
                    <Button
                      size="small" icon={<CopyOutlined />}
                      onClick={handleCopyPreset}
                      disabled={!selectedPresetId}
                      style={{ height: 24 }}
                    />
                  </Tooltip>
                  <Tooltip title="将当前因子配置保存为自定义组合">
                    <Button
                      size="small" icon={<SaveOutlined />}
                      onClick={() => setSaveModalVisible(true)}
                      disabled={factors.length === 0}
                      style={{ height: 24 }}
                    />
                  </Tooltip>
                </Space>
              </Col>
            </Row>
          </Card>

          {/* ── 因子选择表格 ─────────────────────────────────────── */}
          <Card
            title={<Space><FilterOutlined />因子选择</Space>}
            extra={
              <Space size="small">
                <Button size="small" icon={<SwapOutlined />} onClick={autoWeight}>均分权重</Button>
                <Button
                  size="small" type="primary" icon={<PlusOutlined />}
                  onClick={addFactor} disabled={loadingFactors || availableFactors.length === 0}
                >
                  添加因子
                </Button>
              </Space>
            }
            style={{ marginBottom: 16 }}
            styles={{ body: { padding: '0 0 8px' } }}
          >
            {factors.length === 0 ? (
              <Empty description='选择预设组合或点击"添加因子"开始配置' style={{ padding: '32px 0' }} />
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr style={{ background: '#fafafa', borderBottom: '1px solid #f0f0f0' }}>
                    <th style={{ ...thStyle, width: 110 }}>因子名称</th>
                    <th style={{ ...thStyle, width: 76 }}>
                      <Tooltip title="正向：值越大越好；反向：值越小越好">
                        方向 <QuestionCircleOutlined style={{ color: '#aaa' }} />
                      </Tooltip>
                    </th>
                    <th style={{ ...thStyle, width: 108 }}>筛选条件</th>
                    <th style={{ ...thStyle, width: 72 }}>阈值</th>
                    <th style={{ ...thStyle, width: 64 }}>权重</th>
                    <th style={{ ...thStyle, width: 32 }}></th>
                  </tr>
                </thead>
                <tbody>
                  {factors.map((f, idx) => {
                    const info = availableFactors.find(x => x.factorCode === f.factorCode);
                    return (
                      <tr key={idx} style={{ borderBottom: '1px solid #f5f5f5' }}>
                        <td style={tdStyle}>
                          <Tooltip title={info ? `${info.factorName}（${CATEGORY_LABEL[info.category] || info.category}）` : f.factorCode}>
                            <Select
                              value={f.factorCode}
                              size="small"
                              style={{ width: '100%' }}
                              loading={loadingFactors}
                              onChange={v => updateFactor(idx, { factorCode: v })}
                              optionLabelProp="label"
                            >
                              {availableFactors.map(af => (
                                <Option
                                  key={af.factorCode}
                                  value={af.factorCode}
                                  label={af.factorCode}
                                  disabled={factors.some((sf, si) => si !== idx && sf.factorCode === af.factorCode)}
                                >
                                  <Space size={4}>
                                    <Tag
                                      color={CATEGORY_COLOR[af.category] || 'default'}
                                      style={{ fontSize: 10, padding: '0 3px', margin: 0 }}
                                    >
                                      {CATEGORY_LABEL[af.category] || af.category}
                                    </Tag>
                                    <span>{af.factorCode}</span>
                                  </Space>
                                </Option>
                              ))}
                            </Select>
                          </Tooltip>
                          {info && (
                            <div style={{ fontSize: 10, color: '#999', marginTop: 2, lineHeight: '12px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                              {info.factorName}
                            </div>
                          )}
                        </td>
                        <td style={tdStyle}>
                          <Select
                            value={f.direction} size="small" style={{ width: 72 }}
                            onChange={v => updateFactor(idx, { direction: v })}
                            optionLabelProp="label"
                          >
                            <Option value={1} label={<span style={{ color: '#1677ff', fontWeight: 500 }}>↑ 正向</span>}>
                              <Space size={4}>
                                <Tag color="blue" style={{ margin: 0, fontSize: 11 }}>正向</Tag>
                                <span style={{ fontSize: 12, color: '#595959' }}>越大越好</span>
                              </Space>
                            </Option>
                            <Option value={-1} label={<span style={{ color: '#ff4d4f', fontWeight: 500 }}>↓ 反向</span>}>
                              <Space size={4}>
                                <Tag color="red" style={{ margin: 0, fontSize: 11 }}>反向</Tag>
                                <span style={{ fontSize: 12, color: '#595959' }}>越小越好</span>
                              </Space>
                            </Option>
                          </Select>
                        </td>
                        <td style={tdStyle}>
                          <Select
                            value={f.filterOp} size="small" style={{ width: 100 }}
                            onChange={v => updateFactor(idx, { filterOp: v, filterValue: null })}
                          >
                            {FILTER_OP_OPTIONS.map(o => (
                              <Option key={o.value} value={o.value}>{o.label}</Option>
                            ))}
                          </Select>
                        </td>
                        <td style={tdStyle}>
                          <InputNumber
                            value={f.filterValue} size="small" style={{ width: 68 }}
                            disabled={f.filterOp === 'NONE'}
                            onChange={v => updateFactor(idx, { filterValue: v })}
                            placeholder="阈值"
                          />
                        </td>
                        <td style={tdStyle}>
                          <InputNumber
                            value={f.weight} size="small"
                            min={0} max={10} step={0.1}
                            style={{ width: 58 }}
                            onChange={v => updateFactor(idx, { weight: v ?? 1 })}
                          />
                        </td>
                        <td style={{ ...tdStyle, textAlign: 'center' }}>
                          <Button
                            type="text" size="small" danger icon={<DeleteOutlined />}
                            onClick={() => removeFactor(idx)}
                          />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </Card>

          {/* ── 选股参数 ─────────────────────────────────────────── */}
          <Card title="选股参数" style={{ marginBottom: 16 }} styles={{ body: { padding: '16px 16px 4px' } }}>
            {/* 第一行：选股日期（独立） */}
            <Row style={{ marginBottom: 12 }}>
              <Col span={24}>
                <div style={paramLabelStyle}>选股日期</div>
                <Space size={8}>
                  <Button
                    size="small"
                    type={!useMultiDayMode ? 'primary' : 'default'}
                    onClick={() => { setUseMultiDayMode(false); }}
                  >单日</Button>
                  <Button
                    size="small"
                    type={useMultiDayMode ? 'primary' : 'default'}
                    onClick={() => { setUseMultiDayMode(true); }}
                  >多日</Button>
                  <Tooltip
                    title={
                      <div style={{ width: 480, lineHeight: 1.8 }}>
                        <div style={{ fontWeight: 600, marginBottom: 4 }}>单日模式（默认）</div>
                        <div>取<strong>某一天</strong>的因子值快照做截面筛选。</div>
                        <div style={{ color: '#52c41a' }}>✓ 信号灵敏，能捕捉短期异动</div>
                        <div style={{ color: '#ff4d4f' }}>✗ 受单日噪声/异常值影响大</div>
                        <Divider style={{ margin: '10px 0' }} />

                        <div style={{ fontWeight: 600, marginBottom: 4 }}>多日模式（最新值 + 稳定性过滤）</div>
                        <div>取范围内<strong>最新一天</strong>的因子值做筛选，同时计算该范围内的<strong>变异系数(CV)</strong>，CV 过高说明因子波动剧烈、不稳定，予以剔除。</div>
                        <div style={{ color: '#52c41a' }}>✓ 保留单日灵敏度 + 过滤噪声票</div>
                        <div style={{ color: '#ff4d4f' }}>✗ 强势异动股（如连续涨停）CV 也高，可能被误杀</div>
                        <Divider style={{ margin: '10px 0' }} />

                        <div style={{ fontWeight: 600, marginBottom: 4 }}>📈 趋势动量（Trend）— 仅多日模式显示</div>
                        <div>在结果表每个因子的进度条下方，额外展示该因子在选定时间范围内的<strong>变化方向与幅度</strong>。</div>
                        <div style={{ background: '#fafafa', padding: '6px 8px', borderRadius: 4, marginTop: 4 }}>
                          <div><strong>公式：</strong>trend = (结束日因子值 - 起始日因子值) / |起始日因子值|</div>
                          <div><strong>含义：</strong>正值表示因子在该区间内走强（如 RSI 从 40→65），负值表示走弱。</div>
                          <div><strong>高亮：</strong>|trend| &gt; 30% 时彩色标注 —— 绿色↑=持续改善，红色↓=持续恶化；灰色=平稳无大幅波动。</div>
                          <div><strong>作用：</strong>帮助判断该股票的因子得分是"趋势性改善"还是"偶然跳升"，辅助区分真假信号。例如一只股票 PE 因子排名靠前但 trend 为红色↓，说明它在靠估值回归临时上位，持续性存疑。</div>
                        </div>
                        <Divider style={{ margin: '10px 0' }} />

                        <div style={{ fontSize: 12, color: '#888', background: '#fafafa', padding: '6px 8px', borderRadius: 4 }}>
                          💡 建议：日常快速选股用「单日」；市场波动剧烈或验证策略时切「多日」
                        </div>
                      </div>
                    }
                    placement="bottom"
                    styles={{ root: { maxWidth: 530 } }}
                  >
                    <QuestionCircleOutlined style={{ cursor: 'pointer', color: '#999', fontSize: 14 }} />
                  </Tooltip>
                  {!useMultiDayMode ? (
                    <DatePicker
                      value={screenDate} onChange={(d) => { setScreenDate(d); }}
                      placeholder="最新可用"
                      style={{ width: 240 }} size="small"
                    />
                  ) : (
                    <DatePicker.RangePicker
                      value={screenDateRange}
                      onChange={(dates) => { setScreenDateRange(dates); }}
                      placeholder={['开始日期', '结束日期']}
                      style={{ width: 360 }} size="small"
                    />
                  )}
                </Space>
              </Col>
            </Row>

            {/* 第二行：持仓数量 | 极值处理 */}
            <Row gutter={[12, 12]}>
              <Col span={12}>
                <div style={paramLabelStyle}>持仓数量</div>
                <InputNumber
                  value={topN} onChange={v => setTopN(v ?? 10)}
                  min={5} max={500} style={{ width: '100%' }} size="small"
                  addonAfter="只"
                />
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  极值处理
                  <Tooltip title="对每个因子的截面数据先做极值压缩，再做标准化">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={globalOutlier} onChange={setGlobalOutlier} style={{ width: '100%' }} size="small">
                  {OUTLIER_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 第三行：选股方向 | 标准化处理 */}
              <Col span={12}>
                <div style={paramLabelStyle}>选股方向</div>
                <Select value={direction} onChange={setDirection} style={{ width: '100%' }} size="small">
                  <Option value="LONG">做多（高分优先）</Option>
                  <Option value="SHORT">做空（低分优先）</Option>
                </Select>
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  标准化处理
                  <Tooltip title="将极值处理后的因子值映射到统一量纲，再按权重合成综合得分">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={globalNormalize} onChange={setGlobalNormalize} style={{ width: '100%' }} size="small">
                  {NORMALIZE_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 第四行：剔除ST股（独占或可扩展） */}
              <Col span={12}>
                <div style={paramLabelStyle}>剔除ST股</div>
                <Button
                  size="small"
                  type={excludeSt ? 'primary' : 'default'}
                  onClick={() => setExcludeSt(!excludeSt)}
                  style={{ width: '100%' }}
                >
                  {excludeSt ? '✓ 剔除' : '不剔除'}
                </Button>
              </Col>
              <Col span={12}>
                <div style={paramLabelStyle}>
                  因子正交化
                  <Tooltip title="对标准化后的因子值做正交化处理，消除因子间共线性（如动量类因子MOM5/MOM10/MOM20高度相关），使综合得分更均衡">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Select value={orthogonalMethod} onChange={setOrthogonalMethod} style={{ width: '100%' }} size="small">
                  {ORTHOGONAL_OPTIONS.map(o => <Option key={o.value} value={o.value}>{o.label}</Option>)}
                </Select>
              </Col>

              {/* 自定义 SQL 条件（高级模式） */}
              <Col span={24}>
                <div style={paramLabelStyle}>
                  <Space size={4}>
                    <ThunderboltOutlined />
                    <span>自定义 SQL 条件（高级模式）</span>
                    <Tag color="purple" size="small">进阶</Tag>
                  </Space>
                  <Tooltip title="直接用 SQL WHERE 条件过滤候选股票池，适用于有数据库经验的用户。条件将作用于 stock_daily 表，可使用 close、volume、turnover、change_percent 等字段。仅支持 AND 连接的条件">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <TextArea
                  value={customSqlWhere}
                  onChange={e => setCustomSqlWhere(e.target.value)}
                  placeholder={'例如: close > 10 AND volume > 1000000 AND change_percent > -5\n\n可用字段: code, open, high, low, close, volume, turnover, change_percent, change_amount'}
                  autoSize={{ minRows: 2, maxRows: 4 }}
                  allowClear
                  style={{ fontSize: 12, fontFamily: 'monospace' }}
                />
              </Col>

              {/* MA 均线位置过滤 */}
              <Col span={24}>
                <div style={paramLabelStyle}>
                  <Space size={4}>
                    <RiseOutlined />
                    <span>均线位置过滤</span>
                    <Tag color="blue" size="small">多头</Tag>
                  </Space>
                  <Tooltip title="要求当前价格在指定均线上方，过滤掉处于下降趋势的股票。MA30≈1.5月、MA60≈3月、MA100≈5月">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Space size={8} wrap>
                  <Button
                    size="small"
                    type={maAbove30 ? 'primary' : 'default'}
                    onClick={() => setMaAbove30(v => !v)}
                    style={{ minWidth: 72 }}
                  >
                    {maAbove30 ? '✓ ' : ''}价格 &gt; MA30
                  </Button>
                  <Button
                    size="small"
                    type={maAbove60 ? 'primary' : 'default'}
                    onClick={() => setMaAbove60(v => !v)}
                    style={{ minWidth: 72 }}
                  >
                    {maAbove60 ? '✓ ' : ''}价格 &gt; MA60
                  </Button>
                  <Button
                    size="small"
                    type={maAbove100 ? 'primary' : 'default'}
                    onClick={() => setMaAbove100(v => !v)}
                    style={{ minWidth: 72 }}
                  >
                    {maAbove100 ? '✓ ' : ''}价格 &gt; MA100
                  </Button>
                </Space>
                {(maAbove30 || maAbove60 || maAbove100) && (
                  <div style={{ marginTop: 4, fontSize: 11, color: '#faad14' }}>
                    ⚠ 均线过滤会对每只候选股票单独加载历史行情，候选池较大时耗时会增加
                  </div>
                )}
              </Col>

              {/* 估值/技术加权比例 */}
              <Col span={24}>
                <div style={paramLabelStyle}>
                  买入价加权比例
                  <Tooltip title="控制估值面和技术面在建议买入价计算中的权重分配">
                    <QuestionCircleOutlined style={{ color: '#bbb', marginLeft: 4 }} />
                  </Tooltip>
                </div>
                <Row gutter={8} align="middle">
                  <Col span={4}><Text style={{ fontSize: 12 }}>估值 {(valuationWeight)}%</Text></Col>
                  <Col span={16}>
                    <Slider
                      min={0} max={100} value={valuationWeight}
                      onChange={setValuationWeight}
                      marks={{ 0: '纯技术', 40: '均衡', 100: '纯估值' }}
                      tooltip={{ formatter: v => `估值 ${v}% / 技术 ${100 - v}%` }}
                    />
                  </Col>
                  <Col span={4}><Text style={{ fontSize: 12, textAlign: 'right' }}>技术 {(100 - valuationWeight)}%</Text></Col>
                </Row>
              </Col>
            </Row>
          </Card>
        </div>

        {/* ══ 右侧结果区 ════════════════════════════════════════════ */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {running && (
            <Card>
              <div style={{ textAlign: 'center', padding: '80px 0' }}>
                <Spin size="large" tip="正在执行多因子选股...">
                  <div />
                </Spin>
              </div>
            </Card>
          )}

          {!running && !result && (
            <Card>
              <Empty
                description={
                  <span>选择策略组合或配置因子，点击 <Text strong>执行选股</Text> 获取结果</span>
                }
                style={{ padding: '80px 0' }}
              />
            </Card>
          )}

          {!running && result && (
            <>
              {/* 汇总统计 */}
              <Card style={{ marginBottom: 16 }}>
                <Row gutter={16} align="middle">
                  <Col span={6}>
                    <Statistic
                      title="选股日期"
                      value={result.screenStartDate ? `${result.screenStartDate} ~ ${result.screenEndDate}` : result.screenDate}
                      valueStyle={{ fontSize: 13 }}
                    />
                  </Col>
                  <Col span={6}>
                    <Statistic title="候选股票" value={result.candidateCount} suffix="只" />
                  </Col>
                  <Col span={6}>
                    <Statistic
                      title="选出数量" value={result.stocks?.length ?? 0} suffix="只"
                      valueStyle={{ color: '#1677ff' }}
                    />
                  </Col>
                  <Col span={6}>
                    <Statistic title="使用因子" value={result.factors?.length ?? 0} suffix="个" />
                  </Col>
                </Row>

                {(result.factorFilterPass || result.factorCoverage) && (
                  <>
                    <Divider style={{ margin: '12px 0' }} />
                    {result.factorFilterPass && Object.keys(result.factorFilterPass).length > 0 && (
                      <div style={{ marginBottom: result.factorCoverage ? 6 : 0 }}>
                        <span style={{ fontSize: 12, color:'#888', marginRight: 8 }}>筛选通过：</span>
                        <Row gutter={[8, 6]} wrap>
                          {Object.entries(result.factorFilterPass).map(([code, cnt]) => (
                            <Col key={`fp-${code}`}>
                              <Tooltip title={`${code}：${cnt} 只股票通过筛选条件`}>
                                <Tag color={cnt > 500 ? 'red' : cnt > 100 ? 'orange' : cnt > 20 ? 'green' : 'blue'}>
                                  {code}: {cnt} 只
                                </Tag>
                              </Tooltip>
                            </Col>
                          ))}
                        </Row>
                      </div>
                    )}
                    {result.factorCoverage && Object.keys(result.factorCoverage).length > 0 && (
                      <div>
                        <span style={{ fontSize: 12, color:'#aaa', marginRight: 8 }}>数据覆盖：</span>
                        <Row gutter={[8, 4]} wrap>
                          {Object.entries(result.factorCoverage).map(([code, cnt]) => (
                            <Col key={`cov-${code}`}>
                              <Tooltip title={`${code}：${cnt} 只股票有因子数据`}>
                                <span style={{ fontSize: 11, color: '#bbb', cursor: 'default' }}>
                                  {code}({cnt})
                                </span>
                              </Tooltip>
                            </Col>
                          ))}
                        </Row>
                      </div>
                    )}
                  </>
                )}
              </Card>

              {/* 结果表格 */}
              <Card
                title={
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 }}>
                    <span style={{ marginRight: 4 }}>筛选结果</span>
                    <Tag color="blue" style={{ marginRight: 0 }}>{direction === 'LONG' ? '做多' : '做空'}</Tag>
                    <Tag color="purple">{OUTLIER_OPTIONS.find(o => o.value === globalOutlier)?.label}</Tag>
                    <Tag color="cyan">{NORMALIZE_OPTIONS.find(o => o.value === globalNormalize)?.label}</Tag>
                    {orthogonalMethod !== 'NONE' && (
                      <Tag color="magenta">{ORTHOGONAL_OPTIONS.find(o => o.value === orthogonalMethod)?.label}</Tag>
                    )}
                  </div>
                }
                extra={
                  <Space size="small">
                    {result && (
                      <Button type="primary" size="small" icon={<PlayCircleOutlined />}
                        onClick={() => setBacktestModalVisible(true)}>
                        滚动回测
                      </Button>
                    )}
                    <Button
                      size="small"
                      icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                      onClick={() => setCollapsed(c => !c)}
                    >
                      {collapsed ? '展开参数' : '折叠参数'}
                    </Button>
                    <Button size="small" icon={<ReloadOutlined />} onClick={handleRun} loading={running}>
                      刷新
                    </Button>
                  </Space>
                }
              >
                <Table
                  dataSource={result.stocks}
                  columns={resultColumns}
                  rowKey="symbol"
                  size="small"
                  scroll={{ x: 800 + (result.factors?.length ?? 0) * 100 }}
                  pagination={{
                    pageSize: resultPageSize,
                    showSizeChanger: true,
                    pageSizeOptions: ['10', '20', '50', '100'],
                    showTotal: t => `共 ${t} 只`,
                    onChange: (page, size) => setResultPageSize(size),
                  }}
                  rowClassName={(_, i) => i < 3 ? 'top10-row' : ''}
                  expandable={{
                    expandedRowRender: (record) => <ExpandedRow record={record} />,
                    rowExpandable: (record) => !!record.suggestPrice,
                    expandIcon: ({ expanded, onExpand, record }) =>
                      record.suggestPrice ? (
                        expanded
                          ? <MinusSquareOutlined style={{ color: '#1677ff', fontSize: 16, cursor: 'pointer' }} onClick={e => onExpand(record, e)} />
                          : <PlusSquareOutlined style={{ color: '#8c8c8c', fontSize: 16, cursor: 'pointer' }} onClick={e => onExpand(record, e)} />
                      ) : null,
                  }}
                />
              </Card>
            </>
          )}
        </div>
      </div>
            ),
          },
          {
            key: 'chan',
            label: <span><StockOutlined /> 缠论结构筛选</span>,
            children: <ChanScreenTab />,
          },
        ]}
      />

      {/* ── 保存组合弹窗 ─────────────────────────────────────────── */}
      <Modal
        title="保存策略组合"
        open={saveModalVisible}
        forceRender
        onOk={handleSavePreset}
        onCancel={() => { setSaveModalVisible(false); saveForm.resetFields(); }}
        okText="保存"
        cancelText="取消"
      >
        <Form form={saveForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="presetName" label="组合名称" rules={[{ required: true, message: '请输入组合名称' }]}>
            <Input placeholder="如：我的成长组合" maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label="组合描述">
            <TextArea placeholder="简要描述这个组合的投资逻辑" rows={3} maxLength={500} />
          </Form.Item>
        </Form>
        <Alert
          type="info" showIcon
          message={`当前包含 ${factors.length} 个因子，权重合计 ${totalWeight.toFixed(2)}`}
          style={{ marginTop: 8 }}
        />
      </Modal>

      {/* ── 滚动选股回测弹窗 ─────────────────────────────────────── */}
      <RollingBacktestModal
        visible={backtestModalVisible}
        onClose={() => setBacktestModalVisible(false)}
        screenConfig={buildScreenRequest()}
      />
    </div>
  );
}

/* ── 样式常量 ──────────────────────────────────────────────────────── */
const thStyle = {
  padding: '9px 10px',
  fontWeight: 500,
  fontSize: 12,
  color: '#595959',
  textAlign: 'left',
  whiteSpace: 'nowrap',
};
const tdStyle = {
  padding: '9px 10px',
  verticalAlign: 'middle',
};
const paramLabelStyle = {
  fontSize: 12,
  color: '#595959',
  marginBottom: 4,
  display: 'flex',
  alignItems: 'center',
};

/* ── 缠论筛选辅助函数（从 ChanScreen.js 迁移）─────────────────────── */
const chanFmtVal = (v, prec = 2) => {
  if (v == null) return '-';
  const n = Number(v);
  if (Number.isNaN(n)) return v;
  return n.toFixed(prec);
};
const chanPenDirTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  return n > 0
    ? <Tag color="red">▲ 上升</Tag>
    : <Tag color="green">▼ 下降</Tag>;
};
const chanTrendTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  if (n === 1)  return <Tag color="red">  上涨</Tag>;
  if (n === 0)  return <Tag color="blue"> 盘整</Tag>;
  return <Tag color="green">下跌</Tag>;
};
const chanBuySellTag = (v) => {
  if (v == null) return '-';
  const n = Number(v);
  if (n > 0) return <Tag color="volcano">{n}买</Tag>;
  if (n < 0) return <Tag color="cyan">  {Math.abs(n)}卖</Tag>;
  return '-';
};
const getChanTagRenderer = (code) => {
  const c = code.toUpperCase();
  if (c === 'CHAN_PEN_DIR')   return chanPenDirTag;
  if (c === 'CHAN_TREND')     return chanTrendTag;
  if (c === 'CHAN_BUY_SELL')  return chanBuySellTag;
  return (v) => v == null ? '-' : <Text>{chanFmtVal(v, 3)}</Text>;
};

/* ── 缠论筛选 Tab 组件 ─────────────────────────────────────────────── */
function ChanScreenTab() {
  const [meta, setMeta]                 = useState(null);
  const [metaLoading, setMetaLoading]   = useState(true);
  const [checkboxFilters, setCheckboxFilters] = useState({});
  const [rangeFilters, setRangeFilters]       = useState({});
  const [keyword, setKeyword]                 = useState('');
  const [page, setPage]                       = useState(1);
  const [pageSize, setPageSize]               = useState(20);
  const [loading, setLoading]   = useState(false);
  const [data, setData]         = useState(null);
  const [error, setError]       = useState(null);
  const [helpVisible, setHelpVisible] = useState(false);

  useEffect(() => {
    factorApi.chanScreenMeta()
      .then(res => {
        setMeta(res);
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
      .catch(() => message.error('加载缠论因子元数据失败，请稍后重试'))
      .finally(() => setMetaLoading(false));
  }, []);

  const buildChanParams = useCallback((newPage, newPageSize) => {
    const params = {};
    Object.entries(checkboxFilters).forEach(([code, vals]) => {
      if (vals && vals.length > 0) {
        const p = code.toUpperCase();
        if (p === 'CHAN_PEN_DIR')   params.penDir   = vals.join(',');
        if (p === 'CHAN_TREND')     params.trend    = vals.join(',');
        if (p === 'CHAN_BUY_SELL')  params.buySell  = vals.join(',');
      }
    });
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

  const doChanSearch = useCallback((newPage = 1, newPageSize = pageSize) => {
    setLoading(true);
    setError(null);
    setPage(newPage);
    setPageSize(newPageSize);
    const params = buildChanParams(newPage, newPageSize);
    factorApi.chanScreen(params)
      .then(res => setData(res))
      .catch(e => setError(e.message || '筛选失败'))
      .finally(() => setLoading(false));
  }, [buildChanParams, pageSize]);

  const buildChanColumns = useCallback(() => {
    if (!meta || !meta.columns) return [];
    const baseCols = [
      { title: '代码', dataIndex: 'ts_code', key: 'ts_code', width: 100,
        render: v => <Text strong copyable={{ text: v }}>{v}</Text> },
      { title: '名称', dataIndex: 'name', key: 'name', width: 90, ellipsis: true },
      { title: '数据日期', dataIndex: 'calc_date', key: 'date', width: 110 },
    ];
    const factorCols = (meta.columns || []).map(col => ({
      title: col.title, dataIndex: col.dataIndex, key: col.key, width: 90,
      render: (val, row) => { const tagFn = getChanTagRenderer(col.key); return tagFn(row[col.dataIndex]); },
    }));
    return [...baseCols, ...factorCols];
  }, [meta]);

  const renderChanFilterControls = () => {
    if (!meta || !meta.factors) return null;
    return meta.factors.map(factor => {
      if (factor.controlType === 'checkbox') {
        return (
          <Col span={12} key={factor.code}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{factor.name}</Text>
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

      {metaLoading ? (
        <Card size="small" style={{ marginBottom: 16 }}><Spin tip="加载因子定义..."><div /></Spin></Card>
      ) : (
        <>
          <Card size="small" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 12]}>
              {renderChanFilterControls()}
              <Col span={12}>
                <Space>
                  <Input placeholder="搜索代码或名称关键词" value={keyword}
                    onChange={e => setKeyword(e.target.value)} onPressEnter={() => doChanSearch(1)}
                    style={{ width: 220 }} allowClear />
                  <Button type="primary" icon={<FilterOutlined />} onClick={() => doChanSearch(1)} loading={loading}>筛选</Button>
                  <Button icon={<ReloadOutlined />} onClick={() => {
                    const defaults = {}; const cbDefaults = {};
                    (meta?.factors || []).forEach(f => {
                      if (f.controlType === 'slider') defaults[f.code] = [f.min ?? 0, f.max ?? (f.code.toUpperCase() === 'CHAN_HUB_POS' ? 1 : 100)];
                      else cbDefaults[f.code] = [];
                    });
                    setCheckboxFilters(cbDefaults); setRangeFilters(defaults); setKeyword(''); setData(null);
                  }}>重置</Button>
                </Space>
              </Col>
            </Row>
          </Card>

          <Card title={`筛选结果（共 ${total} 只）`} size="small">
            <Spin spinning={loading}>
              {!data && !error ? (
                <Empty description="请设置筛选条件后点击「筛选」" />
              ) : error ? (
                <Text type="danger">{error}</Text>
              ) : (
                <Table dataSource={list} columns={buildChanColumns()} rowKey="ts_code" size="small"
                  pagination={{
                    current: page, pageSize: pageSize, total: total,
                    showSizeChanger: true, pageSizeOptions: ['10', '20', '50'],
                    onChange: (p, ps) => doChanSearch(p, ps),
                  }}
                  scroll={{ x: 700 }} />
              )}
            </Spin>
          </Card>
        </>
      )}

      <Modal title="缠论结构筛选 · 使用说明" open={helpVisible} onCancel={() => setHelpVisible(false)}
        footer={null} width={900}>
        <div style={{ fontSize: 13, lineHeight: 1.8 }}>
          <Alert type="info" showIcon style={{ marginBottom: 16 }} message="这是做什么的"
            description="缠论结构筛选是基于缠论理论，从全市场股票中筛选出处于特定「结构状态」的标的。
            比如：当前有哪些股票刚出现「1买」买点？哪些处于「上涨走势+中枢上半区」？
            省去逐个翻图分析的时间，直接给出符合条件的股票清单。" />
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
          <Alert type="warning" showIcon style={{ marginTop: 8 }} message="增减缠论因子的影响"
            description="缠论筛选页面已改为动态化，新增/删除/重命名缠论因子后自动生效，无需修改代码。
            但新增因子需要在因子计算引擎中实现计算逻辑，且在「因子管理」中激活后才会出现在筛选中。" />
        </div>
      </Modal>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════ */
