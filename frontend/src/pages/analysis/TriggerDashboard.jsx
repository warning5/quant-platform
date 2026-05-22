import React from 'react';
import { Card, Tag } from 'antd';
import { FireOutlined } from '@ant-design/icons';

/**
 * 触发器观察台 v2 — 借鉴圆桌报告风格
 *
 * 2列布局：左=变量名+当前值，右=多段触发链（阈值→结论；阈值→结论）
 * 混合技术/资金/估值/事件四类变量
 */
export function TriggerDashboard({ tech, money, sentiment, fundamental, tailRisks, catalysts, price, pe, pb }) {
  const rows = buildTriggerRows({ tech, money, sentiment, fundamental, tailRisks, catalysts, price, pe, pb });
  if (rows.length === 0) return null;

  return (
    <Card
      size="small"
      title={
        <span>
          <FireOutlined style={{ color: '#fa8c16', marginRight: 6 }} />
          触发器观察台
          <span style={{ fontSize: 12, color: '#999', fontWeight: 400, marginLeft: 8 }}>
            变量 · 当前 &nbsp;&nbsp;|&nbsp;&nbsp; 重新评估触发线
          </span>
        </span>
      }
      style={{ marginTop: 16 }}
    >
      {rows.map((r, i) => <TriggerRow key={i} row={r} />)}
    </Card>
  );
}

/**
 * 构建触发行 — 返回 [{ label, subLabel, chain: [{condition, text, color}] }]
 *
 * chain 是多段式：每个元素包含 condition(阈值描述)、text(结论)、color(颜色)
 */
