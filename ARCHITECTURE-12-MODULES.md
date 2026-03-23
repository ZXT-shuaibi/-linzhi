# 12 模块分层架构约束

## 1. 目录规范

每个模块统一采用以下结构（按需增减）：

- `controller`：HTTP 接口层
- `service`：业务编排层
- `mapper`：数据访问层
- `model`：DTO/Entity/VO
- `event`（可选）：事件定义与消费处理

当前 12 模块：
- `auth`
- `discover`
- `content`
- `rag`
- `social`
- `feed`
- `trade`
- `threadpool`
- `guard`
- `data`
- `consistency`
- `platform`

## 2. 调用约束

1. 模块内调用顺序固定：`controller -> service -> mapper`
2. `controller` 禁止直接调用 `mapper`
3. 跨模块调用只允许：
- 调用对方 `service` 的公开接口
- 或通过事件总线（Outbox/Kafka）
4. 禁止跨模块直接调用对方 `mapper`
5. 公共工具统一放 `common`，不得放业务逻辑

## 3. 认证模块示例

认证模块已经按此结构落地：
- `auth/controller/AuthController`
- `auth/service/AuthService`
- `auth/service/impl/AuthServiceImpl`
- `auth/mapper/AuthUserMapper`
- `auth/mapper/InMemoryAuthUserMapper`
- `auth/model/*`

## 4. 后续落地建议

1. 优先补齐模块2（discover）和模块3（content）的 controller/service/mapper。
2. 模块10（data）和模块11（consistency）允许“无对外 controller”，但保留 service/mapper 结构。
3. 每个模块新增代码必须落在本模块目录，不允许写回全局平铺目录。
