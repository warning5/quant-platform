package com.quant.platform.factor.regime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 市场环境体制(regime)日历服务。
 *
 * <p>设计要点（避免循环依赖）：
 * 本服务本身不持有 detectRegime 逻辑，只负责「存储 + 缓存 + 懒计算」。
 * 真正计算 regime 的 detector 由 RecommendationService 在启动后通过
 * {@link #setDetector(Function)} 注入（方法引用 this::detectRegimeName），
 * 因此本服务与 RecommendationService 之间不存在 Spring 硬依赖环。</p>
 *
 * <p>行为：
 * 1. 进程内缓存（按交易日），避免重复计算/查询。
 * 2. 缓存未命中先查 MySQL 表；仍无则调用 detector 懒计算并落库。
 * 3. detector 为 null 时退化为 "SIDEWAYS"（保守、不报错）。</p>
 */
@Slf4j
@Service
public class MarketRegimeCalendarService {

    private final MarketRegimeCalendarMapper mapper;
    private final Map<LocalDate, String> cache = new ConcurrentHashMap<>();
    private volatile Function<LocalDate, String> detector;

    public MarketRegimeCalendarService(MarketRegimeCalendarMapper mapper) {
        this.mapper = mapper;
    }

    /** 由持有 detectRegime 逻辑的组件注入（如 RecommendationService） */
    public void setDetector(Function<LocalDate, String> detector) {
        this.detector = detector;
    }

    /**
     * 获取某交易日的体制。
     * 命中缓存→直接返回；否则查表；仍无→懒计算(detector)并落库；detector 缺失→SIDEWAYS。
     */
    public String getRegime(LocalDate date) {
        if (date == null) return "SIDEWAYS";
        String cached = cache.get(date);
        if (cached != null) return cached;

        String db = safeSelect(date);
        if (db != null) {
            cache.put(date, db);
            return db;
        }

        String r = (detector != null) ? detector.apply(date) : null;
        if (r == null || r.isBlank()) r = "SIDEWAYS";
        cache.put(date, r);
        safeUpsert(date, r);
        return r;
    }

    /** 显式写入（detectRegime 在主链路已算出 regime 时直接落库，省去一次懒计算） */
    public void upsert(LocalDate date, String regime) {
        if (date == null || regime == null || regime.isBlank()) return;
        cache.put(date, regime);
        safeUpsert(date, regime);
    }

    private String safeSelect(LocalDate date) {
        try {
            return mapper.selectRegime(date);
        } catch (Exception e) {
            log.warn("[RegimeCalendar] 查询失败 date={}: {}", date, e.getMessage());
            return null;
        }
    }

    private void safeUpsert(LocalDate date, String regime) {
        try {
            mapper.upsert(date, regime);
        } catch (Exception e) {
            log.warn("[RegimeCalendar] 写入失败 date={} regime={}: {}", date, regime, e.getMessage());
        }
    }
}
