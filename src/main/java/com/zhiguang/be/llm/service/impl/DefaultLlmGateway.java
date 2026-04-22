package com.zhiguang.be.llm.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.llm.LlmConfig;
import com.zhiguang.be.llm.config.LlmProperties;
import com.zhiguang.be.llm.service.LlmGateway;
import com.zhiguang.be.llm.service.RagAnswerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 LLM 网关实现。
 */
@Service
public class DefaultLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);

    private final ObjectMapper objectMapper;
    private final LlmProperties llmProperties;
    private final HttpClient httpClient;

    public DefaultLlmGateway(ObjectMapper objectMapper, LlmProperties llmProperties) {
        this.objectMapper = objectMapper;
        this.llmProperties = llmProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(llmProperties.getHttp().getTimeoutSeconds(), 1)))
                .build();
    }

    @Override
    public String currentModelName() {
        if (useHttpProvider()) {
            return llmProperties.getModelName();
        }
        return LlmConfig.MODEL_NAME;
    }

    @Override
    public String generateDescription(String content, int maxCodePoints) {
        if (useHttpProvider()) {
            try {
                Map<String, Object> input = new LinkedHashMap<String, Object>();
                input.put("content", content);
                input.put("maxCodePoints", maxCodePoints);
                String response = requestText("post_description", input);
                if (StringUtils.hasText(response)) {
                    return truncateByCodePoint(response.trim(), maxCodePoints);
                }
            } catch (Exception ex) {
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("调用 LLM 描述生成接口失败", ex);
                }
                log.warn("HTTP LLM 描述生成失败，回退模板实现: {}", ex.getMessage());
            }
        }
        return templateDescription(content, maxCodePoints);
    }

    @Override
    public String generateRagAnswer(String question, List<RagAnswerService.Context> contexts) {
        if (useHttpProvider()) {
            try {
                Map<String, Object> input = new LinkedHashMap<String, Object>();
                input.put("question", question);
                input.put("contexts", contexts);
                String response = requestText("rag_answer", input);
                if (StringUtils.hasText(response)) {
                    return response.trim();
                }
            } catch (Exception ex) {
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("调用 LLM 问答接口失败", ex);
                }
                log.warn("HTTP LLM 问答生成失败，回退模板实现: {}", ex.getMessage());
            }
        }
        return templateRagAnswer(question, contexts);
    }

    private boolean useHttpProvider() {
        return "http".equalsIgnoreCase(llmProperties.getProvider())
                && StringUtils.hasText(llmProperties.getHttp().getEndpoint());
    }

    private String requestText(String task, Map<String, Object> input) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", llmProperties.getModelName());
        payload.put("task", task);
        payload.put("input", input);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(llmProperties.getHttp().getEndpoint()))
                .timeout(Duration.ofSeconds(Math.max(llmProperties.getHttp().getTimeoutSeconds(), 1)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (StringUtils.hasText(llmProperties.getHttp().getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + llmProperties.getHttp().getApiKey().trim());
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP 响应异常，status=" + response.statusCode());
        }
        return extractText(objectMapper.readTree(response.body()));
    }

    private String extractText(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.hasNonNull("answer")) {
            return root.get("answer").asText();
        }
        if (root.hasNonNull("content")) {
            return root.get("content").asText();
        }
        if (root.has("output")) {
            JsonNode output = root.get("output");
            if (output.isTextual()) {
                return output.asText();
            }
            if (output.hasNonNull("text")) {
                return output.get("text").asText();
            }
        }
        if (root.has("data")) {
            JsonNode data = root.get("data");
            if (data.hasNonNull("answer")) {
                return data.get("answer").asText();
            }
            if (data.hasNonNull("content")) {
                return data.get("content").asText();
            }
        }
        if (root.has("choices") && root.get("choices").isArray() && !root.get("choices").isEmpty()) {
            JsonNode choice = root.get("choices").get(0);
            if (choice.hasNonNull("text")) {
                return choice.get("text").asText();
            }
            if (choice.has("message") && choice.get("message").hasNonNull("content")) {
                return choice.get("message").get("content").asText();
            }
        }
        return null;
    }

    private String templateDescription(String content, int maxCodePoints) {
        String normalized = normalize(content);
        String sentence = firstSentence(normalized);
        if (sentence.isEmpty()) {
            sentence = normalized;
        }
        return truncateByCodePoint(sentence, maxCodePoints);
    }

    private String templateRagAnswer(String question, List<RagAnswerService.Context> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "当前没有检索到和“" + question + "”直接相关的公开内容。"
                    + "你可以换一个更具体的问法，或者先到邻里里补充相关内容后再来提问。";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("我先根据当前社区里已经公开的内容做一个基础回答。\n");
        answer.append("问题：").append(question).append("\n\n");
        answer.append("结合当前命中的帖子，可以先得到这些信息：\n");
        int limit = Math.min(contexts.size(), LlmConfig.RAG_CONTEXT_LIMIT);
        for (int i = 0; i < limit; i++) {
            RagAnswerService.Context context = contexts.get(i);
            answer.append(i + 1)
                    .append(". 来自《")
                    .append(context.title())
                    .append("》：")
                    .append(context.content().replace('\n', ' ').trim())
                    .append("\n");
        }
        answer.append("\n这版回答基于简化检索结果生成，后续接入真实大模型后，表达会更自然、总结也会更完整。");
        return answer.toString();
    }

    private String normalize(String content) {
        return Normalizer.normalize(content, Normalizer.Form.NFKC)
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstSentence(String content) {
        String[] parts = content.split("[。！？!?]");
        for (String part : parts) {
            String normalized = part.trim();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private String truncateByCodePoint(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        int count = 0;
        while (index < value.length() && count < maxCodePoints) {
            int codePoint = value.codePointAt(index);
            builder.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            count++;
        }
        return builder.toString();
    }
}
