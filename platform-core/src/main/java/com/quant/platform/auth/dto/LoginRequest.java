package com.quant.platform.auth.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 账号密码登录请求
 */
@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;
    /** 图形验证码 id（渐进式：失败达阈值后必填） */
    private String captchaId;
    /** 图形验证码输入值 */
    private String captchaCode;
}
