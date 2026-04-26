package com.zhiguang.be.platform.service.impl;

import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.platform.model.PlatformCacheEvictRequest;
import com.zhiguang.be.platform.model.PlatformCacheMetricsData;
import com.zhiguang.be.platform.model.PlatformCacheRegionData;
import com.zhiguang.be.platform.model.PlatformModuleStatusData;
import com.zhiguang.be.platform.model.PlatformRuntimeData;
import com.zhiguang.be.platform.model.PlatformThreadPoolData;
import com.zhiguang.be.platform.service.PlatformService;
import com.zhiguang.be.threadpool.AdaptiveBufferedThreadPoolExecutor;
import com.zhiguang.be.threadpool.ThreadPoolProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 平台治理服务实现。
 */
@Service
public class PlatformServiceImpl implements PlatformService {

    private final CacheService cacheService;
    private final Environment environment;
    private final ThreadPoolProperties threadPoolProperties;
    private final Executor tradeOrderExecutor;
    private final Executor ragQueryExecutor;

    @Value("${spring.application.name:zhiguang-be}")
    private String applicationName;
    @Value("${llm.provider:template}")
    private String llmProvider;
    @Value("${llm.model-name:template-llm}")
    private String llmModel;
    @Value("${search.provider:db}")
    private String searchProvider;
    @Value("${rag.query.default-top-k:5}")
    private int ragDefaultTopK;
    @Value("${social.counter.kafka.enabled:false}")
    private boolean socialKafkaEnabled;
    @Value("${social.relation.repair.enabled:false}")
    private boolean socialRebuildEnabled;
    @Value("${trade.kafka.enabled:false}")
    private boolean tradeKafkaEnabled;
    @Value("${discover.lbs.fail-open-on-search-error:false}")
    private boolean discoverFailOpenEnabled;
    @Value("${security.login-blacklist.enabled:true}")
    private boolean loginBlacklistEnabled;
    @Value("${cache.local.max-entries-per-region:1024}")
    private int localCacheMaxEntriesPerRegion;
    @Value("${cache.hotkey.enabled:true}")
    private boolean cacheHotkeyEnabled;

    public PlatformServiceImpl(
            CacheService cacheService,
            Environment environment,
            ThreadPoolProperties threadPoolProperties,
            @Qualifier("tradeOrderExecutor") Executor tradeOrderExecutor,
            @Qualifier("ragQueryExecutor") Executor ragQueryExecutor
    ) {
        this.cacheService = cacheService;
        this.environment = environment;
        this.threadPoolProperties = threadPoolProperties;
        this.tradeOrderExecutor = tradeOrderExecutor;
        this.ragQueryExecutor = ragQueryExecutor;
    }

    @Override
    public PlatformRuntimeData getRuntimeSummary() {
        return new PlatformRuntimeData(
                applicationName,
                resolveActiveProfiles(),
                Instant.now(),
                llmProvider,
                llmModel,
                searchProvider,
                ragDefaultTopK,
                socialKafkaEnabled,
                socialRebuildEnabled,
                tradeKafkaEnabled,
                discoverFailOpenEnabled,
                loginBlacklistEnabled,
                cacheHotkeyEnabled,
                localCacheMaxEntriesPerRegion,
                cacheService.localRegionCount(),
                buildModuleStatuses()
        );
    }

    @Override
    public List<PlatformCacheRegionData> listCacheRegions() {
        List<PlatformCacheRegionData> regions = new ArrayList<PlatformCacheRegionData>();
        for (Map.Entry<String, CacheService.LocalRegionSnapshot> entry : cacheService.snapshotLocalRegionStats().entrySet()) {
            CacheService.LocalRegionSnapshot snapshot = entry.getValue();
            regions.add(new PlatformCacheRegionData(
                    entry.getKey(),
                    snapshot.size(),
                    snapshot.maxEntries(),
                    snapshot.hitCount(),
                    snapshot.missCount(),
                    snapshot.expiredCount(),
                    snapshot.manualEvictionCount(),
                    snapshot.capacityEvictionCount()
            ));
        }
        regions.sort(Comparator
                .comparingInt(PlatformCacheRegionData::size).reversed()
                .thenComparing(PlatformCacheRegionData::region));
        return regions;
    }

    @Override
    public PlatformCacheMetricsData getCacheMetrics() {
        CacheService.CacheMetricsSnapshot snapshot = cacheService.snapshotMetrics();
        return new PlatformCacheMetricsData(
                snapshot.localHitCount(),
                snapshot.localMissCount(),
                snapshot.localExpiredCount(),
                snapshot.localManualEvictionCount(),
                snapshot.localCapacityEvictionCount(),
                snapshot.redisReadFailureCount(),
                snapshot.redisWriteFailureCount(),
                snapshot.redisDeleteFailureCount(),
                snapshot.redisPatternDeleteFailureCount(),
                snapshot.redisPatternDeletedKeyCount()
        );
    }

