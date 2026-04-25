package com.zhiguang.be.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch 搜索提供者。
 * 参考 zhiguang 的 ES 搜索思路，支持高亮、search_after 和联想建议。
 */
@Component
@ConditionalOnBean(ElasticsearchClient.class)
public class EsSearchProvider implements SearchProvider {

    private final ElasticsearchClient elasticsearchClient;
    private final SearchProperties searchProperties;

    public EsSearchProvider(ElasticsearchClient elasticsearchClient, SearchProperties searchProperties) {
        this.elasticsearchClient = elasticsearchClient;
        this.searchProperties = searchProperties;
    }

    @Override
    public String provider() {
        return "es";
    }

    @Override
    @SuppressWarnings("unchecked")
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
        int safeSize = normalizePageSize(size);
        List<FieldValue> afterValues = parseAfter(searchAfter);

        SearchResponse<Map<String, Object>> response;
        try {
            response = elasticsearchClient.search(search -> {
                search.index(searchProperties.getEs().getIndex())
                        .size(safeSize + 1)
                        .query(query -> query.bool(bool -> {
                            bool.must(must -> must.multiMatch(multiMatch -> multiMatch
                                    .query(q.trim())
                                    .fields("title^4", "summary^2", "tags_text")
                            ));
                            bool.filter(filter -> filter.term(term -> term.field("status").value("published")));
                            bool.filter(filter -> filter.term(term -> term.field("visible").value("public")));
                            if (hasText(tag)) {
                                bool.filter(filter -> filter.term(term -> term.field("tags").value(tag.trim())));
                            }
                            if (lat != null && lng != null && radius != null && radius.doubleValue() > 0D) {
                                bool.filter(filter -> filter.geoDistance(geo -> geo
                                        .field("location")
                                        .location(location -> location.latlon(point -> point.lat(lat).lon(lng)))
                                        .distance(Math.max(radius.doubleValue(), 1D) + "m")
                                ));
                            }
                            return bool;
                        }))
                        .highlight(highlight -> highlight
                                .fields("title", field -> field)
                                .fields("summary", field -> field)
                        )
                        .sort(sort -> sort.score(score -> score.order(SortOrder.Desc)))
                        .sort(sort -> sort.field(field -> field.field("is_top").order(SortOrder.Desc)))
                        .sort(sort -> sort.field(field -> field.field("publish_time").order(SortOrder.Desc).format("epoch_millis")))
                        .sort(sort -> sort.field(field -> field.field("content_id").order(SortOrder.Desc)));
                if (afterValues != null && !afterValues.isEmpty()) {
                    search.searchAfter(afterValues);
                }
                return search;
            }, (Class<Map<String, Object>>) (Class<?>) Map.class);
        } catch (Exception ex) {
            return new SearchPostsData(
                    Collections.<SearchResultItem>emptyList(),
                    new CursorPageMeta(safePage, safeSize, false, null, List.of())
            );
        }

