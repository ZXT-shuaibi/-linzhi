package com.zhiguang.be.rag.config;

import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * RAG 向量索引 Elasticsearch 配置。
 * 和搜索模块拆开，避免 search provider 不启用时 RAG 没法独立接入 ES。
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.vector", name = "store-enabled", havingValue = "true")
public class RagVectorElasticsearchConfiguration {

    @Bean(name = "ragVectorRestClient", destroyMethod = "close")
    public RestClient ragVectorRestClient(RagProperties ragProperties) {
        RagProperties.Vector vector = ragProperties.getVector();
        if (!StringUtils.hasText(vector.getEndpoint())) {
            throw new IllegalStateException("rag.vector.store-enabled=true 时必须配置 rag.vector.endpoint");
        }

        RestClientBuilder builder = RestClient.builder(HttpHost.create(vector.getEndpoint().trim()));
        if (StringUtils.hasText(vector.getApiKey())) {
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + vector.getApiKey().trim())
            });
        } else if (StringUtils.hasText(vector.getUsername()) && StringUtils.hasText(vector.getPassword())) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(vector.getUsername().trim(), vector.getPassword().trim())
            );
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            );
        }
        return builder.build();
    }
}
