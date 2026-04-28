package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.llm.LlmConstants;
import com.zhiguang.be.llm.config.LlmProperties;
import com.zhiguang.be.llm.service.LlmGateway;
import com.zhiguang.be.llm.service.RagAnswerService;
import com.zhiguang.be.rag.config.RagProperties;
import com.zhiguang.be.rag.model.RagQueryRequest;
import com.zhiguang.be.rag.model.SseChunk;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 问答服务。
 * 参考 zhiguang 的主链，优先使用 VectorStore 做上下文召回，
 * 再直接通过 Spring AI ChatClient 生成回答。
 * 当 Spring AI 不可用时，再回退到当前项目的 LLM 网关兜底，保证主链可用。
 */
@Service
public class RagQueryService {

    private final RagIndexService ragIndexService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final Executor ragQueryExecutor;
    private final RagProperties ragProperties;
    private final LlmProperties llmProperties;
    private final ChatClient chatClient;

    public RagQueryService(
            RagIndexService ragIndexService,
            LlmGateway llmGateway,
            ObjectMapper objectMapper,
            @Qualifier("ragQueryExecutor") Executor ragQueryExecutor,
            RagProperties ragProperties,
            LlmProperties llmProperties,
            ObjectProvider<ChatClient> chatClientProvider
    ) {
        this.ragIndexService = ragIndexService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.ragQueryExecutor = ragQueryExecutor;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.chatClient = chatClientProvider.getIfAvailable();
    }

    /**
     * 发起流式问答。
     */
    public SseEmitter stream(RagQueryRequest request) {
        SseEmitter emitter = new SseEmitter(ragProperties.getStream().getTimeoutMillis());
        CompletableFuture.runAsync(() -> doStream(request, emitter), ragQueryExecutor);
        return emitter;
    }

    /**
     * 后台异步输出 SSE 片段。
     */
    private void doStream(RagQueryRequest request, SseEmitter emitter) {
        try {
            int topK = normalizeTopK(request.topK());
            RagIndexService.SearchResult searchResult = ragIndexService.search(
                    request.question(),
                    request.postId(),
                    request.lat(),
                    request.lng(),
                    topK
            );
            List<RagAnswerService.Context> contexts = toContexts(searchResult.hits());
            List<com.zhiguang.be.rag.model.RagReference> references = searchResult.references();

            AtomicInteger seq = new AtomicInteger(1);
            boolean streamed = streamWithSpringAi(
                    request.question(),
                    contexts,
                    piece -> sendQuietly(emitter, "message", new SseChunk("message", seq.getAndIncrement(), piece, references, null, null))
            );

            if (!streamed) {
                streamed = llmGateway.streamRagAnswer(
                        request.question(),
                        contexts,
                        piece -> sendQuietly(emitter, "message", new SseChunk("message", seq.getAndIncrement(), piece, references, null, null))
                );
            }

            if (!streamed) {
                String answer = buildWithSpringAi(request.question(), contexts);
                if (!StringUtils.hasText(answer)) {
                    answer = llmGateway.generateRagAnswer(request.question(), contexts);
                }
                for (String piece : splitAnswer(answer, ragProperties.getStream().getChunkSize())) {
                    send(emitter, "message", new SseChunk("message", seq.getAndIncrement(), piece, references, null, null));
                    sleep(ragProperties.getStream().getChunkDelayMillis());
                }
            }

            send(emitter, "done", new SseChunk("done", seq.get(), "", references, "stop", null));
            emitter.complete();
        } catch (Exception ex) {
            try {
                send(emitter, "error", new SseChunk("error", 1, "", List.of(), null, "RAG_INTERNAL_ERROR"));
            } catch (Exception ignored) {
                // 这里不再继续向外抛异常，避免覆盖原始错误状态。
            }
            emitter.completeWithError(ex);
        }
    }

    /**
     * 规范化 topK，避免调用方传入过大值导致检索退化。
     */
    private int normalizeTopK(Integer topK) {
        int defaultTopK = Math.max(1, ragProperties.getQuery().getDefaultTopK());
        int maxTopK = Math.max(defaultTopK, ragProperties.getQuery().getMaxTopK());
        if (topK == null || topK.intValue() <= 0) {
            return defaultTopK;
        }
        return Math.min(topK.intValue(), maxTopK);
    }

