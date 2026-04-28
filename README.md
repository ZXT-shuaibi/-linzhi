# 邻里 - 基于地理 LBS 动态感知算法的知识分享

> 打通“本地知识发布 → 本地发现 → AI 问答 → 社交裂变 → 交易转化”的完整闭环。

## 项目简介

邻里知光是一个基于地理位置的智能社区知识生活服务分享，解决传统 O2O 在高并发下的性能瓶颈、周边检索效率低、本地知识服务缺失等问题。

- 高并发互动与交易链路的稳定性问题
- 附近内容检索与 Feed 浏览的性能问题
- 本地知识难以沉淀与复用的问题
- 社区内容、社交互动、AI 问答、交易转化之间缺少闭环的问题

当前代码已经覆盖：

- 认证授权
- 内容发布
- 社交互动
- Feed 流
- 搜索索引
- RAG 问答
- 高并发交易
- 平台治理

整体目标是形成一套可运行、可扩展、可继续工程化增强的社区后端。

## 核心特性

- **高并发架构**：点赞、关注、秒杀链路抗压，Feed 首页低延迟高命中
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

项目采用“领域模块化单体 + 统一分层”的架构模式，模块分为两大类：

- **核心业务模块**：面向用户直接感知的业务场景，如认证、发现、内容、个人主页、Feed、社交、交易、AI 问答
- **基础支持模块**：为业务模块提供通用能力，如线程池、防护、缓存、治理与数据访问规范

每个模块遵循统一目录规范：

```text
模块名/
├── controller/    # HTTP 接口层
├── service/       # 业务编排层
├── mapper/        # 数据访问层
├── model/         # DTO / Entity / VO
└── event/         # 事件定义与消费（可选）
```

### 调用约束

1. 模块内调用顺序：`controller → service → mapper`
2. 禁止 `controller` 直接调用 `mapper`
3. 跨模块调用只允许调用对方 `service` 或通过事件总线
4. 公共工具统一放 `common`，不混入业务逻辑

## 核心模块

### 模块进度总览

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| auth | 已较完整 | 注册、登录、JWT 双令牌、黑名单、验证码、`/me` 已打通 |
| discover | 已实现（基础版） | LBS 发现主链已落地，当前阶段暂缓继续深挖 |
| content | 已较完整 | 草稿、预签名上传、发布、详情、列表、删除已打通 |
| social | 已较完整 | 关注、点赞、收藏、计数、Kafka 聚合与回放入口已打通 |
| feed | 已较完整 | Feed 首页、匿名浏览、缓存、与 content/social 联动已打通 |
| storage | 已实现（基础版） | 已支持独立预签名上传入口、头像上传场景、公开 URL 统一生成 |
| cache | 已实现（增强版） | 已抽离独立缓存模块，统一承接本地缓存与 Redis JSON/String 读写，并接入热点探测、区域摘要与治理接口 |
| search | 已实现（增强版） | 已支持 `db / es` 双 Provider、本地同步 / 异步索引同步、公开搜索与联想建议 |
| profile | 已实现（增强版） | 已支持资料查询、资料编辑、个人主页聚合、个人发布列表、关注/粉丝资料列表 |
| llm | 已实现（增强版） | 已提供帖子描述生成能力，主链优先走 Spring AI ChatClient，保留 HTTP / template 兜底 |
| rag | 已实现（增强版） | 已切到 Spring AI / VectorStore / ChatClient 主链，支持向量检索、流式问答、单篇重建与公开内容批量重建 |
| trade | 已实现（基础增强版） | 已支持活动创建/查询、Redis+Lua 预扣、异步下单、支付、主动取消、主动/被动关单、补偿回写 |
| platform | 已实现（增强版） | 已补平台运行摘要、可观测性摘要、缓存治理、Redis 指标、线程池摘要等运维接口 |
| threadpool | 已实现 | 支持业务模式 / 实验模式双配置切换 |
| guard | 已实现（基础版） | 已提供限流与部分防护能力 |

