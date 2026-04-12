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
import com.zhiguang.be.content.ContentModels.OutboxEventEntity;
import com.zhiguang.be.content.ContentModels.PostAuthor;
import com.zhiguang.be.content.ContentModels.PostDetail;
import com.zhiguang.be.content.ContentModels.PostLocation;
import com.zhiguang.be.content.ContentModels.PostPublishedPayload;
import com.zhiguang.be.content.ContentModels.PublishPostRequest;
import com.zhiguang.be.content.ContentModels.StoragePresignData;
import com.zhiguang.be.content.ContentModels.StoragePresignRequest;
import com.zhiguang.be.content.ContentModels.UpdatePostMetadataRequest;
import com.zhiguang.be.content.ContentModels.UpdateTopRequest;
import com.zhiguang.be.content.ContentModels.UpdateVisibilityRequest;
import com.zhiguang.be.content.mapper.JdbcKnowPostMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 内容模块核心服务。
 * 参考 zhiguang 的 knowpost + storage 主链路，收成单个服务实现。
 */
@Service
public class ContentServiceImpl {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_CONTENT_CONFIRMED = "content_confirmed";
    private static final String STATUS_METADATA_COMPLETED = "metadata_completed";
    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_DELETED = "deleted";
    private static final String DEFAULT_TYPE = "image_text";
    private static final String DEFAULT_VISIBILITY = "public";
    private static final String POST_PUBLISHED = "POST_PUBLISHED";

    private final JdbcKnowPostMapper knowPostMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;
    private final String mockUploadBaseUrl;
    private final String publicBaseUrl;
    private final long presignExpireSeconds;

    /**
     * 构造内容服务。
     *
     * @param knowPostMapper 文章持久化实现
     * @param snowflakeIdGenerator 雪花算法 ID 生成器
     * @param objectMapper JSON 序列化组件
     * @param mockUploadBaseUrl mock 上传地址前缀
     * @param publicBaseUrl mock 公网访问地址前缀
     * @param presignExpireSeconds 预签名有效期秒数
     */
    public ContentServiceImpl(
            JdbcKnowPostMapper knowPostMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ObjectMapper objectMapper,
            @Value("${storage.mock-upload-base-url:https://mock-oss.local/upload}") String mockUploadBaseUrl,
            @Value("${storage.public-base-url:https://mock-oss.local/public}") String publicBaseUrl,
            @Value("${storage.presign-expire-seconds:600}") long presignExpireSeconds
    ) {
        this.knowPostMapper = knowPostMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.objectMapper = objectMapper;
        this.mockUploadBaseUrl = mockUploadBaseUrl;
        this.publicBaseUrl = publicBaseUrl;
        this.presignExpireSeconds = presignExpireSeconds;
    }

    /**
     * 创建文章草稿。
     *
     * @param creatorId 作者 ID
     * @param request 草稿请求
     * @return 草稿结果
     */
    @Transactional
    public DraftData createDraft(String creatorId, CreateDraftRequest request) {
        Instant now = Instant.now();
        String postId = String.valueOf(snowflakeIdGenerator.nextId());
        String rawType = request == null ? null : request.contentType();
        String type = rawType == null || rawType.isBlank() ? DEFAULT_TYPE : rawType.trim();

        knowPostMapper.insert(new KnowPostEntity(
                postId, creatorId, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, Boolean.FALSE, type, DEFAULT_VISIBILITY,
                null, null, STATUS_DRAFT, now, now, null
        ));
        return new DraftData(postId, STATUS_DRAFT, now);
    }

