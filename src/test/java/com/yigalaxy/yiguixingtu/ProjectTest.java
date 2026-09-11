package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.project.dto.ProjectVO;
import com.yigalaxy.yiguixingtu.project.entity.Project;
import com.yigalaxy.yiguixingtu.project.mapper.ProjectMapper;
import com.yigalaxy.yiguixingtu.project.service.ProjectService;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
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
 * 项目模块测试（ProjectController / AdminProjectController / ProjectServiceImpl）
 *
 * 【它和 FriendLinkTest 的同与不同】
 *   同：前台只含显示中的、排序 sort + id 双段稳定、逻辑删除、缓存命中与失效、
 *       权限 401/403、"清空可选字段真的写成 NULL"、sort/status 缺省行为。
 *       这些是 F5 四个内容模块共有的形状，每个模块各测一遍 ——
 *       因为它们是【各自实现】的（三个 Service 三个 Mapper），
 *       共用模板不等于共用代码，不测就等于没测。
 *   不同：本项目特有的一条业务规则 ——
 *       **url（在线地址）与 repo（仓库地址）可以各为空，但不能同时为空**。
 *       它跨两个字段，是唯一一条 Bean Validation 表达不了的规则，
 *       所以既测"两个都空被拒"，也测"只有仓库 / 只有在线地址都是合法的"，
 *       外加一条"编辑时把两个都清空也会被拒，且库里的旧值分毫未动"。
 *
 * 【审计断言不在这里】理由见 FriendLinkTest 的类注释：
 *   本类整体 @Transactional（跑完回滚），而审计是 AFTER_COMMIT 才落库的，
 *   事件永远等不到提交。项目的审计在 OperationLogTest 第⑯条验证。
 * =====================================================================
 */
class ProjectTest extends AbstractIntegrationTest {

