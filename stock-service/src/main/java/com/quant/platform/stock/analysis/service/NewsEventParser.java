package com.quant.platform.stock.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.llm.LlmService;
import com.quant.platform.stock.analysis.domain.EventTag;
import com.quant.platform.stock.analysis.mapper.NewsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 新闻事件LLM解析服务
 * 定时扫描 stock_news 中未解析的新闻，用LLM提取结构化事件，
 * 回写 event_tag / sentiment_score 字段，供估值修复和事件驱动策略使用。
 * 事件标签体系见 {@link EventTag}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsEventParser {

    private final LlmService llmService;
    private final NewsMapper newsMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 每次批量处理的最大新闻条数 */
    private static final int BATCH_SIZE = 30;
    /** LLM解析的最大内容长度（字符），超长截断 */
    private static final int MAX_CONTENT_LEN = 800;

    /**
     * 定时任务：每10分钟扫描并解析未标记的新闻
     * 仅在工作日9:00-18:00执行
     * 无未解析新闻时直接返回，不调用LLM
     */
    @Scheduled(cron = "0 */10 9-18 ? * MON-FRI")
    public void scheduledParse() {
        if (!llmService.isEnabled()) {
            log.debug("[NewsEventParser] LLM未启用，跳过新闻解析");
            return;
        }
        log.info("[NewsEventParser] 开始定时解析未标记新闻...");
        int total = parseUnprocessedNews();
        log.info("[NewsEventParser] 定时解析完成，共处理 {} 条", total);
    }

    /**
     * 手动触发解析（供前端/调试使用）
     * @return 解析的新闻条数
     */
    public int parseUnprocessedNews() {
        // 1. 查询 event_tag IS NULL 的新闻（按发布时间倒序，取最新BATCH_SIZE条）
        List<Map<String, Object>> newsList = findUnprocessedNews();
        if (newsList.isEmpty()) {
            log.info("[NewsEventParser] 无未解析新闻");
            return 0;
        }

        log.info("[NewsEventParser] 找到 {} 条未解析新闻", newsList.size());

        // 2. 逐条调用LLM解析（控制速率避免API限流）
        int parsed = 0;
        int errors = 0;
        for (Map<String, Object> news : newsList) {
            try {
                Long id = ((Number) news.get("id")).longValue();
                String title = (String) news.get("title");
                String content = (String) news.get("content");

                // 截断过长的内容
                if (content != null && content.length() > MAX_CONTENT_LEN) {
                    content = content.substring(0, MAX_CONTENT_LEN);
                }

                ParsedEvent event = parseSingleNews(title, content);
                if (event != null) {
                    updateNewsEvent(id, event);
                    parsed++;
                } else {
                    // LLM返回null，标记为OTHER避免重复处理
                    updateNewsEvent(id, new ParsedEvent("OTHER", 0.0, "neutral"));
                    errors++;
                }

                // 限速：每次调用间隔300ms
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[NewsEventParser] 解析新闻异常 id={}: {}", news.get("id"), e.getMessage());
                errors++;
            }
        }

        log.info("[NewsEventParser] 解析完成: 成功={}, 失败={}", parsed, errors);
        return parsed;
    }

    /**
     * 获取指定股票近N天的利好事件标签（供估值修复策略使用）
     * @param code 股票代码（纯代码）
     * @param days 近N天
     * @return 利好事件标签列表，如 ["BUYBACK","INCREASE"]
     */
    public List<String> getRecentBullishEvents(String code, int days) {
        List<Map<String, Object>> newsList = newsMapper.selectLatestNews(code, 200);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        Set<String> bullishCodes = EventTag.getBullishCodes();

        return newsList.stream()
                .filter(n -> {
                    String tag = (String) n.get("event_tag");
                    return tag != null && !tag.isEmpty() && bullishCodes.contains(tag);
                })
                .filter(n -> {
                    Object pubDate = n.get("publish_date");
                    if (pubDate == null) return true; // 无日期的保留
                    try {
                        LocalDateTime dt = LocalDateTime.parse(pubDate.toString().replace(" ", "T"));
                        return dt.isAfter(cutoff);
                    } catch (Exception e) {
                        return true;
                    }
                })
                .map(n -> (String) n.get("event_tag"))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取指定股票近N天的事件评分（供策略评分使用）
     * @param code 股票代码
     * @param days 近N天
     * @return -1.0(利空) ~ 1.0(利好)
     */
    public double getEventSentimentScore(String code, int days) {
        List<Map<String, Object>> newsList = newsMapper.selectLatestNews(code, 200);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        Set<String> bullishCodes = EventTag.getBullishCodes();
        Set<String> bearishCodes = EventTag.getBearishCodes();

        double totalScore = 0.0;
        int count = 0;
        for (Map<String, Object> n : newsList) {
            String tag = (String) n.get("event_tag");
            if (tag == null || tag.isEmpty()) continue;

            Object pubDate = n.get("publish_date");
            if (pubDate != null) {
                try {
                    LocalDateTime dt = LocalDateTime.parse(pubDate.toString().replace(" ", "T"));
                    if (!dt.isAfter(cutoff)) continue;
                } catch (Exception ignored) {}
            }

            if (bullishCodes.contains(tag)) {
                totalScore += 1.0;
            } else if (bearishCodes.contains(tag)) {
                totalScore -= 1.0;
            }
            count++;
        }

        if (count == 0) return 0.0;
        // 归一化到 -1 ~ 1
        return Math.max(-1.0, Math.min(1.0, totalScore / Math.max(1, count / 2.0)));
    }

    // ── 私有方法 ──────────────────────────────────────────────

    /**
     * 查询未解析的新闻（event_tag IS NULL，取最新BATCH_SIZE条）
     */
    private List<Map<String, Object>> findUnprocessedNews() {
        return jdbcTemplate.queryForList(
                "SELECT id, title, content FROM stock_news " +
                "WHERE event_tag IS NULL AND title IS NOT NULL " +
                "ORDER BY publish_date DESC LIMIT ?",
                BATCH_SIZE
        );
    }

    /**
     * 用LLM解析单条新闻，提取结构化事件
     */
    private ParsedEvent parseSingleNews(String title, String content) {
        String systemPrompt = """
                你是一个A股新闻事件分析专家。请根据新闻标题和内容，提取结构化事件信息。

                事件类型必须是以下之一：
                %s

                请输出JSON格式：
                {"event_type":"BUYBACK","sentiment":0.8,"direction":"positive"}
                sentiment范围-1到1，1=极利好，-1=极利空，0=中性
                direction为positive/negative/neutral
                只输出JSON，不要其他内容。""".formatted(EventTag.toPromptText());

        String userPrompt = "新闻标题：" + title;
        if (content != null && !content.isBlank()) {
            userPrompt += "\n新闻内容：" + content;
        }

        try {
            JsonNode result = llmService.chatAsJson(systemPrompt, userPrompt);
            if (result == null) return null;

            String eventType = result.path("event_type").asText("OTHER");
            double sentiment = result.path("sentiment").asDouble(0.0);
            String direction = result.path("direction").asText("neutral");

            // 校验标签合法性
            EventTag eventTag = EventTag.fromCode(eventType);
            eventType = eventTag.getCode();
            // 校验sentiment范围
            sentiment = Math.max(-1.0, Math.min(1.0, sentiment));
            // 校验direction
            if (!Set.of("positive", "negative", "neutral").contains(direction)) {
                direction = "neutral";
            }

            return new ParsedEvent(eventType, sentiment, direction);
        } catch (Exception e) {
            log.warn("[NewsEventParser] LLM解析失败 title={}: {}", title, e.getMessage());
            return null;
        }
    }

    /**
     * 更新新闻的事件标签和情感分数
     */
    private void updateNewsEvent(Long id, ParsedEvent event) {
        String newsType = switch (event.direction) {
            case "positive" -> "positive";
            case "negative" -> "negative";
            default -> "neutral";
        };
        jdbcTemplate.update(
                "UPDATE stock_news SET event_tag = ?, sentiment_score = ?, news_type = ? WHERE id = ?",
                event.eventType, event.sentiment, newsType, id
        );
    }

    /**
     * 解析结果内部DTO
     */
    private record ParsedEvent(String eventType, double sentiment, String direction) {}
}
