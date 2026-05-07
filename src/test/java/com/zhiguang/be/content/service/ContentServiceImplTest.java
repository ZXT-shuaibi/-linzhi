package com.zhiguang.be.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.content.dto.ConfirmContentRequest;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostDetailRow;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.OutboxEventEntity;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import com.zhiguang.be.feed.service.FeedCacheInvalidationService;
import com.zhiguang.be.search.SearchIndexService;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import com.zhiguang.be.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentServiceImplTest {

    private KnowPostMapper knowPostMapper;
    private LbsDiscoverService lbsDiscoverService;
    private FeedCacheInvalidationService feedCacheInvalidationService;
    private FollowService followService;
    private InteractionService interactionService;
    private UserSocialCounterService userSocialCounterService;
    private StorageService storageService;
    private SearchIndexService searchIndexService;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private ContentServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        knowPostMapper = mock(KnowPostMapper.class);
        lbsDiscoverService = mock(LbsDiscoverService.class);
        feedCacheInvalidationService = mock(FeedCacheInvalidationService.class);
        followService = mock(FollowService.class);
        interactionService = mock(InteractionService.class);
        userSocialCounterService = mock(UserSocialCounterService.class);
        storageService = mock(StorageService.class);
        searchIndexService = mock(SearchIndexService.class);
        snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        ObjectProvider<SearchIndexService> searchIndexServiceProvider = mock(ObjectProvider.class);
        when(searchIndexServiceProvider.getIfAvailable()).thenReturn(searchIndexService);
        when(searchIndexService.isLocalSyncEnabled()).thenReturn(false);
        when(followService.relationStatus(anyLong(), anyLong())).thenReturn(new RelationStatusData(false, false, false));

        service = new ContentServiceImpl(
                knowPostMapper,
                lbsDiscoverService,
                feedCacheInvalidationService,
                followService,
                interactionService,
                userSocialCounterService,
                storageService,
                searchIndexServiceProvider,
                snowflakeIdGenerator,
                new ObjectMapper().findAndRegisterModules()
        );
        ReflectionTestUtils.setField(service, "outboxMaxRetryAttempts", 3);
    }

    @Test
    void deleteShouldDecrementPublishedCounterWhenDraftWasPublishedBeforeDeleteCommit() {
        when(knowPostMapper.findById("1001"))
                .thenReturn(post("1001", "7", "draft", null))
                .thenReturn(post("1001", "7", "deleted", Instant.now()));
        when(knowPostMapper.softDelete(eq("1001"), eq("7"), any())).thenReturn(1);

        service.delete(7L, 1001L);

        verify(userSocialCounterService).incrementPosts(7L, -1);
    }

    @Test
    void confirmContentShouldTreatExactDuplicateAsIdempotent() {
        KnowPostEntity confirmed = confirmedPost(
                "1001",
                "7",
                "posts/1001/content/content.md",
                "\"etag-1\"",
                12L,
                sha('a')
        );
        when(knowPostMapper.findById("1001")).thenReturn(confirmed);

        service.confirmContent(
                7L,
                1001L,
                new ConfirmContentRequest("posts/1001/content/content.md", "\"etag-1\"", sha('a'), 12L)
        );

        verify(knowPostMapper, never()).updateContent(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                any()
        );
    }

    @Test
    void confirmContentShouldRejectDifferentObjectAfterContentWasConfirmed() {
        when(knowPostMapper.findById("1001")).thenReturn(confirmedPost(
                "1001",
                "7",
                "posts/1001/content/old.md",
                "\"old\"",
                12L,
                sha('a')
        ));

        assertThrows(
                BusinessException.class,
                () -> service.confirmContent(
                        7L,
                        1001L,
                        new ConfirmContentRequest("posts/1001/content/new.md", "\"new\"", sha('b'), 13L)
                )
        );

        verify(knowPostMapper, never()).updateContent(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                any()
        );
    }

    @Test
    void confirmContentShouldValidateUploadedObjectMetadataBeforeSaving() {
        ConfirmContentRequest request = new ConfirmContentRequest(
                "posts/1001/content/content.md",
                "\"etag-1\"",
                sha('d'),
                128L
        );
        when(knowPostMapper.findById("1001")).thenReturn(unconfirmedPost("1001", "7", "draft"));
        when(storageService.toPublicUrl("posts/1001/content/content.md"))
                .thenReturn("https://oss.local/posts/1001/content/content.md");
        when(knowPostMapper.updateContent(
                eq("1001"),
                eq("7"),
                eq("metadata_completed"),
                eq("https://oss.local/posts/1001/content/content.md"),
                eq("posts/1001/content/content.md"),
                eq("\"etag-1\""),
                eq(128L),
                eq(sha('d')),
                any()
        )).thenReturn(1);

        service.confirmContent(7L, 1001L, request);

        verify(storageService).validateUploadedObject("posts/1001/content/content.md", "\"etag-1\"", 128L);
    }

    @Test
    void publishShouldReturnCurrentDetailWhenPostIsAlreadyPublished() {
        Instant publishedAt = Instant.now().minusSeconds(3600);
        when(knowPostMapper.findById("1001")).thenReturn(post("1001", "7", "published", publishedAt));
        when(knowPostMapper.findDetailById("1001")).thenReturn(detail("1001", "7", false, publishedAt));

        service.publish(7L, 1001L);

        verify(knowPostMapper, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(userSocialCounterService, never()).incrementPosts(anyLong(), anyInt());
        verify(knowPostMapper, never()).insertOutbox(any());
        verify(feedCacheInvalidationService, never()).invalidatePostAfterCommit(anyString());
    }

    @Test
    void updateTopShouldWriteOutboxAndRefreshDiscoverProjection() {
        Instant publishedAt = Instant.now().minusSeconds(3600);
        when(knowPostMapper.findById("1001")).thenReturn(post("1001", "7", "published", publishedAt));
        when(knowPostMapper.updateTop(eq("1001"), eq("7"), eq(true), any())).thenReturn(1);
        when(knowPostMapper.findDetailById("1001")).thenReturn(detail("1001", "7", true, publishedAt));
        when(snowflakeIdGenerator.nextId()).thenReturn(9001L);

        service.updateTop(7L, 1001L, true);

        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(knowPostMapper).insertOutbox(outboxCaptor.capture());
        assertEquals("POST_TOP_CHANGED", outboxCaptor.getValue().eventType());
        verify(lbsDiscoverService).addLocation(
                eq("1001"),
                eq("knowledge"),
                eq(31.2D),
                eq(121.4D),
                eq("title"),
                eq("summary"),
                any(),
                eq("address"),
                eq("7"),
                eq("author"),
                any(),
                any(),
                eq(publishedAt.toEpochMilli()),
                eq(0),
                eq(0)
        );
    }

    @Test
    void cleanupExpiredDraftsShouldSoftDeleteOldMutablePosts() {
        ReflectionTestUtils.setField(service, "draftTtlDays", 3L);
        ReflectionTestUtils.setField(service, "draftCleanupBatchSize", 25);
        when(knowPostMapper.softDeleteExpiredDrafts(any(), any(), eq(25))).thenReturn(4);

        service.cleanupExpiredDrafts();

        ArgumentCaptor<Instant> expiresBeforeCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(knowPostMapper).softDeleteExpiredDrafts(expiresBeforeCaptor.capture(), any(), eq(25));
        assertTrue(expiresBeforeCaptor.getValue().isBefore(Instant.now().minus(Duration.ofDays(2))));
    }

    @Test
    void reconcileDiscoverOutboxShouldReplaySearchBeforeMarkingEventPublished() throws Exception {
        Instant publishedAt = Instant.now().minusSeconds(3600);
        OutboxEventEntity event = outboxEvent("event-1", "POST_PUBLISHED", "1001");
        when(searchIndexService.isLocalSyncEnabled()).thenReturn(true);
        when(knowPostMapper.listPendingOutbox(eq(20), eq(3))).thenReturn(List.of(event));
        when(knowPostMapper.findById("1001")).thenReturn(post("1001", "7", "published", publishedAt));
        when(knowPostMapper.findDetailById("1001")).thenReturn(detail("1001", "7", false, publishedAt));

        service.reconcileDiscoverOutbox();

        verify(searchIndexService).syncPostStrict(1001L);
        verify(lbsDiscoverService).addLocation(
                eq("1001"),
                eq("knowledge"),
                eq(31.2D),
                eq(121.4D),
                eq("title"),
                eq("summary"),
                any(),
                eq("address"),
                eq("7"),
                eq("author"),
                any(),
                any(),
                eq(publishedAt.toEpochMilli()),
                eq(0),
                eq(0)
        );
        verify(knowPostMapper).markOutboxPublished(eq("event-1"), any());
    }

    @Test
    void reconcileDiscoverOutboxShouldUseMaxRetryWhenProjectionFails() throws Exception {
        OutboxEventEntity event = outboxEvent("event-1", "POST_PUBLISHED", "1001");
        when(searchIndexService.isLocalSyncEnabled()).thenReturn(true);
        when(knowPostMapper.listPendingOutbox(eq(20), eq(3))).thenReturn(List.of(event));
        doThrow(new RuntimeException("search down")).when(searchIndexService).syncPostStrict(1001L);

        service.reconcileDiscoverOutbox();

        verify(knowPostMapper).markOutboxFailed("event-1", "search down", 3);
        verify(knowPostMapper, never()).markOutboxPublished(eq("event-1"), any());
    }

    private KnowPostEntity confirmedPost(
            String postId,
            String creatorId,
            String objectKey,
            String etag,
            Long size,
            String sha256
    ) {
        return new KnowPostEntity(
                postId,
                creatorId,
                null,
                null,
                "title",
                null,
                null,
                null,
                null,
                null,
                "https://oss.local/" + objectKey,
                objectKey,
                etag,
                size,
                sha256,
                Boolean.FALSE,
                "image_text",
                "public",
                null,
                null,
                "content_confirmed",
                Instant.now(),
                Instant.now(),
                null
        );
    }

    private OutboxEventEntity outboxEvent(String eventId, String eventType, String postId) {
        return new OutboxEventEntity(
                eventId,
                "post",
                postId,
                eventType,
                "{}",
                "pending",
                0,
                Instant.now()
        );
    }

    private KnowPostEntity post(String postId, String creatorId, String status, Instant publishTime) {
        return new KnowPostEntity(
                postId,
                creatorId,
                null,
                null,
                "title",
                "summary",
                31.2D,
                121.4D,
                null,
                "address",
                "https://oss.local/posts/" + postId + "/content/content.md",
                "posts/" + postId + "/content/content.md",
                "\"etag\"",
                12L,
                sha('c'),
                Boolean.FALSE,
                "image_text",
                "public",
                null,
                null,
                status,
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600),
                publishTime
        );
    }

    private KnowPostEntity unconfirmedPost(String postId, String creatorId, String status) {
        return new KnowPostEntity(
                postId,
                creatorId,
                null,
                null,
                "title",
                "summary",
                31.2D,
                121.4D,
                null,
                "address",
                null,
                null,
                null,
                null,
                null,
                Boolean.FALSE,
                "image_text",
                "public",
                null,
                null,
                status,
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600),
                null
        );
    }

    private KnowPostDetailRow detail(String postId, String creatorId, boolean isTop, Instant publishedAt) {
        return new KnowPostDetailRow(
                postId,
                creatorId,
                "author",
                null,
                "published",
                "title",
                "summary",
                "https://oss.local/posts/" + postId + "/content/content.md",
                null,
                null,
                "public",
                "image_text",
                isTop,
                31.2D,
                121.4D,
                null,
                "address",
                publishedAt,
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600)
        );
    }

    private String sha(char value) {
        return String.valueOf(value).repeat(64);
    }
}
