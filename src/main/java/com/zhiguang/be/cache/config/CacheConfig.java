package com.zhiguang.be.cache.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存模块配置入口。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {
}
