# 邻里知光 后端 API 开发文档（超详细）

> 基线文档：`邻里知光-技术分档-详细版.md`、`openapi.yaml`
> 文档版本：v1.0（重构期）
> 范围说明：本文件是后端开发与联调基准，不修改既有接口行为。

## 1. 全局规范

### 1.1 基础约定
- Base URL：`/api/v1`
- 认证：默认 `Authorization: Bearer <JWT>`，`/auth/register`、`/auth/login`、`/auth/token/refresh`、`/auth/password/reset` 为匿名可调用。
- 协议：`application/json`；RAG 流式问答为 `text/event-stream`。
- 成熟度标记：`已实现` / `部分实现` / `规划中`（来自 `openapi.yaml` 的 `x-maturity`）。

### 1.2 通用响应信封
- 成功：`ApiResponse`
  - `code: string`
  - `message: string`
  - `data: any`
  - `requestId: string`
  - `timestamp: date-time`
- 失败：`ErrorResponse`
  - `code: string`
  - `message: string`
  - `errors[]: { field, reason }`
  - `requestId: string`
  - `timestamp: date-time`

### 1.3 通用错误响应
- `400` 参数校验失败
- `401` 认证失败
- `404` 资源不存在
- `409` 状态冲突
- `429` 触发限流
- `503` 依赖服务不可用

### 1.4 分页与查询参数约定
- 页码分页：`page`（默认1）、`size`（默认20，上限100）
- 游标分页：`searchAfter`（Base64）
- 地理参数：`lat`、`lng`、`radius`（默认3000，范围1~50000）

## 2. 模块-接口映射矩阵（12 模块）

| 模块 | 主要外部 HTTP API | 内部事件 API | 运维 API |
| --- | --- | --- | --- |
| 模块1 认证系统 | `/auth/*` | 无 | 可复用模块9限流策略 |
| 模块2 LBS知识发现 | `/discover/nearby`、`/search/posts`、`/search/suggest` | 无 | 无 |
| 模块3 知识发布系统 | `/posts/*`、`/storage/presign` | `post-published` | 无 |
| 模块4 AI知识引擎 | `/rag/queries/stream` | `rag-index-rebuild` | 可观测指标由模块12承载 |
| 模块5 社交裂变系统 | `/interactions/*`、`/follows/*` | `counter-event`、`follow-event` | 可复用模块9限流策略 |
| 模块6 智能Feed流 | `/feed/home` | 消费 `post-published`、`follow-event`（派生） | 命中率与延迟指标在模块12 |
| 模块7 高并发交易引擎 | `/trade/*` | `seckill-success`、`order-timeout` | 可复用模块9限流策略 |
| 模块8 LADBTP线程池 | 无业务直连HTTP | 任务执行内部事件（实现态） | `/ops/thread-pools/{poolName}` |
| 模块9 缓存与防护中心 | 无业务直连HTTP | 限流/降级命中内部记录 | `/ops/rate-limits/scenes/{scene}` |
| 模块10 数据库设计规范 | 无 | Outbox/Key/索引规范契约 | 通过模块12指标观测 |
| 模块11 一致性保障 | 无业务直连HTTP | Outbox + Canal + Kafka 全事件链 | 重放/对账工具（内部） |
| 模块12 可扩展与治理 | `/ops/metrics/overview` | 规划态扩展事件 | 指标总览、发布治理 |

> 说明：`/search/*` 在当前 `openapi.yaml` 已存在，归入模块2“发现与召回”能力域，确保全量接口映射闭环。

---

## 3. 模块1：认证系统（JWT 双令牌 + Redis 白名单）

实现级细化文档：`认证模块-开发实现清单.md`

### 3.1 模块目标/边界
- 目标：提供低延迟认证、可吊销刷新令牌、支持设备级会话治理。
- 边界：仅负责身份认证和令牌生命周期，不负责复杂 RBAC 策略配置。

