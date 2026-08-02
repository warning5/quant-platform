package com.quant.platform.auth.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 小程序登录请求（wx.login 得到的 code）
 */
@Data
public class WechatMiniLoginRequest {
    @NotBlank(message = "微信登录code不能为空")
    private String code;
}
