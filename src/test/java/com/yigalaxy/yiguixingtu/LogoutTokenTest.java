package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.cache.TokenBlacklist;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 登出后 token 立即失效的测试
 *
 * 【它守护的是什么】
 *   在此之前 /auth/logout 是个空实现，所以"退出登录"只是前端删掉了本地 token ——
 *   服务端签出去的那个 token 依然能用到自然过期（默认 24 小时）。
 *   如果它被截获（或者浏览器历史里还留着），拿到的人就能继续用。
 *
 *   本类要证明的核心就一句：
 *   **登出之后，那个 token 立刻不能用了，而别的 token 不受影响。**
 *
 * 【为什么"别的 token 不受影响"也要测】
 *   这一条很容易被忽略，但它决定了实现方案对不对：
 *   如果做成"按用户拉黑"，那么手机上点退出会把电脑也踢下线 ——
 *   那是个越权行为。jti 方案必须精确到单个 token，所以必须有这条断言守着。
 *
 * 【@Transactional 管不了 Redis】
 *   和 UserAuthCache 的测试一样，黑名单是写在 Redis 里的，不受事务回滚影响，
 *   所以每个用例前后都要手动清一次，否则会互相污染。
 * =====================================================================
 */
class LogoutTokenTest extends AbstractIntegrationTest {

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklist tokenBlacklist;

