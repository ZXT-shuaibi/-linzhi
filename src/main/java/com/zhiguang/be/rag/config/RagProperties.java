package com.zhiguang.be.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 模块配置。
 * 用于收口流式输出节奏、索引切块和公开检索范围等基础参数。
 */
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private final Query query = new Query();
    private final Stream stream = new Stream();
    private final Index index = new Index();
    private final Vector vector = new Vector();

    public Query getQuery() {
        return query;
    }

    public Stream getStream() {
        return stream;
    }

    public Index getIndex() {
        return index;
    }

    public Vector getVector() {
        return vector;
    }

    /**
     * 问答查询参数。
     */
    public static class Query {

        private int defaultTopK = 5;
        private int maxTopK = 10;
        private int publicSearchPageSize = 20;
        private int publicSearchMaxPages = 3;
        private double nearbyBoostRadiusMeters = 3000D;
        private int nearbyBoostScore = 8;

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public int getMaxTopK() {
            return maxTopK;
        }

        public void setMaxTopK(int maxTopK) {
            this.maxTopK = maxTopK;
        }

        public int getPublicSearchPageSize() {
            return publicSearchPageSize;
        }

        public void setPublicSearchPageSize(int publicSearchPageSize) {
            this.publicSearchPageSize = publicSearchPageSize;
        }

        public int getPublicSearchMaxPages() {
            return publicSearchMaxPages;
        }

        public void setPublicSearchMaxPages(int publicSearchMaxPages) {
            this.publicSearchMaxPages = publicSearchMaxPages;
        }

        public double getNearbyBoostRadiusMeters() {
            return nearbyBoostRadiusMeters;
        }

        public void setNearbyBoostRadiusMeters(double nearbyBoostRadiusMeters) {
            this.nearbyBoostRadiusMeters = nearbyBoostRadiusMeters;
        }

        public int getNearbyBoostScore() {
            return nearbyBoostScore;
        }

        public void setNearbyBoostScore(int nearbyBoostScore) {
            this.nearbyBoostScore = nearbyBoostScore;
        }
    }

    /**
     * 流式输出参数。
     */
    public static class Stream {

        private long timeoutMillis = 60000L;
        private int chunkSize = 48;
        private long chunkDelayMillis = 80L;

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public long getChunkDelayMillis() {
            return chunkDelayMillis;
        }

        public void setChunkDelayMillis(long chunkDelayMillis) {
            this.chunkDelayMillis = chunkDelayMillis;
        }
    }

    /**
     * 索引切块参数。
     */
    public static class Index {

        private int maxChunkLength = 200;
        private int chunkStep = 160;
        private int fetchTimeoutSeconds = 5;
        private boolean fallbackToMetadata = true;
        private boolean autoRebuildOnStartup = false;
        private int rebuildPageSize = 20;
        private int rebuildMaxPages = 20;

        public int getMaxChunkLength() {
            return maxChunkLength;
        }

        public void setMaxChunkLength(int maxChunkLength) {
            this.maxChunkLength = maxChunkLength;
        }

        public int getChunkStep() {
            return chunkStep;
        }

        public void setChunkStep(int chunkStep) {
            this.chunkStep = chunkStep;
        }

        public int getFetchTimeoutSeconds() {
            return fetchTimeoutSeconds;
        }

        public void setFetchTimeoutSeconds(int fetchTimeoutSeconds) {
            this.fetchTimeoutSeconds = fetchTimeoutSeconds;
        }

        public boolean isFallbackToMetadata() {
            return fallbackToMetadata;
        }

        public void setFallbackToMetadata(boolean fallbackToMetadata) {
            this.fallbackToMetadata = fallbackToMetadata;
        }

        public boolean isAutoRebuildOnStartup() {
            return autoRebuildOnStartup;
        }

        public void setAutoRebuildOnStartup(boolean autoRebuildOnStartup) {
            this.autoRebuildOnStartup = autoRebuildOnStartup;
        }

        public int getRebuildPageSize() {
            return rebuildPageSize;
        }

        public void setRebuildPageSize(int rebuildPageSize) {
            this.rebuildPageSize = rebuildPageSize;
        }

        public int getRebuildMaxPages() {
            return rebuildMaxPages;
        }

        public void setRebuildMaxPages(int rebuildMaxPages) {
            this.rebuildMaxPages = rebuildMaxPages;
        }
    }

    /**
     * 向量检索参数。
     */
    public static class Vector {

        private boolean storeEnabled = true;
        private String endpoint = "http://localhost:9200";
        private String indexName = "zhiguang_rag_chunk_index";
        private String username;
        private String password;
        private String apiKey;
        private boolean autoCreateIndex = true;
        private int candidateSize = 64;
        private String embeddingEndpoint;
        private String embeddingApiKey;
        private String embeddingModel = "text-embedding-3-small";
        private int embeddingTimeoutSeconds = 10;
        private boolean allowLocalFallback = true;
        private int dimension = 1536;
        private double minSimilarity = 0.12D;
        private double vectorWeight = 0.7D;
        private double keywordWeight = 0.3D;
        private double titleBoost = 0.08D;

        public boolean isStoreEnabled() {
            return storeEnabled;
        }

        public void setStoreEnabled(boolean storeEnabled) {
            this.storeEnabled = storeEnabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isAutoCreateIndex() {
            return autoCreateIndex;
        }

        public void setAutoCreateIndex(boolean autoCreateIndex) {
            this.autoCreateIndex = autoCreateIndex;
        }

        public int getCandidateSize() {
            return candidateSize;
        }

        public void setCandidateSize(int candidateSize) {
            this.candidateSize = candidateSize;
        }

        public String getEmbeddingEndpoint() {
            return embeddingEndpoint;
        }

        public void setEmbeddingEndpoint(String embeddingEndpoint) {
            this.embeddingEndpoint = embeddingEndpoint;
        }

        public String getEmbeddingApiKey() {
            return embeddingApiKey;
        }

        public void setEmbeddingApiKey(String embeddingApiKey) {
            this.embeddingApiKey = embeddingApiKey;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingTimeoutSeconds() {
            return embeddingTimeoutSeconds;
        }

        public void setEmbeddingTimeoutSeconds(int embeddingTimeoutSeconds) {
            this.embeddingTimeoutSeconds = embeddingTimeoutSeconds;
        }

        public boolean isAllowLocalFallback() {
            return allowLocalFallback;
        }

        public void setAllowLocalFallback(boolean allowLocalFallback) {
            this.allowLocalFallback = allowLocalFallback;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public double getMinSimilarity() {
            return minSimilarity;
        }

        public void setMinSimilarity(double minSimilarity) {
            this.minSimilarity = minSimilarity;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getKeywordWeight() {
            return keywordWeight;
        }

        public void setKeywordWeight(double keywordWeight) {
            this.keywordWeight = keywordWeight;
        }

        public double getTitleBoost() {
            return titleBoost;
        }

        public void setTitleBoost(double titleBoost) {
            this.titleBoost = titleBoost;
        }
    }
}
