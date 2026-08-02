package com.zhiguang.be.threadpool;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池配置。
 * 将自适应缓冲线程池收口到 linli 的 threadpool 模块，对外仍暴露 trade 和 rag 两个既有执行器 Bean。
 */
@Configuration
@EnableConfigurationProperties(ThreadPoolProperties.class)
public class ThreadPoolConfiguration {

    private final ThreadPoolProperties threadPoolProperties;

    /**
     * 构造线程池配置。
     *
     * @param threadPoolProperties 线程池配置属性
     */
    public ThreadPoolConfiguration(ThreadPoolProperties threadPoolProperties) {
        this.threadPoolProperties = threadPoolProperties;
    }

    /**
     * 交易模块异步下单线程池。
     */
    @Bean(name = "tradeOrderExecutor", destroyMethod = "shutdown")
    public Executor tradeOrderExecutor() {
        return buildExecutor(threadPoolProperties.getTradeOrder());
    }

    /**
     * RAG 流式问答执行线程池。
     */
    @Bean(name = "ragQueryExecutor", destroyMethod = "shutdown")
    public Executor ragQueryExecutor() {
        return buildExecutor(threadPoolProperties.getRagQuery());
    }

    /**
     * Interaction projections run after the primary database transaction commits.
     */
    @Bean(name = "interactionProjectionExecutor", destroyMethod = "shutdown")
    public Executor interactionProjectionExecutor() {
        return buildExecutor(threadPoolProperties.getInteractionProjection());
    }

    /**
     * 根据配置构建自适应缓冲线程池。
     */
    private AdaptiveBufferedThreadPoolExecutor buildExecutor(ThreadPoolProperties.PoolProperties properties) {
        AdaptiveBufferedThreadPoolExecutor executor = new AdaptiveBufferedThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaximumPoolSize(),
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(Math.max(1, properties.getQueueCapacity())),
                new NamedThreadFactory(properties.getThreadNamePrefix()),
                resolveRejectedExecutionHandler(properties),
                properties.getBufferDegree(),
                properties.isPreventRejection(),
                properties.getThreadLoadJudge(),
                properties.getCpuLoadJudge(),
                properties.getSpinWaitMillis(),
                properties.getBlockTimeoutMillis(),
                properties.getMaxRetryAttempts(),
                properties.getThreadNamePrefix()
        );
        executor.allowCoreThreadTimeOut(properties.isAllowCoreThreadTimeout());
        return executor;
    }

    /**
     * 按配置装配拒绝策略。
     * 当前默认值由配置属性控制：业务模式默认 caller-runs，实验模式显式切换为 count。
     * 如果配置了未知策略，直接在启动期失败，避免静默回落到不符合预期的行为。
     */
    private AdaptiveRejectedExecutionHandler resolveRejectedExecutionHandler(ThreadPoolProperties.PoolProperties properties) {
        String policy = properties.getRejectionPolicy();
        if (policy == null || policy.isBlank()) {
            return new AdaptiveBufferedThreadPoolExecutor.CallerRunsPolicy();
        }

        if ("discard".equalsIgnoreCase(policy)) {
            return new AdaptiveBufferedThreadPoolExecutor.DiscardPolicy();
        }
        if ("caller-runs".equalsIgnoreCase(policy) || "callerRuns".equalsIgnoreCase(policy)) {
            return new AdaptiveBufferedThreadPoolExecutor.CallerRunsPolicy();
        }
        if ("abort".equalsIgnoreCase(policy)) {
            return new AdaptiveBufferedThreadPoolExecutor.AbortPolicy();
        }
        if ("count".equalsIgnoreCase(policy)) {
            return new AdaptiveBufferedThreadPoolExecutor.CountPolicy();
        }
        throw new IllegalArgumentException("不支持的线程池拒绝策略: " + policy + "，可选值为 caller-runs / abort / count / discard");
    }
}
