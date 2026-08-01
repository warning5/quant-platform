package com.quant.platform.notification;

import com.quant.platform.recommendation.domain.StockRecommendation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 通知推送服务
 * 支持: Server酱(PushPlus)/企业微信Webhook/钉钉Webhook
 * 配置: application.yml 中 quant.notification.*
 */
@Slf4j
@Service
public class NotificationService {

    private final RestTemplate restTemplate = new RestTemplate();

    /** 推送渠道: none / serverchan / wecom / dingtalk（默认值，实际以 notification_config 表为准） */
    @Value("${quant.notification.channel:none}")
    private String channel;

    /** 通知配置服务（从 DB 读取，前端可动态配置）。懒注入避免循环依赖风险。 */
    @Autowired(required = false)
    private NotificationConfigService configService;

    /** Server酱 SendKey */
    @Value("${quant.notification.serverchan.sendkey:}")
    private String serverChanSendKey;

    /** 企业微信 Webhook URL */
    @Value("${quant.notification.wecom.webhook-url:}")
    private String wecomWebhookUrl;

    /** 钉钉 Webhook URL */
    @Value("${quant.notification.dingtalk.webhook-url:}")
    private String dingtalkWebhookUrl;

    /** 钉钉加签密钥（可选） */
    @Value("${quant.notification.dingtalk.secret:}")
    private String dingtalkSecret;

    /** 发送紧急提醒（买入信号/止损/止盈等） */
    public boolean sendAlert(String message) {
        NotificationConfig cfg = effectiveConfig();
        if (!cfg.isEnabled()) {
            log.info("[Notification] 通知渠道未配置或未启用, 跳过提醒: {}",
                message != null ? message.substring(0, Math.min(50, message.length())) : "");
            return false;
        }
        String title = "⚡ 任务告警 | " + LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        return dispatch(cfg, title, message);
    }

    /** 发送每日持仓报告 */
    public boolean sendDailyReport(java.util.Map<String, Object> report) {
        NotificationConfig cfg = effectiveConfig();
        if (!cfg.isEnabled()) {
            log.info("[Notification] 通知渠道未配置或未启用, 跳过报告推送");
            return false;
        }
        String title = String.format("📋 每日持仓报告 | %s", report.getOrDefault("date", ""));
        String content = String.format("## 每日持仓报告 %s\n\n", report.get("date")) +
                String.format("- 持仓数: %s\n", report.get("openCount")) +
                String.format("- 总市值: %s\n", report.get("totalMarketValue")) +
                String.format("- 总盈亏: %s (%s%%)\n", report.get("totalProfitLoss"), report.get("totalProfitLossPct")) +
                String.format("- 今日平仓: %s\n", report.get("closedTodayCount"));

        return dispatch(cfg, title, content);
    }

    public void sendDailyRecommendation(List<StockRecommendation> recommendations, String factorProfile) {
        NotificationConfig cfg = effectiveConfig();
        if (!cfg.isEnabled()) {
            log.info("[Notification] 通知渠道未配置或未启用, 跳过推送");
            return;
        }

        String title = String.format("📊 每日推荐 | %s | %s", factorProfile, LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        String content = buildRecommendationMarkdown(recommendations, factorProfile);

        dispatch(cfg, title, content);
    }

    /**
     * 构建推荐结果的 Markdown 内容
     */
    private String buildRecommendationMarkdown(List<StockRecommendation> recommendations, String factorProfile) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 每日推荐报告\n\n");
        sb.append(String.format("- **策略**: %s\n", factorProfile));
        sb.append(String.format("- **日期**: %s\n", LocalDate.now()));
        sb.append(String.format("- **推荐数量**: %d\n\n", recommendations.size()));

        sb.append("| 序号 | 代码 | 名称 | 综合得分 | 建议买价 | 行业 |\n");
        sb.append("|:---:|:---:|:---:|:---:|:---:|:---:|\n");

        int idx = 1;
        for (StockRecommendation rec : recommendations) {
            sb.append(String.format("| %d | %s | %s | %.1f | %s | %s |\n",
                    idx++,
                    rec.getStockCode() != null ? rec.getStockCode() : "-",
                    rec.getStockName() != null ? rec.getStockName() : "-",
                    rec.getFinalScore() != null ? rec.getFinalScore() * 100 : 0,
                    rec.getSuggestedBuyPrice() != null ? String.format("%.2f", rec.getSuggestedBuyPrice()) : "-",
                    rec.getIndustry() != null ? rec.getIndustry() : "-"
            ));
        }

        // 风险提示
        sb.append("\n> ⚠️ 以上推荐仅供参考，不构成投资建议。投资有风险，入市需谨慎。\n");

        // 买入理由摘要（如果有）
        List<StockRecommendation> withReason = recommendations.stream()
                .filter(r -> r.getBuyReason() != null && !r.getBuyReason().isEmpty())
                .limit(3)
                .toList();
        if (!withReason.isEmpty()) {
            sb.append("\n### 重点标的\n\n");
            for (StockRecommendation rec : withReason) {
                sb.append(String.format("**%s(%s)**: %s\n\n",
                        rec.getStockName(), rec.getStockCode(), rec.getBuyReason()));
            }
        }

        return sb.toString();
    }

