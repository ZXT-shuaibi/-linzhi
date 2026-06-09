package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.KnowPostFeedRow;
import com.zhiguang.be.rag.config.RagProperties;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexServiceTest {

    @Test
    void searchShouldInspectMultiplePublicPages() {
        KnowPostMapper knowPostMapper = mock(KnowPostMapper.class);
        RagIndexService service = new RagIndexService(
                knowPostMapper,
                ragProperties(),
                new ObjectMapper(),
                constantEmbeddingGateway(),
                emptyProvider(RestClient.class),
                emptyProvider(VectorStore.class)
        );

        when(knowPostMapper.listFeedPublic(3, 0)).thenReturn(List.of(
                row("post-1", "First post", "Campus introduction", "[\"campus\"]"),
                row("post-2", "Trade tips", "Payment guide for second-hand trade", "[\"trade\",\"payment\"]"),
                row("post-3", "Ignored extra", "Extra", "[]")
        ));
        when(knowPostMapper.listFeedPublic(3, 2)).thenReturn(List.of());
        when(knowPostMapper.findById("post-1")).thenReturn(entity(
                "post-1",
                "First post",
                "Campus introduction",
                "[\"campus\"]",
                "Tongji campus"
        ));
        when(knowPostMapper.findById("post-2")).thenReturn(entity(
                "post-2",
                "Trade tips",
                "Payment guide for second-hand trade",
                "[\"trade\",\"payment\"]",
                "Siping Road"
        ));

        RagIndexService.SearchResult result = service.search("payment", null, null, null, 5);

        assertFalse(result.hits().isEmpty());
        assertEquals("post-2", result.hits().get(0).postId());
        verify(knowPostMapper).listFeedPublic(3, 0);
        verify(knowPostMapper).listFeedPublic(3, 2);
    }

    @Test
    void searchShouldFallbackToSinglePostChunksWhenNoKeywordMatches() {
        KnowPostMapper knowPostMapper = mock(KnowPostMapper.class);
        RagIndexService service = new RagIndexService(
                knowPostMapper,
                ragProperties(),
                new ObjectMapper(),
                constantEmbeddingGateway(),
                emptyProvider(RestClient.class),
                emptyProvider(VectorStore.class)
        );

        when(knowPostMapper.findById("post-9")).thenReturn(entity(
                "post-9",
                "Dorm rules",
                "Lights out at 11 PM and keep shared areas clean.",
                "[\"campus\",\"dorm\"]",
                "North dormitory"
        ));

        RagIndexService.SearchResult result = service.search("completely unrelated question", "post-9", null, null, 2);

        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "post-9".equals(hit.postId())));
        assertTrue(result.references().size() <= 2);
    }

    @Test
    void vectorSearchShouldOnlyUseCurrentPostVersionChunks() {
        KnowPostMapper knowPostMapper = mock(KnowPostMapper.class);
        VectorStore vectorStore = mock(VectorStore.class);
        RestClient restClient = mock(RestClient.class);
        RagIndexService service = new RagIndexService(
                knowPostMapper,
                ragProperties(),
                new ObjectMapper(),
                constantEmbeddingGateway(),
                provider(RestClient.class, restClient),
                provider(VectorStore.class, vectorStore)
        );

        when(knowPostMapper.findById("post-9")).thenReturn(entity(
                "post-9",
                "Dorm rules",
                "Current answer says lights out at 11 PM.",
                "[\"campus\",\"dorm\"]",
                "North dormitory"
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                vectorDocument("post-9", "post-9#old#0", "sha:stale-sha", "Dorm rule old answer lights out at 10 PM."),
                vectorDocument("post-9", "post-9#summary#0", "sha256:sha-post-9", "Dorm rule current answer lights out at 11 PM.")
        ));

        RagIndexService.SearchResult result = service.search("lights out", "post-9", null, null, 5);

        assertEquals(1, result.hits().size());
        assertEquals("post-9#summary#0", result.hits().get(0).chunkId());
        assertTrue(result.hits().get(0).content().contains("11 PM"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reindexShouldWriteChunkIdAsVectorDocumentIdAndChunkContentAsText() {
        KnowPostMapper knowPostMapper = mock(KnowPostMapper.class);
        VectorStore vectorStore = mock(VectorStore.class);
        RestClient restClient = mock(RestClient.class);
        RagIndexService service = new RagIndexService(
                knowPostMapper,
                ragProperties(),
                new ObjectMapper(),
                constantEmbeddingGateway(),
                provider(RestClient.class, restClient),
                provider(VectorStore.class, vectorStore)
        );

        when(knowPostMapper.findById("post-9")).thenReturn(entity(
                "post-9",
                "Dorm rules",
                "Current answer says lights out at 11 PM.",
                "[\"campus\",\"dorm\"]",
                "North dormitory"
        ));

        service.reindexSinglePost("post-9");

        ArgumentCaptor<List> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documentsCaptor.capture());
        List<Document> documents = documentsCaptor.getValue();
        Optional<Document> titleDocument = documents.stream()
                .filter(document -> "post-9#title#0".equals(document.getId()))
                .findFirst();

        assertTrue(titleDocument.isPresent());
        assertTrue(titleDocument.get().getText().contains("Dorm rules"));
    }

    private RagProperties ragProperties() {
        RagProperties properties = new RagProperties();
        properties.getQuery().setPublicSearchPageSize(2);
        properties.getQuery().setPublicSearchMaxPages(3);
        properties.getQuery().setMaxTopK(10);
        properties.getIndex().setFallbackToMetadata(true);
        return properties;
    }

    private RagEmbeddingGateway constantEmbeddingGateway() {
        return text -> new double[]{1D, 1D, 1D};
    }

    private <T> ObjectProvider<T> emptyProvider(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    private <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean(type.getName(), bean);
        return beanFactory.getBeanProvider(type);
    }

    private Document vectorDocument(String postId, String chunkId, String indexVersion, String content) {
        return Document.builder()
                .id(chunkId)
                .text(content)
                .metadata(Map.of(
                        "postId", postId,
                        "chunkId", chunkId,
                        "indexVersion", indexVersion,
                        "title", "Dorm rules",
                        "position", 0,
                        "weight", 3,
                        "contentSha256", "sha-post-9",
                        "contentEtag", "etag-post-9"
                ))
                .build();
    }

    private KnowPostFeedRow row(String postId, String title, String description, String tagsJson) {
        return new KnowPostFeedRow(
                postId,
                "user-" + postId,
                "Author " + postId,
                null,
                title,
                description,
                "[]",
                tagsJson,
                "public",
                Instant.now(),
                Boolean.FALSE
        );
    }

    private KnowPostEntity entity(
            String postId,
            String title,
            String description,
            String tagsJson,
            String address
    ) {
        Instant now = Instant.now();
        return new KnowPostEntity(
                postId,
                "user-" + postId,
                null,
                tagsJson,
                title,
                description,
                31.0,
                121.0,
                "wtw3sj",
                address,
                null,
                null,
                "etag-" + postId,
                null,
                "sha-" + postId,
                Boolean.FALSE,
                "post",
                "public",
                "[]",
                null,
                "published",
                now,
                now,
                now
        );
    }
}
