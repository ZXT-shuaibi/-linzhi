package com.zhiguang.be.rag.model;

/**
 * RAG 参考片段。
 * 用于标记当前回答依赖了哪篇帖子、哪个分片。
 */
public record RagReference(
        String postId,
        String chunkId,
        String title
) {
}
