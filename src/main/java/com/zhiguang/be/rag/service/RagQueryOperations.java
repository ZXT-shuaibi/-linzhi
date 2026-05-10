package com.zhiguang.be.rag.service;

import com.zhiguang.be.rag.model.RagQueryRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface RagQueryOperations {

    SseEmitter stream(RagQueryRequest request);
}