function buildTriggerRows({ tech, money, sentiment, fundamental, tailRisks, catalysts, price, pe, pb }) {
  const list = [];

  // ════════════════════════════════
  // 1. 股价位置（距MA60 / 支撑阻力）
  // ════════════════════════════════
  if (tech?.nearHighPct != null || tech?.nearLowPct != null || tech?.ma5Value != null) {
    const highPct = tech.nearHighPct ? parseFloat(tech.nearHighPct) : null;
    const lowPct = tech.nearLowPct ? parseFloat(tech.nearLowPct) : null;
    const ma5 = tech.ma5Value ? parseFloat(tech.ma5Value) : null;
    let subText = '';
    if (price) subText = `当前 ${price}元`;
    else if (ma5) subText = `当前 MA5=${ma5.toFixed(2)}`;

    const chain = [];
    if (highPct !== null && highPct <= 3) {
      chain.push({ cond: `距前高${highPct}%`, text: '接近强阻力区，追高风险大', color: '#f5222d' });
    } else if (highPct !== null && highPct <= 10) {
      chain.push({ cond: `距前高${highPct}%`, text: '上方有套牢盘压力', color: '#fa8c16' });
    }
    if (lowPct !== null && lowPct <= 5) {
      chain.push({ cond: `距前低${lowPct}%`, text: '支撑位附近，可关注止跌信号', color: '#52c41a' });
    }
    if (chain.length === 0) {
      chain.push({
        cond: highPct !== null ? `距前高${highPct.toFixed(1)}%` : '中期区间',
        text: highPct !== null && highPct > 20 ? '远离关键位，趋势延续空间充足' : '运行在正常波动范围',
        color: '#52c41a'
      });
    }

    list.push({ label: '股价', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 2. PE(TTM) 估值
  // ════════════════════════════════
  if (fundamental?.peTtm != null || pe) {
    const peVal = parseFloat(fundamental?.peTtm || pe);
    if (peVal > 0 && peVal < 200) {
      const subText = `当前 ${peVal.toFixed(1)}x`;
      const chain = [];

      if (peVal >= 50) {
        chain.push({ cond: `${peVal.toFixed(1)}x`, text: '估值显著偏高，透支未来增长预期', color: '#f5222d' });
      } else if (peVal >= 35) {
        chain.push({ cond: `${peVal.toFixed(1)}x`, text: '估值偏高，需业绩增速匹配否则有回调压力', color: '#fa8c16' });
      } else if (peVal >= 15) {
        chain.push({ cond: `${peVal.toFixed(1)}x`, text: '估值处于合理区间', color: '#52c41a' });
      } else if (peVal > 0) {
        chain.push({ cond: `${peVal.toFixed(1)}x`, text: '低估值区域，可试探性关注', color: '#1890ff' });
      }

      if (peVal >= 80) {
        chain.push({ cond: '≥80x', text: '泡沫风险极高', color: '#f5222d' });
      } else if (peVal >= 50) {
        chain.push({ cond: '≥50x', text: '高成长溢价但安全垫薄', color: '#fa8c16' });
      } else if (peVal <= 10 && peVal > 0) {
        chain.push({ cond: '≤10x', text: '深度价值区间，适合左侧布局', color: '#1890ff' });
      }

      list.push({ label: 'PE(TTM)', subText, chain });
    }
  }

  // ════════════════════════════════
  // 3. PB 市净率
  // ════════════════════════════════
  if (fundamental?.pb != null || pb) {
    const pbVal = parseFloat(fundamental?.pb || pb);
    if (pbVal > 0) {
      const subText = `当前 ${pbVal.toFixed(2)}x`;
      const chain = [];

      if (pbVal >= 5) {
        chain.push({ cond: `${pbVal.toFixed(1)}x`, text: 'PB偏高，市场给予高成长溢价', color: '#fa8c16' });
        chain.push({ cond: '≥5x', text: '若业绩不及预期则面临双杀', color: '#f5222d' });
      } else if (pbVal >= 2.5) {
        chain.push({ cond: `${pbVal.toFixed(2)}x`, text: 'PB合理偏上', color: '#fa8c16' });
        chain.push({ cond: '≤2x', text: '回归至历史正常上轨区间', color: '#52c41a' });
      } else if (pbVal >= 1.5) {
        chain.push({ cond: `${pbVal.toFixed(2)}x`, text: 'PB处于历史中枢附近', color: '#52c41a' });
      } else if (pbVal >= 1.0) {
        chain.push({ cond: `${pbVal.toFixed(2)}x`, text: '低PB区间，估值有安全边际', color: '#1890ff' });
      } else {
        chain.push({ cond: `${pbVal.toFixed(2)}x`, text: '破净状态，要么极度低估要么有硬伤', color: '#f5222d' });
      }

      list.push({ label: 'PB', subLabel: subText, chain });
    }
  }

  // ════════════════════════════════
  // 4. RSI(14)
  // ════════════════════════════════
  if (tech?.rsi != null) {
    const rsi = parseFloat(tech.rsi);
    const subText = `${rsi.toFixed(1)}`;
    const chain = [];

    if (rsi >= 75) {
      chain.push({ cond: `≥75`, text: '超买区，短期回调概率高，不宜追高', color: '#f5222d' });
      chain.push({ cond: '回落至65', text: '超买缓解后可能重新企稳', color: '#52c41a' });
    } else if (rsi >= 60) {
      chain.push({ cond: `${rsi.toFixed(0)}`, text: '偏强但未极端，量能配合可继续持有', color: '#52c41a' });
      chain.push({ cond: '≥75', text: '进入超买区考虑减仓', color: '#fa8c16' });
      chain.push({ cond: '跌破50', text: '转弱信号，注意防守', color: '#f5222d' });
    } else if (rsi >= 40) {
      chain.push({ cond: `${rsi.toFixed(0)}`, text: '中性区域，等待方向选择', color: '#999' });
    } else if (rsi >= 30) {
      chain.push({ cond: `${rsi.toFixed(0)}`, text: '偏弱但未超卖，观望为主', color: '#fa8c16' });
      chain.push({ cond: '≤25', text: '进入超卖区，可能出现反弹', color: '#1890ff' });
    } else {
      chain.push({ cond: `${rsi.toFixed(0)}`, text: '超卖区，空头力量过度释放', color: '#1890ff' });
      chain.push({ cond: '回升至35', text: '超卖修复，确认反弹可跟进', color: '#52c41a' });
    }

    list.push({ label: 'RSI(14)', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 5. MACD柱 + 动能
  // ════════════════════════════════
  if (tech?.macdHistogram != null) {
    const macd = parseFloat(tech.macdHistogram);
    const prevMacd = tech.macdHistogramPrev != null ? parseFloat(tech.macdHistogramPrev) : null;
    const subText = macd.toFixed(4);

    const chain = [];
    if (macd > 0 && prevMacd != null && prevMacd <= 0) {
      chain.push({ cond: '翻红↑', text: '动能由空转多，金叉确认可加仓', color: '#52c41a' });
    } else if (macd < 0 && prevMacd != null && prevMacd >= 0) {
      chain.push({ cond: '翻绿↓', text: '动能衰竭转空，减仓避险', color: '#f5222d' });
    } else if (macd > 0) {
      chain.push({ cond: macd > prevMacd ? '红柱扩大' : '红柱收窄', text: macd > prevMacd ? '多头加速，趋势健康' : '多头衰减，注意背离风险', color: macd > prevMacd ? '#52c41a' : '#fa8c16' });
    } else {
      chain.push({ cond: Math.abs(macd) > Math.abs(prevMacd || 0) ? '绿柱加深' : '绿柱缩短', text: Math.abs(macd) > Math.abs(prevMacd || 0) ? '空头主导增强' : '抛压减弱，底部临近', color: Math.abs(macd) > Math.abs(prevMacd || 0) ? '#f5222d' : '#1890ff' });
    }

    list.push({ label: 'MACD动能', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 6. BOLL 位置
  // ════════════════════════════════
  if (tech?.bollPosition != null) {
    const bp = parseFloat(tech.bollPosition);
    const upper = tech.bollUpper ? parseFloat(tech.bollUpper) : null;
    const lower = tech.bollLower ? parseFloat(tech.bollLower) : null;
    const mid = tech.bollMid ? parseFloat(tech.bollMid) : null;

    const parts = [];
    if (upper && mid) parts.push(`中轨${mid.toFixed(2)}`);
    if (lower) parts.push(`下轨${lower.toFixed(2)}`);
    const subText = `${bp.toFixed(2)}${parts.length ? ` (${parts.join('/')})` : ''}`;

    const chain = [];
    if (bp >= 1.0) {
      chain.push({ cond: '突破上轨', text: '强势突破，短期或有回踩确认', color: '#fa8c16' });
      chain.push({ cond: '回落至上轨下方', text: '回踩不破中轨仍为健康调整', color: '#52c41a' });
    } else if (bp >= 0.7) {
      chain.push({ cond: '上轨附近', text: '接近布林带上沿压力', color: '#fa8c16' });
      chain.push({ cond: '放量突破', text: '打开上升空间', color: '#52c41a' });
    } else if (bp >= 0.3) {
      chain.push({ cond: '中轨偏上', text: '多头排列中的正常运行', color: '#52c41a' });
    } else if (bp >= 0) {
      chain.push({ cond: '中轨附近', text: '多空分界线，方向待选择', color: '#999' });
      chain.push({ cond: '站稳中轨', text: '多方占优', color: '#52c41a' });
      chain.push({ cond: '跌破中轨', text: '转弱信号', color: '#f5222d' });
    } else if (bp >= -0.3) {
      chain.push({ cond: '下轨附近', text: '弱势运行，接近支撑', color: '#fa8c16' });
      chain.push({ cond: '触及下轨反弹', text: '超跌修复信号', color: '#1890ff' });
    } else {
      chain.push({ cond: '跌破下轨', text: '极度弱势，远离均值或加速赶底', color: '#f5222d' });
      chain.push({ cond: '缩量回升至下轨上方', text: '探底成功', color: '#1890ff' });
    }

    list.push({ label: 'BOLL位置', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 7. 营收/净利润增速（基本面动态）
  // ════════════════════════════════
  if (fundamental?.revenueYoy != null || fundamental?.netProfitYoy != null) {
    const rev = fundamental.revenueYoy ? parseFloat(fundamental.revenueYoy) : null;
    const profit = fundamental.netProfitYoy ? parseFloat(fundamental.netProfitYoy) : null;

    if (rev !== null) {
      const subText = rev >= 0 ? `+${rev.toFixed(1)}%` : `${rev.toFixed(1)}%`;
      const chain = [];

      if (rev >= 30) {
        chain.push({ cond: `Q+${rev.toFixed(0)}%`, text: '高成长确认，景气上行周期', color: '#52c41a' });
      } else if (rev >= 15) {
        chain.push({ cond: `+${rev.toFixed(1)}%`, text: '稳健增长，估值有支撑', color: '#52c41a' });
      } else if (rev >= 0) {
        chain.push({ cond: `+${rev.toFixed(1)}%`, text: '低速增长或持平', color: '#999' });
      } else if (rev >= -15) {
        chain.push({ cond: `${rev.toFixed(1)}%`, text: '营收下滑，关注拐点', color: '#fa8c16' });
      } else {
        chain.push({ cond: `${rev.toFixed(1)}%`, text: '大幅下滑，基本面恶化', color: '#f5222d' });
      }

      if (rev < 0 && profit !== null && profit < rev) {
        chain.push({ cond: '利润降幅>收入', text: '盈利能力恶化幅度更大', color: '#f5222d' });
      }

      list.push({ label: '营收同比', subLabel: subText, chain });
    }
  }

  // ════════════════════════════════
  // 8. ROE（盈利质量）
  // ════════════════════════════════
  if (fundamental?.roe != null) {
    const roe = parseFloat(fundamental.roe);
    const subText = `${roe.toFixed(1)}%`;

    const chain = [];
    if (roe >= 20) {
      chain.push({ cond: `≥${roe.toFixed(0)}%`, text: '优质盈利水平，具备长期持有基础', color: '#52c41a' });
    } else if (roe >= 12) {
      chain.push({ cond: `${roe.toFixed(1)}%`, text: 'ROE良好，股东回报稳定', color: '#52c41a' });
    } else if (roe >= 6) {
      chain.push({ cond: `${roe.toFixed(1)}%`, text: 'ROE一般，需结合增长率判断', color: '#999' });
    } else if (roe >= 0) {
      chain.push({ cond: `${roe.toFixed(1)}%`, text: 'ROE偏低，资本效率不足', color: '#fa8c16' });
    } else {
      chain.push({ cond: `${roe.toFixed(1)}%`, text: '亏损状态，回避或博弈反转', color: '#f5222d' });
    }

    list.push({ label: 'ROE', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 9. 主力资金流向
  // ════════════════════════════════
  if (money?.netMain5d != null) {
    const net5d = parseFloat(money.netMain5d);
    let displayVal, unit;
    if (Math.abs(net5d) >= 1e9) { displayVal = (net5d / 1e8).toFixed(1); unit = '亿'; }
    else if (Math.abs(net5d) >= 1e7) { displayVal = (net5d / 1e4).toFixed(0); unit = '万'; }
    else { displayVal = net5d.toFixed(0); unit = ''; }
    const subText = `5日 ${(net5d >= 0 ? '+' : '')}${displayVal}${unit}`;

    const chain = [];
    if (net5d >= 5e8) {
      chain.push({ cond: '≥5亿净流入', text: '机构大举抢筹，中线看多信号强', color: '#52c41a' });
    } else if (net5d >= 1e8) {
      chain.push({ cond: '净流入过亿', text: '主力持续进场，资金面偏多', color: '#52c41a' });
    } else if (net5d >= 0) {
      chain.push({ cond: '小幅净流入', text: '主力态度中性偏多，力度有限', color: '#1890ff' });
    } else if (net5d >= -1e8) {
      chain.push({ cond: '小幅流出', text: '主力微幅撤离，暂不构成重大威胁', color: '#fa8c16' });
    } else if (net5d >= -5e8) {
      chain.push({ cond: '流出过亿', text: '主力明显出逃，资金面转弱', color: '#f5222d' });
    } else {
      chain.push({ cond: '大幅流出≥5亿', text: '机构集中出货，警惕趋势反转', color: '#f5222d' });
    }

    list.push({ label: '主力资金', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 10. 换手率 / 量能状态
  // ════════════════════════════════
  if (money?.turnoverRate != null && money?.turnoverRate5d != null) {
    const tr = parseFloat(money.turnoverRate);
    const tr5d = parseFloat(money.turnoverRate5d);
    const dev = tr - tr5d;
    const subText = `当日${tr.toFixed(2)}%（5日均${tr5d.toFixed(2)}%）`;

    const chain = [];
    if (dev >= 3) {
      chain.push({ cond: `偏离+${dev.toFixed(1)}%`, text: '异常放量，资金大量进出，关注是否有利好催化', color: '#fa8c16' });
    } else if (dev >= 1) {
      chain.push({ cond: `温和放量+${dev.toFixed(1)}%`, text: '关注度提升，量价配合则为积极信号', color: '#52c41a' });
    } else if (dev >= -1) {
      chain.push({ cond: '与均量持平', text: '交易活跃度正常，无异常信号', color: '#999' });
    } else if (dev >= -3) {
      chain.push({ cond: `缩量${dev.toFixed(1)}%`, text: '交投清淡，多空观望情绪浓', color: '#fa8c16' });
    } else {
      chain.push({ cond: '严重缩量', text: '流动性枯竭，可能酝酿变盘方向选择', color: '#f5222d' });
    }

    list.push({ label: '换手率', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 11. 连续涨停/炸板
  // ════════════════════════════════
  if (sentiment?.limitUpDays != null) {
    const days = sentiment.limitUpDays;
    const rate = sentiment.brokenLimitUpRate != null ? parseFloat(sentiment.brokenLimitUpRate) : null;
    const parts = [`${days}天`];
    if (rate != null) parts.push(`炸板${rate.toFixed(0)}%`);
    const subText = parts.join(' | ');

    const chain = [];
    if (days >= 3) {
      chain.push({ cond: `${days}连板`, text: '龙头股特征但高位分歧风险极大', color: '#f5222d' });
      if (rate != null && rate >= 30) chain.push({ cond: `炸板${rate.toFixed(0)}%`, text: '封板不稳，次日大概率低开', color: '#f5222d' });
    } else if (days === 2) {
      chain.push({ cond: '2连板', text: '强势连板，次日关注能否封住三板', color: '#fa8c16' });
    } else if (days === 1) {
      chain.push({ cond: '涨停', text: '单日强势，关注次日持续性', color: '#1890ff' });
    } else {
      chain.push({ cond: `近10日${days}天`, text: '无连续异动，走势平稳', color: '#52c41a' });
    }

    list.push({ label: '涨停记录', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 12. 公告正负事件差
  // ════════════════════════════════
  if (sentiment?.noticePositiveCount != null || sentiment?.noticeNegativeCount != null) {
    const pos = sentiment.noticePositiveCount || 0;
    const neg = sentiment.noticeNegativeCount || 0;
    const subText = `${pos}正/${neg}负`;

    const chain = [];
    if (pos >= 5 && neg <= 1) {
      chain.push({ cond: `正面${pos}项远多于负面`, text: '催化剂密集，基本面正向驱动充足', color: '#52c41a' });
    } else if (pos > neg + 2) {
      chain.push({ cond: `正面${pos} vs 负面${neg}`, text: '公告面偏暖，可作为辅助做多理由', color: '#52c41a' });
    } else if (neg > pos + 2) {
      chain.push({ cond: `负面${neg}项多于正面`, text: '利空事件密集，需评估实质性影响', color: '#f5222d' });
    } else {
      chain.push({ cond: `正负基本平衡`, text: '公告面无明确方向性指引', color: '#999' });
    }

    list.push({ label: '公告事件', subLabel: subText, chain });
  }

  // ════════════════════════════════
  // 13. 尾部风险事件（从tailRisks提取）
  // ════════════════════════════════
  if (tailRisks && Array.isArray(tailRisks) && tailRisks.length > 0) {
    // 只取前3个最严重的风险作为独立触发行
    const topRisks = tailRisks.slice(0, 3);
    topRisks.forEach(risk => {
      const level = risk.impact || 'WARNING';
      const color = level === '致命' || level === '毁灭性' ? '#f5222d' : '#fa8c16';
      const label = risk.name || risk.category || '尾部风险';
      const parts = [];
      if (risk.probability) parts.push(`概率${risk.probability}`);
      if (risk.potentialDecline) parts.push(`跌幅${risk.potentialDecline}`);
      const subText = parts.join('，') || risk.metric || '';

      const chain = [{
        cond: level,
        text: risk.triggerCondition || risk.metric || '',
        color,
      }];
      list.push({ label, subLabel: subText, chain, highlight: true });
    });
  }

  // ════════════════════════════════
  // 14. 近期催化剂（从catalysts提取关键项）
  // ════════════════════════════════
  if (catalysts && Array.isArray(catalysts)) {
    // 取最近的2个正面催化剂和1个负面
    const posCats = catalysts.filter(c => c.type === 'POSITIVE').slice(0, 2);
    const negCats = catalysts.filter(c => c.type === 'NEGATIVE').slice(0, 1);

    [...posCats, ...negCats].forEach(cat => {
      const isPos = cat.type === 'POSITIVE';
      const srcMap = { FINANCE: '财务', NEWS: '新闻', EVENT: '事件', MACRO: '宏观', VALUATION: '估值' };
      const chain = [];
      if (cat.trigger) {
        chain.push({
          cond: cat.source ? (srcMap[cat.source] || cat.source) : '',
          text: cat.trigger,
          color: isPos ? '#52c41a' : '#f5222d',
        });
      }
      list.push({
        label: cat.description || (isPos ? '正面催化' : '风险事件'),
        subLabel: cat.source ? (srcMap[cat.source] || cat.source) : (isPos ? '利好' : '利空'),
        chain,
        highlight: !isPos,
      });
    });
  }

  return list;
}

/** 单行渲染：左=变量+值，右=触发链 */
function TriggerRow({ row }) {
  const { label, subLabel, chain = [], highlight } = row;

  return (
    <div style={{
      display: 'flex',
      padding: '14px 16px',
      borderBottom: '1px solid #f5f5f5',
      ...(highlight ? { background: '#fffaf0' } : {}),
      transition: 'background 0.15s',
    }}
      onMouseEnter={e => e.currentTarget.style.background = highlight ? '#ffe7ba' : '#fafafa'}
      onMouseLeave={e => e.currentTarget.style.background = highlight ? '#fffaf0' : 'transparent'}
    >
      {/* 左列：变量名 + 当前值 */}
      <div style={{
        width: 200,
        flexShrink: 0,
        paddingRight: 20,
        borderRight: highlight ? '2px solid #ffd591' : '1px solid #f0f0f0',
      }}>
        <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{label}</div>
        {subLabel && (
          <div style={{ fontSize: 12, color: '#888' }}>{subLabel}</div>
        )}
      </div>

      {/* 右列：触发链 */}
      <div style={{ flex: 1, paddingLeft: 16, lineHeight: 2 }}>
        {chain.map((c, i) => (
          <span key={i}>
            {i > 0 && (
              <span style={{ margin: '0 6px', color: '#ccc' }}>；</span>
            )}
            {c.cond && (
              <span style={{ color: '#666', fontSize: 13 }}>{c.cond}</span>
            )}
            {c.cond && c.text && (
              <span style={{ margin: '0 4px', color: '#ccc' }}>→</span>
            )}
            <span style={{
              color: c.color || '#333',
              fontWeight: chain.length <= 1 ? 500 : 400,
              fontSize: 13,
            }}>
              {c.text}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
