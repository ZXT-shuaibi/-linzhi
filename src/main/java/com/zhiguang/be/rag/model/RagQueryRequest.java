package com.zhiguang.be.rag.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RAG 问答请求。
 * 当前基础版支持按单篇帖子或公开内容流做简化检索。
 */
public record RagQueryRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 512, message = "问题长度不能超过 512")
        String question,

        String postId,
        Double lat,
        Double lng,
        Integer topK,
        String sessionId
) {
}
