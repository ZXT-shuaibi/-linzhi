package com.zhiguang.be.search;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索模块服务实现。
 * 当前基础版先走数据库检索，后续可以平滑升级到 ES。
 */
@Service
public class SearchServiceImpl implements SearchService {

    private final SearchMapper searchMapper;

    public SearchServiceImpl(SearchMapper searchMapper) {
        this.searchMapper = searchMapper;
    }

    @Override
    public SearchPostsData searchPosts(
            String q,
            int page,
            int size,
            String searchAfter,
            Double lat,
            Double lng,
            Double radius,
            String tag
    ) {
        int safePage = Math.max(page, 1);
        int safeSize = normalizeSize(size, 20);
        SearchCursor cursor = decodeAfter(searchAfter);
        int offset = cursor == null ? (safePage - 1) * safeSize : 0;
        int fetchLimit = Math.min(Math.max(safeSize * 3, safeSize + 1), 100);
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

        List<SearchResultItem> items = new ArrayList<SearchResultItem>();
        for (SearchPostRow row : rows) {
            Double distanceMeters = computeDistanceMeters(lat, lng, row.latitude(), row.longitude());
            if (radius != null && distanceMeters != null && distanceMeters > radius.doubleValue()) {
                continue;
            }
            List<String> rowSearchAfter = buildSearchAfter(row);
            items.add(new SearchResultItem(
                    row.postId(),
                    row.title(),
                    row.summary(),
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
        int safeSize = normalizeSize(size, 10);
        List<String> titles = searchMapper.suggestTitles("%" + q.trim().toLowerCase() + "%", Math.min(safeSize * 3, 50));
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
        return new SuggestData(items);
    }

    private int normalizeSize(int size, int defaultSize) {
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, 20);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private Double computeDistanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        double earthRadius = 6371000D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2D) * Math.sin(dLng / 2D);
        double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        return earthRadius * c;
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
