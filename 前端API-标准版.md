# 邻里知光 前端 API 开发文档（标准版）

> 基线文档：`邻里知光-技术分档-详细版.md`、`openapi.yaml`
> 面向角色：前端开发、联调测试、接口封装维护

## 1. 前端全局调用规范

### 1.1 基础约定
- API 前缀：`/api/v1`
- 鉴权：除匿名认证接口外，统一携带 `Authorization: Bearer <accessToken>`。
- 响应信封：统一解析 `ApiResponse`，业务数据在 `data`。
- 错误信封：`ErrorResponse`，优先消费 `code/message/errors`。

### 1.2 Token 策略
- 登录成功保存 `accessToken` + `refreshToken`。
- 接口收到 `401`：单飞刷新 `POST /auth/token/refresh`，成功后重放原请求。
- 刷新失败：清理会话并跳转登录页。

### 1.3 通用错误处理
- `400`：表单校验提示。
- `401`：触发刷新或重登。
- `404`：资源不存在提示。
- `409`：状态冲突提示（如重复发布/重复关注）。
- `429`：限流提示（可含倒计时重试）。
- `503`：依赖服务不可用（展示降级文案）。

### 1.4 重试与缓存
- 读接口：网络超时可重试 1~2 次（指数退避）。
- 写接口：默认不自动重试，除非带幂等键。
- 分页列表：优先内存缓存最近页，避免重复请求。

---

## 2. 模块1：认证系统

