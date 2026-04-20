package com.zhiguang.be.social.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.social.FollowActionData;
import com.zhiguang.be.social.FollowListData;
import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.service.FollowService;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注关系控制器。
 */
@Validated
@RestController
@RequestMapping("/api/v1/follows")
public class FollowController {

    private final FollowService followService;

    /**
     * 构造关注关系控制器。
     *
     * @param followService 关注服务
     */
    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /**
     * 关注目标用户。
     *
     * @param followeeId 目标用户 ID
     * @param jwt 当前登录态
     * @return 关注动作结果
     */
    @PostMapping("/{followeeId}")
    public ApiResponse<FollowActionData> follow(
            @PathVariable long followeeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(followService.follow(currentUserId(jwt), followeeId));
    }

    /**
     * 取消关注目标用户。
     *
     * @param followeeId 目标用户 ID
     * @param jwt 当前登录态
     * @return 取消关注结果
     */
    @DeleteMapping("/{followeeId}")
    public ApiResponse<FollowActionData> unfollow(
            @PathVariable long followeeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(followService.unfollow(currentUserId(jwt), followeeId));
    }

    /**
     * 查询用户关注列表。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 每页条数
     * @return 关注列表
     */
    @GetMapping("/users/{userId}/following")
    public ApiResponse<FollowListData> following(
            @PathVariable long userId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size
    ) {
        return ApiResponse.success(followService.following(userId, page, size));
    }

    /**
     * 查询用户粉丝列表。
     *
     * @param userId 目标用户 ID
     * @param page 页码
     * @param size 每页条数
     * @return 粉丝列表
     */
    @GetMapping("/users/{userId}/followers")
    public ApiResponse<FollowListData> followers(
            @PathVariable long userId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size
    ) {
        return ApiResponse.success(followService.followers(userId, page, size));
    }

    /**
     * 查询当前查看者与目标用户之间的关系状态。
     * 支持匿名查看。
     *
     * @param targetUserId 目标用户 ID
     * @param jwt 当前登录态
     * @return 关系状态结果
     */
    @GetMapping("/status")
    public ApiResponse<RelationStatusData> relationStatus(
            @RequestParam long targetUserId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(followService.relationStatus(currentViewerId(jwt), targetUserId));
    }

    /**
     * 从 JWT 中解析当前用户 ID。
     *
     * @param jwt 当前登录态
     * @return 当前用户 ID
     */
    private long currentUserId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (Exception ex) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED,
                    HttpStatus.UNAUTHORIZED,
                    "无效的登录态"
            );
        }
    }

    /**
     * 解析当前查看者 ID。
     *
     * @param jwt 当前登录态
     * @return 当前查看者 ID，匿名用户返回 0
     */
    private long currentViewerId(Jwt jwt) {
        if (jwt == null) {
            return 0L;
        }
        return currentUserId(jwt);
    }
}
