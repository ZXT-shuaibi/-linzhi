package com.zhiguang.be.auth.model;

/**
 * 注册结果。
 * 用于明确告知前端注册已经完成，但仍需要回到登录流程再次登录。
 *
 * @param userId 用户唯一 ID
 * @param phone 注册手机号
 * @param account 注册账号
 * @param nextAction 下一步动作
 * @param status 当前状态
 */
public record RegisterResult(
        String userId,
        String phone,
        String account,
        String nextAction,
        String status
) {
}
