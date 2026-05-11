// 因子分类常量，供因子列表/因子编辑器等页面共用
export const CATEGORY_OPTIONS = [
  'MOMENTUM','VALUE','QUALITY','VOLATILITY',
  'TECHNICAL','FINANCIAL','SENTIMENT','CHANTHEORY',
  'LIQUIDITY','VOLUME_PRICE','CUSTOM',
];

export const CATEGORY_LABELS = {
  MOMENTUM:    '动量',
  VALUE:       '价值',
  QUALITY:     '质量',
  VOLATILITY:  '波动率',
  TECHNICAL:   '技术',
  FINANCIAL:   '财务',
  SENTIMENT:   '情绪',
  CHANTHEORY:  '缠论',
  LIQUIDITY:   '流动性',
  VOLUME_PRICE: '量价',
  CUSTOM:      '自定义',
};

// 显示用：中文 + 编码，如 "动量 MOMENTUM"
export const CATEGORY_DISPLAY = CATEGORY_OPTIONS.map(k => ({
  value: k,
  label: `${CATEGORY_LABELS[k]} ${k}`,
}));
