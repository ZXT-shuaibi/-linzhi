package com.zhiguang.be.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.geo.GeoDistances;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import com.zhiguang.be.content.model.KnowPostFeedRow;
import com.zhiguang.be.rag.config.RagProperties;
import com.zhiguang.be.rag.model.RagReference;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.zhiguang.be.common.util.Texts.hasText;

/**
 * RAG 索引服务。
 * 参考 zhiguang 的写法，优先使用 Spring AI 的 VectorStore 做向量写入和语义召回，
 * 同时保留当前工程里的 ES 指纹校验和本地兜底索引，避免基础设施未就绪时主链断掉。
 */
@Service
public class RagIndexService implements RagIndexOperations {

    private static final String STATUS_PUBLISHED = "published";
    private static final String VISIBILITY_PUBLIC = "public";

    private final KnowPostMapper knowPostMapper;
    private final RagProperties ragProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RagEmbeddingGateway ragEmbeddingGateway;
    private final RestClient ragVectorRestClient;
    private final VectorStore vectorStore;
    private final Map<String, IndexedPost> localIndexStore = new ConcurrentHashMap<String, IndexedPost>();

    public RagIndexService(
            KnowPostMapper knowPostMapper,
            RagProperties ragProperties,
            ObjectMapper objectMapper,
            RagEmbeddingGateway ragEmbeddingGateway,
            @Qualifier("ragVectorRestClient") ObjectProvider<RestClient> ragVectorRestClientProvider,
            @Qualifier("ragVectorStore") ObjectProvider<VectorStore> vectorStoreProvider
    ) {
        this.knowPostMapper = knowPostMapper;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.ragEmbeddingGateway = ragEmbeddingGateway;
        this.ragVectorRestClient = ragVectorRestClientProvider.getIfAvailable();
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        this.restTemplate = createRestTemplate(ragProperties);
    }

    /**
     * 确保指定帖子已经具备可用索引。
     * 内容指纹未变化时会直接复用已有索引，避免重复切块和重复向量写入。
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
            IndexedFingerprint fingerprint = findIndexedFingerprint(postId, buildIndexVersion(entity));
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
    @Override
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
     * 批量重建公开内容的索引。
     */
    @Override
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
     * 执行语义检索并返回命中的内容分片。
     */
    public SearchResult search(String question, String postId, Double lat, Double lng, int topK) {
        int safeTopK = Math.max(1, Math.min(topK, ragProperties.getQuery().getMaxTopK()));
        String normalizedQuestion = normalizeText(question);
        Set<String> queryTokens = tokenize(normalizedQuestion);
        double[] queryVector = ragEmbeddingGateway.embed(normalizedQuestion);

        List<ChunkHit> hits = useVectorStore()
                ? searchFromVectorStore(postId, lat, lng, safeTopK, normalizedQuestion, queryTokens)
                : searchFromLocalStore(postId, lat, lng, safeTopK, normalizedQuestion, queryTokens, queryVector);

        List<RagReference> references = hits.stream()
                .map(hit -> new RagReference(hit.postId(), hit.chunkId(), hit.title()))
                .collect(Collectors.toList());
        return new SearchResult(hits, references);
    }

