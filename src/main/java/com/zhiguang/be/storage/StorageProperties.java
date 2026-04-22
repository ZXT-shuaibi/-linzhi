package com.zhiguang.be.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置。
 * 当前默认使用 mock 上传地址和公开访问地址，后续可以平滑切换到真实存储服务。
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String mockUploadBaseUrl = "https://mock-oss.local/upload";
    private String publicBaseUrl = "https://mock-oss.local/public";
    private long presignExpireSeconds = 600L;

    public String getMockUploadBaseUrl() {
        return mockUploadBaseUrl;
    }

    public void setMockUploadBaseUrl(String mockUploadBaseUrl) {
        this.mockUploadBaseUrl = mockUploadBaseUrl;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public long getPresignExpireSeconds() {
        return presignExpireSeconds;
    }

    public void setPresignExpireSeconds(long presignExpireSeconds) {
        this.presignExpireSeconds = presignExpireSeconds;
    }
}
