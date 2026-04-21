package com.quant.platform.stock.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票基本信息表
 */
@Data
@TableName("stock_info")
public class StockInfo implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 股票代码（不含市场标识，如：000001）
     */
    @TableField("code")
    private String code;

    /**
     * 股票名称
     */
    @TableField("name")
    private String name;

    /**
     * 市场（SH/SZ/BJ）
     */
    @TableField("market")
    private String market;

    /**
     * 所属行业
     */
    @TableField("industry")
    private String industry;

    /**
     * 上市日期
     */
    @TableField("list_date")
    private LocalDate listDate;

    /**
     * 是否沪深股通（0-否，1-是）
     */
    @TableField("is_hs")
    private Integer isHs;

    /**
     * 是否ST（0-否，1-是）
     */
    @TableField("is_st")
    private Integer isSt;

    /**
     * 总股本（股）
     */
    @TableField("total_share")
    private BigDecimal totalShare;

    /**
     * 流通股本（股）
     */
    @TableField("float_share")
    private BigDecimal floatShare;

    /**
     * 总市值（元）
     */
    @TableField("total_market_cap")
    private BigDecimal totalMarketCap;

    /**
     * 流通市值（元）
     */
    @TableField("float_market_cap")
    private BigDecimal floatMarketCap;

    /**
     * 市盈率（TTM）
     */
    @TableField("pe_ttm")
    private BigDecimal peTtm;

    /**
     * 市净率
     */
    @TableField("pb")
    private BigDecimal pb;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
