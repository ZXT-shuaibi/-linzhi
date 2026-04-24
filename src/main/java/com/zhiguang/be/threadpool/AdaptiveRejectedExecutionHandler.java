package com.zhiguang.be.threadpool;

/**
 * 自适应线程池拒绝策略接口。
 * 当线程池和缓冲队列都无法继续承载任务时，由具体策略决定如何处理任务。
 */
public interface AdaptiveRejectedExecutionHandler {

    /**
     * 处理被拒绝的任务。
     *
     * @param task 被拒绝的任务
     * @param executor 当前线程池
     */
    void rejectedExecution(Runnable task, AdaptiveBufferedThreadPoolExecutor executor);
}
