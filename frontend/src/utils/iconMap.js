import * as Icons from '@ant-design/icons';
import React from 'react';

// 图标白名单：菜单管理处可选的所有 AntD 图标
// 需要新图标直接在此数组追加英文名即可
const ICON_NAMES = [
  // 导航/大盘
  'DashboardOutlined', 'FundOutlined', 'FundViewOutlined', 'StockOutlined',
  'BarChartOutlined', 'LineChartOutlined', 'AreaChartOutlined', 'PieChartOutlined',
  'RadarChartOutlined', 'FallOutlined', 'RiseOutlined', 'DotChartOutlined',
  // 布局/内容
  'MenuOutlined', 'AppstoreOutlined', 'TableOutlined', 'UnorderedListOutlined',
  'OrderedListOutlined', 'FileTextOutlined', 'FileOutlined', 'FolderOutlined',
  'FolderOpenOutlined', 'CalendarOutlined', 'ClockOutlined', 'BellOutlined',
  'MailOutlined', 'MessageOutlined', 'CommentOutlined', 'BookOutlined',
  'ReadOutlined', 'ProfileOutlined', 'ContainerOutlined', 'InboxOutlined',
  // 操作
  'PlusOutlined', 'EditOutlined', 'DeleteOutlined', 'SearchOutlined',
  'FilterOutlined', 'SortAscendingOutlined', 'SortDescendingOutlined',
  'ReloadOutlined', 'SyncOutlined', 'UndoOutlined', 'RedoOutlined',
  'CheckOutlined', 'CloseOutlined', 'CheckCircleOutlined', 'CloseCircleOutlined',
  'ExclamationCircleOutlined', 'InfoCircleOutlined', 'QuestionCircleOutlined',
  'EyeOutlined', 'EyeInvisibleOutlined', 'CopyOutlined', 'ScissorOutlined',
  'PushpinOutlined', 'ToTopOutlined', 'VerticalAlignTopOutlined', 'DownloadOutlined',
  'UploadOutlined', 'ImportOutlined', 'ExportOutlined', 'SaveOutlined',
  'PaperClipOutlined', 'PrinterOutlined', 'ShareAltOutlined', 'SendOutlined',
  // 用户/权限
  'UserOutlined', 'TeamOutlined', 'UserAddOutlined', 'UserDeleteOutlined',
  'UserSwitchOutlined', 'SafetyOutlined', 'SecurityScanOutlined', 'LockOutlined',
  'KeyOutlined', 'IdcardOutlined', 'ContactsOutlined',
  // 数据/系统
  'DatabaseOutlined', 'CloudOutlined', 'CloudServerOutlined', 'CloudDownloadOutlined',
  'SettingOutlined', 'ToolOutlined', 'ControlOutlined', 'PartitionOutlined',
  'ApartmentOutlined', 'GlobalOutlined', 'LinkOutlined', 'TagOutlined', 'TagsOutlined',
  'FlagOutlined', 'EnvironmentOutlined', 'HomeOutlined', 'ShopOutlined',
  // 金融/业务
  'AccountBookOutlined', 'WalletOutlined', 'BankOutlined', 'TransactionOutlined',
  'RedEnvelopeOutlined', 'CreditCardOutlined', 'PayCircleOutlined',
  'DollarOutlined', 'PoundCircleOutlined', 'MoneyCollectOutlined',
  // 特色/装饰
  'BulbOutlined', 'FireOutlined', 'ThunderboltOutlined', 'RocketOutlined',
  'TrophyOutlined', 'StarOutlined', 'HeartOutlined', 'LikeOutlined',
  'DislikeOutlined', 'SmileOutlined', 'FrownOutlined', 'MehOutlined',
  'NotificationOutlined', 'SoundOutlined', 'PlayCircleOutlined', 'PauseCircleOutlined',
  // 箭头
  'ArrowUpOutlined', 'ArrowDownOutlined', 'ArrowLeftOutlined', 'ArrowRightOutlined',
  'UpOutlined', 'DownOutlined', 'LeftOutlined', 'RightOutlined',
  'SwapOutlined', 'SwapRightOutlined', 'RetweetOutlined',
  // 其他页面已用
  'MenuFoldOutlined', 'MenuUnfoldOutlined', 'MoonOutlined', 'SunOutlined',
  'LogoutOutlined', 'SearchOutlined', 'SafetyOutlined', 'TeamOutlined',
];

