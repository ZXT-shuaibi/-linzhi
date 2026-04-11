-- users：统一用户主表。
-- 认证、内容、关系等模块都基于这张表关联用户。
CREATE TABLE IF NOT EXISTS users (
    -- 用户唯一 ID，业务层使用雪花算法生成。
    id BIGINT PRIMARY KEY,
    -- 手机号，当前注册和找回密码主标识。
    phone VARCHAR(20) NOT NULL,
    -- 登录账号，支持和手机号分离。
    account VARCHAR(32) NOT NULL,
    -- 预留邮箱字段，后续可扩展邮箱登录。
    email VARCHAR(128),
    -- 密码哈希值，不存明文密码。
    password_hash VARCHAR(255) NOT NULL,
    -- 用户昵称。
    nickname VARCHAR(64) NOT NULL,
    -- 头像 URL。
    avatar VARCHAR(512),
    -- 个人简介。
    bio VARCHAR(512),
    -- 性别。
    gender VARCHAR(16),
    -- 生日。
    birthday DATE,
    -- 学校或组织信息。
    school VARCHAR(128),
    -- 用户标签 JSON，便于后续做画像。
    tags_json JSON,
    -- 账户状态，如 active/disabled/banned。
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    -- 创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 最后更新时间。
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 保证手机号唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone ON users(phone);
-- 保证登录账号唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_account ON users(account);
-- 保证邮箱唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email ON users(email);
-- 便于按状态和时间检索用户。
CREATE INDEX IF NOT EXISTS ix_users_status_created_at ON users(status, created_at);

-- auth_user：旧版认证用户表。
-- 仅用于兼容历史数据，启动时会迁移到 users。
CREATE TABLE IF NOT EXISTS auth_user (
    -- 旧版用户 ID，字符串格式存储。
    user_id VARCHAR(32) PRIMARY KEY,
    -- 旧版手机号。
    phone VARCHAR(20) NOT NULL UNIQUE,
    -- 旧版登录账号，老数据可能为空。
    account VARCHAR(32),
    -- 旧版昵称。
    nickname VARCHAR(64) NOT NULL,
    -- 旧版密码哈希。
    password_hash VARCHAR(255) NOT NULL,
    -- 旧版创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 旧版更新时间。
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 补齐旧表缺失的 account 字段。
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS account VARCHAR(32);
-- 老数据没有账号时，默认用手机号回填。
UPDATE auth_user SET account = phone WHERE account IS NULL;
-- 兼容表账号唯一索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_user_account ON auth_user(account);

-- 将历史 auth_user 数据迁移到新的 users 表。
-- 兼容两类旧 user_id：
-- 1. 能安全转成数值的旧 ID，直接沿用；
-- 2. 历史 UUID / 非数字 ID，映射到保留的高位数值区间，避免启动时 CAST 失败。
INSERT INTO users (id, phone, account, password_hash, nickname, created_at, updated_at)
SELECT
    -- 迁移后的数值 ID。
    migrated.migrated_id,
    -- 迁移手机号。
    migrated.phone,
    -- 迁移登录账号。
    migrated.account,
    -- 迁移密码哈希。
    migrated.password_hash,
    -- 迁移昵称。
    migrated.nickname,
    -- 保留原始创建时间。
    migrated.created_at,
    -- 保留原始更新时间。
    migrated.updated_at
FROM (
    SELECT
        CASE
            WHEN TRY_CAST(legacy.user_id AS BIGINT) IS NOT NULL THEN TRY_CAST(legacy.user_id AS BIGINT)
            ELSE CAST(8000000000000000000 AS BIGINT) + ROW_NUMBER() OVER (ORDER BY legacy.user_id)
        END AS migrated_id,
        legacy.phone,
        legacy.account,
        legacy.password_hash,
        legacy.nickname,
        legacy.created_at,
        legacy.updated_at
    FROM auth_user legacy
) migrated
WHERE NOT EXISTS (
    SELECT 1
    FROM users current_users
    WHERE current_users.id = migrated.migrated_id
       OR current_users.phone = migrated.phone
       OR current_users.account = migrated.account
);

-- login_logs：登录日志表。
-- 用于审计、排查问题和风控回溯。
CREATE TABLE IF NOT EXISTS login_logs (
    -- 日志唯一 ID，建议也使用雪花算法生成。
    id BIGINT PRIMARY KEY,
    -- 命中用户时记录用户 ID，未命中可为空。
    user_id BIGINT,
    -- 登录标识，可能是手机号或账号。
    identifier VARCHAR(128) NOT NULL,
    -- 登录渠道，如 app/h5/web。
    channel VARCHAR(32),
    -- 登录 IP，兼容 IPv4/IPv6。
    ip VARCHAR(45),
    -- 客户端 UA 信息。
    user_agent VARCHAR(512),
    -- 登录结果，如 success/failed/blocked。
    status VARCHAR(16) NOT NULL,
    -- 补充描述，如失败原因。
    message VARCHAR(255),
    -- 日志创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 按用户回查登录历史。
CREATE INDEX IF NOT EXISTS ix_login_logs_user_created_at ON login_logs(user_id, created_at);
-- 按标识排查登录问题。
CREATE INDEX IF NOT EXISTS ix_login_logs_identifier_created_at ON login_logs(identifier, created_at);

-- know_posts：知文主表。
-- 存储图文内容的核心信息。
CREATE TABLE IF NOT EXISTS know_posts (
    -- 内容唯一 ID，业务层使用雪花算法生成。
    id BIGINT PRIMARY KEY,
    -- 主分类 ID。
    tag_id BIGINT,
    -- 标签数组 JSON。
    tags JSON,
    -- 标题。
    title VARCHAR(256),
    -- 摘要描述。
    description VARCHAR(128),
    -- 正文或正文文件访问地址。
    content_url CLOB,
    -- 对象存储中的 key。
    content_object_key VARCHAR(512),
    -- 对象存储返回的 ETag。
    content_etag VARCHAR(128),
    -- 正文字节大小。
    content_size BIGINT,
    -- 正文内容 SHA-256。
    content_sha256 CHAR(64),
    -- 作者用户 ID。
    creator_id BIGINT NOT NULL,
    -- 是否置顶。
    is_top BOOLEAN NOT NULL DEFAULT FALSE,
    -- 内容类型，一期默认图文。
    type VARCHAR(32) NOT NULL DEFAULT 'image_text',
    -- 可见范围，如 public/followers/private。
    visible VARCHAR(32) NOT NULL DEFAULT 'public',
    -- 图片地址数组 JSON。
    img_urls JSON,
    -- 视频地址，当前预留。
    video_url CLOB,
    -- 内容状态，如 draft/published/rejected。
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    -- 创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 更新时间。
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 发布时间。
    publish_time TIMESTAMP,
    -- 关联作者。
    CONSTRAINT fk_know_posts_creator FOREIGN KEY (creator_id) REFERENCES users(id)
);

-- 查用户发帖列表。
CREATE INDEX IF NOT EXISTS ix_know_posts_creator_created_at ON know_posts(creator_id, created_at);
-- 查某状态下的内容。
CREATE INDEX IF NOT EXISTS ix_know_posts_status_created_at ON know_posts(status, created_at);
-- 按标签筛内容。
CREATE INDEX IF NOT EXISTS ix_know_posts_tag_created_at ON know_posts(tag_id, created_at);
-- 首页查置顶内容。
CREATE INDEX IF NOT EXISTS ix_know_posts_top_created_at ON know_posts(is_top, created_at);
-- 查用户已发布内容。
CREATE INDEX IF NOT EXISTS ix_know_posts_creator_status_publish ON know_posts(creator_id, status, publish_time);

-- outbox：事件外发表。
-- 用于异步事件投递和失败补偿。
CREATE TABLE IF NOT EXISTS outbox (
    -- 事件唯一 ID。
    id BIGINT PRIMARY KEY,
    -- 聚合类型，如 post/follow/order。
    aggregate_type VARCHAR(64) NOT NULL,
    -- 聚合主键 ID。
    aggregate_id BIGINT,
    -- 事件类型。
    event_type VARCHAR(64) NOT NULL,
    -- 事件载荷 JSON。
    payload JSON NOT NULL,
    -- 投递状态，如 pending/published/failed。
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    -- 重试次数。
    retry_count INT NOT NULL DEFAULT 0,
    -- 最后一次投递失败原因。
    last_error VARCHAR(512),
    -- 事件创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 实际投递成功时间。
    published_at TIMESTAMP
);

-- 扫描待投递事件。
CREATE INDEX IF NOT EXISTS ix_outbox_status_created_at ON outbox(status, created_at);
-- 回查某个聚合的事件流。
CREATE INDEX IF NOT EXISTS ix_outbox_aggregate ON outbox(aggregate_type, aggregate_id);

-- following：关注关系正向表。
-- 表示“谁关注了谁”。
CREATE TABLE IF NOT EXISTS following (
    -- 关系唯一 ID。
    id BIGINT PRIMARY KEY,
    -- 发起关注的用户 ID。
    from_user_id BIGINT NOT NULL,
    -- 被关注的用户 ID。
    to_user_id BIGINT NOT NULL,
    -- 关系状态，1 有效，0 取消。
    rel_status TINYINT NOT NULL DEFAULT 1,
    -- 关注创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 关系更新时间。
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 防止重复关注。
CREATE UNIQUE INDEX IF NOT EXISTS uk_following_from_to ON following(from_user_id, to_user_id);
-- 查某人关注列表。
CREATE INDEX IF NOT EXISTS ix_following_from_created ON following(from_user_id, created_at, to_user_id, rel_status);
-- 查某人被哪些人关注。
CREATE INDEX IF NOT EXISTS ix_following_to ON following(to_user_id, from_user_id, rel_status);

-- follower：粉丝关系反向表。
-- 便于快速查粉丝列表。
CREATE TABLE IF NOT EXISTS follower (
    -- 关系唯一 ID。
    id BIGINT PRIMARY KEY,
    -- 被关注者 ID。
    to_user_id BIGINT NOT NULL,
    -- 关注者 ID。
    from_user_id BIGINT NOT NULL,
    -- 关系状态，1 有效，0 取消。
    rel_status TINYINT NOT NULL DEFAULT 1,
    -- 粉丝关系创建时间。
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 粉丝关系更新时间。
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 防止重复粉丝关系。
CREATE UNIQUE INDEX IF NOT EXISTS uk_follower_to_from ON follower(to_user_id, from_user_id);
-- 查某人的粉丝列表。
CREATE INDEX IF NOT EXISTS ix_follower_to_created ON follower(to_user_id, created_at, from_user_id, rel_status);
-- 查某人关注了谁。
CREATE INDEX IF NOT EXISTS ix_follower_from ON follower(from_user_id, to_user_id, rel_status);
