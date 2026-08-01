package com.quant.platform.notification;

import lombok.Data;

/**
 * 通知配置（对应表 notification_config，单行 id=1）
 */
@Data
public class NotificationConfig {
    private Integer id = 1;
    private String channel = "none";            // none / serverchan / wecom / dingtalk
    private String serverchanSendkey;
    private String wecomWebhookUrl;
    private String dingtalkWebhookUrl;
    private String dingtalkSecret;
    private Boolean enabled = false;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled) && channel != null && !"none".equals(channel);
    }
}
