package com.zhiguang.be.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.common.tx.Transactions;
import com.zhiguang.be.common.util.Numbers;
import com.zhiguang.be.content.dto.ConfirmContentRequest;
import com.zhiguang.be.content.dto.DraftData;
import com.zhiguang.be.content.dto.PostAuthor;
import com.zhiguang.be.content.dto.PostCard;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostLocation;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.dto.UpdatePostMetadataRequest;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostDetailRow;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.KnowPostFeedRow;
import com.zhiguang.be.content.model.OutboxEventEntity;
import com.zhiguang.be.content.model.PostSyncPayload;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import com.zhiguang.be.feed.service.FeedCacheInvalidationService;
import com.zhiguang.be.search.SearchIndexService;
import com.zhiguang.be.storage.StorageService;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.InteractionService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.zhiguang.be.common.util.Texts.hasText;

@Service
public class ContentServiceImpl implements ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentServiceImpl.class);

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_CONTENT_CONFIRMED = "content_confirmed";
    private static final String STATUS_METADATA_COMPLETED = "metadata_completed";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_DELETED = "deleted";

    private static final String DEFAULT_TYPE = "image_text";
    private static final String DEFAULT_VISIBILITY = "public";
    private static final String FOLLOWERS_VISIBILITY = "followers";

    private static final String EVENT_POST_PUBLISHED = "POST_PUBLISHED";
    private static final String EVENT_POST_VISIBILITY_CHANGED = "POST_VISIBILITY_CHANGED";
    private static final String EVENT_POST_TOP_CHANGED = "POST_TOP_CHANGED";
    private static final String EVENT_POST_DELETED = "POST_DELETED";

    private static final String DISCOVER_TYPE = "knowledge";

    private final KnowPostMapper knowPostMapper;
    private final LbsDiscoverService lbsDiscoverService;
    private final FeedCacheInvalidationService feedCacheInvalidationService;
    private final FollowService followService;
    private final InteractionService interactionService;
    private final UserSocialCounterService userSocialCounterService;
    private final StorageService storageService;
    private final SearchIndexService searchIndexService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;

    @Value("${content.draft-ttl-days:7}")
    private long draftTtlDays;

    @Value("${content.draft-cleanup-batch-size:100}")
    private int draftCleanupBatchSize;

    @Value("${content.outbox-max-retry-attempts:5}")
    private int outboxMaxRetryAttempts;

    public ContentServiceImpl(
            KnowPostMapper knowPostMapper,
            LbsDiscoverService lbsDiscoverService,
            FeedCacheInvalidationService feedCacheInvalidationService,
            FollowService followService,
            InteractionService interactionService,
            UserSocialCounterService userSocialCounterService,
            StorageService storageService,
            ObjectProvider<SearchIndexService> searchIndexServiceProvider,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ObjectMapper objectMapper
    ) {
        this.knowPostMapper = knowPostMapper;
        this.lbsDiscoverService = lbsDiscoverService;
        this.feedCacheInvalidationService = feedCacheInvalidationService;
        this.followService = followService;
        this.interactionService = interactionService;
        this.userSocialCounterService = userSocialCounterService;
        this.storageService = storageService;
        this.searchIndexService = searchIndexServiceProvider.getIfAvailable();
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Override
    public DraftData createDraft(long creatorId) {
        Instant now = Instant.now();
        long postId = snowflakeIdGenerator.nextId();
        knowPostMapper.insert(new KnowPostEntity(
                String.valueOf(postId),
                String.valueOf(creatorId),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                Boolean.FALSE, DEFAULT_TYPE, DEFAULT_VISIBILITY, null, null,
                STATUS_DRAFT, now, now, null
        ));
        return new DraftData(String.valueOf(postId), STATUS_DRAFT, now);
    }

    @Override
    public PostPageData getPublicFeed(Long viewerId, int page, int size) {
        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedPublic(safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize, viewerId);
    }

    @Override
    public PostPageData getMyPublished(long creatorId, int page, int size) {
        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        List<KnowPostFeedRow> rows = knowPostMapper.listMyPublished(
                String.valueOf(creatorId), safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize, creatorId);
    }

    @Override
    public PostPageData getUserPublished(long creatorId, Long viewerId, int page, int size) {
        if (viewerId != null && creatorId == viewerId) {
            return getMyPublished(creatorId, page, size);
        }

        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        boolean includeFollowers = viewerId != null
                && followService.isFollowing(viewerId, creatorId);
        List<KnowPostFeedRow> rows = knowPostMapper.listUserPublished(
                String.valueOf(creatorId), includeFollowers, safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize, viewerId);
    }

    @Transactional
    @Override
    public void confirmContent(long creatorId, long postId, ConfirmContentRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertMutable(entity);
        String postIdStr = String.valueOf(postId);
        if (!request.objectKey().startsWith("posts/" + postIdStr + "/content/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "objectKey 与当前文章不匹配");
        }

        if (hasText(entity.contentObjectKey())) {
            if (isSameConfirmedContent(entity, request)) {
                return;
            }
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "正文已确认，不能重复覆盖");
        }

        storageService.validateUploadedObject(request.objectKey(), request.etag(), request.size());

        String nextStatus = hasText(entity.title()) ? STATUS_METADATA_COMPLETED : STATUS_CONTENT_CONFIRMED;
        int updated = knowPostMapper.updateContent(
                postIdStr, String.valueOf(creatorId),
                nextStatus,
                storageService.toPublicUrl(request.objectKey()),
                request.objectKey(), request.etag(), request.size(), request.sha256(),
                Instant.now()
        );
        if (updated == 0) {
            KnowPostEntity latest = knowPostMapper.findById(postIdStr);
            if (isSameConfirmedContent(latest, request)) {
                return;
            }
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "正文确认失败，请刷新后重试");
        }
    }

    @Transactional
    @Override
    public PostDetail updateMetadata(long creatorId, long postId, UpdatePostMetadataRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertMutable(entity);

        String title = request.title() == null ? entity.title() : normalizeNullableText(request.title());
        String summary = request.summary() == null ? entity.description() : normalizeNullableText(request.summary());

        String tagsJson = entity.tagsJson();
        if (request.tags() != null) {
            List<String> normalizedTags = new ArrayList<>();
            for (String rawTag : request.tags()) {
                String normalizedTag = normalizeNullableText(rawTag);
                if (normalizedTag != null && !normalizedTags.contains(normalizedTag)) {
                    normalizedTags.add(normalizedTag);
                }
            }
            tagsJson = toJson(normalizedTags);
        }

        String imgUrlsJson = entity.imgUrlsJson();
        if (request.imageUrls() != null) {
            List<String> normalizedImageUrls = new ArrayList<>();
            for (String rawImageUrl : request.imageUrls()) {
                String normalizedImageUrl = storageService.normalizeOwnedPostImageUrl(
                        String.valueOf(postId), rawImageUrl);
                if (normalizedImageUrl != null && !normalizedImageUrls.contains(normalizedImageUrl)) {
                    normalizedImageUrls.add(normalizedImageUrl);
                }
            }
            imgUrlsJson = toJson(normalizedImageUrls);
        }

        Double latitude = entity.latitude();
        Double longitude = entity.longitude();
        String geoHash = entity.geoHash();
        String address = entity.address();
        if (request.location() != null) {
            latitude = request.location().lat();
            longitude = request.location().lng();
            geoHash = normalizeNullableText(request.location().geoHash());
            address = normalizeNullableText(request.location().address());
        }

        String visibility = request.visibility() == null
                ? (hasText(entity.visible()) ? entity.visible() : DEFAULT_VISIBILITY)
                : request.visibility().trim();
        Boolean isTop = request.isTop() == null ? entity.isTop() : request.isTop();

        String nextStatus = hasText(title) && hasText(entity.contentUrl())
                ? STATUS_METADATA_COMPLETED
                : (hasText(entity.contentUrl()) ? STATUS_CONTENT_CONFIRMED : STATUS_DRAFT);

        int updated = knowPostMapper.updateMetadata(
                String.valueOf(postId), String.valueOf(creatorId),
                nextStatus, title, summary, tagsJson, imgUrlsJson,
                isTop, visibility, latitude, longitude, geoHash, address,
                Instant.now()
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章元数据更新失败，请刷新后重试");
        }
        return getDetail(postId, creatorId);
    }

    @Transactional
    @Override
    public PostDetail publish(long creatorId, long postId) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        if (STATUS_PUBLISHED.equals(entity.status())) {
            return getDetail(postId, creatorId);
        }
        assertMutable(entity);
        if (!hasText(entity.contentUrl())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请先确认正文上传");
        }
        if (!hasText(entity.title())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请先补全标题后再发布");
        }

        Instant now = Instant.now();
        Instant publishTime = now;
        String visibility = hasText(entity.visible()) ? entity.visible() : DEFAULT_VISIBILITY;

        int updated = knowPostMapper.publish(
                String.valueOf(postId), String.valueOf(creatorId),
                visibility, STATUS_PUBLISHED, publishTime, now
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章发布失败，请刷新后重试");
        }

        enqueuePostSyncEvent(postId, EVENT_POST_PUBLISHED, now);
        Transactions.runAfterCommit(() -> {
            incrementPublishedPostCounter(creatorId, 1);
            syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), visibility, publishTime);
            syncSearchIndex(postId);
        });
        feedCacheInvalidationService.invalidatePostAfterCommit(String.valueOf(postId));
        return getDetail(postId, creatorId);
    }

    @Transactional
    @Override
    public PostDetail updateTop(long creatorId, long postId, boolean isTop) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertPublished(entity);

        Instant now = Instant.now();
        int updated = knowPostMapper.updateTop(
                String.valueOf(postId), String.valueOf(creatorId), isTop, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章置顶状态更新失败，请刷新后重试");
        }
        enqueuePostSyncEvent(postId, EVENT_POST_TOP_CHANGED, now);
        Transactions.runAfterCommit(() -> {
            syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), entity.visible(), entity.publishTime());
            syncSearchIndex(postId);
        });
        feedCacheInvalidationService.invalidatePostAfterCommit(String.valueOf(postId));
        return getDetail(postId, creatorId);
    }

    @Transactional
    @Override
    public PostDetail updateVisibility(long creatorId, long postId, String visibility) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertPublished(entity);

        String normalizedVisibility = visibility.trim();
        Instant now = Instant.now();
        int updated = knowPostMapper.updateVisibility(
                String.valueOf(postId), String.valueOf(creatorId), normalizedVisibility, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章可见性更新失败，请刷新后重试");
        }

        enqueuePostSyncEvent(postId, EVENT_POST_VISIBILITY_CHANGED, now);
        Transactions.runAfterCommit(() -> {
            syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), normalizedVisibility, entity.publishTime());
            syncSearchIndex(postId);
        });
        feedCacheInvalidationService.invalidatePostAfterCommit(String.valueOf(postId));
        return getDetail(postId, creatorId);
    }

    @Transactional
    @Override
    public void delete(long creatorId, long postId) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }

        Instant now = Instant.now();
        int updated = knowPostMapper.softDelete(
                String.valueOf(postId), String.valueOf(creatorId), now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章删除失败，请刷新后重试");
        }

        KnowPostEntity deletedEntity = knowPostMapper.findById(String.valueOf(postId));
        boolean shouldDecrementPublishedCounter = wasPublishedBeforeDelete(entity, deletedEntity);
        enqueuePostSyncEvent(postId, EVENT_POST_DELETED, now);
        Transactions.runAfterCommit(() -> {
            if (shouldDecrementPublishedCounter) {
                incrementPublishedPostCounter(creatorId, -1);
            }
            removeFromDiscover(postId);
            removeFromSearchIndex(postId);
        });
        feedCacheInvalidationService.invalidatePostAfterCommit(String.valueOf(postId));
    }

    @Override
    public PostDetail getDetail(long postId, Long viewerId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(String.valueOf(postId));
        if (row == null || STATUS_DELETED.equals(row.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }

        boolean isOwner = viewerId != null && Objects.equals(row.creatorId(), String.valueOf(viewerId));
        long viewerUserId = viewerId == null ? 0L : viewerId;
        long creatorUserId = Numbers.toLongOrZero(row.creatorId());
        boolean isPublicPublished = STATUS_PUBLISHED.equals(row.status()) && DEFAULT_VISIBILITY.equals(row.visible());
        boolean isFollowersVisible = STATUS_PUBLISHED.equals(row.status())
                && FOLLOWERS_VISIBILITY.equals(row.visible())
                && viewerUserId > 0L
                && creatorUserId > 0L
                && followService.isFollowing(viewerUserId, creatorUserId);
        if (!isPublicPublished && !isOwner && !isFollowersVisible) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "当前文章暂无访问权限");
        }

        List<String> imageUrls = parseStringList(row.imgUrlsJson());
        InteractionSummary summary = loadDetailInteractionSummary(row, viewerUserId);
        PostAuthor author = buildAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar(), viewerUserId);
        return new PostDetail(
                row.postId(),
                author,
                row.status(),
                row.title(),
                row.description(),
                row.contentUrl(),
                imageUrls,
                parseStringList(row.tagsJson()),
                new PostLocation(row.latitude(), row.longitude(), row.geoHash(), row.address()),
                row.visible(),
                row.type(),
                row.isTop(),
                summary == null ? 0L : summary.getLikeCount(),
                summary == null ? 0L : summary.getFavoriteCount(),
                viewerUserId > 0L && summary != null ? summary.isViewerLiked() : null,
                viewerUserId > 0L && summary != null ? summary.isViewerFavorited() : null,
                row.publishTime(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    /**
     * 定时补偿 discover 和 search 同步事件。
     * 从 outbox 表中取出待处理的事件，重放到 discover 和 search 索引。
     */
    @Scheduled(fixedDelayString = "${content.outbox-reconcile-delay-ms:10000}")
    public void reconcileDiscoverOutbox() {
        int maxRetryAttempts = normalizedOutboxMaxRetryAttempts();
        List<OutboxEventEntity> events = knowPostMapper.listPendingOutbox(20, maxRetryAttempts);
        for (OutboxEventEntity event : events) {
            try {
                reconcileSearchState(event);
                reconcileDiscoverState(event.aggregateId());
                knowPostMapper.markOutboxPublished(event.id(), Instant.now());
            } catch (Exception ex) {
                knowPostMapper.markOutboxFailed(event.id(), abbreviateError(ex.getMessage()), maxRetryAttempts);
                log.warn("Failed to reconcile outbox event {} for post {}: {}", event.id(), event.aggregateId(), ex.getMessage());
            }
        }
    }

    /**
     * 定时清理长时间未发布的过期草稿。
     * 超过 draftTtlDays 天仍未发布的草稿将被软删除。
     */
    @Scheduled(fixedDelayString = "${content.draft-cleanup-delay-ms:3600000}")
    public void cleanupExpiredDrafts() {
        if (draftTtlDays <= 0) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresBefore = now.minus(Duration.ofDays(draftTtlDays));
        int batchSize = Math.max(draftCleanupBatchSize, 1);
        int cleaned = knowPostMapper.softDeleteExpiredDrafts(expiresBefore, now, batchSize);
        if (cleaned > 0) {
            log.info("Cleaned expired content drafts, count={}", cleaned);
        }
    }

    private KnowPostEntity loadOwnedPost(long postId, long creatorId) {
        String postIdStr = String.valueOf(postId);
        String creatorIdStr = String.valueOf(creatorId);
        KnowPostEntity entity = knowPostMapper.findById(postIdStr);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (!Objects.equals(entity.creatorId(), creatorIdStr)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "无权操作该文章");
        }
        return entity;
    }

    private void assertMutable(KnowPostEntity entity) {
        if (STATUS_PUBLISHED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章已发布，当前阶段不支持再次编辑");
        }
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
    }

    private void assertPublished(KnowPostEntity entity) {
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (!STATUS_PUBLISHED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "仅已发布文章支持当前操作");
        }
    }

    private boolean isSameConfirmedContent(KnowPostEntity entity, ConfirmContentRequest request) {
        return entity != null
                && Objects.equals(entity.contentObjectKey(), request.objectKey())
                && Objects.equals(entity.contentEtag(), request.etag())
                && Objects.equals(entity.contentSize(), request.size())
                && Objects.equals(entity.contentSha256(), request.sha256());
    }

    private boolean wasPublishedBeforeDelete(KnowPostEntity beforeDelete, KnowPostEntity afterDelete) {
        return (beforeDelete != null && STATUS_PUBLISHED.equals(beforeDelete.status()))
                || (afterDelete != null && afterDelete.publishTime() != null);
    }

    private void reconcileSearchState(OutboxEventEntity event) throws Exception {
        if (searchIndexService == null || !searchIndexService.isLocalSyncEnabled()) {
            return;
        }
        Long postId = Numbers.toLongOrNull(event.aggregateId());
        if (postId == null) {
            return;
        }
        if (EVENT_POST_DELETED.equals(event.eventType())) {
            searchIndexService.deletePostStrict(postId);
            return;
        }
        if (isSearchSyncEvent(event.eventType())) {
            searchIndexService.syncPostStrict(postId);
        }
    }

    private boolean isSearchSyncEvent(String eventType) {
        return EVENT_POST_PUBLISHED.equals(eventType)
                || EVENT_POST_VISIBILITY_CHANGED.equals(eventType)
                || EVENT_POST_TOP_CHANGED.equals(eventType);
    }

    private int normalizedOutboxMaxRetryAttempts() {
        return Math.max(outboxMaxRetryAttempts, 1);
    }

    private void enqueuePostSyncEvent(long postId, String eventType, Instant occurredAt) {
        String eventId = String.valueOf(snowflakeIdGenerator.nextId());
        String postIdStr = String.valueOf(postId);
        knowPostMapper.insertOutbox(new OutboxEventEntity(
                eventId, "post", postIdStr, eventType,
                toJson(new PostSyncPayload(eventId, eventType, postIdStr, occurredAt)),
                "pending", 0, occurredAt
        ));
    }

    private void reconcileDiscoverState(String postIdStr) {
        KnowPostEntity entity = knowPostMapper.findById(postIdStr);
        if (entity == null || STATUS_DELETED.equals(entity.status())) {
            removeFromDiscoverStrict(postIdStr);
            return;
        }
        if (!STATUS_PUBLISHED.equals(entity.status())
                || !DEFAULT_VISIBILITY.equals(entity.visible())
                || !hasLocation(entity.latitude(), entity.longitude())) {
            removeFromDiscoverStrict(postIdStr);
            return;
        }
        syncDiscoverIndexStrict(postIdStr, entity.title(), entity.latitude(), entity.longitude(),
                entity.visible(), entity.publishTime());
    }

    private void syncDiscoverIndex(long postId, String title, Double latitude,
                                   Double longitude, String visibility, Instant publishTime) {
        try {
            syncDiscoverIndexStrict(String.valueOf(postId), title, latitude, longitude, visibility, publishTime);
        } catch (Exception ex) {
            log.warn("Failed to sync post {} to discover index: {}", postId, ex.getMessage());
        }
    }

    private void removeFromDiscover(long postId) {
        try {
            removeFromDiscoverStrict(String.valueOf(postId));
        } catch (Exception ex) {
            log.warn("Failed to remove post {} from discover index: {}", postId, ex.getMessage());
        }
    }

    private void syncSearchIndex(long postId) {
        if (searchIndexService == null || !searchIndexService.isLocalSyncEnabled()) {
            return;
        }
        try {
            searchIndexService.syncPost(postId);
        } catch (Exception ex) {
            log.warn("Failed to sync post {} to search index: {}", postId, ex.getMessage());
        }
    }

    private void removeFromSearchIndex(long postId) {
        if (searchIndexService == null || !searchIndexService.isLocalSyncEnabled()) {
            return;
        }
        try {
            searchIndexService.deletePost(postId);
        } catch (Exception ex) {
            log.warn("Failed to remove post {} from search index: {}", postId, ex.getMessage());
        }
    }

    private void syncDiscoverIndexStrict(String postIdStr, String title, Double latitude,
                                         Double longitude, String visibility, Instant publishTime) {
        if (!hasLocation(latitude, longitude) || !DEFAULT_VISIBILITY.equals(visibility)) {
            removeFromDiscoverStrict(postIdStr);
            return;
        }
        KnowPostDetailRow detailRow = knowPostMapper.findDetailById(postIdStr);
        if (detailRow == null || !STATUS_PUBLISHED.equals(detailRow.status())) {
            removeFromDiscoverStrict(postIdStr);
            return;
        }
        List<String> imageUrls = parseStringList(detailRow.imgUrlsJson());
        InteractionSummary discoverSummary = loadDiscoverInteractionSummary(postIdStr);
        lbsDiscoverService.addLocation(
                postIdStr, DISCOVER_TYPE, latitude, longitude,
                detailRow.title(), detailRow.description(),
                imageUrls.isEmpty() ? null : imageUrls.get(0),
                detailRow.address(),
                detailRow.creatorId(), detailRow.authorNickname(), detailRow.authorAvatar(),
                detailRow.tagsJson(),
                publishTime == null ? null : publishTime.toEpochMilli(),
                discoverSummary == null ? 0 : safeToInt(discoverSummary.getLikeCount()),
                discoverSummary == null ? 0 : safeToInt(discoverSummary.getFavoriteCount())
        );
    }

    private void removeFromDiscoverStrict(String postIdStr) {
        lbsDiscoverService.removeLocation(postIdStr, DISCOVER_TYPE);
    }

    private PostPageData toPageData(List<KnowPostFeedRow> rows, int page, int size, Long viewerId) {
        boolean hasMore = rows.size() > size;
        List<KnowPostFeedRow> pageRows = hasMore ? rows.subList(0, size) : rows;
        long viewerUserId = viewerId == null ? 0L : viewerId;
        Map<String, InteractionSummary> summaryMap = loadPageInteractionSummaries(pageRows, viewerUserId);
        Map<String, PostAuthor> authorMap = loadAuthors(pageRows, viewerUserId);
        List<PostCard> items = new ArrayList<>(pageRows.size());
        for (KnowPostFeedRow row : pageRows) {
            List<String> imageUrls = parseStringList(row.imgUrlsJson());
            InteractionSummary summary = summaryMap.get(row.postId());
            PostAuthor author = authorMap.get(row.creatorId());
            if (author == null) {
                author = buildAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar(), viewerUserId);
            }
            items.add(new PostCard(
                    row.postId(), row.title(), row.description(),
                    imageUrls.isEmpty() ? null : imageUrls.get(0),
                    parseStringList(row.tagsJson()), author,
                    summary == null ? 0L : summary.getLikeCount(),
                    summary == null ? 0L : summary.getFavoriteCount(),
                    viewerUserId > 0L && summary != null ? summary.isViewerLiked() : null,
                    viewerUserId > 0L && summary != null ? summary.isViewerFavorited() : null,
                    row.visibility(), row.isTop(), row.publishTime()
            ));
        }
        return new PostPageData(items, page, size, hasMore);
    }

    private InteractionSummary loadDetailInteractionSummary(KnowPostDetailRow row, long viewerUserId) {
        if (!STATUS_PUBLISHED.equals(row.status())) {
            return null;
        }
        long postId = Numbers.toLongOrZero(row.postId());
        if (postId <= 0L) {
            return null;
        }
        return interactionService.summary(viewerUserId, "post", postId);
    }

    private Map<String, InteractionSummary> loadPageInteractionSummaries(
            List<KnowPostFeedRow> rows, long viewerUserId) {
        Map<String, InteractionSummary> summaryMap = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return summaryMap;
        }
        List<Long> targetIds = new ArrayList<>(rows.size());
        for (KnowPostFeedRow row : rows) {
            long postId = Numbers.toLongOrZero(row.postId());
            if (postId > 0L) {
                targetIds.add(postId);
            }
        }
        if (targetIds.isEmpty()) {
            return summaryMap;
        }
        return interactionService.summaryBatch(viewerUserId, "post", targetIds);
    }

    private InteractionSummary loadDiscoverInteractionSummary(String postIdStr) {
        long targetId = Numbers.toLongOrZero(postIdStr);
        if (targetId <= 0L) {
            return null;
        }
        return interactionService.summary(0L, "post", targetId);
    }

    private PostAuthor buildAuthor(String creatorId, String nickname, String avatar, long viewerUserId) {
        long creatorUserId = Numbers.toLongOrZero(creatorId);
        UserSocialCounterData socialCounters = creatorUserId > 0L
                ? userSocialCounterService.getUserSocialCounter(creatorUserId)
                : null;
        RelationStatusData relationStatus = resolveRelationStatus(viewerUserId, creatorUserId);
        return new PostAuthor(creatorId, nickname, avatar, socialCounters, relationStatus);
    }

    private Map<String, PostAuthor> loadAuthors(List<KnowPostFeedRow> rows, long viewerUserId) {
        Map<String, PostAuthor> authorMap = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return authorMap;
        }
        for (KnowPostFeedRow row : rows) {
            if (row == null || !hasText(row.creatorId()) || authorMap.containsKey(row.creatorId())) {
                continue;
            }
            authorMap.put(row.creatorId(),
                    buildAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar(), viewerUserId));
        }
        return authorMap;
    }

    private RelationStatusData resolveRelationStatus(long viewerUserId, long creatorUserId) {
        if (creatorUserId <= 0L || viewerUserId <= 0L) {
            return new RelationStatusData(false, false, false);
        }
        return followService.relationStatus(viewerUserId, creatorUserId);
    }

    private void incrementPublishedPostCounter(long creatorId, int delta) {
        if (creatorId <= 0L || delta == 0) {
            return;
        }
        userSocialCounterService.incrementPosts(creatorId, delta);
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizePageSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }

    private int safeToInt(long value) {
        if (value <= 0L) return 0;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private boolean hasLocation(Double latitude, Double longitude) {
        return latitude != null && longitude != null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "内容模块 JSON 序列化失败");
        }
    }

    private List<String> parseStringList(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String normalizeNullableText(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String abbreviateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "unknown";
        }
        return errorMessage.length() > 512 ? errorMessage.substring(0, 512) : errorMessage;
    }
}
