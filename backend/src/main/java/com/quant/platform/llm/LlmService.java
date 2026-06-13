package com.quant.platform.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 统一 LLM 接入服务
 * 支持 DeepSeek V4 Flash/Pro（OpenAI 兼容接口），也支持 Ollama / Qwen 等
 * 配置项: llm.base-url / llm.api-key / llm.model 等
 * API文档: https://platform.deepseek.com/api-docs/chat
 */
@Slf4j
@Service
public class LlmService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${llm.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:deepseek-v4-flash}")
    private String defaultModel;

    @Value("${llm.max-tokens:4096}")
    private int maxTokens;

    @Value("${llm.temperature:0.3}")
    private double temperature;

    @Value("${llm.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${llm.enabled:false}")
    private boolean enabled;

    public LlmService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 LLM Chat API，返回原始文本（使用默认模型，非思考模式）
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, defaultModel, false);
    }

    /**
     * 调用 LLM Chat API，指定模型（非思考模式）
     */
    public String chat(String systemPrompt, String userPrompt, String model) {
        return chat(systemPrompt, userPrompt, model, false);
    }

    /**
     * 调用 LLM Chat API，完整参数
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     * @param model        模型名称
     * @param enableThinking 是否开启思考模式（DeepSeek V4 支持）
     */
    public String chat(String systemPrompt, String userPrompt, String model, boolean enableThinking) {
        if (!enabled || apiKey == null || apiKey.isEmpty()) {
            log.warn("[LlmService] LLM未启用或API Key未配置");
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);
            body.put("stream", false);

            // DeepSeek V4 思考模式参数
            if (enableThinking) {
                body.put("enable_thinking", true);
            }

            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));
            body.put("messages", messages);

            String apiUrl = baseUrl + "/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            log.info("[LlmService] 调用LLM API: url={}, model={}, thinking={}, inputLen={}",
                    apiUrl, model, enableThinking, userPrompt.length());

            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    String content = choices.get(0).get("message").get("content").asText();
                    // 记录 token 用量
                    JsonNode usage = root.get("usage");
                    if (usage != null) {
                        log.info("[LlmService] API调用成功: model={}, outputLen={}, prompt_tokens={}, completion_tokens={}",
                                model, content.length(),
                                usage.path("prompt_tokens").asInt(-1),
                                usage.path("completion_tokens").asInt(-1));
                    } else {
                        log.info("[LlmService] API调用成功: model={}, outputLen={}", model, content.length());
                    }
                    return content;
                }
            }

            log.warn("[LlmService] API返回异常: status={}", response.getStatusCode());
            return null;

        } catch (Exception e) {
            log.error("[LlmService] API调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 调用 LLM API 并解析为 JSON 对象
     * 从LLM返回的文本中提取JSON部分（可能被markdown代码块包裹）
     */
    public JsonNode chatAsJson(String systemPrompt, String userPrompt) {
        return chatAsJson(systemPrompt, userPrompt, defaultModel);
    }

    public JsonNode chatAsJson(String systemPrompt, String userPrompt, String model) {
        return chatAsJson(systemPrompt, userPrompt, model, false);
    }

    public JsonNode chatAsJson(String systemPrompt, String userPrompt, String model, boolean enableThinking) {
        String raw = chat(systemPrompt, userPrompt, model, enableThinking);
        if (raw == null) return null;

        try {
            String jsonStr = extractJson(raw);
            return objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            // 首次解析失败，尝试截断修复
            log.warn("[LlmService] LLM返回内容首次解析失败，尝试截断修复... (原始长度={})", raw.length());
            try {
                String repaired = repairTruncatedJson(raw);
                if (repaired != null) {
                    JsonNode node = objectMapper.readTree(repaired);
                    log.info("[LlmService] 截断修复成功，已解析JSON");
                    return node;
                }
            } catch (Exception e2) {
                log.warn("[LlmService] 截断修复也失败: {}", e2.getMessage());
            }
            log.warn("[LlmService] LLM返回内容无法解析为JSON: {}", raw.substring(0, Math.min(300, raw.length())));
            return null;
        }
    }

    /**
     * 从LLM返回文本中提取JSON
     * 支持: 纯JSON、```json ... ```包裹、``` ... ```包裹
     */
    private String extractJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastBacktick = trimmed.lastIndexOf("```");
            if (lastBacktick > 0) {
                trimmed = trimmed.substring(0, lastBacktick);
            }
            trimmed = trimmed.trim();
        }

        if (trimmed.contains("{") && trimmed.contains("}")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
        }

        return trimmed;
    }

    /**
     * 修复被截断的JSON字符串
     * 常见场景：LLM因max_tokens限制导致输出截断，JSON不完整
     * 策略：找到最后一个完整的key:value对，截断后面的部分，然后补齐缺失的}和"
     */
    private String repairTruncatedJson(String text) {
        String trimmed = text.trim();

        // 先提取JSON部分（去掉markdown包裹）
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            trimmed = trimmed.trim();
        }

        if (!trimmed.contains("{")) return null;

        int start = trimmed.indexOf('{');
        String json = trimmed.substring(start);

        // 如果已经能正常解析，不需要修复
        try {
            objectMapper.readTree(json);
            return json;
        } catch (Exception ignored) {}

        // 计算未闭合的引号和大括号
        // 策略：从后向前找到最后一个完整的 value，然后截断补齐
        StringBuilder sb = new StringBuilder(json);

        // 去掉末尾不完整的部分（截断通常发生在字符串值中间）
        // 找到最后一个逗号或冒号的位置，判断是否在值中间被截断
        int lastComma = sb.lastIndexOf(",");
        int lastColon = sb.lastIndexOf(":");
        int lastOpenBrace = sb.lastIndexOf("{");
        int lastCloseBrace = sb.lastIndexOf("}");
        int lastOpenBracket = sb.lastIndexOf("[");
        int lastCloseBracket = sb.lastIndexOf("]");
        int lastQuote = sb.lastIndexOf("\"");

        // 情况1: 截断在字符串值中间（如 "logic": "公司估值处于3年...）
        // 找到最后一个 key: value 模式中的完整 value
        // 从后向前找，去掉不完整的键值对
        if (lastColon > lastCloseBrace && lastColon > lastCloseBracket) {
            // 最后一个冒号后面是值，值可能不完整
            // 找到这个值的起始key
            int valueStart = lastColon + 1;
            String afterColon = sb.substring(valueStart).trim();

            if (afterColon.startsWith("\"")) {
                // 值是字符串类型，检查引号是否闭合
                // 找第二个引号（值的结束引号）
                int valueQuoteEnd = -1;
                boolean escaped = false;
                for (int i = valueStart + 1; i < sb.length(); i++) {
                    char c = sb.charAt(i);
                    if (escaped) { escaped = false; continue; }
                    if (c == '\\') { escaped = true; continue; }
                    if (c == '"') { valueQuoteEnd = i; break; }
                }

                if (valueQuoteEnd == -1) {
                    // 字符串值未闭合（被截断），需要补上结束引号
                    // 截断到冒号前一个完整键值对的末尾
                    // 更好的策略：补齐当前字符串的引号
                    sb.append("\"");
                }
            }

            // 去掉这个不完整键值对前面的逗号问题
            // 如果值后面没有逗号和更多内容，说明这是截断点
        }

        // 补齐未闭合的括号
        int openBraces = 0, openBrackets = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') openBraces++;
            else if (c == '}') openBraces--;
            else if (c == '[') openBrackets++;
            else if (c == ']') openBrackets--;
        }

        // 如果在字符串内被截断，先闭合字符串
        if (inString) {
            sb.append("\"");
        }

        // 补齐缺失的闭合括号
        // 重新计算（因为可能刚补了引号）
        openBraces = 0; openBrackets = 0;
        inString = false; escape = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') openBraces++;
            else if (c == '}') openBraces--;
            else if (c == '[') openBrackets++;
            else if (c == ']') openBrackets--;
        }

        for (int i = 0; i < openBrackets; i++) sb.append("]");
        for (int i = 0; i < openBraces; i++) sb.append("}");

        String repaired = sb.toString();
        log.debug("[LlmService] 截断修复: 原始长度={}, 修复后长度={}, 补了{}个]和{}个}",
                json.length(), repaired.length(), Math.max(0, openBrackets), Math.max(0, openBraces));

        // 验证修复后能否解析
        try {
            objectMapper.readTree(repaired);
            return repaired;
        } catch (Exception e) {
            // 修复失败，返回null
            return null;
        }
    }

    /**
     * 批量推理：对多只股票分别调用LLM
     * @param prompts key=stockCode, value=userPrompt
     * @return key=stockCode, value=解析后的JSON
     */
    public Map<String, JsonNode> batchChatAsJson(String systemPrompt, Map<String, String> prompts) {
        return batchChatAsJson(systemPrompt, prompts, defaultModel);
    }

    public Map<String, JsonNode> batchChatAsJson(String systemPrompt, Map<String, String> prompts, String model) {
        return batchChatAsJson(systemPrompt, prompts, model, false);
    }

    public Map<String, JsonNode> batchChatAsJson(String systemPrompt, Map<String, String> prompts,
                                                  String model, boolean enableThinking) {
        Map<String, JsonNode> results = new LinkedHashMap<>();
        int idx = 0;
        for (Map.Entry<String, String> entry : prompts.entrySet()) {
            idx++;
            String code = entry.getKey();
            String userPrompt = entry.getValue();
            log.info("[LlmService] 批量推理进度: {}/{}, code={}", idx, prompts.size(), code);

            JsonNode result = chatAsJson(systemPrompt, userPrompt, model, enableThinking);
            if (result != null) {
                results.put(code, result);
            }

            // 限速：避免API限流
            if (idx < prompts.size()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return results;
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
