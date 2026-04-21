package com.zhiguang.be.social.service.impl;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.UserSocialCounterData;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户维社交计数服务实现。
 * 负责维护用户维度的关注、粉丝、发帖、获赞和获收藏计数，并提供按需自检与重建能力。
 */
@Service
public class UserSocialCounterServiceImpl implements UserSocialCounterService {

    private static final int FIELD_SIZE = 4;
    private static final int FIELD_COUNT = 5;
    private static final int OFFSET_FOLLOWINGS = 0;
    private static final int OFFSET_FOLLOWERS = 4;
    private static final int OFFSET_POSTS = 8;
    private static final int OFFSET_LIKED_POSTS = 12;
    private static final int OFFSET_FAVED_POSTS = 16;
    private static final Duration COUNTER_CHECK_INTERVAL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final SocialMapper socialMapper;
    private final DefaultRedisScript<Long> incrementFieldScript;

    /**
     * 构造用户社交计数服务实现。
     *
     * @param stringRedisTemplate Redis 字符串模板
     * @param socialMapper 社交模块统一数据访问接口
     */
    public UserSocialCounterServiceImpl(StringRedisTemplate stringRedisTemplate, SocialMapper socialMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.socialMapper = socialMapper;
        this.incrementFieldScript = new DefaultRedisScript<Long>();
        this.incrementFieldScript.setResultType(Long.class);
        this.incrementFieldScript.setScriptText(
                "local cntKey = KEYS[1]\n"
                        + "local schemaLen = tonumber(ARGV[1])\n"
                        + "local fieldSize = tonumber(ARGV[2])\n"
                        + "local idx = tonumber(ARGV[3])\n"
                        + "local delta = tonumber(ARGV[4])\n"
                        + "local function read32be(s, off)\n"
                        + "  local b = {string.byte(s, off + 1, off + 4)}\n"
                        + "  local n = 0\n"
                        + "  for i = 1, 4 do n = n * 256 + b[i] end\n"
                        + "  return n\n"
                        + "end\n"
                        + "local function write32be(n)\n"
                        + "  local t = {}\n"
                        + "  for i = 4, 1, -1 do t[i] = n % 256; n = math.floor(n / 256) end\n"
                        + "  return string.char(unpack(t))\n"
                        + "end\n"
                        + "local cnt = redis.call('GET', cntKey)\n"
                        + "if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end\n"
                        + "local off = (idx - 1) * fieldSize\n"
                        + "local v = read32be(cnt, off) + delta\n"
                        + "if v < 0 then v = 0 end\n"
                        + "local seg = write32be(v)\n"
                        + "cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)\n"
                        + "redis.call('SET', cntKey, cnt)\n"
                        + "return 1\n"
        );
    }

    /**
     * 增量更新用户关注数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    @Override
    public void incrementFollowings(long userId, int delta) {
        incrementField(userId, 1, delta);
    }

    /**
     * 增量更新用户粉丝数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    @Override
    public void incrementFollowers(long userId, int delta) {
        incrementField(userId, 2, delta);
    }

    /**
     * 增量更新用户已发布内容数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    @Override
    public void incrementPosts(long userId, int delta) {
        incrementField(userId, 3, delta);
    }

    /**
     * 增量更新作者累计获赞数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    @Override
    public void incrementLikesReceived(long userId, int delta) {
        incrementField(userId, 4, delta);
    }

    /**
     * 增量更新作者累计获收藏数。
     *
     * @param userId 用户 ID
     * @param delta 变化量
     */
    @Override
    public void incrementFavoritesReceived(long userId, int delta) {
        incrementField(userId, 5, delta);
    }

    /**
     * 查询用户维社交计数。
     * 读取时会按需做结构校验和轻量一致性校验，不一致时自动重建。
     *
     * @param userId 用户 ID
     * @return 用户维社交计数
     */
    @Override
    public UserSocialCounterData getUserSocialCounter(long userId) {
        ensureUserExists(userId);

        byte[] raw = readRawCounter(userId);
        if (!isValidRawCounter(raw)) {
            return rebuildAllCounters(userId);
        }

        if (shouldCheckCounterConsistency(userId) && !isUserCounterConsistent(userId, raw)) {
            return rebuildAllCounters(userId);
        }

        return toUserSocialCounterData(userId, raw);
    }

