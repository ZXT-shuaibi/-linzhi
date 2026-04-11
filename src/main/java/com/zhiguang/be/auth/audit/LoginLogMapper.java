package com.zhiguang.be.auth.audit;

/**
 * 登录日志持久化接口。
 * 对外暴露登录日志写入能力，便于后续替换具体存储实现。
 */
public interface LoginLogMapper {

    /**
     * 持久化一条登录日志记录。
     *
     * @param entry 登录日志实体
     */
    void insert(LoginLogEntry entry);
}
