package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.comment.dto.CommentVO;
import com.yigalaxy.yiguixingtu.comment.entity.Comment;
import com.yigalaxy.yiguixingtu.comment.mapper.CommentMapper;
import com.yigalaxy.yiguixingtu.comment.service.CommentService;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 评论模块测试（发表 / 审核 / 可见性 / 防滥用）
 *
 * 【本类重点盯的四件事】
 *  ① **可见性**：这是评论模块最容易出严重问题的地方 ——
 *     未审核的评论一旦对外可见，等于这个站点成了垃圾信息的发布平台。
 *     所以用例里反复出现"提交后前台查不到 → 审核通过后才可见"这条主线，
 *     而且会专门拿 status=0 去试探前台接口（传参绕过是这类漏洞的常见形态）。
 *  ② 草稿文章不能评论：草稿对外根本不存在，能对它评论说明调用方拿到了不该拿的 id。
 *  ③ XSS：评论是全站唯一一个任何人都能写内容的地方。
 *     入库前已经做了 HTML 转义，用例断言 <script> 落库后是实体形式。
 *  ④ 防滥用：内容/昵称长度上限、评论接口限流（配额用完返回 429）。
 *
 * 【本类为什么没有"审计留痕"的断言】
 *   审计是 AFTER_COMMIT 才落库的，而本类是 @Transactional（跑完回滚）——
 *   事务永远不提交，事件会被丢弃，断言必然失败。那属于测试环境的事务语义，
 *   不是功能坏了；"审核 / 删除评论会留痕"放在 OperationLogTest 里验证
 *   （那个类用 Propagation.NOT_SUPPORTED，让业务真的提交）。
 * =====================================================================
 */
class CommentTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentMapper commentMapper;

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
    private JdbcTemplate jdbcTemplate;

    private String mark;
    private User admin;
    private String adminToken;
    /** 真正的 GUEST 用户（见 ⑫ 的说明：不能给管理员签一个 GUEST 的 token 来冒充游客） */
    private User guest;
    private String guestToken;
    private Category category;

    @BeforeEach
    void setUp() {
        mark = "C" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_comment", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        guest = insertUser("test_guest_comment", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        category = new Category();
        category.setName(mark + "_评论测试分类");
        category.setDescription("评论测试");
        category.setSort(99);
        category.setDeleted(0);
        categoryMapper.insert(category);
    }

    // ================================================================
    //  一、发表评论（游客可用）
    // ================================================================

    @Test
    @DisplayName("① 游客就能发表评论 -> 200，落库状态是【待审核】，前台还看不到")
    void guestCanPostComment_butItIsPending() throws Exception {
        Long articleId = insertArticle("评论目标", 1);

        // 不带任何 token：评论本来就该允许游客
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "路过的读者", "写得很清楚，收藏了！")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 返回值里带上 status，前端据此提示"评论已提交，等待审核"
                .andExpect(jsonPath("$.data.status").value(0));

        Comment saved = findComment(articleId, "路过的读者");
        assertNotNull(saved, "评论应当已经落库");
        assertEquals(Comment.STATUS_PENDING, saved.getStatus(), "默认必须是待审核");

        // 前台列表：查不到（这是"审核"这件事真正的意义）
        assertEquals(0, publishedComments(articleId).size(),
                "待审核的评论不该出现在前台列表里");
    }

    @Test
    @DisplayName("② 审核通过后前台才可见；拒绝则一直不可见")
    void commentBecomesVisibleOnlyAfterApproval() throws Exception {
        Long articleId = insertArticle("审核流程", 1);
        Long approvedId = postComment(articleId, "甲读者", "这条会被通过");
        Long rejectedId = postComment(articleId, "乙读者", "这条会被拒绝");

        assertEquals(0, publishedComments(articleId).size(), "审核前都不可见");

        // 通过
        mockMvc.perform(put("/admin/comment/{id}/status", approvedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        List<CommentVO> visible = publishedComments(articleId);
        assertEquals(1, visible.size(), "通过之后才出现在前台");
        assertEquals("甲读者", visible.get(0).getNickname());

        // 拒绝
        mockMvc.perform(put("/admin/comment/{id}/status", rejectedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, publishedComments(articleId).size(), "被拒绝的仍然不可见");
        // 但记录要留着：判断"是不是同一个人反复发"要靠它
        assertEquals(Comment.STATUS_REJECTED, commentMapper.selectById(rejectedId).getStatus(),
                "拒绝是改状态，不是删记录");
    }

    @Test
    @DisplayName("③ 前台接口传 status=0 也拿不到待审核的评论（状态是写死的，不是默认值）")
    void publishedList_shouldIgnoreStatusParam() throws Exception {
        Long articleId = insertArticle("绕过试探", 1);
        postComment(articleId, "试探者", "这条还没审核");

        // 这正是最常见的绕过形态：前端/攻击者直接传 status=0 想拿到待审核内容
        mockMvc.perform(get("/comment/list")
                        .param("articleId", String.valueOf(articleId))
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    @DisplayName("④ 草稿文章不能评论（它对外根本不存在）")
    void cannotCommentOnDraft() throws Exception {
        Long draftId = insertArticle("草稿", 0);

        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(draftId, "读者", "对草稿的评论")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("⑤ 评论不存在的文章 -> 404")
    void cannotCommentOnUnknownArticle() throws Exception {
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(999999999L, "读者", "对不存在文章的评论")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("⑥ 昵称/内容为空或超长 -> 400（校验与列长度一致，用户看得懂）")
    void invalidComment_shouldBeRejected() throws Exception {
        Long articleId = insertArticle("参数校验", 1);

        // 昵称为空白
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "   ", "内容")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 内容为空白
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "读者", "   ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 内容超过 1000 字
        String tooLong = "长".repeat(1001);
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, "读者", tooLong)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 邮箱格式不对（选填，但填了就要合法）
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId
                                + ",\"nickname\":\"读者\",\"email\":\"not-an-email\",\"content\":\"内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑦ XSS：评论里的 <script> 落库时被转义（这个站点不该成为脚本的投放平台）")
    void contentShouldBeEscapedBeforePersist() throws Exception {
        Long articleId = insertArticle("XSS 验证", 1);

        postComment(articleId, "<img src=x onerror=alert(1)>", "<script>alert('xss')</script> 你好");

        Comment saved = commentMapper.selectOne(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .last("LIMIT 1"));

        assertNotNull(saved, "评论应当落库");
        // 转义发生在【入库这一层】：这样不管将来谁去渲染它（前端、RSS、导出脚本），
        // 都不会执行到脚本 —— 而不是依赖"每个渲染方都记得转义"
        assertTrue(saved.getContent().contains("&lt;script&gt;"),
                "尖括号应当被转义成实体，实际=" + saved.getContent());
        assertTrue(!saved.getContent().contains("<script>"),
                "库里不该出现原始的 script 标签");
        // 昵称同样是用户输入，一样要转义
        assertTrue(saved.getNickname().contains("&lt;img"),
                "昵称也应当被转义，实际=" + saved.getNickname());
    }

    @Test
    @DisplayName("⑧ 前台返回里【没有】email 和 ip（公开接口不该带读者隐私）")
    void publicCommentVo_shouldNotExposeEmailOrIp() throws Exception {
        Long articleId = insertArticle("隐私字段", 1);
        Long id = postCommentWithEmail(articleId, "带邮箱的读者", "reader@example.com", "内容");
        commentService.updateStatus(id, Comment.STATUS_APPROVED);

        String body = mockMvc.perform(get("/comment/list")
                        .param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 直接对响应体做字符串断言：这比"字段为 null"更强 ——
        // 就算将来有人给 VO 加了字段，只要它出现在响应里就会红
        assertTrue(!body.contains("reader@example.com"),
                "公开接口的响应体里不该出现邮箱，实际=" + body);
        assertTrue(!body.contains("\"ip\""),
                "公开接口的响应体里不该出现 ip 字段，实际=" + body);
    }

    // ================================================================
    //  二、后台管理
    // ================================================================

    @Test
    @DisplayName("⑨ 后台能看到待审核的评论，并且带邮箱、IP 与文章标题")
    void adminPage_shouldShowPendingWithSensitiveFields() throws Exception {
        Long articleId = insertArticle("后台列表", 1);
        postCommentWithEmail(articleId, "待审核的人", "pending@example.com", "内容");

        mockMvc.perform(get("/admin/comment/page")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "0")
                        .param("articleId", String.valueOf(articleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].nickname").value("待审核的人"))
                .andExpect(jsonPath("$.data.records[0].email").value("pending@example.com"))
                // IP 是给管理员判断"是不是同一个人刷"用的
                .andExpect(jsonPath("$.data.records[0].ip").exists())
                // 文章标题：否则后台只能看到一串 article_id
                .andExpect(jsonPath("$.data.records[0].articleTitle").exists());
    }

    @Test
    @DisplayName("⑩ 审核状态非法（0 / 3 / 空）-> 400；审核不存在的评论 -> 404")
    void updateStatus_shouldValidateInput() throws Exception {
        Long articleId = insertArticle("审核校验", 1);
        Long id = postComment(articleId, "读者", "内容");

        // 0（待审核）不是一个可以"审核到"的状态：审核只有通过与拒绝两个结果
        mockMvc.perform(put("/admin/comment/{id}/status", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/admin/comment/{id}/status", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/admin/comment/{id}/status", 999999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("⑪ 删除评论 -> 逻辑删除（物理行还在），前台也不可见")
    void deleteComment_shouldBeLogical() throws Exception {
        Long articleId = insertArticle("删除评论", 1);
        Long id = postComment(articleId, "被删的人", "这条会被删掉");
        commentService.updateStatus(id, Comment.STATUS_APPROVED);
        assertEquals(1, publishedComments(articleId).size(), "前置条件：通过后可见");

        mockMvc.perform(delete("/admin/comment/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(commentMapper.selectById(id), "Mapper 查不到了（@TableLogic 过滤掉了）");
        // 但物理行还在：这是"逻辑删除"的定义，也是"删错了还能人工恢复"的前提。
        // 用原生 SQL 直查才看得到这个区别（Mapper 会自动加 deleted = 0）
        Integer physical = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE id = ? AND deleted = 1", Integer.class, id);
        assertEquals(1, physical, "逻辑删除：物理行还在，只是 deleted = 1");
        assertEquals(0, publishedComments(articleId).size(), "删掉之后前台不可见");
    }

    @Test
    @DisplayName("⑫ 权限：游客 403、无 token 401（后台评论接口一个都不能漏）")
    void adminCommentApis_shouldRequireAdmin() throws Exception {
        // 【这里曾经写错过，值得记一笔】第一版是拿管理员的 id 去签一个 role=GUEST 的 token，
        // 想省掉"再插一个游客用户"这一步 —— 结果接口返回 200，用例红了。
        // 原因不是权限有漏洞，恰恰相反：JwtAuthenticationFilter 判断身份时
        // 【以数据库里的角色为准】（token 里的 role claim 只作为签发时的快照）。
        // 所以给一个 ADMIN 用户签 GUEST 的 token，他依然是管理员 ——
        // 这正是我们要的行为（token 里的旧角色不能反过来"降级"或"提权"用户）。
        // 测试要造游客，就必须真的插一个 GUEST 用户。
        mockMvc.perform(get("/admin/comment/page")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/comment/page"))
                .andExpect(status().isUnauthorized());
        // 审核与删除更要挡：它们是"让内容上线/下线"的能力
        mockMvc.perform(put("/admin/comment/{id}/status", 1L)
                        .header("Authorization", "Bearer " + guestToken)
                        .param("status", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/comment/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    //  三、防滥用与查询细节
    // ================================================================

    @Test
    @DisplayName("⑬ 评论接口有限流：配额用完返回真 HTTP 429")
    void createComment_shouldBeRateLimited() throws Exception {
        Long articleId = insertArticle("限流验证", 1);

        // 配额是 20 次/分钟（见 application.properties 的 commentRateLimiter）。
        // 这里打 21 次：前 20 次成功，第 21 次必须被拒。
        // 【为什么要打到超配额】只断言"没超时能成功"是测不出限流的 ——
        // 那种断言在限流器被误删之后依然会绿。
        int tooManyRequests = 0;
        for (int i = 0; i < 21; i++) {
            int code = mockMvc.perform(post("/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentJson(articleId, "刷评论的人", "第 " + i + " 条")))
                    .andReturn().getResponse().getStatus();
            if (code == 429) {
                tooManyRequests++;
            }
        }
        assertTrue(tooManyRequests >= 1,
                "超过配额后应当返回 429，实际一次都没有（限流可能没生效）");
    }

    @Test
    @DisplayName("⑭ 前台评论列表按时间【正序】、支持分页，且只能查指定文章的评论")
    void publishedList_shouldBeOrderedAndPaged() throws Exception {
        Long first = insertArticle("排序-甲", 1);
        Long second = insertArticle("排序-乙", 1);

        Long a = postComment(first, "先来的", "第一条");
        Long b = postComment(first, "后来的", "第二条");
        Long other = postComment(second, "别篇文章的", "不该出现");
        commentService.updateStatus(a, Comment.STATUS_APPROVED);
        commentService.updateStatus(b, Comment.STATUS_APPROVED);
        commentService.updateStatus(other, Comment.STATUS_APPROVED);

        List<CommentVO> visible = publishedComments(first);
        assertEquals(2, visible.size(), "只该有本篇文章的评论");
        // 评论区是对话，最早的在上面读起来最自然（后台反而是倒序：管理员关心最新的）
        assertEquals("先来的", visible.get(0).getNickname(), "前台应当按时间正序");

        // 分页要真的生效：一页一条时，第一页拿到的是最早那条
        assertEquals(1, commentService.pagePublished(commentQuery(first, 1L, 1L)).getRecords().size());
        assertEquals("后来的",
                commentService.pagePublished(commentQuery(first, 2L, 1L)).getRecords().get(0).getNickname());
    }

    @Test
    @DisplayName("⑮ 前台列表必须带 articleId，否则 400（不能当「全站评论流」用）")
    void publishedList_requiresArticleId() throws Exception {
        mockMvc.perform(get("/comment/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private List<CommentVO> publishedComments(Long articleId) {
        return commentService.pagePublished(commentQuery(articleId, 1L, 50L)).getRecords();
    }

    private com.yigalaxy.yiguixingtu.comment.dto.CommentQuery commentQuery(Long articleId, Long page, Long size) {
        com.yigalaxy.yiguixingtu.comment.dto.CommentQuery query =
                new com.yigalaxy.yiguixingtu.comment.dto.CommentQuery();
        query.setArticleId(articleId);
        query.setPage(page);
        query.setSize(size);
        return query;
    }

    /** 走接口发表评论，返回新评论 id */
    private Long postComment(Long articleId, String nickname, String content) throws Exception {
        String body = mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson(articleId, nickname, content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").get("id").asLong();
    }

    private Long postCommentWithEmail(Long articleId, String nickname, String email, String content)
            throws Exception {
        String body = mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"nickname\":\"" + nickname
                                + "\",\"email\":\"" + email + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").get("id").asLong();
    }

    private String commentJson(Long articleId, String nickname, String content) {
        return "{\"articleId\":" + articleId + ",\"nickname\":\"" + nickname
                + "\",\"content\":\"" + content + "\"}";
    }

    private Comment findComment(Long articleId, String nickname) {
        return commentMapper.selectOne(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getNickname, nickname)
                .last("LIMIT 1"));
    }

    private Long insertArticle(String title, int status) {
        Article article = new Article();
        article.setTitle(mark + "-" + title);
        article.setSummary("评论测试摘要");
        article.setContent("# 评论测试\n\n正文");
        article.setCategoryId(category.getId());
        article.setStatus(status);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(admin.getId());
        article.setDeleted(0);
        articleMapper.insert(article);
        return article.getId();
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("评论测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }
}
