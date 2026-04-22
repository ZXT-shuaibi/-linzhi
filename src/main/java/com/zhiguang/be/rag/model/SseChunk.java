package com.zhiguang.be.rag.model;

import java.util.List;

/**
 * SSE 输出分片。
 * 与 openapi 中约定的 message / done / error 事件结构保持一致。
 */
public record SseChunk(
        String event,
        int seq,
        String delta,
        List<RagReference> references,
        String finishReason,
        String errorCode
) {
}
