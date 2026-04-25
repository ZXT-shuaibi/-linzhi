package com.zhiguang.be.search;

/**
 * 搜索提供者接口。
 * 当前先落地 db 提供者，后续接 ES 时只需要补实现，不需要改控制器和外层服务。
 */
public interface SearchProvider {

    /**
     * 提供者名称。
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
