package com.yigalaxy.yiguixingtu.auth.cache;

import com.yigalaxy.yiguixingtu.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户认证信息缓存。
 *
 * 【它解决什么问题？】
 * JwtAuthenticationFilter 每个请求都要查一次数据库确认"用户还在、没被禁用"。
 * 把这份认证信息放进 Redis 后，绝大部分请求就不用碰数据库了。
 *
 * 【为什么缓存这些字段？】
 * 缓存的是"认证需要的全部信息"：id / username / role / status。
 * 这样过滤器命中缓存时，不需要再查库就能构造出登录用户。
 * 注意【不缓存 password】，敏感数据不进缓存。
 *
 * 【一致性策略】
 * 不做定时刷新，而是"写时清除"：任何会改变这些字段的操作
 * （禁用/启用、删除、改角色）都主动删掉这个 key。
 * TTL 只是兜底 —— 万一某次清除漏了，最多脏 30 分钟。
 */
@Slf4j
@Component
public class UserAuthCache {

    /** Redis key 前缀，配上 userId 组成完整 key，如 auth:user:1 */
    private static final String KEY_PREFIX = "auth:user:";

    /** 缓存有效期：30 分钟（主动清除是主力，TTL 只是最后的保险） */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public UserAuthCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /**
     * 从缓存读取用户认证信息。
     * @return 命中返回 User；未命中返回 null（调用方应回落到查数据库）
     */
    public User get(Long userId) {
        if (userId == null) return null;

        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key(userId));
            if (entries == null || entries.isEmpty()) {
                return null;      // 缓存未命中
            }

            User user = new User();
            user.setId(Long.valueOf(String.valueOf(entries.get("id"))));
            user.setUsername((String) entries.getOrDefault("username", ""));
            user.setNickname((String) entries.getOrDefault("nickname", ""));
            user.setRole((String) entries.getOrDefault("role", ""));
            user.setStatus(Integer.valueOf(String.valueOf(entries.getOrDefault("status", "0"))));
            return user;

        } catch (Exception e) {
            // 【重要】缓存出问题绝不能影响主流程。
            // 读失败就当作"未命中"，让调用方回落到数据库，业务照常运行。
            log.warn("读取用户缓存失败, userId={}, 已回落到数据库: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存
     */
    public void put(User user) {
        if (user == null || user.getId() == null) return;

        try {
            Map<String, String> map = new HashMap<>();
            map.put("id", String.valueOf(user.getId()));
            map.put("username", user.getUsername() == null ? "" : user.getUsername());
            map.put("nickname", user.getNickname() == null ? "" : user.getNickname());
            map.put("role", user.getRole() == null ? "" : user.getRole());
            map.put("status", String.valueOf(user.getStatus() == null ? 0 : user.getStatus()));

            String k = key(user.getId());
            redis.opsForHash().putAll(k, map);
            redis.expire(k, TTL);

        } catch (Exception e) {
            log.warn("写入用户缓存失败, userId={}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * 清除缓存。
     * 在【禁用/启用、删除、改角色】之后必须调用，否则被禁用的人还能靠旧缓存混进去。
     */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
            log.info("已清除用户缓存: userId={}", userId);
        } catch (Exception e) {
            log.warn("清除用户缓存失败, userId={}: {}", userId, e.getMessage());
        }
    }
}