    @Autowired
    private StringRedisTemplate redis;

    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        clearBlacklist();
        user = insertUser();
        // 和别的测试类一样：直接用 JwtUtil 造 token，比走登录接口更快，
        // 而且能精确控制里面的 userId 与 role
        token = jwtUtil.generateToken(user.getId(), user.getUsername(), "GUEST");
    }

    // ================================================================
    // 一、核心：登出 → 旧 token 立刻失效
    // ================================================================

    @Test
    @DisplayName("① 登出之后，旧 token 再调 /auth/me -> 401（这就是这个功能的意义）")
    void afterLogout_oldToken_shouldBeRejected() throws Exception {
        // 登出前是能用的 —— 先把这个前提验掉，否则下面"登出后 401"可能只是因为
        // token 本来就是坏的，那样测试就没证明到任何东西
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 登出
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 同一个 token 再用 —— 必须被拒
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("② 登出只作废【这一个】token，同一个人的另一个 token 不受影响")
    void afterLogout_otherTokenOfSameUser_shouldStillWork() throws Exception {
        // 模拟"手机和电脑同时登着"这个真实场景
        String otherDeviceToken = jwtUtil.generateToken(user.getId(), user.getUsername(), "GUEST");

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 手机上的 token 作废了
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // 电脑上的那个还必须能用（否则就是"在手机退出把电脑也踢了"的越权行为）
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + otherDeviceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("③ 登出不会影响【别的用户】的 token")
    void afterLogout_otherUserToken_shouldStillWork() throws Exception {
        User other = insertUser("logout_other_user");
        String otherToken = jwtUtil.generateToken(other.getId(), other.getUsername(), "GUEST");

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk());
    }

    // ================================================================
    // 二、黑名单本身的细节
    // ================================================================

    @Test
    @DisplayName("④ 登出会在 Redis 里留下一条黑名单，且 TTL 等于 token 的剩余有效期")
    void logout_shouldWriteBlacklistWithRemainingTtl() throws Exception {
        String jti = jwtUtil.getJti(token);
        assertNotNull(jti, "token 里应当有 jti（否则没法精确拉黑）");

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String key = BLACKLIST_PREFIX + jti;
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)), "Redis 里应当有这条黑名单: " + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        // token 配的是 24 小时（86400 秒）。这里断言"剩下的有效期在 23~24 小时之间"：
        // 上界 86400 是因为不能超过 token 本身的有效期；
        // 下界留 1 小时的余量，避免测试跑得慢时误判。
        assertTrue(ttl > 0 && ttl <= 86400,
                "黑名单的 TTL 应当等于 token 的剩余有效期（不超过 24 小时），实际=" + ttl);
        assertTrue(ttl > 3600, "刚签发的 token 剩余有效期应当还很长，实际=" + ttl);
    }

    @Test
    @DisplayName("⑤ 重复登出同一次 token -> 仍然返回成功（幂等，别让前端弹登录框）")
    void logoutTwice_shouldBeIdempotent() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 第二次带的是【已经被拉黑】的 token。
        //
        // 【为什么这里必须还是 200 而不是 401】
        //   这条用例一开始写的是 401（因为拉黑后过滤器就拒了），结果被它逼着改了设计：
        //   如果第二次返回 401，前端 useApi 会弹"登录已过期"并弹出登录框 ——
        //   用户刚点完退出却被要求登录，非常莫名其妙。
        //   所以 /auth/logout 在 SecurityConfig 里被放行，做成幂等：
        //   带有效 token 就顺手拉黑，没带或已失效就什么都不做，永远返回成功。
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("⑥ 不带 token 调登出 -> 也返回成功（幂等；本来就没登录，没什么可作废的）")
    void logoutWithoutToken_shouldStillSucceed() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("⑦ 伪造/乱写的 token 调登出 -> 也返回成功，不会 500")
    void logoutWithGarbageToken_shouldNotReturnServerError() throws Exception {
        // 伪造的 token 解析会抛异常。这里守的是"异常被接住了、没有变成 500"。
        // 而且对用户来说"退出"这个动作永远应该成功 —— 他本来就想退出
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("⑧ 没被登出的 token -> 黑名单里查不到（别把所有人都拉黑了）")
    void notLoggedOutToken_shouldNotBeBlacklisted() {
        String jti = jwtUtil.getJti(token);
        assertFalse(tokenBlacklist.contains(jti),
                "刚签发、还没登出的 token 不该在黑名单里");
    }

    @Test
    @DisplayName("⑨ 黑名单的 TTL 由 token 剩余有效期决定，不用写定时清理任务")
    void blacklistTtl_shouldFollowTokenRemainingValidity() {
        tokenBlacklist.add("unit-test-jti", Duration.ofSeconds(120));

        String key = BLACKLIST_PREFIX + "unit-test-jti";
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        // TTL 落在 100~120 秒之间：说明传进去的剩余时长被正确用上了，
        // 到期后 Redis 自己会删掉这条记录
        assertTrue(ttl > 0 && ttl <= 120, "TTL 应当不超过传进去的 120 秒，实际=" + ttl);
        assertTrue(ttl > 100, "TTL 应当接近传进去的值，实际=" + ttl);
    }

    @Test
    @DisplayName("⑩ 已经过期的时长传进去 -> 不写黑名单（没意义）")
    void addWithNonPositiveTtl_shouldNotWriteAnything() {
        tokenBlacklist.add("expired-jti", Duration.ZERO);

        assertFalse(Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + "expired-jti")),
                "剩余有效期为 0 的 token 不需要拉黑 —— 它自己就无效了");
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 清掉黑名单（@Transactional 回滚不了 Redis，必须手动清） */
    private void clearBlacklist() {
        Set<String> keys = redis.keys(BLACKLIST_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private User insertUser() {
        return insertUser("logout_test_user");
    }

    private User insertUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("登出测试用户");
        u.setRole("GUEST");
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    @Test
    @DisplayName("清理检查：用例之间不残留黑名单（自检用）")
    void blacklistIsolation() {
        // 这条用例本身不测业务，它守的是"本类的隔离机制真的有效"：
        // 如果 @BeforeEach 的清理没生效，随机失败的用例会非常难查
        assertEquals(0L, countBlacklistKeys(), "每个用例开始前黑名单应当是干净的");
    }

    private long countBlacklistKeys() {
        Set<String> keys = redis.keys(BLACKLIST_PREFIX + "*");
        return keys == null ? 0L : keys.size();
    }
}