    /**
     * 基于数据库事实层和实体计数快照重建用户维社交计数。
     *
     * @param userId 用户 ID
     * @return 重建后的用户维社交计数
     */
    @Override
    public UserSocialCounterData rebuildAllCounters(long userId) {
        ensureUserExists(userId);

        long followings = socialMapper.countFollowingActive(userId);
        long followers = socialMapper.countFollowerActive(userId);
        long posts = socialMapper.countPublishedPostsByCreatorId(userId);
        List<Long> publishedPostIds = socialMapper.listPublishedPostIdsByCreatorId(userId);

        long likedPosts = 0L;
        long favedPosts = 0L;
        if (publishedPostIds != null && !publishedPostIds.isEmpty()) {
            Map<Long, long[]> interactionStats = readPostInteractionCounters(publishedPostIds);
            for (Long postId : publishedPostIds) {
                long[] stats = interactionStats.get(postId);
                if (stats != null) {
                    likedPosts += stats[0];
                    favedPosts += stats[1];
                }
            }
        }

        byte[] raw = new byte[FIELD_SIZE * FIELD_COUNT];
        writeInt32BE(raw, OFFSET_FOLLOWINGS, followings);
        writeInt32BE(raw, OFFSET_FOLLOWERS, followers);
        writeInt32BE(raw, OFFSET_POSTS, posts);
        writeInt32BE(raw, OFFSET_LIKED_POSTS, likedPosts);
        writeInt32BE(raw, OFFSET_FAVED_POSTS, favedPosts);
        writeRawCounter(userId, raw);
        return toUserSocialCounterData(userId, raw);
    }

    /**
     * 对用户维计数指定槽位做原子增量更新。
     *
     * @param userId 用户 ID
     * @param fieldIndex 槽位下标，从 1 开始
     * @param delta 变化量
     */
    private void incrementField(long userId, int fieldIndex, int delta) {
        if (userId <= 0L || delta == 0) {
            return;
        }
        stringRedisTemplate.execute(
                incrementFieldScript,
                Collections.singletonList(SocialRedisKeys.userCounterKey(userId)),
                String.valueOf(FIELD_COUNT),
                String.valueOf(FIELD_SIZE),
                String.valueOf(fieldIndex),
                String.valueOf(delta)
        );
    }

    /**
     * 判断是否需要做本次抽样一致性校验。
     * 参考 zhiguang 的做法，用短期锁控制检查频率。
     *
     * @param userId 用户 ID
     * @return 需要检查返回 true，否则返回 false
     */
    private boolean shouldCheckCounterConsistency(long userId) {
        Boolean doCheck = stringRedisTemplate.opsForValue().setIfAbsent(
                SocialRedisKeys.userCounterCheckKey(userId),
                "1",
                COUNTER_CHECK_INTERVAL
        );
        return Boolean.TRUE.equals(doCheck);
    }

    /**
     * 校验用户关注数和粉丝数是否与事实层一致。
     * 这里仅做轻量校验，不每次都回源比对全部槽位。
     *
     * @param userId 用户 ID
     * @param raw 当前 Redis 中的原始计数字节数组
     * @return 一致返回 true，否则返回 false
     */
    private boolean isUserCounterConsistent(long userId, byte[] raw) {
        long cachedFollowings = readInt32BE(raw, OFFSET_FOLLOWINGS);
        long cachedFollowers = readInt32BE(raw, OFFSET_FOLLOWERS);
        long cachedPosts = readInt32BE(raw, OFFSET_POSTS);
        long dbFollowings = socialMapper.countFollowingActive(userId);
        long dbFollowers = socialMapper.countFollowerActive(userId);
        long dbPosts = socialMapper.countPublishedPostsByCreatorId(userId);
        return cachedFollowings == dbFollowings
                && cachedFollowers == dbFollowers
                && cachedPosts == dbPosts;
    }

    /**
     * 判断原始计数结构是否完整有效。
     *
     * @param raw 原始计数字节数组
     * @return 结构有效返回 true，否则返回 false
     */
    private boolean isValidRawCounter(byte[] raw) {
        return raw != null && raw.length == FIELD_SIZE * FIELD_COUNT;
    }

