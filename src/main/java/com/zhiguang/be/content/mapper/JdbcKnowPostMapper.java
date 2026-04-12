package com.zhiguang.be.content.mapper;

import com.zhiguang.be.content.ContentModels.KnowPostDetailRow;
import com.zhiguang.be.content.ContentModels.KnowPostEntity;
import com.zhiguang.be.content.ContentModels.OutboxEventEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 基于 JDBC 的文章持久化实现。
 * 负责知文草稿、发布状态与 outbox 事件写入。
 */
@Repository
public class JdbcKnowPostMapper {

    private static final RowMapper<KnowPostEntity> ENTITY_ROW_MAPPER = (rs, rowNum) -> new KnowPostEntity(
            String.valueOf(rs.getLong("id")),
            String.valueOf(rs.getLong("creator_id")),
            getNullableLong(rs, "tag_id"),
            rs.getString("tags"),
            rs.getString("title"),
            rs.getString("description"),
            getNullableDouble(rs, "latitude"),
            getNullableDouble(rs, "longitude"),
            rs.getString("geo_hash"),
            rs.getString("address"),
            rs.getString("content_url"),
            rs.getString("content_object_key"),
            rs.getString("content_etag"),
            getNullableLong(rs, "content_size"),
            rs.getString("content_sha256"),
            rs.getObject("is_top", Boolean.class),
            rs.getString("type"),
            rs.getString("visible"),
            rs.getString("img_urls"),
            rs.getString("video_url"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("publish_time"))
    );

