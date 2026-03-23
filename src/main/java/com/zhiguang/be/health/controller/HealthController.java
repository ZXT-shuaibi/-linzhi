package com.zhiguang.be.health.controller;

import com.zhiguang.be.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器。
 * 提供服务可用性探针接口，用于网关与监控系统探活。
 */
@RestController
@RequestMapping("/api/v1/_meta")
public class HealthController {

    /**
     * 服务存活检查。
     * 作用：返回固定的健康状态，用于负载均衡和监控系统快速判断服务是否可用。
     */
    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(Map.of("status", "ok"));
    }
}
