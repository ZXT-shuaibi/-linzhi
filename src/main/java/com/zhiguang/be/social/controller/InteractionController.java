package com.zhiguang.be.social.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.social.InteractionActionData;
import com.zhiguang.be.social.InteractionSummary;
import com.zhiguang.be.social.service.InteractionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞与收藏控制器。
 * 对外暴露点赞、取消点赞、收藏、取消收藏和互动汇总查询接口。
 */
@RestController
@RequestMapping("/api/v1/interactions")
public class InteractionController {

    private final InteractionService interactionService;

    /**
     * 构造点赞与收藏控制器。
     *
     * @param interactionService 点赞与收藏服务
     */
    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * 对目标内容执行点赞。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前访问令牌
     * @return 点赞动作结果
     */
    @PostMapping("/targets/{targetType}/{targetId}/like")
    public ApiResponse<InteractionActionData> like(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.like(currentUserId(jwt), targetType, targetId));
    }

    /**
     * 对目标内容取消点赞。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前访问令牌
     * @return 取消点赞动作结果
     */
    @DeleteMapping("/targets/{targetType}/{targetId}/like")
    public ApiResponse<InteractionActionData> unlike(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.unlike(currentUserId(jwt), targetType, targetId));
    }

    /**
     * 对目标内容执行收藏。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前访问令牌
     * @return 收藏动作结果
     */
    @PostMapping("/targets/{targetType}/{targetId}/favorite")
    public ApiResponse<InteractionActionData> favorite(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.favorite(currentUserId(jwt), targetType, targetId));
    }

    /**
     * 对目标内容取消收藏。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前访问令牌
     * @return 取消收藏动作结果
     */
    @DeleteMapping("/targets/{targetType}/{targetId}/favorite")
    public ApiResponse<InteractionActionData> unfavorite(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.unfavorite(currentUserId(jwt), targetType, targetId));
    }

    /**
     * 查询目标内容的互动汇总。
     * 未登录用户也允许访问，此时只返回计数信息，viewer 相关状态统一按 false 处理。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前访问令牌
     * @return 互动汇总
     */
    @GetMapping("/targets/{targetType}/{targetId}/summary")
    public ApiResponse<InteractionSummary> summary(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.summary(currentViewerId(jwt), targetType, targetId));
    }

    /**
     * 从访问令牌中解析当前登录用户 ID。
     *
     * @param jwt 当前访问令牌
     * @return 当前登录用户 ID
     */
    private long currentUserId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "无效的登录态");
        }
    }

    /**
     * 解析当前查看者用户 ID。
     * 未登录时返回 0，供只读浏览场景使用。
     *
     * @param jwt 当前访问令牌
     * @return 当前查看者用户 ID，匿名用户返回 0
     */
    private long currentViewerId(Jwt jwt) {
        if (jwt == null) {
            return 0L;
        }
        return currentUserId(jwt);
    }
}
