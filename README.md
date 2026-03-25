# 邻里知光 - 基于 LBS 的智能社区生活服务平台

> 打通"本地知识发布 → 本地发现 → AI 问答 → 社交裂变 → 交易转化"的完整闭环

## 项目简介

邻里知光是一个基于地理位置的智能社区知识生活服务平台，解决传统 O2O 在高并发下的性能瓶颈、周边检索效率低、本地知识服务缺失等问题。

### 核心特性

- **高并发架构**：点赞/关注/秒杀链路抗压，Feed 首页低延迟高命中
- **智能 LBS**：GeoRadius + GeoHash 双模式地理检索，米级精度
- **本地化 RAG**：基于向量检索的本地知识问答，支持流式输出
- **分布式一致性**：Outbox + Canal + Kafka + 乐观锁组合保障
- **企业级安全**：JWT 双令牌 + Redis 白名单 + 登录失败追踪 + 审计日志

## 技术栈

### 后端技术
- **框架**：Spring Boot 3.x + Spring Security
- **数据库**：MySQL 8.0 + Redis 7.0
- **搜索引擎**：Elasticsearch（向量检索 + 全文搜索）
- **消息队列**：Kafka（异步事件处理）
- **数据同步**：Canal（MySQL binlog 订阅）
- **AI 能力**：DeepSeek + Spring AI（RAG 问答）
- **对象存储**：OSS（预签名直传）

### 核心中间件
- **认证**：JWT RS256 非对称加密
- **缓存**：Caffeine（本地缓存）+ Redis（分布式缓存）
- **限流**：Redis 滑动窗口
- **防护**：Single-flight + 热点保护 + 降级开关

## 架构设计

### 分层架构

项目采用 12 模块分层架构，每个模块遵循统一的目录规范：

```
模块名/
├── controller/    # HTTP 接口层
├── service/       # 业务编排层
├── mapper/        # 数据访问层
├── model/         # DTO/Entity/VO
└── event/         # 事件定义与消费（可选）
```

### 调用约束

1. 模块内调用顺序：`controller → service → mapper`
2. 禁止 `controller` 直接调用 `mapper`
3. 跨模块调用只允许调用对方 `service` 或通过事件总线
4. 公共工具统一放 `common`，不得放业务逻辑

## 核心模块

### 1. auth - 认证模块 ✅
**状态**：已完成

**功能**：
- JWT 双令牌认证（Access Token 15分钟 + Refresh Token 7天）
- 登录失败追踪（3次验证码，10次封禁）
- 黑名单机制
- 密码重置（短信验证码验证）
- 审计日志记录

**技术亮点**：
- RSA 2048位非对称加密
- Refresh Token 轮换防泄露
- Redis 白名单 + Lua 脚本原子消费
- 统一错误消息防用户枚举

### 2. discover - 发现模块
**功能**：基于 LBS 的附近内容发现

**核心能力**：
- GeoRadius + GeoHash 双模式地理检索
- 支持知识、商家、活动的附近搜索
- 米级精度距离计算
- 热点区域缓存优化

**技术实现**：
- Redis Geo 数据结构存储地理位置
- GeoHash 编码实现快速范围查询
- ES 地理位置索引支持复杂过滤
- 缓存热点区域查询结果（TTL 2分钟）

**数据模型**：
```
Redis Key: geo:knowledge, geo:merchant
ES Index: knowledge_geo, merchant_geo
```

### 3. content - 内容模块
**功能**：知识内容发布与管理

**核心能力**：
- OSS 预签名直传（避免服务端流量）
- 元数据结构化存储
- 异步摘要生成
- 内容审核预留接口

**技术实现**：
- 客户端通过预签名 URL 直传 OSS
- 服务端只存储元数据（标题、位置、URL）
- Kafka 异步触发摘要生成任务
- MySQL 存储结构化数据，ES 存储全文索引

**发布流程**：
1. 客户端请求预签名 URL
2. 客户端直传文件到 OSS
3. 客户端提交元数据到服务端
4. 服务端写入 MySQL + 发送 Kafka 事件
5. 消费者生成摘要并更新 ES 索引

