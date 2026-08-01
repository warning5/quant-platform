package com.quant.platform.auth.dto;

import lombok.Data;

/**
 * 小程序登录请求（wx.login 得到的 code）
 */
@Data
public class WechatMiniLoginRequest {
    private String code;
}
