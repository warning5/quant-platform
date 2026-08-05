package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertDataLoader {

    private final PositionAlertMapper positionAlertMapper;
    private final PaperRiskConfigMapper paperRiskConfigMapper;
    private final JdbcTemplate jdbcTemplate;


    public List<PaperPosition> getPositions(Long paperId) {
        // 直接用 JdbcTemplate 查，避免循环依赖
        return jdbcTemplate.query(
            "SELECT id, paper_id, code, name, shares, cost_price, current_price, market_value, profit_loss_pct, buy_date " +
            "FROM paper_position WHERE paper_id = ?",
            (rs, rowNum) -> PaperPosition.builder()
                .id(rs.getLong("id"))
                .paperId(rs.getLong("paper_id"))
                .code(rs.getString("code"))
                .name(rs.getString("name"))
                .shares(rs.getInt("shares"))
                .costPrice(rs.getBigDecimal("cost_price"))
                .currentPrice(rs.getBigDecimal("current_price"))
                .marketValue(rs.getBigDecimal("market_value"))
                .profitLossPct(rs.getBigDecimal("profit_loss_pct"))
                .buyDate(rs.getDate("buy_date") != null ? rs.getDate("buy_date").toLocalDate() : null)
                .build(),
            paperId);
    }


    public PaperRiskConfig getRiskConfig(Long paperId) {
        PaperRiskConfig cfg = paperRiskConfigMapper.selectOne(
            new LambdaQueryWrapper<PaperRiskConfig>()
                .eq(PaperRiskConfig::getPaperId, paperId));
        if (cfg != null) return cfg;
        // 未配置时返回默认值，确保风控预警能正常触发
        return PaperRiskConfig.defaults(paperId);
    }


    public BigDecimal getTotalAssets(Long paperId) {
        try {
            List<BigDecimal> r = jdbcTemplate.query(
                "SELECT total_assets FROM paper_trading WHERE id = ?",
                (rs, rowNum) -> rs.getBigDecimal("total_assets"), paperId);
            return r.isEmpty() ? null : r.getFirst();
        } catch (Exception e) {
            return null;
        }
    }


    public String getStockIndustry(String code) {
        if (code == null) return null;
        try {
            List<String> r = jdbcTemplate.query(
                "SELECT industry FROM stock_info WHERE code = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("industry"), code);
            return r.isEmpty() ? null : r.getFirst();
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 查询某行业的当前持仓市值
     */
    public BigDecimal getIndustryMarketValue(Long paperId, String industry) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT p.code, p.market_value, s.industry " +
                "FROM paper_position p " +
                "LEFT JOIN stock_info s ON p.code = s.code " +
                "WHERE p.paper_id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("industry", rs.getString("industry"));
                    m.put("mv", rs.getBigDecimal("market_value"));
                    return m;
                }, paperId);

            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                if (industry.equals(row.get("industry"))) {
                    BigDecimal mv = (BigDecimal) row.get("mv");
                    if (mv != null) total = total.add(mv);
                }
            }
            return total;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }


    /**
     * 查询某股票的当前持仓市值
     */
    public BigDecimal getStockMarketValue(Long paperId, String code) {
        try {
            List<BigDecimal> r = jdbcTemplate.query(
                "SELECT market_value FROM paper_position WHERE paper_id = ? AND code = ?",
                (rs, rowNum) -> rs.getBigDecimal("market_value"), paperId, code);
            return r.isEmpty() ? BigDecimal.ZERO : r.getFirst();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }


    public void saveAlert(PositionAlert alert) {
        try {
            // 去重：同一模拟盘+同一股票+同一类型+同一日期，不重复插入
            long existing = positionAlertMapper.selectCount(
                new LambdaQueryWrapper<PositionAlert>()
                    .eq(PositionAlert::getPaperId, alert.getPaperId())
                    .eq(PositionAlert::getCode, alert.getCode())
                    .eq(PositionAlert::getAlertType, alert.getAlertType())
                    .eq(PositionAlert::getAlertDate, alert.getAlertDate()));
            if (existing == 0) {
                positionAlertMapper.insert(alert);
            }
        } catch (Exception e) {
            log.warn("保存预警失败: paperId={}, code={}, error={}", alert.getPaperId(), alert.getCode(), e.getMessage());
        }
    }


    /**
     * 查询模拟盘的预警列表
     */
    public List<PositionAlert> getAlerts(Long paperId, int limit) {
        return positionAlertMapper.selectList(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId)
                .orderByDesc(PositionAlert::getAlertDate)
                .orderByDesc(PositionAlert::getId)
                .last("LIMIT " + Math.min(limit, 200)));
    }


    /**
     * 获取未读预警数量
     */
    public long getUnreadCount(Long paperId) {
        return positionAlertMapper.selectCount(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId)
                .eq(PositionAlert::getIsRead, false));
    }


    /**
     * 标记所有预警为已读
     */
    public int markAllRead(Long paperId) {
        List<PositionAlert> unread = positionAlertMapper.selectList(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId)
                .eq(PositionAlert::getIsRead, false));
        for (PositionAlert a : unread) {
            a.setIsRead(true);
            positionAlertMapper.updateById(a);
        }
        return unread.size();
    }


    /**
     * 标记单条预警为已读
     */
    public void markRead(Long alertId) {
        PositionAlert alert = positionAlertMapper.selectById(alertId);
        if (alert != null && !alert.getIsRead()) {
            alert.setIsRead(true);
            positionAlertMapper.updateById(alert);
        }
    }


    /**
     * 删除单条预警
     */
    public void deleteAlert(Long alertId) {
        positionAlertMapper.deleteById(alertId);
    }


    /**
     * 清空模拟盘所有预警
     */
    public int clearAlerts(Long paperId) {
        return positionAlertMapper.delete(
            new LambdaQueryWrapper<PositionAlert>()
                .eq(PositionAlert::getPaperId, paperId));
    }
}
