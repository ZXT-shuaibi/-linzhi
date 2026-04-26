package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.KnowPostFeedRow;
import com.zhiguang.be.rag.config.RagProperties;
import com.zhiguang.be.rag.model.RagReference;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 索引服务。
 * 参考 zhiguang 的思路，把正文抓取、切块、指纹判断、向量写入和检索放在一条链路里。
 * 当前优先使用 Elasticsearch 持久化向量分片；如果未启用真实向量存储，再回退到本地轻量索引。
 */
@Service
public class RagIndexService {

    private static final String STATUS_PUBLISHED = "published";
    private static final String VISIBILITY_PUBLIC = "public";

    private final KnowPostMapper knowPostMapper;
    private final RagProperties ragProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RagEmbeddingGateway ragEmbeddingGateway;
    private final RestClient ragVectorRestClient;
    private final Map<String, IndexedPost> localIndexStore = new ConcurrentHashMap<String, IndexedPost>();

    public RagIndexService(
            KnowPostMapper knowPostMapper,
            RagProperties ragProperties,
            ObjectMapper objectMapper,
            RagEmbeddingGateway ragEmbeddingGateway,
            @Qualifier("ragVectorRestClient") ObjectProvider<RestClient> ragVectorRestClientProvider
    ) {
        this.knowPostMapper = knowPostMapper;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.ragEmbeddingGateway = ragEmbeddingGateway;
        this.ragVectorRestClient = ragVectorRestClientProvider.getIfAvailable();
        this.restTemplate = createRestTemplate(ragProperties);
    }

    /**
     * 确保指定内容已经具备可用索引。
     */
    /**
     * 确保指定帖子已经具备可用索引。
     * 如果内容指纹未变化，则直接复用已有索引。
     */
    public int ensureIndexed(String postId) {
        if (!hasText(postId)) {
            return 0;
        }
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null || !isIndexable(entity)) {
            removeIndex(postId);
            return 0;
        }

        if (useVectorStore()) {
            ensureVectorIndex();
            IndexedFingerprint fingerprint = findIndexedFingerprint(postId);
            if (fingerprint != null && isUpToDate(entity, fingerprint)) {
                return fingerprint.chunkCount();
            }
            return reindexSinglePost(postId);
        }

