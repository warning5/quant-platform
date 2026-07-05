/**
 * 价格格式化 - 保留两位小数
 */
export function formatPrice(val) {
  if (val == null || isNaN(val)) return '--';
  return Number(val).toFixed(2);
}

/**
 * 百分比格式化
 * @param {number} val - 百分比值 (如 2.35 表示 +2.35%)
 * @param {boolean} withSign - 是否带正负号
 */
export function formatPercent(val, withSign = true) {
  if (val == null || isNaN(val)) return '--';
  const num = Number(val);
  const sign = withSign && num > 0 ? '+' : '';
  return sign + num.toFixed(2) + '%';
}

/**
 * 日期格式化 YYYY-MM-DD
 */
export function formatDate(dateStr) {
  if (!dateStr) return '--';
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return dateStr;
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * 市值格式化 - 亿/万亿
 */
export function formatMarketCap(val) {
  if (val == null || isNaN(val)) return '--';
  const num = Number(val);
  if (num >= 1e12) return (num / 1e12).toFixed(2) + '万亿';
  if (num >= 1e8) return (num / 1e8).toFixed(2) + '亿';
  if (num >= 1e4) return (num / 1e4).toFixed(2) + '万';
  return num.toFixed(0);
}

/**
 * 仓位百分比 0~1 -> 0%~100%
 */
export function formatPosition(val) {
  if (val == null || isNaN(val)) return '--';
  return (Number(val) * 100).toFixed(1) + '%';
}

/**
 * 得分百分比 0~1 -> 0~100
 */
export function formatScore(val) {
  if (val == null || isNaN(val)) return '--';
  return (Number(val) * 100).toFixed(0);
}

/**
 * 涨跌颜色 - 中国股市：红涨绿跌
 */
export function priceColor(val) {
  if (val == null || isNaN(val)) return '';
  const num = Number(val);
  if (num > 0) return 'text-red';
  if (num < 0) return 'text-green';
  return '';
}

/**
 * Action 标签中文
 */
export function actionTagText(tag) {
  const map = { BUY: '买入', HOLD: '持有', SELL: '卖出' };
  return map[tag] || tag || '--';
}

/**
 * Action 标签样式
 */
export function actionTagClass(tag) {
  const map = {
    BUY: 'tag-buy',
    HOLD: 'tag-hold',
    SELL: 'tag-sell'
  };
  return map[tag] || 'tag-hold';
}

/**
 * Regime 中文
 */
export function regimeText(regime) {
  const map = { BULL: '牛市', BEAR: '熊市', SIDEWAYS: '震荡' };
  return map[regime] || regime || '--';
}

/**
 * 置信度等级中文
 */
export function confidenceText(level) {
  const map = { HIGH: '高置信', NORMAL: '中等', LOW: '偏低', SUSPENDED: '建议暂停', UNTRAINED: '待训练' };
  return map[level] || level || '--';
}

/**
 * 维度等级中文（与后端 buildDimensionReason 一致）
 */
export function levelText(level) {
  return level || '';
}

/**
 * 维度等级 -> 颜色 class
 */
export function levelClass(level) {
  if (level === '强' || level === '较强') return 'level-strong';
  if (level === '弱' || level === '较弱') return 'level-weak';
  return 'level-mid';
}

/**
 * 解析后端拼接的 buyReason 字符串为结构化字段
 * 实际格式（后端 buildConclusion 生成）：
 *   【股票名(代码)】综合评分53分，建议【持有】，建议仓位30%。
 *   技术面：较弱（趋势状态盘整，MACD综合红柱扩张）；资金面：较弱（主力净流入-134.09万，...）；
 *   事件面：一般（炸板率0.0%...）；基本面：强（ROE7.66%，...）；注意：若跌破34.80...
 *
 * 返回：
 *   {
 *     summary: '【股票名(代码)】综合评分53分，建议【持有】，建议仓位30%。',
 *     dimensions: [
 *       { name: '技术面', level: '较弱', content: '趋势状态盘整，MACD综合红柱扩张' },
 *       ...
 *     ],
 *     risk: '若跌破34.80（近20日低点），考虑减仓'
 *   }
 */
export function parseBuyReason(text) {
  if (!text) return { summary: '', dimensions: [], risk: '' };
  const str = String(text);

  // 1) 摘要：开头【...】+ 综合评分 + 建议 + 建议仓位 + 句末的 。
  //    一直匹配到第一个维度名出现（技术面/资金面/事件面/基本面/风险面/流动性面）为止
  const dimPattern = /(技术面|资金面|事件面|基本面|风险面|流动性面)/;
  const dimMatch = str.match(dimPattern);
  let summary = '';
  let rest = str;
  if (str.startsWith('【')) {
    if (dimMatch) {
      summary = str.slice(0, dimMatch.index).trim();
      rest = str.slice(dimMatch.index);
    } else {
      // 找不到维度名 - 整段作为 summary
      summary = str.trim();
      rest = '';
    }
  }

  // 2) 维度段：rest 里用分号切分（兼容英文 ; 和中文 ；）
  const segments = rest
    ? rest.split(/[；;]/).map((s) => s.trim()).filter(Boolean)
    : [];

  const result = { summary, dimensions: [], risk: '' };

  for (const seg of segments) {
    // 注意：xxx
    if (seg.startsWith('注意')) {
      result.risk = seg.replace(/^注意[：:]/, '').trim();
      continue;
    }

    // 维度名：等级（内容） 或 维度名：内容
    const dm = seg.match(/^(技术面|资金面|事件面|基本面|风险面|流动性面)[：:](.*)$/);
    if (!dm) continue;
    const name = dm[1];
    const after = dm[2].trim();

    // 尝试匹配 "等级（内容）" 或 "等级(xxx)"
    const m = after.match(/^(强|较强|一般|较弱|弱)[（(]([^）)]*)[）)]/);
    if (m) {
      result.dimensions.push({ name, level: m[1], content: m[2].trim() });
    } else if (after) {
      result.dimensions.push({ name, level: '', content: after });
    }
  }

  return result;
}
