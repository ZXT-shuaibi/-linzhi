package com.zhiguang.be.content.model;

import java.time.Instant;

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
