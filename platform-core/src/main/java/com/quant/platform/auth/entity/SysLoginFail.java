package com.quant.platform.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录失败计数与锁定（账号维度 / IP 维度共用）
 */
@Data
@TableName("sys_login_fail")
public class SysLoginFail {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 维度键: U:username|ip 或 I:ip */
    private String lockKey;

    /** 连续失败次数 */
    private Integer failCount;

    private LocalDateTime firstFailTime;

    private LocalDateTime lastFailTime;

    /** 锁定截止时间, NULL=未锁定 */
    private LocalDateTime lockedUntil;
}