### 3.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求模型 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/auth/send-code` | 发送验证码 | `SendCodeRequest` | `SendCodeResponse` | 400/429 | 已实现 |
| POST | `/auth/register` | 用户注册（不自动登录） | `RegisterRequest` | `RegisterResult` | 400/409/429 | 已实现 |
| POST | `/auth/login` | 用户登录并返回会话 | `LoginRequest` | `AuthSessionData` | 400/401/429 | 已实现 |
| POST | `/auth/token/refresh` | 刷新令牌 | `RefreshTokenRequest` | `AuthTokens` | 400/401 | 已实现 |
| POST | `/auth/logout` | 用户登出（支持范围） | `LogoutRequest` | `ActionResult` | 401 | 已实现 |
| POST | `/auth/password/reset` | 重置密码 | `PasswordResetRequest` | `ActionResult` | 400/429 | 已实现 |

请求关键字段：
- `RegisterRequest`：`phone/password/nickname/smsCode`
- `LoginRequest`：`identifier/password/channel/captchaToken`
- `LogoutRequest.logoutScope`：`current_device|all_devices`

### 3.3 内部事件契约
- 当前 OpenAPI 未声明认证域对外事件主题。
- 建议内部审计事件（实现可选）：`auth-login-success`、`auth-refresh-rotated`、`auth-logout`。

### 3.4 数据模型
- `RegisterResult`：`userId`（注册后不自动签发令牌，前端需额外调用 /auth/login）
- `AuthTokens`：`accessToken/accessExpiresAt/refreshToken/refreshExpiresAt/tokenType`
- `AuthSessionData`：`userId + tokens`（仅 /auth/login 返回）
- `SendCodeResponse`：`sent/expireAt`
- Redis 关键键（来自技术文档）：
  - `auth:rt:{uid}:{jti}`（refresh 白名单）
  - `auth:fail:{identifier}`（登录失败计数）
  - `jwt:refresh:{user_id}`（旧文档兼容键）

### 3.5 一致性与幂等
- Refresh 轮换必须保证“旧token失效 + 新token生效”同请求闭环。
- 登出建议幂等：重复登出返回成功态 `ActionResult.success=true`。
- 密码重置建议幂等键：`phone + smsCode + 时间窗`。

### 3.6 限流降级
- 登录/重置密码：用户/IP 双维滑窗限流。
- Redis 不可用时：拒绝高风险刷新请求，避免放过非法会话。

### 3.7 验收指标
- 登录成功率、刷新成功率、401 占比。
- 令牌签发 RT、刷新 RT、异常登出吊销时延。

### 3.8 实现成熟度
- 结论：`已实现`（依据 `openapi.yaml`）

---

## 4. 模块2：LBS 知识发现（GeoHash + GeoRadius + Search）

### 4.1 模块目标/边界
- 目标：提供“附近发现 + 关键词搜索 + 联想补全”统一召回入口。
- 边界：只负责召回与基础重排，不承担商业投放预算分配。

### 4.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求/参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/discover/nearby` | 附近发现 | `lat/lng/radius/page/size/entityType/tag` | `NearbyData` | 400/401 | 规划中 |
| GET | `/search/posts` | 搜索文章 | `q/page/size/searchAfter/lat/lng/radius/tag` | `SearchPostsData` | 400/401 | 已实现 |
| GET | `/search/suggest` | 联想词 | `q/size` | `SuggestData` | 400/401 | 已实现 |

关键约束：
- `entityType`：`post|merchant|mixed`
- `searchAfter`：用于深翻页，避免大偏移分页性能问题。

### 4.3 内部事件契约
- 无强制外发事件。
- 可选消费：`post-published` 更新检索库（通常由模块3和模块11处理）。

### 4.4 数据模型
- `NearbyItem`：`id/entityType/title/summary/distanceMeters/freshnessScore/interactionScore/trustScore/location`
- `SearchResultItem`：`postId/title/summary/score/distanceMeters/searchAfter[]`
- 建议索引/缓存键：
  - `geo:knowledge`
  - `geo:merchant`
  - `lbs:result:{uid}:{hash}`（短TTL）

### 4.5 一致性与幂等
- 查询接口天然幂等；通过 `requestId` 支持链路追踪。
- 索引异步刷新时允许短暂不一致，需提供降级回退（见 4.6）。

### 4.6 限流降级
- 发现/搜索接口按 IP + 用户维度限流。
- ES 故障降级：返回基础 Feed 或最近缓存结果。

### 4.7 验收指标
- 附近发现 `P95 < 150ms`（缓存命中场景）。
- 搜索接口错误率、联想命中率、search_after 深翻页成功率。