    /**
     * 获取生效通知配置：优先 notification_config 表（前端可配），
     * 若服务不可用则回退到 application.yml 的 @Value 默认。
     */
    private NotificationConfig effectiveConfig() {
        if (configService != null) {
            NotificationConfig cfg = configService.get();
            if (cfg != null) return cfg;
        }
        // 回退：用 @Value 字段拼一个配置对象
        NotificationConfig fallback = new NotificationConfig();
        fallback.setChannel(channel);
        fallback.setServerchanSendkey(serverChanSendKey);
        fallback.setWecomWebhookUrl(wecomWebhookUrl);
        fallback.setDingtalkWebhookUrl(dingtalkWebhookUrl);
        fallback.setDingtalkSecret(dingtalkSecret);
        fallback.setEnabled(!"none".equals(channel) && channel != null && !channel.isEmpty());
        return fallback;
    }

    /** 根据生效配置分发到对应渠道 */
    private boolean dispatch(NotificationConfig cfg, String title, String content) {
        return switch (cfg.getChannel().toLowerCase()) {
            case "serverchan" -> sendServerChan(title, content);
            case "wecom" -> sendWeComMarkdown(title, content);
            case "dingtalk" -> sendDingTalkMarkdown(title, content);
            default -> {
                log.warn("[Notification] 未知推送渠道: {}", cfg.getChannel());
                yield false;
            }
        };
    }

    /** 生效的 Server酱 SendKey（DB 优先） */
    private String effectiveServerChanSendKey() {
        NotificationConfig cfg = effectiveConfig();
        return cfg.getServerchanSendkey() != null && !cfg.getServerchanSendkey().isEmpty()
            ? cfg.getServerchanSendkey() : serverChanSendKey;
    }
    private String effectiveWecomUrl() {
        NotificationConfig cfg = effectiveConfig();
        return cfg.getWecomWebhookUrl() != null && !cfg.getWecomWebhookUrl().isEmpty()
            ? cfg.getWecomWebhookUrl() : wecomWebhookUrl;
    }
    private String effectiveDingtalkUrl() {
        NotificationConfig cfg = effectiveConfig();
        return cfg.getDingtalkWebhookUrl() != null && !cfg.getDingtalkWebhookUrl().isEmpty()
            ? cfg.getDingtalkWebhookUrl() : dingtalkWebhookUrl;
    }
    /**
     * Server酱推送
     * API: <a href="https://sctapi.ftqq.com/">...</a>{sendkey}.send
     */
    private boolean sendServerChan(String title, String content) {
        String key = effectiveServerChanSendKey();
        if (key == null || key.isEmpty()) {
            log.warn("[Notification] Server酱SendKey未配置");
            return false;
        }
        try {
            String url = "https://sctapi.ftqq.com/" + key + ".send";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = "title=" + encode(title) + "&desp=" + encode(content);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            boolean success = response.getStatusCode().is2xxSuccessful();
            log.info("[Notification] Server酱推送: {}", success ? "成功" : "失败");
            return success;
        } catch (Exception e) {
            log.error("[Notification] Server酱推送异常", e);
            return false;
        }
    }

    /**
     * 企业微信 Webhook 推送（Markdown 格式）
     */
    private boolean sendWeComMarkdown(String title, String content) {
        String url = effectiveWecomUrl();
        if (url == null || url.isEmpty()) {
            log.warn("[Notification] 企业微信Webhook URL未配置");
            return false;
        }
        try {
            // 企业微信 Markdown 有字数限制(4096)，超长则截断
            String trimmedContent = content.length() > 3800 ? content.substring(0, 3800) + "\n\n...(内容过长已截断)" : content;

            String jsonBody = String.format(
                "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"%s\\n\\n%s\"}}",
                escapeJson(title), escapeJson(trimmedContent)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(wecomWebhookUrl, entity, String.class);
            boolean success = response.getStatusCode().is2xxSuccessful();
            log.info("[Notification] 企业微信推送: {}", success ? "成功" : "失败");
            return success;
        } catch (Exception e) {
            log.error("[Notification] 企业微信推送异常", e);
            return false;
        }
    }

    /**
     * 钉钉 Webhook 推送（Markdown 格式）
     */
    private boolean sendDingTalkMarkdown(String title, String content) {
        String url = effectiveDingtalkUrl();
        if (url == null || url.isEmpty()) {
            log.warn("[Notification] 钉钉Webhook URL未配置");
            return false;
        }
        try {
            // 钉钉 Markdown 标题不能包含特殊字符
            String cleanTitle = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-\\s|]", "");

            String jsonBody = String.format(
                "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"%s\",\"text\":\"%s\\n\\n%s\"}}",
                escapeJson(cleanTitle), escapeJson(cleanTitle), escapeJson(content)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            boolean success = response.getStatusCode().is2xxSuccessful();
            log.info("[Notification] 钉钉推送: {}", success ? "成功" : "失败");
            return success;
        } catch (Exception e) {
            log.error("[Notification] 钉钉推送异常", e);
            return false;
        }
    }

    /** 公开测试方法：发送一条测试告警（供前端"发送测试"按钮调用） */
    public boolean testSend(String message) {
        return sendAlert(message);
    }

    /** URL编码 */
    private String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** JSON字符串转义 */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
