package com.zhiguang.be.profile.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.service.ContentService;
import com.zhiguang.be.profile.mapper.ProfileMapper;
import com.zhiguang.be.profile.model.ProfileAvatarRequest;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ProfileServiceImpl implements ProfileService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final Set<String> CLEARABLE_PROFILE_FIELDS = Set.of("bio", "birthday", "school");

    private final ProfileMapper profileMapper;
    private final FollowService followService;
    private final UserSocialCounterService userSocialCounterService;
    private final ContentService contentService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

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

    @Override
    @Transactional(readOnly = true)
    public ProfileData me(long currentUserId) {
        return buildProfileData(currentUserId, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileData getProfile(long viewerUserId, long targetUserId) {
        return buildProfileData(viewerUserId, targetUserId);
    }

    @Override
    @Transactional
    public ProfileData updateProfile(long currentUserId, ProfilePatchRequest request) {
        requireUser(currentUserId);
        Set<String> clearFields = normalizeClearFields(request.clearFields());
        if (!hasAnyUpdateField(request, clearFields)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "No fields submitted for update"
            );
        }

        boolean clearBio = clearFields.contains("bio");
        boolean clearBirthday = clearFields.contains("birthday");
        boolean clearSchool = clearFields.contains("school");

        profileMapper.updateProfile(
                currentUserId,
                normalizeNullableText(request.nickname()),
                null,
                clearBio ? null : normalizeNullableText(request.bio()),
                normalizeGender(request.gender()),
                clearBirthday ? null : request.birthday(),
                clearSchool ? null : normalizeNullableText(request.school()),
                request.tags() == null ? null : toJson(normalizeTags(request.tags())),
                clearBio,
                clearBirthday,
                clearSchool
        );
        return buildProfileData(currentUserId, currentUserId);
    }

    @Override
    @Transactional
    public ProfileData updateAvatar(long currentUserId, ProfileAvatarRequest request) {
        requireUser(currentUserId);
        String avatarReference = resolveAvatarReference(request);
        profileMapper.updateProfile(
                currentUserId,
                null,
                storageService.normalizeOwnedAvatarUrl(currentUserId, avatarReference),
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false
        );
        return buildProfileData(currentUserId, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileListData getFollowingProfiles(long viewerUserId, long targetUserId, int page, int size) {
        requireUser(targetUserId);
        FollowListData followListData = followService.following(targetUserId, page, size);
        return toProfileListData(viewerUserId, followListData);
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileListData getFollowerProfiles(long viewerUserId, long targetUserId, int page, int size) {
        requireUser(targetUserId);
        FollowListData followListData = followService.followers(targetUserId, page, size);
        return toProfileListData(viewerUserId, followListData);
    }

    @Override
    @Transactional(readOnly = true)
    public PostPageData getPublishedPosts(long viewerUserId, long targetUserId, int page, int size) {
        requireUser(targetUserId);
        Long viewerId = viewerUserId > 0L ? viewerUserId : null;
        return contentService.getUserPublished(targetUserId, viewerId, page, size);
    }

    private ProfileData buildProfileData(long viewerUserId, long targetUserId) {
        ProfileUserRow row = requireUser(targetUserId);
        return buildProfileData(viewerUserId, row);
    }

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

    private ProfileUserRow requireUser(long userId) {
        ProfileUserRow row = profileMapper.findByUserId(userId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "User not found");
        }
        return row;
    }

    private boolean hasAnyUpdateField(ProfilePatchRequest request, Set<String> clearFields) {
        return !clearFields.isEmpty()
                || request.nickname() != null
                || request.bio() != null
                || request.gender() != null
                || request.birthday() != null
                || request.school() != null
                || request.tags() != null;
    }

    private Set<String> normalizeClearFields(List<String> clearFields) {
        if (clearFields == null || clearFields.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String rawField : clearFields) {
            if (rawField == null || rawField.isBlank()) {
                continue;
            }
            String field = rawField.trim().toLowerCase(Locale.ROOT);
            if (!CLEARABLE_PROFILE_FIELDS.contains(field)) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        HttpStatus.BAD_REQUEST,
                        "Unsupported clear field: " + rawField
                );
            }
            normalized.add(field);
        }
        return normalized;
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        return value.trim();
    }

    private String resolveAvatarReference(ProfileAvatarRequest request) {
        if (request == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "Avatar payload is required"
            );
        }
        String objectKey = normalizeNullableText(request.objectKey());
        if (objectKey != null && !objectKey.isEmpty()) {
            return objectKey;
        }
        String avatarUrl = normalizeNullableText(request.avatarUrl());
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            return avatarUrl;
        }
        throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                HttpStatus.BAD_REQUEST,
                "Either avatarUrl or objectKey must be provided"
        );
    }

    private String normalizeGender(String gender) {
        if (gender == null) {
            return null;
        }
        return gender.trim().toLowerCase();
    }

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

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize tags"
            );
        }
    }

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