### 2.1 调用入口
- `POST /auth/send-code`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/token/refresh`
- `POST /auth/logout`
- `POST /auth/password/reset`

### 2.2 请求参数
- 发送验证码：`phone/scene(register|login|reset_password)`
- 注册：`phone/password/nickname/smsCode`
- 登录：`identifier/password/channel/captchaToken`
- 刷新：`refreshToken`
- 登出：`refreshToken/logoutScope(current_device|all_devices)`
- 重置密码：`phone/smsCode/newPassword`

### 2.3 响应结构
- 发送验证码：`SendCodeResponse(sent/expireAt)`
- 注册：`RegisterResult(userId)`（注册后不自动登录，前端需额外调用 /auth/login）
- 登录：`AuthSessionData(userId,tokens)`
- 刷新：`AuthTokens`
- 登出/重置：`ActionResult(success/action/resourceId/status)`

### 2.4 错误码处理
- 登录 `401`：账号密码错误提示。
- 注册 `409`：手机号冲突提示。
- 认证链路 `429`：增加验证码或稍后重试。

### 2.5 页面状态机
- 登录页：`idle -> submitting -> success/fail`
- 会话态：`valid -> refreshing -> valid/expired`

### 2.6 SSE/轮询策略
- 无。

### 2.7 缓存与重试策略
- Token 只存安全存储（Web 可考虑 HttpOnly Cookie 方案）。
- 刷新请求必须单飞，避免并发刷新覆盖。

### 2.8 联调注意事项
- 匿名接口不要带过期 token，避免被网关误拦截。
- `tokenType` 当前示例为 `Bearer`，前端拼接时不要硬编码大小写异常。

---

## 3. 模块2：LBS知识发现（含搜索）

### 3.1 调用入口
- `GET /discover/nearby`
- `GET /search/posts`
- `GET /search/suggest`

### 3.2 请求参数
- 附近发现：`lat/lng/radius/page/size/entityType/post|merchant|mixed/tag`
- 搜索：`q/page/size/searchAfter/lat/lng/radius/tag`
- 联想：`q/size(<=20)`

### 3.3 响应结构
- 附近发现：`NearbyData(items + PageMeta)`
- 搜索：`SearchPostsData(items + CursorPageMeta)`
- 联想：`SuggestData(items)`

### 3.4 错误码处理
- `400`：位置参数或关键词非法。
- `401`：未登录或 token 失效。

### 3.5 页面状态机
- 发现页：`定位中 -> 拉取中 -> 展示/空态/错误`
- 搜索页：`输入中 -> 联想 -> 搜索结果 -> 深翻页`

### 3.6 SSE/轮询策略
- 无。

### 3.7 缓存与重试策略
- 联想接口本地防抖（200~300ms）。
- 搜索分页保留 `searchAfter` 游标，避免 page 深翻性能问题。
- 定位抖动时短窗口复用上次结果。

### 3.8 联调注意事项
- `discover/nearby` 当前成熟度为规划中，前端需保留功能开关。
- 经纬度为空时要有默认区域兜底逻辑。

---

## 4. 模块3：知识发布系统

### 4.1 调用入口
- `POST /posts/drafts`
- `POST /storage/presign`
- `POST /posts/{postId}/content/confirm`
- `PUT /posts/{postId}/metadata`
- `PATCH /posts/{postId}`
- `POST /posts/{postId}/publish`
- `PATCH /posts/{postId}/top`
- `PATCH /posts/{postId}/visibility`
- `DELETE /posts/{postId}`
- `GET /posts/{postId}`
- `GET /posts/feed`
- `GET /posts/mine`

### 4.2 请求参数
- 草稿：无（自动生成）
- 预签名：`postId/filename/contentType/purpose(content|cover|image)`
- 内容确认：`objectKey/etag/sha256/size`
- 元数据：`title/summary/tags/imageUrls/location/isTop/visibility`
- 发布：无参数
- 修改：同元数据（PATCH）
- 置顶：`isTop`
- 可见性：`visibility(public|followers|private)`
- Feed 列表：`page/size`，可选 `lat/lng/geoHash`
- 我的帖子：`page/size`

### 4.3 响应结构
- 草稿：`DraftData(postId,status,createdAt)`
- 预签名：`PresignData(uploadUrl,objectKey,expireAt)`
- 确认：空（成功无数据体）
- 详情/发布/元数据更新：`PostDetail`
- 列表：`PostPageData(items,page,size,hasMore)`

### 4.4 错误码处理
- `404`：`postId` 不存在或无权限。
- `409`：发布状态冲突。

### 4.5 页面状态机
- 发布流程：`创建草稿 -> 上传 -> 确认 -> 编辑元数据 -> 发布`
- 任一步失败可回退到草稿态。

### 4.6 SSE/轮询策略
- 无。

### 4.7 缓存与重试策略
- OSS 上传失败可重传文件；业务确认接口需手动重试。
- 发布成功后主动失效详情缓存并触发列表刷新。

### 4.8 联调注意事项
- `objectKey` 与 `etag/sha256` 必须原样回传，避免校验失败。
- 预签名 URL 有过期时间，上传前需检查 `expireAt`。

---

## 5. 模块4：AI知识引擎（RAG）

### 5.1 调用入口
- `POST /rag/queries/stream`

### 5.2 请求参数
- 必填：`question`
- 可选：`postId/lat/lng/topK/sessionId`

### 5.3 响应结构
- `text/event-stream`
- 事件载荷可映射为 `SseChunk(event,seq,delta,references,finishReason,errorCode)`

### 5.4 错误码处理
- `429`：问答限流，前端提示稍后再试。
- `503`：模型或向量服务不可用，提示降级模式。

### 5.5 页面状态机
- 问答页：`输入 -> 建连 -> 流式输出 -> done/error`
- 支持中途取消（AbortController）。

### 5.6 SSE/轮询策略
- SSE 主流程：
  - `message`：追加 token
  - `done`：结束并落盘会话
  - `error`：停止并提示

### 5.7 缓存与重试策略
- 同一 `sessionId` 可本地缓存上下文。
- SSE 断线重连仅建议重发问题，不建议盲目续流。

### 5.8 联调注意事项
- 注意代理层需透传 `text/event-stream`，禁止缓存和缓冲。
- 若网关会超时，前端需要心跳/超时提示策略。

---

## 6. 模块5：社交裂变系统

### 6.1 调用入口
- `POST/DELETE /interactions/targets/{targetType}/{targetId}/like`
- `POST/DELETE /interactions/targets/{targetType}/{targetId}/favorite`
- `GET /interactions/targets/{targetType}/{targetId}/summary`
- `POST/DELETE /follows/{followeeId}`
- `GET /follows/users/{userId}/following`
- `GET /follows/users/{userId}/followers`

### 6.2 请求参数
- `targetType`：`post|merchant`
- `targetId`、`followeeId`、`userId`
- 列表接口：`page/size`

### 6.3 响应结构
- 动作接口：`InteractionActionData` 或 `FollowActionData`
- 汇总接口：`InteractionSummary`
- 列表接口：`FollowListData`

### 6.4 错误码处理
- `404`：目标不存在。
- `409`：关注状态冲突。
- `429`：交互过频。

### 6.5 页面状态机
- 点赞/收藏：乐观更新 -> 服务端确认 -> 回滚（失败时）。
- 关注按钮：`未关注 -> 已关注` 双向切换。

### 6.6 SSE/轮询策略
- 无强制 SSE。
- 可按需轮询汇总接口修正计数漂移。

### 6.7 缓存与重试策略
- 动作接口不自动重试，防止误触发。
- 汇总和列表可短 TTL 缓存。

### 6.8 联调注意事项
- 乐观更新必须配套失败回滚。
- 不要在 UI 层自己累加计数后长期不校正。

---

## 7. 模块6：个人资料（Profile）

### 7.1 调用入口
- `GET /profile/me`
- `PATCH /profile/me`
- `POST /profile/avatar`
- `GET /profile/users/{userId}`
- `GET /profile/users/{userId}/posts`
- `GET /profile/users/{userId}/following`
- `GET /profile/users/{userId}/followers`

### 7.2 请求参数
- 更新资料：`nickname/bio`
- 更新头像：`avatarUrl`
- 用户主页/帖子/关注/粉丝：`userId`
- 帖子列表：`page/size`

### 7.3 响应结构
- 资料：`ProfileData(userId,nickname,avatar,bio,socialCounters,relationStatus)`
- 用户列表：`ProfileListData(items,page,size,hasMore)`
- 帖子列表：`PostPageData(items,page,size,hasMore)`

### 7.4 错误码处理
- `404`：用户不存在。
- `401`：未登录访问需认证的操作。

### 7.5 页面状态机
- 个人页：`加载中 -> 展示资料/编辑 -> 保存`
- 他人主页：`加载中 -> 展示资料+帖子`

### 7.6 SSE/轮询策略
- 无。

### 7.7 缓存与重试策略
- 用户资料可短 TTL（如 60s）缓存。
- 帖子列表分页缓存，翻页失败保留上页数据。

### 7.8 联调注意事项
- `/profile/users/{userId}` 支持匿名访问；携带 token 时补充关系态。
- 头像更新接口单独暴露，方便独立上传流程。

---

## 8. 模块7：评论系统

### 8.1 调用入口
- `GET /posts/{postId}/comments`
- `POST /posts/{postId}/comments`

### 8.2 请求参数
- 列表：`page/size`
- 创建：`content`

### 8.3 响应结构
- 列表：`CommentPageData(items,page,hasMore)`
- `CommentItemData(id,postId,content,authorId,authorNickname,authorAvatar,createdAt)`

### 8.4 错误码处理
- `404`：帖子不存在。
- `401`：评论创建需登录。
- `409`：内容不可互动。

### 8.5 页面状态机
- 评论列表：`加载 -> 展示 + 翻页加载更多`
- 发表评论：`输入 -> 提交 -> 列表前置`

### 8.6 SSE/轮询策略
- 无。

### 8.7 缓存与重试策略
- 评论列表分页缓存，提交成功后乐观插入。
- 创建动作不自动重试，避免重复评论。

### 8.8 联调注意事项
- 评论列表支持 optional auth（携带 token 时可看到可见性过滤后的评论）。
- 评论创建必须携带有效 access token。

---

## 9. 模块8：智能Feed流

### 9.1 调用入口
- `GET /feed/home`

### 9.2 请求参数
- `page/size`
- 可选地理：`lat/lng/geoHash`

### 7.3 响应结构
- `FeedData(items,page,cacheLayer)`
- `cacheLayer` 可用于埋点分析命中层级（L1/L2/L3/DB）。

### 7.4 错误码处理
- `401`：会话失效。
- `429`：访问过频，前端降频拉取。

### 7.5 页面状态机
- 首页：`首屏加载 -> 下拉刷新 -> 翻页加载 -> 结束`

### 7.6 SSE/轮询策略
- 无。

### 7.7 缓存与重试策略
- 首屏失败可重试 1 次。
- 翻页失败保留上页数据，支持“点击重试”。

### 7.8 联调注意事项
- 传入位置信息后，结果排序可能变化，分页游标要按请求参数隔离缓存。

---

## 10. 模块9：高并发交易引擎

### 8.1 调用入口
- `POST /trade/seckill/requests`
- `GET /trade/seckill/requests/{requestId}`
- `GET /trade/orders/{orderId}`
- `POST /trade/orders/{orderId}/cancel`

### 8.2 请求参数
- 秒杀请求头：`X-Idempotency-Key`（必填）
- 秒杀体：`activityId/productId/quantity/skuAttrs`
- 查询参数：`requestId/orderId`

### 8.3 响应结构
- 受理：`SeckillAcceptedData(requestId,accepted,queueStatus,acceptedAt)`
- 受理状态：`SeckillStatusData(status,orderId,failureReason)`
- 订单：`OrderDetail`
- 取消：`ActionResult`

### 8.4 错误码处理
- `409`：重复请求或状态冲突。
- `429`：秒杀限流。

### 8.5 页面状态机
- 下单：`提交 -> 受理 -> 轮询中 -> 成功下单/失败/过期`

### 8.6 SSE/轮询策略
- 使用轮询 `GET /trade/seckill/requests/{requestId}`。
- 建议轮询间隔：500ms~1s，超时后给出排队提示。

### 8.7 缓存与重试策略
- 秒杀请求不自动重试，避免重复受理。
- 用户手动重试时必须重新生成幂等键。

### 8.8 联调注意事项
- 本模块成熟度为规划中，前端需灰度开关控制。
- 严格保留 `requestId` 供状态查询链路使用。

---

## 11. 模块10：LADBTP线程池（运维视角）

### 9.1 调用入口
- `GET /ops/thread-pools/{poolName}`

### 9.2 请求参数
- `poolName`

### 9.3 响应结构
- `ThreadPoolSnapshot(poolName,corePoolSize,maxPoolSize,activeCount,queueSize,completedTaskCount,saturationLevel)`

### 9.4 错误码处理
- `404`：线程池不存在。
- `401`：无运维权限。

### 9.5 页面状态机
- 线程池监控页：`查询 -> 渲染 -> 自动刷新`。

### 9.6 SSE/轮询策略
- 采用轮询（如 5~10s），无需 SSE。

### 9.7 缓存与重试策略
- 监控数据不做长缓存。
- 请求失败可短退避重试。

### 9.8 联调注意事项
- 该接口偏运维，不建议在普通用户端直接暴露。

---

## 12. 模块11：缓存与防护中心（运维视角）

### 10.1 调用入口
- `GET /ops/rate-limits/scenes/{scene}`

### 10.2 请求参数
- `scene`：`login|interaction|rag|trade|global`

### 10.3 响应结构
- `RateLimitScene(scene,enabled,dimensions,windowSeconds,threshold,degradeStrategy)`

### 10.4 错误码处理
- `404`：场景未配置。
- `401`：无运维权限。

### 10.5 页面状态机
- 防护配置页：`选择场景 -> 拉取配置 -> 展示策略`。

### 10.6 SSE/轮询策略
- 无强制需求，可按需轮询。

### 10.7 缓存与重试策略
- 场景配置可本地缓存短时间（如 30s）。

### 10.8 联调注意事项
- 与业务错误提示统一：命中限流时前端文案应区分“系统繁忙”与“操作过快”。

---

## 13. 模块12：数据库设计规范（前端消费视角）

### 11.1 调用入口
- 无前端直连 API。

### 11.2 请求参数
- 无。

### 11.3 响应结构
- 无。

### 11.4 错误码处理
- 无。

### 11.5 页面状态机
- 无。

### 11.6 SSE/轮询策略
- 无。

### 11.7 缓存与重试策略
- 前端无需感知底层表结构和 Redis Key，但要遵守分页与幂等参数契约。

### 11.8 联调注意事项
- 若出现“数据延迟可见”，优先按最终一致性窗口处理，不直接判定后端异常。

---

## 14. 模块13：整体一致性保障（前端感知面）

### 12.1 调用入口
- 无前端直连一致性基础设施 API。

### 12.2 请求参数
- 无。

### 12.3 响应结构
- 无。

### 12.4 错误码处理
- 通过业务接口间接感知：短暂不一致、状态延迟更新。

### 12.5 页面状态机
- 对依赖异步链路的场景（关注计数、订单状态）应支持“处理中”态。

### 12.6 SSE/轮询策略
- 关注计数/订单状态可轮询校正。

### 12.7 缓存与重试策略
- 列表与计数展示采用“先展示再校正”策略。

### 12.8 联调注意事项
- 遇到短暂不一致时，优先看 `requestId` 和重试结果，不要立即触发错误弹窗。

---

## 15. 模块14：可扩展性与未来规划（治理面）

### 13.1 调用入口
- `GET /ops/metrics/overview`

### 13.2 请求参数
- 无。

### 13.3 响应结构
- `MetricsOverview(availability,feedP95Ms,lbsP95Ms,ragFirstTokenMs,tradeAcceptP95Ms,observedAt)`

### 13.4 错误码处理
- `401`：无运维权限。

### 13.5 页面状态机
- 指标总览页：`加载 -> 展示 -> 定时刷新`。

### 13.6 SSE/轮询策略
- 当前采用轮询（如 10~30s）。

### 13.7 缓存与重试策略
- 指标可短缓存，失败后指数退避重试。

### 13.8 联调注意事项
- 本接口成熟度规划中，前端需功能开关和占位 UI。

---

## 14. 前端接口清单索引（按模块）

- 模块1：6 个操作
- 模块2：3 个操作
- 模块3：10 个操作
- 模块4：1 个操作
- 模块5：9 个操作
- 模块6：1 个操作
- 模块7：4 个操作
- 模块8：1 个操作（运维）
- 模块9：1 个操作（运维）
- 模块10：4 个操作（评论模块）
- 模块11：8 个操作（Profile 模块）
- 模块12：0（无直连）
- 模块13：0（无直连）
- 模块14：1 个操作（运维）

总计：50 个操作，已全量覆盖。
