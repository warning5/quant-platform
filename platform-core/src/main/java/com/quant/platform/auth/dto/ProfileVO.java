package com.quant.platform.auth.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人中心资料视图（不含 password 等敏感字段）
 */
@Data
public class ProfileVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private LocalDateTime lastLoginTime;
}
