package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.rag.config.RagProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 HTTP 的 RAG 向量化网关。
 * 优先走真实 embedding 接口；未配置时才按配置决定是否回退到本地哈希向量。
 */
@Service
public class HttpRagEmbeddingGateway implements RagEmbeddingGateway {

    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final HttpClient httpClient;

    public HttpRagEmbeddingGateway(ObjectMapper objectMapper, RagProperties ragProperties) {
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, ragProperties.getVector().getEmbeddingTimeoutSeconds())))
                .build();
    }

    @Override
    public double[] embed(String text) {
        if (StringUtils.hasText(ragProperties.getVector().getEmbeddingEndpoint())) {
            try {
                return requestEmbedding(text);
            } catch (Exception ex) {
                if (!ragProperties.getVector().isAllowLocalFallback()) {
                    throw new IllegalStateException("Failed to call embedding API", ex);
                }
            }
        }
        if (!ragProperties.getVector().isAllowLocalFallback()) {
            throw new IllegalStateException("Embedding endpoint is not configured");
        }
        return localEmbedding(text);
    }

    private double[] requestEmbedding(String text) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", ragProperties.getVector().getEmbeddingModel());
        payload.put("input", text);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(ragProperties.getVector().getEmbeddingEndpoint()))
                .timeout(Duration.ofSeconds(Math.max(1, ragProperties.getVector().getEmbeddingTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (StringUtils.hasText(ragProperties.getVector().getEmbeddingApiKey())) {
            builder.header("Authorization", "Bearer " + ragProperties.getVector().getEmbeddingApiKey().trim());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Embedding API status is not successful: " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Embedding API response does not contain data");
        }
        JsonNode embedding = data.get(0).get("embedding");
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding API response does not contain embedding");
        }

        double[] vector = new double[Math.max(1, ragProperties.getVector().getDimension())];
        int upperBound = Math.min(vector.length, embedding.size());
        for (int index = 0; index < upperBound; index++) {
            vector[index] = embedding.get(index).asDouble(0D);
        }
        return normalize(vector);
    }

    private double[] localEmbedding(String text) {
        int dimension = Math.max(32, ragProperties.getVector().getDimension());
        double[] vector = new double[dimension];
        if (!StringUtils.hasText(text)) {
            return vector;
        }
        String normalized = text.trim().toLowerCase();
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            int index = Math.abs(token.hashCode()) % dimension;
            vector[index] += 1D;
        }
        return normalize(vector);
    }

    private double[] normalize(double[] vector) {
        double sumSquares = 0D;
        for (double value : vector) {
            sumSquares += value * value;
        }
        if (sumSquares <= 0D) {
            return vector;
        }
        double norm = Math.sqrt(sumSquares);
        double[] normalized = new double[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = vector[index] / norm;
        }
        return normalized;
    }
}
