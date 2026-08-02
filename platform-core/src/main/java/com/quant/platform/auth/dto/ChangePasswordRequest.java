package com.quant.platform.auth.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 自助改密请求（登录用户修改自己的密码）
 */
@Data
public class ChangePasswordRequest {
    /** 原密码（用于校验身份） */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;
    /** 新密码（需满足复杂度：长度 8-64 且同时含字母和数字） */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
