package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.platform.dataperm.service.DataPermissionService;
import com.quant.platform.factor.service.FactorService;
import com.quant.platform.recommendation.mapper.RecommendationMapper;
import com.quant.platform.stock.analysis.engine.SellSignalEngine;
import com.quant.platform.stock.analysis.service.MarketThermometerService;
import com.quant.platform.calendar.service.TradeCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.quant.platform.common.enums.JobStatus;

/**
 * 模拟盘资金账户服务
 * 总资产同步、每日净值落库、出入金流水与信息比率等绩效指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperAccountService {

    private final PaperTradingMapper paperTradingMapper;
    private final PaperPositionMapper paperPositionMapper;
    private final PaperNavMapper paperNavMapper;
    private final PaperCashFlowMapper paperCashFlowMapper;

    @Autowired(required = false)
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    /**
     * 刷新/追加当日净值快照，确保 getDetail 返回时 navHistory 包含今日最新数据
     */
    public void refreshTodayNav(PaperTrading pt) {
        if (clickHouseJdbcTemplate == null) return;
        try {
            // 获取最新交易日作为"今日"（非自然日）
            List<String> dates = clickHouseJdbcTemplate.query(
                "SELECT max(trade_date) as d FROM stock.stock_daily FINAL",
                (rs, rowNum) -> rs.getString("d"));
            if (dates.isEmpty() || dates.getFirst() == null) return;
            LocalDate today = LocalDate.parse(dates.getFirst());

            // 前一交易日 NAV（用于计算 dailyReturn）
            PaperNav prevNav = paperNavMapper.selectOne(
                new LambdaQueryWrapper<PaperNav>()
                    .eq(PaperNav::getPaperId, pt.getId())
                    .ne(PaperNav::getNavDate, today)
                    .orderByDesc(PaperNav::getNavDate)
                    .last("LIMIT 1"));

            BigDecimal prevTotalAssets = prevNav != null
                ? prevNav.getTotalAssets() : pt.getInitialCapital();

            BigDecimal dailyReturn = prevTotalAssets.compareTo(BigDecimal.ZERO) > 0
                ? pt.getTotalAssets().subtract(prevTotalAssets)
                    .divide(prevTotalAssets, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            BigDecimal cumulativeReturn = pt.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
                ? pt.getTotalAssets().subtract(pt.getInitialCapital())
                    .divide(pt.getInitialCapital(), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            PaperNav todayNav = paperNavMapper.selectOne(
                new LambdaQueryWrapper<PaperNav>()
                    .eq(PaperNav::getPaperId, pt.getId())
                    .eq(PaperNav::getNavDate, today));

            if (todayNav != null) {
                todayNav.setTotalAssets(pt.getTotalAssets());
                todayNav.setDailyReturn(dailyReturn);
                todayNav.setCumulativeReturn(cumulativeReturn);
                paperNavMapper.updateById(todayNav);
            } else {
                PaperNav nav = PaperNav.builder()
                    .paperId(pt.getId())
                    .navDate(today)
                    .totalAssets(pt.getTotalAssets())
                    .dailyReturn(dailyReturn)
                    .cumulativeReturn(cumulativeReturn)
                    .build();
                paperNavMapper.insert(nav);
            }
        } catch (Exception e) {
            log.warn("刷新当日净值快照失败: paperId={}, error={}", pt.getId(), e.getMessage());
        }
    }

    public void updateTotalAssets(PaperTrading pt) {
        List<PaperPosition> positions = paperPositionMapper.selectList(
            new LambdaQueryWrapper<PaperPosition>().eq(PaperPosition::getPaperId, pt.getId()));
        BigDecimal posValue = positions.stream()
            .map(p -> p.getMarketValue() != null ? p.getMarketValue() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        pt.setTotalAssets(pt.getCurrentCapital().add(posValue));
        paperTradingMapper.updateById(pt);
    }

    /**
     * 追加/更新当日 NAV 记录（收盘后统一调用，基于收盘价计算日收益）
     * 同一天多次交易时，更新当日已存在的记录，不重复插入
     */
    public void appendNavRecord(Long paperId) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        LocalDate today = LocalDate.now();

        // 尝试获取今日已有 NAV 记录
        PaperNav todayNav = paperNavMapper.selectOne(
            new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, paperId)
                .eq(PaperNav::getNavDate, today)
                .last("LIMIT 1"));

        // 获取前一交易日 NAV（取最近一条非今日的记录）
        PaperNav prevNav = paperNavMapper.selectOne(
            new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, paperId)
                .ne(PaperNav::getNavDate, today)
                .orderByDesc(PaperNav::getNavDate)
                .last("LIMIT 1"));

        BigDecimal prevTotalAssets = prevNav != null ? prevNav.getTotalAssets() : pt.getInitialCapital();
        BigDecimal dailyReturn = prevTotalAssets.compareTo(BigDecimal.ZERO) > 0
            ? pt.getTotalAssets().subtract(prevTotalAssets)
                .divide(prevTotalAssets, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal cumulativeReturn = pt.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
            ? pt.getTotalAssets().subtract(pt.getInitialCapital())
                .divide(pt.getInitialCapital(), 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        if (todayNav != null) {
            // 更新今日已有记录
            todayNav.setTotalAssets(pt.getTotalAssets());
            todayNav.setDailyReturn(dailyReturn);
            todayNav.setCumulativeReturn(cumulativeReturn);
            paperNavMapper.updateById(todayNav);
        } else {
            // 插入新记录
            PaperNav nav = PaperNav.builder()
                .paperId(paperId)
                .navDate(today)
                .totalAssets(pt.getTotalAssets())
                .dailyReturn(dailyReturn)
                .cumulativeReturn(cumulativeReturn)
                .build();
            paperNavMapper.insert(nav);
        }
    }

    /** 追加入金 */
    public PaperCashFlow deposit(Long paperId, BigDecimal amount, String note) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在: " + paperId);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("入金金额必须大于0");

        pt.setInitialCapital(pt.getInitialCapital().add(amount));
        pt.setCurrentCapital(pt.getCurrentCapital().add(amount));
        paperTradingMapper.updateById(pt);

        PaperCashFlow flow = PaperCashFlow.builder()
            .paperId(paperId)
            .flowDate(LocalDate.now())
            .amount(amount)
            .flowType("DEPOSIT")
            .note(note != null ? note : "追加入金")
            .build();
        paperCashFlowMapper.insert(flow);
        log.info("入金: paperId={} amount={}", paperId, amount);
        return flow;
    }

    /** 提取出金 */
    public PaperCashFlow withdraw(Long paperId, BigDecimal amount, String note) {
        PaperTrading pt = paperTradingMapper.selectById(paperId);
        if (pt == null) throw new IllegalArgumentException("模拟盘不存在: " + paperId);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("出金金额必须大于0");
        if (amount.compareTo(pt.getCurrentCapital()) > 0) throw new IllegalArgumentException("出金金额超过可用资金");

        pt.setInitialCapital(pt.getInitialCapital().subtract(amount));
        pt.setCurrentCapital(pt.getCurrentCapital().subtract(amount));
        paperTradingMapper.updateById(pt);

        PaperCashFlow flow = PaperCashFlow.builder()
            .paperId(paperId)
            .flowDate(LocalDate.now())
            .amount(amount.negate())
            .flowType("WITHDRAW")
            .note(note != null ? note : "提取出金")
            .build();
        paperCashFlowMapper.insert(flow);
        log.info("出金: paperId={} amount={}", paperId, amount);
        return flow;
    }

    /** 查询现金流记录 */
    public List<PaperCashFlow> getCashFlows(Long paperId) {
        return paperCashFlowMapper.selectList(
            new LambdaQueryWrapper<PaperCashFlow>()
                .eq(PaperCashFlow::getPaperId, paperId)
                .orderByDesc(PaperCashFlow::getFlowDate));
    }

    /**
     * 聚合组合根净值（Route B 子账户聚合核心）。
     *  1) 汇总各子账户当前总资产/现金/持仓数 → 更新组合根快照
     *  2) 收集各子账户 paper_nav，按日期并集对齐（缺失日向前填充）→ 计算组合每日总资产/日收益/累计收益
     *     → upsert 进 paper_nav(paper_id=组合根)，供组合净值曲线与 IR 复用
     */
    public void aggregateCombo(Long comboId) {
        List<PaperTrading> children = paperTradingMapper.selectList(
            new LambdaQueryWrapper<PaperTrading>().eq(PaperTrading::getParentId, comboId));
        PaperTrading root = paperTradingMapper.selectById(comboId);
        if (root == null) return;

        // ── 1) 当前快照汇总 ──
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal currentCapital = BigDecimal.ZERO;
        int positionCount = 0;
        for (PaperTrading c : children) {
            totalAssets = totalAssets.add(c.getTotalAssets() != null ? c.getTotalAssets() : BigDecimal.ZERO);
            currentCapital = currentCapital.add(c.getCurrentCapital() != null ? c.getCurrentCapital() : BigDecimal.ZERO);
            positionCount += (c.getPositionCount() != null ? c.getPositionCount() : 0);
        }
        root.setTotalAssets(totalAssets);
        root.setCurrentCapital(currentCapital);
        root.setPositionCount(positionCount);
        paperTradingMapper.updateById(root);

        if (children.isEmpty()) return;

        // ── 2) 净值对齐聚合 ──
        List<Long> childIds = children.stream().map(PaperTrading::getId).collect(Collectors.toList());
        List<PaperNav> allNavs = paperNavMapper.selectList(
            new LambdaQueryWrapper<PaperNav>().in(PaperNav::getPaperId, childIds));

        // 每个子账户：按日期升序的 nav 列表
        Map<Long, List<PaperNav>> byChild = allNavs.stream()
            .sorted(Comparator.comparing(PaperNav::getNavDate))
            .collect(Collectors.groupingBy(PaperNav::getPaperId));

        // 子账户初始资本（首日前用初始资本向前填充），以及运行时向前填充值
        Map<Long, BigDecimal> fwd = children.stream()
            .collect(Collectors.toMap(PaperTrading::getId,
                c -> c.getInitialCapital() != null ? c.getInitialCapital() : BigDecimal.ZERO));

        // 所有子账户 nav 日期并集（升序）
        TreeSet<LocalDate> dates = new TreeSet<>();
        byChild.values().forEach(list -> list.forEach(n -> dates.add(n.getNavDate())));

        BigDecimal initialCapital = root.getInitialCapital() != null ? root.getInitialCapital() : BigDecimal.ZERO;
        BigDecimal prevCombo = null;
        List<PaperNav> comboNavs = new ArrayList<>();
        for (LocalDate d : dates) {
            BigDecimal comboVal = BigDecimal.ZERO;
            for (Map.Entry<Long, List<PaperNav>> en : byChild.entrySet()) {
                Long cid = en.getKey();
                BigDecimal v = en.getValue().stream()
                    .filter(n -> n.getNavDate().equals(d))
                    .map(PaperNav::getTotalAssets)
                    .findFirst()
                    .orElse(fwd.get(cid));
                if (v != null) fwd.put(cid, v);
                comboVal = comboVal.add(v != null ? v : BigDecimal.ZERO);
            }
            BigDecimal dailyReturn = (prevCombo != null && prevCombo.compareTo(BigDecimal.ZERO) > 0)
                ? comboVal.subtract(prevCombo).divide(prevCombo, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            BigDecimal cumulativeReturn = initialCapital.compareTo(BigDecimal.ZERO) > 0
                ? comboVal.subtract(initialCapital).divide(initialCapital, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            comboNavs.add(PaperNav.builder()
                .paperId(comboId).navDate(d).totalAssets(comboVal)
                .dailyReturn(dailyReturn).cumulativeReturn(cumulativeReturn).build());
            prevCombo = comboVal;
        }

        // upsert 组合净值（按 paper_id + nav_date 唯一）
        for (PaperNav nav : comboNavs) {
            PaperNav existing = paperNavMapper.selectOne(new LambdaQueryWrapper<PaperNav>()
                .eq(PaperNav::getPaperId, comboId)
                .eq(PaperNav::getNavDate, nav.getNavDate())
                .last("LIMIT 1"));
            if (existing != null) {
                existing.setTotalAssets(nav.getTotalAssets());
                existing.setDailyReturn(nav.getDailyReturn());
                existing.setCumulativeReturn(nav.getCumulativeReturn());
                paperNavMapper.updateById(existing);
            } else {
                paperNavMapper.insert(nav);
            }
        }
        log.info("组合根 [{}] 聚合完成：子账户数={}, 组合总资产={}", comboId, children.size(), totalAssets);
    }

    /**
     * 计算信息比率（Information Ratio）
     * IR = 超额收益均值 / 超额收益标准差，滚动N日窗口
     * 超额收益 = 模拟盘累计收益率 - 基准累计收益率（归一化：基准净值/基准起点净值 - 1）
     *
     * @param result    放入 IR 计算结果的 Map
     * @param navs      模拟盘净值历史
     * @param indexRows 全量基准指数数据（含 close_price）
     * @param basePrice 基准归一化起点价格（navStartDate前一日收盘价）
     */
    @SuppressWarnings("unchecked")
    public void calculateInformationRatio(
            Map<String, Object> result,
            List<PaperNav> navs,
            List<Map<String, Object>> indexRows,
            BigDecimal basePrice) {

        if (navs == null || navs.isEmpty()
                || indexRows == null || indexRows.isEmpty()
                || basePrice == null || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 构建日期 -> 归一化基准净值的映射（全量 indexRows）
        Map<String, Double> benchNormMap = new java.util.HashMap<>();
        for (Map<String, Object> row : indexRows) {
            String date = String.valueOf(row.get("date"));
            BigDecimal close = (BigDecimal) row.get("close");
            if (date != null && close != null) {
                double nav = close.divide(basePrice, 6, RoundingMode.HALF_UP).doubleValue();
                benchNormMap.put(date, nav);
            }
        }

        // 计算每日超额收益 = 模拟盘累计收益 - (基准净值 - 1)
        List<Double> excessList = new java.util.ArrayList<>();
        List<Map<String, Object>> excessDailyList = new java.util.ArrayList<>();
        for (PaperNav nav : navs) {
            String date = nav.getNavDate().toString();
            if (!benchNormMap.containsKey(date)) continue;
            double benchReturn = benchNormMap.get(date) - 1.0;
            double paperReturn = nav.getCumulativeReturn() != null
                ? nav.getCumulativeReturn().doubleValue() : 0.0;
            double excess = paperReturn - benchReturn;
            excessList.add(excess);

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("date", date);
            entry.put("excessReturn", Math.round(excess * 10000.0) / 10000.0);
            excessDailyList.add(entry);
        }

        if (excessList.size() < 3) {
            log.info("[信息比率] 数据点不足({}<3)，跳过计算", excessList.size());
            return;
        }

        // 滚动窗口 IR（窗口 = min(20, 数据长度)）
        int windowDays = Math.min(20, excessList.size());
        List<Double> irList = new java.util.ArrayList<>();
        for (int i = windowDays - 1; i < excessList.size(); i++) {
            int start = i - windowDays + 1;
            double mean = 0;
            for (int j = start; j <= i; j++) mean += excessList.get(j);
            mean /= windowDays;
            double variance = 0;
            for (int j = start; j <= i; j++) {
                double d = excessList.get(j) - mean;
                variance += d * d;
            }
            variance /= windowDays;
            double std = Math.sqrt(Math.max(0, variance));
            if (std > 1e-10) {
                irList.add(mean / std);
            }
        }

        if (!irList.isEmpty()) {
            double latestIR = irList.get(irList.size() - 1);
            double avgIR = irList.stream().mapToDouble(Double::doubleValue).sum() / irList.size();
            result.put("informationRatio", Math.round(latestIR * 10000.0) / 10000.0);
            result.put("informationRatioAnnualized", Math.round(latestIR * Math.sqrt(252) * 10000.0) / 10000.0);
            result.put("informationRatioAvg", Math.round(avgIR * 10000.0) / 10000.0);
            result.put("informationRatioAvgAnnualized", Math.round(avgIR * Math.sqrt(252) * 10000.0) / 10000.0);
            result.put("irWindowDays", windowDays);
            result.put("irExcessReturns", excessDailyList);
            log.info("[信息比率] 最新{}-日IR={}, 年化={}, 均值IR={}, 数据点={}",
                windowDays, latestIR, latestIR * Math.sqrt(252), avgIR, excessList.size());
        } else {
            log.info("[信息比率] 滚动IR全为NaN（标准差≈0），数据点={}", excessList.size());
        }
    }

}
