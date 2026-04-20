package com.zhiguang.be.feed;

import java.time.Instant;

/**
 * Feed 模块查询行对象。
 * 只保留首页混排真正需要的基础字段。
 */
public record FeedPostRow(
        String postId,
        String creatorId,
        String authorNickname,
        String authorAvatar,
        String title,
        String description,
        Double latitude,
        Double longitude,
        Instant publishTime,
        Boolean isTop
) {
}
