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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * RAG 向量索引 Elasticsearch 配置。
 * 参考 zhiguang 的思路，把底层 ES 连接和 Spring AI VectorStore 一起收口。
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

    /**
     * 构建 Spring AI VectorStore。
     * 这样 RAG 写入和语义召回都可以先走统一的 VectorStore 接口。
     */
    @Bean(name = "ragVectorStore")
    public VectorStore ragVectorStore(
            RagProperties ragProperties,
            RestClient ragVectorRestClient,
            EmbeddingModel ragEmbeddingModel
    ) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(ragProperties.getVector().getIndexName());
        options.setDimensions(Math.max(1, ragProperties.getVector().getDimension()));
        return ElasticsearchVectorStore.builder(ragVectorRestClient, ragEmbeddingModel)
                .options(options)
                .initializeSchema(ragProperties.getVector().isAutoCreateIndex())
                .build();
    }
}
