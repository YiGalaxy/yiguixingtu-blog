package com.yigalaxy.yiguixingtu.auth.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * =====================================================================
 * 已登出 token 的黑名单 —— 让"退出登录"真的生效
 *
 * ============ 它解决什么问题 ============
 *
 * JWT 是【无状态】的：服务端签出去就不管了，token 在过期前一直有效。
 * 好处是不用查库、天然支持多实例；代价是【撤销不掉】。
 * 于是"退出登录"就变成了一个假动作：
 *   前端把本地 token 删了 —— 但要是这个 token 已经被人截获（或者浏览器
 *   历史里还留着），它依然能用到自然过期（本项目配的是 24 小时）。
 *
 * 解决办法是"在无状态里加一点状态"：登出时把 token 的唯一编号（jti）
 * 记进 Redis，之后凡是带着这个 jti 的请求一律拒绝。
 *
 * ============ 为什么是"按 jti 拉黑"而不是"按用户失效" ============
 *   另一种做法是给每个用户记一个版本号，登出就 +1 —— 那会把该用户的
 *   【所有设备】一起踢下线。对个人博客来说，用户可能手机和电脑都登着，
 *   在手机上点退出却把电脑也踢了，是明显的越权行为。
 *   jti 精确到"这一个 token"，语义才对。
 *   （对应地，管理员"禁用/删除/改角色"要踢掉该用户全部 token，
 *    那是另一套机制，见 UserAuthCache —— 两者解决的不是同一件事。）
 *
 * ============ 为什么必须设过期时间 ============
 *   黑名单里的记录只在"这个 token 本来会过期的时刻"之前有用。
 *   过了那个时刻 token 自己就失效了，记录留着纯属占内存。
 *   所以 TTL 取【token 的剩余有效期】—— 这样黑名单不会无限增长，
 *   不需要任何定时清理任务（定时任务才是需要维护的东西）。
 *
 * ============ 和 UserAuthCache 的写法为什么这么像 ============
 *   都是"用 Redis 给无状态的 JWT 补一点状态"，都遵循同一个原则：
 *   【缓存出问题绝不能影响主流程】—— 读失败当作"没命中"，写失败只打日志。
 *   这里唯一的例外取向是：读黑名单失败时【宁可放行】而不是拒绝，
 *   因为它和 UserAuthCache 一样不应该让 Redis 抖动变成全站不可用。
 *   （真正兜底的是 token 的过期时间，攻击面被限制在"Redis 挂了的那段时间"。）
 * =====================================================================
 */
@Slf4j
@Component
public class TokenBlacklist {

    /** Redis key 前缀，配上 jti 组成完整 key，如 auth:blacklist:{uuid} */
    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String jti) {
        return KEY_PREFIX + jti;
    }

    /**
     * 把一个 token 拉黑。
     *
     * @param jti            token 的唯一编号（{@code JwtUtil#getJti}）
     * @param remainingTtl   这个 token 还剩多久过期；传 0 或负数表示已经过期，
     *                       那就没必要记了
     */
    public void add(String jti, Duration remainingTtl) {
        if (!StringUtils.hasText(jti)) {
            // 没有 jti 说明是本次改动之前签发的老 token（或者不是我们签的）。
            // 这种情况没法精确拉黑，只能让它自然过期 —— 打个日志让人知道。
            log.warn("token 没有 jti，无法加入黑名单（可能是本功能上线前签发的旧 token）");
            return;
        }
        if (remainingTtl == null || remainingTtl.isZero() || remainingTtl.isNegative()) {
            // 已经过期的 token 不需要拉黑：它自己就无效了
            return;
        }

        try {
            redis.opsForValue().set(key(jti), "1", remainingTtl);
            log.info("token 已加入黑名单, jti={}, 剩余有效期={}s", jti, remainingTtl.toSeconds());
        } catch (Exception e) {
            // 【为什么这里只打日志不抛异常】登出接口不应该因为 Redis 抖动而失败。
            // 后果是"这次登出没生效"，用户看到的是"退出成功"但 token 仍然能用 ——
            // 这个后果不理想，但比"点了退出却报错、用户以为没退成功"要好一点，
            // 而且日志里留下了明确记录，可以查。
            log.error("写入 token 黑名单失败, jti={}，该 token 在过期前仍然有效", jti, e);
        }
    }

    /**
     * 判断一个 token 是否已被拉黑。
     *
     * @param jti token 的唯一编号
     * @return true 表示已登出/已作废，应当拒绝这次请求
     */
    public boolean contains(String jti) {
        if (!StringUtils.hasText(jti)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(jti)));
        } catch (Exception e) {
            // 读失败当作"不在黑名单里"（宁可放行也不让整站不可用）。
            // 见类注释里关于这条取向的说明
            log.warn("读取 token 黑名单失败, jti={}, 本次按未拉黑处理: {}", jti, e.getMessage());
            return false;
        }
    }
}
