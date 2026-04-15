package com.zhiguang.be.content.mapper;

import com.zhiguang.be.content.model.KnowPostDetailRow;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.KnowPostFeedRow;
import com.zhiguang.be.content.model.OutboxEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 内容模块 Mapper。
 * 参考 zhiguang，SQL 由 XML 承载，接口只保留方法签名。
 */
@Mapper
public interface KnowPostMapper {

    /**
     * 插入草稿内容。
     */
    void insert(KnowPostEntity entity);

    /**
     * 按文章 ID 查询实体。
     */
    KnowPostEntity findById(@Param("postId") String postId);

    /**
     * 按文章 ID 查询详情行。
     */
    KnowPostDetailRow findDetailById(@Param("postId") String postId);

    /**
     * 查询公开 feed。
     */
    List<KnowPostFeedRow> listFeedPublic(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 查询我的已发布内容。
     */
    List<KnowPostFeedRow> listMyPublished(
            @Param("creatorId") String creatorId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 判断查看者是否关注了内容作者。
     */
    boolean existsFollowingRelation(
            @Param("viewerId") String viewerId,
            @Param("creatorId") String creatorId
    );

    /**
     * 更新正文确认结果。
     */
    int updateContent(
            @Param("postId") String postId,
            @Param("creatorId") String creatorId,
            @Param("status") String status,
            @Param("contentUrl") String contentUrl,
            @Param("objectKey") String objectKey,
            @Param("etag") String etag,
            @Param("size") Long size,
            @Param("sha256") String sha256,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 更新文章元数据。
     */
    int updateMetadata(
            @Param("postId") String postId,
            @Param("creatorId") String creatorId,
            @Param("status") String status,
            @Param("title") String title,
            @Param("description") String description,
            @Param("tagsJson") String tagsJson,
            @Param("imgUrlsJson") String imgUrlsJson,
            @Param("isTop") Boolean isTop,
            @Param("visible") String visible,
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("geoHash") String geoHash,
            @Param("address") String address,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 发布文章。
     */
    int publish(
            @Param("postId") String postId,
            @Param("creatorId") String creatorId,
            @Param("visibility") String visibility,
            @Param("status") String status,
            @Param("publishTime") Instant publishTime,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 更新置顶状态。
     */
    int updateTop(
            @Param("postId") String postId,
            @Param("creatorId") String creatorId,
            @Param("isTop") Boolean isTop,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 更新可见性。
     */
    int updateVisibility(
            @Param("postId") String postId,
            @Param("creatorId") String creatorId,
            @Param("visible") String visible,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 软删除文章。
     */
    int softDelete(
            @Param("postId") String postId,
            @Param("creatorId") String creatorId,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 插入 outbox 事件。
     */
    void insertOutbox(OutboxEventEntity entity);

    /**
     * 查询待处理 outbox 事件。
     */
    List<OutboxEventEntity> listPendingOutbox(@Param("limit") int limit);

    /**
     * 标记 outbox 已发布。
     */
    int markOutboxPublished(@Param("eventId") String eventId, @Param("publishedAt") Instant publishedAt);

    /**
     * 标记 outbox 发布失败。
     */
    int markOutboxFailed(@Param("eventId") String eventId, @Param("lastError") String lastError);
}
