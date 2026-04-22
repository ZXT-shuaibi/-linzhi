package com.zhiguang.be.search;

/**
 * 搜索模块服务接口。
 */
public interface SearchService {

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
