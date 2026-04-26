package com.zhiguang.be.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI EmbeddingModel 适配器。
 * 复用当前项目已经存在的 embedding 网关，把自研向量生成能力接入 Spring AI VectorStore。
 */
@Component("ragEmbeddingModel")
public class SpringAiEmbeddingModelAdapter implements EmbeddingModel {

    private final RagEmbeddingGateway ragEmbeddingGateway;

    public SpringAiEmbeddingModelAdapter(RagEmbeddingGateway ragEmbeddingGateway) {
        this.ragEmbeddingGateway = ragEmbeddingGateway;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> results = new ArrayList<Embedding>(instructions.size());
        for (int index = 0; index < instructions.size(); index++) {
            results.add(new Embedding(toFloatArray(ragEmbeddingGateway.embed(instructions.get(index))), index));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return toFloatArray(ragEmbeddingGateway.embed(document.getText()));
    }

    private float[] toFloatArray(double[] vector) {
        float[] floats = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            floats[index] = (float) vector[index];
        }
        return floats;
    }
}
