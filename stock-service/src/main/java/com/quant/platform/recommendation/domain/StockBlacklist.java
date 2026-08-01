package com.quant.platform.recommendation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票黑名单
 * 当某只股票连续推荐失利或出现踩雷时，自动或手动加入黑名单，
 * 推荐生成时自动过滤黑名单中的股票。
 */
@Data
@TableName("stock_blacklist")
public class StockBlacklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略ID */
    private Long strategyId;

    /** 股票代码（纯代码，无后缀） */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /**
     * 加入原因:
     * CONSECUTIVE_LOSS - 连续N次推荐失利
     * LOW_HIT_RATE - 近N次命中率过低
     * SEVERE_LOSS - 单次跌幅过大(踩雷)
     * MANUAL - 手动屏蔽
     */
    private String reason;

    /** 原因详情描述 */
    private String reasonDetail;

    /** 黑名单到期日期（NULL=永久） */
    private LocalDate blacklistUntil;

    /** 创建方式: AUTO/MANUAL */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
