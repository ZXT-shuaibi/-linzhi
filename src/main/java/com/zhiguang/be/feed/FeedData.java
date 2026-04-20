package com.zhiguang.be.feed;

import com.zhiguang.be.social.PageMeta;

import java.util.List;

/**
 * 首页 Feed 分页结果。
 */
public record FeedData(
        List<FeedItem> items,
        PageMeta page,
        String cacheLayer
) {
}