    @Override
    public List<PlatformThreadPoolData> listThreadPools() {
        List<PlatformThreadPoolData> pools = new ArrayList<PlatformThreadPoolData>();
        pools.add(toThreadPoolData("tradeOrderExecutor", tradeOrderExecutor, threadPoolProperties.getTradeOrder()));
        pools.add(toThreadPoolData("ragQueryExecutor", ragQueryExecutor, threadPoolProperties.getRagQuery()));
        return pools;
    }

    @Override
    public String evictCache(PlatformCacheEvictRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "清理请求不能为空");
        }

        boolean touched = false;
        if (StringUtils.hasText(request.region()) && StringUtils.hasText(request.localKey())) {
            cacheService.evictLocal(request.region().trim(), request.localKey().trim());
            touched = true;
        } else if (StringUtils.hasText(request.region())) {
            cacheService.evictLocalRegion(request.region().trim());
            touched = true;
        }
        if (StringUtils.hasText(request.redisKey())) {
            cacheService.deleteRedis(request.redisKey().trim());
            touched = true;
        }
        if (StringUtils.hasText(request.redisPattern())) {
            cacheService.deleteRedisByPattern(request.redisPattern().trim());
            touched = true;
        }
        if (!touched) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "请至少提供本地区域、本地缓存键、Redis 键或 Redis pattern 中的一项"
            );
        }
        return "缓存清理请求已执行";
    }

    private PlatformThreadPoolData toThreadPoolData(
            String name,
            Executor executor,
            ThreadPoolProperties.PoolProperties properties
    ) {
        AdaptiveBufferedThreadPoolExecutor adaptiveExecutor = executor instanceof AdaptiveBufferedThreadPoolExecutor
                ? (AdaptiveBufferedThreadPoolExecutor) executor
                : null;
        return new PlatformThreadPoolData(
                name,
                properties.getThreadNamePrefix(),
                properties.getCorePoolSize(),
                properties.getMaximumPoolSize(),
                properties.getQueueCapacity(),
                properties.isPreventRejection(),
                adaptiveExecutor == null ? 0 : adaptiveExecutor.getPoolSize(),
                adaptiveExecutor == null ? 0 : adaptiveExecutor.getActiveCount(),
                adaptiveExecutor == null ? 0 : adaptiveExecutor.getQueuedTaskCount(),
                adaptiveExecutor == null ? 0L : adaptiveExecutor.getTaskCount(),
                adaptiveExecutor == null ? 0L : adaptiveExecutor.getCompletedTaskCount(),
                adaptiveExecutor == null ? 0 : adaptiveExecutor.getLargestPoolSize(),
                adaptiveExecutor == null ? 0L : adaptiveExecutor.getRejectedCount(),
                adaptiveExecutor == null ? 0.0D : adaptiveExecutor.getCpuLoad()
        );
    }

    private List<String> resolveActiveProfiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles == null || profiles.length == 0) {
            String[] defaults = environment.getDefaultProfiles();
            if (defaults == null || defaults.length == 0) {
                return List.of("default");
            }
            return Arrays.asList(defaults);
        }
        return Arrays.asList(profiles);
    }

    private List<PlatformModuleStatusData> buildModuleStatuses() {
        List<PlatformModuleStatusData> modules = new ArrayList<PlatformModuleStatusData>();
        modules.add(new PlatformModuleStatusData("auth", "COMPLETED", "认证主链已闭环"));
        modules.add(new PlatformModuleStatusData("content", "COMPLETED", "内容发布主链已闭环"));
        modules.add(new PlatformModuleStatusData("social", "COMPLETED", "社交互动与计数主链已闭环"));
        modules.add(new PlatformModuleStatusData("feed", "COMPLETED", "首页 Feed 主链已闭环"));
        modules.add(new PlatformModuleStatusData("storage", "BASIC_READY", "独立存储入口已接通"));
        modules.add(new PlatformModuleStatusData("search", "BASIC_READY", "搜索与索引同步主链可用"));
        modules.add(new PlatformModuleStatusData("profile", "BASIC_READY", "个人主页基础聚合已可用"));
        modules.add(new PlatformModuleStatusData("discover", "BASIC_READY", "发现模块已接入互动元数据联动"));
        modules.add(new PlatformModuleStatusData("llm", "BASIC_READY", "已具备真实接模与 RAG 检索骨架"));
        modules.add(new PlatformModuleStatusData("trade", "BASIC_READY", "交易主链已打通，仍在持续增强"));
        modules.add(new PlatformModuleStatusData("cache", "BASIC_READY", "缓存治理与观测能力已落地"));
        modules.add(new PlatformModuleStatusData("threadpool", "BASIC_READY", "线程池双模式与运行摘要已可观测"));
        return modules;
    }
}
