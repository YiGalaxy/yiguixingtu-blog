package com.yigalaxy.yiguixingtu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.tag.dto.TagForm;
import com.yigalaxy.yiguixingtu.tag.entity.Tag;
import com.yigalaxy.yiguixingtu.tag.mapper.ArticleTagMapper;
import com.yigalaxy.yiguixingtu.tag.mapper.TagMapper;
import com.yigalaxy.yiguixingtu.tag.service.TagService;
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
 * 标签模块测试（TagController / AdminTagController / TagServiceImpl）
 *
 * 【本类重点盯的四件事】
 *  ① 标签是【物理删除】—— 这条和其它表都不一样，必须有用例钉住：
 *     删除后 tag 表里那行必须真的不存在（用 JdbcTemplate 直查物理行，
 *     Mapper 查不出来 —— 因为它走的还是同一张表，看不出"行还在不在"的区别）。
 *     为什么标签不用逻辑删除，见 V5__create_tag_tables.sql 里那段说明
 *     （逻辑删除与唯一索引 uk_name 天生打架）。
 *  ② 标签下文章数的口径：只算【已发布】。草稿不计 ——
 *     否则访客点进标签页会看到"计数 5、列表 2 篇"。
 *  ③ 删除标签必须连带清理 article_tag 关联：不清理会留下指向不存在标签的
 *     悬空关联，表现是"标签的文章数统计到一个查不到的标签上"。
 *  ④ 缓存：前台标签列表走 Redis（key 里带文章缓存版本号），
 *     任何标签写操作都要让它失效；后台列表刻意不走缓存。
 *
 * 【@Transactional 管不了 Redis】
 *   和 ArticleCacheTest 一样，缓存的 key 不会随事务回滚消失，
 *   所以 @BeforeEach / @AfterEach 都要清一次 tag:list:v1:*。
 * =====================================================================
 */
class TagTest extends AbstractIntegrationTest {

    /** 标签列表缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String TAG_CACHE_PREFIX = RedisConfig.CACHE_TAG_LIST + ":v1:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TagService tagService;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private ArticleTagMapper articleTagMapper;

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
    private StringRedisTemplate redis;

    @Autowired
    private ArticleCacheVersion cacheVersion;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次用例的唯一标记，避免和库里已有数据混在一起 */
    private String mark;

    /** 类内自增序号：让同一轮测试里每个用例的标记都不同，且足够短（见 setUp 的说明） */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private User admin;
    private User guest;
    private String adminToken;
    private String guestToken;
    private Category category;

    @BeforeEach
    void setUp() {
        // 【标记为什么要这么短】标签名只有 30 字符，而用例里的名字是"前缀 + 标记"
        //   （比如 "改前T1n23"）。第一版用的是 "TAG" + 纳秒时间戳（22 位），
        //   拼出来直接超长 —— 报的是 MySQL 的 Data truncation: Data too long
        //   for column 'name'，看起来像校验没生效，其实是测试自己把名字造太长了。
        //   这里用"类内自增序号 + 纳秒后三位"，保证同一个 JVM 里唯一且足够短。
        mark = "T" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_tag", "ADMIN");
        guest = insertUser("test_guest_tag", "GUEST");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        category = new Category();
        category.setName(mark + "_标签测试分类");   // 分类名有唯一索引，必须带标记
        category.setDescription("标签测试用分类");
        category.setSort(99);
        category.setDeleted(0);
        categoryMapper.insert(category);

        clearTagCache();
    }

    @AfterEach
    void tearDown() {
        clearTagCache();
    }

