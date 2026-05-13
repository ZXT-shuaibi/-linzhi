package com.zhiguang.be.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

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
        SearchProvider provider = currentProvider();
        try {
            return provider.searchPosts(q, page, size, searchAfter, currentUserId, lat, lng, radius, tag);
        } catch (RuntimeException ex) {
            SearchProvider fallback = dbFallback(provider);
            if (fallback == null) {
                throw ex;
            }
            log.warn("search provider {} failed, falling back to db", provider.provider(), ex);
            return fallback.searchPosts(q, page, size, searchAfter, currentUserId, lat, lng, radius, tag);
        }
    }

    @Override
    public SuggestData suggest(String q, int size) {
        SearchProvider provider = currentProvider();
        try {
            return provider.suggest(q, size);
        } catch (RuntimeException ex) {
            SearchProvider fallback = dbFallback(provider);
            if (fallback == null) {
                throw ex;
            }
            log.warn("search suggest provider {} failed, falling back to db", provider.provider(), ex);
            return fallback.suggest(q, size);
        }
    }

    private SearchProvider currentProvider() {
        String configuredProvider = normalizeProvider(searchProperties.getProvider());
        SearchProvider dbProvider = null;
        for (SearchProvider searchProvider : searchProviders) {
            String providerName = normalizeProvider(searchProvider.provider());
            if ("db".equalsIgnoreCase(providerName)) {
                dbProvider = searchProvider;
            }
            if (configuredProvider.equalsIgnoreCase(providerName)) {
                return searchProvider;
            }
        }
        if (dbProvider != null && !"db".equalsIgnoreCase(configuredProvider)) {
            log.warn("search.provider={} is unavailable, falling back to db", configuredProvider);
            return dbProvider;
        }
        throw new IllegalStateException("No search provider found for search.provider=" + configuredProvider);
    }

    private SearchProvider dbFallback(SearchProvider failedProvider) {
        if (failedProvider == null || "db".equalsIgnoreCase(normalizeProvider(failedProvider.provider()))) {
            return null;
        }
        for (SearchProvider searchProvider : searchProviders) {
            if ("db".equalsIgnoreCase(normalizeProvider(searchProvider.provider()))) {
                return searchProvider;
            }
        }
        return null;
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "db" : provider.trim();
    }
}
