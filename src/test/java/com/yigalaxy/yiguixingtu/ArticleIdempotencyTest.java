package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.idempotency.IdempotencyService;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 接口幂等测试（Idempotency-Key）—— "点两下会不会多出一篇文章"
 *
 * 【它守护的是一个非常具体、人人都遇到过的场景】
 *   在后台点「发布」，网络卡了两秒没反应，你又点了一下 ——
 *   浏览器发出两个一模一样的创建请求，库里出现两篇一样的文章。
 *   这个类要证明的就是：带上 Idempotency-Key 之后，重复请求
 *   只会产生一篇文章，而且第二次返回的是【和第一次同一个 id】。
 *
 * 【为什么断言的是"库里的行数"而不是"接口返回了什么"】
 *   接口完全可以返回成功、库里却多写了一行 —— 那正是这个 bug 的样子。
 *   所以每条用例都用 Mapper 直接数一遍库里的真实行数，
 *   而且计数用的是本轮测试独有的标题标记，不会受别的数据影响。
 *
 * 【为什么"不带幂等键"也要有一条用例】
 *   幂等键是【可选】的，不带的时候行为必须和以前完全一样 ——
 *   否则对老调用方就是一次破坏性改动。这条用例守的就是那个兼容性。
 * =====================================================================
 */
class ArticleIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private IdempotencyService idempotencyService;

    /** 本次测试的唯一标记，用来把"本次造的数据"和任何其它数据分开 */
    private String mark;

    private User admin;
    private String adminToken;
    private Category category;

    @BeforeEach
    void setUp() {
        mark = "IDEM" + System.nanoTime();

        admin = new User();
        admin.setUsername("idem_admin_" + mark);
        admin.setPassword(passwordEncoder.encode("123456"));
        admin.setNickname("幂等测试管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setDeleted(0);
        userMapper.insert(admin);
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        category = new Category();
        category.setName(mark + "_分类");
        category.setDescription("幂等测试用分类");
        category.setSort(99);
        category.setDeleted(0);
        categoryMapper.insert(category);
    }

    // =================================================================
    //  核心：同一个幂等键发两次
    // =================================================================

    @Test
    @DisplayName("① 同一个幂等键发两次 -> 库里只有一篇文章，且两次返回同一个 id")
    void sameKeyTwice_shouldCreateOnlyOneArticle() throws Exception {
        String key = "key-" + mark;
        String body = articleJson("重复提交的文章");

        Long firstId = postCreate(body, key);
        Long secondId = postCreate(body, key);

        // 关键断言：两次拿到的是【同一个 id】。
        // 对调用方来说，"重试"和"第一次成功"长得完全一样 —— 这就是幂等的定义。
        assertEquals(firstId, secondId,
                "同一个幂等键的第二次请求应当返回第一次创建出来的那个 id");

        // 更关键的一条：库里真的只有一篇。
        // 接口返回正确、库里却写了两行，正是这个 bug 最典型的样子。
        assertEquals(1, countByTitle("重复提交的文章"),
                "同一个幂等键发两次，库里必须只有一篇文章");
    }

    @Test
    @DisplayName("② 不同幂等键 -> 两篇都要创建出来（不能把正常需求也拦掉）")
    void differentKeys_shouldCreateTwoArticles() throws Exception {
        String body = articleJson("不同键的文章");

        Long first = postCreate(body, "key-a-" + mark);
        Long second = postCreate(body, "key-b-" + mark);

        // 这条守的是"别拦过头"：用户真想写两篇同样内容时，
        // 只要是两次独立的提交动作（两个不同的键），就该都创建成功
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second,
                "不同的幂等键应当各自创建一篇文章");
        assertEquals(2, countByTitle("不同键的文章"),
                "两个不同的幂等键应当产生两篇文章");
    }

    @Test
    @DisplayName("③ 不带幂等键 -> 行为与以前完全一样（向后兼容）")
    void noKey_shouldKeepOldBehavior() throws Exception {
        String body = articleJson("不带键的文章");

        Long first = postCreate(body, null);
        Long second = postCreate(body, null);

        // 老调用方（Swagger 里手点、或者还没升级的前端）不带这个头，
        // 行为必须和加这个功能之前一模一样 —— 两次都创建
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
        assertEquals(2, countByTitle("不带键的文章"),
                "不带幂等键时应当保持原来的行为（每次都创建）");
    }

    // =================================================================
    //  边界：正在处理中、以及失败后要能重试
    // =================================================================

    @Test
    @DisplayName("④ 同一个键的请求正在处理中 -> 返回 429，且不重复执行")
    void sameKeyWhileProcessing_shouldReturnDuplicateSubmit() throws Exception {
        String key = "key-inflight-" + mark;

        // 【怎么确定性地造出"正在处理中"这个状态】
        //   真实并发不好在测试里稳定复现（要控制两个线程的时序），
        //   所以直接调用幂等服务的 claim 把坑占住 ——
        //   这和"另一个请求已经占位但还没写完结果"是同一个状态。
        //   测的是同一段判断逻辑，但结果是确定的、不会时绿时红。
        IdempotencyService.Claim claim = idempotencyService.claim("article:create", key);
        org.junit.jupiter.api.Assertions.assertTrue(claim.acquired(),
                "第一次占位应当成功，否则这条用例的前提就不成立");

        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson("正在处理中的文章")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.DUPLICATE_SUBMIT.getCode()));

        // 不重复执行：库里不该有这篇文章
        assertEquals(0, countByTitle("正在处理中的文章"),
                "占位还在处理中时，不该执行创建");
    }

    @Test
    @DisplayName("⑤ 创建失败后占位被释放 -> 用同一个键重试能成功")
    void failedCreate_shouldReleaseKeySoRetryWorks() throws Exception {
        String key = "key-retry-" + mark;

        // 第一次：故意用一个不存在的分类 id，让创建在业务校验上失败
        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJsonWithCategory("失败后重试的文章", 99999999L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CATEGORY_NOT_FOUND.getCode()));

        // 第二次：同一个键、改成合法分类，必须能创建成功。
        // 【为什么这条最重要】失败之后如果不释放占位，
        //   这个键会在占位 TTL（60 秒）内一直返回"正在处理中"——
        //   用户看到的是一个没有原因、也无法自救的失败。
        Long id = postCreate(articleJson("失败后重试的文章"), key);
        org.junit.jupiter.api.Assertions.assertNotNull(id);

        assertEquals(1, countByTitle("失败后重试的文章"),
                "失败后的重试应当能正常创建，且只创建一篇");
    }

    // =================================================================
    //  工具方法
    // =================================================================

    /** 发一次创建请求，返回 body 里的 data（文章 id）；key 为 null 时不带幂等头 */
    private Long postCreate(String json, String key) throws Exception {
        var request = post("/admin/article")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }

        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        int idx = response.indexOf("\"data\":");
        org.junit.jupiter.api.Assertions.assertTrue(idx > 0, "响应里应当带 data，实际=" + response);
        String tail = response.substring(idx + "\"data\":".length()).trim();
        return Long.valueOf(tail.substring(0, tail.indexOf('}')));
    }

    /** 数一数库里有多少篇标题以本次标记开头的文章 */
    private long countByTitle(String name) {
        return articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .eq(Article::getTitle, mark + "_" + name));
    }

    private String articleJson(String title) {
        return articleJsonWithCategory(title, category.getId());
    }

    private String articleJsonWithCategory(String title, Long categoryId) {
        return """
                {
                  "title": "%s",
                  "content": "正文",
                  "categoryId": %d,
                  "status": 0
                }
                """.formatted(mark + "_" + title, categoryId);
    }
}
