package com.zhiguang.be.threadpool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线程池模块配置。
 * 当前先为 trade 和 rag 两类异步任务提供独立的自适应线程池参数。
 */
@ConfigurationProperties(prefix = "threadpool")
public class ThreadPoolProperties {

    private PoolProperties tradeOrder = new PoolProperties();
    private PoolProperties ragQuery = new PoolProperties();

    public ThreadPoolProperties() {
        tradeOrder.setThreadNamePrefix("trade-order-");
        tradeOrder.setCorePoolSize(4);
        tradeOrder.setMaximumPoolSize(8);
        tradeOrder.setQueueCapacity(200);

        ragQuery.setThreadNamePrefix("rag-query-");
        ragQuery.setCorePoolSize(2);
        ragQuery.setMaximumPoolSize(4);
        ragQuery.setQueueCapacity(100);
        ragQuery.setRejectionPolicy("abort");
    }

    public PoolProperties getTradeOrder() {
        return tradeOrder;
    }

    public void setTradeOrder(PoolProperties tradeOrder) {
        this.tradeOrder = tradeOrder;
    }

    public PoolProperties getRagQuery() {
        return ragQuery;
    }

    public void setRagQuery(PoolProperties ragQuery) {
        this.ragQuery = ragQuery;
    }

    /**
     * 单个线程池配置项。
     */
    public static class PoolProperties {

        private String threadNamePrefix = "linli-pool-";
        private int corePoolSize = 4;
        private int maximumPoolSize = 8;
        private int queueCapacity = 200;
        private long keepAliveSeconds = 60;
        private double bufferDegree = 0.5D;
        private boolean preventRejection = true;
        private int threadLoadJudge = 5;
        private double cpuLoadJudge = 0.70D;
        private long spinWaitMillis = 10L;
        private long blockTimeoutMillis = 100L;
        private int maxRetryAttempts = 3;
        private boolean allowCoreThreadTimeout = false;
        private String rejectionPolicy = "caller-runs";

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public double getBufferDegree() {
            return bufferDegree;
        }

        public void setBufferDegree(double bufferDegree) {
            this.bufferDegree = bufferDegree;
        }

        public boolean isPreventRejection() {
            return preventRejection;
        }

        public void setPreventRejection(boolean preventRejection) {
            this.preventRejection = preventRejection;
        }

        public int getThreadLoadJudge() {
            return threadLoadJudge;
        }

        public void setThreadLoadJudge(int threadLoadJudge) {
            this.threadLoadJudge = threadLoadJudge;
        }

        public double getCpuLoadJudge() {
            return cpuLoadJudge;
        }

        public void setCpuLoadJudge(double cpuLoadJudge) {
            this.cpuLoadJudge = cpuLoadJudge;
        }

        public long getSpinWaitMillis() {
            return spinWaitMillis;
        }

        public void setSpinWaitMillis(long spinWaitMillis) {
            this.spinWaitMillis = spinWaitMillis;
        }

        public long getBlockTimeoutMillis() {
            return blockTimeoutMillis;
        }

        public void setBlockTimeoutMillis(long blockTimeoutMillis) {
            this.blockTimeoutMillis = blockTimeoutMillis;
        }

        public int getMaxRetryAttempts() {
            return maxRetryAttempts;
        }

        public void setMaxRetryAttempts(int maxRetryAttempts) {
            this.maxRetryAttempts = maxRetryAttempts;
        }

        public boolean isAllowCoreThreadTimeout() {
            return allowCoreThreadTimeout;
        }

        public void setAllowCoreThreadTimeout(boolean allowCoreThreadTimeout) {
            this.allowCoreThreadTimeout = allowCoreThreadTimeout;
        }

        public String getRejectionPolicy() {
            return rejectionPolicy;
        }

        public void setRejectionPolicy(String rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
        }
    }
}
