package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.about.entity.About;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.audit.OperationLog;
import com.yigalaxy.yiguixingtu.audit.mapper.OperationLogMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.favorite.entity.Favorite;
import com.yigalaxy.yiguixingtu.favorite.mapper.FavoriteMapper;
import com.yigalaxy.yiguixingtu.link.entity.FriendLink;
import com.yigalaxy.yiguixingtu.link.mapper.FriendLinkMapper;
import com.yigalaxy.yiguixingtu.music.entity.Music;
import com.yigalaxy.yiguixingtu.music.mapper.MusicMapper;
import com.yigalaxy.yiguixingtu.project.entity.Project;
import com.yigalaxy.yiguixingtu.project.mapper.ProjectMapper;
import com.yigalaxy.yiguixingtu.setting.entity.SiteSetting;
import com.yigalaxy.yiguixingtu.tag.entity.Tag;
import com.yigalaxy.yiguixingtu.tag.mapper.TagMapper;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 操作审计（operation_log）的集成测试
 *
 * 【这个功能要证明什么】
 *   审计的价值只有一条：**事后能回答"谁、在什么时候、对什么东西、做了什么"**。
 *   所以每条用例都是把一条真实记录从库里捞出来，逐字段对：
 *   操作人、动作、对象、IP、traceId、detail 快照。
 *   只断言"接口返回 200"是不够的 —— 审计写没写、写对没写对，
 *   只有查库才知道。
 *
 * ---------------------------------------------------------------------
 * 【⚠️ 本类最关键的一件事：为什么整个类都要关掉测试事务】
 *
 *   基类 AbstractIntegrationTest 上有 @Transactional，作用是"跑完自动回滚"，
 *   绝大多数测试类靠它保持库干净。但在这里它会【让功能看起来完全失效】：
 *
 *     · 落库监听器用的是 @TransactionalEventListener(AFTER_COMMIT) ——
 *       "业务事务真的提交了才记账"，这是审计可信度的来源（见 OperationLogListener）
 *     · 而测试事务【永远只回滚、从不提交】
 *     · 两者一叠加：事件永远等不到那个 commit，一条审计都不会写
 *
 *   更麻烦的是它的失败长相：测试会红在"查不到审计记录"，
 *   看起来像"功能没实现"，而真正的原因是"测试环境的事务语义和线上不一样"。
 *
 *   所以本类显式声明 Propagation.NOT_SUPPORTED（挂起/不开启测试事务），
 *   让业务真的提交 —— 测的就是线上那套事务行为。
 *
 *   代价：数据真的要自己清理，见 @AfterEach。
 *   （同样的取舍在 ArticleDetailCacheTest 第⑧条并发用例里出现过：
 *     那个是为了让别的线程看得见数据，这里是为了让事务真的提交。）
 *
 * ---------------------------------------------------------------------
 * 【为什么每条断言都要"轮询等待"而不是直接查】
 *   监听器上还有 @Async —— 请求返回时，落库动作可能还在另一个线程上排队。
 *   直接查库就会变成"偶发查不到"的随机红（这类测试比没有还糟：
 *   会让人习惯性重跑，直到某次恰好绿了）。
 *   所以下面统一用 awaitLog 轮询到超时，见该方法的注释。
 * =====================================================================
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OperationLogTest extends AbstractIntegrationTest {

    /** 轮询上限：5 秒足够（正常只要几毫秒），超时说明真的没写进去 */
    private static final long WAIT_TIMEOUT_MS = 5000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private TagMapper tagMapper;

    /** 友链：⑮ 验证内容模块（F5）的写操作也会留痕 */
    @Autowired
    private FriendLinkMapper friendLinkMapper;

    /** 项目：⑯ 同上 */
    @Autowired
    private ProjectMapper projectMapper;

    /** 收藏：⑰ 同上 */
    @Autowired
    private FavoriteMapper favoriteMapper;

    /** 音乐：⑲ 同上 */
    @Autowired
    private MusicMapper musicMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ArticleService articleService;

    /** 用来建一个"外层事务"，专门演示回滚场景（见第⑪条） */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 直接用原生 SQL 连审计表的【原始一行】取出来。
     * 用途见第⑩条：要断言"整行里都不含明文密码"，
     * 用实体类查只能逐个字段看，漏一个字段就漏一种泄露方式。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试的唯一标记，避免和库里已有数据混淆（也不需要先清库） */
    private String mark;

    private User admin;
    private User guest;
    private Category category;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mark = "AUD" + System.nanoTime();

        // 直接写库造用户，不走注册/登录接口：
        // 一是快，二是避免把"注册"这件事的审计混进断言范围
        admin = insertUser("test_admin_audit", "ADMIN");
        guest = insertUser("test_guest_audit", "GUEST");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        category = new Category();
        category.setName(mark + "_审计分类");   // 分类名有唯一索引，必须带标记
        category.setDescription("审计测试用分类");
        category.setSort(99);
        category.setDeleted(0);
        categoryMapper.insert(category);
    }

    /**
     * 本类没有测试事务兜底，所以每条用例跑完要自己把数据删干净。
     *
     * 【为什么一律按 mark 删，而不是记下 id 逐个删】
     *   mark 是每条用例唯一的，所有造出来的数据（用户名、标题、分类名）
     *   都带着它，按 like 一删就是"本次用例造的全部"。
     *   漏删的后果很具体：这个 JVM 里 MySQL 容器是【所有测试类共用一个】的
     *   （见 AbstractIntegrationTest 的 singleton container 说明），
     *   脏数据会流到后面跑的测试类里去。
     *
     * 【为什么用物理 DELETE 而不是 Mapper】
     *   Article / User 上有 @TableLogic，走 Mapper 删是"逻辑删除"——
     *   行还在表里，唯一索引还占着，下次跑测试可能直接撞 Duplicate entry。
     *   这里要的是"当它没来过"，所以用原生 SQL 物理删。
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM operation_log WHERE username LIKE ? OR detail LIKE ?",
                "%" + mark + "%", "%" + mark + "%");
        jdbcTemplate.update("DELETE FROM article WHERE title LIKE ?", mark + "%");
        // 标签这块要额外清：标签是物理删除，用例里造的标签（以及它们的关联）
        // 不会随测试事务回滚消失 —— 本类是 NOT_SUPPORTED，所有写入都是真提交
        jdbcTemplate.update("DELETE FROM article_tag WHERE tag_id IN (SELECT id FROM tag WHERE name LIKE ?)",
                "%" + mark + "%");
        jdbcTemplate.update("DELETE FROM tag WHERE name LIKE ?", "%" + mark + "%");
        // 评论同理：本类是 NOT_SUPPORTED，评论是真的提交进库的
        jdbcTemplate.update("DELETE FROM comment WHERE nickname LIKE ?", "%" + mark + "%");
        // 友链（F5）：同样是真提交，而且它只有一个自增 id 可用作清理依据，
        // 所以一律按"名字里带 mark"来删
        jdbcTemplate.update("DELETE FROM friend_link WHERE name LIKE ?", "%" + mark + "%");
        // 项目（F5）：同上
        jdbcTemplate.update("DELETE FROM project WHERE name LIKE ?", "%" + mark + "%");
        // 收藏（F5）：同上
        jdbcTemplate.update("DELETE FROM favorite WHERE title LIKE ?", "%" + mark + "%");
        // 音乐（F6）：同上（本类是 NOT_SUPPORTED，音乐是真的提交进库的）
        jdbcTemplate.update("DELETE FROM music WHERE title LIKE ?", "%" + mark + "%");
        // 关于页（F5）：这张表只有一行、而且不能删（删了前后台都拿不到数据），
        // 所以本类改完之后要【改回迁移脚本里的初始状态】，而不是删掉它
        jdbcTemplate.update("UPDATE about SET nickname = '站长', avatar = NULL, bio = NULL,"
                + " email = NULL, github = NULL, wechat = NULL, qq = NULL WHERE id = 1");
        // 站点设置（F7）：同样是"只有一行、不能删"，同样要改回迁移脚本里的初始状态。
        // ⚠️ 而且这一个【必须】还原，理由比 about 更硬：评论总开关关掉之后，
        //    POST /comment 会被后端真的拒绝（ResultCode.COMMENT_DISABLED）——
        //    本类是 NOT_SUPPORTED（真提交），不还原的话，凡是在它之后跑的评论用例
        //    都会莫名其妙地失败，而报错信息是"评论已关闭"，
        //    看起来像"评论模块坏了"，实际是上一个测试类留下的开关。
        //    （站点名与每页条数也一起还原：它们同样是共享状态，还原是一行的事。）
        //    ⚠️ 这条 SQL 是【逐列列举】的，所以 site_setting 每加一列都要回来补一笔
        //       （V13 加的 police_number 就是这么补上的）。漏了的表现很隐蔽：
        //       本类跑完会把公安备案号残留在那一行里，而"页脚该不该显示公安备案"
        //       恰恰就是靠这一列是不是 NULL 来判断的 —— 后面的用例会读到一个
        //       根本不是它写进去的值，失败时看起来像"新功能坏了"。
        jdbcTemplate.update("UPDATE site_setting SET site_name = '亿轨星途', announcement = NULL,"
                + " comment_enabled = 1, icp_number = NULL, police_number = NULL,"
                + " copyright = NULL, page_size = 12 WHERE id = 1");
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE ?", "%" + mark + "%");
        jdbcTemplate.update("DELETE FROM category WHERE name LIKE ?", "%" + mark + "%");
    }

    // ================================================================
    //  一、文章类写操作的审计
    // ================================================================

    @Test
    @DisplayName("① 新建文章 -> 记下 CREATE_ARTICLE，且操作人/对象/traceId/IP 都对")
    void createArticle_shouldBeAudited() throws Exception {
        String title = mark + "_审计-新建";

        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(title, "正文", category.getId(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber());

        Long articleId = findByTitle(title).getId();

        OperationLog log = awaitLog("CREATE_ARTICLE", articleId);
        assertNotNull(log, "新建文章后应当有一条 CREATE_ARTICLE 审计记录");

        // 【核心字段逐一对】审计记录的价值全在这些字段上
        assertEquals("ARTICLE", log.getTargetType(), "对象类型应当是文章");
        assertEquals(articleId, log.getTargetId(), "应当记的是刚建出来的那篇文章");
        assertEquals(admin.getId(), log.getUserId(), "要记下操作人ID");
        assertEquals(admin.getUsername(), log.getUsername(), "要记下操作人用户名");

        // traceId 是审计表和日志系统之间的那根线：
        // 没有它，这条记录就只是一个孤立的"某年某月有人建了篇文章"，
        // 出事时无法顺着它去查那次请求里到底发生了什么（见 V4 迁移脚本的注释）
        assertNotNull(log.getTraceId(), "应当带上 traceId，否则无法和日志系统对上号");
        assertFalse(log.getTraceId().isBlank(), "traceId 不该是空串");

        assertNotNull(log.getIp(), "应当记下来源IP");
        assertTrue(log.getDetail().contains(title),
                "detail 里应当留有标题快照，实际=" + log.getDetail());
    }

    @Test
    @DisplayName("② 带了 X-Forwarded-For -> 记的是真实客户端IP，而不是 Nginx 的 127.0.0.1")
    void forwardedFor_shouldBeRecordedAsClientIp() throws Exception {
        String title = mark + "_审计-IP";

        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        // 线上就是这个形态：客户端 -> Nginx -> 后端，
                        // Nginx 用 $proxy_add_x_forwarded_for 把真实地址追加在最前面
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(title, "正文", category.getId(), 0)))
                .andExpect(status().isOk());

        OperationLog log = awaitLog("CREATE_ARTICLE", findByTitle(title).getId());
        assertNotNull(log, "应当有审计记录");

        // 【为什么这条用例重要】
        //   不处理这个头的话，线上每条审计记录里的 IP 都是 127.0.0.1（Nginx 自己），
        //   等于这个字段完全没用 —— 而且不会有任何报错，只有真出事时才发现查不出人。
        assertEquals("203.0.113.7", log.getIp(), "应当取 X-Forwarded-For 的第一段（最初的客户端）");
    }

    @Test
    @DisplayName("③ 编辑文章 -> 记下 UPDATE_ARTICLE，detail 里是改完之后的新标题")
    void updateArticle_shouldBeAuditedWithNewTitle() throws Exception {
        String oldTitle = mark + "_审计-改之前";
        Article article = insertArticle(oldTitle, 1);

        String newTitle = mark + "_审计-改之后";
        mockMvc.perform(put("/admin/article/{id}", article.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(newTitle, "改过的正文", category.getId(), 1)))
                .andExpect(status().isOk());

        OperationLog log = awaitLog("UPDATE_ARTICLE", article.getId());
        assertNotNull(log, "编辑文章后应当有一条 UPDATE_ARTICLE 审计记录");
        assertTrue(log.getDetail().contains(newTitle),
                "detail 应当记下改成了什么标题，实际=" + log.getDetail());
    }

    @Test
    @DisplayName("④ 发布 / 下架走同一个接口 -> 用 detail 区分方向，两条都记上")
    void updateArticleStatus_shouldRecordBothDirections() throws Exception {
        Article article = insertArticle(mark + "_审计-发布", 0);

        mockMvc.perform(put("/admin/article/{id}/status", article.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "1"))
                .andExpect(status().isOk());
        assertEquals("发布", awaitLog("UPDATE_ARTICLE_STATUS", article.getId()).getDetail(),
                "发布时 detail 应当是「发布」");

        mockMvc.perform(put("/admin/article/{id}/status", article.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "0"))
                .andExpect(status().isOk());

        // 【为什么要等到"下架"这一条，而不是直接查最新那条】
        //   两条记录同 action 同 targetId，只靠 awaitLog 拿到的可能是第一条。
        //   所以这里等到出现 detail=下架 的那条为止。
        OperationLog offline = awaitLogWithDetail("UPDATE_ARTICLE_STATUS", article.getId(), "下架");
        assertNotNull(offline, "下架也应当留下一笔审计记录（同一个接口，方向不同）");

        // 两次操作 = 两条记录，审计不该把它们合并成一条
        assertEquals(2, countLogs("UPDATE_ARTICLE_STATUS", article.getId()),
                "发布一条、下架一条，应当是两条独立记录");
    }

    @Test
    @DisplayName("⑤ 删除文章 -> 记录里保留标题快照（文章删了之后只能靠它追溯）")
    void deleteArticle_shouldKeepTitleSnapshot() throws Exception {
        String title = mark + "_审计-删除";
        Article article = insertArticle(title, 1);

        mockMvc.perform(delete("/admin/article/{id}", article.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        OperationLog log = awaitLog("DELETE_ARTICLE", article.getId());
        assertNotNull(log, "删除文章后应当有一条 DELETE_ARTICLE 审计记录");

        // 【这条断言是整个审计设计的一个缩影】
        //   文章是逻辑删除（deleted=1），查库虽然还能翻到行，
        //   但接口层已经查不到它了 —— "删的到底是哪一篇"这个问题，
        //   将来只有这条审计记录里的标题快照能回答
        assertTrue(log.getDetail().contains(title),
                "detail 里应当是删除前的标题快照，实际=" + log.getDetail());
    }

    // ================================================================
    //  二、用户类写操作的审计
    // ================================================================

    @Test
    @DisplayName("⑥ 禁用 / 启用用户 -> detail 区分方向，操作人是管理员")
    void updateUserStatus_shouldRecordDirection() throws Exception {
        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "0"))
                .andExpect(status().isOk());

        OperationLog disable = awaitLog("UPDATE_USER_STATUS", guest.getId());
        assertNotNull(disable, "禁用用户后应当有 UPDATE_USER_STATUS 审计记录");
        assertEquals(admin.getUsername(), disable.getUsername(), "操作人应当是发起请求的管理员");
        assertTrue(disable.getDetail().contains("禁用") && disable.getDetail().contains(guest.getUsername()),
                "detail 应当写清方向（禁用）和对象（用户名），实际=" + disable.getDetail());

        mockMvc.perform(put("/user/{id}/status", guest.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "1"))
                .andExpect(status().isOk());

        OperationLog enable = awaitLogWithDetail("UPDATE_USER_STATUS", guest.getId(), "启用");
        assertNotNull(enable, "启用也应当被记下来");
    }

    @Test
    @DisplayName("⑦ 改角色 -> detail 记下「从什么改成什么」（只记结果看不出是提权还是降权）")
    void updateUserRole_shouldRecordOldAndNewRole() throws Exception {
        mockMvc.perform(put("/user/{id}/role", guest.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .param("role", "ADMIN"))
                .andExpect(status().isOk());

        OperationLog log = awaitLog("UPDATE_USER_ROLE", guest.getId());
        assertNotNull(log, "改角色后应当有 UPDATE_USER_ROLE 审计记录");

        String detail = log.getDetail();
        assertTrue(detail.contains("GUEST") && detail.contains("ADMIN"),
                "detail 应当同时留下修改前后的角色，实际=" + detail);
    }

    @Test
    @DisplayName("⑧ 删除用户 -> 审计里是【改写之前】的用户名（改写后就追不回是谁了）")
    void deleteUser_shouldKeepOriginalUsernameSnapshot() throws Exception {
        String originalUsername = guest.getUsername();

        mockMvc.perform(delete("/user/{id}", guest.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        OperationLog log = awaitLog("DELETE_USER", guest.getId());
        assertNotNull(log, "删除用户后应当有 DELETE_USER 审计记录");

        // 【这条断言守的是本项目的一个特殊设计】
        //   用户的逻辑删除会把 username 改写成 "原名#deleted#id"（为了释放用户名），
        //   所以 user 表里已经查不到"原来叫什么"了。
        //   审计记录必须留下【改写之前】的名字，否则过两天出问题，
        //   你看到 target_id=37 却永远不知道 37 是谁
        assertTrue(log.getDetail().contains(originalUsername),
                "detail 里应当是删除前的用户名，实际=" + log.getDetail());
        assertFalse(log.getDetail().contains("#deleted#"),
                "记的应当是删改之前的原名，不该把改写后的名字当成人名记下来");

        // 【顺带证明改写确实发生过】
        //   不查这一下的话，上面两条断言有可能只是"巧合地都对"：
        //   万一日后删除逻辑不再改写用户名，这条用例会继续绿，
        //   却再也证明不了"审计快照是唯一的追溯手段"。
        String rawUsername = jdbcTemplate.queryForObject(
                "SELECT username FROM user WHERE id = ?", String.class, guest.getId());
        assertNotNull(rawUsername, "逻辑删除只是 UPDATE，行还在表里");
        assertEquals(originalUsername + "#deleted#" + guest.getId(), rawUsername,
                "删除时应当把用户名改写掉（为了释放用户名给以后注册的人用）");
    }

    @Test
    @DisplayName("⑨ 重置密码 -> 有记录，但【整行都不含】明文密码")
    void resetPassword_shouldNotLeakPasswordIntoAudit() throws Exception {
        // 【密码为什么写成固定短串，而不是像别处那样拼 mark】
        //   密码有 @Size(min = 6, max = 20) 的长度校验（DTO 与 Service 各一道）。
        //   第一版拼了 mark（"Secret-" + 纳秒时间戳，快 30 位），
        //   请求在【参数校验】就被拦下了 —— 接口照样返回 HTTP 200
        //   （业务错误是 HTTP 200 + body.code，见「统一返回与错误处理」），
        //   于是测试红在"查不到审计记录"上，看起来像审计没生效，
        //   实际是这条用例压根没走到 Service。
        String newPassword = "Audit-pw1";

        mockMvc.perform(put("/user/{id}/password", guest.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk());

        OperationLog log = awaitLog("RESET_USER_PASSWORD", guest.getId());
        assertNotNull(log, "重置密码后应当有 RESET_USER_PASSWORD 审计记录");

        // 【为什么要把整行拼成一个字符串来找密码】
        //   密码可能泄露到 detail、也可能被谁顺手加进 username 或别的列。
        //   逐字段断言只能挡住"我已经想到的那一种"，
        //   把整行拿出来查，才是"这一行里没有它"这个结论
        String row = jdbcTemplate.queryForObject(
                "SELECT CONCAT_WS('|', user_id, username, action, target_type, target_id, detail, ip, trace_id)"
                        + " FROM operation_log WHERE id = ?",
                String.class, log.getId());

        assertNotNull(row, "应当能取到刚写进去的那一行");
        assertFalse(row.contains(newPassword),
                "审计表是长期保留的，绝不能出现明文密码，实际行内容=" + row);

        // 反向确认这条用例不是"空跑"：记录确实记下了"谁重置了谁的密码"
        assertTrue(log.getDetail().contains(guest.getUsername()),
                "detail 应当记下被重置密码的是哪个账号，实际=" + log.getDetail());
    }

    @Test
    @DisplayName("⑫ 标签的增 / 改 / 删也都会留痕，删除记录里保留标签名快照")
    void tagOperations_shouldBeAudited() throws Exception {
        // 【为什么这条用例在本类、而不在 TagTest 里】
        //   审计是 @TransactionalEventListener(AFTER_COMMIT) 才落库的，
        //   而 TagTest 整体是 @Transactional（跑完回滚）—— 事务永远不提交，
        //   事件会被直接丢弃，那边断言"查得到审计"必然失败。
        //   本类是 NOT_SUPPORTED（让业务真的提交），所以审计相关的断言都放这里。
        String firstName = mark + "-标签甲";

        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + firstName + "\",\"sort\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Tag created = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, firstName));
        assertNotNull(created, "前置条件：标签应当建出来了");

        OperationLog createLog = awaitLog("CREATE_TAG", created.getId());
        assertNotNull(createLog, "新建标签应当留下 CREATE_TAG 审计");
        assertEquals(admin.getUsername(), createLog.getUsername(), "要记下是谁建的");
        assertTrue(createLog.getDetail().contains(firstName),
                "detail 里要有标签名，实际=" + createLog.getDetail());

        // ---- 改名 ----
        String secondName = mark + "-标签乙";
        mockMvc.perform(put("/admin/tag/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + secondName + "\",\"sort\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog updateLog = awaitLog("UPDATE_TAG", created.getId());
        assertNotNull(updateLog, "编辑标签应当留下 UPDATE_TAG 审计");
        // 只记新名字的话，事后看不出"这是一次改名"还是别的调整
        assertTrue(updateLog.getDetail().contains(firstName) && updateLog.getDetail().contains(secondName),
                "detail 里应当同时有旧名和新名，实际=" + updateLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/tag/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_TAG", created.getId());
        assertNotNull(deleteLog, "删除标签应当留下 DELETE_TAG 审计");
        // 【这条断言是本用例存在的主要理由】标签是【物理删除】——
        // 删完之后 tag 表里再也查不到这个名字，只有审计记录能回答
        // "当时删掉的到底是哪个标签"。这也是"物理删除"能被接受的前提：
        // 删除这件事本身并没有失去痕迹。
        assertTrue(deleteLog.getDetail().contains(secondName),
                "删除记录里必须保留标签名快照，实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑬ 评论的「审核」与「删除」都会留痕，但【发表评论本身不记】")
    void commentModeration_shouldBeAudited() throws Exception {
        // 本类里的 insertArticle 返回的是实体（其它用例要拿标题做断言），这里取 id
        Article auditArticle = insertArticle(mark + "_评论审计", 1);
        Long articleId = auditArticle.getId();

        // ---- 发表评论（游客身份，不需要 token）----
        String nickname = mark + "-读者";
        String created = mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":" + articleId + ",\"nickname\":\"" + nickname
                                + "\",\"content\":\"这条评论只用来验审计\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        Long commentId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).get("data").get("id").asLong();

        // 【发表评论本身不写审计】这是刻意的：评论是内容，数量会持续增长，
        //   把每条公开评论都塞进审计表只会让真正要追溯的"管理动作"被淹没。
        //   评论的记录就在 comment 表里 —— 下面两条管理动作才需要留痕。
        assertEquals(0, countLogs("CREATE_COMMENT", commentId),
                "发表评论不该写审计（评论本身就是内容，不是管理动作）");

        // ---- 审核通过 ----
        mockMvc.perform(put("/admin/comment/{id}/status", commentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog approveLog = awaitLog("UPDATE_COMMENT_STATUS", commentId);
        assertNotNull(approveLog, "审核评论应当留下审计");
        assertEquals(admin.getUsername(), approveLog.getUsername(), "要记下是谁审核的");
        // detail 里要能看出"从什么状态改成什么"：
        // 只记结果值的话，事后分不清这是"通过了一条待审核"还是"把已通过的又拒了"
        assertTrue(approveLog.getDetail().contains("待审核") && approveLog.getDetail().contains("已通过"),
                "detail 里应当有状态变化，实际=" + approveLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/comment/{id}", commentId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_COMMENT", commentId);
        assertNotNull(deleteLog, "删除评论应当留下审计");
        // 评论被逻辑删除后按 id 已经查不到内容，而"删掉的是一句什么话"
        // 恰恰是事后最需要回答的问题（用户来问"我的评论怎么没了"）
        assertTrue(deleteLog.getDetail().contains(nickname),
                "删除记录里应当有内容快照（昵称），实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑭ 分类的增 / 改 / 删也都会留痕，删除记录里保留分类名快照")
    void categoryOperations_shouldBeAudited() throws Exception {
        String firstName = mark + "-分类甲";

        mockMvc.perform(post("/admin/category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + firstName + "\",\"sort\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Category created = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, firstName));
        assertNotNull(created, "前置条件：分类应当建出来了");

        OperationLog createLog = awaitLog("CREATE_CATEGORY", created.getId());
        assertNotNull(createLog, "新建分类应当留下审计");
        assertTrue(createLog.getDetail().contains(firstName),
                "detail 里要有分类名，实际=" + createLog.getDetail());

        // ---- 改名 ----
        String secondName = mark + "-分类乙";
        mockMvc.perform(put("/admin/category/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + secondName + "\",\"sort\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog updateLog = awaitLog("UPDATE_CATEGORY", created.getId());
        assertNotNull(updateLog, "编辑分类应当留下审计");
        assertTrue(updateLog.getDetail().contains(firstName) && updateLog.getDetail().contains(secondName),
                "detail 里应当同时有旧名和新名，实际=" + updateLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/category/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_CATEGORY", created.getId());
        assertNotNull(deleteLog, "删除分类应当留下审计");
        // 分类删除时会把名字改写成 原名#deleted#id（为了释放唯一索引），
        // 所以从 category 表里已经看不出它原来叫什么 —— 只有审计记录能回答
        assertTrue(deleteLog.getDetail().contains(secondName),
                "删除记录里必须保留分类名快照，实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑮ 友链的增 / 改 / 删也都会留痕，删除记录里保留站点名快照")
    void linkOperations_shouldBeAudited() throws Exception {
        // 【为什么友链（以及接下来的项目 / 收藏 / 关于）的审计断言也在本类】
        //   审计是 @TransactionalEventListener(AFTER_COMMIT) 才落库的，
        //   而 FriendLinkTest 整体是 @Transactional（跑完回滚）—— 事务永远不提交，
        //   事件会被丢弃，那边断言"查得到审计"必然失败。
        //   那属于"测试环境的事务语义"，不是功能坏了。
        String firstName = mark + "-友链甲";

        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(firstName, "https://example.com/link-a", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        FriendLink created = friendLinkMapper.selectOne(new LambdaQueryWrapper<FriendLink>()
                .eq(FriendLink::getName, firstName));
        assertNotNull(created, "前置条件：友链应当建出来了");

        OperationLog createLog = awaitLog("CREATE_LINK", created.getId());
        assertNotNull(createLog, "新建友链应当留下 CREATE_LINK 审计");
        // 对象类型是 LINK：按 target_type + target_id 查"这条友链被改动过几次"，
        // 比拿 action 去 LIKE '%LINK%' 猜名字可靠得多（AuditTarget 的类注释里写着这件事）
        assertEquals("LINK", createLog.getTargetType(), "对象类型应当是 LINK");
        assertEquals(admin.getId(), createLog.getUserId(), "要记下操作人ID");
        assertEquals(admin.getUsername(), createLog.getUsername(), "要记下是谁建的");
        assertTrue(createLog.getDetail().contains(firstName),
                "detail 里要有站点名，实际=" + createLog.getDetail());

        // ---- 改名 ----
        String secondName = mark + "-友链乙";
        mockMvc.perform(put("/admin/link/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(secondName, "https://example.com/link-b", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog updateLog = awaitLog("UPDATE_LINK", created.getId());
        assertNotNull(updateLog, "编辑友链应当留下 UPDATE_LINK 审计");
        assertTrue(updateLog.getDetail().contains(firstName) && updateLog.getDetail().contains(secondName),
                "detail 里应当同时有旧名和新名（只记新名字的话，事后看不出这是一次改名），实际="
                        + updateLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/link/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_LINK", created.getId());
        assertNotNull(deleteLog, "删除友链应当留下 DELETE_LINK 审计");
        // 友链是【逻辑删除】：行虽然还在表里，但所有走 Mapper 的查询都看不到了
        // （@TableLogic 会自动加 deleted = 0）——"删掉的是哪个站点"这个问题，
        // 在不专门去翻原生 SQL 的情况下，只有这条审计记录能回答
        assertTrue(deleteLog.getDetail().contains(secondName),
                "删除记录里必须保留站点名快照，实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑯ 项目的增 / 改 / 删也都会留痕，审计对象类型是 PROJECT")
    void projectOperations_shouldBeAudited() throws Exception {
        String firstName = mark + "-项目甲";

        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(firstName, "https://example.com/p1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Project created = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getName, firstName));
        assertNotNull(created, "前置条件：项目应当建出来了");

        OperationLog createLog = awaitLog("CREATE_PROJECT", created.getId());
        assertNotNull(createLog, "新建项目应当留下 CREATE_PROJECT 审计");
        assertEquals("PROJECT", createLog.getTargetType(), "对象类型应当是 PROJECT");
        assertTrue(createLog.getDetail().contains(firstName),
                "detail 里要有项目名，实际=" + createLog.getDetail());

        // ---- 改名 ----
        String secondName = mark + "-项目乙";
        mockMvc.perform(put("/admin/project/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(secondName, "https://example.com/p2", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog updateLog = awaitLog("UPDATE_PROJECT", created.getId());
        assertNotNull(updateLog, "编辑项目应当留下 UPDATE_PROJECT 审计");
        assertTrue(updateLog.getDetail().contains(firstName) && updateLog.getDetail().contains(secondName),
                "detail 里应当同时有旧名和新名，实际=" + updateLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/project/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_PROJECT", created.getId());
        assertNotNull(deleteLog, "删除项目应当留下 DELETE_PROJECT 审计");
        assertTrue(deleteLog.getDetail().contains(secondName),
                "删除记录里必须保留项目名快照，实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑰ 收藏的增 / 改 / 删也都会留痕，审计对象类型是 FAVORITE")
    void favoriteOperations_shouldBeAudited() throws Exception {
        String firstTitle = mark + "-收藏甲";

        mockMvc.perform(post("/admin/favorite")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(firstTitle, "https://example.com/fav-1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Favorite created = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getTitle, firstTitle));
        assertNotNull(created, "前置条件：收藏应当建出来了");

        OperationLog createLog = awaitLog("CREATE_FAVORITE", created.getId());
        assertNotNull(createLog, "新建收藏应当留下 CREATE_FAVORITE 审计");
        assertEquals("FAVORITE", createLog.getTargetType(), "对象类型应当是 FAVORITE");
        assertTrue(createLog.getDetail().contains(firstTitle),
                "detail 里要有标题，实际=" + createLog.getDetail());

        // ---- 改名 ----
        String secondTitle = mark + "-收藏乙";
        mockMvc.perform(put("/admin/favorite/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(favoriteJson(secondTitle, "https://example.com/fav-2", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog updateLog = awaitLog("UPDATE_FAVORITE", created.getId());
        assertNotNull(updateLog, "编辑收藏应当留下 UPDATE_FAVORITE 审计");
        assertTrue(updateLog.getDetail().contains(firstTitle) && updateLog.getDetail().contains(secondTitle),
                "detail 里应当同时有旧标题和新标题，实际=" + updateLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/favorite/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_FAVORITE", created.getId());
        assertNotNull(deleteLog, "删除收藏应当留下 DELETE_FAVORITE 审计");
        assertTrue(deleteLog.getDetail().contains(secondTitle),
                "删除记录里必须保留标题快照，实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑱ 关于页的保存会留痕：对象类型 ABOUT、target_id 恒为 1")
    void aboutUpdate_shouldBeAudited() throws Exception {
        String nickname = mark + "-关于页昵称";

        mockMvc.perform(put("/admin/about")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"bio\":\"审计用的自我介绍\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog log = awaitLog("UPDATE_ABOUT", About.SINGLE_ROW_ID);
        assertNotNull(log, "保存关于页应当留下 UPDATE_ABOUT 审计");

        // 【关于页的 target_id 恒为 1】它是全站唯一一份单条数据，
        // 所以按 target_type = 'ABOUT' 查就能得到"关于页被谁改过几次"的完整历史，
        // 连 id 条件都不用加 —— 和"按 target_type + target_id 查某条友链"的用法形成对照
        assertEquals("ABOUT", log.getTargetType(), "对象类型应当是 ABOUT");
        assertEquals(About.SINGLE_ROW_ID, log.getTargetId(), "单条记录的 target_id 恒为 1");
        assertEquals(admin.getUsername(), log.getUsername(), "要记下是谁保存的");
        // detail 里记昵称：关于页没有标题这类标识字段，昵称是最能指认它的东西
        assertTrue(log.getDetail().contains(nickname),
                "detail 里应当有昵称，实际=" + log.getDetail());
    }

    @Test
    @DisplayName("⑲ 音乐的增 / 改 / 删也都会留痕，审计对象类型是 MUSIC")
    void musicOperations_shouldBeAudited() throws Exception {
        // 【顺带说明：音乐比另外几个内容模块多一个"文件"】
        //   本用例只走增删改接口，不上传文件 —— 因为审计记的是"谁改了哪条记录"，
        //   而音频文件本身由 upload 包负责落盘（那部分的断言在 UploadAdminTest ⑬）。
        //   提交一个 url 就足以覆盖审计要证明的事。
        String firstTitle = mark + "-音乐甲";

        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(firstTitle, "https://example.com/music-a.mp3", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Music created = musicMapper.selectOne(new LambdaQueryWrapper<Music>()
                .eq(Music::getTitle, firstTitle));
        assertNotNull(created, "前置条件：音乐应当建出来了");

        OperationLog log = awaitLog("CREATE_MUSIC", created.getId());
        assertNotNull(log, "新建音乐应当留下 CREATE_MUSIC 审计");
        // 对象类型是 MUSIC：按 target_type + target_id 查"这首曲子被改动过几次 / 什么时候被删的"
        assertEquals("MUSIC", log.getTargetType(), "对象类型应当是 MUSIC");
        assertEquals(admin.getId(), log.getUserId(), "要记下操作人ID");
        assertEquals(admin.getUsername(), log.getUsername(), "要记下是谁建的");
        assertTrue(log.getDetail().contains(firstTitle),
                "detail 里要有曲名，实际=" + log.getDetail());

        // ---- 改名 ----
        String secondTitle = mark + "-音乐乙";
        mockMvc.perform(put("/admin/music/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(secondTitle, "https://example.com/music-b.mp3", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog updateLog = awaitLog("UPDATE_MUSIC", created.getId());
        assertNotNull(updateLog, "编辑音乐应当留下 UPDATE_MUSIC 审计");
        assertTrue(updateLog.getDetail().contains(firstTitle) && updateLog.getDetail().contains(secondTitle),
                "detail 里应当同时有旧曲名和新曲名（只记新名字的话，事后看不出这是一次改名），实际="
                        + updateLog.getDetail());

        // ---- 删除 ----
        mockMvc.perform(delete("/admin/music/{id}", created.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog deleteLog = awaitLog("DELETE_MUSIC", created.getId());
        assertNotNull(deleteLog, "删除音乐应当留下 DELETE_MUSIC 审计");
        // 音乐是【逻辑删除】：行虽然还在表里，但所有走 Mapper 的查询都看不到了
        // （@TableLogic 会自动加 deleted = 0）——"删掉的是哪一首"这个问题，
        // 在不专门去翻原生 SQL 的情况下，只有这条审计记录能回答。
        // ⚠️ 另注：磁盘上的音频文件是删还是不删，取决于"有没有别处还在引用它"
        //    （见 MusicServiceImpl.delete），所以这条记录只说明"这首歌被删了"，
        //    不能当成"文件已被清理"的凭据 —— 那件事由 MusicFileCleanupTest 覆盖。
        assertTrue(deleteLog.getDetail().contains(secondTitle),
                "删除记录里必须保留曲名快照，实际=" + deleteLog.getDetail());
    }

    @Test
    @DisplayName("⑳ 站点设置的保存会留痕：对象类型 SETTING、target_id 恒为 1，且 detail 里带上了评论开关")
    void settingUpdate_shouldBeAudited() throws Exception {
        String siteName = mark + "-审计站点名";

        // 特意在这次保存里【把评论关掉】：这个动作影响所有访客，
        // 而事后翻审计时最需要一眼看出"那次保存有没有顺手关掉评论"
        mockMvc.perform(put("/admin/setting")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteName\":\"" + siteName + "\",\"commentEnabled\":false,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog log = awaitLog("UPDATE_SETTING", SiteSetting.SINGLE_ROW_ID);
        assertNotNull(log, "保存站点设置应当留下 UPDATE_SETTING 审计");

        // 【与 ⑱ 关于页同一个用法】站点设置也是"只有一行"，
        // 所以按 target_type = 'SETTING' 查就能得到完整历史，连 id 条件都不用加
        assertEquals("SETTING", log.getTargetType(), "对象类型应当是 SETTING");
        assertEquals(SiteSetting.SINGLE_ROW_ID, log.getTargetId(), "单条记录的 target_id 恒为 1");
        assertEquals(admin.getUsername(), log.getUsername(), "要记下是谁保存的");
        // detail 里记站点名（最能指认"这是哪个站的设置"）与评论开关的状态
        assertTrue(log.getDetail().contains(siteName),
                "detail 里应当有站点名，实际=" + log.getDetail());
        assertTrue(log.getDetail().contains("评论关闭"),
                "detail 里应当写明评论被关掉了 —— 它是会影响到所有访客的开关，"
                        + "只记站点名的话事后看不出这次保存改变了什么。实际=" + log.getDetail());
    }

    // ================================================================
    //  三、"不该记的绝不记"
    // ================================================================
    @Test
    @DisplayName("⑩ 业务失败（分类不存在）-> 不产生审计记录")
    void failedOperation_shouldNotBeAudited() throws Exception {
        // 分类 999999 不存在，Service 抛 CATEGORY_NOT_FOUND（code 404），事务回滚。
        // 【注意状态码】：业务错误走的是"HTTP 200 + body.code"，
        // 所以这里断言的是 200 里面的 code 字段，而不是 HTTP 状态码本身
        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(mark + "_审计-失败", "正文", 999999L, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        assertNoLogForOperatorWithin(admin.getUsername(), 800);

        // 库里也不该多出文章 —— 说明失败发生在写库之前或之后被回滚了
        assertEquals(0, articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .like(Article::getTitle, mark + "_审计-失败")), "失败的操作不该留下文章");
    }

    @Test
    @DisplayName("⑪ 事务回滚 -> 事件已发出也不记账；同一段代码提交了就会记账")
    void rolledBackTransaction_shouldNotBeAudited() throws Exception {
        String rolledBackTitle = mark + "_审计-回滚";
        String committedTitle = mark + "_审计-已提交";

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        // 【第一步：制造一次"业务先成功、最后却回滚"】
        //   这里刻意用 TransactionTemplate 包住 Service 调用，
        //   让操作发生在【我控制得住的那个事务】里，
        //   最后 setRollbackOnly 模拟"业务走到最后一步失败了"。
        //   注意：此刻事件【已经发布了】，审计功能到底会不会记账，就靠这条用例说话。
        template.execute(status -> {
            articleService.create(form(rolledBackTitle, category.getId()), admin.getId());
            status.setRollbackOnly();
            return null;
        });

        // 【第二步：对照组】
        //   同一段代码、同样的调用方式，只是这次让它正常提交。
        //   有这一步，上面那条"没记账"才排得掉另一种解释：
        //   "其实审计根本没写进去，和回滚没关系"。
        Long committedId = articleService.create(form(committedTitle, category.getId()), admin.getId());

        OperationLog committed = awaitLog("CREATE_ARTICLE", committedId);
        assertNotNull(committed, "正常提交的创建应当被记账（对照组，证明审计本身是好的）");

        // 【核心断言：回滚的那一次没有留下任何痕迹】
        //   注意这里查的是"有没有任何一条记录提到这个标题"——
        //   detail 里会带标题，所以即使记录以任何形式落库都会被这条断言抓住
        assertEquals(0, articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                        .eq(Article::getTitle, rolledBackTitle)),
                "事务回滚了，文章本身不该留在库里");
        assertEquals(0, countLogsByDetailLike(rolledBackTitle),
                "事务回滚了，审计记录也不该留下 —— '没真正发生的事不该被记下来'");
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /**
     * 轮询等待一条审计记录出现。
     *
     * 【为什么必须轮询：@Async 决定的】
     *   监听器在另一个线程上落库，请求（或 Service 调用）返回时它可能还没跑完。
     *   直接查库会得到"偶发查不到"，而这类随机红是最伤测试信誉的。
     *   轮询等到超时：正常几毫秒就命中，真的没写才会等到超时后报错。
     *
     * 【为什么不用 Thread.sleep 一个固定值】
     *   固定睡眠要么太短（仍然是随机红）、要么太长（每次跑测试都白等）。
     *   轮询是"够快就立刻返回、没写才等到超时"。
     */
    private OperationLog awaitLog(String action, Long targetId) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            OperationLog log = latestLog(action, targetId);
            if (log != null) {
                return log;
            }
            sleep(50);
        }
        return null;
    }

    /** 等到出现一条 detail 里包含指定内容的记录（同一个接口有多个方向时用） */
    private OperationLog awaitLogWithDetail(String action, Long targetId, String detailPart) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (OperationLog log : logs(action, targetId)) {
                if (log.getDetail() != null && log.getDetail().contains(detailPart)) {
                    return log;
                }
            }
            sleep(50);
        }
        return null;
    }

    /**
     * 等一小会儿，确认这期间【始终没有】出现该操作人的审计记录。
     *
     * 【"断言没有发生"这件事为什么也要等】
     *   不能"请求一返回就查库说没有" —— 异步落库还没跑完呢，这时候查当然是空的，
     *   断言会假绿（测试通过，但功能其实是坏的）。
     *   所以必须给足时间让它有机会写，再断言"它始终没写"。
     */
    private void assertNoLogForOperatorWithin(String username, long ms) {
        sleep(ms);
        assertEquals(0, operationLogMapper.selectCount(new LambdaQueryWrapper<OperationLog>()
                        .eq(OperationLog::getUsername, username)),
                "失败的操作不该在审计表里留下记录，操作人=" + username);
    }

    private OperationLog latestLog(String action, Long targetId) {
        return operationLogMapper.selectOne(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getAction, action)
                .eq(OperationLog::getTargetId, targetId)
                .orderByDesc(OperationLog::getId)
                .last("LIMIT 1"));
    }

    private java.util.List<OperationLog> logs(String action, Long targetId) {
        return operationLogMapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getAction, action)
                .eq(OperationLog::getTargetId, targetId));
    }

    private long countLogs(String action, Long targetId) {
        return operationLogMapper.selectCount(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getAction, action)
                .eq(OperationLog::getTargetId, targetId));
    }

    private long countLogsByDetailLike(String part) {
        return operationLogMapper.selectCount(new LambdaQueryWrapper<OperationLog>()
                .like(OperationLog::getDetail, part));
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);   // 用户名有唯一索引，必须带标记
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("审计测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    private Article insertArticle(String title, int status) {
        Article a = new Article();
        a.setTitle(title);
        a.setSummary("审计测试摘要");
        a.setContent("# 审计测试\n\n正文");
        a.setCategoryId(category.getId());
        a.setStatus(status);
        a.setIsTop(0);
        a.setViewCount(0);
        a.setAuthorId(admin.getId());
        a.setDeleted(0);
        articleMapper.insert(a);
        return a;
    }

    private Article findByTitle(String title) {
        return articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getTitle, title));
    }

    /** 构造提交表单（走 Service 直接调用时用，见第⑪条） */
    private ArticleForm form(String title, Long categoryId) {
        ArticleForm form = new ArticleForm();
        form.setTitle(title);
        form.setContent("正文");
        form.setCategoryId(categoryId);
        form.setStatus(0);
        return form;
    }

    /** 构造"新建 / 编辑友链"的 JSON 请求体（见第⑮条） */
    private String linkJson(String name, String url, Object sort) {
        return """
                {
                  "name": "%s",
                  "url": "%s",
                  "sort": %s
                }
                """.formatted(name, url, sort);
    }

    /** 构造"新建 / 编辑项目"的 JSON 请求体（见第⑯条） */
    private String projectJson(String name, String repo, Object sort) {
        return """
                {
                  "name": "%s",
                  "repo": "%s",
                  "sort": %s
                }
                """.formatted(name, repo, sort);
    }

    /** 构造"新建 / 编辑收藏"的 JSON 请求体（见第⑰条） */
    private String favoriteJson(String title, String url, Object sort) {
        return """
                {
                  "title": "%s",
                  "url": "%s",
                  "sort": %s
                }
                """.formatted(title, url, sort);
    }

    /** 构造"新建 / 编辑音乐"的 JSON 请求体（见第⑲条） */
    private String musicJson(String title, String url, Object sort) {
        return """
                {
                  "title": "%s",
                  "url": "%s",
                  "sort": %s
                }
                """.formatted(title, url, sort);
    }

    /** 构造"新建/编辑文章"的 JSON 请求体 */
    private String articleJson(String title, String content, Object categoryId, Object status) {        return """
                {
                  "title": "%s",
                  "content": "%s",
                  "categoryId": %s,
                  "status": %s
                }
                """.formatted(title, content, categoryId, status);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 恢复中断标记再抛出：吞掉 InterruptedException 会让上层失去"该停了"的信号
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待审计落库时被中断", e);
        }
    }
}