### 1. auth - 认证模块

**状态**：已完成

**功能**

- JWT 双令牌认证（Access Token 15 分钟 + Refresh Token 7 天）
- 登录失败追踪（3 次验证码，10 次封禁）
- 黑名单机制
- 密码重置（短信验证码验证）
- 审计日志记录

**技术亮点**

- RSA 2048 位非对称加密
- Refresh Token 轮换防泄露
- Redis 白名单 + Lua 脚本原子消费
- 统一错误消息防用户枚举

### 2. discover - 发现模块

**状态**：已实现（基础版）

**功能**：基于 LBS 的附近内容发现

**核心能力**

- GeoRadius + GeoHash 双模式地理检索
- 支持知识、商家、活动的附近搜索
- 米级精度距离计算
- 热点区域缓存优化

**技术实现**

- Redis Geo 数据结构存储地理位置
- GeoHash 编码实现快速范围查询
- ES 地理位置索引支持复杂过滤
- 缓存热点区域查询结果（TTL 2 分钟）

**数据模型**

```text
Redis Key: geo:knowledge, geo:merchant
ES Index: knowledge_geo, merchant_geo
```

> 当前阶段先不继续深挖 `discover`，后续如果接入高德等地图服务，再补地址标准化、POI 联想和真实路径距离排序。

### 3. content - 内容模块

**状态**：已完成

**功能**：知识内容发布与管理

**核心能力**

- OSS 预签名直传（避免服务端流量）
- 元数据结构化存储
- 草稿、正文确认、发布、置顶、可见性、删除等内容管理动作
- 异步摘要生成
- 内容审核预留接口

**技术实现**

- 客户端通过预签名 URL 直传 OSS
- 服务端负责草稿、正文确认、元数据更新和发布状态流转
- 发布时同步维护 Discover 索引，并保留 Outbox 事件作为后续扩展入口
- MySQL 存储结构化数据，ES 存储全文索引

**发布流程**

1. 客户端请求预签名 URL
2. 客户端直传文件到 OSS
3. 客户端确认正文上传并提交元数据
4. 服务端发布内容并写入 MySQL，同时同步更新 Discover
5. Outbox 事件预留给后续摘要生成与索引更新链路

### 4. rag - RAG 问答模块

**状态**：已实现（增强版）

**功能**：本地化智能问答

**核心能力**

- Spring AI `VectorStore` 向量召回
- Spring AI `ChatClient` 流式回答
- Elasticsearch 向量分片存储
- 单篇重建与公开内容批量重建
- 指纹校验（SHA256 / ETag）避免重复重建

**技术实现**

- 公开已发布内容切块后写入向量索引
- 切块策略：Markdown 标题 + 段落切块
- DeepSeek 通过 Spring AI 封装为统一 `ChatClient`
- RAG 主链优先走 `VectorStore + ChatClient`
- 当前项目仍保留 HTTP / template 兜底，避免模型或向量环境不可用时主链中断

**问答流程**

1. 用户提问，可选带 `postId / lat / lng / topK`
2. `RagIndexService` 通过向量检索召回相关上下文
3. 组装上下文 Prompt
4. `ChatClient` 进行流式生成
5. Spring AI 不可用时回退到现有 LLM 网关

**当前口径**

- 当前 `rag` 已不再只是基础版 SSE 壳子，而是切到了 Spring AI / VectorStore / ChatClient 主链
- 已支持单篇索引重建、公开内容批量重建、启动预热与向量召回
- 后续重点不再是“是否接入 RAG”，而是继续增强检索质量、观测能力和模型治理

### 5. social - 社交模块

**状态**：已完成

**功能**：高并发社交互动

**核心能力**

- 点赞 / 收藏（Redis 位图 + Kafka 异步）
- 计数系统（Redis SDS / 位图重建）
- 关注 / 取关（Outbox + Canal 同步）
- 投影修复与重建

**技术实现**

