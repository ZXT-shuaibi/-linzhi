package com.zhiguang.be.feed.service;

import com.zhiguang.be.cache.CacheRegions;
import com.zhiguang.be.cache.service.CacheService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class FeedCacheInvalidationServiceTest {

    @Test
    void invalidatePostFragmentAfterCommitShouldAvoidHomePagePatternScan() {
        CacheService cacheService = mock(CacheService.class);
        FeedCacheInvalidationService service = new FeedCacheInvalidationService(cacheService, Runnable::run);

        service.invalidatePostFragmentAfterCommit("1001");

        verify(cacheService, times(2)).evictLocalRegion(CacheRegions.FEED_HOME);
        verify(cacheService, times(2)).deleteRedis("feed:v1:fragment:post:1001");
        verify(cacheService, times(2)).deleteRedis("feed:fragment:post:1001");
        verify(cacheService, never()).deleteRedisByPattern(anyString());
    }

    @Test
    void invalidatePostAfterCommitShouldDeleteVersionedPageAndFragmentKeys() {
        CacheService cacheService = mock(CacheService.class);
        FeedCacheInvalidationService service = new FeedCacheInvalidationService(cacheService, Runnable::run);

        service.invalidatePostAfterCommit("1001");

        verify(cacheService, atLeastOnce()).evictLocalRegion(CacheRegions.FEED_HOME);
        verify(cacheService, times(2)).deleteRedisByPattern("feed:v1:page:home:*");
        verify(cacheService, times(2)).deleteRedis("feed:v1:fragment:post:1001");
    }

    @Test
    void invalidatePostAfterCommitShouldAlsoDeleteLegacyUnversionedKeysDuringMigration() {
        CacheService cacheService = mock(CacheService.class);
        FeedCacheInvalidationService service = new FeedCacheInvalidationService(cacheService, Runnable::run);

        service.invalidatePostAfterCommit("1001");

        verify(cacheService, times(2)).deleteRedisByPattern("feed:page:home:*");
        verify(cacheService, times(2)).deleteRedis("feed:fragment:post:1001");
    }

    @Test
    void invalidatePostAfterCommitShouldUseConfiguredCacheVersion() {
        CacheService cacheService = mock(CacheService.class);
        FeedCacheInvalidationService service = new FeedCacheInvalidationService(cacheService, Runnable::run);
        ReflectionTestUtils.setField(service, "cacheVersion", "v2");

        service.invalidatePostAfterCommit("1001");

        verify(cacheService, times(2)).deleteRedisByPattern("feed:v2:page:home:*");
        verify(cacheService, times(2)).deleteRedis("feed:v2:fragment:post:1001");
    }
}
