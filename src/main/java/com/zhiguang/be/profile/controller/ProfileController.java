package com.zhiguang.be.profile.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.auth.security.JwtSubjects;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.profile.model.ProfileAvatarRequest;
import com.zhiguang.be.profile.model.ProfileData;
import com.zhiguang.be.profile.model.ProfileListData;
import com.zhiguang.be.profile.model.ProfilePatchRequest;
import com.zhiguang.be.profile.service.ProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人资料控制器。
 * 对外提供“我的资料”“用户主页”“用户已发布内容”等接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;

    /**
     * 注入个人资料服务。
     */
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 查询当前登录用户资料。
     */
    @GetMapping("/me")
    public ApiResponse<ProfileData> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(profileService.me(JwtSubjects.requireUserId(jwt)));
    }

    /**
     * 局部更新当前登录用户资料。
     */
    @PatchMapping("/me")
    public ApiResponse<ProfileData> patchMe(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProfilePatchRequest request
    ) {
        return ApiResponse.success(profileService.updateProfile(JwtSubjects.requireUserId(jwt), request));
    }

    /**
     * 单独更新当前登录用户头像。
     */
    @PostMapping("/avatar")
    public ApiResponse<ProfileData> updateAvatar(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProfileAvatarRequest request
    ) {
        return ApiResponse.success(profileService.updateAvatar(JwtSubjects.requireUserId(jwt), request));
    }

    /**
     * 查询指定用户主页资料。
     * 支持匿名访问；如果携带合法 access token，则补充关系态与 self 信息。
     */
    @GetMapping("/users/{userId}")
    public ApiResponse<ProfileData> getProfile(
            @PathVariable @Min(1) long userId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(profileService.getProfile(JwtSubjects.optionalUserId(jwt), userId));
    }

    /**
     * 查询指定用户主页可见的已发布内容。
     */
    @GetMapping("/users/{userId}/posts")
    public ApiResponse<PostPageData> getPublishedPosts(
            @PathVariable @Min(1) long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(profileService.getPublishedPosts(JwtSubjects.optionalUserId(jwt), userId, page, size));
    }

    /**
     * 查询指定用户的关注列表资料视图。
     */
    @GetMapping("/users/{userId}/following")
    public ApiResponse<ProfileListData> getFollowingProfiles(
            @PathVariable @Min(1) long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(profileService.getFollowingProfiles(JwtSubjects.optionalUserId(jwt), userId, page, size));
    }

    /**
     * 查询指定用户的粉丝列表资料视图。
     */
    @GetMapping("/users/{userId}/followers")
    public ApiResponse<ProfileListData> getFollowerProfiles(
            @PathVariable @Min(1) long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(profileService.getFollowerProfiles(JwtSubjects.optionalUserId(jwt), userId, page, size));
    }

}
