package com.quant.platform.auth.dto;

import lombok.Data;

/**
 * 账号密码登录请求
 */
@Data
public class LoginRequest {
    private String username;
    private String password;
}
