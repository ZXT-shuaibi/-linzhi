package com.zhiguang.be.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.content.ContentModels.ConfirmContentData;
import com.zhiguang.be.content.ContentModels.ConfirmContentRequest;
import com.zhiguang.be.content.ContentModels.CreateDraftRequest;
import com.zhiguang.be.content.ContentModels.DraftData;
import com.zhiguang.be.content.ContentModels.KnowPostDetailRow;
import com.zhiguang.be.content.ContentModels.KnowPostEntity;
import com.zhiguang.be.content.ContentModels.KnowPostFeedRow;
import com.zhiguang.be.content.ContentModels.OutboxEventEntity;
import com.zhiguang.be.content.ContentModels.PostAuthor;
import com.zhiguang.be.content.ContentModels.PostCard;
import com.zhiguang.be.content.ContentModels.PostDetail;
import com.zhiguang.be.content.ContentModels.PostLocation;
import com.zhiguang.be.content.ContentModels.PostPageData;
import com.zhiguang.be.content.ContentModels.PostSyncPayload;
import com.zhiguang.be.content.ContentModels.PublishPostRequest;
import com.zhiguang.be.content.ContentModels.StoragePresignData;
import com.zhiguang.be.content.ContentModels.StoragePresignRequest;
import com.zhiguang.be.content.ContentModels.UpdatePostMetadataRequest;
import com.zhiguang.be.content.ContentModels.UpdateTopRequest;
import com.zhiguang.be.content.ContentModels.UpdateVisibilityRequest;
import com.zhiguang.be.content.mapper.JdbcKnowPostMapper;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ContentServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(ContentServiceImpl.class);

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_CONTENT_CONFIRMED = "content_confirmed";
    private static final String STATUS_METADATA_COMPLETED = "metadata_completed";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_DELETED = "deleted";

    private static final String DEFAULT_TYPE = "image_text";
    private static final String DEFAULT_VISIBILITY = "public";

    private static final String POST_PUBLISHED = "POST_PUBLISHED";
    private static final String POST_VISIBILITY_CHANGED = "POST_VISIBILITY_CHANGED";
    private static final String POST_DELETED = "POST_DELETED";

    private static final String DISCOVER_TYPE = "knowledge";

    private final JdbcKnowPostMapper knowPostMapper;
    private final LbsDiscoverService lbsDiscoverService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;
    private final String mockUploadBaseUrl;
    private final String publicBaseUrl;
    private final long presignExpireSeconds;

    public ContentServiceImpl(
            JdbcKnowPostMapper knowPostMapper,
            LbsDiscoverService lbsDiscoverService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ObjectMapper objectMapper,
            @Value("${storage.mock-upload-base-url:https://mock-oss.local/upload}") String mockUploadBaseUrl,
            @Value("${storage.public-base-url:https://mock-oss.local/public}") String publicBaseUrl,
            @Value("${storage.presign-expire-seconds:600}") long presignExpireSeconds
    ) {
        this.knowPostMapper = knowPostMapper;
        this.lbsDiscoverService = lbsDiscoverService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.objectMapper = objectMapper;
        this.mockUploadBaseUrl = mockUploadBaseUrl;
        this.publicBaseUrl = publicBaseUrl;
        this.presignExpireSeconds = presignExpireSeconds;
    }

    @Transactional
    public DraftData createDraft(String creatorId, CreateDraftRequest request) {
        Instant now = Instant.now();
        String postId = String.valueOf(snowflakeIdGenerator.nextId());
        knowPostMapper.insert(new KnowPostEntity(
                postId, creatorId, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, Boolean.FALSE, DEFAULT_TYPE, DEFAULT_VISIBILITY,
                null, null, STATUS_DRAFT, now, now, null
        ));
        return new DraftData(postId, STATUS_DRAFT, now);
    }

    public PostPageData getPublicFeed(int page, int size) {
        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        List<KnowPostFeedRow> rows = knowPostMapper.listFeedPublic(safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize);
    }

    public PostPageData getMyPublished(String creatorId, int page, int size) {
        int safeSize = normalizePageSize(size);
        int safePage = normalizePage(page);
        int offset = (safePage - 1) * safeSize;
        List<KnowPostFeedRow> rows = knowPostMapper.listMyPublished(creatorId, safeSize + 1, offset);
        return toPageData(rows, safePage, safeSize);
    }

    public StoragePresignData createPresign(String creatorId, StoragePresignRequest request) {
        KnowPostEntity entity = loadOwnedPost(request.postId(), creatorId);
        assertMutable(entity);

        Instant expireAt = Instant.now().plusSeconds(presignExpireSeconds);
        String safeFilename = request.filename()
                .replace("\\", "-")
                .replace("/", "-")
                .trim()
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        String folder = "content".equals(request.purpose()) ? "content" : "images";
        String objectKey = "posts/" + request.postId() + "/" + folder + "/"
                + UUID.randomUUID().toString().replace("-", "") + "-" + safeFilename;
        String uploadUrl = normalizeBaseUrl(mockUploadBaseUrl) + "/"
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
        return new StoragePresignData(uploadUrl, objectKey, expireAt);
    }

    @Transactional
    public ConfirmContentData confirmContent(String creatorId, String postId, ConfirmContentRequest request) {
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
                buildPublicUrl(request.objectKey()),
                request.objectKey(),
                request.etag(),
                request.size(),
                request.sha256(),
                Instant.now()
        );
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "正文确认失败，请刷新后重试");
        }
        return new ConfirmContentData(postId, nextStatus, request.objectKey());
    }

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
                String normalizedImageUrl = normalizeOwnedImageUrl(postId, rawImageUrl);
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

    @Transactional
    public PostDetail publish(String creatorId, String postId, PublishPostRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertMutable(entity);
        if (!hasText(entity.contentUrl())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请先确认正文上传");
        }
        if (!hasText(entity.title())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请先补全标题后再发布");
        }

        Instant now = Instant.now();
        Instant publishTime = request.publishAt() == null ? now : request.publishAt();
        String visibility = request.visibility() == null || request.visibility().isBlank()
                ? (hasText(entity.visible()) ? entity.visible() : DEFAULT_VISIBILITY)
                : request.visibility().trim();

        int updated = knowPostMapper.publish(postId, creatorId, visibility, STATUS_PUBLISHED, publishTime, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章发布失败，请刷新后重试");
        }

        enqueuePostSyncEvent(postId, POST_PUBLISHED, now);
        syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), visibility, publishTime);
        return getDetail(postId, creatorId);
    }

    @Transactional
    public PostDetail updateTop(String creatorId, String postId, UpdateTopRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertPublished(entity);
        int updated = knowPostMapper.updateTop(postId, creatorId, request.isTop(), Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章置顶状态更新失败，请刷新后重试");
        }
        return getDetail(postId, creatorId);
    }

    @Transactional
    public PostDetail updateVisibility(String creatorId, String postId, UpdateVisibilityRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        assertPublished(entity);

        String visibility = request.visibility().trim();
        Instant now = Instant.now();
        int updated = knowPostMapper.updateVisibility(postId, creatorId, visibility, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章可见性更新失败，请刷新后重试");
        }

        enqueuePostSyncEvent(postId, POST_VISIBILITY_CHANGED, now);
        syncDiscoverIndex(postId, entity.title(), entity.latitude(), entity.longitude(), visibility, entity.publishTime());
        return getDetail(postId, creatorId);
    }

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

        enqueuePostSyncEvent(postId, POST_DELETED, now);
        removeFromDiscover(postId);
    }

    public PostDetail getDetail(String postId, String viewerId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在"));
        if (STATUS_DELETED.equals(row.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }

        boolean isOwner = Objects.equals(row.creatorId(), viewerId);
        boolean isPublicPublished = STATUS_PUBLISHED.equals(row.status()) && DEFAULT_VISIBILITY.equals(row.visible());
        if (!isPublicPublished && !isOwner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "当前文章暂无访问权限");
        }

        List<String> imageUrls = parseStringList(row.imgUrlsJson());
        return new PostDetail(
                row.postId(),
                new PostAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar()),
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
                row.publishTime(),
                row.createdAt(),
                row.updatedAt()
        );
    }

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

    private KnowPostEntity loadOwnedPost(String postId, String creatorId) {
        KnowPostEntity entity = knowPostMapper.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在"));
        if (!Objects.equals(entity.creatorId(), creatorId)) {
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

    private void reconcileDiscoverState(String postId) {
        KnowPostEntity entity = knowPostMapper.findById(postId).orElse(null);
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

    private void removeFromDiscover(String postId) {
        try {
            removeFromDiscoverStrict(postId);
        } catch (Exception ex) {
            log.warn("Failed to remove post {} from discover index: {}", postId, ex.getMessage());
        }
    }

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
        lbsDiscoverService.addLocation(
                postId,
                DISCOVER_TYPE,
                latitude,
                longitude,
                title,
                publishTime == null ? null : publishTime.toEpochMilli(),
                0
        );
    }

    private void removeFromDiscoverStrict(String postId) {
        lbsDiscoverService.removeLocation(postId, DISCOVER_TYPE);
    }

    private PostPageData toPageData(List<KnowPostFeedRow> rows, int page, int size) {
        boolean hasMore = rows.size() > size;
        List<KnowPostFeedRow> pageRows = hasMore ? rows.subList(0, size) : rows;
        List<PostCard> items = new ArrayList<>(pageRows.size());
        for (KnowPostFeedRow row : pageRows) {
            List<String> imageUrls = parseStringList(row.imgUrlsJson());
            items.add(new PostCard(
                    row.postId(),
                    row.title(),
                    row.description(),
                    imageUrls.isEmpty() ? null : imageUrls.get(0),
                    parseStringList(row.tagsJson()),
                    new PostAuthor(row.creatorId(), row.authorNickname(), row.authorAvatar()),
                    row.visibility(),
                    row.isTop(),
                    row.publishTime()
            ));
        }
        return new PostPageData(items, page, size, hasMore);
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizePageSize(int size) {
        return Math.min(Math.max(size, 1), 50);
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
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

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

    private String buildPublicUrl(String objectKey) {
        return normalizeBaseUrl(publicBaseUrl) + "/" + objectKey;
    }

    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
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
