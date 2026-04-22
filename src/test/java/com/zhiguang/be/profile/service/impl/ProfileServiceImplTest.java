package com.zhiguang.be.profile.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.service.ContentService;
import com.zhiguang.be.profile.mapper.ProfileMapper;
import com.zhiguang.be.profile.model.ProfileAvatarRequest;
import com.zhiguang.be.profile.model.ProfileData;
import com.zhiguang.be.profile.model.ProfileUserRow;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import com.zhiguang.be.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileServiceImplTest {

    private ProfileMapper profileMapper;
    private StorageService storageService;
    private ProfileServiceImpl profileService;

    @BeforeEach
    void setUp() {
        profileMapper = mock(ProfileMapper.class);
        FollowService followService = mock(FollowService.class);
        UserSocialCounterService userSocialCounterService = mock(UserSocialCounterService.class);
        ContentService contentService = mock(ContentService.class);
        storageService = mock(StorageService.class);

        when(userSocialCounterService.getUserSocialCounter(7L)).thenReturn(
                new UserSocialCounterData("7", 1, 2, 3, 4, 5)
        );
        when(followService.relationStatus(7L, 7L)).thenReturn(new RelationStatusData(false, false, false));

        profileService = new ProfileServiceImpl(
                profileMapper,
                followService,
                userSocialCounterService,
                contentService,
                storageService,
                new ObjectMapper()
        );
    }

    @Test
    void updateAvatarShouldPreferObjectKeyAndStoreNormalizedUrl() {
        when(profileMapper.findByUserId(7L)).thenReturn(
                row(null),
                row("https://mock-oss.local/public/avatars/7/20260422/avatar.png")
        );
        when(storageService.normalizeOwnedAvatarUrl(7L, "avatars/7/20260422/avatar.png")).thenReturn(
                "https://mock-oss.local/public/avatars/7/20260422/avatar.png"
        );

        ProfileData result = profileService.updateAvatar(
                7L,
                new ProfileAvatarRequest(null, "avatars/7/20260422/avatar.png")
        );

        verify(storageService).normalizeOwnedAvatarUrl(7L, "avatars/7/20260422/avatar.png");
        verify(profileMapper).updateProfile(
                eq(7L),
                isNull(),
                eq("https://mock-oss.local/public/avatars/7/20260422/avatar.png"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        );
        assertEquals("https://mock-oss.local/public/avatars/7/20260422/avatar.png", result.avatar());
        assertTrue(result.self());
    }

    @Test
    void updateAvatarShouldRejectMissingReference() {
        when(profileMapper.findByUserId(7L)).thenReturn(row(null));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> profileService.updateAvatar(7L, new ProfileAvatarRequest(" ", " "))
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
        verify(profileMapper, never()).updateProfile(
                eq(7L),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        );
    }

    private ProfileUserRow row(String avatar) {
        return new ProfileUserRow(
                7L,
                "13800000000",
                "demo",
                "demo@example.com",
                "Demo",
                avatar,
                "bio",
                "unknown",
                null,
                "Tongji",
                null,
                "active"
        );
    }
}