    private void clearTagCache() {
        Set<String> keys = redis.keys(TAG_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、前台标签列表（公开）与文章数口径
    // ================================================================

    @Test
    @DisplayName("① 游客就能拿标签列表，且每个标签带「已发布文章数」")
    void publicList_shouldBeAccessibleWithoutToken() throws Exception {
        Tag published = insertTag("已发布", 1);
        Tag unused = insertTag("还没人用", 2);

        // 一篇已发布的文章挂在第一个标签上
        insertArticle(published.getId(), 1);

        mockMvc.perform(get("/tag/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        assertEquals(1L, findTagVOInPublicList(published.getName()).getArticleCount());
        // 没有文章的标签要给 0，而不是 null —— 前端可以直接展示与排序，不用再 ?? 0
        assertEquals(0L, findTagVOInPublicList(unused.getName()).getArticleCount(),
                "没有文章的标签，文章数应当是 0");
    }

    @Test
    @DisplayName("② 标签的文章数只算已发布：草稿不计（否则访客看到「计数 5、列表 2 篇」）")
    void articleCount_shouldOnlyCountPublished() throws Exception {
        Tag tag = insertTag("口径验证", 1);

        insertArticle(tag.getId(), 1);   // 已发布
        insertArticle(tag.getId(), 0);   // 草稿
        insertArticle(tag.getId(), 0);   // 再一篇草稿

        Long count = findTagVOInPublicList(tag.getName()).getArticleCount();
        assertEquals(1L, count, "3 篇里只有 1 篇是已发布，计数应当是 1");
    }

    @Test
    @DisplayName("③ 文章被下架后，标签的文章数跟着减少")
    void articleCount_shouldDropAfterUnpublish() throws Exception {
        Tag tag = insertTag("下架验证", 1);
        Article article = insertArticle(tag.getId(), 1);

        assertEquals(1L, findTagVOInPublicList(tag.getName()).getArticleCount());

        // 绕过 Service 直接改状态：这样不会推进缓存版本号，
        // 也就顺带验证了"计数确实是从库里现算的，不是缓存里那份旧数据"
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .set(Article::getStatus, 0));

        // 让缓存失效后再看（相当于"过了一会儿"）
        cacheVersion.bump();
        clearTagCache();
        assertEquals(0L, findTagVOInPublicList(tag.getName()).getArticleCount(),
                "下架之后不该再计入");
    }

    // ================================================================
    //  二、后台增删改
    // ================================================================

    @Test
    @DisplayName("④ 新建标签 -> 200，库里真有这行，并且留下一条 CREATE_TAG 审计")
    void createTag_shouldPersistAndAudit() throws Exception {
        String name = "新建" + mark;

        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sort\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNumber());

        Tag created = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        assertNotNull(created, "库里应当真的多出这个标签");
        assertEquals(3, created.getSort());

        // 【这里的审计断言为什么不放在本类】见类注释：审计是 AFTER_COMMIT 才落库的，
        //   而本类整体是 @Transactional（跑完回滚）—— 事务永远不提交，
        //   事件就被丢弃了，断言必然失败。那属于"测试环境的事务语义"，
        //   不是功能坏了。所以"标签的增删改也会留痕"放在 OperationLogTest 里验证
        //   （那个类用 Propagation.NOT_SUPPORTED，让业务真的提交）。
    }

    @Test
    @DisplayName("⑤ 标签名重复 -> 400；首尾空格不影响查重（『工作』与『 工作 』是同一个名字）")
    void createTag_duplicateName_shouldBeRejected() throws Exception {
        String name = "重复" + mark;

        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 带首尾空格再建一次：Service 会 trim 之后再查重，所以应当被拦住。
        // 不 trim 的话会插进两个看起来一模一样的标签（DB 认为 "x" 与 " x " 不同）
        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  " + name + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));
    }

    @Test
    @DisplayName("⑥ 标签名为空 / 超过 30 字 -> 400（校验必须和列长度一致，否则用户看不懂数据库的报错）")
    void createTag_invalidName_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        String tooLong = "长".repeat(31);
        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑦ 编辑标签（改名 + 改排序）-> 库里更新，审计里记下「从什么改成什么」")
    void updateTag_shouldPersistAndAuditOldName() throws Exception {
        Tag tag = insertTag("改前" + mark, 1);

        mockMvc.perform(put("/admin/tag/{id}", tag.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改后" + mark + "\",\"sort\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Tag updated = tagMapper.selectById(tag.getId());
        assertEquals("改后" + mark, updated.getName());
        assertEquals(9, updated.getSort());
        // （审计断言见 ④ 里的说明：它放在 OperationLogTest）
    }

    @Test
    @DisplayName("⑧ 改成别人已用的名字 -> 400；改成自己原来的名字 -> 不算冲突")
    void updateTag_duplicateName_shouldBeRejectedButSelfAllowed() throws Exception {
        Tag first = insertTag("甲", 1);
        Tag second = insertTag("乙", 2);

        // 乙 想叫 甲 的名字 → 冲突。
        // 注意这里用 first.getName() 取【库里那个确切的名字】，
        // 而不是自己拼一遍 —— 第一版就是自己拼的（漏了 insertTag 里加的下划线），
        // 结果提交的名字和库里那个其实不同，接口正常返回 200，用例红在"expected 400"。
        // 这类"测试自己把数据造错了"的失败最容易被误判成功能有 bug。
        mockMvc.perform(put("/admin/tag/{id}", second.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + first.getName() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));

        // 甲 不改名字、只改排序 → 不能因为"查到自己"而被拦
        // （查重时没排除自己的话，这里会报"标签名已存在"，那是很典型的一个 bug）
        mockMvc.perform(put("/admin/tag/{id}", first.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + first.getName() + "\",\"sort\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(5, tagMapper.selectById(first.getId()).getSort());
    }

    @Test
    @DisplayName("⑨ 删除标签 -> 【物理删除】（表里那行真的没了）+ 关联被清理 + 审计留痕")
    void deleteTag_shouldPhysicallyRemoveRowAndLinks() throws Exception {
        Tag tag = insertTag("待删" + mark, 1);
        Article article = insertArticle(tag.getId(), 1);

        assertEquals(1, countTagRowsPhysically(tag.getId()), "前置条件：这行确实在表里");
        assertEquals(1, countLinks(tag.getId()), "前置条件：确实有一条文章关联");

        mockMvc.perform(delete("/admin/tag/{id}", tag.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 【本类最关键的一条断言】用原生 SQL 数物理行 ——
        // tag 表没有 deleted 字段，删除是真删。若哪天有人给它加上 @TableLogic，
        // 这条会立刻变红（而那正是 V5 里解释过"不要这么做"的原因）
        assertEquals(0, countTagRowsPhysically(tag.getId()),
                "标签是物理删除：表里不该再留着这一行");
        assertNull(tagMapper.selectById(tag.getId()), "用 Mapper 也应当查不到");

        // 关联必须一起清掉：不清理就会留下指向不存在标签的悬空行
        assertEquals(0, countLinks(tag.getId()), "删除标签时必须同时解除文章关联");

        // 文章本身不受影响（只是没标签了）
        assertNotNull(articleMapper.selectById(article.getId()), "删标签不该动文章");
        // （审计断言见 ④ 里的说明：它放在 OperationLogTest）
    }

    @Test
    @DisplayName("⑩ 删除不存在的标签 -> 404（业务错误码，HTTP 仍是 200）")
    void deleteTag_notFound_shouldReturn404Code() throws Exception {
        mockMvc.perform(delete("/admin/tag/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("⑪ 权限：游客 403、不带 token 401（后台标签接口一个都不该漏）")
    void adminTagApis_shouldRequireAdmin() throws Exception {
        mockMvc.perform(get("/admin/tag/list")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/tag/list"))
                .andExpect(status().isUnauthorized());

        // 写接口也要挡：只挡 GET 是最容易漏的一种权限漏洞
        mockMvc.perform(post("/admin/tag")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"游客偷偷建的\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/admin/tag/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    //  三、缓存
    // ================================================================

    @Test
    @DisplayName("⑫ 前台标签列表走缓存：绕过 Service 直插标签 -> 列表里看不到（说明命中缓存了）")
    void publicList_shouldServeFromCache() throws Exception {
        tagService.listPublished();
        assertTrue(awaitTagCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 tag:list 的缓存 key");

        // 绕过 Service 直插一个标签：Mapper 上没有任何缓存注解，所以缓存不会失效
        Tag sneaky = new Tag();
        sneaky.setName("绕过Service" + mark);
        sneaky.setSort(50);
        tagMapper.insert(sneaky);

        // 缓存命中 → 看不到刚插进去的那个标签
        assertNull(findTagVOInPublicList("绕过Service" + mark),
                "列表这次应当直接吃缓存，看不到绕过 Service 插进去的数据");
    }

    @Test
    @DisplayName("⑬ 通过 Service 建标签 -> 版本号推进，前台列表立刻能看到（缓存失效）")
    void createTag_shouldInvalidatePublicListCache() throws Exception {
        tagService.listPublished();
        // current() 返回的是 Redis 里那串计数值（String），这里比较大小要转成数字。
        // 【为什么不用 String 直接比】"9" > "10" 在字符串比较下是成立的 ——
        // 那会让这条断言在版本号跨过 10 之后莫名其妙地变红
        long versionBefore = Long.parseLong(cacheVersion.current());

        TagForm form = new TagForm();
        form.setName("正常建的" + mark);
        form.setSort(1);
        tagService.create(form);

        assertTrue(Long.parseLong(cacheVersion.current()) > versionBefore,
                "标签写操作应当推进缓存版本号（否则前台标签云会一直是旧的）");

        // 版本号变了 → key 也变了 → 必然未命中 → 重新查库 → 能看到新标签
        assertNotNull(findTagVOInPublicList("正常建的" + mark),
                "新建标签之后，前台列表应当立刻能看到它");
    }

    @Test
    @DisplayName("⑭ 缓存 key 里带当前版本号，且有 TTL（不会变成永不过期的数据）")
    void tagCacheKey_shouldCarryVersionAndTtl() {
        tagService.listPublished();

        Set<String> keys = awaitTagCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "标签列表只有一份，应当只有一条缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        assertTrue(key.contains(String.valueOf(cacheVersion.current())),
                "缓存 key 里应当带当前版本号（这是「写操作能立刻失效」的全部原理），实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL（兜底失效），实际剩余秒数=" + ttl);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 从【前台接口】返回的列表里按名字找一个标签（这样断言的是接口真实返回的东西） */
    private com.yigalaxy.yiguixingtu.tag.dto.TagVO findTagVOInPublicList(String name) {
        return tagService.listPublished().stream()
                .filter(vo -> name.equals(vo.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 造一个标签（直接写库，绕过接口 —— 用于准备数据） */
    private Tag insertTag(String name, int sort) {
        Tag tag = new Tag();
        tag.setName(name + "_" + mark);   // 标签名有唯一索引，必须带标记
        tag.setSort(sort);
        tagMapper.insert(tag);
        return tag;
    }

    /** 造一篇文章并挂上指定标签（直接写库） */
    private Article insertArticle(Long tagId, int status) {
        Article article = new Article();
        article.setTitle(mark + "_文章_" + System.nanoTime());
        article.setSummary("标签测试摘要");
        article.setContent("# 标签测试\n\n正文");
        article.setCategoryId(category.getId());
        article.setStatus(status);
        article.setIsTop(0);
        article.setViewCount(0);
        article.setAuthorId(admin.getId());
        article.setDeleted(0);
        articleMapper.insert(article);

        articleTagMapper.insertBatch(article.getId(), List.of(tagId));
        return article;
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("标签测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /** 数物理行：tag 表没有逻辑删除，所以这就是"这行到底还在不在"的权威答案 */
    private int countTagRowsPhysically(Long tagId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag WHERE id = ?", Integer.class, tagId);
        return count == null ? 0 : count;
    }

    private int countLinks(Long tagId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE tag_id = ?", Integer.class, tagId);
        return count == null ? 0 : count;
    }

    /**
     * 等缓存 key 出现。
     * 【为什么要轮询】Spring Data Redis 的写入是异步发出的（连接池上另一条连接），
     * 方法返回时 KEYS 可能还看不到它 —— 直接断言会偶发变红。
     * 这个坑在 ArticleCacheTest 里踩过一次，解决办法就是轮询。
     */
    private Set<String> awaitTagCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(TAG_CACHE_PREFIX + "*");
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
