package com.zhiguang.be.feed.service;

import com.zhiguang.be.cache.CacheRegions;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.tx.Transactions;
import com.zhiguang.be.feed.FeedCacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Feed 缓存失效服务。
 * 内容发生发布、删除、置顶或可见性变化时，统一负责清理 Feed 三层缓存。
 */
@Service
public class FeedCacheInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(FeedCacheInvalidationService.class);

    private static final long DOUBLE_DELETE_DELAY_MILLIS = 80L;

    private final CacheService cacheService;
    private final Executor delayedDeleteExecutor;

    @Value("${feed.cache.version:v1}")
    private String cacheVersion;

    @Autowired
    public FeedCacheInvalidationService(CacheService cacheService) {
        this(cacheService, CompletableFuture.delayedExecutor(DOUBLE_DELETE_DELAY_MILLIS, TimeUnit.MILLISECONDS));
    }

    FeedCacheInvalidationService(CacheService cacheService, Executor delayedDeleteExecutor) {
        this.cacheService = cacheService;
        this.delayedDeleteExecutor = delayedDeleteExecutor;
    }

    /**
     * 在事务提交后失效指定文章相关的 Feed 缓存。
     * 采用双删方式，降低并发回源把旧页面重新写回缓存的概率。
     *
     * @param postId 文章 ID
     */
    public void invalidatePostAfterCommit(String postId) {
        if (!StringUtils.hasText(postId)) {
            return;
        }
        Runnable task = () -> doubleDeletePost(postId.trim());
        Transactions.runAfterCommit(task);
    }

    /**
     * Invalidates only the per-post fragment after an interaction count changes.
     * The post remains on the same Feed pages, so scanning and deleting every home-page key is unnecessary.
     *
     * @param postId post identifier whose interaction counters changed
     */
    public void invalidatePostFragmentAfterCommit(String postId) {
        if (!StringUtils.hasText(postId)) {
            return;
        }
        Runnable task = () -> doubleDeletePostFragment(postId.trim());
        Transactions.runAfterCommit(task);
    }

    /**
     * 立即执行指定文章的双删失效。
     *
     * @param postId 文章 ID
     */
    private void doubleDeletePost(String postId) {
        deletePostOnce(postId);
        delayedDeleteExecutor.execute(() -> deletePostOnce(postId));
    }

    private void doubleDeletePostFragment(String postId) {
        deletePostFragmentOnce(postId);
        delayedDeleteExecutor.execute(() -> deletePostFragmentOnce(postId));
    }

    /**
     * 单次清理：删除本地完整页、Redis 页面骨架和当前文章碎片。
     *
     * @param postId 文章 ID
     */
    private void deletePostOnce(String postId) {
        cacheService.evictLocalRegion(CacheRegions.FEED_HOME);
        long pageDeleted = cacheService.deleteRedisByPattern(FeedCacheKeys.homePagePattern(cacheVersion));
        cacheService.deleteRedis(FeedCacheKeys.fragmentKey(cacheVersion, postId));
        // 迁移期兼容旧实例：滚动发布或回滚时，旧无版本缓存也必须被同一事件清理。
        pageDeleted += cacheService.deleteRedisByPattern(FeedCacheKeys.legacyHomePagePattern());
        cacheService.deleteRedis(FeedCacheKeys.legacyFragmentKey(postId));
        log.debug("feed cache invalidated, postId={}, pageDeleted={}", postId, pageDeleted);
    }

    private void deletePostFragmentOnce(String postId) {
        // Local Feed pages are cheap to discard; Redis home-page scans are reserved for membership changes.
        cacheService.evictLocalRegion(CacheRegions.FEED_HOME);
        cacheService.deleteRedis(FeedCacheKeys.fragmentKey(cacheVersion, postId));
        cacheService.deleteRedis(FeedCacheKeys.legacyFragmentKey(postId));
        log.debug("feed fragment invalidated after interaction, postId={}", postId);
    }
}