### 4. rag - RAG 问答模块
**功能**：本地化智能问答

**核心能力**：
- 向量检索 + LLM 生成
- SSE 流式输出
- LBS 上下文增强
- 问答日志记录

**技术实现**：
- ES 向量索引存储知识分块
- 分块策略：Markdown 标题 + 段落（512 token）
- DeepSeek API 通过 Spring AI 封装
- 召回 Top-K（8-12）片段注入 Prompt
- LBS 过滤：优先召回附近知识

**问答流程**：
1. 用户提问 + 地理位置
2. 向量检索召回相关片段（带 LBS 过滤）
3. 构建 Prompt（系统指令 + 上下文 + 问题）
4. LLM 生成答案（SSE 流式返回）
5. 记录问答日志（问题、召回、答案、耗时）

### 5. social - 社交模块
**功能**：高并发社交互动

**核心能力**：
- 点赞/收藏（Redis 位图 + Kafka 异步）
- 关注/取关（Outbox + Canal 同步）
- 评论系统

**技术实现**：
- **点赞/收藏**：
  - Kafka 异步化，消费者用 Lua 原子更新位图和计数
  - Redis 位图 key：`like:bit:{target_id}`
  - 计数 key：`count:like:{target_id}`
- **关注/取关**：
  - 同事务写 `follow` + `outbox`
  - Canal 订阅 binlog 推送 `follow_event`
  - 消费者更新粉丝计数与 zset 列表

**一致性保障**：
- Outbox 模式确保业务事务和消息投递同源
- Canal 自动捕获数据变更，无需业务代码侵入
- 死信队列处理失败消息

### 6. feed - Feed 流模块
**功能**：智能内容推荐

**核心能力**：
- 三级缓存（Caffeine + Redis 页面 + Redis 分段）
- Single-flight 防击穿
- 热点自动延长 TTL
- 按时间/距离混排

**技术实现**：
- **L1 Caffeine**（5s）：本地缓存，极速响应
- **L2 Redis 页面**（30s）：完整页面缓存
- **L3 Redis 分段**（2min）：按 GeoHash 分段缓存
- **Single-flight**：同一查询只回源一次
- **热点保护**：自动延长热点 key 的 TTL + 抖动

**缓存 Key 规范**：
```
feed:page:{user_id}:{page}
feed:segment:{geo_hash}:{timestamp}
```

### 7. trade - 交易模块
**功能**：秒杀与团购

**核心能力**：
- 库存扣减（Redis + Lua 原子操作）
- 防超卖机制
- 订单一致性保障
- 未支付自动关单

**技术实现**：
- **秒杀预检**：Lua 原子校验库存、资格、限流并完成预扣
- **异步下单**：Kafka 解耦 + 线程池执行
- **一致性保障**：Redisson 分布式锁 + MySQL 乐观锁
- **定时兜底**：每 5 分钟扫描过期未支付订单并关闭

**秒杀流程**：
1. 入口快返：预检成功即发 `seckill_success` 事件
2. 下单防重：用户维度分布式锁
3. 订单表 `version` 乐观锁字段
4. 数据回写：库存最终状态同步 Redis 与 ES

### 8. threadpool - 线程池模块
**功能**：异步任务编排

**核心能力**：
- 动态线程池配置（LADBTP）
- 任务监控与告警
- 轻/中/重负载三态入队策略

**技术实现**：
- Buffer Factor 决定扩容强度
- 重载可强制入队 + 降级
- 配置中心动态调整参数
- 指标暴露（队列长度、执行时间、拒绝次数）

### 9. guard - 防护模块
**功能**：系统防护

**核心能力**：
- 限流（全局/IP/用户维度）
- 防穿透、防雪崩
- 降级开关
- 熔断机制

**技术实现**：
- AOP + 注解 + Redis 滑动窗口限流
- 布隆过滤器防缓存穿透
- Single-flight 防缓存击穿
- 热点 key 自动延长 TTL 防雪崩
- 配置中心控制降级开关

### 10. data - 数据层模块
**功能**：数据访问规范

