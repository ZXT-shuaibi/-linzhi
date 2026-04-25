package com.zhiguang.be.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索模块配置。
 * 当前同时支持 db 和 es 两种 provider，默认仍走 db。
 */
@Component
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    private String provider = "db";
    private int defaultPageSize = 20;
    private int maxPageSize = 20;
    private int fetchMultiplier = 3;
    private int maxFetchLimit = 100;
    private int defaultSuggestSize = 10;
    private int maxSuggestSize = 20;
    private int snippetLength = 80;
    private boolean enableTagSuggest = true;
    private final Es es = new Es();
    private final Outbox outbox = new Outbox();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getFetchMultiplier() {
        return fetchMultiplier;
    }

    public void setFetchMultiplier(int fetchMultiplier) {
        this.fetchMultiplier = fetchMultiplier;
    }

    public int getMaxFetchLimit() {
        return maxFetchLimit;
    }

    public void setMaxFetchLimit(int maxFetchLimit) {
        this.maxFetchLimit = maxFetchLimit;
    }

    public int getDefaultSuggestSize() {
        return defaultSuggestSize;
    }

    public void setDefaultSuggestSize(int defaultSuggestSize) {
        this.defaultSuggestSize = defaultSuggestSize;
    }

    public int getMaxSuggestSize() {
        return maxSuggestSize;
    }

    public void setMaxSuggestSize(int maxSuggestSize) {
        this.maxSuggestSize = maxSuggestSize;
    }

    public int getSnippetLength() {
        return snippetLength;
    }

    public void setSnippetLength(int snippetLength) {
        this.snippetLength = snippetLength;
    }

    public boolean isEnableTagSuggest() {
        return enableTagSuggest;
    }

    public void setEnableTagSuggest(boolean enableTagSuggest) {
        this.enableTagSuggest = enableTagSuggest;
    }

    public Es getEs() {
        return es;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    /**
     * Elasticsearch 配置。
     */
    public static class Es {

        private String endpoint = "http://localhost:9200";
        private String index = "zhiguang_content_index";
        private String username;
        private String password;
        private String apiKey;
        private int connectTimeoutSeconds = 3;
        private int socketTimeoutSeconds = 5;
        private boolean autoCreateIndex = true;
        private boolean autoRebuildOnStartup = false;
        private int rebuildBatchSize = 200;
        private boolean useIkAnalyzer = false;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getIndex() {
            return index;
        }

        public void setIndex(String index) {
            this.index = index;
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

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getSocketTimeoutSeconds() {
            return socketTimeoutSeconds;
        }

        public void setSocketTimeoutSeconds(int socketTimeoutSeconds) {
            this.socketTimeoutSeconds = socketTimeoutSeconds;
        }

        public boolean isAutoCreateIndex() {
            return autoCreateIndex;
        }

        public void setAutoCreateIndex(boolean autoCreateIndex) {
            this.autoCreateIndex = autoCreateIndex;
        }

        public boolean isAutoRebuildOnStartup() {
            return autoRebuildOnStartup;
        }

        public void setAutoRebuildOnStartup(boolean autoRebuildOnStartup) {
            this.autoRebuildOnStartup = autoRebuildOnStartup;
        }

        public int getRebuildBatchSize() {
            return rebuildBatchSize;
        }

        public void setRebuildBatchSize(int rebuildBatchSize) {
            this.rebuildBatchSize = rebuildBatchSize;
        }

        public boolean isUseIkAnalyzer() {
            return useIkAnalyzer;
        }

        public void setUseIkAnalyzer(boolean useIkAnalyzer) {
            this.useIkAnalyzer = useIkAnalyzer;
        }
    }

    /**
     * 搜索索引 outbox 消费配置。
     */
    public static class Outbox {

        private boolean localSyncEnabled = true;
        private boolean kafkaEnabled = false;
        private String topic = "canal-outbox";
        private String groupId = "search-index-consumer";

        public boolean isLocalSyncEnabled() {
            return localSyncEnabled;
        }

        public void setLocalSyncEnabled(boolean localSyncEnabled) {
            this.localSyncEnabled = localSyncEnabled;
        }

        public boolean isKafkaEnabled() {
            return kafkaEnabled;
        }

        public void setKafkaEnabled(boolean kafkaEnabled) {
            this.kafkaEnabled = kafkaEnabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }
    }
}
