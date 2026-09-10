package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 冒烟测试：应用能不能起来、最底层那几条链路通不通
 *
 * 【这个类在容器化改造时改了什么，为什么】
 *   改造前它 4 个方法全部【没有任何断言】，只用 log.info 打印，
 *   而且 testLoadUserByUsername 明确写着"前提：user 表里有
 *   username='yigalaxy' 这条数据"——依赖本机库里手工造的管理员。
 *   MySQL 换成 Testcontainers 拉起的空库之后，这条前提必然不成立。
 *
 *   所以做了两件事：
 *     ① 数据自己造：需要用户就先插一个，不再依赖库里预先有什么
 *     ② 补上断言：只打日志不断言的"测试"永远不会失败，
 *        也就永远不会告诉你有东西坏了 —— 那不算测试
 *
 * 【它和别的测试类的分工】
 *   这里只验证最小烟测：上下文能加载、表真的建出来了、JWT 能签发又能解析、
 *   UserDetailsService 能查出人、BCrypt 能校验。
 *   业务规则（权限、草稿隔离、分页边界）都在各自的测试类里，不在这里重复。
 * =====================================================================
 */
@Slf4j
class YiguixingtuApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 上下文能加载，且数据库真的可读可写。
     *
     * 【为什么这条烟测比以前有价值】
     *   以前是 selectById(1L) 然后打印 —— 查不到（null）也算通过。
     *   现在改成"插一条再查回来"，等于顺带验证了三件事：
     *     · Flyway 把表建出来了（否则 insert 直接报表不存在）
     *     · MyBatis-Plus 映射正常（字段能对上、@TableLogic 生效）
     *     · 事务能正常回滚（基类的 @Transactional）
     */
    @Test
    void contextLoads() {
        User user = new User();
        user.setUsername("smoke_" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("123456"));
        user.setNickname("冒烟测试用户");
        user.setRole("GUEST");
        user.setStatus(1);

        userMapper.insert(user);
        assertNotNull(user.getId(), "插入后应能拿到自增主键，说明表结构与 id 策略都对得上");

        User loaded = userMapper.selectById(user.getId());
        assertNotNull(loaded, "刚插入的用户应该能查回来");
        assertEquals(user.getUsername(), loaded.getUsername());
        log.info("数据库读写正常, 测试用户 id={}", loaded.getId());
    }

    /** JWT 签发后能解析，且解析出来的值就是签进去的值。 */
    @Test
    void testGenerateAndParse() {
        long userId = 12345L;
        String token = jwtUtil.generateToken(userId, "smoke_user", "ADMIN");

        Claims claims = jwtUtil.parseToken(token);
        assertEquals("smoke_user", claims.getSubject(), "subject 应是用户名");
        assertEquals(userId, ((Number) claims.get("userId")).longValue(), "userId 应原样带出来");
        assertEquals("ADMIN", claims.get("role"), "role 应原样带出来");
        assertTrue(jwtUtil.isValid(token), "刚签发的 token 应当是有效的");
    }

    /**
     * UserDetailsService 能按用户名查出人。
     *
     * 【关键改动】用户名不再写死 'yigalaxy'，而是当场插一个自己的。
     * 这样测试在任何数据库环境下都能重复运行，也不怕哪天管理员改名。
     */
    @Test
    void testLoadUserByUsername() {
        User user = new User();
        String username = "smoke_load_" + System.nanoTime();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("123456"));
        user.setNickname("待加载用户");
        user.setRole("ADMIN");
        user.setStatus(1);
        userMapper.insert(user);

        UserDetails ud = userDetailsService.loadUserByUsername(username);
        assertEquals(username, ud.getUsername());
        assertTrue(ud.isEnabled(), "status=1 的用户应当是启用状态");
        assertTrue(ud.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())),
                "ADMIN 角色应当映射成 ROLE_ADMIN（hasRole('ADMIN') 靠的就是这个前缀）");
    }

    /**
     * BCrypt 加密是单向且可校验的。
     *
     * 【以前这条只打印哈希，等于没测】现在断言两件事：
     *   · matches() 能校验通过 —— 说明注册存进去的哈希、登录时确实能比对
     *   · 哈希 != 明文 —— 说明密码没被原样存进去
     */
    @Test
    void testPasswordEncoder() {
        String raw = "123456";
        String hash = passwordEncoder.encode(raw);

        assertNotEquals(raw, hash, "密码不能以明文形式存储");
        assertTrue(passwordEncoder.matches(raw, hash), "原文应当能通过 BCrypt 校验");
        assertFalse(passwordEncoder.matches("wrong-password", hash), "错误密码不应通过校验");
        // 同一个明文每次加密结果都不同（BCrypt 每次都会生成新的随机盐）
        assertNotEquals(hash, passwordEncoder.encode(raw), "加盐后同一明文的两次哈希结果不应相同");
    }
}
