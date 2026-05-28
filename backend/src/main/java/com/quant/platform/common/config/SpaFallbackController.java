package com.quant.platform.common.config;

import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * SPA 路由回退：浏览器刷新 React Router 路径时返回 index.html。
 * <p>
 * 直接返回 ClassPathResource，不走 forward，避免 DispatcherServlet 重新匹配导致 StackOverflow。
 * 优先级设为最低，API Controller 先匹配。
 * </p>
 */
@Controller
public class SpaFallbackController implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 匹配所有未被 API Controller 处理的 GET 请求，返回 index.html。
     * 静态资源（含 "." 的路径）由 ResourceHttpRequestHandler 优先处理，
     * 不会进入此方法。返回 Resource 而非 "forward:..." 是关键 ——
     * forward 会重新进入 DispatcherServlet，导致 /** 递归匹配。
     */
    @RequestMapping("/**")
    @ResponseBody
    public Resource fallback() {
        return new ClassPathResource("static/index.html");
    }
}
