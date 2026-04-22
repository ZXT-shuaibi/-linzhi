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
     * HTTP 模型接入配置。
     */
    public static class Http {

        private String endpoint;
        private String apiKey;
        private int timeoutSeconds = 10;

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
    }
}
