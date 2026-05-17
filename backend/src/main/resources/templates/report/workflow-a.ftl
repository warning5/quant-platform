<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Workflow A 分析报告 - ${report.name!report.code}(${report.code})</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background: #f5f7fa;
            color: #333;
            line-height: 1.6;
            padding: 20px;
        }
        .container {
            max-width: 900px;
            margin: 0 auto;
            background: #fff;
            border-radius: 12px;
            box-shadow: 0 2px 12px rgba(0,0,0,0.08);
            overflow: hidden;
        }
        /* 头部 */
        .header {
            background: linear-gradient(135deg, #1a73e8 0%, #4285f4 100%);
            color: #fff;
            padding: 32px;
            text-align: center;
        }
        .header h1 {
            font-size: 24px;
            font-weight: 600;
            margin-bottom: 8px;
        }
        .header .subtitle {
            font-size: 14px;
            opacity: 0.9;
        }
        .header .meta {
            margin-top: 12px;
            font-size: 12px;
            opacity: 0.8;
        }

        /* 综合评分区 */
        .score-section {
            padding: 24px 32px;
            border-bottom: 1px solid #eee;
        }
        .score-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 20px;
        }
        .score-big {
            display: flex;
            align-items: baseline;
            gap: 8px;
        }
        .score-number {
            font-size: 48px;
            font-weight: 700;
            color: #1a73e8;
        }
        .score-label {
            font-size: 14px;
            color: #666;
        }
        .score-badge {
            display: inline-block;
            padding: 6px 16px;
            border-radius: 20px;
            font-size: 14px;
            font-weight: 600;
        }
        .badge-buy { background: #e8f5e9; color: #2e7d32; }
        .badge-hold { background: #fff3e0; color: #ef6c00; }
        .badge-reduce { background: #fce4ec; color: #c62828; }
        .badge-clear { background: #f3e5f5; color: #6a1b9a; }

        .score-dimensions {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 16px;
        }
        .dim-card {
            background: #f8f9fa;
            border-radius: 8px;
            padding: 16px;
            text-align: center;
        }
        .dim-name {
            font-size: 12px;
            color: #666;
            margin-bottom: 4px;
        }
        .dim-score {
            font-size: 24px;
            font-weight: 700;
            color: #333;
        }
        .dim-bar {
            height: 6px;
            background: #e0e0e0;
            border-radius: 3px;
            margin-top: 8px;
            overflow: hidden;
        }
        .dim-bar-fill {
            height: 100%;
            border-radius: 3px;
            transition: width 0.3s;
        }

        /* 模块区 */
        .section {
            padding: 24px 32px;
            border-bottom: 1px solid #eee;
        }
        .section:last-child {
            border-bottom: none;
        }
        .section-title {
            font-size: 16px;
            font-weight: 600;
            color: #333;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .section-title .icon {
            width: 28px;
            height: 28px;
            border-radius: 6px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 14px;
        }

        /* 指标网格 */
        .metric-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
            gap: 12px;
        }
        .metric-item {
            background: #f8f9fa;
            border-radius: 8px;
            padding: 12px 16px;
        }
        .metric-label {
            font-size: 11px;
            color: #888;
            margin-bottom: 2px;
        }
        .metric-value {
            font-size: 16px;
            font-weight: 600;
            color: #333;
        }
        .metric-value.up { color: #d32f2f; }
        .metric-value.down { color: #2e7d32; }

        /* 多空辩论 */
        .debate-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 16px;
        }
        .debate-col {
            border-radius: 8px;
            padding: 16px;
        }
        .debate-bull {
            background: #e8f5e9;
            border: 1px solid #c8e6c9;
        }
        .debate-bear {
            background: #ffebee;
            border: 1px solid #ffcdd2;
        }
        .debate-title {
            font-size: 14px;
            font-weight: 600;
            margin-bottom: 12px;
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .debate-bull .debate-title { color: #2e7d32; }
        .debate-bear .debate-title { color: #c62828; }
        .debate-item {
            background: rgba(255,255,255,0.7);
            border-radius: 6px;
            padding: 10px 12px;
            margin-bottom: 8px;
        }
        .debate-item:last-child { margin-bottom: 0; }
        .debate-rule {
            font-size: 12px;
            font-weight: 600;
            color: #333;
            margin-bottom: 2px;
        }
        .debate-desc {
            font-size: 12px;
            color: #555;
        }
        .debate-dim {
            display: inline-block;
            font-size: 10px;
            padding: 1px 6px;
            border-radius: 4px;
            background: rgba(0,0,0,0.06);
            color: #666;
            margin-top: 4px;
        }
        .stars {
            color: #ffc107;
            font-size: 11px;
            letter-spacing: 1px;
        }

        /* 结论区 */
        .conclusion {
            background: #e3f2fd;
            border-radius: 8px;
            padding: 20px;
            margin-top: 8px;
        }
        .conclusion-title {
            font-size: 14px;
            font-weight: 600;
            color: #1565c0;
            margin-bottom: 8px;
        }
        .conclusion-text {
            font-size: 13px;
            color: #333;
            line-height: 1.8;
        }

        /* 研报列表 */
        .report-list {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .report-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 10px 12px;
            background: #f8f9fa;
            border-radius: 6px;
            font-size: 12px;
        }
        .report-rating {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: 600;
        }
        .rating-buy { background: #e8f5e9; color: #2e7d32; }
        .rating-add { background: #e3f2fd; color: #1565c0; }
        .rating-neutral { background: #fff3e0; color: #ef6c00; }
        .rating-reduce { background: #ffebee; color: #c62828; }

        /* 页脚 */
        .footer {
            padding: 16px 32px;
            text-align: center;
            font-size: 11px;
            color: #999;
            background: #f8f9fa;
        }

        @media print {
            body { background: #fff; padding: 0; }
            .container { box-shadow: none; border-radius: 0; }
        }
    </style>
</head>
<body>
<div class="container">
    <!-- 头部 -->
    <div class="header">
        <h1>Workflow A 综合分析报告</h1>
        <div class="subtitle">${report.name!report.code} (${report.code})</div>
        <div class="meta">生成时间：${report.reportTime} | 数据来源：量化平台</div>
    </div>

    <!-- 综合评分 -->
    <div class="score-section">
        <div class="score-header">
            <div class="score-big">
                <span class="score-number">${report.totalScore!0}</span>
                <span class="score-label">综合评分</span>
            </div>
            <div>
                <span class="score-badge badge-${(report.actionName!'持有')?replace('买入', 'buy')?replace('持有', 'hold')?replace('减仓', 'reduce')?replace('清仓', 'clear')?replace('观望', 'hold')}">
                    ${report.actionName!'--'}
                </span>
            </div>
        </div>
        <#if report.overview?? && report.overview.scoreDetails??>
        <div class="score-dimensions">
            <#list report.overview.scoreDetails as detail>
            <div class="dim-card">
                <div class="dim-name">${detail.dimensionName!detail.dimension!'--'}</div>
                <div class="dim-score">${detail.score!0}/${detail.maxScore!0}</div>
                <div class="dim-bar">
                    <div class="dim-bar-fill" style="width: ${(detail.maxScore!1 > 0)?then((detail.score!0 / detail.maxScore!1 * 100)?string('0.0'), '0')}%; background: ${(detail.score!0 / detail.maxScore!1 > 0.6)?then('#4caf50', (detail.score!0 / detail.maxScore!1 > 0.3)?then('#ff9800', '#f44336')}"></div>
                </div>
            </div>
            </#list>
        </div>
        </#if>
    </div>

    <!-- 技术分析师 -->
    <div class="section">
        <div class="section-title">
            <span class="icon" style="background:#e3f2fd;color:#1565c0;">&#x1F4C8;</span>
            技术分析师
        </div>
        <#if report.overview?? && report.overview.techSignal??>
        <#assign tech = report.overview.techSignal>
        <div class="metric-grid">
            <div class="metric-item">
                <div class="metric-label">缠论信号</div>
                <div class="metric-value ${(tech.chanSignal!'')?contains('BUY')?then('up', (tech.chanSignal!'')?contains('SELL')?then('down', ''))}">${(tech.chanSignal!'')?replace('BUY','买入')?replace('SELL','卖出')?replace('HOLD','持有')}</div>
            </div>
            <div class="metric-item">
                <div class="metric-label">缠论趋势</div>
                <div class="metric-value">${(tech.trend!'')?replace('BULLISH','上涨')?replace('BEARISH','下跌')?replace('SIDEWAYS','盘整')}</div>
            </div>
            <div class="metric-item">
                <div class="metric-label">均线多头</div>
                <div class="metric-value">${(tech.maBullish?? && tech.maBullish)?then('是', '否')}</div>
            </div>
            <div class="metric-item">
                <div class="metric-label">MACD金叉</div>
                <div class="metric-value">${(tech.macdGolden?? && tech.macdGolden)?then('是', '否')}</div>
            </div>
            <#if tech.rsi??>
            <div class="metric-item">
                <div class="metric-label">RSI(14)</div>
                <div class="metric-value">${tech.rsi}</div>
            </div>
            </#if>
            <#if tech.hubPos??>
            <div class="metric-item">
                <div class="metric-label">中枢位置</div>
                <div class="metric-value">${(tech.hubPos!'')?replace('UPPER','上轨')?replace('MIDDLE','中轨')?replace('LOWER','下轨')}</div>
            </div>
            </#if>
        </div>
        <#else>
        <p style="color:#888;font-size:13px;">暂无技术面数据</p>
        </#if>
    </div>

    <!-- 基本面分析师 -->
    <div class="section">
        <div class="section-title">
            <span class="icon" style="background:#e8f5e9;color:#2e7d32;">&#x1F4B0;</span>
            基本面分析师
        </div>
        <#if report.overview?? && report.overview.fundamentalSignal??>
        <#assign fund = report.overview.fundamentalSignal>
        <div class="metric-grid">
            <#if fund.peTtm??>
            <div class="metric-item">
                <div class="metric-label">PE(TTM)</div>
                <div class="metric-value">${fund.peTtm}</div>
            </div>
            </#if>
            <#if fund.pb??>
            <div class="metric-item">
                <div class="metric-label">PB</div>
                <div class="metric-value">${fund.pb}</div>
            </div>
            </#if>
            <#if fund.roe??>
            <div class="metric-item">
                <div class="metric-label">ROE</div>
                <div class="metric-value">${fund.roe}%</div>
            </div>
            </#if>
            <#if fund.revenueYoy??>
            <div class="metric-item">
                <div class="metric-label">营收增速</div>
                <div class="metric-value ${(fund.revenueYoy > 0)?then('up', 'down')}">${fund.revenueYoy}%</div>
            </div>
            </#if>
            <#if fund.netProfitYoy??>
            <div class="metric-item">
                <div class="metric-label">净利润增速</div>
                <div class="metric-value ${(fund.netProfitYoy > 0)?then('up', 'down')}">${fund.netProfitYoy}%</div>
            </div>
            </#if>
            <#if fund.grossMargin??>
            <div class="metric-item">
                <div class="metric-label">毛利率</div>
                <div class="metric-value">${fund.grossMargin}%</div>
            </div>
            </#if>
            <#if fund.debtRatio??>
            <div class="metric-item">
                <div class="metric-label">资产负债率</div>
                <div class="metric-value">${fund.debtRatio}%</div>
            </div>
            </#if>
        </div>
        <#if report.valuationPercentile??>
        <div style="margin-top:12px;padding:12px;background:#f8f9fa;border-radius:6px;font-size:12px;">
            <strong>估值分位（近3年）：</strong>
            PE分位 <span style="color:#1a73e8;font-weight:600;">${(report.valuationPercentile.pePercentile!0)?string('0.0')}%</span>
            | PB分位 <span style="color:#1a73e8;font-weight:600;">${(report.valuationPercentile.pbPercentile!0)?string('0.0')}%</span>
            | 当前PE <span style="color:#666;">${report.valuationPercentile.peCurrent!'--'}</span>
            | 当前PB <span style="color:#666;">${report.valuationPercentile.pbCurrent!'--'}</span>
        </div>
        </#if>
        <#else>
        <p style="color:#888;font-size:13px;">暂无基本面数据</p>
        </#if>
    </div>

    <!-- 新闻分析师 -->
    <div class="section">
        <div class="section-title">
            <span class="icon" style="background:#fff3e0;color:#ef6c00;">&#x1F4DD;</span>
            新闻分析师（近90天研报）
        </div>
        <#if report.researchAnalysis??>
        <#assign rs = report.researchAnalysis>
        <div class="metric-grid" style="margin-bottom:12px;">
            <#if rs.ratingSummary??>
            <div class="metric-item">
                <div class="metric-label">最新评级</div>
                <div class="metric-value">
                    <span class="report-rating rating-${(rs.ratingSummary.latestRating!'')?replace('买入','buy')?replace('增持','add')?replace('中性','neutral')?replace('减持','reduce')?replace('卖出','reduce')}">${rs.ratingSummary.latestRating!'--'}</span>
                </div>
            </div>
            <div class="metric-item">
                <div class="metric-label">买入占比</div>
                <div class="metric-value">${(rs.ratingSummary.buyRatio!0)?string('0.0')}%</div>
            </div>
            <div class="metric-item">
                <div class="metric-label">覆盖机构</div>
                <div class="metric-value">${(rs.coverage!{}).institutionCount!0}家</div>
            </div>
            <div class="metric-item">
                <div class="metric-label">近90天研报</div>
                <div class="metric-value">${(rs.coverage!{}).reportCount90d!0}份</div>
            </div>
        </div>
        <#if rs.recentReports?? && rs.recentReports?size > 0>
        <div class="report-list">
            <#list rs.recentReports?take(5) as r>
            <div class="report-item">
                <span>${r.institution!'--'} | ${r.reportDate!'--'}</span>
                <span>
                    <span class="report-rating rating-${(r.rating!'')?replace('买入','buy')?replace('增持','add')?replace('中性','neutral')?replace('减持','reduce')}">${r.rating!'--'}</span>
                    <#if r.targetPrice??><span style="margin-left:8px;color:#666;">目标价:${r.targetPrice}</span></#if>
                </span>
            </div>
            </#list>
        </div>
        </#if>
        <#else>
        <p style="color:#888;font-size:13px;">暂无研报数据</p>
        </#if>
    </div>

    <!-- 情绪分析师 -->
    <div class="section">
        <div class="section-title">
            <span class="icon" style="background:#fce4ec;color:#c62828;">&#x1F4B8;</span>
            情绪分析师（资金流向）
        </div>
        <#if report.overview?? && report.overview.moneySignal??>
        <#assign mf = report.overview.moneySignal>
        <div class="metric-grid">
            <#if mf.netMain??>
            <div class="metric-item">
                <div class="metric-label">主力净流入</div>
                <div class="metric-value ${(mf.netMain > 0)?then('up', 'down')}">${mf.netMain}</div>
            </div>
            </#if>
            <#if mf.netMainPct??>
            <div class="metric-item">
                <div class="metric-label">主力净流入占比</div>
                <div class="metric-value ${(mf.netMainPct > 0)?then('up', 'down')}">${mf.netMainPct}%</div>
            </div>
            </#if>
            <#if mf.volumeRatio??>
            <div class="metric-item">
                <div class="metric-label">量比</div>
                <div class="metric-value">${mf.volumeRatio}</div>
            </div>
            </#if>
            <#if mf.turnoverRate??>
            <div class="metric-item">
                <div class="metric-label">换手率</div>
                <div class="metric-value">${mf.turnoverRate}%</div>
            </div>
            </#if>
            <#if mf.netHuge??>
            <div class="metric-item">
                <div class="metric-label">超大单净流入</div>
                <div class="metric-value ${(mf.netHuge > 0)?then('up', 'down')}">${mf.netHuge}</div>
            </div>
            </#if>
            <#if mf.netBig??>
            <div class="metric-item">
                <div class="metric-label">大单净流入</div>
                <div class="metric-value ${(mf.netBig > 0)?then('up', 'down')}">${mf.netBig}</div>
            </div>
            </#if>
        </div>
        <#else>
        <p style="color:#888;font-size:13px;">暂无资金流向数据</p>
        </#if>
    </div>

    <!-- 多空辩论 -->
    <div class="section">
        <div class="section-title">
            <span class="icon" style="background:#f3e5f5;color:#6a1b9a;">&#x2694;&#xFE0F;</span>
            多空辩论（规则引擎）
            <span style="margin-left:auto;font-size:12px;color:#666;">多头 ${report.bullCount} vs 空头 ${report.bearCount} | 倾向：${report.bias}</span>
        </div>
        <div class="debate-grid">
            <div class="debate-col debate-bull">
                <div class="debate-title">&#x1F4C8; 多头论据 (${report.bullCount})</div>
                <#if report.bullArguments?? && report.bullArguments?size > 0>
                <#list report.bullArguments as arg>
                <div class="debate-item">
                    <div class="debate-rule">${arg.rule}</div>
                    <div class="debate-desc">${arg.description}</div>
                    <div>
                        <span class="debate-dim">${arg.dimension}</span>
                        <span class="stars"><#list 1..arg.strength as i>&#x2605;</#list></span>
                    </div>
                </div>
                </#list>
                <#else>
                <p style="font-size:12px;color:#666;">暂无明确多头信号</p>
                </#if>
            </div>
            <div class="debate-col debate-bear">
                <div class="debate-title">&#x1F4C9; 空头论据 (${report.bearCount})</div>
                <#if report.bearArguments?? && report.bearArguments?size > 0>
                <#list report.bearArguments as arg>
                <div class="debate-item">
                    <div class="debate-rule">${arg.rule}</div>
                    <div class="debate-desc">${arg.description}</div>
                    <div>
                        <span class="debate-dim">${arg.dimension}</span>
                        <span class="stars"><#list 1..arg.strength as i>&#x2605;</#list></span>
                    </div>
                </div>
                </#list>
                <#else>
                <p style="font-size:12px;color:#666;">暂无明确空头信号</p>
                </#if>
            </div>
        </div>
    </div>

    <!-- 综合结论 -->
    <div class="section">
        <div class="section-title">
            <span class="icon" style="background:#e3f2fd;color:#1565c0;">&#x1F4CB;</span>
            综合结论
        </div>
        <div class="conclusion">
            <div class="conclusion-title">${report.name!report.code}(${report.code}) - ${report.actionName!'--'} | 仓位 ${report.position!0}%</div>
            <div class="conclusion-text">${report.conclusion!'暂无结论'}</div>
        </div>
        <#if report.overview?? && report.overview.risks??>
        <div style="margin-top:12px;padding:12px;background:#fff3e0;border-radius:6px;font-size:12px;color:#666;">
            <strong style="color:#ef6c00;">&#x26A0;&#xFE0F; 风险提示：</strong>${report.overview.risks}
        </div>
        </#if>
    </div>

    <div class="footer">
        Workflow A 综合分析报告 | 档一：轻量版报告模板填充 | 数据驱动的综合报告，多空辩论为规则判断而非 AI 博弈
    </div>
</div>
</body>
</html>
