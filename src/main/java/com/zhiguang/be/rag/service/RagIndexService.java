package com.zhiguang.be.rag.service;

import com.zhiguang.be.content.dto.PostAuthor;
import com.zhiguang.be.content.dto.PostCard;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostLocation;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.service.ContentService;
import com.zhiguang.be.rag.config.RagProperties;
import com.zhiguang.be.rag.model.RagReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
 * 轻量内存版 RAG 索引服务。
 * 当前阶段不直接引入向量库，但会把索引切块、召回范围和位置加权整理成可配置形态，
 * 让后续切到真正的向量检索时不需要改控制器和服务接口。
 */
@Service
public class RagIndexService {

    private final ContentService contentService;
    private final RagProperties ragProperties;
    private final Map<String, IndexedPost> indexStore = new ConcurrentHashMap<String, IndexedPost>();

    public RagIndexService(ContentService contentService, RagProperties ragProperties) {
        this.contentService = contentService;
        this.ragProperties = ragProperties;
    }

    public int ensureIndexed(String postId) {
        if (!StringUtils.hasText(postId)) {
            return 0;
        }
        IndexedPost indexedPost = indexStore.get(postId);
        if (indexedPost != null) {
            return indexedPost.chunks().size();
        }
        return reindexSinglePost(postId);
    }

    public int reindexSinglePost(String postId) {
        PostDetail detail = contentService.getDetail(postId, null);
        List<IndexedChunk> chunks = buildChunks(detail);
        PostLocation location = detail.location();
        indexStore.put(
                postId,
                new IndexedPost(
                        postId,
                        detail.title(),
                        location == null ? null : location.lat(),
                        location == null ? null : location.lng(),
                        chunks
                )
        );
        return chunks.size();
    }

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
        for (int page = 1; page <= ragProperties.getQuery().getPublicSearchMaxPages(); page++) {
            PostPageData pageData = contentService.getPublicFeed(null, page, ragProperties.getQuery().getPublicSearchPageSize());
            if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) {
                break;
            }
            for (PostCard item : pageData.items()) {
                if (item == null || !StringUtils.hasText(item.postId()) || !seenPostIds.add(item.postId())) {
                    continue;
                }
                ensureIndexed(item.postId());
                IndexedPost indexedPost = indexStore.get(item.postId());
                if (indexedPost != null) {
                    posts.add(indexedPost);
                }
            }
            if (!pageData.hasMore()) {
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
                .sorted(Comparator.comparingInt(IndexedChunk::weight).reversed().thenComparing(IndexedChunk::chunkId))
                .limit(topK)
                .map(chunk -> new ChunkHit(indexedPost.postId(), indexedPost.title(), chunk.chunkId(), chunk.content(), 1))
                .collect(Collectors.toList());
    }

    private List<IndexedChunk> buildChunks(PostDetail detail) {
        List<IndexedChunk> chunks = new ArrayList<IndexedChunk>();
        appendSectionChunks(chunks, detail.postId(), "title", detail.title(), 4);
        appendSectionChunks(chunks, detail.postId(), "summary", detail.summary(), 3);
        appendSectionChunks(chunks, detail.postId(), "tags", detail.tags() == null ? null : String.join(" ", detail.tags()), 2);
        appendAuthorChunks(chunks, detail.postId(), detail.author());
        appendLocationChunks(chunks, detail.postId(), detail.location());

        if (chunks.isEmpty()) {
            appendSectionChunks(
                    chunks,
                    detail.postId(),
                    "fallback",
                    "This post currently has no summary or metadata available for retrieval.",
                    1
            );
        }
        return chunks;
    }

    private void appendAuthorChunks(List<IndexedChunk> chunks, String postId, PostAuthor author) {
        if (author == null) {
            return;
        }
        appendSectionChunks(chunks, postId, "author", author.nickname(), 1);
    }

    private void appendLocationChunks(List<IndexedChunk> chunks, String postId, PostLocation location) {
        if (location == null) {
            return;
        }
        StringBuilder locationText = new StringBuilder();
        if (StringUtils.hasText(location.address())) {
            locationText.append(location.address().trim());
        }
        if (StringUtils.hasText(location.geoHash())) {
            if (!locationText.isEmpty()) {
                locationText.append(' ');
            }
            locationText.append(location.geoHash().trim());
        }
        appendSectionChunks(chunks, postId, "location", locationText.toString(), 1);
    }

    private void appendSectionChunks(List<IndexedChunk> chunks, String postId, String section, String rawValue, int weight) {
        String normalizedValue = normalizeText(rawValue);
        if (!StringUtils.hasText(normalizedValue)) {
            return;
        }

        Set<String> keywords = tokenize(normalizedValue);
        List<String> pieces = splitIntoChunks(decorateSection(section, normalizedValue));
        for (int index = 0; index < pieces.size(); index++) {
            chunks.add(new IndexedChunk(
                    postId + "#" + section + "#" + index,
                    pieces.get(index),
                    keywords,
                    weight
            ));
        }
    }

    private List<String> splitIntoChunks(String text) {
        List<String> parts = new ArrayList<String>();
        int index = 0;
        int maxChunkLength = Math.max(32, ragProperties.getIndex().getMaxChunkLength());
        int chunkStep = Math.max(8, Math.min(ragProperties.getIndex().getChunkStep(), maxChunkLength));
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
            return "Title: " + value;
        }
        if ("summary".equals(section)) {
            return "Summary: " + value;
        }
        if ("tags".equals(section)) {
            return "Tags: " + value;
        }
        if ("author".equals(section)) {
            return "Author: " + value;
        }
        if ("location".equals(section)) {
            return "Location: " + value;
        }
        return value;
    }

    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<String>();
        String normalized = normalizeText(text);
        for (String part : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+")) {
            if (!StringUtils.hasText(part)) {
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
        if (!StringUtils.hasText(normalizedQuestion)) {
            return 0;
        }

        String normalizedContent = normalizeText(chunk.content());
        int score = 0;
        if (normalizedContent.contains(normalizedQuestion)) {
            score += 20;
        }
        for (String token : queryTokens) {
            if (normalizedContent.contains(token)) {
                score += 3 + chunk.weight() * 2;
            } else if (chunk.keywords().contains(token)) {
                score += 2 + chunk.weight();
            }
        }
        return score;
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

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private record IndexedPost(
            String postId,
            String title,
            Double latitude,
            Double longitude,
            List<IndexedChunk> chunks
    ) {
    }

    public record IndexedChunk(
            String chunkId,
            String content,
            Set<String> keywords,
            int weight
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
