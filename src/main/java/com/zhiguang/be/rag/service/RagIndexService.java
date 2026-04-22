package com.zhiguang.be.rag.service;

import com.zhiguang.be.content.dto.PostCard;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.service.ContentService;
import com.zhiguang.be.rag.model.RagReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 索引服务。
 * 当前基础版不接 ES 向量库，而是用内存索引承接“内容切片 + 简化召回”。
 */
@Service
public class RagIndexService {

    private final ContentService contentService;
    private final Map<String, IndexedPost> indexStore = new ConcurrentHashMap<>();

    /**
     * 注入内容查询能力。
     */
    public RagIndexService(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * 确保单篇帖子已经建立索引。
     */
    public int ensureIndexed(String postId) {
        if (!StringUtils.hasText(postId)) {
            return 0;
        }
        if (indexStore.containsKey(postId)) {
            return indexStore.get(postId).chunks().size();
        }
        return reindexSinglePost(postId);
    }

    /**
     * 手动重建单篇帖子索引。
     */
    public int reindexSinglePost(String postId) {
        PostDetail detail = contentService.getDetail(postId, null);
        List<IndexedChunk> chunks = buildChunks(detail);
        indexStore.put(postId, new IndexedPost(postId, detail.title(), chunks));
        return chunks.size();
    }

    /**
     * 执行简化检索。
     * 单篇问答优先在指定帖子内召回；未指定时从公开内容流构建候选集。
     */
    public SearchResult search(String question, String postId, int topK) {
        int safeTopK = Math.max(1, Math.min(topK, 10));
        List<IndexedPost> candidates = StringUtils.hasText(postId)
                ? searchSinglePost(postId)
                : searchPublicPosts();

        Set<String> tokens = tokenize(question);
        List<ChunkHit> hits = new ArrayList<>();
        for (IndexedPost post : candidates) {
            for (IndexedChunk chunk : post.chunks()) {
                int score = score(tokens, chunk.content());
                if (score > 0) {
                    hits.add(new ChunkHit(post.postId(), post.title(), chunk.chunkId(), chunk.content(), score));
                }
            }
        }

        hits.sort(Comparator.comparingInt(ChunkHit::score).reversed());
        List<ChunkHit> selected = hits.stream().limit(safeTopK).collect(Collectors.toList());
        List<RagReference> references = selected.stream()
                .map(hit -> new RagReference(hit.postId(), hit.chunkId(), hit.title()))
                .collect(Collectors.toList());
        return new SearchResult(selected, references);
    }

    /**
     * 读取单篇帖子候选。
     */
    private List<IndexedPost> searchSinglePost(String postId) {
        int chunkCount = ensureIndexed(postId);
        if (chunkCount <= 0) {
            return List.of();
        }
        IndexedPost indexedPost = indexStore.get(postId);
        return indexedPost == null ? List.of() : List.of(indexedPost);
    }

    /**
     * 读取公开内容流作为基础候选集。
     */
    private List<IndexedPost> searchPublicPosts() {
        PostPageData pageData = contentService.getPublicFeed(null, 1, 20);
        List<IndexedPost> posts = new ArrayList<>();
        for (PostCard item : pageData.items()) {
            String postId = item.postId();
            ensureIndexed(postId);
            IndexedPost indexedPost = indexStore.get(postId);
            if (indexedPost != null) {
                posts.add(indexedPost);
            }
        }
        return posts;
    }

    /**
     * 将帖子详情拆成简化分片。
     */
    private List<IndexedChunk> buildChunks(PostDetail detail) {
        List<IndexedChunk> chunks = new ArrayList<>();
        String title = detail.title() == null ? "" : detail.title().trim();
        String summary = detail.summary() == null ? "" : detail.summary().trim();
        String tags = detail.tags() == null ? "" : String.join(" ", detail.tags());
        StringBuilder fullText = new StringBuilder();
        if (!title.isEmpty()) {
            fullText.append("标题：").append(title).append('\n');
        }
        if (!summary.isEmpty()) {
            fullText.append("摘要：").append(summary).append('\n');
        }
        if (!tags.isEmpty()) {
            fullText.append("标签：").append(tags).append('\n');
        }

        String content = fullText.toString().trim();
        if (content.isEmpty()) {
            content = "该帖子当前没有可供问答的摘要信息。";
        }

        int maxChunkLength = 200;
        int step = 160;
        int index = 0;
        int seq = 0;
        while (index < content.length()) {
            int end = Math.min(index + maxChunkLength, content.length());
            String chunkText = content.substring(index, end);
            chunks.add(new IndexedChunk(detail.postId() + "#" + seq, chunkText));
            if (end >= content.length()) {
                break;
            }
            index += step;
            seq++;
        }

        if (chunks.isEmpty()) {
            chunks.add(new IndexedChunk(detail.postId() + "#0", content));
        }
        return chunks;
    }

    /**
     * 将问题拆成简单关键词集合。
     */
    private Set<String> tokenize(String question) {
        return List.of(question.toLowerCase().split("[\\s,，。！？;；、】【()（）]+"))
                .stream()
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }

    /**
     * 计算简化相关性分数。
     */
    private int score(Set<String> tokens, String content) {
        if (tokens.isEmpty()) {
            return 1;
        }
        String lowerContent = content.toLowerCase();
        int score = 0;
        for (String token : tokens) {
            if (lowerContent.contains(token)) {
                score++;
            }
        }
        return score;
    }

    /**
     * 索引后的单篇帖子。
     */
    private record IndexedPost(
            String postId,
            String title,
            List<IndexedChunk> chunks
    ) {
    }

    /**
     * 索引后的分片。
     */
    public record IndexedChunk(
            String chunkId,
            String content
    ) {
    }

    /**
     * 检索命中结果。
     */
    public record ChunkHit(
            String postId,
            String title,
            String chunkId,
            String content,
            int score
    ) {
    }

    /**
     * 检索结果集合。
     */
    public record SearchResult(
            List<ChunkHit> hits,
            List<RagReference> references
    ) {
    }
}
