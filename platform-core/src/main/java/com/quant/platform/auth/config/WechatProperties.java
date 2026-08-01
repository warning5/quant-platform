package com.quant.platform.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信联合登录配置（从 application.yml 的 wechat.* 读取，敏感凭据经环境变量注入）
 */
@Component
@ConfigurationProperties(prefix = "wechat")
@Data
public class WechatProperties {

    /** 前端基础地址，用于回调节点把 token 带回到 SPA */
    private String frontendBaseUrl = "http://localhost:3000";

    private Web web = new Web();

    private Mp mp = new Mp();

    private Mini mini = new Mini();

    @Data
    public static class Web {
        private String appId = "";
        private String appSecret = "";
        private String redirectUri = "http://localhost:8080/api/auth/wechat/website/callback";
    }

    @Data
    public static class Mp {
        private String appId = "";
        private String appSecret = "";
        private String redirectUri = "http://localhost:8080/api/auth/wechat/mp/callback";
    }

    @Data
    public static class Mini {
        private String appId = "";
        private String appSecret = "";
    }
}
