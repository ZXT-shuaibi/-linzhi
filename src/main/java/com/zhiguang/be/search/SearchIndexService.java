package com.zhiguang.be.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索索引维护服务。
 * 负责初始化索引、全量回灌，以及单篇内容的 upsert / delete。
 */
@Service
@ConditionalOnBean(ElasticsearchClient.class)
public class SearchIndexService {

    private final ElasticsearchClient elasticsearchClient;
    private final SearchMapper searchMapper;
    private final SearchProperties searchProperties;
    private final ObjectMapper objectMapper;

    public SearchIndexService(
            ElasticsearchClient elasticsearchClient,
            SearchMapper searchMapper,
            SearchProperties searchProperties,
            ObjectMapper objectMapper
    ) {
        this.elasticsearchClient = elasticsearchClient;
        this.searchMapper = searchMapper;
        this.searchProperties = searchProperties;
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
     * 应用启动时确保索引存在。
     * 默认不强依赖 IK，让玩具项目在本地更容易跑起来。
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
                        mappings.properties("tags", property -> property.keyword(keyword -> keyword));
                        mappings.properties("is_top", property -> property.integer(value -> value));
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
            // ES 不可用时不阻断应用启动，仍保留 db provider 兜底。
        }
    }

    /**
     * 首次切到 ES 时可用来全量回灌历史内容。
     */
    public void rebuildAll() {
        int batchSize = Math.max(searchProperties.getEs().getRebuildBatchSize(), 1);
        int offset = 0;
        while (true) {
            List<SearchIndexDocumentRow> rows = searchMapper.listSearchIndexDocumentRows(batchSize, offset);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            for (SearchIndexDocumentRow row : rows) {
                syncRow(row);
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
        syncRow(searchMapper.findSearchIndexDocumentRow(postId));
    }

    /**
     * 从搜索索引中删除指定内容。
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
            // 删除失败不影响主链路，后续仍可通过重建修正。
        }
    }

    private void syncRow(SearchIndexDocumentRow row) {
        if (row == null || row.postId() == null) {
            return;
        }
        if (!"published".equals(row.status()) || !"public".equals(row.visible())) {
            deletePost(row.postId());
            return;
        }

        List<String> tags = parseTags(row.tagsJson());
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("content_id", row.postId());
        document.put("title", row.title());
        document.put("summary", row.summary());
        document.put("tags", tags);
        document.put("tags_text", String.join(" ", tags));
        document.put("is_top", row.isTop() == null ? 0 : row.isTop());
        document.put("status", row.status());
        document.put("visible", row.visible());
        document.put("title_suggest", row.title());
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

        try {
            elasticsearchClient.index(request -> request
                    .index(indexName())
                    .id(String.valueOf(row.postId()))
                    .document(document)
                    .refresh(Refresh.WaitFor)
            );
        } catch (Exception ignored) {
            // 不阻断内容主流程，ES 异常由后续重建修正。
        }
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
            List<String> normalized = new ArrayList<String>();
            for (String tag : parsed) {
                if (tag == null) {
                    continue;
                }
                String trimmed = tag.trim();
                if (!trimmed.isEmpty() && !normalized.contains(trimmed)) {
                    normalized.add(trimmed);
                }
            }
            return normalized;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String indexName() {
        return searchProperties.getEs().getIndex();
    }
}
