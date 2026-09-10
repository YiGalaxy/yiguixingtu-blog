package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.config.MybatisPlusConfig;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 分页全局上限测试（MybatisPlusConfig.MAX_PAGE_SIZE）
 *
 * 【为什么这个测试类不叫 XxxAdminTest】
 *   现有测试类是按【业务模块】分的（ArticleAdminTest / UserAdminTest ...）。
 *   但这个类验证的东西不属于任何业务模块 ——
 *   它验证的是 MyBatis-Plus 分页插件那道【横切所有分页查询】的安全底线。
 *   硬塞进某个模块的测试类里，反而会让人误以为"只有那个模块有这个限制"。
 *
 * 【为什么必须绕过 Service、直接调 Mapper】
 *   现在每个分页接口都已经自己夹过 size 了
 *   （文章 Math.min(size,50)、用户 if(size>100) size=100），
 *   所以从 HTTP 层根本传不进一个"超过全局上限的 size"——
 *   请求还没走到插件就被 Service 夹小了。
 *   也就是说，插件这层兜底【无法通过接口用例触发】。
 *   如果只在接口层测，这行配置就等于没有测试覆盖，哪天真被删掉也不会变红。
 *   所以这里直接调 userMapper.selectPage，模拟"将来某个 Service 忘了夹取"的情形。
 *
 * 【怎么才能真的观察到插件生效 —— 这里踩过一个坑，很值得记】
 *   第一版我断言的是 `page.getSize()` 应该从 999999 变成 100。结果失败了：
 *       expected: <100> but was: <999999>
 *   查清楚才明白：setMaxLimit 只改写【最终拼出来的 SQL 里的 LIMIT】，
 *   并不会把 Page 对象上的 size 字段改掉。
 *   （这也是为什么 UserAdminTest 里 `$.data.size` 能看到 100 ——
 *    那个 100 是 UserServiceImpl 自己夹的，跟插件没关系。两回事。）
 *   所以想证明插件生效，只能看【真正返回了多少条】：
 *   先造出超过上限的数据，再用一个超大的 size 去查，
 *   如果只回来 100 条，就说明 LIMIT 被改写了。
 *   光断言"返回了 100 条"还不够 —— 万一表里本来就只有 100 条，
 *   那不设上限也是 100 条。所以必须同时断言【总数确实超过上限】，
 *   这条断言才是有效的。
 *
 * 【用到的东西】
 *   · {@code Page<User>} —— MyBatis-Plus 的分页对象；getRecords() 是本次取回的数据，
 *     getTotal() 是 count 查询得到的总条数（不受 LIMIT 影响）
 *   · {@code userMapper.selectPage(page, null)} —— 走真实的 Mapper 代理与完整插件链，
 *     和业务代码的运行路径一致（wrapper 传 null 表示不加额外条件）
 *   · 继承 {@link AbstractIntegrationTest} —— 拿到 Testcontainers 起的真实 MySQL，
 *     这样才能真的跑 LIMIT 语句
 *   · {@code @Transactional}（在基类上）—— 造出来的 150 条测试用户跑完自动回滚
 * =====================================================================
 */
class PaginationLimitTest extends AbstractIntegrationTest {

    /** 造多少条测试数据：必须明显大于全局上限，否则测不出效果 */
    private static final int SEED_COUNT = 150;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("给一个远超上限的 size -> 实际最多只会取回上限条数")
    void selectPage_sizeBeyondGlobalLimit_shouldReturnAtMostMaxPageSize() {
        seedUsers(SEED_COUNT);

        // 模拟"某个 Service 忘了夹取"：直接把一个荒唐的 size 交给插件
        Page<User> page = new Page<>(1, 999_999);
        userMapper.selectPage(page, null);

        // 【前提断言】先证明"库里的数据确实比上限多"。
        // 没有这一条，下面那句断言可能是"因为本来就只有 100 条"而通过的，
        // 等于什么也没验证住 —— 这种"看着绿、其实无效"的用例最危险。
        assertTrue(page.getTotal() >= SEED_COUNT,
                "库里应当至少有 " + SEED_COUNT + " 条数据，否则测不出上限的作用，实际 total=" + page.getTotal());

        // 【核心断言】想要 99 万条，实际只回来 100 条 —— 说明 LIMIT 被插件改写了
        assertEquals(MybatisPlusConfig.MAX_PAGE_SIZE, page.getRecords().size(),
                "超过全局上限时，实际取回的条数应当正好是上限值");
    }

    @Test
    @DisplayName("size 没超上限 -> 该取多少就取多少，插件不该多管闲事")
    void selectPage_sizeWithinLimit_shouldReturnRequestedCount() {
        seedUsers(SEED_COUNT);

        long normalSize = 20L;
        Page<User> page = new Page<>(1, normalSize);
        userMapper.selectPage(page, null);

        // 夹取必须是"只在超过上限时才动手"。
        // 如果写成无条件改写，所有分页都会被强行拉成 100 条，
        // 前端"每页 10 条"的设置就悄悄失效了 —— 这种 bug 从界面上很难看出来
        assertEquals(normalSize, page.getRecords().size(),
                "没超过上限的 size 应当原样生效");
    }

    /**
     * 造测试数据。
     *
     * 【为什么用户名要带索引和纳秒时间戳】
     *   user 表上 username 是唯一索引，直接用固定名字第二次插入就会撞索引。
     *   加上纳秒时间戳可以让这个测试反复跑都不冲突（基类的 @Transactional
     *   虽然会回滚，但同一个类里两个用例是前后脚跑的，靠时间戳更保险）。
     */
    private void seedUsers(int count) {
        String tag = "pglimit_" + System.nanoTime() + "_";
        for (int i = 0; i < count; i++) {
            User u = new User();
            u.setUsername(tag + i);
            u.setPassword(passwordEncoder.encode("123456"));
            u.setNickname("分页上限测试用户");
            u.setRole("GUEST");
            u.setStatus(1);
            userMapper.insert(u);
        }
    }
}
