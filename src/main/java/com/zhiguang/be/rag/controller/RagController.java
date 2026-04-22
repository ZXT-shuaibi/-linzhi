package com.zhiguang.be.rag.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.rag.model.RagQueryRequest;
import com.zhiguang.be.rag.service.RagIndexService;
import com.zhiguang.be.rag.service.RagQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 控制器。
 * 当前基础版先承接流式问答与单篇索引重建两个入口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagQueryService ragQueryService;
    private final RagIndexService ragIndexService;

    /**
     * 注入 RAG 相关服务。
     */
    public RagController(RagQueryService ragQueryService, RagIndexService ragIndexService) {
        this.ragQueryService = ragQueryService;
        this.ragIndexService = ragIndexService;
    }

    /**
     * 发起流式问答。
     */
    @PostMapping(value = "/queries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody RagQueryRequest request) {
        return ragQueryService.stream(request);
    }

    /**
     * 手动触发单篇帖子索引重建。
     */
    @PostMapping("/posts/{postId}/reindex")
    public ApiResponse<Integer> reindex(@PathVariable @Min(1) long postId) {
        return ApiResponse.success(ragIndexService.reindexSinglePost(String.valueOf(postId)));
    }
}
