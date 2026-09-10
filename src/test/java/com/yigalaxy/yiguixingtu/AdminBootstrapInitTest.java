package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.config.AdminBootstrapRunner;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 管理员初始化引导的测试（AdminBootstrapRunner）
 *
 * 【它守护的是"全新部署能不能进后台"这件事】
 *   注册接口只产出 GUEST，而提升角色又要求调用者是 ADMIN ——
 *   所以一个全新数据库如果没有引导机制，后台是【永久进不去】的。
 *   这类问题只在"真的一次干净部署"时才出现，本地永远测不出来，
 *   所以必须用测试把它钉住。
 *
 * 【为什么直接调 bootstrapAdminIfAbsent，而不是调 run()】
 *   run() 只是"从配置读三个值再转交给这个业务方法"。
 *   直接调业务方法可以传任意账号密码，想测"配置为空"就直接传 null，
 *   不必为了这一种情形再起一个改了配置的 Spring 上下文（那样每个用例都要几十秒）。
 *   —— 这也是把判断逻辑抽成 public 方法的原因。
 *
 * 【为什么测试里的库能保证"没有管理员"】
 *   数据库是 Testcontainers 起的干净容器；用例又都跑在基类的 @Transactional 里，
 *   跑完自动回滚。所以每个用例开始时"启用的 ADMIN 数量"都是 0。
 *   下面 @BeforeEach 会把这条前提【显式断言】出来 ——
 *   如果哪天这个前提被破坏，用例会以"前提不成立"的方式失败，
 *   而不是给出一个让人看不懂的断言错误。
 * =====================================================================
 */
class AdminBootstrapInitTest extends AbstractIntegrationTest {

    @Autowired
    private AdminBootstrapRunner runner;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabaseIsThePrecondition() {
        assertEquals(0L, countEnabledAdmins(),
                "测试前提：干净的测试库里不应该有管理员（下面的用例都建立在这个前提上）");
    }

    @Test
    @DisplayName("空库 + 配了引导账号 -> 建出管理员，密码存的是 BCrypt 哈希")
    void whenNoAdminAndConfigured_shouldCreateAdmin() {
        String result = runner.bootstrapAdminIfAbsent("bootstrap_admin", "Admin@123456", "站长");

        assertEquals("CREATED", result);

        User created = findByUsername("bootstrap_admin");
        assertNotNull(created, "应当已经把管理员建出来了");
        assertEquals("ADMIN", created.getRole(), "角色必须是 ADMIN，否则进不了后台");
        assertEquals(1, created.getStatus(), "必须是启用状态，被禁用的管理员登录会被拒");

        // 【关键断言】存的必须是 BCrypt 哈希，而且能校验通过。
        // 如果图省事存明文，这个账号是【登录不进去】的（登录时用 BCrypt.matches 比对），
        // 而且库里会留一条明文密码 —— 这条断言把两种错误一起挡住
        assertNotEquals("Admin@123456", created.getPassword(), "密码不能以明文存储");
        assertTrue(passwordEncoder.matches("Admin@123456", created.getPassword()),
                "存进去的哈希应当能通过 BCrypt 校验（否则这个账号根本登不进去）");
    }

    @Test
    @DisplayName("已经有管理员 -> 什么都不做（不能每次启动都建一个账号）")
    void whenAdminAlreadyExists_shouldSkip() {
        runner.bootstrapAdminIfAbsent("first_admin", "Admin@123456", "第一个管理员");
        long adminsAfterFirst = countEnabledAdmins();
        assertEquals(1L, adminsAfterFirst);

        String result = runner.bootstrapAdminIfAbsent("second_admin", "Admin@123456", "第二个管理员");

        assertEquals("SKIPPED_ADMIN_EXISTS", result);
        assertEquals(adminsAfterFirst, countEnabledAdmins(),
                "已经存在管理员时再来一次引导不该多出账号 —— 否则每次重启都会多一个管理员");
    }

    @Test
    @DisplayName("空库 + 没配引导账号 -> 不建账号也不报错，返回明确结果")
    void whenNotConfigured_shouldDoNothingWithoutFailing() {
        assertEquals("SKIPPED_NOT_CONFIGURED", runner.bootstrapAdminIfAbsent(null, null, null));
        assertEquals(0L, countEnabledAdmins(), "没配置时不该凭空造出管理员");

        // 顺带覆盖"只配了一半"的情形：也必须按未配置处理，
        // 而不是把 null 编码成密码存进去（那会造出一个无论如何都登不进去的账号）
        assertEquals("SKIPPED_NOT_CONFIGURED", runner.bootstrapAdminIfAbsent("only_name", null, null));
        assertEquals(0L, countEnabledAdmins());
    }

    @Test
    @DisplayName("空库 + 用户名已存在（是 GUEST）-> 提升为 ADMIN，且不改它的密码")
    void whenUsernameExistsAsGuest_shouldPromoteWithoutChangingPassword() {
        // 模拟"先注册了一个普通账号，再想把它变成管理员"这个很常见的部署场景
        User guest = new User();
        guest.setUsername("my_account");
        guest.setPassword(passwordEncoder.encode("OriginalPass123"));
        guest.setNickname("原来的昵称");
        guest.setRole("GUEST");
        guest.setStatus(1);
        guest.setDeleted(0);
        userMapper.insert(guest);
        String passwordHashBefore = guest.getPassword();

        String result = runner.bootstrapAdminIfAbsent("my_account", "BrandNewPass456", "新昵称");

        assertEquals("PROMOTED", result);
        User after = findByUsername("my_account");
        assertEquals("ADMIN", after.getRole(), "应当已经被提升为管理员");

        // 【关键断言】密码必须原封不动。
        // 改密码是破坏性动作：万一运维把这个配置填成了某个正在使用的账号，
        // 重置密码会把那个人直接挡在门外。所以只提升角色、不动凭据
        assertEquals(passwordHashBefore, after.getPassword(),
                "提升角色时不应该改动已有账号的密码");
        assertTrue(passwordEncoder.matches("OriginalPass123", after.getPassword()),
                "原来的密码仍然应当可用");
        assertTrue(!passwordEncoder.matches("BrandNewPass456", after.getPassword()),
                "配置里那个新密码不该被写进去（它只在新建账号时才生效）");
    }

    @Test
    @DisplayName("被禁用的管理员不算数 -> 仍然会触发引导（否则后台照样进不去）")
    void whenAdminExistsButDisabled_shouldStillBootstrap() {
        User disabledAdmin = new User();
        disabledAdmin.setUsername("disabled_admin");
        disabledAdmin.setPassword(passwordEncoder.encode("whatever123"));
        disabledAdmin.setNickname("被禁用的管理员");
        disabledAdmin.setRole("ADMIN");
        disabledAdmin.setStatus(0);          // 关键：被禁用
        disabledAdmin.setDeleted(0);
        userMapper.insert(disabledAdmin);

        // 被禁用的管理员登录会被拒（LoginUser.isEnabled 读 status），
        // 所以"有 ADMIN 记录"不等于"能进后台" —— 这种情况必须继续引导
        assertEquals(0L, countEnabledAdmins(), "前提：被禁用的管理员不该被计入");

        String result = runner.bootstrapAdminIfAbsent("real_admin", "Admin@123456", "站长");

        assertEquals("CREATED", result, "没有【可用的】管理员时应当继续引导");
        assertEquals(1L, countEnabledAdmins());
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 统计启用状态的管理员数量（与 AdminBootstrapRunner 里的判断口径保持一致） */
    private long countEnabledAdmins() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, "ADMIN")
                .eq(User::getStatus, 1));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }
}
