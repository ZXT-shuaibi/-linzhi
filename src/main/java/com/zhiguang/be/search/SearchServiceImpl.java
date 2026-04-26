package com.zhiguang.be.search;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索模块服务实现。
 */
@Service
public class SearchServiceImpl implements SearchService {

    private final SearchProperties searchProperties;
    private final List<SearchProvider> searchProviders;

    public SearchServiceImpl(SearchProperties searchProperties, List<SearchProvider> searchProviders) {
        this.searchProperties = searchProperties;
        this.searchProviders = searchProviders;
    }

    @Override
    public SearchPostsData searchPosts(
            String q,
            int page,
            int size,
            String searchAfter,
            long currentUserId,
            Double lat,
            Double lng,
            Double radius,
            String tag
    ) {
        return currentProvider().searchPosts(q, page, size, searchAfter, currentUserId, lat, lng, radius, tag);
    }

    @Override
    public SuggestData suggest(String q, int size) {
        return currentProvider().suggest(q, size);
    }

    private SearchProvider currentProvider() {
        String configuredProvider = normalizeProvider(searchProperties.getProvider());
        for (SearchProvider searchProvider : searchProviders) {
            if (configuredProvider.equalsIgnoreCase(normalizeProvider(searchProvider.provider()))) {
                return searchProvider;
            }
        }
        throw new IllegalStateException("未找到 search.provider=" + configuredProvider + " 对应的搜索提供者");
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "db" : provider.trim();
    }
}
