package com.zhiguang.be.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内容模块核心服务。
 * 参考 zhiguang 的 knowpost 主链路实现，保留 linli 当前这版的最小闭环：
 * 草稿 -> 预签名 -> 确认正文 -> 更新元数据 -> 发布 -> 同步 Discover -> 详情/feed/mine。
 */
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

    /**
     * 注入内容模块依赖。
     */
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

    /**
     * 创建内容草稿。
     * 参考 zhiguang，草稿阶段直接固定为 image_text。
     */
    @Transactional
    public DraftData createDraft(String creatorId) {
        Instant now = Instant.now();
        String postId = String.valueOf(snowflakeIdGenerator.nextId());
        knowPostMapper.insert(new KnowPostEntity(
                postId,
                creatorId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Boolean.FALSE,
                DEFAULT_TYPE,
                DEFAULT_VISIBILITY,
                null,
                null,
                STATUS_DRAFT,
                now,
                now,
                null
        ));
        return new DraftData(postId, STATUS_DRAFT, now);
    }

    /**
     * 查询公开内容流。
     * 当前阶段先对齐 zhiguang 的返回结构，互动字段暂以默认值占位。
     */
    public PostPageData getPublicFeed(String viewerId, int page, int size) {
        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedPublic(safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize, viewerId);
    }

    /**
     * 查询当前用户已发布内容。
     */
    @Override
    public PostPageData getMyPublished(String creatorId, int page, int size) {
        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        List<KnowPostFeedRow> rows = knowPostMapper.listMyPublished(creatorId, safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize, creatorId);
    }

    /**
     * 查询指定用户主页可见的已发布内容。
     * 个人主页场景下：本人可见自己的全部已发布内容；关注者可见 followers 内容；其他人仅可见 public。
     */
    @Override
    public PostPageData getUserPublished(String creatorId, String viewerId, int page, int size) {
        if (hasText(viewerId) && creatorId.equals(viewerId)) {
            return getMyPublished(creatorId, page, size);
        }

        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        boolean includeFollowers = hasText(viewerId)
                && followService.isFollowing(Long.parseLong(viewerId), Long.parseLong(creatorId));
        List<KnowPostFeedRow> rows = knowPostMapper.listUserPublished(creatorId, includeFollowers, safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize, viewerId);
    }

    /**
     * 创建预签名上传地址。
     * 当前阶段只区分正文与图片，封面统一走图片目录。
     */

    /**
     * 确认正文上传成功。
     */
    @Transactional
    public void confirmContent(String creatorId, String postId, ConfirmContentRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertMutable(entity);
        if (!request.objectKey().startsWith("posts/" + postId + "/content/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "objectKey 与当前文章不匹配");
        }

        String nextStatus = hasText(entity.title()) ? STATUS_METADATA_COMPLETED : STATUS_CONTENT_CONFIRMED;
        int updated = knowPostMapper.updateContent(
                postId,
                creatorId,
                nextStatus,
                storageService.toPublicUrl(request.objectKey()),
                request.objectKey(),
                request.etag(),
                request.size(),
                request.sha256(),
                Instant.now()
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "正文确认失败，请刷新后重试");
        }
    }

    /**
     * 更新文章元数据。
     */
    @Transactional
    public PostDetail updateMetadata(String creatorId, String postId, UpdatePostMetadataRequest request) {
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
                String normalizedImageUrl = storageService.normalizeOwnedPostImageUrl(postId, rawImageUrl);
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
                postId,
                creatorId,
                nextStatus,
                title,
                summary,
                tagsJson,
                imgUrlsJson,
                isTop,
                visibility,
                latitude,
                longitude,
                geoHash,
                address,
                Instant.now()
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章元数据更新失败，请刷新后重试");
        }
        return getDetail(postId, creatorId);
    }

    /**
     * 发布文章。
     */
    @Transactional
    public PostDetail publish(String creatorId, String postId) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
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

        int updated = knowPostMapper.publish(postId, creatorId, visibility, STATUS_PUBLISHED, publishTime, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章发布失败，请刷新后重试");
        }

        incrementPublishedPostCounter(creatorId, 1);
        enqueuePostSyncEvent(postId, EVENT_POST_PUBLISHED, now);
        syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), visibility, publishTime);
        syncSearchIndex(postId);
        feedCacheInvalidationService.invalidatePostAfterCommit(postId);
        return getDetail(postId, creatorId);
    }

    /**
     * 更新文章置顶状态。
     */
    @Transactional
    public PostDetail updateTop(String creatorId, String postId, boolean isTop) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertPublished(entity);

        int updated = knowPostMapper.updateTop(postId, creatorId, isTop, Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章置顶状态更新失败，请刷新后重试");
        }
        syncSearchIndex(postId);
        feedCacheInvalidationService.invalidatePostAfterCommit(postId);
        return getDetail(postId, creatorId);
    }

    /**
     * 更新文章可见性。
     */
    @Transactional
    public PostDetail updateVisibility(String creatorId, String postId, String visibility) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertPublished(entity);

        String normalizedVisibility = visibility.trim();
        Instant now = Instant.now();
        int updated = knowPostMapper.updateVisibility(postId, creatorId, normalizedVisibility, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章可见性更新失败，请刷新后重试");
        }

        enqueuePostSyncEvent(postId, EVENT_POST_VISIBILITY_CHANGED, now);
        syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), normalizedVisibility, entity.publishTime());
        syncSearchIndex(postId);
        feedCacheInvalidationService.invalidatePostAfterCommit(postId);
        return getDetail(postId, creatorId);
    }

    /**
     * 删除文章。
     */
    @Transactional
    public void delete(String creatorId, String postId) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }

        Instant now = Instant.now();
        int updated = knowPostMapper.softDelete(postId, creatorId, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章删除失败，请刷新后重试");
        }

        if (STATUS_PUBLISHED.equals(entity.status())) {
            incrementPublishedPostCounter(creatorId, -1);
        }
        enqueuePostSyncEvent(postId, EVENT_POST_DELETED, now);
        removeFromDiscover(postId);
        removeFromSearchIndex(postId);
        feedCacheInvalidationService.invalidatePostAfterCommit(postId);
    }

    /**
     * 查询文章详情。
     * 当前阶段对齐 zhiguang 的公开访问策略，并额外让 followers 可见性真正生效。
     */
    public PostDetail getDetail(String postId, String viewerId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
        if (row == null || STATUS_DELETED.equals(row.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }

        boolean isOwner = Objects.equals(row.creatorId(), viewerId);
        long viewerUserId = parseOptionalUserId(viewerId);
        long creatorUserId = parseOptionalUserId(row.creatorId());
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
     * 定时补偿 discover 同步事件。
     */
    @Scheduled(fixedDelayString = "${content.outbox-reconcile-delay-ms:10000}")
    public void reconcileDiscoverOutbox() {
        List<OutboxEventEntity> events = knowPostMapper.listPendingOutbox(20);
        for (OutboxEventEntity event : events) {
            try {
                reconcileDiscoverState(event.aggregateId());
                knowPostMapper.markOutboxPublished(event.id(), Instant.now());
            } catch (Exception ex) {
                knowPostMapper.markOutboxFailed(event.id(), abbreviateError(ex.getMessage()));
                log.warn("Failed to reconcile outbox event {} for post {}: {}", event.id(), event.aggregateId(), ex.getMessage());
            }
        }
    }

    /**
     * 查询并校验文章归属。
     */
    private KnowPostEntity loadOwnedPost(String postId, String creatorId) {
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (!Objects.equals(entity.creatorId(), creatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "无权操作该文章");
        }
        return entity;
    }

    /**
     * 校验文章是否仍可编辑。
     */
    private void assertMutable(KnowPostEntity entity) {
        if (STATUS_PUBLISHED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章已发布，当前阶段不支持再次编辑");
        }
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
    }

    /**
     * 校验文章是否已发布。
     */
    private void assertPublished(KnowPostEntity entity) {
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (!STATUS_PUBLISHED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "仅已发布文章支持当前操作");
        }
    }

    /**
     * 写入一条 post outbox 事件。
     */
    private void enqueuePostSyncEvent(String postId, String eventType, Instant occurredAt) {
        String eventId = String.valueOf(snowflakeIdGenerator.nextId());
        knowPostMapper.insertOutbox(new OutboxEventEntity(
                eventId,
                "post",
                postId,
                eventType,
                toJson(new PostSyncPayload(eventId, eventType, postId, occurredAt)),
                "pending",
                0,
                occurredAt
        ));
    }

    /**
     * 根据文章当前状态决定 discover 中是否应存在该内容。
     */
    private void reconcileDiscoverState(String postId) {
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null || STATUS_DELETED.equals(entity.status())) {
            removeFromDiscoverStrict(postId);
            return;
        }
        if (!STATUS_PUBLISHED.equals(entity.status())
                || !DEFAULT_VISIBILITY.equals(entity.visible())
                || !hasLocation(entity.latitude(), entity.longitude())) {
            removeFromDiscoverStrict(postId);
            return;
        }
        syncDiscoverIndexStrict(
                postId,
                entity.title(),
                entity.latitude(),
                entity.longitude(),
                entity.visible(),
                entity.publishTime()
        );
    }

    /**
     * 同步 discover 索引，失败时不阻塞主流程。
     */
    private void syncDiscoverIndex(
            String postId,
            String title,
            Double latitude,
            Double longitude,
            String visibility,
            Instant publishTime
    ) {
        try {
            syncDiscoverIndexStrict(postId, title, latitude, longitude, visibility, publishTime);
        } catch (Exception ex) {
            log.warn("Failed to sync post {} to discover index: {}", postId, ex.getMessage());
        }
    }

    /**
     * 从 discover 中移除文章，失败时不阻塞主流程。
     */
    private void removeFromDiscover(String postId) {
        try {
            removeFromDiscoverStrict(postId);
        } catch (Exception ex) {
            log.warn("Failed to remove post {} from discover index: {}", postId, ex.getMessage());
        }
    }

    /**
     * 同步搜索索引，失败时不阻断内容主流程。
     */
    private void syncSearchIndex(String postId) {
        if (searchIndexService == null || !searchIndexService.isLocalSyncEnabled()) {
            return;
        }
        try {
            searchIndexService.syncPost(parseOptionalPostId(postId));
        } catch (Exception ex) {
            log.warn("Failed to sync post {} to search index: {}", postId, ex.getMessage());
        }
    }

    /**
     * 从搜索索引中移除内容，失败时不阻断主流程。
     */
    private void removeFromSearchIndex(String postId) {
        if (searchIndexService == null || !searchIndexService.isLocalSyncEnabled()) {
            return;
        }
        try {
            searchIndexService.deletePost(parseOptionalPostId(postId));
        } catch (Exception ex) {
            log.warn("Failed to remove post {} from search index: {}", postId, ex.getMessage());
        }
    }

    /**
     * 严格同步 discover 索引。
     */
    private void syncDiscoverIndexStrict(
            String postId,
            String title,
            Double latitude,
            Double longitude,
            String visibility,
            Instant publishTime
    ) {
        if (!hasLocation(latitude, longitude) || !DEFAULT_VISIBILITY.equals(visibility)) {
            removeFromDiscoverStrict(postId);
            return;
        }
        KnowPostDetailRow detailRow = knowPostMapper.findDetailById(postId);
        if (detailRow == null || !STATUS_PUBLISHED.equals(detailRow.status())) {
            removeFromDiscoverStrict(postId);
            return;
        }
        List<String> imageUrls = parseStringList(detailRow.imgUrlsJson());
        InteractionSummary discoverSummary = loadDiscoverInteractionSummary(postId);
        lbsDiscoverService.addLocation(
                postId,
                DISCOVER_TYPE,
                latitude,
                longitude,
                detailRow.title(),
                detailRow.description(),
                imageUrls.isEmpty() ? null : imageUrls.get(0),
                detailRow.address(),
                detailRow.creatorId(),
                detailRow.authorNickname(),
                detailRow.authorAvatar(),
                detailRow.tagsJson(),
                publishTime == null ? null : publishTime.toEpochMilli(),
                discoverSummary == null ? 0 : safeToInt(discoverSummary.getLikeCount()),
                discoverSummary == null ? 0 : safeToInt(discoverSummary.getFavoriteCount())
        );
    }

    /**
     * 严格从 discover 中移除文章。
     */
    private void removeFromDiscoverStrict(String postId) {
        lbsDiscoverService.removeLocation(postId, DISCOVER_TYPE);
    }

    /**
     * 将 feed 行对象转换成分页卡片。
     * 当前阶段先把互动字段补齐到更像 zhiguang 的结构，后续再接真实互动模块。
     */
    private PostPageData toPageData(List<KnowPostFeedRow> rows, int page, int size, String viewerId) {
        boolean hasMore = rows.size() > size;
        List<KnowPostFeedRow> pageRows = hasMore ? rows.subList(0, size) : rows;
        long viewerUserId = parseOptionalUserId(viewerId);
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
                    row.postId(),
                    row.title(),
                    row.description(),
                    imageUrls.isEmpty() ? null : imageUrls.get(0),
                    parseStringList(row.tagsJson()),
                    author,
                    summary == null ? 0L : summary.getLikeCount(),
                    summary == null ? 0L : summary.getFavoriteCount(),
                    viewerUserId > 0L && summary != null ? summary.isViewerLiked() : null,
                    viewerUserId > 0L && summary != null ? summary.isViewerFavorited() : null,
                    row.visibility(),
                    row.isTop(),
                    row.publishTime()
            ));
        }
        return new PostPageData(items, page, size, hasMore);
    }

    /**
     * 为详情页加载互动汇总。
     * 只有已发布内容才接入社交互动，避免草稿态误走互动校验。
     */
    private InteractionSummary loadDetailInteractionSummary(KnowPostDetailRow row, long viewerUserId) {
        if (!STATUS_PUBLISHED.equals(row.status())) {
            return null;
        }

        long postId = parseOptionalUserId(row.postId());
        if (postId <= 0L) {
            return null;
        }
        return interactionService.summary(viewerUserId, "post", postId);
    }

    /**
     * 批量加载列表页互动汇总。
     * 内容卡片只补互动状态，不把用户态写回公共缓存。
     */
    private Map<String, InteractionSummary> loadPageInteractionSummaries(List<KnowPostFeedRow> rows, long viewerUserId) {
        Map<String, InteractionSummary> summaryMap = new LinkedHashMap<String, InteractionSummary>();
        if (rows == null || rows.isEmpty()) {
            return summaryMap;
        }

        List<Long> targetIds = new ArrayList<Long>(rows.size());
        for (KnowPostFeedRow row : rows) {
            long postId = parseOptionalUserId(row.postId());
            if (postId > 0L) {
                targetIds.add(postId);
            }
        }
        if (targetIds.isEmpty()) {
            return summaryMap;
        }
        return interactionService.summaryBatch(viewerUserId, "post", targetIds);
    }

    /**
     * 为 discover 同步加载互动汇总。
     * discover 当前只收公开已发布内容，因此这里统一使用匿名视角读取聚合计数。
     *
     * @param postId 内容 ID
     * @return discover 需要的互动汇总
     */
    private InteractionSummary loadDiscoverInteractionSummary(String postId) {
        long targetId = parseOptionalUserId(postId);
        if (targetId <= 0L) {
            return null;
        }
        return interactionService.summary(0L, "post", targetId);
    }

    /**
     * 构建详情页作者信息。
     *
     * @param creatorId 作者 ID
     * @param nickname 作者昵称
     * @param avatar 作者头像
     * @param viewerUserId 当前查看者 ID
     * @return 作者信息
     */
    private PostAuthor buildAuthor(String creatorId, String nickname, String avatar, long viewerUserId) {
        long creatorUserId = parseOptionalUserId(creatorId);
        UserSocialCounterData socialCounters = creatorUserId > 0L
                ? userSocialCounterService.getUserSocialCounter(creatorUserId)
                : null;
        RelationStatusData relationStatus = resolveRelationStatus(viewerUserId, creatorUserId);
        return new PostAuthor(creatorId, nickname, avatar, socialCounters, relationStatus);
    }

    /**
     * 批量装配列表中的作者信息，避免同一作者重复查询。
     *
     * @param rows 当前页面的内容行
     * @param viewerUserId 当前查看者 ID
     * @return 以作者 ID 为键的作者信息映射
     */
    private Map<String, PostAuthor> loadAuthors(List<KnowPostFeedRow> rows, long viewerUserId) {
        Map<String, PostAuthor> authorMap = new LinkedHashMap<String, PostAuthor>();
        if (rows == null || rows.isEmpty()) {
            return authorMap;
        }

        for (KnowPostFeedRow row : rows) {
            if (row == null || !hasText(row.creatorId()) || authorMap.containsKey(row.creatorId())) {
                continue;
            }
            authorMap.put(
                    row.creatorId(),
                    buildAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar(), viewerUserId)
            );
        }
        return authorMap;
    }

    /**
     * 解析当前查看者与作者之间的关系态。
     *
     * @param viewerUserId 当前查看者 ID
     * @param creatorUserId 作者用户 ID
     * @return 关系态结果
     */
    private RelationStatusData resolveRelationStatus(long viewerUserId, long creatorUserId) {
        if (creatorUserId <= 0L) {
            return new RelationStatusData(false, false, false);
        }
        if (viewerUserId <= 0L) {
            return new RelationStatusData(false, false, false);
        }
        return followService.relationStatus(viewerUserId, creatorUserId);
    }

    /**
     * 同步作者已发布内容数。
     * 这里对齐 zhiguang 的做法，发布和删除时直接维护用户维 posts 槽位。
     *
     * @param creatorId 作者用户 ID
     * @param delta 计数变化量
     */
    private void incrementPublishedPostCounter(String creatorId, int delta) {
        long creatorUserId = parseOptionalUserId(creatorId);
        if (creatorUserId <= 0L || delta == 0) {
            return;
        }
        userSocialCounterService.incrementPosts(creatorUserId, delta);
    }

    /**
     * 规范化页码。
     */
    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    /**
     * 规范化分页大小。
     */
    private int normalizePageSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }

    /**
     * 解析可选内容 ID。
     */
    private Long parseOptionalPostId(String rawPostId) {
        if (!hasText(rawPostId)) {
            return null;
        }
        try {
            return Long.valueOf(rawPostId.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 解析可选用户 ID。
     * 对匿名态或异常值统一回退为 0，避免影响公开读链路。
     */
    private long parseOptionalUserId(String rawUserId) {
        if (!hasText(rawUserId)) {
            return 0L;
        }
        try {
            return Long.parseLong(rawUserId.trim());
        } catch (Exception ex) {
            return 0L;
        }
    }

    /**
     * 将 long 计数安全收缩为 int。
     *
     * @param value long 计数值
     * @return 安全转换后的 int 值
     */
    private int safeToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * 判断是否携带经纬度。
     */
    private boolean hasLocation(Double latitude, Double longitude) {
        return latitude != null && longitude != null;
    }

    /**
     * 序列化 JSON。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "内容模块 JSON 序列化失败");
        }
    }

    /**
     * 解析字符串数组 JSON。
     */
    private List<String> parseStringList(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    /**
     * 判断字符串是否有内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 校验图片 URL 是否属于当前文章。
     */
    private String normalizeOwnedImageUrl(String postId, String rawValue) {
        String normalized = normalizeNullableText(rawValue);
        if (normalized == null) {
            return null;
        }
        String objectKeyPrefix = "posts/" + postId + "/images/";
        if (normalized.startsWith(objectKeyPrefix)) {
            return buildPublicUrl(normalized);
        }
        String publicPrefix = buildPublicUrl(objectKeyPrefix);
        if (normalized.startsWith(publicPrefix)) {
            return normalized;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "图片资源与当前文章不匹配");
    }

    /**
     * 构建公开访问地址。
     */
    private String buildPublicUrl(String objectKey) {
        return storageService.toPublicUrl(objectKey);
    }

    /**
     * 去掉 baseUrl 末尾多余斜杠。
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 规范化可空文本。
     */
    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 截断过长错误信息，避免写入 outbox 时超长。
     */
    private String abbreviateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "unknown";
        }
        return errorMessage.length() > 512 ? errorMessage.substring(0, 512) : errorMessage;
    }
}
