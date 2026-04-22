package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.rag.model.RagQueryRequest;
import com.zhiguang.be.rag.model.SseChunk;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 问答服务。
 * 当前基础版通过内存索引做简化召回，再用模板化回答拼出 SSE 流。
 */
@Service
public class RagQueryService {

    private final RagIndexService ragIndexService;
    private final ObjectMapper objectMapper;

    /**
     * 注入 RAG 依赖。
     */
    public RagQueryService(RagIndexService ragIndexService, ObjectMapper objectMapper) {
        this.ragIndexService = ragIndexService;
        this.objectMapper = objectMapper;
    }

    /**
     * 发起流式问答。
     */
    public SseEmitter stream(RagQueryRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> doStream(request, emitter));
        return emitter;
    }

    /**
     * 以后台异步方式输出 SSE 分片。
     */
    private void doStream(RagQueryRequest request, SseEmitter emitter) {
        try {
            int topK = request.topK() == null ? 5 : request.topK();
            RagIndexService.SearchResult searchResult = ragIndexService.search(request.question(), request.postId(), topK);
            String answer = buildAnswer(request.question(), searchResult.hits());
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
                // 这里不再向外抛，避免覆盖原始异常状态。
            }
            emitter.completeWithError(ex);
        }
    }

    /**
     * 构造基础版回答。
     */
    private String buildAnswer(String question, List<RagIndexService.ChunkHit> hits) {
        if (hits.isEmpty()) {
            return "当前没有检索到和问题“" + question + "”直接相关的社区内容。"
                    + "你可以换一个更具体的问法，或者先到邻里里发布相关内容后再来提问。";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("我先根据当前社区里已公开的内容做一个基础回答。\n");
        answer.append("问题：").append(question).append("\n\n");
        answer.append("结合已命中的帖子，可以先得到这些信息：\n");
        int limit = Math.min(hits.size(), 3);
        for (int i = 0; i < limit; i++) {
            RagIndexService.ChunkHit hit = hits.get(i);
            answer.append(i + 1)
                    .append(". 来自《")
                    .append(hit.title())
                    .append("》：")
                    .append(hit.content().replace('\n', ' ').trim())
                    .append("\n");
        }
        answer.append("\n这版回答基于简化检索结果生成，后续接入真实向量检索和大模型后，回答会更自然也更完整。");
        return answer.toString();
    }

    /**
     * 将回答切成多个小片段，便于 SSE 流式输出。
     */
    private List<String> splitAnswer(String answer, int chunkSize) {
        List<String> parts = new java.util.ArrayList<>();
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