    /**
     * 优先通过 Spring AI VectorStore 进行宽召回，再叠加标题、关键词、位置等业务重排。
     */
    private List<ChunkHit> searchFromVectorStore(
            String postId,
            Double lat,
            Double lng,
            int topK,
            String normalizedQuestion,
            Set<String> queryTokens
    ) {
        List<VectorChunkDocument> documents = searchVectorDocuments(postId, topK, normalizedQuestion);
        if (documents.isEmpty()) {
            return List.of();
        }

        List<ChunkHit> hits = new ArrayList<ChunkHit>();
        for (VectorChunkDocument document : documents) {
            double vectorScore = normalizedSimilarity(document.retrievalScore());
            double score = score(
                    normalizedQuestion,
                    queryTokens,
                    vectorScore,
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
     * 当 VectorStore 不可用时，回退到本地轻量索引，并沿用同一套重排逻辑。
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
        List<IndexedPost> candidates = hasText(postId)
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
        return vectorStore != null && ragVectorRestClient != null;
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

    /**
     * 从向量索引中读取已有指纹，判断是否需要重建。
     */
    private IndexedFingerprint findIndexedFingerprint(String postId, String indexVersion) {
        if (!useVectorStore()) {
            return null;
        }
        try {
            Request request = new Request("POST", "/" + ragProperties.getVector().getIndexName() + "/_search");
            request.setJsonEntity("{"
                    + "\"size\":1,"
                    + "\"track_total_hits\":true,"
                    + "\"query\":{\"bool\":{\"must\":["
                    + "{\"term\":{\"metadata.postId\":\"" + escapeJson(postId) + "\"}},"
                    + "{\"term\":{\"metadata.indexVersion\":\"" + escapeJson(indexVersion) + "\"}}"
                    + "]}}"
                    + "}");
            JsonNode root = parseResponse(ragVectorRestClient.performRequest(request));
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return null;
            }
            JsonNode source = hits.get(0).path("_source");
            JsonNode metadata = source.path("metadata");
            int chunkCount = root.path("hits").path("total").path("value").asInt(hits.size());
            return new IndexedFingerprint(
                    metadata.path("contentSha256").asText(null),
                    metadata.path("contentEtag").asText(null),
                    chunkCount
            );
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 通过 VectorStore 写入向量分片。
     */
    private void writeChunksToVectorStore(KnowPostEntity entity, List<IndexedChunk> chunks) {
        String indexVersion = buildIndexVersion(entity);
        List<Document> documents = new ArrayList<Document>(chunks.size());
        for (IndexedChunk chunk : chunks) {
            Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("postId", entity.postId());
            metadata.put("indexVersion", indexVersion);
            metadata.put("chunkId", chunk.chunkId());
            metadata.put("title", entity.title());
            metadata.put("position", chunk.position());
            metadata.put("weight", chunk.weight());
            metadata.put("contentSha256", entity.contentSha256());
            metadata.put("contentEtag", entity.contentEtag());
            metadata.put("latitude", entity.latitude());
            metadata.put("longitude", entity.longitude());
            documents.add(Document.builder()
                    .id(chunk.chunkId())
                    .text(chunk.content())
                    .metadata(metadata)
                    .build());
        }
        vectorStore.add(documents);
        deleteVectorChunks(entity.postId(), indexVersion);
    }

    /**
     * 根据 postId 删除旧分片，保证重建幂等。
     */
    private void deleteVectorChunks(String postId) {
        deleteVectorChunks(postId, null);
    }

    private void deleteVectorChunks(String postId, String keepIndexVersion) {
        if (!useVectorStore()) {
            return;
        }
        try {
            Request request = new Request("POST", "/" + ragProperties.getVector().getIndexName() + "/_delete_by_query");
            if (hasText(keepIndexVersion)) {
                request.setJsonEntity("{\"query\":{\"bool\":{\"must\":[{\"term\":{\"metadata.postId\":\""
                        + escapeJson(postId)
                        + "\"}}],\"must_not\":[{\"term\":{\"metadata.indexVersion\":\""
                        + escapeJson(keepIndexVersion)
                        + "\"}}]}}}");
            } else {
                request.setJsonEntity("{\"query\":{\"term\":{\"metadata.postId\":\"" + escapeJson(postId) + "\"}}}");
            }
            ragVectorRestClient.performRequest(request);
        } catch (Exception ignored) {
            // 删除失败时保留静默，后续重建仍会继续尝试写入。
        }
    }

    /**
     * 使用 VectorStore 做语义宽召回，再把结果转成项目内部的分片模型。
     */
    private List<VectorChunkDocument> searchVectorDocuments(String postId, int topK, String normalizedQuestion) {
        if (!useVectorStore()) {
            return List.of();
        }
        try {
            int candidateSize = Math.max(
                    Math.max(1, ragProperties.getVector().getCandidateSize()),
                    Math.max(1, topK) * 5
            );
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(normalizedQuestion)
                    .topK(candidateSize)
                    .similarityThresholdAll();
            if (hasText(postId)) {
                requestBuilder.filterExpression("postId == '" + postId + "'");
            }
            List<Document> documents = vectorStore.similaritySearch(requestBuilder.build());
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            Map<String, String> currentIndexVersions = new HashMap<String, String>();
            List<VectorChunkDocument> hits = new ArrayList<VectorChunkDocument>();
            for (Document document : documents) {
                Map<String, Object> metadata = document.getMetadata();
                String documentPostId = asString(metadata.get("postId"));
                if (hasText(postId) && !postId.equals(documentPostId)) {
                    continue;
                }
                if (!isCurrentVectorVersion(
                        documentPostId,
                        asString(metadata.get("indexVersion")),
                        currentIndexVersions
                )) {
                    continue;
                }
                Double retrievalScore = document.getScore();
                if (!hasText(postId) && retrievalScore != null && normalizedSimilarity(retrievalScore.doubleValue()) < ragProperties.getVector().getMinSimilarity()) {
                    continue;
                }
                hits.add(new VectorChunkDocument(
                        documentPostId,
                        asString(metadata.get("chunkId")),
                        asString(metadata.get("title")),
                        document.getText(),
                        asInt(metadata.get("position")),
                        asInt(metadata.get("weight"), 1),
                        asString(metadata.get("contentSha256")),
                        asString(metadata.get("contentEtag")),
                        asDouble(metadata.get("latitude")),
                        asDouble(metadata.get("longitude")),
                        retrievalScore == null ? 0D : retrievalScore.doubleValue()
                ));
            }
            return hits;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean isCurrentVectorVersion(
            String postId,
            String indexVersion,
            Map<String, String> currentIndexVersions
    ) {
        if (!hasText(postId) || !hasText(indexVersion)) {
            return false;
        }
        String currentIndexVersion = currentIndexVersions.computeIfAbsent(postId, this::resolveCurrentIndexVersion);
        return hasText(currentIndexVersion) && currentIndexVersion.equals(indexVersion);
    }

    private String resolveCurrentIndexVersion(String postId) {
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null || !isIndexable(entity)) {
            return null;
        }
        return buildIndexVersion(entity);
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

    private String buildIndexVersion(KnowPostEntity entity) {
        if (entity == null) {
            return "missing";
        }
        if (hasText(entity.contentSha256())) {
            return "sha256:" + entity.contentSha256();
        }
        if (hasText(entity.contentEtag())) {
            return "etag:" + entity.contentEtag();
        }
        if (entity.updatedAt() != null) {
            return "updated:" + entity.updatedAt().toEpochMilli();
        }
        return "post:" + entity.postId();
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
     * 组合语义分数、关键词命中和分片权重，得到最终重排分数。
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

    /**
     * 计算标题、正文、关键词与权重带来的业务加分。
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
        score += Math.max(0D, weight - 1) * 0.03D;
        score += Math.max(0D, 0.08D - position * 0.01D);
        return score;
    }

    /**
     * Spring AI 返回的 score 直接作为向量相关度主分数使用。
     */
    private double normalizedSimilarity(double retrievalScore) {
        return Math.max(0D, retrievalScore);
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
        double distanceMeters = GeoDistances.haversineMeters(
                queryLat.doubleValue(),
                queryLng.doubleValue(),
                postLat.doubleValue(),
                postLng.doubleValue()
        );
        if (distanceMeters > radius) {
            return 0D;
        }
        double ratio = 1D - (distanceMeters / radius);
        return ratio * 0.2D;
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

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value) {
        return asInt(value, 0);
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
