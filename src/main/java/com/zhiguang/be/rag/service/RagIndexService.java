package com.zhiguang.be.rag.service;

import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.KnowPostFeedRow;
import com.zhiguang.be.rag.config.RagProperties;
import com.zhiguang.be.rag.model.RagReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 索引服务。
 * 参考 zhiguang 的实现思路，优先根据正文地址抓取内容并切成多个检索分片，
 * 同时结合内容指纹判断是否需要重建；如果正文暂时不可读，再回退到元数据切块，
 * 保证 linli 在轻量环境下依旧能稳定工作。
 */
@Service
public class RagIndexService {

    private static final String STATUS_PUBLISHED = "published";
    private static final String VISIBILITY_PUBLIC = "public";

    private final KnowPostMapper knowPostMapper;
    private final RagProperties ragProperties;
    private final RestTemplate restTemplate;
    private final Map<String, IndexedPost> indexStore = new ConcurrentHashMap<String, IndexedPost>();

    public RagIndexService(KnowPostMapper knowPostMapper, RagProperties ragProperties) {
        this.knowPostMapper = knowPostMapper;
        this.ragProperties = ragProperties;
        this.restTemplate = createRestTemplate(ragProperties);
    }

    /**
     * 确保指定内容已构建索引。
     */
    public int ensureIndexed(String postId) {
        if (!StringUtils.hasText(postId)) {
            return 0;
        }
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null || !isIndexable(entity)) {
            indexStore.remove(postId);
            return 0;
        }
        IndexedPost indexedPost = indexStore.get(postId);
        if (indexedPost != null && isUpToDate(entity, indexedPost)) {
            return indexedPost.chunks().size();
        }
        return reindexSinglePost(postId);
    }

    /**
     * 重建单篇内容索引。
     */
    public int reindexSinglePost(String postId) {
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null || !isIndexable(entity)) {
            indexStore.remove(postId);
            return 0;
        }

        String content = fetchContent(entity.contentUrl());
        List<IndexedChunk> chunks = buildChunks(entity, content);
        if (chunks.isEmpty()) {
            indexStore.remove(postId);
            return 0;
        }

        indexStore.put(
                postId,
                new IndexedPost(
                        postId,
                        entity.title(),
                        entity.latitude(),
                        entity.longitude(),
                        entity.contentSha256(),
                        entity.contentEtag(),
                        chunks
                )
        );
        return chunks.size();
    }

    /**
     * 执行检索并返回命中分片和引用信息。
     */
    public SearchResult search(String question, String postId, Double lat, Double lng, int topK) {
        int safeTopK = Math.max(1, Math.min(topK, ragProperties.getQuery().getMaxTopK()));
        String normalizedQuestion = normalizeText(question);
        Set<String> queryTokens = tokenize(normalizedQuestion);
        List<IndexedPost> candidates = StringUtils.hasText(postId)
                ? searchSinglePost(postId)
                : searchPublicPosts();

        if (candidates.isEmpty()) {
            return new SearchResult(List.of(), List.of());
        }

        List<ChunkHit> hits = new ArrayList<ChunkHit>();
        for (IndexedPost post : candidates) {
            for (IndexedChunk chunk : post.chunks()) {
                int score = score(normalizedQuestion, queryTokens, chunk)
                        + locationBoost(lat, lng, post.latitude(), post.longitude());
                if (score > 0) {
                    hits.add(new ChunkHit(post.postId(), post.title(), chunk.chunkId(), chunk.content(), score));
                }
            }
        }

        hits.sort(Comparator
                .comparingInt(ChunkHit::score).reversed()
                .thenComparing(ChunkHit::postId)
                .thenComparing(ChunkHit::chunkId));

        List<ChunkHit> selected = hits.stream()
                .limit(safeTopK)
                .collect(Collectors.toList());
        if (selected.isEmpty() && StringUtils.hasText(postId)) {
            selected = fallbackSinglePostChunks(candidates, safeTopK);
        }

        List<RagReference> references = selected.stream()
                .map(hit -> new RagReference(hit.postId(), hit.chunkId(), hit.title()))
                .collect(Collectors.toList());
        return new SearchResult(selected, references);
    }

    private RestTemplate createRestTemplate(RagProperties ragProperties) {
        int timeoutMillis = Math.max(1, ragProperties.getIndex().getFetchTimeoutSeconds()) * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return new RestTemplate(factory);
    }

    private boolean isIndexable(KnowPostEntity entity) {
        return entity != null
                && STATUS_PUBLISHED.equalsIgnoreCase(entity.status())
                && VISIBILITY_PUBLIC.equalsIgnoreCase(entity.visible());
    }

    private boolean isUpToDate(KnowPostEntity entity, IndexedPost indexedPost) {
        if (indexedPost == null) {
            return false;
        }
        if (hasText(entity.contentSha256()) && hasText(indexedPost.contentSha256())) {
            return entity.contentSha256().equals(indexedPost.contentSha256());
        }
        if (hasText(entity.contentEtag()) && hasText(indexedPost.contentEtag())) {
            return entity.contentEtag().equals(indexedPost.contentEtag());
        }
        return false;
    }

    private String fetchContent(String contentUrl) {
        if (!hasText(contentUrl)) {
            return null;
        }
        try {
            return restTemplate.getForObject(contentUrl, String.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<IndexedPost> searchSinglePost(String postId) {
        int chunkCount = ensureIndexed(postId);
        if (chunkCount <= 0) {
            return List.of();
        }
        IndexedPost indexedPost = indexStore.get(postId);
        return indexedPost == null ? List.of() : List.of(indexedPost);
    }

    private List<IndexedPost> searchPublicPosts() {
        List<IndexedPost> posts = new ArrayList<IndexedPost>();
        Set<String> seenPostIds = new HashSet<String>();
        int pageSize = Math.max(1, ragProperties.getQuery().getPublicSearchPageSize());
        int maxPages = Math.max(1, ragProperties.getQuery().getPublicSearchMaxPages());
        for (int page = 1; page <= maxPages; page++) {
            int offset = (page - 1) * pageSize;
            List<KnowPostFeedRow> rows = knowPostMapper.listFeedPublic(pageSize + 1, offset);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            List<KnowPostFeedRow> pageRows = rows.size() > pageSize ? rows.subList(0, pageSize) : rows;
            for (KnowPostFeedRow row : pageRows) {
                if (row == null || !hasText(row.postId()) || !seenPostIds.add(row.postId())) {
                    continue;
                }
                ensureIndexed(row.postId());
                IndexedPost indexedPost = indexStore.get(row.postId());
                if (indexedPost != null) {
                    posts.add(indexedPost);
                }
            }
            if (rows.size() <= pageSize) {
                break;
            }
        }
        return posts;
    }

    private List<ChunkHit> fallbackSinglePostChunks(List<IndexedPost> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        IndexedPost indexedPost = candidates.get(0);
        return indexedPost.chunks().stream()
                .sorted(Comparator
                        .comparingInt(IndexedChunk::weight).reversed()
                        .thenComparingInt(IndexedChunk::position))
                .limit(topK)
                .map(chunk -> new ChunkHit(indexedPost.postId(), indexedPost.title(), chunk.chunkId(), chunk.content(), 1))
                .collect(Collectors.toList());
    }

    private List<IndexedChunk> buildChunks(KnowPostEntity entity, String content) {
        List<IndexedChunk> chunks = new ArrayList<IndexedChunk>();
        int[] position = new int[]{0};
        if (hasText(content)) {
            appendMarkdownChunks(chunks, entity.postId(), entity.title(), content, position);
        }
        if (chunks.isEmpty() && ragProperties.getIndex().isFallbackToMetadata()) {
            appendMetadataChunks(chunks, entity, position);
        }
        return chunks;
    }

    private void appendMarkdownChunks(
            List<IndexedChunk> chunks,
            String postId,
            String title,
            String markdown,
            int[] position
    ) {
        List<String> sections = splitMarkdownSections(markdown);
        for (String section : sections) {
            int weight = section.startsWith("#") ? 4 : 3;
            List<String> parts = splitIntoChunks(section);
            for (String part : parts) {
                appendChunk(chunks, postId, title, "content", part, weight, position);
            }
        }
    }

    private void appendMetadataChunks(List<IndexedChunk> chunks, KnowPostEntity entity, int[] position) {
        appendChunk(chunks, entity.postId(), entity.title(), "title", entity.title(), 5, position);
        appendChunk(chunks, entity.postId(), entity.title(), "summary", entity.description(), 3, position);
        appendChunk(chunks, entity.postId(), entity.title(), "tags", entity.tagsJson(), 2, position);
        appendChunk(chunks, entity.postId(), entity.title(), "location", entity.address(), 1, position);
    }

    private void appendChunk(
            List<IndexedChunk> chunks,
            String postId,
            String title,
            String section,
            String rawValue,
            int weight,
            int[] position
    ) {
        String normalizedValue = normalizeContent(rawValue);
        if (!hasText(normalizedValue)) {
            return;
        }
        chunks.add(new IndexedChunk(
                postId + "#" + section + "#" + position[0],
                title,
                section,
                decorateSection(section, normalizedValue),
                tokenize(normalizedValue),
                weight,
                position[0]
        ));
        position[0]++;
    }

    private List<String> splitMarkdownSections(String markdown) {
        List<String> sections = new ArrayList<String>();
        String[] lines = markdown.split("\\r?\\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("#") && builder.length() > 0) {
                sections.add(builder.toString().trim());
                builder.setLength(0);
            }
            builder.append(line).append('\n');
        }
        if (builder.length() > 0) {
            sections.add(builder.toString().trim());
        }
        if (sections.isEmpty()) {
            sections.add(markdown.trim());
        }
        return sections;
    }

    private List<String> splitIntoChunks(String text) {
        List<String> parts = new ArrayList<String>();
        int maxChunkLength = Math.max(64, ragProperties.getIndex().getMaxChunkLength());
        int chunkStep = Math.max(16, Math.min(ragProperties.getIndex().getChunkStep(), maxChunkLength));
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + maxChunkLength, text.length());
            parts.add(text.substring(index, end));
            if (end >= text.length()) {
                break;
            }
            index += chunkStep;
        }
        return parts;
    }

    private String decorateSection(String section, String value) {
        if ("title".equals(section)) {
            return "标题：" + value;
        }
        if ("summary".equals(section)) {
            return "摘要：" + value;
        }
        if ("tags".equals(section)) {
            return "标签：" + value;
        }
        if ("location".equals(section)) {
            return "位置：" + value;
        }
        return value;
    }

    private Set<String> tokenize(String text) {
        if (!hasText(text)) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<String>();
        String normalized = normalizeText(text);
        for (String part : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+")) {
            if (!hasText(part)) {
                continue;
            }
            String token = part.trim();
            if (token.length() >= 2) {
                tokens.add(token);
            }
            if (containsCjk(token) && token.length() > 2) {
                for (int index = 0; index < token.length() - 1; index++) {
                    tokens.add(token.substring(index, index + 2));
                }
            }
        }
        return tokens;
    }

    private int score(String normalizedQuestion, Set<String> queryTokens, IndexedChunk chunk) {
        if (!hasText(normalizedQuestion)) {
            return 0;
        }
        String normalizedContent = normalizeText(chunk.content());
        String normalizedTitle = normalizeText(chunk.title());
        int score = 0;
        if (normalizedContent.contains(normalizedQuestion)) {
            score += 24;
        }
        if (hasText(normalizedTitle) && normalizedTitle.contains(normalizedQuestion)) {
            score += 12;
        }
        for (String token : queryTokens) {
            if (normalizedContent.contains(token)) {
                score += 4 + chunk.weight();
            } else if (chunk.keywords().contains(token)) {
                score += 2 + chunk.weight();
            }
            if (hasText(normalizedTitle) && normalizedTitle.contains(token)) {
                score += 3;
            }
        }
        return score + Math.max(0, 6 - chunk.position());
    }

    private int locationBoost(Double queryLat, Double queryLng, Double postLat, Double postLng) {
        if (queryLat == null || queryLng == null || postLat == null || postLng == null) {
            return 0;
        }
        double radius = Math.max(1D, ragProperties.getQuery().getNearbyBoostRadiusMeters());
        double distanceMeters = computeDistanceMeters(queryLat.doubleValue(), queryLng.doubleValue(), postLat.doubleValue(), postLng.doubleValue());
        if (distanceMeters > radius) {
            return 0;
        }
        double ratio = 1D - (distanceMeters / radius);
        return (int) Math.round(ratio * ragProperties.getQuery().getNearbyBoostScore());
    }

    private double computeDistanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2D) * Math.sin(dLng / 2D);
        double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        return earthRadius * c;
    }

    private boolean containsCjk(String token) {
        return token.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String normalizeContent(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.replaceAll("```[\\s\\S]*?```", " ")
                .replace("`", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeText(String value) {
        return normalizeContent(value).toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record IndexedPost(
            String postId,
            String title,
            Double latitude,
            Double longitude,
            String contentSha256,
            String contentEtag,
            List<IndexedChunk> chunks
    ) {
    }

    public record IndexedChunk(
            String chunkId,
            String title,
            String section,
            String content,
            Set<String> keywords,
            int weight,
            int position
    ) {
    }

    public record ChunkHit(
            String postId,
            String title,
            String chunkId,
            String content,
            int score
    ) {
    }

    public record SearchResult(
            List<ChunkHit> hits,
            List<RagReference> references
    ) {
    }
}
