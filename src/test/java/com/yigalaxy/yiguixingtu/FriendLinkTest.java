package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkForm;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkVO;
import com.yigalaxy.yiguixingtu.link.entity.FriendLink;
import com.yigalaxy.yiguixingtu.link.mapper.FriendLinkMapper;
import com.yigalaxy.yiguixingtu.link.service.FriendLinkService;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
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
 * 友链模块测试（LinkController / AdminLinkController / FriendLinkServiceImpl）
 *
 * 【一个类里同时测前台与后台，和 TagTest / CommentTest 是同一个安排】
 *   友链这个功能本身很小，"公开读一遍、后台增删改查一遍"用同一个类读下来
 *   最连贯；拆成 FriendLinkPublicTest / FriendLinkAdminTest 两个类，
 *   两边都要重复一套 @BeforeEach 造数据与清缓存的代码。
 *   （ArticleAdminTest / ArticlePublicTest 之所以拆开，是因为文章是最大的模块 ——
 *     两个方向各十几条用例，塞在一起会到九百行。）
 *
 * 【本类重点盯的五件事】
 *  ① 前台只返回【显示中】的（status = 1）：过滤必须写在查询里，不能交给前端。
 *  ② 排序：sort 升序，sort 相同再按 id 升序 —— 少了第二段，刷新一次顺序就可能变。
 *  ③ 编辑时【清空可选字段】必须真的写进 NULL：这条守的是
 *     "用 LambdaUpdateWrapper 而不是 updateById"这个决定
 *     （updateById 会跳过 null 字段，头像删不掉，用户以为界面卡了）。
 *  ④ 删除是【逻辑删除】：物理行还在、deleted = 1、Mapper 查不到、前台也不返回。
 *     和标签的"物理删除"正好相反，两条路都要有用例钉住，免得后人"统一"成一种。
 *  ⑤ 缓存：前台列表走 Redis（key 里带内容缓存版本号），任何写操作都要让它失效；
 *     后台列表刻意不走缓存。
 *
 * 【⚠️ @Transactional 管不了 Redis】
 *   和 TagTest / ArticleCacheTest 一样：缓存的 key 不会随事务回滚消失，
 *   所以 @BeforeEach / @AfterEach 都要清一次 link:list:v1:*。
 *
 * 【审计断言为什么不在这里】见 ③ 用例里的说明：审计是 AFTER_COMMIT 才落库的，
 *   而本类整体是 @Transactional（跑完回滚）—— 事务不提交，事件就被丢弃。
 *   所以"友链的增删改会留痕"放在 OperationLogTest 里验证（那个类是 NOT_SUPPORTED）。
 * =====================================================================
 */
class FriendLinkTest extends AbstractIntegrationTest {

    /** 前台列表缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String LINK_CACHE_PREFIX = RedisConfig.CACHE_FRIEND_LINK_LIST + ":v1:";

    /**
     * 类内自增序号：让同一个 JVM 里的每条用例都拿到不同的名字。
     * 【为什么要加序号而不只用时间戳】见 TagTest 里那段说明：
     * 拼出来的名字要同时满足"唯一"和"不超过列长度（50）"两个条件。
     */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FriendLinkService friendLinkService;

    @Autowired
    private FriendLinkMapper friendLinkMapper;

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
    /** 游客 token：⑭ 用它验证后台写接口对 GUEST 返回 403 */
    private String guestToken;