- 同事务写 `follow` + `outbox`
- Canal 订阅 binlog
- Kafka 投递异步事件
- Redis 事实层 + 汇总层分离
- 定时修复与对账任务收敛数据偏差

### 6. feed - Feed 模块

**状态**：已完成

**功能**：智能内容浏览流

**核心能力**

- 三级缓存（Caffeine + Redis 页面 + Redis 分段）
- Single-flight 防击穿
- 热点自动延长 TTL
- 时间 / 距离 / 热度混排

**技术实现**

- L1：Caffeine 承接热点页
- L2：Redis 页面缓存承接页面骨架
- L3：Redis 分段缓存承接条目碎片
- 回源后多层回填
- 个性化状态后补全

### 7. profile - 个人模块

**状态**：已实现（增强版）

**功能**：承接个人主页、资料编辑与用户侧聚合展示

**已实现能力**

- 个人资料查看与编辑（昵称、头像、简介、学校、标签等）
- 头像单独更新接口
- 个人主页聚合（基础信息 + 社交计数 + 关系状态）
- 我的发布列表与分页聚合
- 关注列表、粉丝列表资料视图聚合

### 8. trade - 交易模块

**状态**：已实现（基础增强版）

**功能**：秒杀与团购

**核心能力**

- 库存扣减（Redis + Lua 原子操作）
- 防超卖机制
- 订单一致性保障
- 未支付自动关单

**技术实现**

- 活动热点缓存：空值缓存 + 互斥锁 + 随机 TTL
- 秒杀预检：Lua 原子校验库存、限购并完成 Redis 预扣
- 异步下单：Kafka 开关可切换，关闭时回退本地线程池执行
- 一致性保障：提交锁 + MySQL 乐观锁 + outbox 补偿回写 Redis
- 关单收口：主动扫描过期订单 + 查询时被动关单

### 9. threadpool - 线程池模块

**状态**：已实现（自适应缓冲版）

**功能**：异步任务编排

**核心能力**

- 动态线程池配置
- 任务监控与告警
- 轻 / 中 / 重负载三态入队策略
- 业务模式 / 实验模式双配置切换

### 10. guard - 防护模块

**状态**：已实现（基础版）

**功能**

- 限流（全局 / IP / 用户维度）
- 防穿透、防雪崩
- 降级开关

### 11. platform / cache - 平台治理层

**状态**：已实现（增强版）

**核心能力**

- 平台运行摘要
- 可观测性摘要
- 缓存区域摘要
- Redis 指标摘要
- 线程池运行摘要
- 手动清理缓存与热点信息查询

## 业务流程梳理

### 整体架构流程

