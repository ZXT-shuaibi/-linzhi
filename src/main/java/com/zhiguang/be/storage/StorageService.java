package com.zhiguang.be.storage;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对象存储服务。
 * 统一负责预签名地址生成、公开 URL 规则和资源归属校验。
 */
@Service
public class StorageService {

    private final StorageProperties storageProperties;
    private final KnowPostMapper knowPostMapper;

    public StorageService(StorageProperties storageProperties, KnowPostMapper knowPostMapper) {
        this.storageProperties = storageProperties;
        this.knowPostMapper = knowPostMapper;
    }

    /**
     * 生成上传预签名。
     */
    public StoragePresignData createPresign(long currentUserId, StoragePresignRequest request) {
        String scene = request.scene().trim();
        String safeFilename = sanitizeFilename(request.filename());
        String objectKey;
        if ("profile_avatar".equals(scene)) {
            objectKey = "avatars/" + currentUserId + "/"
                    + randomToken() + "-" + safeFilename;
        } else {
            String postId = requireOwnedPostId(currentUserId, request.postId());
            if ("knowpost_content".equals(scene)) {
                objectKey = "posts/" + postId + "/content/"
                        + randomToken() + "-" + safeFilename;
            } else {
                objectKey = "posts/" + postId + "/images/"
                        + randomToken() + "-" + safeFilename;
            }
        }

        Instant expireAt = Instant.now().plusSeconds(storageProperties.getPresignExpireSeconds());
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", request.contentType().trim());
        return new StoragePresignData(
                buildUploadUrl(objectKey),
                objectKey,
                toPublicUrl(objectKey),
                expireAt,
                headers
        );
    }

    /**
     * 将对象键转换为公开访问地址。
     */
    public String toPublicUrl(String objectKey) {
        return normalizeBaseUrl(storageProperties.getPublicBaseUrl()) + "/" + objectKey;
    }

    /**
     * 校验图片资源是否属于指定帖子，并统一返回公开地址。
     */
    public String normalizeOwnedPostImageUrl(String postId, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String objectKeyPrefix = "posts/" + postId + "/images/";
        if (normalized.startsWith(objectKeyPrefix)) {
            return toPublicUrl(normalized);
        }
        String publicPrefix = toPublicUrl(objectKeyPrefix);
        if (normalized.startsWith(publicPrefix)) {
            return normalized;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "图片资源与当前帖子不匹配");
    }

    /**
     * 校验头像资源是否属于当前用户，并统一返回公开地址。
     */
    public String normalizeOwnedAvatarUrl(long currentUserId, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String objectKeyPrefix = "avatars/" + currentUserId + "/";
        if (normalized.startsWith(objectKeyPrefix)) {
            return toPublicUrl(normalized);
        }
        String publicPrefix = toPublicUrl(objectKeyPrefix);
        if (normalized.startsWith(publicPrefix)) {
            return normalized;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "头像资源与当前用户不匹配");
    }

    private String requireOwnedPostId(long currentUserId, String postId) {
        if (postId == null || postId.isBlank() || !postId.matches("^\\d+$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "postId 非法");
        }
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "帖子不存在");
        }
        if (entity.creatorId() == null || !entity.creatorId().equals(String.valueOf(currentUserId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "无权为该帖子申请上传地址");
        }
        return postId;
    }

    private String buildUploadUrl(String objectKey) {
        return normalizeBaseUrl(storageProperties.getMockUploadBaseUrl()) + "/"
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    }

    private String sanitizeFilename(String filename) {
        String trimmed = filename.trim();
        String safe = trimmed
                .replace("\\", "-")
                .replace("/", "-")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "filename 非法");
        }
        return safe;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "对象存储基础地址未配置");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
