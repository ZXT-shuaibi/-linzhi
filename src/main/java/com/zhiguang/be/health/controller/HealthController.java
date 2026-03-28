package com.zhiguang.be.health.controller;

import com.zhiguang.be.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器。
 * 向网关、监控和部署系统提供最基础的服务存活探针接口。
 */
@RestController
@RequestMapping("/api/v1/_meta")
public class HealthController {

    /**
     * 返回固定的服务健康状态。
     * 当应用能够正常处理请求时，该接口会返回 {@code status=ok}。
     *
     * @return 标准响应包装的健康状态信息
     */
    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(Map.of("status", "ok"));
    }
}