    /** 前台列表缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String PROJECT_CACHE_PREFIX = RedisConfig.CACHE_PROJECT_LIST + ":v1:";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ContentCacheVersion contentCacheVersion;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String mark;
    private User admin;
    private String adminToken;
    private String guestToken;

    @BeforeEach
    void setUp() {
        mark = "P" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_project", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        // 真的插一个 GUEST 用户：身份以【数据库里的角色】为准，给 ADMIN 签 GUEST token
        // 他依然是 ADMIN（这个坑在 CommentTest / CategoryAdminTest 里都有记录）
        User guest = insertUser("test_guest_project", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearProjectCache();
    }

    @AfterEach
    void tearDown() {
        clearProjectCache();
    }

    private void clearProjectCache() {
        Set<String> keys = redis.keys(PROJECT_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、前台公开列表
    // ================================================================

    @Test
    @DisplayName("① 游客不登录就能拿项目列表，且【隐藏的那些不会出现】")
    void publicList_withoutToken_shouldExcludeHiddenProjects() throws Exception {
        Project visible = insertProject("显示的项目", 1, Project.STATUS_VISIBLE);
        Project hidden = insertProject("隐藏的项目", 2, Project.STATUS_HIDDEN);

        mockMvc.perform(get("/project/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        assertNotNull(findVisibleByName(visible.getName()), "显示中的项目应当在前台列表里");
        assertNull(findVisibleByName(hidden.getName()),
                "隐藏的项目不该出现在前台列表里（过滤写在 SQL 里，不是交给前端）");
    }

    @Test
    @DisplayName("② 排序：先按 sort 升序；sort 相同时按 id 升序（顺序必须稳定）")
    void publicList_shouldOrderBySortThenId() {
        Project first = insertProject("同序甲", 5, Project.STATUS_VISIBLE);
        Project second = insertProject("同序乙", 5, Project.STATUS_VISIBLE);
        Project head = insertProject("排最前", 1, Project.STATUS_VISIBLE);

        List<String> names = projectService.listVisible().stream()
                .map(ProjectVO::getName)
                .toList();

        // 先确认三条都在：indexOf 找不到时返回 -1，而 -1 比任何下标都小，
        // 少了这条前置断言，下面两条排序断言在"数据根本没查出来"时也会绿
        assertTrue(names.contains(head.getName()) && names.contains(first.getName())
                        && names.contains(second.getName()),
                "前置条件：三条都应当出现在前台列表里，实际=" + names);

        assertTrue(names.indexOf(head.getName()) < names.indexOf(first.getName()), "sort 小的在前");
        assertTrue(names.indexOf(first.getName()) < names.indexOf(second.getName()),
                "sort 相同的按 id 升序（先插的在前）");
    }

    @Test
    @DisplayName("③ 前台列表带齐渲染卡片需要的字段（简介 / 在线地址 / 仓库 / 封面 / 技术栈）")
    void publicList_shouldCarryFieldsForRendering() throws Exception {
        String name = "字段齐全" + mark;
        // 走接口新建：这样字段是一次性全填满的（比直接写库更能证明"提交什么就返回什么"）
        Long id = createViaApi(projectJson(name, "简介文本", "https://example.com/demo",
                "https://github.com/example/repo", "/cover-1.png", "Spring Boot,MySQL,Redis", 1, 1));

        String body = mockMvc.perform(get("/project/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode found = findNodeByName(body, name);
        assertNotNull(found, "响应里应当能找到刚建的项目");

        assertEquals("简介文本", found.path("description").asText());
        assertEquals("https://example.com/demo", found.path("url").asText());
        assertEquals("https://github.com/example/repo", found.path("repo").asText());
        assertEquals("/cover-1.png", found.path("cover").asText());
        // 技术栈原样返回逗号分隔的字符串（不在后端拆数组，理由见 ProjectForm 的注释）——
        // 前端 split(',') 就能渲染成一行小标签
        assertEquals("Spring Boot,MySQL,Redis", found.path("tech").asText());
        assertEquals(1, found.path("status").asInt());
        assertTrue(found.hasNonNull("createTime"), "创建时间要在响应里");
        assertEquals(id, found.path("id").asLong(), "返回的 id 应当就是新建出来的那个");
    }

    // ================================================================
    //  二、后台新建（正常路径 + 边界）
    // ================================================================

    @Test
    @DisplayName("④ 新建项目 -> 200，库里真有这行（用原生 SQL 逐字段核对）")
    void createProject_withValidForm_shouldPersistToDatabase() throws Exception {
        String name = "新项目" + mark;

        Long id = createViaApi(projectJson(name, "项目简介", "https://example.com/app",
                "https://github.com/example/app", "https://example.com/cover.png",
                "Java,Spring Boot", 3, 1));

        // 断言落到数据库：走 Mapper 也能看到数据，但它带着 @TableLogic 过滤与字段映射
        // 两层加工，直查原生列才是"这一行到底存了什么"的权威答案（README 测试约定第 1 条）
        assertEquals(name, rawValue(id, "name", String.class));
        assertEquals("项目简介", rawValue(id, "description", String.class));
        assertEquals("https://example.com/app", rawValue(id, "url", String.class));
        assertEquals("https://github.com/example/app", rawValue(id, "repo", String.class));
        assertEquals("https://example.com/cover.png", rawValue(id, "cover", String.class));
        assertEquals("Java,Spring Boot", rawValue(id, "tech", String.class));
        assertEquals(3, rawValue(id, "sort", Integer.class));
        assertEquals(Project.STATUS_VISIBLE, rawValue(id, "status", Integer.class));
        assertEquals(0, rawValue(id, "deleted", Integer.class), "新建的行 deleted 必须是 0");
    }

    @Test
    @DisplayName("⑤ 可选字段不传时存 NULL；sort 默认 0、status 默认 1（与 tag/category 一致）")
    void createProject_withoutOptionalFields_shouldUseDefaults() throws Exception {
        String name = "默认值" + mark;

        // 只传必填的名称 + 一个地址（在线/仓库至少要有一个）
        String body = mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"repo\":\"https://github.com/example/only-repo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
        assertEquals(0, rawValue(id, "sort", Integer.class), "不传 sort 应当按 0 处理");
        assertEquals(Project.STATUS_VISIBLE, rawValue(id, "status", Integer.class),
                "不传 status 应当按 1(显示) —— 管理员自己录的项目，录完就是要展示的");
        assertNull(rawValue(id, "description", String.class), "不传简介应当存 NULL");
        assertNull(rawValue(id, "cover", String.class), "不传封面应当存 NULL");
        assertNull(rawValue(id, "tech", String.class), "不传技术栈应当存 NULL");
        assertNull(rawValue(id, "url", String.class), "只传了仓库地址，在线地址应当是 NULL");
    }

    @Test
    @DisplayName("⑥ 名称空白 / 超长（101）-> 400（校验与列长度一致）")
    void createProject_invalidName_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("   ", null, "https://example.com/a", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("长".repeat(101), null, "https://example.com/a", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("最长 100 字")));
    }

    @Test
    @DisplayName("⑦ 在线地址 / 仓库地址格式非法（裸域名、javascript:）-> 400")
    void createProject_invalidAddressFormat_shouldReturn400() throws Exception {
        // 裸域名：点上去会跳到本站的 /example.com，一定是填错了
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("裸域名", null, "example.com", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // javascript: 会被渲染成 <a href="javascript:...">，点一下执行脚本（存储型 XSS 通道）
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("脚本地址", null, "javascript:alert(1)", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 仓库地址走的是同一套白名单，同样要拦
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("脚本仓库", null, null, "javascript:alert(2)", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 超长（256 位）
        String tooLong = "https://" + "a".repeat(248);
        assertEquals(256, tooLong.length(), "前置条件：这条地址刚好是 256 位");
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("超长地址", null, tooLong, null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑧【本项目特有】在线地址与仓库地址同时为空 -> 400（不让点不出任何东西的卡片入库）")
    void createProject_withoutAnyAddress_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 名称、简介、技术栈都填了，只有两个地址都是空的
                        .content(projectJson("两个地址都空" + mark, "简介", null, null, null, "Java", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                // 提示里要把两个字段名都说出来，否则用户看着两个地址框不知道说的是哪一个
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("在线地址和仓库地址至少要填一个")));

        // 顺带确认：这次请求没有留下任何数据（校验发生在写库之前）
        assertEquals(0, projectMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Project>()
                                .like(Project::getName, "两个地址都空" + mark)),
                "被拒绝的请求不该留下项目");
    }

    @Test
    @DisplayName("⑨【本项目特有】只有仓库地址 / 只有在线的地址，都是合法的")
    void createProject_withOnlyOneAddress_shouldBeAccepted() throws Exception {
        // 还没部署上线的项目：只有仓库
        String repoOnly = "只有仓库" + mark;
        Long id1 = createViaApi(projectJson(repoOnly, null, null,
                "https://github.com/example/repo-only", null, null, 1, 1));
        assertEquals("https://github.com/example/repo-only", rawValue(id1, "repo", String.class));
        assertNull(rawValue(id1, "url", String.class), "没填在线地址就应当是 NULL");

        // 内部项目不给源码：只有在线演示
        String urlOnly = "只有在线的" + mark;
        Long id2 = createViaApi(projectJson(urlOnly, null, "https://example.com/only-demo",
                null, null, null, 2, 1));
        assertEquals("https://example.com/only-demo", rawValue(id2, "url", String.class));
        assertNull(rawValue(id2, "repo", String.class));
    }

    @Test
    @DisplayName("⑩ 封面允许站内相对路径；封面用脚本协议仍然被拒；技术栈超长 -> 400")
    void createProject_coverAndTechBoundaries() throws Exception {
        String name = "封面站内路径" + mark;
        Long id = createViaApi(projectJson(name, null, "https://example.com/x", null,
                "/cover-1.png", null, 1, 1));
        assertEquals("/cover-1.png", rawValue(id, "cover", String.class),
                "站内相对路径是合法形态（图放在前端仓库的 public 目录里）");

        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("脚本封面", null, "https://example.com/y", null,
                                "javascript:alert(3)", null, 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 技术栈 201 字：比 varchar(200) 多一个
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("技术栈超长", null, "https://example.com/z", null,
                                null, "技".repeat(201), 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("技术栈最长 200 字")));

        // 简介 501 字
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("简介超长", "长".repeat(501), "https://example.com/w",
                                null, null, null, 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑪ 非法状态值（2 / -1）-> 400：不静默纠正")
    void createProject_invalidStatus_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("状态2", null, "https://example.com/s2", null, null, null, 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("0(隐藏) 或 1(显示)")));

        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("状态-1", null, "https://example.com/s3", null, null, null, 1, -1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================
    //  三、后台编辑
    // ================================================================

    @Test
    @DisplayName("⑫ 编辑项目 -> 库里更新；改名后前台立刻显示新名字")
    void updateProject_shouldPersistNewValues() throws Exception {
        Project project = insertProject("改前" + mark, 1, Project.STATUS_VISIBLE);

        String newName = "改后" + mark;
        mockMvc.perform(put("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(newName, "新简介", "https://example.com/new-demo",
                                "https://github.com/example/new", "/new-cover.png", "Vue,TypeScript", 9, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(newName, rawValue(project.getId(), "name", String.class));
        assertEquals("新简介", rawValue(project.getId(), "description", String.class));
        assertEquals("https://example.com/new-demo", rawValue(project.getId(), "url", String.class));
        assertEquals("https://github.com/example/new", rawValue(project.getId(), "repo", String.class));
        assertEquals("/new-cover.png", rawValue(project.getId(), "cover", String.class));
        assertEquals("Vue,TypeScript", rawValue(project.getId(), "tech", String.class));
        assertEquals(9, rawValue(project.getId(), "sort", Integer.class));

        assertNotNull(findVisibleByName(newName), "改名后前台应当是新名字");
        assertNull(findVisibleByName("改前" + mark), "旧名字不该再出现在前台列表里");
    }

    @Test
    @DisplayName("⑬ 清空可选字段 -> 数据库里真的变成 NULL（守「用 LambdaUpdateWrapper」）")
    void updateProject_clearOptionalFields_shouldActuallySetNull() throws Exception {
        String name = "要清空" + mark;
        Long id = createViaApi(projectJson(name, "有简介", "https://example.com/o",
                "https://github.com/example/o", "/has-cover.png", "Java", 1, 1));

        assertEquals("/has-cover.png", rawValue(id, "cover", String.class), "前置条件：封面有值");
        assertEquals("Java", rawValue(id, "tech", String.class), "前置条件：技术栈有值");

        // 提交空串（前端清空输入框就长这样）—— 必须真的写成 NULL。
        // 用 updateById 的话 null 字段会被跳过，封面和技术栈原样留着，
        // 用户看到的是"清空之后一刷新又回来了"，而且不会有任何报错
        mockMvc.perform(put("/admin/project/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(name, "", "https://example.com/o", null, "", "", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue(id, "cover", String.class), "清空封面必须真的写成 NULL");
        assertNull(rawValue(id, "tech", String.class), "清空技术栈同理");
        assertNull(rawValue(id, "description", String.class), "清空简介同理");
        assertEquals("https://example.com/o", rawValue(id, "url", String.class),
                "没动的字段不该受影响");
    }

    @Test
    @DisplayName("⑭ 编辑时 sort / status 缺省 -> 保持原值；显式传 0 才是想改成 0")
    void updateProject_withoutSortAndStatus_shouldKeepExistingValues() throws Exception {
        Project project = insertProject("保持原值" + mark, 7, Project.STATUS_HIDDEN);

        // 只提交必填项（名称 + 地址），sort 与 status 都不传
        mockMvc.perform(put("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + project.getName() + "\",\"repo\":\"https://github.com/example/keep\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(7, rawValue(project.getId(), "sort", Integer.class),
                "不传 sort 应当保持原值 7，而不是被重置成 0");
        assertEquals(Project.STATUS_HIDDEN, rawValue(project.getId(), "status", Integer.class),
                "不传 status 应当保持原值（隐藏）");

        mockMvc.perform(put("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + project.getName()
                                + "\",\"repo\":\"https://github.com/example/keep\",\"sort\":0}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0, rawValue(project.getId(), "sort", Integer.class), "显式传 0 就应当真的改成 0");
    }

    @Test
    @DisplayName("⑮【本项目特有】编辑时把两个地址都清空 -> 400，且库里的旧值分毫未动")
    void updateProject_clearingBothAddresses_shouldBeRejected() throws Exception {
        String name = "清空地址" + mark;
        Long id = createViaApi(projectJson(name, null, "https://example.com/keep-me",
                "https://github.com/example/keep-me", null, null, 1, 1));

        mockMvc.perform(put("/admin/project/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(name, null, "", "", null, null, 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("至少要填一个")));

        // 【为什么这条断言重要】校验写在写库之前，所以被拒绝的请求
        // 连"把地址清空"这个动作都没有发生 —— 用户改错了不会丢数据
        assertEquals("https://example.com/keep-me", rawValue(id, "url", String.class),
                "校验失败时不该把原有的在线地址清掉");
        assertEquals("https://github.com/example/keep-me", rawValue(id, "repo", String.class),
                "校验失败时不该把原有的仓库地址清掉");
    }

    @Test
    @DisplayName("⑯ 把项目改成隐藏 -> 前台立刻消失，后台列表仍看得到")
    void updateProject_toHidden_shouldDisappearFromPublicList() throws Exception {
        Project project = insertProject("先显示" + mark, 1, Project.STATUS_VISIBLE);
        assertNotNull(findVisibleByName(project.getName()), "前置条件：它本来前台可见");

        mockMvc.perform(put("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(project.getName(), null, "https://example.com/hide-me",
                                null, null, null, 1, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(findVisibleByName(project.getName()),
                "改成隐藏之后前台必须立刻看不到（同时验证了写操作会推进缓存版本号）");
        assertNotNull(projectService.listAll().stream()
                        .filter(vo -> project.getName().equals(vo.getName()))
                        .findFirst().orElse(null),
                "后台列表要含隐藏的，否则管理员改不回来");
    }

    @Test
    @DisplayName("⑰ 编辑不存在的项目 -> 404（业务错误码，HTTP 仍是 200）")
    void updateProject_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(put("/admin/project/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson("不存在", null, "https://example.com/nope",
                                null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("项目不存在")));
    }

    // ================================================================
    //  四、删除
    // ================================================================

    @Test
    @DisplayName("⑱ 删除项目 -> 【逻辑删除】：物理行还在、deleted=1、Mapper 与前台都查不到")
    void deleteProject_shouldSoftDelete() throws Exception {
        Project project = insertProject("待删" + mark, 1, Project.STATUS_VISIBLE);

        mockMvc.perform(delete("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, countPhysicalRows(project.getId()), "逻辑删除只是 UPDATE，物理行必须还在");
        assertEquals(1, rawValue(project.getId(), "deleted", Integer.class), "deleted 应当被置成 1");
        assertNull(projectMapper.selectById(project.getId()), "带 @TableLogic 的查询应当看不到它");
        assertNull(findVisibleByName(project.getName()), "删掉之后前台列表里也不该再有它");
    }

    @Test
    @DisplayName("⑲ 删除不存在的项目 / 重复删除 -> 404（第二次不能报删除成功）")
    void deleteProject_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(delete("/admin/project/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        Project project = insertProject("删两次" + mark, 1, Project.STATUS_VISIBLE);
        mockMvc.perform(delete("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/project/{id}", project.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ================================================================
    //  五、权限
    // ================================================================

    @Test
    @DisplayName("⑳ 权限：后台四个接口对游客 403、对无 token 401（一个都不能漏）")
    void adminProjectApis_shouldRequireAdmin() throws Exception {
        String body = projectJson("游客偷偷建的", null, "https://example.com/x", null, null, null, null, null);

        mockMvc.perform(get("/admin/project/list")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/project")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/project/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/project/{id}", 1L)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/project/list")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/project/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/project/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    //  六、缓存
    // ================================================================

    @Test
    @DisplayName("㉑ 前台列表走缓存：绕过 Service 直插 -> 列表里看不到（说明这次吃的是缓存）")
    void publicList_shouldServeFromCache() {
        projectService.listVisible();
        assertTrue(awaitProjectCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 project:list 的缓存 key");

        Project sneaky = insertProject("绕过Service" + mark, 50, Project.STATUS_VISIBLE);

        assertNull(findVisibleByName(sneaky.getName()),
                "这次应当直接吃缓存，看不到绕过 Service 插进去的数据");
    }

    @Test
    @DisplayName("㉒ 新建 / 编辑 / 删除都会推进版本号 -> 前台列表立刻能看到变化")
    void everyWriteOperation_shouldBumpVersionAndRefreshPublicList() throws Exception {
        projectService.listVisible();

        long beforeCreate = contentVersion();
        String name = "会推进版本" + mark;
        Long id = createViaApi(projectJson(name, null, "https://example.com/v1", null, null, null, 1, 1));
        assertTrue(contentVersion() > beforeCreate, "新建之后版本号应当推进");
        assertNotNull(findVisibleByName(name), "新建之后前台应当立刻能看到它");

        long beforeUpdate = contentVersion();
        String renamed = name + "-改名后";
        mockMvc.perform(put("/admin/project/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectJson(renamed, null, "https://example.com/v2", null, null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeUpdate, "编辑之后版本号应当推进");
        assertNotNull(findVisibleByName(renamed), "改名之后前台应当立刻看到新名字");

        long beforeDelete = contentVersion();
        mockMvc.perform(delete("/admin/project/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeDelete, "删除之后版本号应当推进");
        assertNull(findVisibleByName(renamed), "删除之后前台应当立刻看不到它");
    }

    @Test
    @DisplayName("㉓ 缓存 key 里带当前版本号，且有 TTL")
    void projectCacheKey_shouldCarryVersionAndTtl() {
        projectService.listVisible();

        Set<String> keys = awaitProjectCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "前台项目列表只有一份，应当只有一条缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        // 版本号比较要用数字：字符串比较下 "9" > "10" 成立，
        // 那会让这条断言在版本号跨过 10 之后莫名其妙变红
        assertTrue(key.contains(contentCacheVersion.current()),
                "缓存 key 里应当带当前版本号（「写操作立刻失效」的全部原理），实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private long contentVersion() {
        return Long.parseLong(contentCacheVersion.current());
    }

    /** 走接口新建一个项目，返回 id */
    private Long createViaApi(String json) throws Exception {
        String body = mockMvc.perform(post("/admin/project")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
    }

    /** 直接写库造一个项目（准备数据用；绕过 Service，所以也不会推进版本号） */
    private Project insertProject(String namePrefix, int sort, int status) {
        Project project = new Project();
        project.setName(namePrefix + "_" + mark);
        project.setUrl("https://example.com/" + mark);
        project.setSort(sort);
        project.setStatus(status);
        project.setDeleted(0);
        projectMapper.insert(project);
        return project;
    }

    /** 从【前台接口/服务】返回的列表里按名字找一个项目 */
    private ProjectVO findVisibleByName(String name) {
        return projectService.listVisible().stream()
                .filter(vo -> name.equals(vo.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 从响应体里按名字找出那一条（用来断言接口真的把字段吐出来了） */
    private com.fasterxml.jackson.databind.JsonNode findNodeByName(String responseBody, String name) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode node
                : new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody).get("data")) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        return null;
    }

    /** 拼请求体：null 表示"这个字段不出现在 JSON 里"（不传与传空串是两种输入） */
    private String projectJson(String name, String description, String url, String repo,
                               String cover, String tech, Integer sort, Integer status) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":\"").append(name == null ? "" : name).append("\"");
        if (description != null) {
            sb.append(",\"description\":\"").append(description).append("\"");
        }
        // url / repo 传 null 时【不出现在 JSON 里】（等于不填）；传 "" 时明确写空串，
        // 这两种输入在 Service 里都会被归一化成 null，但走的是不同的校验路径
        if (url != null) {
            sb.append(",\"url\":\"").append(url).append("\"");
        }
        if (repo != null) {
            sb.append(",\"repo\":\"").append(repo).append("\"");
        }
        if (cover != null) {
            sb.append(",\"cover\":\"").append(cover).append("\"");
        }
        if (tech != null) {
            sb.append(",\"tech\":\"").append(tech).append("\"");
        }
        if (sort != null) {
            sb.append(",\"sort\":").append(sort);
        }
        if (status != null) {
            sb.append(",\"status\":").append(status);
        }
        return sb.append("}").toString();
    }

    private int countPhysicalRows(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    /**
     * 直查原生列的值。
     * 【为什么不用 Mapper】@TableLogic 会自动加 deleted = 0，
     * 于是"逻辑删除的那一行还在不在、deleted 是几"用 Mapper 是查不出来的。
     */
    private <T> T rawValue(Long id, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM project WHERE id = ?", type, id);
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("项目测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /** 轮询等缓存 key 出现（Spring Data Redis 的写入是异步发出的，直接断言会偶发变红） */
    private Set<String> awaitProjectCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(PROJECT_CACHE_PREFIX + "*");
            if (keys != null && keys.size() >= expected) {
                return keys;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return keys == null ? Set.of() : keys;
    }
}