**核心能力**：
- 统一数据访问接口
- 乐观锁版本控制
- 分库分表预留

**技术实现**：
- Mapper 层统一接口规范
- 所有表包含 `version` 字段
- 更新操作使用 `WHERE version = ?` 条件
- 租户 key 前缀预留多租户支持

### 11. consistency - 一致性模块
**功能**：分布式一致性保障

**核心能力**：
- Outbox 模式
- 死信队列
- 自愈机制
- 事件回放

**技术实现**：
- 业务事务同时写入 `outbox` 表
- Canal 订阅 binlog 推送到 Kafka
- 消费失败进入死信队列
- 定时任务扫描 outbox 补偿未投递消息
- 位图重建工具修复数据不一致

### 12. platform - 平台化模块
**功能**：平台治理

**核心能力**：
- 多租户支持
- 可观测性
- 商业化预留
- 服务拆分预案

**技术实现**：
- Prometheus + Grafana + SkyWalking 监控
- 关键链路指标可视化（QPS、RT、错误率）
- 事件驱动保持松耦合，支持服务拆分
- 知识付费权限模型预留
- 社区电商扩展模型预留

## 业务流程梳理

### 整体架构流程

```mermaid
flowchart TB
    Client[客户端 React + Vite]
    Gateway[API Gateway Spring Boot]

    Client --> Gateway

    Gateway --> Auth[1. Auth 认证模块]
    Gateway --> Discover[2. Discover 发现模块]
    Gateway --> Content[3. Content 内容模块]
    Gateway --> RAG[4. RAG 问答模块]
    Gateway --> Social[5. Social 社交模块]
    Gateway --> Feed[6. Feed 流模块]
    Gateway --> Trade[7. Trade 交易模块]

    Auth --> Redis[(Redis)]
    Discover --> Redis
    Discover --> ES[(Elasticsearch)]

    Content --> OSS[(OSS 对象存储)]
    Content --> MySQL[(MySQL)]
    Content --> Kafka[Kafka 消息队列]

    RAG --> ES
    RAG --> DeepSeek[DeepSeek AI]

    Social --> Kafka
    Social --> MySQL

    Feed --> Caffeine[Caffeine 本地缓存]
    Feed --> Redis
    Feed --> MySQL

    Trade --> Redis
    Trade --> Kafka
    Trade --> MySQL

    Kafka --> Canal[Canal Binlog 订阅]
    Canal --> MySQL

    Kafka --> Consumers[异步消费者]
    Consumers --> Redis
    Consumers --> ES
    Consumers --> MySQL

    style Auth fill:#90EE90
    style Discover fill:#FFE4B5
    style Content fill:#FFE4B5
    style RAG fill:#FFE4B5
    style Social fill:#FFE4B5
    style Feed fill:#FFE4B5
    style Trade fill:#FFE4B5
```

### 知识发布流程

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Content as Content 模块
    participant OSS as OSS 对象存储
    participant MySQL as MySQL
    participant Kafka as Kafka
    participant Consumer as 异步消费者
    participant ES as Elasticsearch

    Client->>Content: 1. 请求预签名 URL
    Content->>OSS: 2. 生成预签名 URL
    OSS-->>Content: 3. 返回预签名 URL
    Content-->>Client: 4. 返回预签名 URL

    Client->>OSS: 5. 直传文件到 OSS
    OSS-->>Client: 6. 上传成功

    Client->>Content: 7. 提交元数据（标题、位置、URL）
    Content->>MySQL: 8. 写入知识表
    Content->>Kafka: 9. 发送 content_published 事件
    Content-->>Client: 10. 返回成功

    Kafka->>Consumer: 11. 消费事件
    Consumer->>Consumer: 12. 生成摘要
    Consumer->>ES: 13. 更新全文索引
    Consumer->>ES: 14. 更新向量索引（RAG）
