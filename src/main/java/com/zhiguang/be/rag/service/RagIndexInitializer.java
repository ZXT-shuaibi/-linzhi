package com.zhiguang.be.rag.service;

import com.zhiguang.be.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * RAG 索引初始化器。
 * 参考 zhiguang 的思路，在应用启动后按配置决定是否预热公开内容索引。
 */
@Component
public class RagIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(RagIndexInitializer.class);

    private final RagIndexOperations ragIndexService;
    private final RagProperties ragProperties;

    public RagIndexInitializer(RagIndexOperations ragIndexService, RagProperties ragProperties) {
        this.ragIndexService = ragIndexService;
        this.ragProperties = ragProperties;
    }

    /**
     * 应用启动完成后按需执行全量索引预热。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!ragProperties.getIndex().isAutoRebuildOnStartup()) {
            return;
        }
        try {
            int rebuiltChunks = ragIndexService.reindexPublicPosts();
            log.info("RAG public index warmup finished, rebuilt chunks={}", rebuiltChunks);
        } catch (Exception ex) {
            log.warn("RAG public index warmup failed: {}", ex.getMessage());
        }
    }
}
