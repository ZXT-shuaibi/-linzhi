package com.zhiguang.be.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
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
 * Elasticsearch 搜索配置。
 * 仅在 search.provider=es 时生效，不影响默认 db 版本。
 */
@Configuration
@ConditionalOnProperty(prefix = "search", name = "provider", havingValue = "es")
public class SearchElasticsearchConfiguration {

    @Bean(destroyMethod = "close")
    public RestClient searchRestClient(SearchProperties searchProperties) {
        SearchProperties.Es es = searchProperties.getEs();
        if (!StringUtils.hasText(es.getEndpoint())) {
            throw new IllegalStateException("search.provider=es 时必须配置 search.es.endpoint");
        }

        RestClientBuilder builder = RestClient.builder(HttpHost.create(es.getEndpoint().trim()))
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(Math.max(es.getConnectTimeoutSeconds(), 1) * 1000)
                        .setSocketTimeout(Math.max(es.getSocketTimeoutSeconds(), 1) * 1000));

        if (StringUtils.hasText(es.getApiKey())) {
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + es.getApiKey().trim())
            });
        } else if (StringUtils.hasText(es.getUsername()) && StringUtils.hasText(es.getPassword())) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(es.getUsername().trim(), es.getPassword().trim())
            );
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            );
        }

        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport searchElasticsearchTransport(RestClient searchRestClient) {
        return new RestClientTransport(searchRestClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient searchElasticsearchClient(ElasticsearchTransport searchElasticsearchTransport) {
        return new ElasticsearchClient(searchElasticsearchTransport);
    }
}
