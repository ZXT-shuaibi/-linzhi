package com.zhiguang.be.common.config;

import com.zhiguang.be.common.util.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花算法配置类。
 * 配置并提供全局唯一的雪花算法ID生成器Bean。
 */
@Configuration
public class SnowflakeConfiguration {

    /**
     * 创建雪花算法ID生成器Bean。
     * 使用固定的workerId=1和datacenterId=1配置。
     * 生产环境应根据实际部署情况配置不同的workerId和datacenterId。
     *
     * @return 雪花算法ID生成器实例
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(1L, 1L);
    }
}
