package com.zhiguang.be.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录风险控制配置。
 * 用于管理验证码阈值、封禁阈值和失败计数有效期。
 */
@Component
@ConfigurationProperties(prefix = "security.login-risk")
public class LoginRiskProperties {

    private int captchaThreshold = 3;
    private int blockThreshold = 10;
    private Duration failureTtl = Duration.ofMinutes(30);

    /**
     * 获取验证码触发阈值。
     *
     * @return 阈值
     */
    public int getCaptchaThreshold() {
        return captchaThreshold;
    }

    /**
     * 设置验证码触发阈值。
     *
     * @param captchaThreshold 阈值
     */
    public void setCaptchaThreshold(int captchaThreshold) {
        this.captchaThreshold = captchaThreshold;
    }

    /**
     * 获取封禁触发阈值。
     *
     * @return 阈值
     */
    public int getBlockThreshold() {
        return blockThreshold;
    }

    /**
     * 设置封禁触发阈值。
     *
     * @param blockThreshold 阈值
     */
    public void setBlockThreshold(int blockThreshold) {
        this.blockThreshold = blockThreshold;
    }

    /**
     * 获取失败计数有效期。
     *
     * @return 有效期
     */
    public Duration getFailureTtl() {
        return failureTtl;
    }

    /**
     * 设置失败计数有效期。
     *
     * @param failureTtl 有效期
     */
    public void setFailureTtl(Duration failureTtl) {
        this.failureTtl = failureTtl;
    }
}