// 菜单图标映射（后端菜单 icon 字段 -> AntD 图标组件）
export const ICON_MAP = {};
ICON_NAMES.forEach((name) => {
  const Comp = Icons[name];
  if (Comp) {
    ICON_MAP[name] = React.createElement(Comp);
  }
});

// 图标选择器下拉选项：只显示图标，不显示英文
export const ICON_OPTIONS = Object.keys(ICON_MAP).map((name) => ({
  value: name,
  label: (
    <span
      title={name}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 28,
        height: 24,
      }}
    >
      {ICON_MAP[name]}
    </span>
  ),
}));

// 前端组件可选项（对应 src/pages 下相对路径）
export const COMPONENT_OPTIONS = [
  { value: '', label: '无（目录/按钮）' },
  { value: 'Dashboard', label: 'Dashboard（总览）' },
  { value: 'Market/MarketList', label: 'Market/MarketList（行情数据）' },
  { value: 'Analysis/MarketThermometer', label: 'Analysis/MarketThermometer（大盘温度计）' },
  { value: 'Analysis/StockAnalysis', label: 'Analysis/StockAnalysis（个股分析）' },
  { value: 'Factors/FactorList', label: 'Factors/FactorList（因子列表）' },
  { value: 'Factors/FactorMonitor', label: 'Factors/FactorMonitor（因子计算）' },
  { value: 'Factors/FactorCorrelation', label: 'Factors/FactorCorrelation（因子相关性）' },
  { value: 'Factors/FactorWeightOptimize', label: 'Factors/FactorWeightOptimize（权重优化）' },
  { value: 'Factors/FactorIcIrAnalysis', label: 'Factors/FactorIcIrAnalysis（IC管理）' },
  { value: 'Strategies/StrategyList', label: 'Strategies/StrategyList（策略列表）' },
  { value: 'Backtest/BacktestList', label: 'Backtest/BacktestList（回测列表）' },
  { value: 'Backtest/BacktestCompare', label: 'Backtest/BacktestCompare（策略对比）' },
  { value: 'Backtest/ParamOptimize', label: 'Backtest/ParamOptimize（参数优化）' },
  { value: 'Backtest/WalkForward', label: 'Backtest/WalkForward（Walk-Forward验证）' },
  { value: 'Strategies/PaperTradingPage', label: 'Strategies/PaperTradingPage（模拟盘）' },
  { value: 'Screen/StockScreen', label: 'Screen/StockScreen（因子选股）' },
  { value: 'recommendation/RecommendationList', label: 'recommendation/RecommendationList（智能推荐）' },
  { value: 'llm/LlmAnalysisPage', label: 'llm/LlmAnalysisPage（AI推理分析）' },
  { value: 'monitor/MonitorPage', label: 'monitor/MonitorPage（盘中监控）' },
  { value: 'calendar/TradeCalendar', label: 'calendar/TradeCalendar（交易日历）' },
  { value: 'dataupdate/DataUpdate', label: 'dataupdate/DataUpdate（数据更新）' },
  { value: 'financial/FinancialData', label: 'financial/FinancialData（财务数据）' },
  { value: 'datadetail/ResearchData', label: 'datadetail/ResearchData（研报数据）' },
  { value: 'market/SectorRanking', label: 'market/SectorRanking（行业排行）' },
  { value: 'dataupdate/ScheduledTasks', label: 'dataupdate/ScheduledTasks（定时任务）' },
  { value: 'dataupdate/DataQualityDashboard', label: 'dataupdate/DataQualityDashboard（质量监控）' },
  { value: 'dataupdate/TaskRunHistory', label: 'dataupdate/TaskRunHistory（任务监控）' },
  { value: 'Manual/ManualFullPage', label: 'Manual/ManualFullPage（使用手册）' },
  { value: 'System/UserManage', label: 'System/UserManage（用户管理）' },
  { value: 'System/RoleManage', label: 'System/RoleManage（角色管理）' },
  { value: 'System/MenuManage', label: 'System/MenuManage（菜单管理）' },
];