    /**
     * 将检索结果转换为回答上下文。
     */
    private List<RagAnswerService.Context> toContexts(List<RagIndexService.ChunkHit> hits) {
        List<RagAnswerService.Context> contexts = new ArrayList<RagAnswerService.Context>();
        for (RagIndexService.ChunkHit hit : hits) {
            contexts.add(new RagAnswerService.Context(hit.title(), hit.content()));
        }
        return contexts;
    }

    /**
     * 直接通过 Spring AI ChatClient 进行流式回答。
     */
    private boolean streamWithSpringAi(
            String question,
            List<RagAnswerService.Context> contexts,
            java.util.function.Consumer<String> consumer
    ) {
        if (!useSpringAiProvider()) {
            return false;
        }
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
            return false;
        }
    }

    /**
     * 直接通过 Spring AI ChatClient 生成完整回答。
     */
    private String buildWithSpringAi(String question, List<RagAnswerService.Context> contexts) {
        if (!useSpringAiProvider()) {
            return null;
        }
        try {
            String answer = chatClient.prompt()
                    .system("你是中文知识助手。只能根据提供的上下文作答；如果上下文不足，请明确说明。")
                    .user(buildRagPrompt(question, contexts))
                    .options(buildSpringAiOptions(llmProperties.getHttp().getMaxTokens()))
                    .call()
                    .content();
            return StringUtils.hasText(answer) ? answer.trim() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 组装与 zhiguang 风格接近的 RAG 提示词。
     */
    private String buildRagPrompt(String question, List<RagAnswerService.Context> contexts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("问题：").append(question).append("\n\n");
        prompt.append("上下文如下（可能不完整）：\n");
        if (contexts == null || contexts.isEmpty()) {
            prompt.append("暂无可用上下文。");
            return prompt.toString();
        }
        int limit = Math.min(contexts.size(), LlmConstants.RAG_CONTEXT_LIMIT);
        for (int index = 0; index < limit; index++) {
            RagAnswerService.Context context = contexts.get(index);
            prompt.append(index + 1)
                    .append(". 标题：")
                    .append(context.title())
                    .append("\n   内容：")
                    .append(context.content())
                    .append("\n");
        }
        prompt.append("\n请基于以上上下文用中文回答。");
        return prompt.toString();
    }

    /**
     * 构建 Spring AI 请求参数。
     */
    private DeepSeekChatOptions buildSpringAiOptions(int maxTokens) {
        return DeepSeekChatOptions.builder()
                .model(llmProperties.getModelName())
                .temperature(llmProperties.getHttp().getTemperature())
                .maxTokens(Math.max(1, maxTokens))
                .build();
    }

    /**
     * 判断当前是否应优先走 Spring AI 主链。
     */
    private boolean useSpringAiProvider() {
        return chatClient != null
                && ("spring-ai".equalsIgnoreCase(llmProperties.getProvider())
                || "deepseek".equalsIgnoreCase(llmProperties.getProvider()));
    }

    /**
     * 将回答切成多个小片段，便于 SSE 流式输出。
     */
    private List<String> splitAnswer(String answer, int chunkSize) {
        List<String> parts = new ArrayList<String>();
        int index = 0;
        while (index < answer.length()) {
            int end = Math.min(index + chunkSize, answer.length());
            parts.add(answer.substring(index, end));
            index = end;
        }
        return parts;
    }

    /**
     * 发送单个 SSE 事件。
     */
    private void send(SseEmitter emitter, String eventName, SseChunk chunk) throws Exception {
        emitter.send(
                SseEmitter.event()
                        .name(eventName)
                        .data(objectMapper.writeValueAsString(chunk))
        );
    }

    /**
     * 安静发送单个流式片段。
     */
    private void sendQuietly(SseEmitter emitter, String eventName, SseChunk chunk) {
        try {
            send(emitter, eventName, chunk);
        } catch (Exception ex) {
            throw new IllegalStateException("发送 SSE 片段失败", ex);
        }
    }

    /**
     * 轻量节流，模拟流式返回节奏。
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
