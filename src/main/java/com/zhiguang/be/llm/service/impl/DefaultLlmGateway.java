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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("content", content);
                input.put("maxCodePoints", maxCodePoints);
                String response = requestText("post_description", input);
                if (StringUtils.hasText(response)) {
                    return response.trim();
                }
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("LLM description response did not contain text");
                }
            } catch (Exception ex) {
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Failed to call LLM description API", ex);
                }
                log.warn("HTTP description generation failed, falling back to template: {}", ex.getMessage());
            }
        }
        return templateDescription(content, maxCodePoints);
    }

    @Override
    public String generateRagAnswer(String question, List<RagAnswerService.Context> contexts) {
        if (useHttpProvider()) {
            try {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("question", question);
                input.put("contexts", contexts);
                String response = requestText("rag_answer", input);
                if (StringUtils.hasText(response)) {
                    return response.trim();
                }
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("LLM RAG response did not contain text");
                }
            } catch (Exception ex) {
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Failed to call LLM RAG API", ex);
                }
                log.warn("HTTP RAG generation failed, falling back to template: {}", ex.getMessage());
            }
        }
        return templateRagAnswer(question, contexts);
    }

    private boolean useHttpProvider() {
        return "http".equalsIgnoreCase(llmProperties.getProvider())
                && StringUtils.hasText(llmProperties.getHttp().getEndpoint());
    }

    private String requestText(String task, Map<String, Object> input) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", llmProperties.getModelName());
        payload.put("task", task);
        payload.put("input", input);
        payload.put("messages", buildMessages(task, input));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(llmProperties.getHttp().getEndpoint()))
                .timeout(Duration.ofSeconds(Math.max(llmProperties.getHttp().getTimeoutSeconds(), 1)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (StringUtils.hasText(llmProperties.getHttp().getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + llmProperties.getHttp().getApiKey().trim());
        }

        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP status is not successful: " + response.statusCode());
        }
        return extractText(objectMapper.readTree(response.body()));
    }

    private List<Map<String, String>> buildMessages(String task, Map<String, Object> input) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if ("post_description".equals(task)) {
            String content = stringValue(input.get("content"));
            String maxCodePoints = stringValue(input.get("maxCodePoints"));
            messages.add(message(
                    "system",
                    "You are a Chinese copy editor. Generate one concise Chinese post description only."
                            + " Keep the reply within " + maxCodePoints + " Unicode code points."
                )
            );
            messages.add(message(
                    "user",
                    "Post content:\n" + content + "\n\nReturn only the description."
            ));
            return messages;
        }

        if ("rag_answer".equals(task)) {
            String question = stringValue(input.get("question"));
            @SuppressWarnings("unchecked")
            List<RagAnswerService.Context> contexts = (List<RagAnswerService.Context>) input.get("contexts");
            messages.add(message(
                    "system",
                    "Answer the user's question based only on the provided community context."
                            + " If the context is insufficient, say so clearly."
                )
            );
            messages.add(message("user", buildRagPrompt(question, contexts)));
            return messages;
        }

        messages.add(message("user", objectToJson(input)));
        return messages;
    }

    private String buildRagPrompt(String question, List<RagAnswerService.Context> contexts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(question).append("\n\n");
        prompt.append("Contexts:\n");
        if (contexts == null || contexts.isEmpty()) {
            prompt.append("No context available.");
            return prompt.toString();
        }
        int limit = Math.min(contexts.size(), LlmConfig.RAG_CONTEXT_LIMIT);
        for (int i = 0; i < limit; i++) {
            RagAnswerService.Context context = contexts.get(i);
            prompt.append(i + 1)
                    .append(". Title: ")
                    .append(context.title())
                    .append("\n")
                    .append("   Content: ")
                    .append(context.content())
                    .append("\n");
        }
        prompt.append("\nAnswer in Chinese.");
        return prompt.toString();
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String extractText(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.isTextual()) {
            return root.asText();
        }

        String direct = firstNonBlank(
                textOf(root.get("answer")),
                textOf(root.get("content")),
                textOf(root.get("output_text")),
                textOf(root.get("text"))
        );
        if (direct != null) {
            return direct;
        }

        String nested = firstNonBlank(
                extractOutputText(root.get("output")),
                extractOutputText(root.get("data")),
                extractChoicesText(root.get("choices")),
                extractCandidatesText(root.get("candidates")),
                extractMessageContent(root.get("message")),
                extractMessageContent(root.get("content"))
        );
        if (nested != null) {
            return nested;
        }

        return null;
    }

    private String extractChoicesText(JsonNode choicesNode) {
        if (choicesNode == null || !choicesNode.isArray() || choicesNode.isEmpty()) {
            return null;
        }
        JsonNode choice = choicesNode.get(0);
        return firstNonBlank(
                textOf(choice.get("text")),
                extractMessageContent(choice.get("message")),
                extractOutputText(choice.get("delta")),
                extractOutputText(choice.get("content"))
        );
    }

    private String extractCandidatesText(JsonNode candidatesNode) {
        if (candidatesNode == null || !candidatesNode.isArray() || candidatesNode.isEmpty()) {
            return null;
        }
        JsonNode candidate = candidatesNode.get(0);
        return firstNonBlank(
                extractMessageContent(candidate.get("content")),
                extractOutputText(candidate.get("output")),
                textOf(candidate.get("text"))
        );
    }

    private String extractOutputText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode item : node) {
                String text = firstNonBlank(
                        textOf(item.get("text")),
                        textOf(item.get("output_text")),
                        extractMessageContent(item.get("message")),
                        extractMessageContent(item.get("content")),
                        extractOutputText(item.get("parts")),
                        extractOutputText(item.get("output"))
                );
                if (StringUtils.hasText(text)) {
                    texts.add(text);
                }
            }
            return texts.isEmpty() ? null : String.join("", texts);
        }
        return firstNonBlank(
                textOf(node.get("text")),
                textOf(node.get("output_text")),
                extractMessageContent(node.get("message")),
                extractMessageContent(node.get("content")),
                extractOutputText(node.get("parts")),
                extractOutputText(node.get("output"))
        );
    }

    private String extractMessageContent(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode item : node) {
                String text = firstNonBlank(
                        textOf(item.get("text")),
                        extractOutputText(item.get("text")),
                        extractMessageContent(item.get("content")),
                        extractOutputText(item.get("parts"))
                );
                if (StringUtils.hasText(text)) {
                    texts.add(text);
                }
            }
            return texts.isEmpty() ? null : String.join("", texts);
        }
        return firstNonBlank(
                textOf(node.get("text")),
                extractOutputText(node.get("parts")),
                extractMessageContent(node.get("content"))
        );
    }

    private String textOf(JsonNode node) {
        return node != null && node.isValueNode() ? node.asText() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
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
                    + "你可以换一个更具体的问法，或者先补充相关内容后再来提问。";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("我先根据社区里已经公开的内容做一个基础回答。\n");
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String objectToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
