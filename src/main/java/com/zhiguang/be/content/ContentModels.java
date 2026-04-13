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
 * 为了减少文件数量，将 DTO、实体和简单查询行对象集中放在一个文件中。
 */
public final class ContentModels {

    private ContentModels() {
    }

    /**
     * 创建草稿请求。
     * 当前版本对齐 zhiguang，草稿类型固定为 image_text，因此不再暴露多余字段。
     */
    public record CreateDraftRequest() {
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
            @Size(min = 64, max = 64, message = "sha256 必须是 64 位")
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
     * 文章位置对象。
     */
    public record PostLocation(
            Double lat,
            Double lng,
            String geoHash,
            String address
    ) {
    }

    /**
     * 更新文章元数据请求。
     * 对齐 zhiguang，只保留 images，不再把 coverUrl 作为独立存储字段。
     */
    public record UpdatePostMetadataRequest(
            @Size(max = 256, message = "标题长度不能超过 256")
            String title,

            @Size(max = 128, message = "摘要长度不能超过 128")
            String summary,

            List<@Size(max = 32, message = "标签长度不能超过 32") String> tags,

            List<@Size(max = 512, message = "图片地址长度不能超过 512") String> imageUrls,

            @Pattern(
                    regexp = "^(public|followers|private)$",
                    message = "visibility 只能是 public、followers、private"
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
                    message = "visibility 只能是 public、followers、private"
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
                    message = "visibility 只能是 public、followers、private"
            )
            String visibility
    ) {
    }

    /**
     * 作者信息。
     */
    public record PostAuthor(
            String userId,
            String nickname,
            String avatar
    ) {
    }

    /**
     * 文章详情。
     * 封面图由前端或读模型从 imageUrls 首图推导，不再单独存储 coverUrl。
     */
    public record PostDetail(
            String postId,
            PostAuthor author,
            String status,
            String title,
            String summary,
            String contentUrl,
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
     * 文章卡片。
     * 这里保留 coverUrl 作为读模型字段，方便 feed/mine 直接展示封面。
     */
    public record PostCard(
            String postId,
            String title,
            String summary,
            String coverUrl,
            List<String> tags,
            PostAuthor author,
            String visibility,
            Boolean isTop,
            Instant publishedAt
    ) {
    }

    /**
     * 内容分页结果。
     */
    public record PostPageData(
            List<PostCard> items,
            int page,
            int size,
            boolean hasMore
    ) {
    }

    /**
     * 申请预签名请求。
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
            @Pattern(regexp = "^(content|cover|image)$", message = "purpose 只能是 content、cover、image")
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
     * feed/mine 查询行对象。
     */
    public record KnowPostFeedRow(
            String postId,
            String creatorId,
            String authorNickname,
            String authorAvatar,
            String title,
            String description,
            String imgUrlsJson,
            String tagsJson,
            String visibility,
            Instant publishTime,
            Boolean isTop
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
     * 内容到 discover 的同步事件载荷。
     * 当前只保留最小字段，补偿任务按 postId 查询最新状态做最终一致性处理。
     */
    public record PostSyncPayload(
            String eventId,
            String eventType,
            String postId,
            Instant occurredAt
    ) {
    }
}
