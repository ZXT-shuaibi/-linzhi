package com.zhiguang.be.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.service.InteractionService;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索索引维护服务。
 */
@Service
@ConditionalOnBean(ElasticsearchClient.class)
public class SearchIndexService {

    private final ElasticsearchClient elasticsearchClient;
    private final SearchMapper searchMapper;
    private final SearchProperties searchProperties;
    private final InteractionService interactionService;
    private final ObjectMapper objectMapper;

    public SearchIndexService(
            ElasticsearchClient elasticsearchClient,
            SearchMapper searchMapper,
            SearchProperties searchProperties,
            InteractionService interactionService,
            ObjectMapper objectMapper
    ) {
        this.elasticsearchClient = elasticsearchClient;
        this.searchMapper = searchMapper;
        this.searchProperties = searchProperties;
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        ensureIndex();
        if (searchProperties.getEs().isAutoRebuildOnStartup()) {
            rebuildAll();
        }
    }

    /**
     * 启动时确保索引存在。
     */
    public void ensureIndex() {
        if (!searchProperties.getEs().isAutoCreateIndex()) {
            return;
        }
        try {
            boolean exists = elasticsearchClient.indices().exists(request -> request.index(indexName())).value();
            if (exists) {
                return;
            }
            elasticsearchClient.indices().create(request -> request
                    .index(indexName())
                    .mappings(mappings -> {
                        mappings.properties("content_id", property -> property.long_(value -> value));
                        if (searchProperties.getEs().isUseIkAnalyzer()) {
                            mappings.properties("title", property -> property.text(text -> text.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                            mappings.properties("summary", property -> property.text(text -> text.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                            mappings.properties("tags_text", property -> property.text(text -> text.analyzer("ik_max_word").searchAnalyzer("ik_smart")));
                        } else {
                            mappings.properties("title", property -> property.text(text -> text));
                            mappings.properties("summary", property -> property.text(text -> text));
                            mappings.properties("tags_text", property -> property.text(text -> text));
                        }
                        mappings.properties("title_keyword", property -> property.keyword(keyword -> keyword));
                        mappings.properties("tags", property -> property.keyword(keyword -> keyword));
                        mappings.properties("cover_url", property -> property.keyword(keyword -> keyword));
                        mappings.properties("author_id", property -> property.long_(value -> value));
                        mappings.properties("author_nickname", property -> property.keyword(keyword -> keyword));
                        mappings.properties("author_avatar", property -> property.keyword(keyword -> keyword));
                        mappings.properties("author_tag_json", property -> property.keyword(keyword -> keyword));
                        mappings.properties("is_top", property -> property.integer(value -> value));
                        mappings.properties("like_count", property -> property.long_(value -> value));
                        mappings.properties("favorite_count", property -> property.long_(value -> value));
                        mappings.properties("publish_time", property -> property.date(value -> value));
                        mappings.properties("latitude", property -> property.double_(value -> value));
                        mappings.properties("longitude", property -> property.double_(value -> value));
                        mappings.properties("location", property -> property.geoPoint(value -> value));
                        mappings.properties("status", property -> property.keyword(keyword -> keyword));
                        mappings.properties("visible", property -> property.keyword(keyword -> keyword));
                        mappings.properties("title_suggest", property -> property.completion(value -> value));
                        return mappings;
                    })
            );
        } catch (Exception ignored) {
            // ES 不可用时不阻断应用启动，仍可回退到 db provider。
        }
    }

    /**
     * 全量回灌历史内容到 ES。
     */
    public void rebuildAll() {
        int batchSize = Math.max(searchProperties.getEs().getRebuildBatchSize(), 1);
        int offset = 0;
        while (true) {
            List<SearchIndexDocumentRow> rows = searchMapper.listSearchIndexDocumentRows(batchSize, offset);
            if (rows == null || rows.isEmpty()) {
                return;
            }

            Map<Long, InteractionSummary> interactionMap = loadInteractionMap(rows);
            for (SearchIndexDocumentRow row : rows) {
                syncRow(row, interactionMap.get(row.postId()));
            }
            offset += rows.size();
        }
    }

    /**
     * 同步单篇内容到搜索索引。
     */
    public void syncPost(Long postId) {
        if (postId == null) {
            return;
        }
        syncRow(searchMapper.findSearchIndexDocumentRow(postId), loadInteractionSummary(postId));
    }

    /**
     * 从搜索索引中删除内容。
     */
    public void deletePost(Long postId) {
        if (postId == null) {
            return;
        }
        try {
            elasticsearchClient.delete(request -> request
                    .index(indexName())
                    .id(String.valueOf(postId))
                    .refresh(Refresh.WaitFor)
            );
        } catch (Exception ignored) {
            // 删除失败不阻断主链路，后续可通过重建修正。
        }
    }

    /**
     * 当前是否允许内容模块执行本地直连同步。
     */
    public boolean isLocalSyncEnabled() {
        return searchProperties.getOutbox().isLocalSyncEnabled();
    }

    private void syncRow(SearchIndexDocumentRow row, InteractionSummary interactionSummary) {
        if (row == null || row.postId() == null) {
            return;
        }
        if (!"published".equals(row.status()) || !"public".equals(row.visible())) {
            deletePost(row.postId());
            return;
        }

        List<String> tags = parseStringList(row.tagsJson());
        Map<String, Object> document = buildDocument(row, tags, interactionSummary);
        try {
            elasticsearchClient.index(request -> request
                    .index(indexName())
                    .id(String.valueOf(row.postId()))
                    .document(document)
                    .refresh(Refresh.WaitFor)
            );
        } catch (Exception ignored) {
            // 不阻断内容主流程，ES 异常可由后续重建修正。
        }
    }

    private Map<String, Object> buildDocument(
            SearchIndexDocumentRow row,
            List<String> tags,
            InteractionSummary interactionSummary
    ) {
        long likeCount = interactionSummary == null ? 0L : interactionSummary.getLikeCount();
        long favoriteCount = interactionSummary == null ? 0L : interactionSummary.getFavoriteCount();

        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("content_id", row.postId());
        document.put("title", row.title());
        document.put("title_keyword", row.title());
        document.put("summary", row.summary());
        document.put("tags", tags);
        document.put("tags_text", String.join(" ", tags));
        document.put("cover_url", firstListValue(parseStringList(row.imgUrlsJson())));
        document.put("author_id", row.authorId());
        document.put("author_nickname", row.authorNickname());
        document.put("author_avatar", row.authorAvatar());
        document.put("author_tag_json", row.authorTagJson());
        document.put("is_top", row.isTop() == null ? 0 : row.isTop());
        document.put("like_count", likeCount);
        document.put("favorite_count", favoriteCount);
        document.put("status", row.status());
        document.put("visible", row.visible());
        document.put("title_suggest", buildSuggestDocument(row, tags, likeCount, favoriteCount));
        if (row.publishTime() != null) {
            document.put("publish_time", row.publishTime().toEpochMilli());
        }
        if (row.latitude() != null) {
            document.put("latitude", row.latitude());
        }
        if (row.longitude() != null) {
            document.put("longitude", row.longitude());
        }
        if (row.latitude() != null && row.longitude() != null) {
            Map<String, Object> location = new LinkedHashMap<String, Object>();
            location.put("lat", row.latitude());
            location.put("lon", row.longitude());
            document.put("location", location);
        }
        return document;
    }

    private Map<String, Object> buildSuggestDocument(
            SearchIndexDocumentRow row,
            List<String> tags,
            long likeCount,
            long favoriteCount
    ) {
        Map<String, Object> suggest = new LinkedHashMap<String, Object>();
        suggest.put("input", row.suggestInputs(tags));
        suggest.put("weight", normalizeSuggestWeight(row.isTop(), likeCount, favoriteCount));
        return suggest;
    }

    private int normalizeSuggestWeight(Integer isTop, long likeCount, long favoriteCount) {
        long weight = likeCount + favoriteCount;
        if (isTop != null && isTop.intValue() == 1) {
            weight += 50L;
        }
        weight += 10L;
        return (int) Math.min(weight, Integer.MAX_VALUE);
    }

    private Map<Long, InteractionSummary> loadInteractionMap(List<SearchIndexDocumentRow> rows) {
        List<Long> targetIds = new ArrayList<Long>();
        for (SearchIndexDocumentRow row : rows) {
            if (row != null && row.postId() != null) {
                targetIds.add(row.postId());
            }
        }
        if (targetIds.isEmpty()) {
            return Map.of();
        }

        Map<String, InteractionSummary> rawMap = interactionService.summaryBatch(0L, "post", targetIds);
        if (rawMap == null || rawMap.isEmpty()) {
            return Map.of();
        }

        Map<Long, InteractionSummary> interactionMap = new LinkedHashMap<Long, InteractionSummary>();
        for (Map.Entry<String, InteractionSummary> entry : rawMap.entrySet()) {
            try {
                interactionMap.put(Long.valueOf(entry.getKey()), entry.getValue());
            } catch (Exception ignored) {
                // 忽略异常键，避免影响整批回灌。
            }
        }
        return interactionMap;
    }

    private InteractionSummary loadInteractionSummary(Long postId) {
        try {
            return interactionService.summary(0L, "post", postId.longValue());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            List<String> normalized = new ArrayList<String>();
            for (String item : parsed) {
                if (item == null) {
                    continue;
                }
                String trimmed = item.trim();
                if (!trimmed.isEmpty() && !normalized.contains(trimmed)) {
                    normalized.add(trimmed);
                }
            }
            return normalized;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String firstListValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private String indexName() {
        return searchProperties.getEs().getIndex();
    }
}