### 4.8 实现成熟度
- `/discover/nearby`：`规划中`
- `/search/*`：`已实现`
- 模块结论：`部分实现`

---

## 5. 模块3：知识发布系统（OSS 直传 + 元数据 + 发布）

### 5.1 模块目标/边界
- 目标：后端只处理元数据与状态机，避免承接大文件流量。
- 边界：不负责长视频转码编排。

### 5.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求模型 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/posts/drafts` | 创建草稿 | `CreateDraftRequest` | `DraftData` | 400/401 | 已实现 |
| POST | `/storage/presign` | 申请上传预签名 | `PresignRequest` | `PresignData` | 400/401 | 已实现 |
| POST | `/posts/{postId}/content/confirm` | 确认上传内容 | `ConfirmContentRequest` | `ConfirmContentData` | 400/401/404 | 已实现 |
| PUT | `/posts/{postId}/metadata` | 更新元数据 | `UpdatePostMetadataRequest` | `PostDetail` | 400/401/404 | 已实现 |
| POST | `/posts/{postId}/publish` | 发布内容 | `PublishPostRequest` | `PostDetail` | 400/401/409 | 已实现 |
| GET | `/posts/{postId}` | 内容详情 | path:`postId` | `PostDetail` | 401/404 | 已实现 |

状态机建议：`draft -> content_confirmed -> metadata_completed -> published`

### 5.3 内部事件契约
- 主题：`post-published`
- 事件类型：`POST_PUBLISHED`
- 触发：发布成功后
- 载荷：`PostPublishedPayload`（`eventId/eventType/postId/authorId/visibility/location/occurredAt`）
- 幂等键：`eventId`
- 补偿：摘要重试、搜索索引回填、Feed 刷新

### 5.4 数据模型
- 上传链：`PresignRequest(purpose=content|cover|image)` -> `PresignData(uploadUrl/objectKey/expireAt)`
- 内容确认：`objectKey/etag/sha256/size`
- 详情对象：`PostDetail`（作者、标签、地理信息、互动计数、发布时间）
- 关键存储约束：后端必须校验 `objectKey` 前缀归属，防越权引用。

### 5.5 一致性与幂等
- `content/confirm` 按 `postId + objectKey + etag` 幂等。
- `publish` 幂等：已发布再次发布返回 409 或等价成功态（按实现择一，需统一）。
- 发布事务与 outbox 需同库同事务提交（见模块11）。

### 5.6 限流降级
- 预签名接口限流，防刷 OSS 签名。
- 摘要服务异常不阻断发布主链路，走异步补偿。

### 5.7 验收指标
- 发布成功率、确认上传成功率。
- 发布到可检索延迟、发布到可见延迟。

### 5.8 实现成熟度
- 结论：`已实现`

---

## 6. 模块4：AI 知识引擎（RAG + SSE）

### 6.1 模块目标/边界
- 目标：提供可解释的流式问答，支持按文章或地理上下文过滤召回。
- 边界：仅回答可检索上下文支持的问题。

### 6.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求模型 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/rag/queries/stream` | 流式问答 | `RagQueryRequest` | SSE(`SseChunk`) | 400/401/429/503 | 已实现 |

关键字段：
- 入参：`question`(required)、`postId`、`lat`、`lng`、`topK`(default=8)、`sessionId`
- SSE 事件：`message`、`done`、`error`

### 6.3 内部事件契约
- 主题：`rag-index-rebuild`
- 事件类型：`RAG_INDEX_REBUILD_REQUESTED`
- 触发：内容更新或索引重建请求
- 载荷：`RagIndexRebuildPayload(eventId/postId/etag/sha256/version/occurredAt)`
- 幂等键：`postId + version`

### 6.4 数据模型
- 引用片段：`RagReference(postId/chunkId/title)`
- 流式分片：`SseChunk(event/seq/delta/references/finishReason/errorCode)`
- 问答审计建议：记录问题、召回片段ID、模型耗时、终态。

### 6.5 一致性与幂等
- 查询天然幂等（同输入不保证完全同输出，但可追踪）。
- 索引重建必须版本化，避免旧分片污染。

### 6.6 限流降级
- 问答接口按用户/IP限流，防成本击穿。
- LLM 或向量库不可用时返回 503 并进入关键词检索降级。

