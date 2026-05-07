package com.zhiguang.be.search;

import com.zhiguang.be.common.geo.GeoDistances;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.service.InteractionService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zhiguang.be.common.util.Texts.hasText;

/**
 * 数据库基础版搜索提供者。
 */
@Component
public class DbSearchProvider implements SearchProvider {

    private final SearchMapper searchMapper;
    private final SearchProperties searchProperties;
    private final InteractionService interactionService;

    public DbSearchProvider(
            SearchMapper searchMapper,
            SearchProperties searchProperties,
            InteractionService interactionService
    ) {
        this.searchMapper = searchMapper;
        this.searchProperties = searchProperties;
        this.interactionService = interactionService;
    }

    @Override
    public String provider() {
        return "db";
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
        int safePage = Math.max(page, 1);
        int safeSize = normalizePageSize(size);
        SearchCursor cursor = decodeAfter(searchAfter);
        int offset = cursor == null ? (safePage - 1) * safeSize : 0;
        int fetchLimit = normalizeFetchLimit(safeSize);
        String keyword = "%" + q.trim().toLowerCase() + "%";
        String tagKeyword = hasText(tag) ? "%" + tag.trim().toLowerCase() + "%" : null;

        List<SearchPostRow> rows = searchMapper.searchPosts(
                keyword,
                tagKeyword,
                cursor == null ? null : Integer.valueOf(cursor.isTop()),
                cursor == null ? null : toInstant(cursor.publishTimeMillis()),
                cursor == null ? null : Long.valueOf(cursor.postId()),
                fetchLimit,
                offset
        );
        Map<String, InteractionSummary> interactionMap = loadInteractionMap(currentUserId, rows);

        List<SearchResultItem> items = new ArrayList<SearchResultItem>();
        for (SearchPostRow row : rows) {
            Double distanceMeters = computeDistanceMeters(lat, lng, row.latitude(), row.longitude());
            if (radius != null && distanceMeters != null && distanceMeters > radius.doubleValue()) {
                continue;
            }
            List<String> rowSearchAfter = buildSearchAfter(row);
            InteractionSummary summary = interactionMap.get(row.postId());
            items.add(new SearchResultItem(
                    row.postId(),
                    row.title(),
                    buildSnippet(q, row),
                    firstListValue(parseJsonArray(row.imgUrlsJson())),
                    parseJsonArray(row.tagsJson()),
                    row.authorId(),
                    row.authorNickname(),
                    row.authorAvatar(),
                    row.authorTagJson(),
                    summary == null ? 0L : summary.getLikeCount(),
                    summary == null ? 0L : summary.getFavoriteCount(),
                    currentUserId > 0L && summary != null ? summary.isViewerLiked() : null,
                    currentUserId > 0L && summary != null ? summary.isViewerFavorited() : null,
                    row.isTop() != null && row.isTop().intValue() == 1,
                    row.publishTime(),
                    computeScore(q, row),
                    distanceMeters,
                    rowSearchAfter
            ));
            if (items.size() >= safeSize + 1) {
                break;
            }
        }

        boolean hasMore = items.size() > safeSize;
        List<SearchResultItem> pageItems = hasMore ? new ArrayList<SearchResultItem>(items.subList(0, safeSize)) : items;
        String nextAfter = null;
        List<String> nextSearchAfter = List.of();
        if (!pageItems.isEmpty()) {
            SearchResultItem last = pageItems.get(pageItems.size() - 1);
            nextSearchAfter = last.searchAfter();
            nextAfter = encodeAfter(nextSearchAfter);
        }

        return new SearchPostsData(
                pageItems,
                new CursorPageMeta(safePage, safeSize, hasMore, nextAfter, nextSearchAfter)
        );
    }

    @Override
    public SuggestData suggest(String q, int size) {
        int safeSize = normalizeSuggestSize(size);
        int scanLimit = Math.min(safeSize * 3, 50);
        List<String> titles = searchMapper.suggestTitles("%" + q.trim().toLowerCase() + "%", scanLimit);
        Set<String> deduplicated = new LinkedHashSet<String>();
        List<SuggestItem> items = new ArrayList<SuggestItem>();
        for (String title : titles) {
            if (title == null) {
                continue;
            }
            String normalized = title.trim();
            if (normalized.isEmpty() || !deduplicated.add(normalized)) {
                continue;
            }
            items.add(new SuggestItem(normalized, suggestScore(q, normalized), "title"));
            if (items.size() >= safeSize) {
                break;
            }
        }
        if (searchProperties.isEnableTagSuggest() && items.size() < safeSize) {
            appendTagSuggests(q, safeSize, deduplicated, items, scanLimit);
        }
        return new SuggestData(items);
    }

