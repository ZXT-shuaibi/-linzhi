package com.zhiguang.be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智光后端应用启动入口。
 * 负责初始化 Spring Boot 容器并装配整个业务系统的运行环境。
 */
@SpringBootApplication
public class ZhiguangBeApplication {

    /**
     * 启动应用程序。
     * 该方法会触发 Spring Boot 自动配置、组件扫描以及内嵌容器启动。
     *
     * @param args 启动参数，通常来自命令行或运行配置
     */
    public static void main(String[] args) {
        SpringApplication.run(ZhiguangBeApplication.class, args);
    }
}