### 6.7 验收指标
- 首 token 时延（目标 < 800ms，预索引后）。
- 引用覆盖率、拒答合理率、SSE 完整结束率。

### 6.8 实现成熟度
- 结论：`已实现`

---

## 7. 模块5：社交裂变系统（点赞/收藏 + 关注）

### 7.1 模块目标/边界
- 目标：高并发交互下保持幂等与最终一致。
- 边界：活动激励策略不在本模块。

### 7.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求/参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/interactions/targets/{targetType}/{targetId}/like` | 点赞 | path:`targetType,targetId` | `InteractionActionData` | 401/404/429 | 已实现 |
| DELETE | `/interactions/targets/{targetType}/{targetId}/like` | 取消点赞 | path | `InteractionActionData` | 401/404 | 已实现 |
| POST | `/interactions/targets/{targetType}/{targetId}/favorite` | 收藏 | path | `InteractionActionData` | 401/404 | 已实现 |
| DELETE | `/interactions/targets/{targetType}/{targetId}/favorite` | 取消收藏 | path | `InteractionActionData` | 401/404 | 已实现 |
| GET | `/interactions/targets/{targetType}/{targetId}/summary` | 查询互动汇总 | path | `InteractionSummary` | 401/404 | 已实现 |
| POST | `/follows/{followeeId}` | 关注用户 | path:`followeeId` | `FollowActionData` | 401/404/409 | 已实现 |
| DELETE | `/follows/{followeeId}` | 取消关注 | path | `FollowActionData` | 401/404 | 已实现 |
| GET | `/follows/users/{userId}/following` | 关注列表 | path+page/size | `FollowListData` | 401 | 已实现 |
| GET | `/follows/users/{userId}/followers` | 粉丝列表 | path+page/size | `FollowListData` | 401 | 已实现 |

### 7.3 内部事件契约
1) `counter-event`
- 事件类型：`LIKE_CHANGED`、`FAVORITE_CHANGED`
- 载荷：`CounterEventPayload(eventId/eventType/targetType/targetId/action/operatorId/occurredAt)`
- 幂等键：`eventId`

2) `follow-event`
- 事件类型：`FOLLOW_CREATED`、`FOLLOW_REMOVED`
- 载荷：`FollowEventPayload(eventId/eventType/followerId/followeeId/occurredAt)`
- 幂等键：`eventId`

### 7.4 数据模型
- 互动动作：`InteractionActionData(targetType/targetId/action/active/snapshotVersion)`
- 互动汇总：`InteractionSummary(likeCount/favoriteCount/viewerLiked/viewerFavorited)`
- 关系列表：`FollowUserItem` + `PageMeta`
- 关键缓存键（技术文档）：
  - `like:bit:{target_id}`
  - `count:like:{target_id}`
  - `count:fans:{user_id}`
  - `follow:list:{user_id}`

### 7.5 一致性与幂等
- 点赞/收藏写路径应 Lua 原子切换，重复点击不重复计数。
- 关注操作主库 + outbox 同事务；下游异步更新允许短暂不一致。

### 7.6 限流降级
- 点赞/收藏/关注接口强制限流（用户维 + IP维）。
- 计数服务异常可回退快照值，保业务可用。

### 7.7 验收指标
- 重复操作幂等正确率。
- 计数延迟、粉丝列表新鲜度、Kafka 积压深度。

### 7.8 实现成熟度
- 结论：`已实现`

---

## 8. 模块6：智能 Feed 流（三级缓存 + 混合排序）

### 8.1 模块目标/边界
- 目标：首页低延迟高命中，支持地理加权和热度混排。
- 边界：不承担复杂推荐模型训练。

### 8.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/feed/home` | 首页Feed | `page/size/lat/lng/geoHash` | `FeedData` | 401/429 | 已实现 |

### 8.3 内部事件契约
- 直接对外事件未定义。
- 典型消费来源：`post-published`、`follow-event`，用于局部刷新或分段重建。

### 8.4 数据模型
- `FeedData(items/page/cacheLayer)`
- `FeedItem(postId/title/summary/author/distanceMeters/hotScore/publishedAt)`
- 缓存层标记：`cacheLayer` = `L1|L2|L3|DB`
- 建议键：
  - `feed:page:{user_id}:{page}`
  - `feed:segment:{geo_hash}:{timestamp}`

