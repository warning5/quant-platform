package com.quant.platform.factor.regime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 市场环境体制(regime)日历映射。
 * 将每个交易日映射到其市场环境体制(BULL/BEAR/SIDEWAYS)，
 * 供 ICW 权重按 regime 分别取 IC 历史使用（避免跨体制 IC 互相污染）。
 */
@Mapper
public interface MarketRegimeCalendarMapper {

    /** 覆盖写入（同一交易日幂等更新体制） */
    @Insert("INSERT INTO market_regime_calendar (trade_date, regime, updated_at) "
            + "VALUES (#{tradeDate}, #{regime}, NOW()) "
            + "ON DUPLICATE KEY UPDATE regime = VALUES(regime), updated_at = NOW()")
    int upsert(@Param("tradeDate") LocalDate tradeDate, @Param("regime") String regime);

    /** 读取某交易日的体制；无记录返回 null */
    @Select("SELECT regime FROM market_regime_calendar WHERE trade_date = #{tradeDate}")
    String selectRegime(@Param("tradeDate") LocalDate tradeDate);
}
