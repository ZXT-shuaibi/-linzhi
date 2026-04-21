package com.zhiguang.be.social.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.social.UserSocialCounterData;
import com.zhiguang.be.social.service.UserSocialCounterService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社交公共能力控制器。
 * 当前先承接用户社交计数读取接口，便于用户主页和作者卡片复用。
 */
@Validated
@RestController
@RequestMapping("/api/v1/social")
public class SocialController {

    private final UserSocialCounterService userSocialCounterService;

    /**
     * 构造社交公共能力控制器。
     *
     * @param userSocialCounterService 用户社交计数服务
     */
    public SocialController(UserSocialCounterService userSocialCounterService) {
        this.userSocialCounterService = userSocialCounterService;
    }

    /**
     * 查询指定用户的社交计数。
     * 支持匿名访问，适合用户主页、作者卡片和内容详情页回填。
     *
     * @param userId 目标用户 ID
     * @return 用户社交计数结果
     */
    @GetMapping("/counters/users/{userId}")
    public ApiResponse<UserSocialCounterData> userCounters(@PathVariable @Min(1) long userId) {
        return ApiResponse.success(userSocialCounterService.getUserSocialCounter(userId));
    }
}
