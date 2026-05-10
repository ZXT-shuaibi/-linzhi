package com.zhiguang.be.llm.service;

import java.util.List;
import java.util.function.Consumer;

/**
 * RAG 回答生成服务。
 */
public interface RagAnswerService {

    /**
     * 根据问题和召回片段生成答案。
     */
    String buildAnswer(String question, List<Context> contexts);

    /**
     * 尝试流式输出答案。
     *
     * @param question 问题
     * @param contexts 上下文
     * @param consumer 增量输出回调
     * @return 成功走真实流式输出时返回 true
     */
    boolean streamAnswer(String question, List<Context> contexts, Consumer<String> consumer, CancellationSignal cancellationSignal);

    /**
     * Streaming cancellation hook. Implementations can poll the flag and register IO cleanup callbacks.
     */
    interface CancellationSignal {

        boolean isCancelled();

        default void onCancel(Runnable cancellationAction) {
            // Optional hook for providers that can close sockets/streams on client abort.
        }
    }

    /**
     * 回答生成上下文。
     */
    record Context(
            String title,
            String content
    ) {
    }
}
