package com.zhiguang.be.content;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 内容模块模型集合。
 * 将对外 DTO 与内部简单数据对象集中放在一个文件中，减少文件数量。
 */
public final class ContentModels {

    private ContentModels() {
    }

    /**
     * 创建草稿请求。
     */
    public record CreateDraftRequest(
            String contentType,
            String sourceType
    ) {
    }

    /**
     * 草稿创建结果。
     */
    public record DraftData(
            String postId,
            String status,
            Instant createdAt
    ) {
    }

    /**
     * 确认正文上传请求。
     */
    public record ConfirmContentRequest(
            @NotBlank(message = "objectKey 不能为空")
            @Size(max = 512, message = "objectKey 长度不能超过 512")
            String objectKey,

            @NotBlank(message = "etag 不能为空")
            @Size(max = 128, message = "etag 长度不能超过 128")
            String etag,

            @NotBlank(message = "sha256 不能为空")
            @Size(min = 64, max = 64, message = "sha256 必须为 64 位")
            String sha256,

            @NotNull(message = "size 不能为空")
            @Positive(message = "size 必须大于 0")
            Long size
    ) {
    }

    /**
     * 确认正文上传结果。
     */
    public record ConfirmContentData(
            String postId,
            String status,
            String objectKey
    ) {
    }

    /**
     * 位置对象。
     */
    public record PostLocation(
            Double lat,
            Double lng,
            String geoHash,
            String address
    ) {
    }

    /**
     * 更新元数据请求。
     */
    public record UpdatePostMetadataRequest(
            @Size(max = 256, message = "标题长度不能超过 256")
            String title,

            @Size(max = 128, message = "摘要长度不能超过 128")
            String summary,

            List<@Size(max = 32, message = "标签长度不能超过 32") String> tags,

            List<@Size(max = 512, message = "图片地址长度不能超过 512") String> imageUrls,

            @Size(max = 512, message = "封面地址长度不能超过 512")
            String coverUrl,

            @Pattern(
                    regexp = "^(public|followers|private)$",
                    message = "visibility 只能为 public、followers、private"
            )
            String visibility,

            Boolean isTop,

            @Valid
            PostLocation location
    ) {
    }

    /**
     * 发布文章请求。
     */
    public record PublishPostRequest(
            @Pattern(
                    regexp = "^(public|followers|private)$",
                    message = "visibility 只能为 public、followers、private"
            )
            String visibility,

            Instant publishAt
    ) {
    }

    /**
     * 更新置顶状态请求。
     */
    public record UpdateTopRequest(
            @NotNull(message = "isTop 不能为空")
            Boolean isTop
    ) {
    }

    /**
     * 更新可见性请求。
     */
    public record UpdateVisibilityRequest(
            @NotBlank(message = "visibility 不能为空")
            @Pattern(
                    regexp = "^(public|followers|private)$",
                    message = "visibility 只能为 public、followers、private"
            )
            String visibility
    ) {
    }

    /**
     * 作者概要信息。
     */
    public record PostAuthor(
            String userId,
            String nickname,
            String avatar
    ) {
    }

    /**
     * 文章详情。
     */
    public record PostDetail(
            String postId,
            PostAuthor author,
            String status,
            String title,
            String summary,
            String contentUrl,
            String coverUrl,
            List<String> imageUrls,
            List<String> tags,
            PostLocation location,
            String visibility,
            String type,
            Boolean isTop,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * 预签名请求。
     */
    public record StoragePresignRequest(
            @NotBlank(message = "postId 不能为空")
            @Pattern(regexp = "^\\d+$", message = "postId 必须为数字 ID")
            String postId,

            @NotBlank(message = "filename 不能为空")
            @Size(max = 255, message = "filename 长度不能超过 255")
            String filename,

            @NotBlank(message = "contentType 不能为空")
            @Size(max = 128, message = "contentType 长度不能超过 128")
            String contentType,

            @NotBlank(message = "purpose 不能为空")
            @Pattern(regexp = "^(content|cover|image)$", message = "purpose 只能为 content、cover、image")
            String purpose
    ) {
    }

    /**
     * 预签名结果。
     */
    public record StoragePresignData(
            String uploadUrl,
            String objectKey,
            Instant expireAt
    ) {
    }

    /**
     * 文章实体。
     */
    public record KnowPostEntity(
            String postId,
            String creatorId,
            Long tagId,
            String tagsJson,
            String title,
            String description,
            Double latitude,
            Double longitude,
            String geoHash,
            String address,
            String contentUrl,
            String contentObjectKey,
            String contentEtag,
            Long contentSize,
            String contentSha256,
            Boolean isTop,
            String type,
            String visible,
            String imgUrlsJson,
            String videoUrl,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Instant publishTime
    ) {
    }

    /**
     * 文章详情查询行对象。
     */
    public record KnowPostDetailRow(
            String postId,
            String creatorId,
            String authorNickname,
            String authorAvatar,
            String status,
            String title,
            String description,
            String contentUrl,
            String imgUrlsJson,
            String tagsJson,
            String visible,
            String type,
            Boolean isTop,
            Double latitude,
            Double longitude,
            String geoHash,
            String address,
            Instant publishTime,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * outbox 事件实体。
     */
    public record OutboxEventEntity(
            String id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            String status,
            int retryCount,
            Instant createdAt
    ) {
    }

    /**
     * 文章发布事件载荷。
     */
    public record PostPublishedPayload(
            String eventId,
            String eventType,
            String postId,
            String authorId,
            String visibility,
            PostLocation location,
            Instant occurredAt
    ) {
    }
}
