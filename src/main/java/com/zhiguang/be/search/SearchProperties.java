package com.zhiguang.be.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索模块配置。
 * 当前默认走数据库基础版，同时把分页、联想和摘要截取等参数收口到配置层。
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
}