```mermaid
graph TD
    %% ==================== 前端 ====================
    subgraph FE ["前端（React Native / UniApp / H5）"]
        USER[用户入口<br>· LBS 定位<br>· 发布知识<br>· AI 问答<br>· 浏览 Feed<br>· 点赞/关注<br>· 秒杀抢购]
    end

    %% ==================== 后端 12 个模块 ====================
    subgraph BE ["后端核心（Spring Boot 3 + Java 21）"]
        direction TB

        AUTH[模块1: 认证中心<br>JWT 双令牌 + Redis 白名单<br>滑动窗口限流]

        LBS[模块2: LBS 知识发现<br>Redis GeoHash + GeoRadius]

        PUBLISH[模块3: 知识发布系统<br>OSS 前端直传 + DeepSeek 摘要]

        RAG[模块4: AI 知识引擎<br>RAG 向量召回 + SSE 流式返回]

        SOCIAL[模块5: 社交裂变系统<br>点赞位图 Lua + Outbox + Canal]

        FEED[模块6: 智能 Feed 流<br>三级缓存架构<br>**HotKey 探测机制**（京东风格）<br>Geo+兴趣混合排序 + single-flight]

        SECKILL[模块7: 高并发交易引擎<br>Lua 预检 + Redisson + 乐观锁]

        LADBTP[模块8: 自研 LADBTP 线程池<br>Buffer Factor 动态扩容]

        CACHE[模块9: 缓存 & 防护中心<br>Caffeine 多级缓存 + 滑动窗口限流]

        DB[模块10: 数据库层<br>MySQL 乐观锁 + Outbox]

        CONSIST[模块11: 一致性保障<br>Kafka + Canal 事件驱动]

        EXTEND[模块12: 可扩展性<br>知识付费 + 个性化推荐]
    end

    %% ==================== 基础设施 ====================
    subgraph INF ["基础设施"]
        MYSQL[(MySQL 8)]
        REDIS[(Redis 7+)]
        KAFKA[(Kafka)]
        ES[(Elasticsearch)]
        OSS[(阿里云 OSS)]
        CANAL[Canal]
        LLM[(DeepSeek)]
        TASK[Spring Task]
        REDISSON[Redisson]
    end

    %% ==================== 完整流程（突出 HotKey） ====================
    USER -->|"JWT 请求"| AUTH
    AUTH --> LBS & PUBLISH & RAG & SOCIAL & FEED & SECKILL

    LBS --> REDIS
    LBS --> FEED
    LBS --> ES

    USER --> PUBLISH
    PUBLISH --> OSS & MYSQL
    PUBLISH -.-> KAFKA
    KAFKA --> RAG & ES & FEED & SOCIAL

    USER --> RAG
    RAG --> ES & LLM & MYSQL

    USER --> SOCIAL
    SOCIAL --> REDIS & MYSQL
    CANAL --> KAFKA

    FEED -->|"HotKey 探测（京东风格采样+滑动窗口）<br>→ 自动延长 TTL + single-flight + 随机抖动"| CACHE
    FEED -->|"缓存 miss"| MYSQL & ES & REDIS
    FEED --> USER

    USER --> SECKILL
    SECKILL --> REDIS & KAFKA
    KAFKA --> LADBTP
    LADBTP --> SECKILL
    SECKILL --> REDISSON & MYSQL & TASK

    CACHE -.-> REDIS
    AUTH & SECKILL -.-> REDIS["限流"]
    ALL[所有写操作] -.-> CONSIST
    CONSIST -.-> REDIS & ES & FEED
```

### 认证模块流程

