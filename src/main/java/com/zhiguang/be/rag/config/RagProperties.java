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

    public Query getQuery() {
        return query;
    }

    public Stream getStream() {
        return stream;
    }

    public Index getIndex() {
        return index;
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
    }
}
