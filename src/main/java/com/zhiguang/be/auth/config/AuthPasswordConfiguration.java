package com.zhiguang.be.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证密码相关配置。
 * 负责注册密码哈希组件，供注册、登录和重置密码流程复用。
 */
@Configuration
public class AuthPasswordConfiguration {

    /**
     * 提供密码加密器。
     * 当前使用 BCrypt 算法并指定较高的强度参数。
     *
     * @return 密码加密器实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}