```

### Feed 浏览流程

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Feed as Feed 模块
    participant L1 as Caffeine 缓存
    participant L2 as Redis 页面缓存
    participant L3 as Redis 分段缓存
    participant MySQL as MySQL
    participant ES as Elasticsearch

    Client->>Feed: 1. 请求 Feed（用户ID + 页码 + 位置）
    Feed->>L1: 2. 查询 Caffeine

    alt L1 命中
        L1-->>Feed: 3. 返回缓存数据
        Feed-->>Client: 4. 返回 Feed 列表
    else L1 未命中
        Feed->>L2: 5. 查询 Redis 页面缓存

        alt L2 命中
            L2-->>Feed: 6. 返回缓存数据
            Feed->>L1: 7. 写入 L1
            Feed-->>Client: 8. 返回 Feed 列表
        else L2 未命中
            Feed->>L3: 9. 查询 Redis 分段缓存

            alt L3 命中
                L3-->>Feed: 10. 返回分段数据
                Feed->>Feed: 11. 组装页面
                Feed->>L2: 12. 写入 L2
                Feed->>L1: 13. 写入 L1
                Feed-->>Client: 14. 返回 Feed 列表
            else L3 未命中（回源）
                Feed->>MySQL: 15. 查询数据库
                Feed->>ES: 16. 查询搜索引擎
                MySQL-->>Feed: 17. 返回数据
                ES-->>Feed: 18. 返回数据
                Feed->>Feed: 19. 混排（时间 + 距离）
                Feed->>L3: 20. 写入 L3
                Feed->>L2: 21. 写入 L2
                Feed->>L1: 22. 写入 L1
                Feed-->>Client: 23. 返回 Feed 列表
            end
        end
    end
```

### 秒杀交易流程

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Trade as Trade 模块
    participant Redis as Redis
    participant Kafka as Kafka
    participant Lock as Redisson 分布式锁
    participant MySQL as MySQL
    participant Consumer as 异步消费者

    Client->>Trade: 1. 秒杀请求（商品ID + 用户ID）
    Trade->>Redis: 2. Lua 脚本预检（库存 + 资格 + 限流）

    alt 预检失败
        Redis-->>Trade: 3. 返回失败原因
        Trade-->>Client: 4. 返回失败（库存不足/已购买/限流）
    else 预检成功
        Redis-->>Trade: 5. 预扣库存成功
        Trade->>Kafka: 6. 发送 seckill_success 事件
        Trade-->>Client: 7. 快速返回成功（入口快返）

        Kafka->>Consumer: 8. 消费秒杀事件
        Consumer->>Lock: 9. 获取用户维度分布式锁

        alt 获取锁失败
            Lock-->>Consumer: 10. 锁已被占用
            Consumer->>Consumer: 11. 重试或进入死信队列
        else 获取锁成功
            Lock-->>Consumer: 12. 锁获取成功
            Consumer->>MySQL: 13. 创建订单（version 乐观锁）

            alt 订单创建成功
                MySQL-->>Consumer: 14. 订单创建成功
                Consumer->>Redis: 15. 更新库存最终状态
                Consumer->>Lock: 16. 释放锁
                Consumer->>Client: 17. 推送订单详情（WebSocket）
            else 订单创建失败（并发冲突）
                MySQL-->>Consumer: 18. 版本冲突
                Consumer->>Redis: 19. 回滚预扣库存
                Consumer->>Lock: 20. 释放锁
                Consumer->>Client: 21. 推送失败通知
            end
        end
    end

    Note over MySQL,Consumer: 定时任务：每5分钟扫描<br/>过期未支付订单并关闭
```

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 7.0+
- Elasticsearch 8.x（可选，RAG 模块需要）
- Kafka 3.x（可选，异步事件需要）

### 配置说明

1. **生成 RSA 密钥对**（生产环境必需）
```bash
# 生成私钥
openssl genrsa -out private.pem 2048

# 生成公钥
openssl rsa -in private.pem -pubout -out public.pem
```

2. **配置环境变量**
```bash
# JWT 密钥
export JWT_PUBLIC_KEY="$(cat public.pem | sed 's/$/\\n/' | tr -d '\n')"
export JWT_PRIVATE_KEY="$(cat private.pem | sed 's/$/\\n/' | tr -d '\n')"

# Redis
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_password
```

3. **修改配置文件**
```yaml
# src/main/resources/application.yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

