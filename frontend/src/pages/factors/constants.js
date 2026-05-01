// 因子分类常量，供因子列表/因子编辑器等页面共用
export const CATEGORY_OPTIONS = [
  'MOMENTUM','VALUE','QUALITY','VOLATILITY',
  'TECHNICAL','FUNDAMENTAL','SENTIMENT','CHANTHEORY','CUSTOM',
];

export const CATEGORY_LABELS = {
  MOMENTUM:    '动量',
  VALUE:       '价值',
  QUALITY:     '质量',
  VOLATILITY:  '波动率',
  TECHNICAL:   '技术',
  FUNDAMENTAL: '基本面',
  SENTIMENT:   '情绪',
  CHANTHEORY:  '缠论',
  CUSTOM:      '自定义',
};

// 显示用：中文 + 编码，如 "动量 MOMENTUM"
export const CATEGORY_DISPLAY = CATEGORY_OPTIONS.map(k => ({
  value: k,
  label: `${CATEGORY_LABELS[k]} ${k}`,
}));
