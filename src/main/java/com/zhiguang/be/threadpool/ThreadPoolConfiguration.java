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
                new AdaptiveBufferedThreadPoolExecutor.CallerRunsPolicy(),
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
}
