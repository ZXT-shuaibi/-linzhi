package com.zhiguang.be.llm.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.llm.LlmConstants;
import com.zhiguang.be.llm.config.LlmProperties;
import com.zhiguang.be.llm.service.LlmGateway;
import com.zhiguang.be.llm.service.RagAnswerService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * 默认 LLM 网关实现。
 * 当前同时支持 Spring AI ChatClient、HTTP 网关以及模板兜底三种路径。
 */
@Service
public class DefaultLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);

    private final ObjectMapper objectMapper;
    private final LlmProperties llmProperties;
    private final ChatClient chatClient;
    private final HttpClient httpClient;

    public DefaultLlmGateway(
            ObjectMapper objectMapper,
            LlmProperties llmProperties,
            ObjectProvider<ChatClient> chatClientProvider
    ) {
        this.objectMapper = objectMapper;
        this.llmProperties = llmProperties;
        this.chatClient = chatClientProvider.getIfAvailable();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(llmProperties.getHttp().getTimeoutSeconds(), 1)))
                .build();
    }

    @Override
    public String currentModelName() {
        if (useSpringAiProvider() || useHttpProvider()) {
            return llmProperties.getModelName();
        }
        return LlmConstants.TEMPLATE_MODEL_NAME;
    }

    @Override
    public String generateDescription(String content, int maxCodePoints) {
        if (useSpringAiProvider()) {
            try {
                String response = chatClient.prompt()
                        .system("你是中文社区文案助手，只输出一条精炼描述。")
                        .user("正文如下：\n" + content + "\n\n请输出不超过 " + maxCodePoints + " 个 Unicode 字符的中文描述。")
                        .options(buildSpringAiOptions(maxCodePoints))
                        .call()
                        .content();
                if (StringUtils.hasText(response)) {
                    return response.trim();
                }
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Spring AI description response did not contain text");
                }
            } catch (Exception ex) {
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Failed to call Spring AI description API", ex);
                }
                log.warn("Spring AI description generation failed, falling back: {}", ex.getMessage());
            }
        }

        if (useHttpProvider()) {
            try {
                String response = requestText(buildDescriptionPayload(content, maxCodePoints, false));
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
        if (useSpringAiProvider()) {
            try {
                String response = chatClient.prompt()
                        .system("你是中文知识助手。只能根据提供的上下文作答；如果上下文不足，请明确说明。")
                        .user(buildRagPrompt(question, contexts))
                        .options(buildSpringAiOptions(llmProperties.getHttp().getMaxTokens()))
                        .call()
                        .content();
                if (StringUtils.hasText(response)) {
                    return response.trim();
                }
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Spring AI RAG response did not contain text");
                }
            } catch (Exception ex) {
                throwIfCancelled(ex);
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Failed to call Spring AI RAG API", ex);
                }
                log.warn("Spring AI RAG generation failed, falling back: {}", ex.getMessage());
            }
        }

        if (useHttpProvider()) {
            try {
                String response = requestText(buildRagPayload(question, contexts, false));
                if (StringUtils.hasText(response)) {
                    return response.trim();
                }
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("LLM RAG response did not contain text");
                }
            } catch (Exception ex) {
                throwIfCancelled(ex);
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Failed to call LLM RAG API", ex);
                }
                log.warn("HTTP RAG generation failed, falling back to template: {}", ex.getMessage());
            }
        }

        return templateRagAnswer(question, contexts);
    }

    @Override
    public boolean streamRagAnswer(String question, List<RagAnswerService.Context> contexts, Consumer<String> consumer) {
        if (useSpringAiProvider()) {
            boolean emitted = false;
            try {
                Iterable<String> pieces = chatClient.prompt()
                        .system("你是中文知识助手。只能根据提供的上下文作答；如果上下文不足，请明确说明。")
                        .user(buildRagPrompt(question, contexts))
                        .options(buildSpringAiOptions(llmProperties.getHttp().getMaxTokens()))
                        .stream()
                        .content()
                        .toIterable();
                for (String piece : pieces) {
                    if (!StringUtils.hasText(piece)) {
                        continue;
                    }
                    consumer.accept(piece);
                    emitted = true;
                }
                return emitted;
            } catch (Exception ex) {
                throwIfCancelled(ex);
                if (!llmProperties.isFallbackToTemplate()) {
                    throw new IllegalStateException("Failed to stream Spring AI RAG API", ex);
                }
                log.warn("Spring AI RAG streaming failed, falling back: {}", ex.getMessage());
                return false;
            }
        }

        if (!useHttpProvider() || !llmProperties.getHttp().isStreamEnabled()) {
            return false;
        }

        boolean emitted = false;
        try {
            HttpRequest request = buildRequest(buildRagPayload(question, contexts, true));
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LLM HTTP status is not successful: " + response.statusCode());
            }

            try (InputStream bodyStream = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String delta = extractStreamDelta(line);
                    if (!StringUtils.hasText(delta)) {
                        if (isDoneLine(line)) {
                            break;
                        }
                        continue;
                    }
                    consumer.accept(delta);
                    emitted = true;
                }
            }
        } catch (Exception ex) {
            throwIfCancelled(ex);
            if (!llmProperties.isFallbackToTemplate()) {
                throw new IllegalStateException("Failed to stream LLM RAG API", ex);
            }
            log.warn("HTTP RAG streaming failed, falling back to template: {}", ex.getMessage());
            return false;
        }
        return emitted;
    }

    private void throwIfCancelled(Exception ex) {
        if (ex instanceof CancellationException cancellationException) {
            throw cancellationException;
        }
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            throw new CancellationException("LLM request interrupted");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("LLM request interrupted");
        }
    }

    private boolean useSpringAiProvider() {
        return chatClient != null
                && ("spring-ai".equalsIgnoreCase(llmProperties.getProvider())
                || "deepseek".equalsIgnoreCase(llmProperties.getProvider()));
    }

    private boolean useHttpProvider() {
        return "http".equalsIgnoreCase(llmProperties.getProvider())
                && StringUtils.hasText(llmProperties.getHttp().getEndpoint());
    }

    private DeepSeekChatOptions buildSpringAiOptions(int maxTokens) {
        return DeepSeekChatOptions.builder()
                .model(llmProperties.getModelName())
                .temperature(llmProperties.getHttp().getTemperature())
                .maxTokens(Math.max(1, maxTokens))
                .build();
    }

    private String requestText(Map<String, Object> payload) throws Exception {
        HttpResponse<String> response = httpClient.send(
                buildRequest(payload),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP status is not successful: " + response.statusCode());
        }
        return extractText(objectMapper.readTree(response.body()));
    }

    private HttpRequest buildRequest(Map<String, Object> payload) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(llmProperties.getHttp().getEndpoint()))
                .timeout(Duration.ofSeconds(Math.max(llmProperties.getHttp().getTimeoutSeconds(), 1)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (StringUtils.hasText(llmProperties.getHttp().getApiKey())) {
            requestBuilder.header("Authorization", "Bearer " + llmProperties.getHttp().getApiKey().trim());
        }
        return requestBuilder.build();
    }

    private Map<String, Object> buildDescriptionPayload(String content, int maxCodePoints, boolean stream) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("content", content);
        input.put("maxCodePoints", maxCodePoints);

        Map<String, Object> payload = basePayload(stream);
        payload.put("task", "post_description");
        payload.put("input", input);
        payload.put("messages", buildDescriptionMessages(content, maxCodePoints));
        return payload;
    }

    private Map<String, Object> buildRagPayload(String question, List<RagAnswerService.Context> contexts, boolean stream) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("question", question);
        input.put("contexts", contexts);

        Map<String, Object> payload = basePayload(stream);
        payload.put("task", "rag_answer");
        payload.put("input", input);
        payload.put("messages", buildRagMessages(question, contexts));
        return payload;
    }

    private Map<String, Object> basePayload(boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", llmProperties.getModelName());
        payload.put("stream", stream);
        payload.put("temperature", llmProperties.getHttp().getTemperature());
        payload.put("max_tokens", llmProperties.getHttp().getMaxTokens());
        return payload;
    }

    private List<Map<String, String>> buildDescriptionMessages(String content, int maxCodePoints) {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>(2);
        messages.add(message(
                "system",
                "You are a Chinese copy editor. Generate one concise Chinese post description only."
                        + " Keep the reply within " + maxCodePoints + " Unicode code points."
        ));
        messages.add(message(
                "user",
                "Post content:\n" + content + "\n\nReturn only the description."
        ));
        return messages;
    }

    private List<Map<String, String>> buildRagMessages(String question, List<RagAnswerService.Context> contexts) {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>(2);
        messages.add(message(
                "system",
                "Answer the user's question based only on the provided community context."
                        + " If the context is insufficient, say so clearly. Reply in Chinese."
        ));
        messages.add(message("user", buildRagPrompt(question, contexts)));
        return messages;
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String buildRagPrompt(String question, List<RagAnswerService.Context> contexts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(question).append("\n\n");
        prompt.append("Contexts:\n");
        if (contexts == null || contexts.isEmpty()) {
            prompt.append("No context available.");
            return prompt.toString();
        }
        int limit = Math.min(contexts.size(), LlmConstants.RAG_CONTEXT_LIMIT);
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

    private String extractStreamDelta(String line) {
        if (!StringUtils.hasText(line)) {
            return null;
        }
        String normalized = line.trim();
        if (normalized.startsWith("data:")) {
            normalized = normalized.substring(5).trim();
        }
        if (!StringUtils.hasText(normalized) || "[DONE]".equalsIgnoreCase(normalized)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(normalized);
            return firstNonBlank(
                    extractChoiceDelta(root.get("choices")),
                    extractOutputText(root.get("output")),
                    extractOutputText(root.get("data")),
                    textOf(root.get("text"))
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isDoneLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String normalized = line.trim();
        if (normalized.startsWith("data:")) {
            normalized = normalized.substring(5).trim();
        }
        return "[DONE]".equalsIgnoreCase(normalized);
    }

    private String extractChoiceDelta(JsonNode choicesNode) {
        if (choicesNode == null || !choicesNode.isArray() || choicesNode.isEmpty()) {
            return null;
        }
        JsonNode choice = choicesNode.get(0);
        return firstNonBlank(
                extractOutputText(choice.get("delta")),
                extractMessageContent(choice.get("message")),
                textOf(choice.get("text"))
        );
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

        return firstNonBlank(
                extractOutputText(root.get("output")),
                extractOutputText(root.get("data")),
                extractChoicesText(root.get("choices")),
                extractCandidatesText(root.get("candidates")),
                extractMessageContent(root.get("message")),
                extractMessageContent(root.get("content"))
        );
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
            List<String> texts = new ArrayList<String>();
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
            List<String> texts = new ArrayList<String>();
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
        int limit = Math.min(contexts.size(), LlmConstants.RAG_CONTEXT_LIMIT);
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
