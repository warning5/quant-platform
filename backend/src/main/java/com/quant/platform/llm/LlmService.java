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

    @Value("${llm.max-tokens:2048}")
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
            log.warn("[LlmService] LLM返回内容无法解析为JSON: {}", raw.substring(0, Math.min(200, raw.length())));
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
