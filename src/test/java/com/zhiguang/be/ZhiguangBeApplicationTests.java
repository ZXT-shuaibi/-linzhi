package com.zhiguang.be;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用启动测试。
 * 用于验证 Spring Boot 上下文在基础配置下能够成功完成装配。
 */
@SpringBootTest(properties = "security.jwt.allow-ephemeral-keys=true")
class ZhiguangBeApplicationTests {

    /**
     * 验证应用上下文可以正常启动。
     */
    @Test
    void contextLoads() {
    }
}