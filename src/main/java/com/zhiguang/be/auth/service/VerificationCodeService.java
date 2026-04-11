package com.zhiguang.be.auth.service;

import com.zhiguang.be.auth.model.CodeScene;
import com.zhiguang.be.auth.model.SendCodeResult;

/**
 * 验证码服务接口。
 * 支持多场景验证码发送和校验，包含频率控制和尝试次数限制。
 */
public interface VerificationCodeService {

    /**
     * 发送验证码。
     * 包含发送间隔控制（60秒）和每日发送上限（10次）。
     *
     * @param phone 手机号
     * @param scene 使用场景
     * @return 发送结果
     */
    SendCodeResult send(String phone, CodeScene scene);

    /**
     * 验证验证码。
     * 包含尝试次数限制（5次），超限后删除验证码。
     *
     * @param phone 手机号
     * @param scene 使用场景
     * @param code 验证码
     * @return 验证成功返回 true
     */
    boolean verify(String phone, CodeScene scene, String code);
}
