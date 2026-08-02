package com.quant.platform.auth.dto;

import lombok.Data;

/**
 * 个人资料更新请求（仅更新当前登录用户自己的资料）
 * 字段均为可选：仅当传入非空字符串时才覆盖对应列
 */
@Data
public class UpdateProfileRequest {
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
}