security:
  jwt:
    public-key: ${JWT_PUBLIC_KEY}
    private-key: ${JWT_PRIVATE_KEY}
    allow-ephemeral-keys: false  # 生产环境必须为 false
```

### 运行项目

1. **启动依赖服务**
```bash
# 启动 Redis
docker run -d -p 6379:6379 redis:7.0

# 启动 MySQL
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.0
```

2. **编译运行**
```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/zhiguang-be-0.0.1-SNAPSHOT.jar
```

3. **验证服务**
```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 注册用户
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "13800138000",
    "password": "Test1234",
    "nickname": "测试用户",
    "smsCode": "123456"
  }'
```

## API 文档

### 认证接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 用户注册 | POST | `/api/v1/auth/register` | 创建新用户并返回令牌 |
| 用户登录 | POST | `/api/v1/auth/login` | 验证凭证并返回令牌 |
| 刷新令牌 | POST | `/api/v1/auth/token/refresh` | 使用 Refresh Token 获取新令牌 |
| 用户登出 | POST | `/api/v1/auth/logout` | 撤销令牌（支持单设备/全设备） |
| 密码重置 | POST | `/api/v1/auth/password/reset` | 通过短信验证码重置密码 |

详细 API 文档请参考：
- [后端API-超详细.md](后端API-超详细.md)
- [前端API-标准版.md](前端API-标准版.md)

## 开发规范

### 代码规范
1. 遵循 12 模块分层架构约束
2. 模块内调用顺序：`controller → service → mapper`
3. 跨模块调用只允许调用对方 `service` 或通过事件总线
4. 禁止 `controller` 直接调用 `mapper`

### 命名规范
- Controller：`XxxController`
- Service：`XxxService` + `XxxServiceImpl`
- Mapper：`XxxMapper`
- Model：`XxxRequest`、`XxxResponse`、`XxxEntity`

### 安全规范
- 生产环境必须配置持久化 RSA 密钥
- 禁用临时密钥（`allow-ephemeral-keys: false`）
- 敏感操作必须记录审计日志
- 所有接口必须进行输入验证

## 技术文档

- [邻里知光-技术分档-详细版.md](邻里知光-技术分档-详细版.md) - 完整技术架构设计
- [ARCHITECTURE-12-MODULES.md](ARCHITECTURE-12-MODULES.md) - 12 模块分层架构约束
- [认证模块-开发实现清单.md](认证模块-开发实现清单.md) - 认证模块实现细节
- [后端API-超详细.md](后端API-超详细.md) - 后端 API 完整文档
- [前端API-标准版.md](前端API-标准版.md) - 前端 API 标准文档

## 开发进度

### T1 核心闭环档 ✅
- [x] 认证系统（JWT 双令牌 + 登录失败追踪 + 审计日志）
- [ ] LBS 发现（GeoRadius + GeoHash）
- [ ] 知识发布（OSS 直传）
- [ ] Feed 基础版

### T2 高并发稳定档
- [ ] 社交高并发写（点赞/关注异步化）
- [ ] 智能 Feed 全量版（三级缓存）
- [ ] 防护中心（限流/防穿透/防雪崩）

### T3 智能增强档
- [ ] 本地知识 RAG
- [ ] 向量检索
- [ ] 流式问答

### T4 交易攻坚档
- [ ] 秒杀防超卖
- [ ] 订单一致性

### T5 平台化演进档
- [ ] 多租户支持
- [ ] 可观测性
- [ ] 商业化预留

## 性能指标

### 认证模块
- 登录接口 P95 < 100ms（缓存命中）
- JWT 验证 P95 < 10ms
- Refresh Token 轮换 P95 < 50ms

### 目标性能（T2 完成后）
- Feed 首页 P95 < 150ms
- 附近检索 P95 < 150ms
- 点赞/关注 P95 < 50ms
- 缓存命中率 > 95%

## 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 许可证

本项目仅供学习和研究使用。

## 联系方式

如有问题或建议，欢迎提交 Issue。

---

**邻里知光** - 让本地知识触手可及