        IndexedPost indexedPost = localIndexStore.get(postId);
        if (indexedPost != null && isUpToDate(entity, indexedPost.toFingerprint())) {
            return indexedPost.chunks().size();
        }
        return reindexSinglePost(postId);
    }

    /**
     * 重建单篇内容索引。
     */
    /**
     * 重建单篇内容索引。
     */
    public int reindexSinglePost(String postId) {
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null || !isIndexable(entity)) {
            removeIndex(postId);
            return 0;
        }

        String content = fetchContent(entity.contentUrl());
        List<IndexedChunk> chunks = buildChunks(entity, content);
        if (chunks.isEmpty()) {
            removeIndex(postId);
            return 0;
        }

        if (useVectorStore()) {
            ensureVectorIndex();
            writeChunksToVectorStore(entity, chunks);
        } else {
            localIndexStore.put(
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
        }
        return chunks.size();
    }

    /**
     * 批量重建公开已发布内容索引。
     */
    /**
     * 批量重建公开已发布内容的索引。
     */
    public int reindexPublicPosts() {
        int pageSize = Math.max(1, ragProperties.getIndex().getRebuildPageSize());
        int maxPages = Math.max(1, ragProperties.getIndex().getRebuildMaxPages());
        int rebuiltChunks = 0;
        Set<String> seenPostIds = new HashSet<String>();
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
                rebuiltChunks += reindexSinglePost(row.postId());
            }
            if (rows.size() <= pageSize) {
                break;
            }
        }
        return rebuiltChunks;
    }

    /**
     * 执行检索并返回命中分片。
     */
    /**
     * 执行检索并返回命中的内容分片。
     */
    public SearchResult search(String question, String postId, Double lat, Double lng, int topK) {
        int safeTopK = Math.max(1, Math.min(topK, ragProperties.getQuery().getMaxTopK()));
        String normalizedQuestion = normalizeText(question);
        Set<String> queryTokens = tokenize(normalizedQuestion);
        double[] queryVector = ragEmbeddingGateway.embed(normalizedQuestion);

        List<ChunkHit> hits = useVectorStore()
                ? searchFromVectorStore(postId, lat, lng, safeTopK, normalizedQuestion, queryTokens, queryVector)
                : searchFromLocalStore(postId, lat, lng, safeTopK, normalizedQuestion, queryTokens, queryVector);

        List<RagReference> references = hits.stream()
                .map(hit -> new RagReference(hit.postId(), hit.chunkId(), hit.title()))
                .collect(Collectors.toList());
        return new SearchResult(hits, references);
    }

    /**
     * 先从 ES 向量索引做宽召回，再叠加关键词和位置等业务分数。
     */
    private List<ChunkHit> searchFromVectorStore(
            String postId,
            Double lat,
            Double lng,
            int topK,
            String normalizedQuestion,
            Set<String> queryTokens,
            double[] queryVector
    ) {
        ensureVectorIndex();
        List<VectorChunkDocument> documents = searchVectorDocuments(postId, topK, queryVector);
        if (documents.isEmpty()) {
            return List.of();
        }

        List<ChunkHit> hits = new ArrayList<ChunkHit>();
        for (VectorChunkDocument document : documents) {
            double score = score(
                    normalizedQuestion,
                    queryTokens,
                    normalizedSimilarity(document.retrievalScore()),
                    document.title(),
                    document.content(),
                    tokenize(document.content()),
                    document.weight(),
                    document.position()
            );
            if (score <= 0D) {
                continue;
            }
            score += locationBoost(lat, lng, document.latitude(), document.longitude());
            hits.add(new ChunkHit(document.postId(), document.title(), document.chunkId(), document.content(), score));
        }

        hits.sort(Comparator
                .comparingDouble(ChunkHit::score).reversed()
                .thenComparing(ChunkHit::postId)
                .thenComparing(ChunkHit::chunkId));
        return hits.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * 本地回退模式下，沿用与向量检索相同的重排思路，保证结果语义尽量一致。
     */
    private List<ChunkHit> searchFromLocalStore(
            String postId,
            Double lat,
            Double lng,
            int topK,
            String normalizedQuestion,
            Set<String> queryTokens,
            double[] queryVector
    ) {
        List<IndexedPost> candidates = StringUtils.hasText(postId)
                ? searchSinglePost(postId)
                : searchPublicPosts();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<ChunkHit> hits = new ArrayList<ChunkHit>();
        for (IndexedPost post : candidates) {
            for (IndexedChunk chunk : post.chunks()) {
                double score = score(
                        normalizedQuestion,
                        queryTokens,
                        cosineSimilarity(queryVector, chunk.vector()),
                        post.title(),
                        chunk.content(),
                        chunk.keywords(),
                        chunk.weight(),
                        chunk.position()
                );
                if (score <= 0D) {
                    continue;
                }
                score += locationBoost(lat, lng, post.latitude(), post.longitude());
                hits.add(new ChunkHit(post.postId(), post.title(), chunk.chunkId(), chunk.content(), score));
            }
        }
        hits.sort(Comparator
                .comparingDouble(ChunkHit::score).reversed()
                .thenComparing(ChunkHit::postId)
                .thenComparing(ChunkHit::chunkId));
        return hits.stream().limit(topK).collect(Collectors.toList());
    }

    private RestTemplate createRestTemplate(RagProperties ragProperties) {
        int timeoutMillis = Math.max(1, ragProperties.getIndex().getFetchTimeoutSeconds()) * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return new RestTemplate(factory);
    }

    private boolean useVectorStore() {
        return ragProperties.getVector().isStoreEnabled() && ragVectorRestClient != null;
    }

    private boolean isIndexable(KnowPostEntity entity) {
        return entity != null
                && STATUS_PUBLISHED.equalsIgnoreCase(entity.status())
                && VISIBILITY_PUBLIC.equalsIgnoreCase(entity.visible());
    }

    private boolean isUpToDate(KnowPostEntity entity, IndexedFingerprint fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        if (hasText(entity.contentSha256()) && hasText(fingerprint.contentSha256())) {
            return entity.contentSha256().equals(fingerprint.contentSha256());
        }
        if (hasText(entity.contentEtag()) && hasText(fingerprint.contentEtag())) {
            return entity.contentEtag().equals(fingerprint.contentEtag());
        }
        return false;
    }

    private void ensureVectorIndex() {
        if (!useVectorStore() || !ragProperties.getVector().isAutoCreateIndex()) {
            return;
        }
        try {
            Request request = new Request("HEAD", "/" + ragProperties.getVector().getIndexName());
            ragVectorRestClient.performRequest(request);
        } catch (ResponseException ex) {
            if (ex.getResponse() == null || ex.getResponse().getStatusLine().getStatusCode() != 404) {
                return;
            }
            createVectorIndex();
        } catch (Exception ignored) {
            // 向量索引不可用时不阻断主链路，后续会自动回退到本地模式。
        }
    }

    private void createVectorIndex() {
        try {
            String mapping = "{"
                    + "\"mappings\":{"
                    + "\"properties\":{"
                    + "\"post_id\":{\"type\":\"keyword\"},"
                    + "\"chunk_id\":{\"type\":\"keyword\"},"
                    + "\"title\":{\"type\":\"text\"},"
                    + "\"content\":{\"type\":\"text\"},"
                    + "\"position\":{\"type\":\"integer\"},"
                    + "\"weight\":{\"type\":\"integer\"},"
                    + "\"content_sha256\":{\"type\":\"keyword\"},"
                    + "\"content_etag\":{\"type\":\"keyword\"},"
                    + "\"latitude\":{\"type\":\"double\"},"
                    + "\"longitude\":{\"type\":\"double\"},"
                    + "\"vector\":{\"type\":\"dense_vector\",\"dims\":" + Math.max(1, ragProperties.getVector().getDimension()) + ",\"index\":false}"
                    + "}"
                    + "}"
                    + "}";
            Request request = new Request("PUT", "/" + ragProperties.getVector().getIndexName());
            request.setJsonEntity(mapping);
            ragVectorRestClient.performRequest(request);
        } catch (Exception ignored) {
            // 创建失败时保持静默，调用方会继续走本地回退。
        }
    }

    private IndexedFingerprint findIndexedFingerprint(String postId) {
        if (!useVectorStore()) {
            return null;
        }
        try {
            Request request = new Request("POST", "/" + ragProperties.getVector().getIndexName() + "/_search");
            request.setJsonEntity("{"
                    + "\"size\":1,"
                    + "\"track_total_hits\":true,"
                    + "\"query\":{\"term\":{\"post_id\":\"" + escapeJson(postId) + "\"}}"
                    + "}");
            JsonNode root = parseResponse(ragVectorRestClient.performRequest(request));
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return null;
            }
            JsonNode source = hits.get(0).path("_source");
            int chunkCount = root.path("hits").path("total").path("value").asInt(hits.size());
            return new IndexedFingerprint(
                    source.path("content_sha256").asText(null),
                    source.path("content_etag").asText(null),
                    chunkCount
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeChunksToVectorStore(KnowPostEntity entity, List<IndexedChunk> chunks) {
        deleteVectorChunks(entity.postId());
        StringBuilder ndjson = new StringBuilder();
        for (IndexedChunk chunk : chunks) {
            // 写入模型和查询命中模型拆开，避免后续检索结构调整时影响 bulk 文档格式。
            VectorChunkWriteDocument document = new VectorChunkWriteDocument(
                    entity.postId(),
                    chunk.chunkId(),
                    entity.title(),
                    chunk.content(),
                    chunk.position(),
                    chunk.weight(),
                    entity.contentSha256(),
                    entity.contentEtag(),
                    entity.latitude(),
                    entity.longitude(),
                    chunk.vector()
            );
            ndjson.append("{\"index\":{\"_index\":\"")
                    .append(ragProperties.getVector().getIndexName())
                    .append("\",\"_id\":\"")
                    .append(chunk.chunkId())
                    .append("\"}}\n")
                    .append(toJson(document))
                    .append('\n');
        }
        try {
            Request request = new Request("POST", "/_bulk");
            request.addParameter("refresh", "true");
            request.setJsonEntity(ndjson.toString());
            ragVectorRestClient.performRequest(request);
        } catch (Exception ignored) {
            // 批量写入失败时不抛出，让上层仍可继续运行。
        }
    }

    private void deleteVectorChunks(String postId) {
        if (!useVectorStore()) {
            return;
        }
        try {
            Request request = new Request("POST", "/" + ragProperties.getVector().getIndexName() + "/_delete_by_query");
            request.setJsonEntity("{\"query\":{\"term\":{\"post_id\":\"" + escapeJson(postId) + "\"}}}");
            ragVectorRestClient.performRequest(request);
        } catch (Exception ignored) {
            // 删除失败不会阻塞重建，下次重建仍会覆盖写。
        }
    }

    /**
     * 从 ES 向量索引中宽召回候选分片，供后续本地重排使用。
     */
    private List<VectorChunkDocument> searchVectorDocuments(String postId, int topK, double[] queryVector) {
        List<VectorChunkDocument> documents = new ArrayList<VectorChunkDocument>();
        try {
            // 先宽召回更多候选，再由本地规则做二次排序，更接近 zhiguang 的相似度检索语义。
            int candidateSize = Math.max(
                    Math.max(1, ragProperties.getVector().getCandidateSize()),
                    Math.max(1, topK) * 3
            );
            Request request = new Request("POST", "/" + ragProperties.getVector().getIndexName() + "/_search");
            request.setJsonEntity(buildSearchBody(postId, candidateSize, queryVector));
            JsonNode root = parseResponse(ragVectorRestClient.performRequest(request));
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray()) {
                return documents;
            }
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode()) {
                    continue;
                }
                documents.add(new VectorChunkDocument(
                        source.path("post_id").asText(),
                        source.path("chunk_id").asText(),
                        source.path("title").asText(""),
                        source.path("content").asText(""),
                        source.path("position").asInt(0),
                        source.path("weight").asInt(1),
                        source.path("content_sha256").asText(null),
                        source.path("content_etag").asText(null),
                        source.path("latitude").isNumber() ? source.path("latitude").asDouble() : null,
                        source.path("longitude").isNumber() ? source.path("longitude").asDouble() : null,
                        hit.path("_score").asDouble(0D)
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return documents;
    }

    /**
     * 组装 ES script_score 查询体。
     * 单帖问答时按 postId 收窄范围，公共问答则对全量公开分片做向量召回。
     */
    private String buildSearchBody(String postId, int candidateSize, double[] queryVector) {
        try {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("size", candidateSize);
            root.put("_source", List.of(
                    "post_id",
                    "chunk_id",
                    "title",
                    "content",
                    "position",
                    "weight",
                    "content_sha256",
                    "content_etag",
                    "latitude",
                    "longitude"
            ));

            Map<String, Object> baseQuery;
            if (hasText(postId)) {
                baseQuery = Map.of("term", Map.of("post_id", postId));
            } else {
                baseQuery = Map.of("match_all", Map.of());
            }

            Map<String, Object> script = new LinkedHashMap<String, Object>();
            script.put("source", "cosineSimilarity(params.query_vector, 'vector') + 1.0");
            script.put("params", Map.of("query_vector", toVectorValues(queryVector)));

            root.put("query", Map.of(
                    "script_score", Map.of(
                            "query", baseQuery,
                            "script", script
                    )
            ));
            // 公共问答保留最小相似度门槛，单帖问答则尽量从当前帖子里找上下文。
            if (!hasText(postId)) {
                root.put("min_score", 1.0D + Math.max(0D, ragProperties.getVector().getMinSimilarity()));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build vector search body", ex);
        }
    }

    private JsonNode parseResponse(Response response) throws Exception {
        return objectMapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
    }

    private void removeIndex(String postId) {
        localIndexStore.remove(postId);
        deleteVectorChunks(postId);
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
        IndexedPost indexedPost = localIndexStore.get(postId);
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
                IndexedPost indexedPost = localIndexStore.get(row.postId());
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
        String content = decorateSection(section, normalizedValue);
        String vectorSource = normalizeContent(title) + "\n" + content;
        chunks.add(new IndexedChunk(
                postId + "#" + section + "#" + position[0],
                content,
                tokenize(normalizedValue),
                ragEmbeddingGateway.embed(vectorSource),
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

    /**
     * 组合语义分数、关键词分数和位置信号，得到最终排序分。
     */
    private double score(
            String normalizedQuestion,
            Set<String> queryTokens,
            double vectorScore,
            String title,
            String content,
            Set<String> keywords,
            int weight,
            int position
    ) {
        if (!hasText(normalizedQuestion)) {
            return 0D;
        }

        double keywordScore = keywordScore(normalizedQuestion, queryTokens, title, content, keywords, weight, position);
        double weightedScore = vectorScore * ragProperties.getVector().getVectorWeight()
                + keywordScore * ragProperties.getVector().getKeywordWeight();

        if (vectorScore < ragProperties.getVector().getMinSimilarity() && keywordScore <= 0D) {
            return 0D;
        }
        return weightedScore;
    }

    private List<Double> toVectorValues(double[] vector) {
        List<Double> values = new ArrayList<Double>(vector.length);
        for (double value : vector) {
            values.add(value);
        }
        return values;
    }

    /**
     * 计算关键词、标题和分片权重带来的业务加分。
     */
    private double keywordScore(
            String normalizedQuestion,
            Set<String> queryTokens,
            String title,
            String content,
            Set<String> keywords,
            int weight,
            int position
    ) {
        String normalizedContent = normalizeText(content);
        String normalizedTitle = normalizeText(title);
        double score = 0D;
        if (normalizedContent.contains(normalizedQuestion)) {
            score += 1.2D;
        }
        if (hasText(normalizedTitle) && normalizedTitle.contains(normalizedQuestion)) {
            score += 0.8D + ragProperties.getVector().getTitleBoost();
        }
        for (String token : queryTokens) {
            if (normalizedContent.contains(token)) {
                score += 0.25D;
            } else if (keywords.contains(token)) {
                score += 0.15D;
            }
            if (hasText(normalizedTitle) && normalizedTitle.contains(token)) {
                score += ragProperties.getVector().getTitleBoost();
            }
        }
        // 标题、摘要等高权重分片在语义接近时应当比普通正文更容易被顶上来。
        score += Math.max(0D, weight - 1) * 0.03D;
        score += Math.max(0D, 0.08D - position * 0.01D);
        return score;
    }

    /**
     * script_score 使用的是 cosineSimilarity + 1.0，这里还原回更直观的相似度区间。
     */
    private double normalizedSimilarity(double retrievalScore) {
        return Math.max(0D, retrievalScore - 1.0D);
    }

    private double cosineSimilarity(double[] left, double[] right) {
        if (left.length != right.length) {
            return 0D;
        }
        double dot = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
        }
        return dot;
    }

    private double locationBoost(Double queryLat, Double queryLng, Double postLat, Double postLng) {
        if (queryLat == null || queryLng == null || postLat == null || postLng == null) {
            return 0D;
        }
        double radius = Math.max(1D, ragProperties.getQuery().getNearbyBoostRadiusMeters());
        double distanceMeters = computeDistanceMeters(queryLat.doubleValue(), queryLng.doubleValue(), postLat.doubleValue(), postLng.doubleValue());
        if (distanceMeters > radius) {
            return 0D;
        }
        double ratio = 1D - (distanceMeters / radius);
        return ratio * 0.2D;
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
        return normalizeContent(value).toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize vector document", ex);
        }
    }

    private record IndexedFingerprint(
            String contentSha256,
            String contentEtag,
            int chunkCount
    ) {
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
        private IndexedFingerprint toFingerprint() {
            return new IndexedFingerprint(contentSha256, contentEtag, chunks == null ? 0 : chunks.size());
        }
    }

    private record IndexedChunk(
            String chunkId,
            String content,
            Set<String> keywords,
            double[] vector,
            int weight,
            int position
    ) {
    }

    private record VectorChunkDocument(
            String postId,
            String chunkId,
            String title,
            String content,
            int position,
            int weight,
            String contentSha256,
            String contentEtag,
            Double latitude,
            Double longitude,
            double retrievalScore
    ) {
    }

    private record VectorChunkWriteDocument(
            String postId,
            String chunkId,
            String title,
            String content,
            int position,
            int weight,
            String contentSha256,
            String contentEtag,
            Double latitude,
            Double longitude,
            double[] vector
    ) {
    }

    public record ChunkHit(
            String postId,
            String title,
            String chunkId,
            String content,
            double score
    ) {
    }

    public record SearchResult(
            List<ChunkHit> hits,
            List<RagReference> references
    ) {
    }
}
