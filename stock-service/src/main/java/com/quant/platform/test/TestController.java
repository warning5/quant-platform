package com.quant.platform.test;

import cn.dev33.satoken.annotation.SaCheckLogin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点
 * 用于验证后端是否正常运行，不包含敏感信息。
 *
 * 安全模型：
 * - dev / local 环境：SaTokenConfigure 将 /test/** 加入 excludes，匿名可访问（方便本地探针）。
 * - prod 环境：/test/** 不在 excludes，须经过 Sa-Token 登录校验；本类 @SaCheckLogin 显式声明
 *   "需认证"，确保生产环境不会以匿名身份命中该端点。
 * - 端点本身仅返回 "OK"，无敏感操作，风险极低。如需匿名健康探针，应在 prod 也放行
 *   /test/health 并叠加 IP 白名单，而非直接移除登录校验。
 */
@RestController
@RequestMapping("/test")
@SaCheckLogin
public class TestController {

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
