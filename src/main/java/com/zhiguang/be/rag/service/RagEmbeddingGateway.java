package com.zhiguang.be.rag.service;

/**
 * RAG 向量化网关。
 * 负责把文本转换成可写入向量存储的 embedding。
 */
public interface RagEmbeddingGateway {

    /**
     * 将文本编码成向量。
     */
    double[] embed(String text);
}