### 8.5 一致性与幂等
- 同页请求使用 single-flight，避免并发回源放大。
- 发布后按影响范围失效缓存，允许短暂最终一致。

### 8.6 限流降级
- Feed 接口可按用户/IP限流。
- 下游检索故障时回退基础时间流。

### 8.7 验收指标
- 首页缓存命中率 > 95%。
- `P95/P99` 延迟、回源率、击穿次数。

### 8.8 实现成熟度
- 结论：`已实现`

---

## 9. 模块7：高并发交易引擎（秒杀/团购）

### 9.1 模块目标/边界
- 目标：防超卖、防重入、低 RT 受理。
- 边界：外部支付网关不在本模块内。

### 9.2 HTTP 接口
| 方法 | 路径 | 说明 | 请求/参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| POST | `/trade/seckill/requests` | 秒杀预检并受理 | header:`X-Idempotency-Key` + `SeckillRequest` | `SeckillAcceptedData` | 400/401/409/429 | 规划中 |
| GET | `/trade/seckill/requests/{requestId}` | 查询受理状态 | path:`requestId` | `SeckillStatusData` | 401/404 | 规划中 |
| GET | `/trade/orders/{orderId}` | 查询订单 | path:`orderId` | `OrderDetail` | 401/404 | 规划中 |
| POST | `/trade/orders/{orderId}/cancel` | 主动取消订单 | path:`orderId` | `ActionResult` | 401/404/409 | 规划中 |

关键字段：
- `SeckillRequest(activityId/productId/quantity/skuAttrs)`
- `SeckillStatusData.status`：`accepted|queued|ordering|ordered|failed|expired`
- `OrderDetail.status`：`INIT|PENDING_PAY|PAID|CLOSED|CANCELLED`

### 9.3 内部事件契约
1) `seckill-success`
- 类型：`SECKILL_PRECHECK_PASSED`
- 载荷：`SeckillSuccessPayload(requestId/activityId/productId/userId/quantity/acceptedAt)`
- 幂等键：`requestId`

2) `order-timeout`
- 类型：`ORDER_PAYMENT_TIMEOUT`
- 载荷：`OrderTimeoutPayload(eventId/orderId/userId/expireAt/occurredAt)`
- 幂等键：`orderId + expireAt`

### 9.4 数据模型
- 请求受理：`SeckillAcceptedData(requestId/accepted/queueStatus/acceptedAt)`
- 订单明细：`OrderDetail(..., version)`，用于乐观锁并发控制。

### 9.5 一致性与幂等
- 入参头 `X-Idempotency-Key` 必填，服务端需落库防重复。
- 库存预扣与下单解耦，使用事件驱动 + 补偿闭环。

### 9.6 限流降级
- 下单入口强限流（场景级 + 用户级）。
- 积压高时降级为排队提示，必要时熔断受理入口。

### 9.7 验收指标
- 超卖率=0、重复下单率可控。
- 受理 RT、排队转订单成功率、超时关单回补成功率。

### 9.8 实现成熟度
- 结论：`规划中`

---

## 10. 模块8：LADBTP（负载感知动态缓冲线程池）

### 10.1 模块目标/边界
- 目标：解决异步任务峰值场景中的拒绝风暴与扩容迟滞。
- 边界：该模块以运行时契约为主，业务方通过线程池执行器接入。

### 10.2 HTTP 接口（运维）
| 方法 | 路径 | 说明 | 请求参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/ops/thread-pools/{poolName}` | 查询线程池快照 | path:`poolName` | `ThreadPoolSnapshot` | 401/404 | 部分实现 |

### 10.3 内部事件契约
- 外部总线事件：无强制定义。
- 运行时事件（内部）：任务拒绝、队列饱和、扩容动作记录。

### 10.4 数据模型
- `ThreadPoolSnapshot`：
  - `corePoolSize/maxPoolSize/activeCount/queueSize/completedTaskCount`
  - `saturationLevel`：`low|medium|high`
- 建议附加指标：排队时长分位、拒绝计数、任务吞吐。

### 10.5 一致性与幂等
- 读接口天然幂等。
- 线程池动态调参须保证配置版本可回滚。

### 10.6 限流降级
- 运维接口需鉴权并可限流，避免监控风暴。
- 高饱和时可触发任务降级策略（轻/中/重负载分流）。

### 10.7 验收指标
- 峰值无集中拒绝，RT/吞吐/CPU 曲线平稳。

### 10.8 实现成熟度
- 结论：`部分实现`

边界分类：
- 外部 HTTP API：`/ops/thread-pools/{poolName}`
- 内部事件 API：线程池运行事件（内部）
- 运维 API：同外部接口 + 指标采集

---

## 11. 模块9：缓存与防护中心（全链路防护）

### 11.1 模块目标/边界
- 目标：防穿透、防击穿、防雪崩、防刷。
- 边界：不承载业务语义，只提供通用防护能力。

### 11.2 HTTP 接口（运维）
| 方法 | 路径 | 说明 | 请求参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/ops/rate-limits/scenes/{scene}` | 查询限流场景 | path:`scene(login|interaction|rag|trade|global)` | `RateLimitScene` | 401/404 | 部分实现 |

