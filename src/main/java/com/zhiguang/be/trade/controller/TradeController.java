package com.zhiguang.be.trade.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.guard.RateLimitDimension;
import com.zhiguang.be.guard.RateLimiter;
import com.zhiguang.be.trade.model.TradeActivityData;
import com.zhiguang.be.trade.model.TradeActivityListData;
import com.zhiguang.be.trade.model.TradeCreateActivityRequest;
import com.zhiguang.be.trade.model.TradeOrderData;
import com.zhiguang.be.trade.model.TradeOrderPageData;
import com.zhiguang.be.trade.model.TradePayRequest;
import com.zhiguang.be.trade.model.TradeOrderStatusData;
import com.zhiguang.be.trade.model.TradeSubmitData;
import com.zhiguang.be.trade.service.TradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易模块控制器。
 * 对外暴露活动查询、活动创建、秒杀下单、支付和我的订单能力。
 */
@Validated
@RestController
@RequestMapping("/api/v1/trade")
public class TradeController {

    private final TradeService tradeService;

    /**
     * 注入交易服务。
     */
    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    /**
     * 查询活动列表。
     */
    @GetMapping("/activities")
    public ApiResponse<TradeActivityListData> listActivities(
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(tradeService.listActivities(stage, page, size));
    }

    /**
     * 查询活动详情。
     */
    @GetMapping("/activities/{activityId}")
    public ApiResponse<TradeActivityData> getActivity(@PathVariable @Min(1) long activityId) {
        return ApiResponse.success(tradeService.getActivity(activityId));
    }

    /**
     * 创建活动。
     * 当前玩具项目先允许登录用户直接创建，后续再接管理后台权限。
     */
    @PostMapping("/activities")
    public ApiResponse<TradeActivityData> createActivity(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TradeCreateActivityRequest request
    ) {
        return ApiResponse.success(tradeService.createActivity(requireUserId(jwt), request));
    }

    /**
     * 提交订单。
     * 交易核心入口启用滑动窗口限流，防止同一用户高频打爆系统。
     */
    @PostMapping("/activities/{activityId}/orders")
    @RateLimiter(
            keyPrefix = "trade:place-order",
            windowMillis = 1000,
            limit = 5,
            message = "下单过于频繁，请稍后再试",
            dimension = RateLimitDimension.USER
    )
    public ApiResponse<TradeSubmitData> placeOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Min(1) long activityId,
            @RequestParam(defaultValue = "1") @Min(1) int quantity
    ) {
        return ApiResponse.success(tradeService.placeOrder(requireUserId(jwt), activityId, quantity));
    }

    /**
     * 模拟支付订单。
     */
    @PostMapping("/orders/{orderNo}/pay")
    public ApiResponse<TradeOrderData> pay(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo,
            @Valid @RequestBody TradePayRequest request
    ) {
        return ApiResponse.success(tradeService.pay(requireUserId(jwt), orderNo, request));
    }

    /**
     * 主动取消当前用户自己的未支付订单。
     */
    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<TradeOrderData> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo
    ) {
        return ApiResponse.success(tradeService.cancelMyOrder(requireUserId(jwt), orderNo));
    }

    /**
     * 查询当前用户订单受理状态。
     */
    @GetMapping("/orders/{orderNo}/status")
    public ApiResponse<TradeOrderStatusData> myOrderStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo
    ) {
        return ApiResponse.success(tradeService.getMyOrderStatus(requireUserId(jwt), orderNo));
    }

    /**
     * 查询当前用户订单列表。
     */
    @GetMapping("/orders/me")
    public ApiResponse<TradeOrderPageData> myOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.success(tradeService.listMyOrders(requireUserId(jwt), status, page, size));
    }

    /**
     * 查询当前用户订单详情。
     */
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<TradeOrderData> myOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo
    ) {
        return ApiResponse.success(tradeService.getMyOrder(requireUserId(jwt), orderNo));
    }

    /**
     * 提取当前登录用户 ID。
     */
    private long requireUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        return Long.parseLong(jwt.getSubject());
    }
}
