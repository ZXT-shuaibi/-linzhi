package com.zhiguang.be.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.ScoreSort;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.util.NamedValue;
import com.zhiguang.be.common.geo.GeoDistances;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.service.InteractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * Elasticsearch 搜索提供者。
 */
@Component
@ConditionalOnBean(ElasticsearchClient.class)
public class EsSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(EsSearchProvider.class);

    private static final double TITLE_EXACT_BOOST = 8.0D;
    private static final double TITLE_PHRASE_BOOST = 4.0D;
    private static final double TOP_WEIGHT = 2.0D;
    private static final double LIKE_WEIGHT = 1.5D;
    private static final double FAVORITE_WEIGHT = 1.0D;
    private static final double NEARBY_WEIGHT = 1.2D;
    private static final int DEFAULT_NEARBY_BOOST_RADIUS_METERS = 3000;

    private final ElasticsearchClient elasticsearchClient;
    private final SearchProperties searchProperties;
    private final InteractionService interactionService;

    public EsSearchProvider(
            ElasticsearchClient elasticsearchClient,
            SearchProperties searchProperties,
            InteractionService interactionService
    ) {
        this.elasticsearchClient = elasticsearchClient;
        this.searchProperties = searchProperties;
        this.interactionService = interactionService;
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
            long currentUserId,
            Double lat,
            Double lng,
            Double radius,
            String tag
    ) {
        int safePage = Math.max(page, 1);
        int safeSize = normalizePageSize(size);
        String queryText = q == null ? "" : q.trim();
        String tagText = hasText(tag) ? tag.trim() : null;
        List<FieldValue> afterValues = parseAfter(searchAfter);

        SearchResponse<Map<String, Object>> response;
        try {
            response = executeSearch(queryText, safeSize, tagText, afterValues, lat, lng, radius);
        } catch (Exception ex) {
            log.warn("es search failed, q={}, page={}, size={}, tag={}", queryText, safePage, safeSize, tagText, ex);
            return new SearchPostsData(
                    Collections.<SearchResultItem>emptyList(),
                    new CursorPageMeta(safePage, safeSize, false, null, List.of())
            );
        }

        List<SearchResultItem> items = new ArrayList<SearchResultItem>();
        List<Hit<Map<String, Object>>> hits = response.hits() == null
                ? Collections.<Hit<Map<String, Object>>>emptyList()
                : response.hits().hits();
        Map<String, InteractionSummary> interactionMap = loadInteractionMap(currentUserId, hits);
        for (Hit<Map<String, Object>> hit : hits) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }
            String postId = asString(source.get("content_id"));
            if (!hasText(postId)) {
                continue;
            }
            InteractionSummary summary = interactionMap.get(postId);
            Double distanceMeters = computeDistanceMeters(lat, lng, asDouble(source.get("latitude")), asDouble(source.get("longitude")));
            items.add(new SearchResultItem(
                    postId,
                    asString(source.get("title")),
                    buildSnippet(hit, asString(source.get("summary"))),
                    asString(source.get("cover_url")),
                    asStringList(source.get("tags")),
                    asString(source.get("author_id")),
                    asString(source.get("author_nickname")),
                    asString(source.get("author_avatar")),
                    asString(source.get("author_tag_json")),
                    summary == null ? defaultLong(asLong(source.get("like_count"))) : summary.getLikeCount(),
                    summary == null ? defaultLong(asLong(source.get("favorite_count"))) : summary.getFavoriteCount(),
                    currentUserId > 0L && summary != null ? summary.isViewerLiked() : null,
                    currentUserId > 0L && summary != null ? summary.isViewerFavorited() : null,
                    asBoolean(source.get("is_top")),
                    asInstant(source.get("publish_time")),
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
        if (!hasText(q)) {
            return new SuggestData(Collections.<SuggestItem>emptyList());
        }
        int safeSize = normalizeSuggestSize(size);
        SearchResponse<Map<String, Object>> response;
        try {
            response = executeSuggest(q.trim(), safeSize);
        } catch (Exception ex) {
            log.warn("es suggest failed, q={}, size={}", q, safeSize, ex);
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
                            items.add(new SuggestItem(normalized, option.score() == null ? 1.0D : option.score().doubleValue(), "title"));
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

    /**
     * 执行帖子检索查询，统一收口 ES function_score、排序和高亮配置。
     */
    @SuppressWarnings("unchecked")
    private SearchResponse<Map<String, Object>> executeSearch(
            String queryText,
            int safeSize,
            String tagText,
            List<FieldValue> afterValues,
            Double lat,
            Double lng,
            Double radius
    ) throws Exception {
        SearchRequest.Builder search = new SearchRequest.Builder()
                .index(searchProperties.getEs().getIndex())
                .size(safeSize + 1)
                .query(buildFunctionScoreQuery(queryText, tagText, lat, lng, radius))
                .highlight(buildSearchHighlight())
                .sort(buildSearchSorts());
        if (afterValues != null && !afterValues.isEmpty()) {
            search.searchAfter(afterValues);
        }
        return elasticsearchClient.search(search.build(), (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    /**
     * 构建 ES function_score 查询，避免过深的链式 lambda 触发 IDE 泛型推断误报。
     */
    private FunctionScoreQuery buildFunctionScoreQuery(
            String queryText,
            String tagText,
            Double lat,
            Double lng,
            Double radius
    ) {
        FunctionScoreQuery.Builder functionScore = new FunctionScoreQuery.Builder()
                .query(buildBaseSearchQuery(queryText, tagText, lat, lng, radius))
                .functions(fn -> fn
                        .filter(filter -> filter.term(term -> term.field("is_top").value(1)))
                        .weight(TOP_WEIGHT))
                .functions(fn -> fn
                        .fieldValueFactor(fieldValueFactor -> fieldValueFactor
                                .field("like_count")
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0))
                        .weight(LIKE_WEIGHT))
                .functions(fn -> fn
                        .fieldValueFactor(fieldValueFactor -> fieldValueFactor
                                .field("favorite_count")
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0))
                        .weight(FAVORITE_WEIGHT))
                .boostMode(FunctionBoostMode.Sum)
                .scoreMode(FunctionScoreMode.Sum);
        if (lat != null && lng != null) {
            int nearbyRadius = resolveNearbyBoostRadius(radius);
            functionScore.functions(fn -> fn
                    .filter(filter -> filter.geoDistance(geo -> geo
                            .field("location")
                            .location(location -> location.latlon(point -> point.lat(lat).lon(lng)))
                            .distance(nearbyRadius + "m")))
                    .weight(NEARBY_WEIGHT));
        }
        return functionScore.build();
    }

    /**
     * 构建搜索高亮配置，避免链式 fields lambda 继续触发 IDE 误报。
     */
    private Highlight buildSearchHighlight() {
        HighlightField titleField = new HighlightField.Builder()
                .numberOfFragments(1)
                .build();
        HighlightField summaryField = new HighlightField.Builder()
                .fragmentSize(Math.max(searchProperties.getSnippetLength(), 40))
                .numberOfFragments(1)
                .build();
        return new Highlight.Builder()
                .fields(
                        NamedValue.of("title", titleField),
                        NamedValue.of("summary", summaryField)
                )
                .build();
    }

    /**
     * 构建统一排序规则，显式对象构造比多层 sort lambda 更稳定。
     */
    private List<SortOptions> buildSearchSorts() {
        List<SortOptions> sorts = new ArrayList<SortOptions>(4);
        sorts.add(new SortOptions.Builder()
                .score(new ScoreSort.Builder().order(SortOrder.Desc).build())
                .build());
        sorts.add(new SortOptions.Builder()
                .field(new FieldSort.Builder()
                        .field("publish_time")
                        .order(SortOrder.Desc)
                        .format("epoch_millis")
                        .build())
                .build());
        sorts.add(new SortOptions.Builder()
                .field(new FieldSort.Builder()
                        .field("like_count")
                        .order(SortOrder.Desc)
                        .build())
                .build());
        sorts.add(new SortOptions.Builder()
                .field(new FieldSort.Builder()
                        .field("content_id")
                        .order(SortOrder.Desc)
                        .build())
                .build());
        return sorts;
    }

    /**
     * 构建帖子搜索基础查询，承载全文检索、公开态过滤、标签过滤和位置过滤。
     */
    private Query buildBaseSearchQuery(
            String queryText,
            String tagText,
            Double lat,
            Double lng,
            Double radius
    ) {
        BoolQuery.Builder bool = new BoolQuery.Builder()
                .must(must -> must.multiMatch(multiMatch -> multiMatch
                        .query(queryText)
                        .fields("title^3", "summary^1.5", "tags_text^2")))
                .filter(filter -> filter.term(term -> term.field("status").value("published")))
                .filter(filter -> filter.term(term -> term.field("visible").value("public")))
                .should(should -> should.term(term -> term
                        .field("title_keyword")
                        .value(queryText)
                        .boost((float) TITLE_EXACT_BOOST)))
                .should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                        .field("title")
                        .query(queryText)
                        .boost((float) TITLE_PHRASE_BOOST)));
        if (tagText != null) {
            bool.filter(filter -> filter.term(term -> term.field("tags").value(tagText)));
        }
        if (lat != null && lng != null && radius != null && radius.doubleValue() > 0D) {
            bool.filter(filter -> filter.geoDistance(geo -> geo
                    .field("location")
                    .location(location -> location.latlon(point -> point.lat(lat).lon(lng)))
                    .distance(Math.max(radius.doubleValue(), 1D) + "m")));
        }
        return new Query(bool.build());
    }

    /**
     * 执行联想建议查询。
     */
    @SuppressWarnings("unchecked")
    private SearchResponse<Map<String, Object>> executeSuggest(String queryText, int safeSize) throws Exception {
        return elasticsearchClient.search(search -> search
                        .index(searchProperties.getEs().getIndex())
                        .suggest(suggest -> suggest
                                .suggesters("title_suggest", fieldSuggester -> fieldSuggester
                                        .prefix(queryText)
                                        .completion(completion -> completion
                                                .field("title_suggest")
                                                .size(safeSize)
                                                .skipDuplicates(true)
                                        )
                                )
                        ),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
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

    private int resolveNearbyBoostRadius(Double radius) {
        if (radius == null || radius.doubleValue() <= 0D) {
            return DEFAULT_NEARBY_BOOST_RADIUS_METERS;
        }
        return (int) Math.max(300D, Math.min(radius.doubleValue(), DEFAULT_NEARBY_BOOST_RADIUS_METERS));
    }

    private Map<String, InteractionSummary> loadInteractionMap(long currentUserId, List<Hit<Map<String, Object>>> hits) {
        List<Long> targetIds = new ArrayList<Long>();
        for (Hit<Map<String, Object>> hit : hits) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }
            Long targetId = asLong(source.get("content_id"));
            if (targetId != null) {
                targetIds.add(targetId);
            }
        }
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        try {
            return interactionService.summaryBatch(currentUserId, "post", targetIds);
        } catch (Exception ex) {
            log.debug("load interaction summary for es search failed, currentUserId={}, targetIds={}", currentUserId, targetIds, ex);
            return Map.of();
        }
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
        List<String> fragments = new ArrayList<String>();
        if (hit.highlight() != null) {
            List<String> titleHighlights = hit.highlight().get("title");
            if (titleHighlights != null && !titleHighlights.isEmpty()) {
                fragments.addAll(titleHighlights);
            }
            List<String> summaryHighlights = hit.highlight().get("summary");
            if (summaryHighlights != null && !summaryHighlights.isEmpty()) {
                fragments.addAll(summaryHighlights);
            }
        }
        if (!fragments.isEmpty()) {
            return String.join(" ", fragments);
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

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value.longValue();
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

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "0".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private Instant asInstant(Object value) {
        Long epochMillis = asLong(value);
        if (epochMillis == null || epochMillis.longValue() <= 0L) {
            return null;
        }
        return Instant.ofEpochMilli(epochMillis.longValue());
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<String>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String normalized = String.valueOf(item).trim();
                if (!normalized.isEmpty() && !values.contains(normalized)) {
                    values.add(normalized);
                }
            }
            return values;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<String>();
        String normalized = raw.replace('[', ' ').replace(']', ' ').replace('"', ' ').replace('\'', ' ').trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        for (String part : normalized.split(",")) {
            String item = part.trim();
            if (!item.isEmpty() && !values.contains(item)) {
                values.add(item);
            }
        }
        return values;
    }

    private Double computeDistanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        return GeoDistances.haversineMeters(lat1, lng1, lat2, lng2);
    }
}