### 11.3 内部事件契约
- 无强制总线事件。
- 建议内部记录：限流命中、降级触发、缓存重建失败。

### 11.4 数据模型
- `RateLimitScene(scene/enabled/dimensions/windowSeconds/threshold/degradeStrategy)`
- 防护维度：`global/ip/user`
- 关键键示例：`rate:limit:{ip}:{user_id}`

### 11.5 一致性与幂等
- 限流配置读取幂等。
- 配置更新建议版本化与灰度生效。

### 11.6 限流降级
- 本模块即限流降级中心：
  - 穿透：布隆 + 空值缓存
  - 击穿：single-flight + 互斥重建
  - 雪崩：TTL抖动 + 热点延寿 + 分层缓存

### 11.7 验收指标
- 核心接口在攻击流量下可用。
- 告警定位到场景与维度。

### 11.8 实现成熟度
- 结论：`部分实现`

边界分类：
- 外部 HTTP API：`/ops/rate-limits/scenes/{scene}`
- 内部事件 API：限流命中与降级事件（内部）
- 运维 API：场景配置查询与审计

---

## 12. 模块10：数据库设计（MySQL + Redis Key 规范）

### 12.1 模块目标/边界
- 目标：保障事务正确性与热点查询性能。
- 边界：本模块为“数据契约 API”，非业务 HTTP API。

### 12.2 HTTP 接口
- 无业务直连 HTTP 接口。

### 12.3 内部事件契约
- 无独立业务事件主题；通过模块11事件流驱动派生数据。

### 12.4 数据模型
核心表（技术文档）：
- `knowledge(id,user_id,title,content,oss_urls,summary,geo_hash,location,create_time,version)`
- `user(id,phone,nickname,avatar,fans_count,follow_count)`
- `follow(follower_id,followee_id,create_time)`
- `outbox(id,aggregate_type,aggregate_id,event_type,payload,status)`
- `orders(id,user_id,knowledge_id/product_id,status,version,expire_time)`
- `like_favorite(user_id,target_id,type,create_time)`

Redis Key 规范（技术文档）：
- `like:bit:{target_id}`
- `count:like:{target_id}`
- `count:fans:{user_id}`
- `follow:list:{user_id}`
- `geo:knowledge`
- `geo:merchant`
- `feed:page:{user_id}:{page}`
- `feed:segment:{geo_hash}:{timestamp}`
- `rate:limit:{ip}:{user_id}`
- `jwt:refresh:{user_id}`

### 12.5 一致性与幂等
- 表结构要求 `version` 字段支持乐观锁。
- Outbox 表与业务变更同事务，保证最终一致性来源可信。

### 12.6 限流降级
- 数据层异常时由上层模块执行降级，数据库层不直接暴露限流接口。

### 12.7 验收指标
- 慢 SQL 全量收敛方案。
- Key 空间可观测、可统计、可清理。

### 12.8 实现成熟度
- 结论：`规范已定义，落地进度随业务模块推进`。

边界分类：
- 外部 HTTP API：无
- 内部事件 API：依赖模块11事件链
- 运维 API：通过模块12指标侧观测

---

## 13. 模块11：整体一致性保障（Outbox + Canal + Kafka + 乐观锁）

### 13.1 模块目标/边界
- 目标：把跨存储一致性问题转化为可重放、可补偿、可观测的事件链。
- 边界：不承担具体业务处理逻辑，只定义一致性基础设施协议。