    /**
     * 申请 mock 预签名上传地址。
     *
     * @param creatorId 作者 ID
     * @param request 预签名请求
     * @return 预签名结果
     */
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
        String objectKey = "posts/" + request.postId() + "/" + folder + "/" + UUID.randomUUID().toString().replace("-", "") + "-" + safeFilename;
        String uploadUrl = normalizeBaseUrl(mockUploadBaseUrl) + "/" + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
        return new StoragePresignData(uploadUrl, objectKey, expireAt);
    }

    /**
     * 确认正文上传。
     *
     * @param creatorId 作者 ID
     * @param postId 文章 ID
     * @param request 正文确认请求
     * @return 确认结果
     */
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

    /**
     * 更新文章元数据。
     * 这一层同时支持标题、摘要、标签、图片、位置、可见性和置顶状态。
     *
     * @param creatorId 作者 ID
     * @param postId 文章 ID
     * @param request 元数据请求
     * @return 最新文章详情
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
                String normalizedImageUrl = normalizeOwnedImageUrl(postId, rawImageUrl);
                if (normalizedImageUrl != null && !normalizedImageUrls.contains(normalizedImageUrl)) {
                    normalizedImageUrls.add(normalizedImageUrl);
                }
            }
            imgUrlsJson = toJson(normalizedImageUrls);
        } else if (request.coverUrl() != null) {
            String coverUrl = normalizeOwnedImageUrl(postId, request.coverUrl());
            imgUrlsJson = toJson(coverUrl == null ? List.of() : List.of(coverUrl));
        }

        PostLocation location = request.location();
        Double latitude = location == null ? entity.latitude() : location.lat();
        Double longitude = location == null ? entity.longitude() : location.lng();
        String geoHash = location == null ? entity.geoHash() : normalizeNullableText(location.geoHash());
        String address = location == null ? entity.address() : normalizeNullableText(location.address());
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
     * 发布文章并写入 outbox。
     *
     * @param creatorId 作者 ID
     * @param postId 文章 ID
     * @param request 发布请求
     * @return 发布后的文章详情
     */
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

        String eventId = String.valueOf(snowflakeIdGenerator.nextId());
        knowPostMapper.insertOutbox(new OutboxEventEntity(
                eventId,
                "post",
                postId,
                POST_PUBLISHED,
                toJson(new PostPublishedPayload(
                        eventId,
                        POST_PUBLISHED,
                        postId,
                        creatorId,
                        visibility,
                        new PostLocation(entity.latitude(), entity.longitude(), entity.geoHash(), entity.address()),
                        publishTime
                )),
                "pending",
                0,
                now
        ));
        return getDetail(postId, creatorId);
    }

    /**
     * 更新文章置顶状态。
     *
     * @param creatorId 作者 ID
     * @param postId 文章 ID
     * @param request 置顶请求
     * @return 最新文章详情
     */
    @Transactional
    public PostDetail updateTop(String creatorId, String postId, UpdateTopRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (!STATUS_PUBLISHED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "仅已发布文章支持调整置顶");
        }

        int updated = knowPostMapper.updateTop(postId, creatorId, request.isTop(), Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章置顶状态更新失败，请刷新后重试");
        }
        return getDetail(postId, creatorId);
    }

    /**
     * 更新文章可见性。
     *
     * @param creatorId 作者 ID
     * @param postId 文章 ID
     * @param request 可见性请求
     * @return 最新文章详情
     */
    @Transactional
    public PostDetail updateVisibility(String creatorId, String postId, UpdateVisibilityRequest request) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (!STATUS_PUBLISHED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "仅已发布文章支持调整可见性");
        }

        int updated = knowPostMapper.updateVisibility(postId, creatorId, request.visibility().trim(), Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章可见性更新失败，请刷新后重试");
        }
        return getDetail(postId, creatorId);
    }

    /**
     * 软删除文章。
     *
     * @param creatorId 作者 ID
     * @param postId 文章 ID
     */
    @Transactional
    public void delete(String creatorId, String postId) {
        KnowPostEntity entity = loadOwnedPost(postId, creatorId);
        if (STATUS_DELETED.equals(entity.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在");
        }

        int updated = knowPostMapper.softDelete(postId, creatorId, Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, "文章删除失败，请刷新后重试");
        }
    }

    /**
     * 查询文章详情。
     *
     * @param postId 文章 ID
     * @param viewerId 当前查看者 ID，可为空
     * @return 文章详情
     */
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
                imageUrls.isEmpty() ? null : imageUrls.get(0),
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

    /**
     * 加载当前用户拥有的文章。
     *
     * @param postId 文章 ID
     * @param creatorId 作者 ID
     * @return 文章实体
     */
    private KnowPostEntity loadOwnedPost(String postId, String creatorId) {
        KnowPostEntity entity = knowPostMapper.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "文章不存在"));
        if (!Objects.equals(entity.creatorId(), creatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "无权操作该文章");
        }
        return entity;
    }

    /**
     * 校验文章是否仍可继续编辑。
     *
     * @param entity 文章实体
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
     * 序列化为 JSON 字符串。
     *
     * @param value 待序列化对象
     * @return JSON 字符串
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
     *
     * @param json JSON 字符串
     * @return 解析后的字符串列表
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
     * 判断文本是否非空白。
     *
     * @param value 待判断文本
     * @return 非空白返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 规范化并校验图片地址是否归属当前文章。
     *
     * @param postId 文章 ID
     * @param rawValue 原始图片地址或对象 Key
     * @return 规范化后的公网地址
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
     * 根据对象 Key 生成 mock 公网访问地址。
     *
     * @param objectKey 对象 Key
     * @return 公网访问地址
     */
    private String buildPublicUrl(String objectKey) {
        return normalizeBaseUrl(publicBaseUrl) + "/" + objectKey;
    }

    /**
     * 规整基础地址，避免尾部多余斜杠。
     *
     * @param baseUrl 原始地址
     * @return 去除尾部斜杠后的地址
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 规范化可空文本。
     *
     * @param value 原始文本
     * @return 去空白后的文本，空白时返回 null
     */
    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