```mermaid
flowchart TD
    Start([认证模块入口])

    Start --> Choice{选择操作}

    Choice -->|1| SendCode[发送验证码]
    Choice -->|2| Register[用户注册]
    Choice -->|3| PwdLogin[密码登录]
    Choice -->|4| SmsLogin[验证码登录]
    Choice -->|5| Refresh[刷新令牌]
    Choice -->|6| Logout[用户登出]
    Choice -->|7| ResetPwd[重置密码]

    SendCode --> SC1{检查发送间隔<br/>60秒}
    SC1 -->|未满60秒| SC_Fail1[返回429: 请等待]
    SC1 -->|已满60秒| SC2{检查每日上限<br/>10次}
    SC2 -->|已达上限| SC_Fail2[返回429: 今日已达上限]
    SC2 -->|未达上限| SC3[生成6位验证码]
    SC3 --> SC4[Redis HSET<br/>auth:code:scene:phone<br/>code/maxAttempts:5/attempts:0]
    SC4 --> SC5[设置过期时间 5分钟]
    SC5 --> SC6[Redis SET interval key<br/>过期60秒]
    SC6 --> SC7[Redis INCR daily key]
    SC7 --> SC_Success[返回200: 验证码已发送]

    Register --> R1[验证码校验<br/>Lua脚本原子操作]
    R1 --> R2{验证码是否有效}
    R2 -->|无效/过期/超限| R_Fail1[审计日志<br/>返回401: 验证码错误]
    R2 -->|有效| R3[生成雪花ID]
    R3 --> R4[BCrypt加密密码]
    R4 --> R5[MySQL saveIfPhoneAbsent]
    R5 --> R6{手机号是否存在}
    R6 -->|已存在| R_Fail2[审计日志<br/>返回409: 手机号已注册]
    R6 -->|不存在| R7[签发JWT双令牌<br/>Access 15min + Refresh 7days]
    R7 --> R8[解析Refresh Token<br/>获取jti和expiresAt]
    R8 --> R9[Redis SADD<br/>auth:refresh:userId jti]
    R9 --> R10[审计日志: 注册成功]
    R10 --> R_Success[返回200: userId + tokens]

    PwdLogin --> PL1{检查失败次数<br/>是否>=10次}
    PL1 -->|是| PL_Fail1[审计日志<br/>返回403: 账号锁定30分钟]
    PL1 -->|否| PL2{失败次数>=3次}
    PL2 -->|是| PL3{是否提供<br/>图形验证码}
    PL3 -->|否| PL_Fail2[审计日志<br/>返回400: 需要验证码]
    PL3 -->|是| PL4{验证码是否正确}
    PL4 -->|否| PL5[记录失败次数]
    PL5 --> PL_Fail3[审计日志<br/>返回400: 验证码错误]
    PL2 -->|否| PL6[查询用户<br/>findByPhoneOrUsername]
    PL4 -->|是| PL6
    PL6 --> PL7{用户是否存在}
    PL7 -->|否| PL8[执行dummy hash<br/>防止时序攻击]
    PL8 --> PL9[记录失败次数]
    PL9 --> PL_Fail4[审计日志<br/>返回401: 用户不存在]
    PL7 -->|是| PL10{检查黑名单<br/>Redis SISMEMBER}
    PL10 -->|在黑名单| PL_Fail5[审计日志<br/>返回403: 账号已封禁]
    PL10 -->|不在| PL11{验证密码<br/>BCrypt matches}
    PL11 -->|错误| PL12[记录失败次数]
    PL12 --> PL_Fail6[审计日志<br/>返回401: 密码错误]
    PL11 -->|正确| PL13[重置失败计数]
    PL13 --> PL14[签发JWT双令牌]
    PL14 --> PL15[Redis SADD保存jti]
    PL15 --> PL16[审计日志: 登录成功]
    PL16 --> PL_Success[返回200: userId + tokens]

    SmsLogin --> SL1[验证码校验<br/>Lua脚本原子操作]
    SL1 --> SL2{验证码是否有效}
    SL2 -->|无效| SL_Fail1[审计日志<br/>返回401: 验证码错误]
    SL2 -->|有效| SL3[查询用户 findByPhone]
    SL3 --> SL4{用户是否存在}
    SL4 -->|否| SL_Fail2[返回401: 用户未注册]
    SL4 -->|是| SL5{检查黑名单}
    SL5 -->|在黑名单| SL_Fail3[审计日志<br/>返回403: 账号已封禁]
    SL5 -->|不在| SL6[签发JWT双令牌]
    SL6 --> SL7[Redis SADD保存jti]
    SL7 --> SL8[审计日志: 登录成功]
    SL8 --> SL_Success[返回200: userId + tokens]

    Refresh --> RF1[JWT验证Refresh Token]
    RF1 --> RF2{令牌是否有效}
    RF2 -->|无效/过期| RF_Fail1[返回401: 令牌无效]
    RF2 -->|有效| RF3[解析userId/jti/expiresAt]
    RF3 --> RF4[查询用户 findByUserId]
    RF4 --> RF5{用户是否存在}
    RF5 -->|否| RF_Fail2[返回401: 用户不存在]
    RF5 -->|是| RF6{检查黑名单}
    RF6 -->|在黑名单| RF7[清空所有refresh token<br/>Redis DEL]
    RF7 --> RF_Fail3[返回403: 账号已封禁]
    RF6 -->|不在| RF8[消费刷新令牌<br/>Redis SREM jti]
    RF8 --> RF9{jti是否在白名单}
    RF9 -->|否| RF_Fail4[返回401: 令牌已失效]
    RF9 -->|是| RF10[签发新JWT双令牌]
    RF10 --> RF11[Redis SADD保存新jti]
    RF11 --> RF_Success[返回200: 新tokens]

    Logout --> LO1[JWT验证Refresh Token]
    LO1 --> LO2[解析userId和jti]
    LO2 --> LO3{登出范围}
    LO3 -->|all_devices| LO4[Redis DEL<br/>auth:refresh:userId]
    LO4 --> LO_Success1[返回200: 已登出所有设备]
    LO3 -->|current_device| LO5[Redis SREM<br/>移除当前jti]
    LO5 --> LO_Success2[返回200: 已登出当前设备]

    ResetPwd --> RP1[验证码校验<br/>Lua脚本原子操作]
    RP1 --> RP2{验证码是否有效}
    RP2 -->|无效| RP_Fail1[审计日志<br/>返回401: 验证码错误]
    RP2 -->|有效| RP3[查询用户 findByPhone]
    RP3 --> RP4{用户是否存在}
    RP4 -->|否| RP_Fail2[返回400: 手机号未注册]
    RP4 -->|是| RP5[BCrypt加密新密码]
    RP5 --> RP6[MySQL更新密码]
    RP6 --> RP7[清空所有refresh token<br/>Redis DEL]
    RP7 --> RP8[审计日志: 密码重置成功]
    RP8 --> RP_Success[返回200: 密码重置成功]
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
    Content->>Kafka: 9. 发送 post-published 事件
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

    Note over MySQL,Consumer: 定时任务：每 5 分钟扫描过期未支付订单并关闭
```

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 7.x
- Elasticsearch 8.x（需要搜索或 RAG 向量检索时）
- Kafka 3.x（需要异步链路时）