### 13.2 HTTP 接口
- 无业务直连 HTTP 接口。

### 13.3 内部事件契约
统一字段：`eventId`、`eventType`、`occurredAt`，消费端按幂等键防重。

已定义事件（来自 `x-internal-events`）：
1. `counter-event`：`LIKE_CHANGED|FAVORITE_CHANGED`
2. `follow-event`：`FOLLOW_CREATED|FOLLOW_REMOVED`
3. `post-published`：`POST_PUBLISHED`
4. `rag-index-rebuild`：`RAG_INDEX_REBUILD_REQUESTED`
5. `seckill-success`：`SECKILL_PRECHECK_PASSED`（规划中）
6. `order-timeout`：`ORDER_PAYMENT_TIMEOUT`（规划中）

标准事件流：
1) 业务事务提交
2) Outbox 入库
3) Canal 订阅 binlog
4) Kafka 分发
5) 消费者幂等落地派生数据

### 13.4 数据模型
- Outbox payload 对应各 `*Payload` schema。
- 消费幂等键：`eventId` 或业务复合键（如 `postId+version`）。

### 13.5 一致性与幂等
- 强一致：单库事务内。
- 最终一致：跨 Redis/ES/派生表通过事件驱动达成。
- 补偿：DLQ + 重放工具 + 周期对账。

### 13.6 限流降级
- 消费端积压时可降级非关键派生更新，保主链路。
- 重试上限后进入 DLQ，禁止无限重试放大。

### 13.7 验收指标
- 故障恢复后数据可回正。
- 重放成功率、对账偏差率、积压深度可视化。

### 13.8 实现成熟度
- 结论：`核心链路已实现，交易相关事件为规划中`。

边界分类：
- 外部 HTTP API：无
- 内部事件 API：本模块核心
- 运维 API：重放/对账（内部工具）

---

## 14. 模块12：可扩展性与未来规划（治理与平台化）

### 14.1 模块目标/边界
- 目标：支撑多业务线扩展、统一指标观测与发布治理。
- 边界：具体业务能力仍归属各业务模块。

### 14.2 HTTP 接口（运维）
| 方法 | 路径 | 说明 | 请求参数 | 返回模型 | 错误码 | 成熟度 |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/ops/metrics/overview` | 指标总览 | 无 | `MetricsOverview` | 401 | 规划中 |

### 14.3 内部事件契约
- 当前 OpenAPI 未定义平台治理事件。
- 规划建议：发布变更审计事件、灰度策略变更事件、回滚事件。

### 14.4 数据模型
- `MetricsOverview`：`availability/feedP95Ms/lbsP95Ms/ragFirstTokenMs/tradeAcceptP95Ms/observedAt`
- 指标需支持按模块维度切片。

### 14.5 一致性与幂等
- 指标查询幂等。
- 指标上报需保证时间序列可去重（建议 `metric + timestamp + labels`）。

### 14.6 限流降级
- 指标接口可缓存短 TTL，防监控查询打爆后端。
- 采集链路异常时允许展示最近窗口快照。

### 14.7 验收指标
- 新模块接入后可在统一面板观测。
- 关键SLO可直接读取并告警。

### 14.8 实现成熟度
- 结论：`规划中`

边界分类：
- 外部 HTTP API：`/ops/metrics/overview`
- 内部事件 API：治理事件（规划）
- 运维 API：指标聚合与治理入口

---

## 15. 覆盖率核对清单（openapi.yaml）

- 认证 5 个操作：已映射到模块1。
- 发现/搜索 3 个操作：已映射到模块2。
- 发布/存储 6 个操作：已映射到模块3。
- RAG 1 个操作：已映射到模块4。
- 社交 9 个操作：已映射到模块5。
- Feed 1 个操作：已映射到模块6。
- 交易 4 个操作：已映射到模块7。
- 运维 3 个操作：已映射到模块8/9/12。

合计：`32` 个操作，映射完成。

## 16. 待补充项（显式留白）
- 交易域（模块7）为规划中，状态流转与库存补偿细节需在实现阶段补充 SQL/状态图。
- 模块8/9/12 需补充实际运维鉴权模型（管理员角色、审计策略）。
- 模块10/11 需补充内部重放工具接口说明（若后续对外开放再纳入 OpenAPI）。
