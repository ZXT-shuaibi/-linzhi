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

    private String provider = "mock";
    private String mockUploadBaseUrl = "https://mock-oss.local/upload";
    private String publicBaseUrl = "https://mock-oss.local/public";
    private long presignExpireSeconds = 600L;
    private long multipartPartSizeBytes = 5L * 1024L * 1024L;
    private final Oss oss = new Oss();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

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

    public long getMultipartPartSizeBytes() {
        return multipartPartSizeBytes;
    }

    public void setMultipartPartSizeBytes(long multipartPartSizeBytes) {
        this.multipartPartSizeBytes = multipartPartSizeBytes;
    }

    public Oss getOss() {
        return oss;
    }

    public static class Oss {
        private String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        private String bucketName;
        private String publicBaseUrl;
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucketName() {
            return bucketName;
        }

        public void setBucketName(String bucketName) {
            this.bucketName = bucketName;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        public String getSecurityToken() {
            return securityToken;
        }

        public void setSecurityToken(String securityToken) {
            this.securityToken = securityToken;
        }
    }
}