### 配置说明

1. **生成 RSA 密钥对**（生产环境必需）

```bash
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem
```

2. **配置环境变量**

```bash
export JWT_PUBLIC_KEY="$(cat public.pem | sed 's/$/\\n/' | tr -d '\n')"
export JWT_PRIVATE_KEY="$(cat private.pem | sed 's/$/\\n/' | tr -d '\n')"
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_password
```

3. **修改配置文件**

```yaml
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
    allow-ephemeral-keys: false
```

### 运行项目

1. 启动 Redis、MySQL 等依赖
2. 编译运行：

```bash
mvn clean package -DskipTests
java -jar target/*.jar
```

3. 验证服务：

```bash
curl http://localhost:8080/actuator/health
```

## API 文档

- [openapi.yaml](openapi.yaml)
- [后端API-超详细.md](后端API-超详细.md)
- [前端API-标准版.md](前端API-标准版.md)
- [认证模块-开发实现清单.md](认证模块-开发实现清单.md)
- [内容模块-开发实现清单.md](内容模块-开发实现清单.md)
- [社交模块-开发实现清单.md](社交模块-开发实现清单.md)
- [社交计数体系-实现路线图.md](社交计数体系-实现路线图.md)
- [项目架构导读-当前代码版.md](项目架构导读-当前代码版.md)
- [邻里知光-技术分档-详细版.md](邻里知光-技术分档-详细版.md)

## 开发规范

### 代码规范

1. 遵循模块分层架构约束
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

## 当前完成度

### 已较完整的主链

- `auth`
- `content`
- `social`
- `feed`
- `profile`
- `threadpool`

### 已实现、仍可继续增强的模块

- `search`
- `llm`
- `rag`
- `trade`
- `platform / cache`

### 当前暂缓继续开发的模块

- `discover`

## 后续优先级

1. 持续同步 README 与真实代码状态
2. 继续增强 `platform / cache` 的系统级可观测性
3. 继续打磨 `search`、`llm`、`rag` 的工程厚度
4. 后续再回头补 `discover`

## 贡献指南

欢迎贡献代码，请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/AmazingFeature`
3. 提交更改：`git commit -m 'Add some AmazingFeature'`
4. 推送到分支：`git push origin feature/AmazingFeature`
5. 发起 Pull Request

## 许可证

本项目仅供学习和研究使用。

## 联系方式

如有问题或建议，欢迎提交 Issue。

---

**邻里知光** - 让本地知识触手可及
