package com.zhiguang.be.llm.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.llm.model.KnowPostDescriptionData;
import com.zhiguang.be.llm.model.KnowPostDescriptionRequest;
import com.zhiguang.be.llm.service.KnowPostDescriptionService;
import com.zhiguang.be.llm.service.LlmGateway;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 控制器。
 */
@Validated
@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

    private final LlmGateway llmGateway;
    private final KnowPostDescriptionService knowPostDescriptionService;

    public LlmController(LlmGateway llmGateway, KnowPostDescriptionService knowPostDescriptionService) {
        this.llmGateway = llmGateway;
        this.knowPostDescriptionService = knowPostDescriptionService;
    }

    /**
     * 根据正文生成帖子描述。
     */
    @PostMapping("/posts/description")
    public ApiResponse<KnowPostDescriptionData> generateKnowPostDescription(
            @Valid @RequestBody KnowPostDescriptionRequest request
    ) {
        String description = knowPostDescriptionService.generateDescription(request.content());
        return ApiResponse.success(new KnowPostDescriptionData(llmGateway.currentModelName(), description));
    }
}
