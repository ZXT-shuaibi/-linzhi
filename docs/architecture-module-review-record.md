# 架构模块改进 Review 记录

记录时间：2026-06-09

本文档用于持久化本轮架构改进的模块级规格审查与代码质量审查证据。审查依据包括提交记录、核心代码抽查、单模块测试与全量 Surefire 报告。早期模块的 review 过程主要发生在开发会话中，本文件补齐仓库内可追踪记录，便于后续继续审计。

## 审查总览

| 模块 | 对应提交 | 规格 Review | 代码质量 Review | 主要验证 |
| --- | --- | --- | --- | --- |
| 认证系统 | `80d9fc5` 认证系统支持JWT多kid滚动换钥 | 通过：覆盖 RS256 多 kid、历史公钥验签、缺 kid/未知 kid 拒绝、生产临时密钥禁用 | 通过：配置 fail-fast，当前签发 key 与历史验签 key 分离，关键方法有注释 | `AuthJwtConfigurationTest` |
| 计数系统 | `ed03155` 社交计数支持CounterEvent事件幂等；`d5e59b0` 社交计数增强Kafka灾难回放护栏 | 通过：覆盖事件幂等、Kafka 不确定发送 fallback、灾难回放范围/dry-run/幂等 | 通过：Redis Lua 原子去重与聚合桶写入同脚本完成，失败保留重试能力 | `InteractionServiceImplTest`、`CounterAggregationConsumerTest`、`CounterRebuildConsumerTest` |
| 用户关系系统 | `1068aaf` 用户关系增强Outbox投影失败重试护栏 | 通过：覆盖 Outbox 事件驱动投影失败时保留 offset 并重试 | 通过：专用 listener factory、手动 ack、阻塞重试，避免静默跳过失败投影 | `CanalOutboxConsumerTest` |
| 发布系统 | `36329fa` 发布系统增强正文确认摘要校验 | 通过：覆盖正文确认 sha256 格式校验、大写归一化、历史大写幂等 | 通过：接口层与服务层双重校验，非法摘要在 OSS 元数据校验和落库前被拒绝 | `ContentServiceImplTest` |
| 点赞系统 | `39c1909` 点赞系统增强位图同步失败重试护栏 | 通过：覆盖点赞位图 Lua 返回异常时进入 retry 兜底，不静默吞掉失败 | 通过：主库互动事实与 Redis 位图/计数投影解耦，失败进入最终一致补偿 | `InteractionServiceImplTest` |
| Feed 流 | `d321c71` Feed流增强缓存Key版本化治理；`5265491` Feed缓存失效服务修复Spring构造器注入 | 通过：覆盖三级缓存 key 版本化、legacy 镜像、双删失效、构造器注入修复 | 通过：统一 key 工厂，失效服务清理当前版本与旧命名空间，降低滚动发布风险 | `FeedCacheKeysTest`、`FeedCacheInvalidationServiceTest`、`FeedServiceImplTest`、应用上下文测试 |
| 搜索系统 | `3cc1766` 搜索系统增强Outbox投影幂等重试护栏 | 通过：覆盖 Search Outbox 幂等、投影失败保留 offset、Redis 完成标记异常回滚 | 通过：先成功投影再写完成标记，优先避免丢投影，允许重复幂等写 ES | `SearchCanalOutboxConsumerTest` |
| AI/RAG 问答系统 | `3d8084d` RAG系统增强向量索引单一版本护栏 | 通过：覆盖当前 indexVersion 过滤、旧分片污染隔离、向量文档写入语义 | 通过：查询阶段按 postId 缓存当前索引版本，避免 delete_by_query 残留污染上下文 | `RagIndexServiceTest` |

## 关键方法注释抽查

- 认证系统：`AuthJwtConfiguration.jwtDecoder`、`verificationJwkSource`、`readVerificationKeys` 说明多 kid 验签、选钥约束与历史公钥读取规则。
- 计数系统：`InteractionServiceImpl.consumeAggregateEventAtomically`、`projectEntityCounterDelta` 说明 eventId 原子去重、Kafka fallback 与聚合桶一致性边界。
- 计数灾难回放：`CounterRebuildConsumer` 类注释、构造器、`isInReplayScope`、`validateCounterEventForReplay` 说明回放范围、dry-run 与事件安全校验。
- 用户关系系统：`CanalOutboxConsumer` 和 Kafka 配置测试覆盖手动 ack、失败抛出、阻塞重试。
- 发布系统：`ContentServiceImpl` 正文确认链路在进入 OSS 元数据校验前执行摘要格式与归一化保护。
- 点赞系统：`InteractionServiceImpl.applyInteractionProjection`、`syncBitmap`、`resolveCounterFieldIndex` 说明位图异常检测、重试补偿与 SDS 字段映射。
- Feed 流：`FeedCacheKeys` 类和工厂方法说明版本化 key、legacy 镜像与迁移期清理；`FeedCacheInvalidationService.invalidatePostAfterCommit` 说明事务后双删。
- 搜索系统：`SearchCanalOutboxConsumer.onMessage`、`markProjected` 说明失败保留位点和投影完成标记策略。
- RAG 系统：`RagIndexService.findIndexedFingerprint`、`writeChunksToVectorStore`、`deleteVectorChunks`、`isCurrentVectorVersion` 说明单一版本索引与召回过滤边界。

## Review 结论

### 规格 Review

- 认证系统与用户描述对齐：继续保持 JWT 双令牌架构，并增强 RS256 key 轮换和生产配置安全。
- 计数系统与点赞系统对齐：实体计数、Kafka 聚合、灾难回放和点赞位图幂等链路均有独立护栏；点赞模块已补独立提交。
- 发布系统对齐：保留 OSS 预签名直传与渐进式发布流程，并补足正文确认摘要校验。
- Feed、搜索、RAG 对齐：分别增强缓存版本化、Outbox 投影幂等重试、向量索引单一版本过滤。

### 代码质量 Review

- 新增改动保持在既有 Spring Boot、Redis、Kafka、MyBatis 和测试风格内，没有引入额外架构依赖。
- 高风险链路优先使用原子 Lua、Kafka 手动 ack、Redis 幂等 key、失败抛出或 retry key 兜底，符合“最终一致但不静默丢失”的设计方向。
- 关键方法均补充或保留了说明性注释，重点解释分布式一致性、回放、缓存迁移和索引版本边界。
- 测试覆盖以模块级回归为主，覆盖异常路径、重复消息、失败回滚、配置校验和上下文启动。

## 已执行验证

- `mvn.cmd -Dtest=InteractionServiceImplTest test`：通过，8 tests，0 failures，0 errors。
- 既有全量验证记录：`mvn.cmd test` 通过，156 tests，0 failures，0 errors。
- `git diff --check`：通过，仅有 Windows CRLF 提示。

## 后续建议

- 若继续演进亿级流量架构，可优先补充观测指标：Kafka consumer lag、Redis Lua 失败率、retry key 积压量、Feed 回源率、RAG 旧版本过滤数量。
- 点赞和计数链路下一阶段可加入分片位图索引集合，替代按 pattern 扫描重建，降低大规模 Redis keyspace 扫描风险。