    @BeforeEach
    void setUp() {
        mark = "L" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_link", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        // 【为什么要真的插一个 GUEST 用户，而不是给管理员签一个 GUEST 的 token】
        //   JwtAuthenticationFilter 判身份时以【数据库里的角色】为准，
        //   token 里的 role claim 只是签发时的快照 —— 给 ADMIN 用户签 GUEST token，
        //   他依然按 ADMIN 处理（这正是要的行为）。这个坑在 CommentTest 里踩过一次。
        User guest = insertUser("test_guest_link", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearLinkCache();
    }

    @AfterEach
    void tearDown() {
        clearLinkCache();
    }

    private void clearLinkCache() {
        Set<String> keys = redis.keys(LINK_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、前台公开列表
    // ================================================================

    @Test
    @DisplayName("① 游客不登录就能拿友链列表，且【隐藏的那些不会出现】")
    void publicList_withoutToken_shouldExcludeHiddenLinks() throws Exception {
        FriendLink visible = insertLink("显示的站点", 1, FriendLink.STATUS_VISIBLE);
        FriendLink hidden = insertLink("隐藏的站点", 2, FriendLink.STATUS_HIDDEN);

        mockMvc.perform(get("/link/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 【为什么这条断言要用接口返回的列表来找，而不是直接查库】
        //   要证明的是"接口没把它吐出来"，查库只能证明"它在库里"——
        //   过滤写漏了的话，库里的数据依然是对的，只有接口返回的列表能看出问题
        assertNotNull(findVisibleByName(visible.getName()), "显示中的友链应当出现在前台列表里");
        assertNull(findVisibleByName(hidden.getName()),
                "隐藏的友链不该出现在前台列表里（过滤必须写在查询里，不能交给前端）");
    }

    @Test
    @DisplayName("② 排序：先按 sort 升序；sort 相同时按 id 升序（顺序必须稳定）")
    void publicList_shouldOrderBySortThenId() {
        // 两个 sort 相同的：如果不加"再按 id 升序"，MySQL 返回的顺序是不保证的，
        // 表现就是"刷新一下，两条友链换了位置"
        FriendLink first = insertLink("同序甲", 5, FriendLink.STATUS_VISIBLE);
        FriendLink second = insertLink("同序乙", 5, FriendLink.STATUS_VISIBLE);
        FriendLink head = insertLink("排最前", 1, FriendLink.STATUS_VISIBLE);

        List<String> names = friendLinkService.listVisible().stream()
                .map(FriendLinkVO::getName)
                .toList();

        // 【为什么要先断言"三条都在列表里"】indexOf 找不到时返回 -1，
        // 而 -1 比任何下标都小 —— 少了这几条前置断言，
        // 下面两条排序断言在"数据根本没查出来"的情况下也会绿（假绿）
        assertTrue(names.contains(head.getName()) && names.contains(first.getName())
                        && names.contains(second.getName()),
                "前置条件：三条都应当出现在前台列表里，实际=" + names);

        assertTrue(names.indexOf(head.getName()) < names.indexOf(first.getName()),
                "sort 小的应当排在前面");
        assertTrue(names.indexOf(first.getName()) < names.indexOf(second.getName()),
                "sort 相同的应当按 id 升序（先插的在前）");
    }

    @Test
    @DisplayName("③ 前台列表带齐前端渲染需要的字段（地址 / 排序 / 创建时间真的在响应里）")
    void publicList_shouldCarryFieldsForRendering() throws Exception {
        FriendLink link = insertLink("字段齐全", 1, FriendLink.STATUS_VISIBLE);
        // 直接写库会带默认的 url（见 insertLink），这里再补一个头像，让断言覆盖到它
        friendLinkMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FriendLink>()
                .eq(FriendLink::getId, link.getId())
                .set(FriendLink::getAvatar, "/avatar-for-field-check.png"));

        String body = mockMvc.perform(get("/link/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        // 【为什么要解析响应体，而不是只断言 Service 返回的 VO】
        //   要证明的是"接口真的把它吐出来了"。VO 上少 set 一个字段时，
        //   编译不报错、Service 单测也发现不了 —— 只有看响应体才知道。
        //   （老实的做法：先把响应里那一条找出来，再逐字段核对。）
        com.fasterxml.jackson.databind.JsonNode data =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data");
        com.fasterxml.jackson.databind.JsonNode found = null;
        for (com.fasterxml.jackson.databind.JsonNode node : data) {
            if (link.getName().equals(node.path("name").asText())) {
                found = node;
                break;
            }
        }
        assertNotNull(found, "响应里应当能找到刚插进去的那条友链");

        assertEquals("https://example.com/" + mark, found.path("url").asText(), "地址要在响应里");
        assertEquals("/avatar-for-field-check.png", found.path("avatar").asText(), "头像要在响应里");
        assertEquals(1, found.path("sort").asInt(), "排序要在响应里（前端按它排）");
        assertEquals(1, found.path("status").asInt(), "状态要在响应里");
        assertTrue(found.hasNonNull("createTime"), "创建时间要在响应里（后台列表要显示添加时间）");
    }

    // ================================================================
    //  二、后台新建（正常路径 + 边界）
    // ================================================================

    @Test
    @DisplayName("④ 新建友链 -> 200，库里真有这行（用原生 SQL 逐字段核对）")
    void createLink_withValidForm_shouldPersistToDatabase() throws Exception {
        String name = "新友链" + mark;

        String body = mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(name, "https://example.com/blog", "/avatar.png", "一句话", 3, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn().getResponse().getContentAsString();

        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();

        // 【为什么用 JdbcTemplate 直查原生 SQL】
        //   断言要回答的是"数据库里真的写进去了吗"。走 Mapper 查一遍也能看到数据，
        //   但它同时会带上 @TableLogic 的过滤、实体的字段映射等一层加工 ——
        //   查原生列才是"这一行到底存了什么"的权威答案（README 的测试约定第 1 条）。
        assertNotNull(rawRow(id), "库里应当真的多出这一行");
        assertEquals(name, rawValue(id, "name", String.class));
        assertEquals("https://example.com/blog", rawValue(id, "url", String.class));
        assertEquals("/avatar.png", rawValue(id, "avatar", String.class));
        assertEquals("一句话", rawValue(id, "description", String.class));
        assertEquals(3, rawValue(id, "sort", Integer.class));
        assertEquals(FriendLink.STATUS_VISIBLE, rawValue(id, "status", Integer.class));
        assertEquals(0, rawValue(id, "deleted", Integer.class), "新建的行 deleted 必须是 0");
    }

    @Test
    @DisplayName("⑤ sort / status 不传时的默认值：sort=0、status=1（与 tag/category 的 sort 处理一致）")
    void createLink_withoutSortAndStatus_shouldUseDefaults() throws Exception {
        String name = "默认值" + mark;

        // 只传必填的两项，sort 与 status 都不传
        String body = mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"url\":\"https://example.com/default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
        assertEquals(0, rawValue(id, "sort", Integer.class),
                "新建时不传 sort 应当按 0 处理（和 TagServiceImpl / CategoryServiceImpl 一致）");
        assertEquals(FriendLink.STATUS_VISIBLE, rawValue(id, "status", Integer.class),
                "新建时不传 status 应当按 1(显示) 处理 —— 管理员自己录的友链，录完就是要展示的");
        // 三个可选字段不传时应当是 NULL，而不是空字符串
        assertNull(rawValue(id, "avatar", String.class), "不传头像时应当存 NULL");
        assertNull(rawValue(id, "description", String.class), "不传简介时应当存 NULL");
    }

    @Test
    @DisplayName("⑥ 名称为空 / 超长 -> 400（校验与列长度一致，用户看得懂）")
    void createLink_invalidName_shouldReturn400() throws Exception {
        // 纯空格：@NotBlank 拦下的（"   " 不是"有内容"）
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("   ", "https://example.com/a", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 51 个字：比 varchar(50) 多一个。留给用户的是"站点名称最长 50 字"，
        // 而不是 MySQL 的 "Data too long for column 'name'"
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("长".repeat(51), "https://example.com/a", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("最长 50 字")));
    }

    @Test
    @DisplayName("⑦ 地址为空 / 没有协议 / 用了非 http(s) 协议 / 超长 -> 400（白名单）")
    void createLink_invalidUrl_shouldReturn400() throws Exception {
        // 空地址：友链没有地址就没有意义
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("地址为空", "", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 裸域名：前端点上去会跳到本站的 /example.com，一定是填错了
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("裸域名", "example.com", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("http")));

        // 【这条是本类最重要的一条边界】javascript: 开头的地址会被前台渲染成
        // <a href="javascript:...">，点一下就执行脚本 —— 典型的存储型 XSS 通道。
        // 白名单只放行 http/https，所以它必须被拦在入库之前
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("脚本地址", "javascript:alert(document.cookie)", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // ftp 也不放行：浏览器点它不会打开网页
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("ftp地址", "ftp://example.com", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 256 个字符：比 varchar(255) 多一个（"https://" 是 8 个字符，后面补 248 个 a）
        String tooLong = "https://" + "a".repeat(248);
        assertEquals(256, tooLong.length(), "前置条件：这条地址刚好是 256 位");
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("超长地址", tooLong, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑧ 非法状态值（2 / -1）-> 400：不静默纠正，否则前端传错值也能\"保存成功\"")
    void createLink_invalidStatus_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("状态2", "https://example.com/b", null, null, null, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("0(隐藏) 或 1(显示)")));

        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("状态-1", "https://example.com/c", null, null, null, -1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑨ 头像可以是站内相对路径（前端 public 下的图）；但 javascript: 仍然被拒")
    void createLink_relativeAvatar_shouldBeAccepted() throws Exception {
        String name = "站内头像" + mark;

        String body = mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(name, "https://example.com/d", "/cover-1.png", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
        assertEquals("/cover-1.png", rawValue(id, "avatar", String.class),
                "站内相对路径是合法形态（图放在前端仓库的 public 目录里是常见做法）");

        // 图片字段也是"会被渲染成属性"的地方，同样不许出现脚本协议
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("脚本头像", "https://example.com/e",
                                "javascript:alert(1)", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================
    //  三、后台编辑
    // ================================================================

    @Test
    @DisplayName("⑩ 编辑友链 -> 库里更新；旧名字不再出现在前台列表里")
    void updateLink_shouldPersistNewValues() throws Exception {
        FriendLink link = insertLink("改前" + mark, 1, FriendLink.STATUS_VISIBLE);

        String newName = "改后" + mark;
        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(newName, "https://example.com/new", "/new.png", "新简介", 9, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(newName, rawValue(link.getId(), "name", String.class));
        assertEquals("https://example.com/new", rawValue(link.getId(), "url", String.class));
        assertEquals("/new.png", rawValue(link.getId(), "avatar", String.class));
        assertEquals("新简介", rawValue(link.getId(), "description", String.class));
        assertEquals(9, rawValue(link.getId(), "sort", Integer.class));

        // 改名之后前台应当立刻显示新名字（版本号被推进 → 旧缓存作废）
        assertNotNull(findVisibleByName(newName), "改名后前台应当是新名字");
        assertNull(findVisibleByName("改前" + mark), "旧名字不该再出现在前台列表里");
    }

    @Test
    @DisplayName("⑪ 清空可选字段 -> 数据库里真的变成 NULL（守「用 LambdaUpdateWrapper 不用 updateById」）")
    void updateLink_clearOptionalFields_shouldActuallySetNull() throws Exception {
        FriendLink link = insertLink("要清空" + mark, 1, FriendLink.STATUS_VISIBLE);
        // 先造出"有值"的状态
        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(link.getName(), "https://example.com/f", "/has.png", "有简介", 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("/has.png", rawValue(link.getId(), "avatar", String.class));

        // 【本用例的核心】提交空串（前端清空输入框就长这样）——
        // 它必须真的把这两列写成 NULL。
        // 如果编辑用的是 updateById，null 字段会被【跳过】，头像和简介原封不动留着，
        // 用户看到的是"清空之后一刷新又回来了"，而且不会有任何报错。
        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(link.getName(), "https://example.com/f", "", "", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue(link.getId(), "avatar", String.class),
                "清空头像必须真的写成 NULL（updateById 会跳过 null 字段，这条会红）");
        assertNull(rawValue(link.getId(), "description", String.class), "清空简介同理");

        // 显式传 null（不是空串）也要是同一个结果
        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(link.getName(), "https://example.com/f", null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue(link.getId(), "avatar", String.class), "传 null 同样应当写成 NULL");
    }

    @Test
    @DisplayName("⑫ 编辑时 sort / status 缺省 -> 保持原值（不传 ≠ 想改成 0 / 想隐藏）")
    void updateLink_withoutSortAndStatus_shouldKeepExistingValues() throws Exception {
        FriendLink link = insertLink("保持原值" + mark, 7, FriendLink.STATUS_HIDDEN);

        // 只改名字和地址，sort 与 status 都不传
        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + link.getName() + "\",\"url\":\"https://example.com/g\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(7, rawValue(link.getId(), "sort", Integer.class),
                "不传 sort 应当保持原值 7，而不是被重置成 0");
        assertEquals(FriendLink.STATUS_HIDDEN, rawValue(link.getId(), "status", Integer.class),
                "不传 status 应当保持原值（隐藏），而不是被改成显示");

        // 显式传 0 才是"想改成 0"——两种意图必须能区分开
        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + link.getName() + "\",\"url\":\"https://example.com/g\",\"sort\":0}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0, rawValue(link.getId(), "sort", Integer.class),
                "显式传 0 就应当真的改成 0");
    }

    @Test
    @DisplayName("⑬ 把友链改成隐藏 -> 前台列表里立刻消失（状态过滤 + 缓存失效一起生效）")
    void updateLink_toHidden_shouldDisappearFromPublicList() throws Exception {
        FriendLink link = insertLink("先显示" + mark, 1, FriendLink.STATUS_VISIBLE);
        assertNotNull(findVisibleByName(link.getName()), "前置条件：它本来在前台是可见的");

        mockMvc.perform(put("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(link.getName(), "https://example.com/h", null, null, 1, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(findVisibleByName(link.getName()),
                "改成隐藏之后前台必须立刻看不到（这同时验证了写操作会推进缓存版本号）");
        // 后台列表仍然看得到它（含隐藏）—— 否则管理员就没法把它改回来了
        assertNotNull(friendLinkService.listAll().stream()
                        .filter(vo -> link.getName().equals(vo.getName()))
                        .findFirst().orElse(null),
                "后台列表要含隐藏的那些，否则管理员改不回来");
    }

    @Test
    @DisplayName("⑭ 编辑不存在的友链 -> 404（业务错误码，HTTP 仍是 200）")
    void updateLink_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(put("/admin/link/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("不存在", "https://example.com/i", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("友链不存在")));
    }

    // ================================================================
    //  四、删除
    // ================================================================

    @Test
    @DisplayName("⑮ 删除友链 -> 【逻辑删除】：物理行还在、deleted=1、Mapper 与前台都查不到")
    void deleteLink_shouldSoftDelete() throws Exception {
        FriendLink link = insertLink("待删" + mark, 1, FriendLink.STATUS_VISIBLE);
        assertEquals(1, countPhysicalRows(link.getId()), "前置条件：这行确实在表里");

        mockMvc.perform(delete("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 【和 TagTest 的断言刚好相反，两条都要有】标签是物理删除，
        // 所以那边断言"物理行数是 0"；友链是逻辑删除，这里断言"物理行还在、deleted=1"。
        // 两边的用例一起构成"每张表的删除语义都被钉住了"，
        // 免得后人看到"不一致"就把它统一掉
        assertEquals(1, countPhysicalRows(link.getId()), "逻辑删除只是 UPDATE，物理行必须还在");
        assertEquals(1, rawValue(link.getId(), "deleted", Integer.class), "deleted 应当被置成 1");
        assertNull(friendLinkMapper.selectById(link.getId()),
                "带 @TableLogic 的 Mapper 查询应当看不到已删除的行");
        assertNull(findVisibleByName(link.getName()), "删掉之后前台列表里也不该再有它");
    }

    @Test
    @DisplayName("⑯ 删除 / 编辑不存在的友链 -> 404；重复删除第二次也是 404（幂等语义）")
    void deleteLink_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(delete("/admin/link/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 删两次：第二次必须报"友链不存在"，而不是"删除成功"。
        // 报成功会让人以为删掉的是另一条数据（前端多点了两下就可能发生）
        FriendLink link = insertLink("删两次" + mark, 1, FriendLink.STATUS_VISIBLE);
        mockMvc.perform(delete("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/link/{id}", link.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ================================================================
    //  五、权限
    // ================================================================

    @Test
    @DisplayName("⑰ 权限：后台四个接口对游客 403、对无 token 401（一个都不能漏）")
    void adminLinkApis_shouldRequireAdmin() throws Exception {
        String body = linkJson("游客偷偷建的", "https://example.com/x", null, null, null, null);

        // 无 token → 401（过滤器层拦住，不知道你是谁）
        mockMvc.perform(get("/admin/link/list")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/link")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/link/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/link/{id}", 1L)).andExpect(status().isUnauthorized());

        // 带合法 token 但角色不够 → 403（知道你是谁，但你不能干这个）
        mockMvc.perform(get("/admin/link/list")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // 写接口一个都不能漏：只挡 GET 是最容易漏的一种权限漏洞
        mockMvc.perform(put("/admin/link/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/link/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    //  六、缓存
    // ================================================================

    @Test
    @DisplayName("⑱ 前台列表走缓存：绕过 Service 直插 -> 列表里看不到（说明这次吃的是缓存）")
    void publicList_shouldServeFromCache() {
        friendLinkService.listVisible();
        assertTrue(awaitLinkCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 link:list 的缓存 key");

        // 绕过 Service 直插：Mapper 上没有任何缓存注解，所以缓存不会失效
        FriendLink sneaky = insertLink("绕过Service" + mark, 50, FriendLink.STATUS_VISIBLE);

        assertNull(findVisibleByName(sneaky.getName()),
                "这次应当直接吃缓存，看不到绕过 Service 插进去的数据");
    }

    @Test
    @DisplayName("⑲ 新建 / 编辑 / 删除都会推进版本号 -> 前台列表立刻能看到变化")
    void everyWriteOperation_shouldBumpVersionAndRefreshPublicList() throws Exception {
        friendLinkService.listVisible();

        // ---- 新建 ----
        long beforeCreate = contentVersion();
        String name = "会推进版本" + mark;
        Long id = createViaApi(name, "https://example.com/v1");
        assertTrue(contentVersion() > beforeCreate, "新建之后版本号应当推进");
        assertNotNull(findVisibleByName(name), "新建之后前台应当立刻能看到它");

        // ---- 编辑（改名）----
        long beforeUpdate = contentVersion();
        String renamed = name + "-改名后";
        mockMvc.perform(put("/admin/link/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(renamed, "https://example.com/v2", null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeUpdate, "编辑之后版本号应当推进");
        assertNotNull(findVisibleByName(renamed), "改名之后前台应当立刻看到新名字");

        // ---- 删除 ----
        long beforeDelete = contentVersion();
        mockMvc.perform(delete("/admin/link/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeDelete, "删除之后版本号应当推进");
        assertNull(findVisibleByName(renamed), "删除之后前台应当立刻看不到它");
    }

    @Test
    @DisplayName("⑳ 缓存 key 里带当前版本号，且有 TTL（不会变成永不过期的数据）")
    void linkCacheKey_shouldCarryVersionAndTtl() {
        friendLinkService.listVisible();

        Set<String> keys = awaitLinkCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "前台友链列表只有一份，应当只有一条缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        // 【为什么版本号要比较数字而不是字符串】"9" > "10" 在字符串比较下成立 ——
        // 那会让这条断言在版本号跨过 10 之后莫名其妙地变红
        assertTrue(key.contains(contentCacheVersion.current()),
                "缓存 key 里应当带当前版本号（这是「写操作能立刻失效」的全部原理），实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 当前内容缓存版本号（转成数字便于比大小） */
    private long contentVersion() {
        return Long.parseLong(contentCacheVersion.current());
    }

    /**
     * 走接口新建一条友链，返回 id。
     * 抽出来是因为有两条用例要"建完再改/再删"，重复三次的 JSON 拼接容易写错。
     */
    private Long createViaApi(String name, String url) throws Exception {
        String body = mockMvc.perform(post("/admin/link")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson(name, url, null, null, 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
    }

    /** 直接写库造一条友链（用于准备数据 —— 绕过接口，所以也不会推进版本号） */
    private FriendLink insertLink(String namePrefix, int sort, int status) {
        FriendLink link = new FriendLink();
        link.setName(namePrefix + "_" + mark);
        link.setUrl("https://example.com/" + mark);
        link.setSort(sort);
        link.setStatus(status);
        link.setDeleted(0);
        friendLinkMapper.insert(link);
        return link;
    }

    /** 从【前台接口/服务】返回的列表里按名字找一条（这样断言的是真实返回的东西） */
    private FriendLinkVO findVisibleByName(String name) {
        return friendLinkService.listVisible().stream()
                .filter(vo -> name.equals(vo.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 拼请求体：可选项传 null 表示"这个字段不出现在 JSON 里"（不传 vs 传空串是两种输入） */
    private String linkJson(String name, String url, String avatar, String description,
                            Integer sort, Integer status) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":\"").append(name).append("\",");
        sb.append("\"url\":\"").append(url == null ? "" : url).append("\"");
        if (avatar != null) {
            sb.append(",\"avatar\":\"").append(avatar).append("\"");
        }
        if (description != null) {
            sb.append(",\"description\":\"").append(description).append("\"");
        }
        if (sort != null) {
            sb.append(",\"sort\":").append(sort);
        }
        if (status != null) {
            sb.append(",\"status\":").append(status);
        }
        return sb.append("}").toString();
    }

    /**
     * 直查原生列的通用方法：返回整数形式的行数。
     * 用 EXISTS 而不是 COUNT(*)：这里只关心"在不在"，读一行就够。
     */
    private Integer rawRow(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friend_link WHERE id = ?", Integer.class, id);
    }

    private int countPhysicalRows(Long id) {
        Integer count = rawRow(id);
        return count == null ? 0 : count;
    }

    /**
     * 直查原生列的值（类型由调用方指定）。
     * 【为什么不用 Mapper】@TableLogic 会自动给查询加上 deleted = 0，
     * 于是"逻辑删除那一行到底还在不在、deleted 是几"这件事，用 Mapper 是查不出来的。
     */
    private <T> T rawValue(Long id, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM friend_link WHERE id = ?", type, id);
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("友链测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /**
     * 等缓存 key 出现。
     * 【为什么要轮询】Spring Data Redis 的写入是异步发出的（连接池上另一条连接），
     * 方法返回时 KEYS 可能还看不到它 —— 直接断言会偶发变红（这个坑在 ArticleCacheTest 里踩过）。
     */
    private Set<String> awaitLinkCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(LINK_CACHE_PREFIX + "*");
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
