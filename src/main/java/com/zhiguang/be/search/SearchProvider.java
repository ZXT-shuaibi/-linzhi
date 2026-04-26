package com.zhiguang.be.search;

/**
 * 搜索提供者接口。
 */
public interface SearchProvider {

    /**
     * 提供者标识。
     */
    String provider();

    /**
     * 搜索公开帖子。
     */
    SearchPostsData searchPosts(
            String q,
            int page,
            int size,
            String searchAfter,
            long currentUserId,
            Double lat,
            Double lng,
            Double radius,
            String tag
    );

    /**
     * 联想建议。
     */
    SuggestData suggest(String q, int size);
}