    private static final RowMapper<KnowPostDetailRow> DETAIL_ROW_MAPPER = (rs, rowNum) -> new KnowPostDetailRow(
            String.valueOf(rs.getLong("id")),
            String.valueOf(rs.getLong("creator_id")),
            rs.getString("author_nickname"),
            rs.getString("author_avatar"),
            rs.getString("status"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("content_url"),
            rs.getString("img_urls"),
            rs.getString("tags"),
            rs.getString("visible"),
            rs.getString("type"),
            rs.getObject("is_top", Boolean.class),
            getNullableDouble(rs, "latitude"),
            getNullableDouble(rs, "longitude"),
            rs.getString("geo_hash"),
            rs.getString("address"),
            toInstant(rs.getTimestamp("publish_time")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造 JDBC 文章持久化实现。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcKnowPostMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(KnowPostEntity entity) {
        jdbcTemplate.update(
                """
                insert into know_posts(
                    id, tag_id, tags, title, description, latitude, longitude, geo_hash, address,
                    content_url, content_object_key, content_etag, content_size, content_sha256,
                    creator_id, is_top, type, visible, img_urls, video_url, status,
                    created_at, updated_at, publish_time
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Long.parseLong(entity.postId()),
                entity.tagId(),
                entity.tagsJson(),
                entity.title(),
                entity.description(),
                entity.latitude(),
                entity.longitude(),
                entity.geoHash(),
                entity.address(),
                entity.contentUrl(),
                entity.contentObjectKey(),
                entity.contentEtag(),
                entity.contentSize(),
                entity.contentSha256(),
                Long.parseLong(entity.creatorId()),
                entity.isTop(),
                entity.type(),
                entity.visible(),
                entity.imgUrlsJson(),
                entity.videoUrl(),
                entity.status(),
                toTimestamp(entity.createdAt()),
                toTimestamp(entity.updatedAt()),
                toTimestamp(entity.publishTime())
        );
    }

    public Optional<KnowPostEntity> findById(String postId) {
        return jdbcTemplate.query(
                """
                select id, tag_id, tags, title, description, latitude, longitude, geo_hash, address,
                       content_url, content_object_key, content_etag, content_size, content_sha256,
                       creator_id, is_top, type, visible, img_urls, video_url, status,
                       created_at, updated_at, publish_time
                  from know_posts
                 where id = ?
                """,
                ENTITY_ROW_MAPPER,
                Long.parseLong(postId)
        ).stream().findFirst();
    }

    public Optional<KnowPostDetailRow> findDetailById(String postId) {
        return jdbcTemplate.query(
                """
                select p.id,
                       p.creator_id,
                       u.nickname as author_nickname,
                       u.avatar as author_avatar,
                       p.status,
                       p.title,
                       p.description,
                       p.content_url,
                       p.img_urls,
                       p.tags,
                       p.visible,
                       p.type,
                       p.is_top,
                       p.latitude,
                       p.longitude,
                       p.geo_hash,
                       p.address,
                       p.publish_time,
                       p.created_at,
                       p.updated_at
                  from know_posts p
                  join users u on u.id = p.creator_id
                 where p.id = ?
                """,
                DETAIL_ROW_MAPPER,
                Long.parseLong(postId)
        ).stream().findFirst();
    }

    public int updateContent(
            String postId,
            String creatorId,
            String status,
            String contentUrl,
            String objectKey,
            String etag,
            Long size,
            String sha256,
            Instant updatedAt
    ) {
        return jdbcTemplate.update(
                """
                update know_posts
                   set status = ?,
                       content_url = ?,
                       content_object_key = ?,
                       content_etag = ?,
                       content_size = ?,
                       content_sha256 = ?,
                       updated_at = ?
                 where id = ?
                   and creator_id = ?
                   and status in ('draft', 'content_confirmed', 'metadata_completed')
                """,
                status,
                contentUrl,
                objectKey,
                etag,
                size,
                sha256,
                toTimestamp(updatedAt),
                Long.parseLong(postId),
                Long.parseLong(creatorId)
        );
    }

    public int updateMetadata(
            String postId,
            String creatorId,
            String status,
            String title,
            String description,
            String tagsJson,
            String imgUrlsJson,
            Boolean isTop,
            String visible,
            Double latitude,
            Double longitude,
            String geoHash,
            String address,
            Instant updatedAt
    ) {
        return jdbcTemplate.update(
                """
                update know_posts
                   set status = ?,
                       title = ?,
                       description = ?,
                       tags = ?,
                       img_urls = ?,
                       is_top = ?,
                       visible = ?,
                       latitude = ?,
                       longitude = ?,
                       geo_hash = ?,
                       address = ?,
                       updated_at = ?
                 where id = ?
                   and creator_id = ?
                   and status in ('draft', 'content_confirmed', 'metadata_completed')
                """,
                status,
                title,
                description,
                tagsJson,
                imgUrlsJson,
                isTop,
                visible,
                latitude,
                longitude,
                geoHash,
                address,
                toTimestamp(updatedAt),
                Long.parseLong(postId),
                Long.parseLong(creatorId)
        );
    }

    public int publish(
            String postId,
            String creatorId,
            String visibility,
            String status,
            Instant publishTime,
            Instant updatedAt
    ) {
        return jdbcTemplate.update(
                """
                update know_posts
                   set visible = ?,
                       status = ?,
                       publish_time = ?,
                       updated_at = ?
                 where id = ?
                   and creator_id = ?
                   and status in ('draft', 'content_confirmed', 'metadata_completed')
                """,
                visibility,
                status,
                toTimestamp(publishTime),
                toTimestamp(updatedAt),
                Long.parseLong(postId),
                Long.parseLong(creatorId)
        );
    }

    public int updateTop(String postId, String creatorId, Boolean isTop, Instant updatedAt) {
        return jdbcTemplate.update(
                """
                update know_posts
                   set is_top = ?,
                       updated_at = ?
                 where id = ?
                   and creator_id = ?
                   and status = 'published'
                """,
                isTop,
                toTimestamp(updatedAt),
                Long.parseLong(postId),
                Long.parseLong(creatorId)
        );
    }

    public int updateVisibility(String postId, String creatorId, String visible, Instant updatedAt) {
        return jdbcTemplate.update(
                """
                update know_posts
                   set visible = ?,
                       updated_at = ?
                 where id = ?
                   and creator_id = ?
                   and status = 'published'
                """,
                visible,
                toTimestamp(updatedAt),
                Long.parseLong(postId),
                Long.parseLong(creatorId)
        );
    }

    public int softDelete(String postId, String creatorId, Instant updatedAt) {
        return jdbcTemplate.update(
                """
                update know_posts
                   set status = 'deleted',
                       updated_at = ?
                 where id = ?
                   and creator_id = ?
                   and status <> 'deleted'
                """,
                toTimestamp(updatedAt),
                Long.parseLong(postId),
                Long.parseLong(creatorId)
        );
    }

    public void insertOutbox(OutboxEventEntity entity) {
        jdbcTemplate.update(
                """
                insert into outbox(
                    id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at, published_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Long.parseLong(entity.id()),
                entity.aggregateType(),
                Long.parseLong(entity.aggregateId()),
                entity.eventType(),
                entity.payloadJson(),
                entity.status(),
                entity.retryCount(),
                toTimestamp(entity.createdAt()),
                null
        );
    }

    private static Long getNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getNullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
