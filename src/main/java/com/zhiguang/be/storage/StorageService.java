package com.zhiguang.be.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadResult;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.mapper.KnowPostMapper;
import com.zhiguang.be.content.model.KnowPostEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StorageService {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC);

    private final StorageProperties storageProperties;
    private final KnowPostMapper knowPostMapper;

    public StorageService(StorageProperties storageProperties, KnowPostMapper knowPostMapper) {
        this.storageProperties = storageProperties;
        this.knowPostMapper = knowPostMapper;
    }

    public StoragePresignData createPresign(long currentUserId, StoragePresignRequest request) {
        String scene = normalizeScene(request.scene());
        String contentType = normalizeContentType(request.contentType());
        validateContentType(scene, contentType);
        String objectKey = buildObjectKey(
                currentUserId,
                scene,
                request.postId(),
                request.filename(),
                contentType,
                request.ext()
        );

        Instant expireAt = Instant.now().plus(presignTtl());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        return new StoragePresignData(
                buildUploadUrl(objectKey, contentType, expireAt),
                objectKey,
                toPublicUrl(objectKey),
                expireAt,
                headers
        );
    }

    public StorageMultipartInitData initiateMultipartUpload(
            long currentUserId,
            StorageMultipartInitRequest request
    ) {
        String scene = normalizeScene(request.scene());
        String contentType = normalizeContentType(request.contentType());
        validateContentType(scene, contentType);
        if (request.fileSize() == null || request.fileSize() <= 0L) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "fileSize must be positive");
        }
        String objectKey = buildObjectKey(
                currentUserId,
                scene,
                request.postId(),
                request.filename(),
                contentType,
                request.ext()
        );

        Instant expireAt = Instant.now().plus(presignTtl());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        String uploadId = isOssProvider()
                ? initiateOssMultipartUpload(objectKey, contentType)
                : "mock-" + randomToken();
        return new StorageMultipartInitData(
                uploadId,
                objectKey,
                toPublicUrl(objectKey),
                expireAt,
                multipartPartSize(),
                headers
        );
    }

    public StorageMultipartPartPresignData createMultipartPartPresign(
            long currentUserId,
            StorageMultipartPartPresignRequest request
    ) {
        String scene = requireOwnedObjectKey(currentUserId, request.objectKey());
        String contentType = normalizeContentType(request.contentType());
        validateContentType(scene, contentType);
        String uploadId = requireText(request.uploadId(), "uploadId is required");
        if (request.partNumber() < 1 || request.partNumber() > 10000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Invalid partNumber");
        }

        Instant expireAt = Instant.now().plus(presignTtl());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        String uploadUrl = isOssProvider()
                ? buildOssMultipartPartUploadUrl(request.objectKey(), uploadId, request.partNumber(), contentType, expireAt)
                : buildMockMultipartPartUploadUrl(request.objectKey(), uploadId, request.partNumber());
        return new StorageMultipartPartPresignData(uploadUrl, expireAt, headers);
    }

    public StorageMultipartCompleteData completeMultipartUpload(
            long currentUserId,
            StorageMultipartCompleteRequest request
    ) {
        requireOwnedObjectKey(currentUserId, request.objectKey());
        String uploadId = requireText(request.uploadId(), "uploadId is required");
        List<StorageMultipartPart> parts = normalizedParts(request.parts());
        String etag = isOssProvider()
                ? completeOssMultipartUpload(request.objectKey(), uploadId, parts)
                : "\"mock-multipart-" + uploadId + "-" + parts.size() + "\"";
        return new StorageMultipartCompleteData(request.objectKey(), toPublicUrl(request.objectKey()), etag);
    }

    public void abortMultipartUpload(long currentUserId, StorageMultipartAbortRequest request) {
        requireOwnedObjectKey(currentUserId, request.objectKey());
        String uploadId = requireText(request.uploadId(), "uploadId is required");
        if (isOssProvider()) {
            abortOssMultipartUpload(request.objectKey(), uploadId);
        }
    }

    public StorageObjectMetadata validateUploadedObject(String objectKey, String expectedEtag, Long expectedSize) {
        String normalizedObjectKey = requireText(objectKey, "objectKey is required");
        if (!isOssProvider()) {
            String publicBaseUrl = properties.getPublicBaseUrl();
            if (!normalizedObjectKey.startsWith(publicBaseUrl)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Invalid object key for mock provider");
            }
            if (expectedSize != null && expectedSize <= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Invalid object size");
            }
            return new StorageObjectMetadata(
                    normalizedObjectKey,
                    expectedEtag,
                    expectedSize == null ? 0L : expectedSize,
                    null
            );
        }

        StorageObjectMetadata metadata = headOssObject(normalizedObjectKey);
        if (expectedSize != null && metadata.size() != expectedSize) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Uploaded object size mismatch");
        }
        String expected = normalizeEtag(expectedEtag);
        String actual = normalizeEtag(metadata.etag());
        if (StringUtils.hasText(expected) && !expected.equalsIgnoreCase(actual)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Uploaded object ETag mismatch");
        }
        return metadata;
    }

    public String toPublicUrl(String objectKey) {
        if (isOssProvider()) {
            return normalizeBaseUrl(resolveOssPublicBaseUrl()) + "/" + objectKey;
        }
        return normalizeBaseUrl(storageProperties.getPublicBaseUrl()) + "/" + objectKey;
    }

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
        throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                HttpStatus.BAD_REQUEST,
                "Image resource does not belong to the current post"
        );
    }

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
        throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                HttpStatus.BAD_REQUEST,
                "Avatar resource does not belong to the current user"
        );
    }

    private String requireOwnedPostId(long currentUserId, String postId) {
        if (postId == null || postId.isBlank() || !postId.matches("^\\d+$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Invalid postId");
        }
        KnowPostEntity entity = knowPostMapper.findById(postId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "Post not found");
        }
        if (entity.creatorId() == null || !entity.creatorId().equals(String.valueOf(currentUserId))) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "You do not have permission to upload files for this post"
            );
        }
        return postId;
    }

    private String buildObjectKey(
            long currentUserId,
            String scene,
            String postId,
            String filename,
            String contentType,
            String requestedExt
    ) {
        String safeFilename = sanitizeFilename(filename);
        String ext = normalizeExt(requestedExt, contentType, scene);

        if ("profile_avatar".equals(scene)) {
            return "avatars/" + currentUserId + "/" + currentDateSegment() + "/"
                    + randomToken() + "-" + safeFilename + ext;
        }

        String ownedPostId = requireOwnedPostId(currentUserId, postId);
        if ("knowpost_content".equals(scene)) {
            return "posts/" + ownedPostId + "/content/"
                    + randomToken() + "-" + safeFilename + ext;
        }
        return "posts/" + ownedPostId + "/images/" + currentDateSegment() + "/"
                + randomToken() + "-" + safeFilename + ext;
    }

    private String requireOwnedObjectKey(long currentUserId, String objectKey) {
        String normalized = requireText(objectKey, "objectKey is required");
        String avatarPrefix = "avatars/" + currentUserId + "/";
        if (normalized.startsWith(avatarPrefix)) {
            return "profile_avatar";
        }
        if (!normalized.startsWith("posts/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Unsupported objectKey");
        }
        int postIdStart = "posts/".length();
        int postIdEnd = normalized.indexOf('/', postIdStart);
        if (postIdEnd <= postIdStart) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Invalid objectKey");
        }
        String postId = requireOwnedPostId(currentUserId, normalized.substring(postIdStart, postIdEnd));
        String contentPrefix = "posts/" + postId + "/content/";
        if (normalized.startsWith(contentPrefix)) {
            return "knowpost_content";
        }
        String imagePrefix = "posts/" + postId + "/images/";
        if (normalized.startsWith(imagePrefix)) {
            return "knowpost_image";
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Unsupported objectKey");
    }

    private String buildUploadUrl(String objectKey, String contentType, Instant expireAt) {
        if (isOssProvider()) {
            return buildOssUploadUrl(objectKey, contentType, expireAt);
        }
        return normalizeBaseUrl(storageProperties.getMockUploadBaseUrl()) + "/"
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    }

    private String buildOssUploadUrl(String objectKey, String contentType, Instant expireAt) {
        String bucketName = requireOssBucketName();
        OSS ossClient = buildConfiguredOssClient();
        try {
            GeneratePresignedUrlRequest presignedUrlRequest =
                    new GeneratePresignedUrlRequest(bucketName, objectKey, HttpMethod.PUT);
            presignedUrlRequest.setExpiration(Date.from(expireAt));
            presignedUrlRequest.setContentType(contentType);
            return ossClient.generatePresignedUrl(presignedUrlRequest).toString();
        } finally {
            ossClient.shutdown();
        }
    }

    private String initiateOssMultipartUpload(String objectKey, String contentType) {
        String bucketName = requireOssBucketName();
        OSS ossClient = buildConfiguredOssClient();
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);
            InitiateMultipartUploadRequest request =
                    new InitiateMultipartUploadRequest(bucketName, objectKey, metadata);
            InitiateMultipartUploadResult result = ossClient.initiateMultipartUpload(request);
            return result.getUploadId();
        } finally {
            ossClient.shutdown();
        }
    }

    private String buildOssMultipartPartUploadUrl(
            String objectKey,
            String uploadId,
            int partNumber,
            String contentType,
            Instant expireAt
    ) {
        String bucketName = requireOssBucketName();
        OSS ossClient = buildConfiguredOssClient();
        try {
            GeneratePresignedUrlRequest presignedUrlRequest =
                    new GeneratePresignedUrlRequest(bucketName, objectKey, HttpMethod.PUT);
            presignedUrlRequest.setExpiration(Date.from(expireAt));
            presignedUrlRequest.setContentType(contentType);
            presignedUrlRequest.addQueryParameter("uploadId", uploadId);
            presignedUrlRequest.addQueryParameter("partNumber", String.valueOf(partNumber));
            return ossClient.generatePresignedUrl(presignedUrlRequest).toString();
        } finally {
            ossClient.shutdown();
        }
    }

    private String completeOssMultipartUpload(
            String objectKey,
            String uploadId,
            List<StorageMultipartPart> parts
    ) {
        String bucketName = requireOssBucketName();
        List<PartETag> partETags = parts.stream()
                .map(part -> new PartETag(part.partNumber(), normalizeEtag(part.etag())))
                .toList();
        OSS ossClient = buildConfiguredOssClient();
        try {
            CompleteMultipartUploadRequest request =
                    new CompleteMultipartUploadRequest(bucketName, objectKey, uploadId, partETags);
            CompleteMultipartUploadResult result = ossClient.completeMultipartUpload(request);
            return result.getETag();
        } finally {
            ossClient.shutdown();
        }
    }

    private void abortOssMultipartUpload(String objectKey, String uploadId) {
        String bucketName = requireOssBucketName();
        OSS ossClient = buildConfiguredOssClient();
        try {
            ossClient.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, objectKey, uploadId));
        } finally {
            ossClient.shutdown();
        }
    }

    private StorageObjectMetadata headOssObject(String objectKey) {
        String bucketName = requireOssBucketName();
        OSS ossClient = buildConfiguredOssClient();
        try {
            ObjectMetadata metadata = ossClient.getObjectMetadata(bucketName, objectKey);
            return new StorageObjectMetadata(
                    objectKey,
                    metadata.getETag(),
                    metadata.getContentLength(),
                    metadata.getContentType()
            );
        } finally {
            ossClient.shutdown();
        }
    }

    private String buildMockMultipartPartUploadUrl(String objectKey, String uploadId, int partNumber) {
        return normalizeBaseUrl(storageProperties.getMockUploadBaseUrl()) + "/"
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
                + "?uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8)
                + "&partNumber=" + partNumber;
    }

    private OSS buildConfiguredOssClient() {
        StorageProperties.Oss ossProperties = storageProperties.getOss();
        String endpoint = requireText(ossProperties.getEndpoint(), "OSS endpoint is not configured");
        String accessKeyId = resolveCredential(
                ossProperties.getAccessKeyId(),
                "ALIYUN_OSS_ACCESS_KEY_ID",
                "ALIBABA_CLOUD_ACCESS_KEY_ID"
        );
        String accessKeySecret = resolveCredential(
                ossProperties.getAccessKeySecret(),
                "ALIYUN_OSS_ACCESS_KEY_SECRET",
                "ALIBABA_CLOUD_ACCESS_KEY_SECRET"
        );
        requireText(accessKeyId, "OSS access key ID is not configured");
        requireText(accessKeySecret, "OSS access key secret is not configured");
        return buildOssClient(endpoint, accessKeyId, accessKeySecret, ossProperties.getSecurityToken());
    }

    private String requireOssBucketName() {
        return requireText(storageProperties.getOss().getBucketName(), "OSS bucket name is not configured");
    }

    private OSS buildOssClient(String endpoint, String accessKeyId, String accessKeySecret, String securityToken) {
        if (StringUtils.hasText(securityToken)) {
            return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret, securityToken);
        }
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    private String normalizeScene(String scene) {
        String normalized = scene == null ? null : scene.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Scene is required");
        }
        if (!"knowpost_content".equals(normalized)
                && !"knowpost_image".equals(normalized)
                && !"profile_avatar".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Unsupported scene");
        }
        return normalized;
    }

    private String normalizeContentType(String contentType) {
        String normalized = contentType == null ? null : contentType.trim().toLowerCase();
        if (normalized == null || normalized.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "Content type is required"
            );
        }
        return normalized;
    }

    private void validateContentType(String scene, String contentType) {
        if ("profile_avatar".equals(scene) || "knowpost_image".equals(scene)) {
            if (!contentType.startsWith("image/")) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        HttpStatus.BAD_REQUEST,
                        "This upload scene only supports image content types"
                );
            }
            return;
        }
        if ("knowpost_content".equals(scene)) {
            boolean allowed = contentType.startsWith("text/")
                    || "application/json".equals(contentType)
                    || "application/octet-stream".equals(contentType);
            if (!allowed) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        HttpStatus.BAD_REQUEST,
                        "knowpost_content only supports text, json, or octet-stream content types"
                );
            }
        }
    }

    private String normalizeExt(String ext, String contentType, String scene) {
        if (ext != null && !ext.isBlank()) {
            String normalized = ext.trim().toLowerCase();
            if (normalized.startsWith(".")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.matches("[a-z0-9]{1,15}")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Invalid ext");
            }
            return "." + normalized;
        }
        if ("knowpost_content".equals(scene)) {
            return switch (contentType) {
                case "text/markdown" -> ".md";
                case "text/html" -> ".html";
                case "text/plain" -> ".txt";
                case "application/json" -> ".json";
                default -> ".bin";
            };
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
    }

    private String sanitizeFilename(String filename) {
        String trimmed = filename == null ? "" : filename.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Filename is required");
        }
        int lastDot = trimmed.lastIndexOf('.');
        String stem = lastDot > 0 ? trimmed.substring(0, lastDot) : trimmed;
        String safe = stem
                .replace("\\", "-")
                .replace("/", "-")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty()) {
            return "file";
        }
        return safe;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Storage base URL is not configured"
            );
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean isOssProvider() {
        return "oss".equalsIgnoreCase(storageProperties.getProvider());
    }

    private String resolveOssPublicBaseUrl() {
        StorageProperties.Oss ossProperties = storageProperties.getOss();
        if (StringUtils.hasText(ossProperties.getPublicBaseUrl())) {
            return ossProperties.getPublicBaseUrl();
        }
        String endpoint = requireText(ossProperties.getEndpoint(), "OSS endpoint is not configured");
        String bucketName = requireText(ossProperties.getBucketName(), "OSS bucket name is not configured");
        String normalizedEndpoint = stripScheme(endpoint);
        return "https://" + bucketName + "." + normalizedEndpoint;
    }

    private String stripScheme(String endpoint) {
        String normalized = endpoint.trim();
        if (normalized.startsWith("https://")) {
            return normalized.substring("https://".length());
        }
        if (normalized.startsWith("http://")) {
            return normalized.substring("http://".length());
        }
        return normalized;
    }

    private Duration presignTtl() {
        long seconds = Math.max(storageProperties.getPresignExpireSeconds(), 1L);
        return Duration.ofSeconds(seconds);
    }

    private long multipartPartSize() {
        return Math.max(storageProperties.getMultipartPartSizeBytes(), 5L * 1024L * 1024L);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, message);
        }
        return value.trim();
    }

    private String resolveCredential(String configured, String... envNames) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        for (String envName : envNames) {
            String envValue = System.getenv(envName);
            if (StringUtils.hasText(envValue)) {
                return envValue.trim();
            }
        }
        return null;
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private List<StorageMultipartPart> normalizedParts(List<StorageMultipartPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "parts is required");
        }
        List<StorageMultipartPart> normalized = new ArrayList<>(parts);
        normalized.sort(Comparator.comparingInt(StorageMultipartPart::partNumber));
        return normalized;
    }

    private String normalizeEtag(String etag) {
        if (!StringUtils.hasText(etag)) {
            return "";
        }
        String normalized = etag.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private String currentDateSegment() {
        return DATE_FORMATTER.format(Instant.now());
    }
}
