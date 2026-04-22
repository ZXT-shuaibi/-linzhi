package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.llm.service.RagAnswerService;
import com.zhiguang.be.rag.model.RagQueryRequest;
import com.zhiguang.be.rag.model.SseChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RAG 问答服务。
 * 当前基础版通过内存索引做简化召回，再交给 llm 模块组织回答内容。
 */
@Service
public class RagQueryService {

    private final RagIndexService ragIndexService;
    private final RagAnswerService ragAnswerService;
    private final ObjectMapper objectMapper;
    private final Executor ragQueryExecutor;

    public RagQueryService(
            RagIndexService ragIndexService,
            RagAnswerService ragAnswerService,
            ObjectMapper objectMapper,
            @Qualifier("ragQueryExecutor") Executor ragQueryExecutor
    ) {
        this.ragIndexService = ragIndexService;
        this.ragAnswerService = ragAnswerService;
        this.objectMapper = objectMapper;
        this.ragQueryExecutor = ragQueryExecutor;
    }

    /**
     * 发起流式问答。
     */
    public SseEmitter stream(RagQueryRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> doStream(request, emitter), ragQueryExecutor);
        return emitter;
    }

    /**
     * 在后台异步输出 SSE 分片。
     */
    private void doStream(RagQueryRequest request, SseEmitter emitter) {
        try {
            int topK = request.topK() == null ? 5 : request.topK();
            RagIndexService.SearchResult searchResult = ragIndexService.search(request.question(), request.postId(), topK);
            String answer = ragAnswerService.buildAnswer(request.question(), toContexts(searchResult.hits()));
            List<com.zhiguang.be.rag.model.RagReference> references = searchResult.references();

            int seq = 1;
            for (String piece : splitAnswer(answer, 48)) {
                send(emitter, "message", new SseChunk("message", seq++, piece, references, null, null));
                sleep(80L);
            }
            send(emitter, "done", new SseChunk("done", seq, "", references, "stop", null));
            emitter.complete();
        } catch (Exception ex) {
            try {
                send(emitter, "error", new SseChunk("error", 1, "", List.of(), null, "RAG_INTERNAL_ERROR"));
            } catch (Exception ignored) {
                // 这里不再向外抛出，避免覆盖原始异常状态。
            }
            emitter.completeWithError(ex);
        }
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