    /**
     * 将原始 SDS 计数转换为对外返回对象。
     *
     * @param userId 用户 ID
     * @param raw 原始计数字节数组
     * @return 用户维社交计数对象
     */
    private UserSocialCounterData toUserSocialCounterData(long userId, byte[] raw) {
        return new UserSocialCounterData(
                String.valueOf(userId),
                readInt32BE(raw, OFFSET_FOLLOWINGS),
                readInt32BE(raw, OFFSET_FOLLOWERS),
                readInt32BE(raw, OFFSET_POSTS),
                readInt32BE(raw, OFFSET_LIKED_POSTS),
                readInt32BE(raw, OFFSET_FAVED_POSTS)
        );
    }

    /**
     * 读取用户维原始 SDS 计数字节数组。
     *
     * @param userId 用户 ID
     * @return 原始字节数组
     */
    private byte[] readRawCounter(long userId) {
        String key = SocialRedisKeys.userCounterKey(userId);
        return stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 将重建后的用户维计数写回 Redis。
     *
     * @param userId 用户 ID
     * @param raw 原始字节数组
     */
    private void writeRawCounter(long userId, byte[] raw) {
        String key = SocialRedisKeys.userCounterKey(userId);
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), raw);
            return null;
        });
    }

    /**
     * 批量读取作者所有已发布帖子对应的点赞和收藏统计。
     * 优先读取实体计数 SDS，缺失时回退到数据库事实层聚合。
     *
     * @param postIds 帖子 ID 列表
     * @return 以帖子 ID 为键的统计结果，数组下标 0 表示点赞数，下标 1 表示收藏数
     */
    private Map<Long, long[]> readPostInteractionCounters(List<Long> postIds) {
        Map<Long, long[]> result = new LinkedHashMap<Long, long[]>();
        List<String> keys = new ArrayList<String>(postIds.size());
        for (Long postId : postIds) {
            keys.add(SocialRedisKeys.entityCounterKey("post", postId));
        }

        List<Object> pipelineResult = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        for (int i = 0; i < postIds.size(); i++) {
            Long postId = postIds.get(i);
            Object value = i < pipelineResult.size() ? pipelineResult.get(i) : null;
            byte[] raw = value instanceof byte[] ? (byte[]) value : null;
            if (raw != null && raw.length == FIELD_SIZE * 2) {
                result.put(postId, new long[]{
                        readInt32BE(raw, 0),
                        readInt32BE(raw, 4)
                });
                continue;
            }

            Long likeCount = socialMapper.aggregateActiveInteractionCount("post", postId, "like");
            Long favoriteCount = socialMapper.aggregateActiveInteractionCount("post", postId, "favorite");
            result.put(postId, new long[]{
                    likeCount == null ? 0L : likeCount.longValue(),
                    favoriteCount == null ? 0L : favoriteCount.longValue()
            });
        }
        return result;
    }

    /**
     * 校验用户是否存在。
     *
     * @param userId 用户 ID
     */
    private void ensureUserExists(long userId) {
        if (userId <= 0L || socialMapper.existsUser(userId) <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "目标用户不存在");
        }
    }

    /**
     * 以大端序读取 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 起始偏移量
     * @return 解析后的数值
     */
    private long readInt32BE(byte[] buffer, int offset) {
        if (buffer == null || buffer.length < offset + FIELD_SIZE) {
            return 0L;
        }
        long value = 0L;
        for (int i = 0; i < FIELD_SIZE; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xFFL);
        }
        return value;
    }

    /**
     * 以大端序写入 4 字节无符号整数。
     *
     * @param buffer 原始字节数组
     * @param offset 起始偏移量
     * @param value 待写入数值
     */
    private void writeInt32BE(byte[] buffer, int offset, long value) {
        long safeValue = Math.max(0L, Math.min(value, 0xFFFF_FFFFL));
        buffer[offset] = (byte) ((safeValue >>> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((safeValue >>> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((safeValue >>> 8) & 0xFF);
        buffer[offset + 3] = (byte) (safeValue & 0xFF);
    }
}
