package com.zhiguang.be.feed.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.CacheRegions;
import com.zhiguang.be.cache.hotkey.HotKeyDetector;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.content.dto.PostAuthor;
import com.zhiguang.be.feed.FeedData;
import com.zhiguang.be.feed.FeedItem;
import com.zhiguang.be.feed.FeedPostRow;
import com.zhiguang.be.feed.mapper.FeedMapper;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.PageMeta;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceImplTest {

    private FeedMapper feedMapper;
    private CacheService cacheService;
    private HotKeyDetector hotKeyDetector;
    private InteractionService interactionService;
    private FeedServiceImpl service;

    @BeforeEach
    void setUp() {
        feedMapper = mock(FeedMapper.class);
        cacheService = mock(CacheService.class);
        hotKeyDetector = mock(HotKeyDetector.class);
        FollowService followService = mock(FollowService.class);
        interactionService = mock(InteractionService.class);
        UserSocialCounterService userSocialCounterService = mock(UserSocialCounterService.class);

        doAnswer(invocation -> invocation.getArgument(1, Duration.class))
                .when(hotKeyDetector).ttl(anyString(), any(Duration.class));
        when(interactionService.summaryBatch(anyLong(), eq("post"), any()))
                .thenReturn(Map.of("1001", new InteractionSummary("post", "1001", 9L, 3L, false, false)));

        service = new FeedServiceImpl(
                feedMapper,
                cacheService,
                hotKeyDetector,
                new ObjectMapper().findAndRegisterModules(),
                followService,
                interactionService,
                userSocialCounterService
        );
        // Mirror validation is enabled by default in the production configuration.
        ReflectionTestUtils.setField(service, "legacyMirrorValidationEnabled", true);
    }

    @Test
    void getHomeFeedShouldWriteVersionedPageAndFragmentCacheKeys() {
        when(feedMapper.countHomeFeed()).thenReturn(1L);
        when(feedMapper.listHomeFeedCandidates(20, 0)).thenReturn(List.of(row("1001")));

        service.getHomeFeed(1, 20, null, null, null, 0L);

        verify(cacheService).putRedisJson(eq("feed:v1:fragment:post:1001"), any(), any(Duration.class));
        verify(cacheService).putRedisJson(eq("feed:fragment:post:1001"), any(), any(Duration.class));
        verify(cacheService).putRedisJson(eq("feed:v1:page:home:global:1:20"), any(), any(Duration.class));
        verify(cacheService).putRedisJson(eq("feed:page:home:global:1:20"), any(), any(Duration.class));
        verify(cacheService).putLocal(eq(CacheRegions.FEED_HOME), eq("feed:v1:page:home:global:1:20"), any(), any(Duration.class));
    }

    @Test
    void getHomeFeedShouldUseConfiguredCacheVersion() {
        ReflectionTestUtils.setField(service, "cacheVersion", "v2");
        when(feedMapper.countHomeFeed()).thenReturn(1L);
        when(feedMapper.listHomeFeedCandidates(20, 0)).thenReturn(List.of(row("1001")));

        service.getHomeFeed(1, 20, null, null, null, 0L);

        verify(cacheService).putRedisJson(eq("feed:v2:fragment:post:1001"), any(), any(Duration.class));
        verify(cacheService).putRedisJson(eq("feed:fragment:post:1001"), any(), any(Duration.class));
        verify(cacheService).putRedisJson(eq("feed:v2:page:home:global:1:20"), any(), any(Duration.class));
        verify(cacheService).putRedisJson(eq("feed:page:home:global:1:20"), any(), any(Duration.class));
    }

    @Test
    void getHomeFeedShouldBypassVersionedRedisPageWhenLegacyMirrorWasDeletedByOldNode() {
        when(feedMapper.countHomeFeed()).thenReturn(1L);
        when(feedMapper.listHomeFeedCandidates(20, 0)).thenReturn(List.of(row("1001")));
        when(cacheService.getRedisString("feed:page:home:global:1:20")).thenReturn(null);

        service.getHomeFeed(1, 20, null, null, null, 0L);

        verify(cacheService, never()).getRedisJson(eq("feed:v1:page:home:global:1:20"), any());
        verify(feedMapper).listHomeFeedCandidates(20, 0);
    }

    @Test
    void getHomeFeedShouldBypassLocalCacheWhenLegacyPageMirrorDiffersFromVersionedPage() {
        when(cacheService.getLocal(eq(CacheRegions.FEED_HOME), eq("feed:v1:page:home:global:1:20"), any()))
                .thenReturn(cachedFeedData("stale-title"));
        when(cacheService.getRedisString("feed:v1:page:home:global:1:20")).thenReturn("old-page");
        when(cacheService.getRedisString("feed:page:home:global:1:20")).thenReturn("fresh-page");
        when(feedMapper.countHomeFeed()).thenReturn(1L);
        when(feedMapper.listHomeFeedCandidates(20, 0)).thenReturn(List.of(row("1001")));

        FeedData result = service.getHomeFeed(1, 20, null, null, null, 0L);

        org.junit.jupiter.api.Assertions.assertEquals("DB", result.cacheLayer());
        verify(feedMapper).listHomeFeedCandidates(20, 0);
    }

    @Test
    void getHomeFeedShouldBypassLocalCacheWhenLegacyFragmentMirrorDiffersFromVersionedFragment() {
        when(cacheService.getLocal(eq(CacheRegions.FEED_HOME), eq("feed:v1:page:home:global:1:20"), any()))
                .thenReturn(cachedFeedData("stale-title"));
        when(cacheService.getRedisString("feed:v1:page:home:global:1:20")).thenReturn("same-page");
        when(cacheService.getRedisString("feed:page:home:global:1:20")).thenReturn("same-page");
        when(cacheService.getRedisStrings(List.of("feed:v1:fragment:post:1001"))).thenReturn(List.of("old-fragment"));
        when(cacheService.getRedisStrings(List.of("feed:fragment:post:1001"))).thenReturn(List.of("fresh-fragment"));
        when(feedMapper.countHomeFeed()).thenReturn(1L);
        when(feedMapper.listHomeFeedCandidates(20, 0)).thenReturn(List.of(row("1001")));

        FeedData result = service.getHomeFeed(1, 20, null, null, null, 0L);

        org.junit.jupiter.api.Assertions.assertEquals("DB", result.cacheLayer());
        verify(feedMapper).listHomeFeedCandidates(20, 0);
    }

    @Test
    void getHomeFeedShouldUseLocalCacheWhenLegacyMirrorValidationIsDisabled() {
        ReflectionTestUtils.setField(service, "legacyMirrorValidationEnabled", false);
        when(cacheService.getLocal(eq(CacheRegions.FEED_HOME), eq("feed:v1:page:home:global:1:20"), any()))
                .thenReturn(cachedFeedData("cached-title"));

        FeedData result = service.getHomeFeed(1, 20, null, null, null, 0L);

        org.junit.jupiter.api.Assertions.assertEquals("L2", result.cacheLayer());
        org.junit.jupiter.api.Assertions.assertEquals("cached-title", result.items().get(0).title());
        verify(feedMapper, never()).listHomeFeedCandidates(anyInt(), anyInt());
    }

    private FeedPostRow row(String postId) {
        return new FeedPostRow(
                postId,
                "7",
                "author",
                null,
                "title",
                "summary",
                null,
                null,
                31.2D,
                121.4D,
                Instant.now().minusSeconds(60),
                Boolean.FALSE
        );
    }

    private FeedData cachedFeedData(String title) {
        FeedItem item = new FeedItem(
                "1001",
                title,
                "summary",
                null,
                List.of(),
                new PostAuthor("7", "author", null, null, null),
                1L,
                1L,
                null,
                null,
                null,
                0.5D,
                Boolean.FALSE,
                Instant.now().minusSeconds(60)
        );
        return new FeedData(List.of(item), PageMeta.of(1, 20, 1), "L2");
    }
}
