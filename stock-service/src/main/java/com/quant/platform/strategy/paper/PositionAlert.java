package com.quant.platform.strategy.paper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 模拟盘持仓预警实体
 */
@Data
@TableName("position_alert")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionAlert {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    private String code;
    private String name;

    /** 预警类型: MA_BREAK(均线跌破) / DROP(大跌) / NOTICE(公告) / REPORT(研报) */
    @TableField("alert_type")
    private String alertType;

    /** 级别: CRITICAL / WARNING / INFO */
    @TableField("alert_level")
    private String alertLevel;

    private String title;

    private String detail;

    @TableField("alert_date")
    private LocalDate alertDate;

    @TableField("is_read")
    private Boolean isRead;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
