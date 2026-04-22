package com.zhiguang.be.profile.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.service.ContentService;
import com.zhiguang.be.profile.mapper.ProfileMapper;
import com.zhiguang.be.profile.model.ProfileData;
import com.zhiguang.be.profile.model.ProfileListData;
import com.zhiguang.be.profile.model.ProfileListItem;
import com.zhiguang.be.profile.model.ProfilePatchRequest;
import com.zhiguang.be.profile.model.ProfileUserRow;
import com.zhiguang.be.profile.service.ProfileService;
import com.zhiguang.be.social.FollowListData;
import com.zhiguang.be.social.FollowUserItem;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import com.zhiguang.be.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人模块服务实现。
 * 负责聚合用户基础资料、社交计数、关系态和个人主页内容。
 */
@Service
public class ProfileServiceImpl implements ProfileService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<List<String>>() {
    };

    private final ProfileMapper profileMapper;
    private final FollowService followService;
    private final UserSocialCounterService userSocialCounterService;
    private final ContentService contentService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    /**
     * 注入个人模块依赖。
     */
    public ProfileServiceImpl(
            ProfileMapper profileMapper,
            FollowService followService,
            UserSocialCounterService userSocialCounterService,
            ContentService contentService,
            StorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.profileMapper = profileMapper;
        this.followService = followService;
        this.userSocialCounterService = userSocialCounterService;
        this.contentService = contentService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询当前登录用户资料。
     */
    @Override
    @Transactional(readOnly = true)
    public ProfileData me(long currentUserId) {
        return buildProfileData(currentUserId, currentUserId);
    }

    /**
     * 查询指定用户主页资料。
     */
    @Override
    @Transactional(readOnly = true)
    public ProfileData getProfile(long viewerUserId, long targetUserId) {
        return buildProfileData(viewerUserId, targetUserId);
    }

    /**
     * 局部更新当前用户资料。
     */
    @Override
    @Transactional
    public ProfileData updateProfile(long currentUserId, ProfilePatchRequest request) {
        requireUser(currentUserId);
        if (!hasAnyUpdateField(request)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "未提交任何更新字段");
        }

        profileMapper.updateProfile(
                currentUserId,
                normalizeNullableText(request.nickname()),
                null,
                normalizeNullableText(request.bio()),
                normalizeGender(request.gender()),
                request.birthday(),
                normalizeNullableText(request.school()),
                request.tags() == null ? null : toJson(normalizeTags(request.tags()))
        );
        return buildProfileData(currentUserId, currentUserId);
    }

    /**
     * 单独更新当前用户头像。
     */
    @Override
    @Transactional
    public ProfileData updateAvatar(long currentUserId, String avatarUrl) {
        requireUser(currentUserId);
        profileMapper.updateProfile(
                currentUserId,
                null,
                storageService.normalizeOwnedAvatarUrl(currentUserId, normalizeNullableText(avatarUrl)),
                null,
                null,
                null,
                null,
                null
        );
        return buildProfileData(currentUserId, currentUserId);
    }

    /**
     * 查询指定用户的关注列表资料视图。
     */
    @Override
    @Transactional(readOnly = true)
    public ProfileListData getFollowingProfiles(long viewerUserId, long targetUserId, int page, int size) {
        requireUser(targetUserId);
        FollowListData followListData = followService.following(targetUserId, page, size);
        return toProfileListData(viewerUserId, followListData);
    }

    /**
     * 查询指定用户的粉丝列表资料视图。
     */
    @Override
    @Transactional(readOnly = true)
    public ProfileListData getFollowerProfiles(long viewerUserId, long targetUserId, int page, int size) {
        requireUser(targetUserId);
        FollowListData followListData = followService.followers(targetUserId, page, size);
        return toProfileListData(viewerUserId, followListData);
    }

    /**
     * 查询指定用户主页可见的发布内容。
     */
    @Override
    @Transactional(readOnly = true)
    public PostPageData getPublishedPosts(long viewerUserId, long targetUserId, int page, int size) {
        requireUser(targetUserId);
        String viewerId = viewerUserId > 0L ? String.valueOf(viewerUserId) : null;
        return contentService.getUserPublished(String.valueOf(targetUserId), viewerId, page, size);
    }

    /**
     * 聚合构建个人资料返回结果。
     */
    private ProfileData buildProfileData(long viewerUserId, long targetUserId) {
        ProfileUserRow row = requireUser(targetUserId);
        return buildProfileData(viewerUserId, row);
    }

    /**
     * 基于已读取的用户行对象构建个人资料结果。
     */
    private ProfileData buildProfileData(long viewerUserId, ProfileUserRow row) {
        long targetUserId = row.userId();
        boolean self = viewerUserId > 0L && viewerUserId == targetUserId;
        UserSocialCounterData socialCounters = userSocialCounterService.getUserSocialCounter(targetUserId);
        RelationStatusData relationStatus = followService.relationStatus(viewerUserId, targetUserId);
        return new ProfileData(
                String.valueOf(row.userId()),
                self ? row.phone() : null,
                self ? row.account() : null,
                self ? row.email() : null,
                row.nickname(),
                row.avatar(),
                row.bio(),
                row.gender(),
                row.birthday(),
                row.school(),
                parseTags(row.tagsJson()),
                socialCounters,
                relationStatus,
                self
        );
    }

    /**
     * 按用户 ID 读取资料，不存在时抛业务异常。
     */
    private ProfileUserRow requireUser(long userId) {
        ProfileUserRow row = profileMapper.findByUserId(userId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在");
        }
        return row;
    }

    /**
     * 判断 patch 请求里是否至少提交了一个更新字段。
     */
    private boolean hasAnyUpdateField(ProfilePatchRequest request) {
        return request.nickname() != null
                || request.bio() != null
                || request.gender() != null
                || request.birthday() != null
                || request.school() != null
                || request.tags() != null;
    }

    /**
     * 归一化可空文本字段。
     * 仅做 trim；空白串会被转成空字符串写库，便于前端主动清空资料字段。
     */
    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        return value.trim();
    }

    /**
     * 统一归一化性别枚举值。
     */
    private String normalizeGender(String gender) {
        if (gender == null) {
            return null;
        }
        return gender.trim().toLowerCase();
    }

    /**
     * 归一化标签列表。
     */
    private List<String> normalizeTags(List<String> tags) {
        List<String> normalized = new ArrayList<>();
        for (String rawTag : tags) {
            if (rawTag == null) {
                continue;
            }
            String tag = rawTag.trim();
            if (!tag.isEmpty()) {
                normalized.add(tag);
            }
        }
        return normalized;
    }

    /**
     * 将标签 JSON 解析成字符串列表。
     */
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, STRING_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    /**
     * 将标签列表编码成 JSON 字符串。
     */
    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "标签序列化失败");
        }
    }

    /**
     * 将社交模块关注列表转换成 profile 模块的资料卡片列表。
     */
    private ProfileListData toProfileListData(long viewerUserId, FollowListData followListData) {
        List<Long> userIds = new ArrayList<>();
        for (FollowUserItem item : followListData.getItems()) {
            userIds.add(Long.parseLong(item.getUserId()));
        }

        Map<Long, ProfileUserRow> profileMap = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (ProfileUserRow row : profileMapper.listByUserIds(userIds)) {
                profileMap.put(row.userId(), row);
            }
        }

        List<ProfileListItem> items = new ArrayList<>();
        for (FollowUserItem item : followListData.getItems()) {
            long targetUserId = Long.parseLong(item.getUserId());
            ProfileUserRow row = profileMap.get(targetUserId);
            if (row == null) {
                continue;
            }
            ProfileData profileData = buildProfileData(viewerUserId, row);
            items.add(new ProfileListItem(
                    profileData.userId(),
                    profileData.nickname(),
                    profileData.avatar(),
                    profileData.bio(),
                    profileData.socialCounters(),
                    profileData.relationStatus(),
                    item.getFollowedAt(),
                    profileData.self()
            ));
        }
        return new ProfileListData(items, followListData.getPage());
    }
}
