package com.zhiguang.be.platform.service.impl;

import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.platform.model.PlatformCacheEvictRequest;
import com.zhiguang.be.platform.model.PlatformModuleStatusData;
import com.zhiguang.be.platform.model.PlatformRuntimeData;
import com.zhiguang.be.platform.service.PlatformService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台治理服务实现。
 */
@Service
public class PlatformServiceImpl implements PlatformService {

    private final CacheService cacheService;

    @Value("${spring.application.name:zhiguang-be}")
    private String applicationName;
    @Value("${llm.provider:template}")
    private String llmProvider;
    @Value("${llm.model-name:template-llm}")
    private String llmModel;
    @Value("${social.counter.kafka.enabled:false}")
    private boolean socialKafkaEnabled;
    @Value("${social.rebuild.enabled:false}")
    private boolean socialRebuildEnabled;
    @Value("${trade.kafka.enabled:false}")
    private boolean tradeKafkaEnabled;
    @Value("${discover.lbs.fail-open-on-search-error:false}")
    private boolean discoverFailOpenEnabled;
    @Value("${security.login-blacklist.enabled:true}")
    private boolean loginBlacklistEnabled;
    @Value("${cache.local.max-entries-per-region:1024}")
    private int localCacheMaxEntriesPerRegion;

    public PlatformServiceImpl(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public PlatformRuntimeData getRuntimeSummary() {
        return new PlatformRuntimeData(
                applicationName,
                Instant.now(),
                llmProvider,
                llmModel,
                socialKafkaEnabled,
                socialRebuildEnabled,
                tradeKafkaEnabled,
                discoverFailOpenEnabled,
                loginBlacklistEnabled,
                localCacheMaxEntriesPerRegion,
                buildModuleStatuses()
        );
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
        }
        if (StringUtils.hasText(request.redisKey())) {
            cacheService.deleteRedis(request.redisKey().trim());
            touched = true;
        }
        if (!touched) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请至少提供一个本地缓存键或 Redis 缓存键");
        }
        return "缓存清理请求已执行";
    }

    private List<PlatformModuleStatusData> buildModuleStatuses() {
        List<PlatformModuleStatusData> modules = new ArrayList<PlatformModuleStatusData>();
        modules.add(new PlatformModuleStatusData("auth", "COMPLETED", "认证主链已闭环"));
        modules.add(new PlatformModuleStatusData("content", "COMPLETED", "内容发布主链已闭环"));
        modules.add(new PlatformModuleStatusData("social", "COMPLETED", "社交互动与计数主链已闭环"));
        modules.add(new PlatformModuleStatusData("feed", "COMPLETED", "首页 Feed 主链已闭环"));
        modules.add(new PlatformModuleStatusData("storage", "BASIC_READY", "独立存储入口已接通"));
        modules.add(new PlatformModuleStatusData("search", "BASIC_READY", "公开搜索基础版已可用"));
        modules.add(new PlatformModuleStatusData("profile", "BASIC_READY", "个人主页基础版已可用"));
        modules.add(new PlatformModuleStatusData("discover", "BASIC_READY", "发现模块正在持续收口"));
        modules.add(new PlatformModuleStatusData("llm", "BASIC_READY", "已具备 provider 可切换的接模骨架"));
        modules.add(new PlatformModuleStatusData("trade", "BASIC_READY", "交易主链已打通，仍在持续增强"));
        modules.add(new PlatformModuleStatusData("cache", "BASIC_READY", "独立缓存支持模块已落地"));
        return modules;
    }
}