    private int normalizePageSize(int size) {
        int defaultSize = Math.max(searchProperties.getDefaultPageSize(), 1);
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, Math.max(searchProperties.getMaxPageSize(), 1));
    }

    private int normalizeSuggestSize(int size) {
        int defaultSize = Math.max(searchProperties.getDefaultSuggestSize(), 1);
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, Math.max(searchProperties.getMaxSuggestSize(), 1));
    }

    private int normalizeFetchLimit(int safeSize) {
        int fetchMultiplier = Math.max(searchProperties.getFetchMultiplier(), 1);
        int maxFetchLimit = Math.max(searchProperties.getMaxFetchLimit(), safeSize + 1);
        return Math.min(Math.max(safeSize * fetchMultiplier, safeSize + 1), maxFetchLimit);
    }

    private Map<String, InteractionSummary> loadInteractionMap(long currentUserId, List<SearchPostRow> rows) {
        List<Long> targetIds = new ArrayList<Long>();
        for (SearchPostRow row : rows) {
            if (row == null || row.postId() == null) {
                continue;
            }
            try {
                targetIds.add(Long.valueOf(row.postId()));
            } catch (Exception ignored) {
                // 忽略异常主键，避免影响整页搜索结果。
            }
        }
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        try {
            return interactionService.summaryBatch(currentUserId, "post", targetIds);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String buildSnippet(String q, SearchPostRow row) {
        String keyword = q == null ? "" : q.trim();
        String candidate = firstNonBlank(row.summary(), row.title(), row.tagsJson());
        if (candidate == null) {
            return "";
        }
        String cleaned = candidate.replaceAll("[\\[\\]\"]", " ").replaceAll("\\s+", " ").trim();
        if (keyword.isEmpty()) {
            return truncate(cleaned, searchProperties.getSnippetLength());
        }
        String lower = cleaned.toLowerCase();
        String normalizedKeyword = keyword.toLowerCase();
        int hitIndex = lower.indexOf(normalizedKeyword);
        if (hitIndex < 0) {
            return truncate(cleaned, searchProperties.getSnippetLength());
        }
        int snippetLength = Math.max(searchProperties.getSnippetLength(), normalizedKeyword.length());
        int halfWindow = Math.max(snippetLength / 2, normalizedKeyword.length());
        int start = Math.max(0, hitIndex - halfWindow);
        int end = Math.min(cleaned.length(), start + snippetLength);
        if (end - start < snippetLength && start > 0) {
            start = Math.max(0, end - snippetLength);
        }
        String snippet = cleaned.substring(start, end).trim();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < cleaned.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private List<String> buildSearchAfter(SearchPostRow row) {
        long publishTimeMillis = row.publishTime() == null ? 0L : row.publishTime().toEpochMilli();
        int isTop = row.isTop() == null ? 0 : row.isTop().intValue();
        return List.of(
                String.valueOf(isTop),
                String.valueOf(publishTimeMillis),
                row.postId()
        );
    }

    private String encodeAfter(List<String> searchAfterValues) {
        String joined = String.join(",", searchAfterValues);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    private SearchCursor decodeAfter(String searchAfter) {
        if (!hasText(searchAfter)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(searchAfter), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",", 3);
            if (parts.length != 3) {
                return null;
            }
            return new SearchCursor(
                    Integer.parseInt(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2])
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private Double computeScore(String q, SearchPostRow row) {
        String keyword = q == null ? "" : q.trim().toLowerCase();
        if (keyword.isEmpty()) {
            return 0D;
        }
        double score = 0D;
        String title = safeLower(row.title());
        String summary = safeLower(row.summary());
        String tags = safeLower(row.tagsJson());
        if (title.contains(keyword)) {
            score += 3D;
            if (title.equals(keyword)) {
                score += 2D;
            }
        }
        if (summary.contains(keyword)) {
            score += 1.5D;
        }
        if (tags.contains(keyword)) {
            score += 1D;
        }
        if (row.isTop() != null && row.isTop().intValue() == 1) {
            score += 0.8D;
        }
        Instant publishTime = row.publishTime();
        if (publishTime != null) {
            long ageHours = Math.max(1L, (Instant.now().getEpochSecond() - publishTime.getEpochSecond()) / 3600L);
            score += 24D / ageHours;
        }
        return score;
    }

    private Double suggestScore(String q, String title) {
        String keyword = q == null ? "" : q.trim().toLowerCase();
        String normalized = title.toLowerCase();
        if (normalized.startsWith(keyword)) {
            return 1.0D;
        }
        if (normalized.contains(keyword)) {
            return 0.6D;
        }
        return 0.3D;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private List<String> parseJsonArray(String rawValue) {
        if (!hasText(rawValue)) {
            return Collections.emptyList();
        }
        String normalized = rawValue
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('"', ' ')
                .replace('\'', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = normalized.split(",");
        List<String> values = new ArrayList<String>();
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty() && !values.contains(item)) {
                values.add(item);
            }
        }
        return values;
    }

    private String firstListValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private void appendTagSuggests(
            String q,
            int safeSize,
            Set<String> deduplicated,
            List<SuggestItem> items,
            int scanLimit
    ) {
        List<String> rawTagValues = searchMapper.suggestTagValues("%" + q.trim().toLowerCase() + "%", scanLimit);
        for (String rawTagValue : rawTagValues) {
            for (String tag : extractTags(rawTagValue)) {
                if (!tag.toLowerCase().contains(q.trim().toLowerCase()) || !deduplicated.add(tag)) {
                    continue;
                }
                items.add(new SuggestItem(tag, suggestScore(q, tag) + 0.1D, "tag"));
                if (items.size() >= safeSize) {
                    return;
                }
            }
        }
    }

    private List<String> extractTags(String rawTagValue) {
        if (!hasText(rawTagValue)) {
            return Collections.emptyList();
        }
        String normalized = rawTagValue
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('"', ' ')
                .replace('\'', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = normalized.split("[,，]");
        List<String> tags = new ArrayList<String>();
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return tags;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(maxLength, 0));
    }

    private Double computeDistanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        return GeoDistances.haversineMeters(lat1, lng1, lat2, lng2);
    }

    private Instant toInstant(long epochMillis) {
        return epochMillis <= 0L ? Instant.EPOCH : Instant.ofEpochMilli(epochMillis);
    }

    /**
     * 搜索游标。
     */
    private record SearchCursor(
            int isTop,
            long publishTimeMillis,
            long postId
    ) {
    }
}