        List<SearchResultItem> items = new ArrayList<SearchResultItem>();
        List<Hit<Map<String, Object>>> hits = response.hits() == null
                ? Collections.<Hit<Map<String, Object>>>emptyList()
                : response.hits().hits();
        for (Hit<Map<String, Object>> hit : hits) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }
            Double distanceMeters = computeDistanceMeters(lat, lng, asDouble(source.get("latitude")), asDouble(source.get("longitude")));
            items.add(new SearchResultItem(
                    asString(source.get("content_id")),
                    asString(source.get("title")),
                    buildSnippet(hit, asString(source.get("summary"))),
                    hit.score() == null ? 0D : hit.score().doubleValue(),
                    distanceMeters,
                    encodeSortValues(hit.sort())
            ));
        }

        boolean hasMore = items.size() > safeSize;
        List<SearchResultItem> pageItems = hasMore ? new ArrayList<SearchResultItem>(items.subList(0, safeSize)) : items;
        String nextAfter = null;
        List<String> nextAfterValues = List.of();
        if (!pageItems.isEmpty() && hits.size() >= pageItems.size()) {
            Hit<Map<String, Object>> lastHit = hits.get(pageItems.size() - 1);
            nextAfterValues = encodeSortValues(lastHit.sort());
            nextAfter = encodeAfter(nextAfterValues);
        }

        return new SearchPostsData(
                pageItems,
                new CursorPageMeta(safePage, safeSize, hasMore, nextAfter, nextAfterValues)
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public SuggestData suggest(String q, int size) {
        int safeSize = normalizeSuggestSize(size);
        SearchResponse<Map<String, Object>> response;
        try {
            response = elasticsearchClient.search(search -> search
                            .index(searchProperties.getEs().getIndex())
                            .suggest(suggest -> suggest
                                    .suggesters("title_suggest", fieldSuggester -> fieldSuggester
                                            .prefix(q.trim())
                                            .completion(completion -> completion.field("title_suggest").size(safeSize))
                                    )
                            ),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
        } catch (Exception ex) {
            return new SuggestData(Collections.<SuggestItem>emptyList());
        }

        Set<String> deduplicated = new LinkedHashSet<String>();
        List<SuggestItem> items = new ArrayList<SuggestItem>();
        Map<String, List<Suggestion<Map<String, Object>>>> suggestMap = response.suggest();
        List<Suggestion<Map<String, Object>>> suggestions = suggestMap == null ? null : suggestMap.get("title_suggest");
        if (suggestions != null) {
            for (Suggestion<Map<String, Object>> suggestion : suggestions) {
                if (suggestion.completion() == null || suggestion.completion().options() == null) {
                    continue;
                }
                suggestion.completion().options().forEach(option -> {
                    String text = option.text();
                    if (text != null) {
                        String normalized = text.trim();
                        if (!normalized.isEmpty() && deduplicated.add(normalized)) {
                            items.add(new SuggestItem(normalized, 1.0D, "title"));
                        }
                    }
                });
                if (items.size() >= safeSize) {
                    break;
                }
            }
        }

        if (items.size() > safeSize) {
            return new SuggestData(new ArrayList<SuggestItem>(items.subList(0, safeSize)));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<FieldValue> parseAfter(String searchAfter) {
        if (!hasText(searchAfter)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(searchAfter), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",", 4);
            if (parts.length != 4) {
                return null;
            }
            List<FieldValue> values = new ArrayList<FieldValue>(4);
            values.add(FieldValue.of(Double.parseDouble(parts[0])));
            values.add(FieldValue.of(Long.parseLong(parts[1])));
            values.add(FieldValue.of(Long.parseLong(parts[2])));
            values.add(FieldValue.of(Long.parseLong(parts[3])));
            return values;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> encodeSortValues(List<FieldValue> sortValues) {
        if (sortValues == null || sortValues.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<String>(sortValues.size());
        for (FieldValue fieldValue : sortValues) {
            values.add(fieldValueToString(fieldValue));
        }
        return values;
    }

    private String encodeAfter(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join(",", values).getBytes(StandardCharsets.UTF_8));
    }

    private String fieldValueToString(FieldValue fieldValue) {
        if (fieldValue == null) {
            return "";
        }
        if (fieldValue.isDouble()) {
            return String.valueOf(fieldValue.doubleValue());
        }
        if (fieldValue.isLong()) {
            return String.valueOf(fieldValue.longValue());
        }
        if (fieldValue.isString()) {
            return fieldValue.stringValue();
        }
        if (fieldValue.isBoolean()) {
            return String.valueOf(fieldValue.booleanValue());
        }
        return fieldValue.toString();
    }

    private String buildSnippet(Hit<Map<String, Object>> hit, String fallback) {
        if (hit.highlight() != null) {
            List<String> titleHighlights = hit.highlight().get("title");
            if (titleHighlights != null && !titleHighlights.isEmpty()) {
                return String.join(" ", titleHighlights);
            }
            List<String> summaryHighlights = hit.highlight().get("summary");
            if (summaryHighlights != null && !summaryHighlights.isEmpty()) {
                return String.join(" ", summaryHighlights);
            }
        }
        if (fallback == null) {
            return "";
        }
        int snippetLength = Math.max(searchProperties.getSnippetLength(), 1);
        return fallback.length() <= snippetLength ? fallback : fallback.substring(0, snippetLength) + "...";
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private Double computeDistanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        double earthRadius = 6371000D;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLng / 2D) * Math.sin(dLng / 2D);
        double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        return earthRadius * c;
    }
}
