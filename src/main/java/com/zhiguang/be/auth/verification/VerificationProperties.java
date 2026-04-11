package com.zhiguang.be.auth.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 验证码配置属性。
 * 用于集中管理验证码长度、有效期、频率限制和最大尝试次数。
 */
@Component
@ConfigurationProperties(prefix = "security.verification")
public class VerificationProperties {

    private Duration codeTtl = Duration.ofMinutes(10);
    private int codeLength = 6;
    private int maxAttempts = 5;
    private Duration sendInterval = Duration.ofSeconds(60);
    private int dailyLimit = 10;

    /**
     * 获取验证码有效期。
     *
     * @return 有效期
     */
    public Duration getCodeTtl() {
        return codeTtl;
    }

    /**
     * 设置验证码有效期。
     *
     * @param codeTtl 有效期
     */
    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    /**
     * 获取验证码长度。
     *
     * @return 验证码长度
     */
    public int getCodeLength() {
        return codeLength;
    }

    /**
     * 设置验证码长度。
     *
     * @param codeLength 验证码长度
     */
    public void setCodeLength(int codeLength) {
        this.codeLength = codeLength;
    }

    /**
     * 获取最大尝试次数。
     *
     * @return 最大尝试次数
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 设置最大尝试次数。
     *
     * @param maxAttempts 最大尝试次数
     */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * 获取发送间隔限制。
     *
     * @return 发送间隔
     */
    public Duration getSendInterval() {
        return sendInterval;
    }

    /**
     * 设置发送间隔限制。
     *
     * @param sendInterval 发送间隔
     */
    public void setSendInterval(Duration sendInterval) {
        this.sendInterval = sendInterval;
    }

    /**
     * 获取每日发送上限。
     *
     * @return 每日上限
     */
    public int getDailyLimit() {
        return dailyLimit;
    }

    /**
     * 设置每日发送上限。
     *
     * @param dailyLimit 每日上限
     */
    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }
}
