package com.zhiguang.be.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 模块配置。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String provider = "template";
    private String modelName = "template-llm";
    private boolean fallbackToTemplate = true;
    private final Http http = new Http();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public boolean isFallbackToTemplate() {
        return fallbackToTemplate;
    }

    public void setFallbackToTemplate(boolean fallbackToTemplate) {
        this.fallbackToTemplate = fallbackToTemplate;
    }

    public Http getHttp() {
        return http;
    }

    /**
     * HTTP 模型网关配置。
     */
    public static class Http {

        private String endpoint;
        private String apiKey;
        private int timeoutSeconds = 10;
        private boolean streamEnabled = false;
        private double temperature = 0.2D;
        private int maxTokens = 1024;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isStreamEnabled() {
            return streamEnabled;
        }

        public void setStreamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
