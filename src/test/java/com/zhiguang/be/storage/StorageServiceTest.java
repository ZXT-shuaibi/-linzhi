package com.zhiguang.be.storage;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    private KnowPostMapper knowPostMapper;
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        knowPostMapper = mock(KnowPostMapper.class);
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setMockUploadBaseUrl("https://mock-oss.local/upload");
        storageProperties.setPublicBaseUrl("https://mock-oss.local/public");
        storageProperties.setPresignExpireSeconds(600L);
        storageService = new StorageService(storageProperties, knowPostMapper);
    }

    @Test
    void createPresignShouldInferAvatarExtensionAndOwnedPrefix() {
        StoragePresignData data = storageService.createPresign(
                7L,
                new StoragePresignRequest("profile_avatar", null, "my avatar.png", "IMAGE/PNG", null)
        );

        assertTrue(data.objectKey().startsWith("avatars/7/"));
        assertTrue(data.objectKey().endsWith(".png"));
        assertTrue(data.uploadUrl().contains("avatars%2F7%2F"));
        assertEquals("image/png", data.headers().get("Content-Type"));
        assertEquals("https://mock-oss.local/public/" + data.objectKey(), data.publicUrl());
        assertNotNull(data.expireAt());
    }

    @Test
    void createPresignShouldUseOwnedPostImageDirectory() {
        when(knowPostMapper.findById("1001")).thenReturn(post("1001", "7"));

        StoragePresignData data = storageService.createPresign(
                7L,
                new StoragePresignRequest("knowpost_image", "1001", "cover.webp", "image/webp", null)
        );

        assertTrue(data.objectKey().startsWith("posts/1001/images/"));
        assertTrue(data.objectKey().endsWith(".webp"));
    }

    @Test
    void createPresignShouldGenerateOssPutUrlWhenProviderIsOss() {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setProvider("oss");
        storageProperties.setPresignExpireSeconds(600L);
        storageProperties.getOss().setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        storageProperties.getOss().setBucketName("zhiguang-test");
        storageProperties.getOss().setAccessKeyId("test-ak");
        storageProperties.getOss().setAccessKeySecret("test-sk");
        storageService = new StorageService(storageProperties, knowPostMapper);

        StoragePresignData data = storageService.createPresign(
                7L,
                new StoragePresignRequest("profile_avatar", null, "avatar.png", "image/png", null)
        );

        assertTrue(data.uploadUrl().startsWith("https://zhiguang-test.oss-cn-hangzhou.aliyuncs.com/avatars/7/"));
        assertTrue(data.uploadUrl().contains("OSSAccessKeyId=test-ak"));
        assertTrue(data.uploadUrl().contains("Signature="));
        assertEquals("https://zhiguang-test.oss-cn-hangzhou.aliyuncs.com/" + data.objectKey(), data.publicUrl());
        assertEquals("image/png", data.headers().get("Content-Type"));
    }

    @Test
    void createPresignShouldRejectTextContentTypeForImageScene() {
        when(knowPostMapper.findById("1001")).thenReturn(post("1001", "7"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> storageService.createPresign(
                        7L,
                        new StoragePresignRequest("knowpost_image", "1001", "cover.txt", "text/plain", null)
                )
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
    }

    private KnowPostEntity post(String postId, String creatorId) {
        return new KnowPostEntity(
                postId,
                creatorId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "draft",
                Instant.now(),
                Instant.now(),
                null
        );
    }
}
