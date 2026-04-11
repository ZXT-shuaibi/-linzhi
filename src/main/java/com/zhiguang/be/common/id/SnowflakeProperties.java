package com.zhiguang.be.common.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Snowflake ID 生成器配置属性。
 */
@ConfigurationProperties(prefix = "snowflake")
public class SnowflakeProperties {

    private long workerId = 0;
    private long datacenterId = 0;

    public long getWorkerId() {
        return workerId;
    }

    public void setWorkerId(long workerId) {
        this.workerId = workerId;
    }

    public long getDatacenterId() {
        return datacenterId;
    }

    public void setDatacenterId(long datacenterId) {
        this.datacenterId = datacenterId;
    }
}
