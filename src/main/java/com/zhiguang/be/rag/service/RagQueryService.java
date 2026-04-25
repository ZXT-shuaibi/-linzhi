package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.llm.service.RagAnswerService;
import com.zhiguang.be.rag.config.RagProperties;
import com.zhiguang.be.rag.model.RagQueryRequest;
import com.zhiguang.be.rag.model.SseChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 问答服务。
 * 当前先用轻量索引做召回，再交给 llm 模块生成回答内容。
 */
@Service
public class RagQueryService {

    private final RagIndexService ragIndexService;
    private final RagAnswerService ragAnswerService;
    private final ObjectMapper objectMapper;
    private final Executor ragQueryExecutor;
    private final RagProperties ragProperties;

    public RagQueryService(
            RagIndexService ragIndexService,
            RagAnswerService ragAnswerService,
            ObjectMapper objectMapper,
            @Qualifier("ragQueryExecutor") Executor ragQueryExecutor,
            RagProperties ragProperties
    ) {
        this.ragIndexService = ragIndexService;
        this.ragAnswerService = ragAnswerService;
        this.objectMapper = objectMapper;
        this.ragQueryExecutor = ragQueryExecutor;
        this.ragProperties = ragProperties;
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
     * 后台异步输出 SSE 分片。
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
            boolean streamed = ragAnswerService.streamAnswer(
                    request.question(),
                    contexts,
                    piece -> sendQuietly(emitter, "message", new SseChunk("message", seq.getAndIncrement(), piece, references, null, null))
            );

            if (!streamed) {
                String answer = ragAnswerService.buildAnswer(request.question(), contexts);
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
            throw new IllegalStateException("Failed to send SSE chunk", ex);
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
