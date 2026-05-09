package com.zhiguang.be.social.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.auth.security.JwtSubjects;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.guard.RateLimiter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 互动行为控制器。
 */
@RestController
@RequestMapping("/api/v1/interactions")
public class InteractionController {

    private final InteractionService interactionService;

    /**
     * 构造互动行为控制器。
     *
     * @param interactionService 互动服务
     */
    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * 对目标内容执行点赞。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前登录态
     * @return 点赞动作结果
     */
    @PostMapping("/targets/{targetType}/{targetId}/like")
    @RateLimiter(keyPrefix = "interaction:write", windowMillis = 10_000, limit = 30, message = "Interaction is too frequent")
    public ApiResponse<InteractionActionData> like(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.like(JwtSubjects.requireUserId(jwt), targetType, targetId));
    }

    /**
     * 取消目标内容点赞。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前登录态
     * @return 取消点赞结果
     */
    @DeleteMapping("/targets/{targetType}/{targetId}/like")
    @RateLimiter(keyPrefix = "interaction:write", windowMillis = 10_000, limit = 30, message = "Interaction is too frequent")
    public ApiResponse<InteractionActionData> unlike(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.unlike(JwtSubjects.requireUserId(jwt), targetType, targetId));
    }

    /**
     * 对目标内容执行收藏。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前登录态
     * @return 收藏动作结果
     */
    @PostMapping("/targets/{targetType}/{targetId}/favorite")
    @RateLimiter(keyPrefix = "interaction:write", windowMillis = 10_000, limit = 30, message = "Interaction is too frequent")
    public ApiResponse<InteractionActionData> favorite(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.favorite(JwtSubjects.requireUserId(jwt), targetType, targetId));
    }

    /**
     * 取消目标内容收藏。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前登录态
     * @return 取消收藏结果
     */
    @DeleteMapping("/targets/{targetType}/{targetId}/favorite")
    @RateLimiter(keyPrefix = "interaction:write", windowMillis = 10_000, limit = 30, message = "Interaction is too frequent")
    public ApiResponse<InteractionActionData> unfavorite(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.unfavorite(JwtSubjects.requireUserId(jwt), targetType, targetId));
    }

    /**
     * 查询单个目标的互动汇总。
     * 支持匿名查看。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param jwt 当前登录态
     * @return 互动汇总数据
     */
    @GetMapping("/targets/{targetType}/{targetId}/summary")
    public ApiResponse<InteractionSummary> summary(
            @PathVariable String targetType,
            @PathVariable long targetId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.summary(JwtSubjects.optionalUserId(jwt), targetType, targetId));
    }

    /**
     * 批量查询多个目标的互动汇总。
     * 支持匿名查看。
     *
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @param jwt 当前登录态
     * @return 互动汇总映射
     */
    @GetMapping("/targets/{targetType}/summary-batch")
    public ApiResponse<Map<String, InteractionSummary>> summaryBatch(
            @PathVariable String targetType,
            @RequestParam List<Long> targetIds,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(interactionService.summaryBatch(JwtSubjects.optionalUserId(jwt), targetType, targetIds));
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
                    "\u65e0\u6548\u7684\u767b\u5f55\u6001"
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
