package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.music.dto.MusicVO;
import com.yigalaxy.yiguixingtu.music.entity.Music;
import com.yigalaxy.yiguixingtu.music.mapper.MusicMapper;
import com.yigalaxy.yiguixingtu.music.service.MusicService;
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
 * 音乐模块测试（MusicController / AdminMusicController / MusicServiceImpl）
 *
 * 【本类除了那套通用形状（与 FavoriteTest / ProjectTest 同构），重点盯三件事】
 *
 *  ① **排序是两段的**：sort 升序，sort 相同的按 id 升序。
 *     第二段不是"顺手加的"，它是前端契约 —— 只按 sort 排的话，
 *     同一个 sort 的两首歌在两次请求里的先后由存储引擎决定，
 *     用户会看到列表"自己在跳"。V11 的索引 (status, sort, id) 就是照这条查询建的。
 *
 *  ② **url 走的是 MEDIA_URL 白名单，而不是 IMAGE_URL / EXTERNAL_URL**：
 *     它既接受上传回来的 {@code /uploads/music/...}（站内路径），
 *     也接受 http(s) 外链；而 {@code javascript:} 这类必须被拒
 *     （它会被塞进 {@code <audio src>}）。
 *     空串也放行 —— 但业务上它是必填，所以空串最终由 @NotBlank 与 Service 拦下，
 *     两条用例分别钉住"格式放行"与"必填仍然生效"。
 *
 *  ③ **歌词是长文本（TEXT），存的是 LRC 原文**：
 *     一条 5000 字、带时间戳与换行的歌词要能原样存取（不被截断、不被转义处理），
 *     而 20001 字要被拒 —— 上限是"防止有人把它当文章正文写"的那道闸。
 *
 * 【审计断言不在这里】审计是 @TransactionalEventListener(AFTER_COMMIT) 才落库的，
 * 而本类整体是 @Transactional（跑完回滚）—— 事务永远不提交，事件会被丢弃。
 * 所以音乐的三条审计在 OperationLogTest 第⑲条。
 * =====================================================================
 */
class MusicTest extends AbstractIntegrationTest {

    /** 前台列表缓存在 Redis 里的完整前缀（与 RedisConfig 的 computePrefixWith 规则一致） */
    private static final String MUSIC_CACHE_PREFIX = RedisConfig.CACHE_MUSIC_LIST + ":v1:";

    /** 一条像样的 LRC（带时间戳与换行）——多处复用来钉"原文存取" */
    private static final String LRC_SAMPLE = "[00:00.00]第一行歌词\n[00:12.34]第二行歌词\n[00:25.00]第三行歌词";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MusicService musicService;

    @Autowired
    private MusicMapper musicMapper;

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
        mark = "M" + SEQ.incrementAndGet() + "n" + (System.nanoTime() % 1000);

        admin = insertUser("test_admin_music", "ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        // 真的插一个 GUEST：身份以数据库里的角色为准（给 ADMIN 签 GUEST token 依然是 ADMIN）
        User guest = insertUser("test_guest_music", "GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        clearMusicCache();
    }

    @AfterEach
    void tearDown() {
        clearMusicCache();
    }

    private void clearMusicCache() {
        Set<String> keys = redis.keys(MUSIC_CACHE_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ================================================================
    //  一、前台公开列表
    // ================================================================

    @Test
    @DisplayName("① 游客不登录就能拿音乐列表，且【隐藏的那些不会出现】")
    void publicList_withoutToken_shouldExcludeHiddenMusic() throws Exception {
        Music visible = insertMusic("显示的曲目", 1, Music.STATUS_VISIBLE);
        Music hidden = insertMusic("隐藏的曲目", 2, Music.STATUS_HIDDEN);

        mockMvc.perform(get("/music/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        assertNotNull(findVisibleByTitle(visible.getTitle()), "显示中的曲目应当在前台列表里");
        assertNull(findVisibleByTitle(hidden.getTitle()),
                "隐藏的曲目不该出现在前台列表里（过滤写在查询里，不是交给前端）");
    }

    @Test
    @DisplayName("② 排序：先按 sort 升序；sort 相同时按 id 升序（前台契约就是这个）")
    void publicList_shouldOrderBySortThenId() {
        Music first = insertMusic("同序甲", 5, Music.STATUS_VISIBLE);
        Music second = insertMusic("同序乙", 5, Music.STATUS_VISIBLE);
        Music head = insertMusic("排最前", 1, Music.STATUS_VISIBLE);

        List<String> titles = musicService.listVisible().stream()
                .map(MusicVO::getTitle)
                .toList();

        // 先确认三条都在：indexOf 找不到时返回 -1，而 -1 比任何下标都小，
        // 少了这条前置断言，下面的排序断言在"数据根本没查出来"时也会绿
        assertTrue(titles.contains(head.getTitle()) && titles.contains(first.getTitle())
                        && titles.contains(second.getTitle()),
                "前置条件：三条都应当出现在前台列表里，实际=" + titles);

        assertTrue(titles.indexOf(head.getTitle()) < titles.indexOf(first.getTitle()), "sort 小的在前");
        assertTrue(titles.indexOf(first.getTitle()) < titles.indexOf(second.getTitle()),
                "sort 相同的按 id 升序（先插的在前）—— 顺序必须稳定，否则列表会自己跳");
    }

    @Test
    @DisplayName("③ 响应里带齐播放器需要的字段（曲名 / 歌手 / 音频地址 / 封面 / 歌词 / 排序 / 状态 / 创建时间）")
    void publicList_shouldCarryFieldsForRendering() throws Exception {
        String title = "字段齐全" + mark;
        Long id = createViaApi(musicJson(title, "周杰伦", "http://localhost:8082/uploads/music/2026/09/x.mp3",
                "/cover-1.png", LRC_SAMPLE, 1, 1));

        String body = mockMvc.perform(get("/music/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode found = findNodeByTitle(body, title);
        assertNotNull(found, "响应里应当能找到刚建的曲目");
        assertEquals(id, found.path("id").asLong());
        assertEquals("周杰伦", found.path("artist").asText());
        assertEquals("http://localhost:8082/uploads/music/2026/09/x.mp3", found.path("url").asText(),
                "音频地址要原样返回（前端直接塞进 <audio src>）");
        assertEquals("/cover-1.png", found.path("cover").asText());
        assertEquals(LRC_SAMPLE, found.path("lyrics").asText(),
                "歌词是 LRC【原文文本】，换行与时间戳都要原样保留（后端不解析、不改写）");
        assertEquals(1, found.path("sort").asInt());
        assertEquals(1, found.path("status").asInt());
        assertTrue(found.hasNonNull("createTime"), "创建时间要在响应里");
    }

    @Test
    @DisplayName("④ 列表为空时不报错：返回 code 200 + 空数组（前端回落内置曲目的前提）")
    void publicList_whenNothingVisible_shouldReturnEmptyArrayNot404() throws Exception {
        // 把库清空再插一条隐藏的：这样"有数据但一条都不该显示"与"库里什么都没有"
        // 两种情形都被这条用例覆盖（本类是 @Transactional，DELETE 跑完会回滚）
        jdbcTemplate.update("DELETE FROM music");
        clearMusicCache();
        insertMusic("只有隐藏的", 1, Music.STATUS_HIDDEN);
        clearMusicCache();

        // 【为什么这条值得单独写】前端在列表为空时会回落成内置的那一首
        // （static-media/bg-music.mp3）。如果这里返回 404 或报错，
        // 前端的回落分支根本走不到，音乐页会变成错误页 —— 而"库里没有数据"
        // 恰恰是这个模块上线时的正常状态（迁移里不插种子数据，见 V11 的注释）
        String body = mockMvc.perform(get("/music/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString();

        assertEquals(0, new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(body).get("data").size(),
                "只含隐藏曲目时前台列表应当是空数组，实际响应=" + body);
    }

    // ================================================================
    //  二、后台新建（正常路径 + 边界）
    // ================================================================

    @Test
    @DisplayName("⑤ 新建音乐 -> 200，库里真有这行（用原生 SQL 逐字段核对，含歌词原文）")
    void createMusic_withValidForm_shouldPersistToDatabase() throws Exception {
        String title = "新曲目" + mark;

        Long id = createViaApi(musicJson(title, "周杰伦", "https://cdn.example.com/music/1.mp3",
                "/cover.png", LRC_SAMPLE, 3, 1));

        assertEquals(title, rawValue(id, "title", String.class));
        assertEquals("周杰伦", rawValue(id, "artist", String.class));
        assertEquals("https://cdn.example.com/music/1.mp3", rawValue(id, "url", String.class));
        assertEquals("/cover.png", rawValue(id, "cover", String.class));
        assertEquals(LRC_SAMPLE, rawValue(id, "lyrics", String.class),
                "歌词要原样落库（TEXT 列，换行不能丢）");
        assertEquals(3, rawValue(id, "sort", Integer.class));
        assertEquals(Music.STATUS_VISIBLE, rawValue(id, "status", Integer.class));
        assertEquals(0, rawValue(id, "deleted", Integer.class), "新建的行 deleted 必须是 0");
    }

    @Test
    @DisplayName("⑥ 只传必填项 -> 歌手/封面/歌词存 NULL；sort 默认 0、status 默认 1")
    void createMusic_withoutOptionalFields_shouldUseDefaults() throws Exception {
        String title = "默认值" + mark;

        // 只传曲名与音频地址（url 是这张表唯一必填的展示字段）
        String body = mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(title, null, "https://example.com/only-required.mp3",
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
        assertEquals(0, rawValue(id, "sort", Integer.class), "不传 sort 应当按 0 处理");
        assertEquals(Music.STATUS_VISIBLE, rawValue(id, "status", Integer.class),
                "不传 status 应当按 1(显示) —— 管理员自己录的曲目，录完就是要展示的");
        assertNull(rawValue(id, "artist", String.class), "不填歌手应当是 NULL");
        assertNull(rawValue(id, "cover", String.class), "不填封面应当是 NULL");
        assertNull(rawValue(id, "lyrics", String.class), "不填歌词应当是 NULL");

        // 空串（前端清空输入框）也要归一化成 NULL，不能存进去一个空串
        String body2 = mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(title + "-空串", "", "https://example.com/blank.mp3",
                                "", "", null, null)))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        Long id2 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body2).get("data").asLong();
        assertNull(rawValue(id2, "artist", String.class),
                "提交空串时也必须存 NULL（否则库里会同时存在 \"\" 和 NULL 两种\"没有值\"）");
        assertNull(rawValue(id2, "cover", String.class));
        assertNull(rawValue(id2, "lyrics", String.class));
    }

    @Test
    @DisplayName("⑦ 曲名空白 / 超长（101）-> 400（校验与列长度一致）")
    void createMusic_invalidTitle_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("   ", null, "https://example.com/a.mp3", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("长".repeat(101), null, "https://example.com/a.mp3",
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("曲名最长 100 字")));
    }

    @Test
    @DisplayName("⑧ 音频地址为空 / 裸域名 / javascript: / 超长 -> 400（唯一必填的地址字段）")
    void createMusic_invalidUrl_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("地址为空", null, "", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("音频地址不能为空")));

        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("裸域名", null, "example.com/a.mp3", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 这条会被塞进 <audio src>，点播放就等于执行脚本
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("脚本地址", null, "javascript:alert(1)", null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        String tooLong = "https://" + "a".repeat(493);
        assertEquals(501, tooLong.length(), "前置条件：这条地址刚好是 501 位");
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("超长地址", null, tooLong, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("音频地址最长 500 字")));
    }

    @Test
    @DisplayName("⑨ 音频地址接受【两种形态】：上传回来的站内路径 / http(s) 外链（MEDIA_URL 而不是 IMAGE_URL）")
    void createMusic_urlAcceptsBothUploadedPathAndExternalLink() throws Exception {
        // 形态一：上传接口返回的地址（本地开发是绝对地址，经 Nginx 反代时常见的是站内路径）
        String uploaded = "/uploads/music/2026/09/abc123.mp3";
        Long id1 = createViaApi(musicJson("上传的" + mark, null, uploaded, null, null, 1, 1));
        assertEquals(uploaded, rawValue(id1, "url", String.class),
                "站内路径必须能存（否则「上传成功却填不进表单」）");

        // 形态二：自己放在 CDN / 对象存储上的外链，后面还会带查询参数
        String external = "https://cdn.example.com/music/song.mp3?sign=abc&expires=1234567890";
        Long id2 = createViaApi(musicJson("外链的" + mark, null, external, null, null, 2, 1));
        assertEquals(external, rawValue(id2, "url", String.class), "外链（含查询串）也要能存");
    }

    @Test
    @DisplayName("⑩ 歌手超长（101）/ 歌词超长（20001）/ 非法状态（2、-1）-> 400")
    void createMusic_lengthAndStatusBoundaries_shouldReturn400() throws Exception {
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("歌手超长", "歌".repeat(101), "https://example.com/a.mp3",
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("歌手最长 100 字")));

        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("歌词超长", null, "https://example.com/a.mp3",
                                null, "词".repeat(20001), null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("歌词最长 20000 字")));

        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("状态2", null, "https://example.com/a.mp3", null, null, null, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("0(隐藏) 或 1(显示)")));

        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("状态-1", null, "https://example.com/a.mp3", null, null, null, -1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑪ 长歌词（约 5000 字，TEXT 列）能原样存取，不被截断")
    void createMusic_longLyrics_shouldBeStoredIntact() throws Exception {
        // 【为什么要有这条】lyrics 是这个模块唯一的 TEXT 字段。
        //   如果哪天有人把它改成 varchar(255)，一条正常的 LRC 会被【静默截断】
        //   （MySQL 非严格模式下是警告而不是错误），歌词只显示前几句 ——
        //   这种问题在页面上看起来像"前端解析坏了"，很难往数据库列长度上想
        StringBuilder lyrics = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                // 【为什么换行只加在行间、不在末尾】可选文本会做一次 trim()
                // （见 MusicServiceImpl.normalizeOptional）—— 末尾那个 \n 会被去掉，
                // 于是"原样存取"这条断言会差一个字符。这里让文本不以空白结尾，
                // 断言的才是"内容一字不少"，而不是"trim 的行为"
                lyrics.append('\n');
            }
            lyrics.append("[00:").append(String.format("%02d", i / 60))
                    .append(":").append(String.format("%02d", i % 60))
                    .append(".00]第 ").append(i).append(" 行歌词，这一行有二十来个字用于凑长度");
        }
        assertTrue(lyrics.length() > 5000, "前置条件：这份歌词应当超过 5000 字，实际=" + lyrics.length());

        String title = "长歌词" + mark;
        Long id = createViaApi(musicJson(title, "测试歌手", "https://example.com/long.mp3",
                null, lyrics.toString(), 1, 1));

        String stored = rawValue(id, "lyrics", String.class);
        assertEquals(lyrics.length(), stored == null ? 0 : stored.length(),
                "歌词长度必须一字不少（原样落库）");
        assertEquals(lyrics.toString(), stored, "歌词内容必须完全一致");
        assertEquals(stored, findVisibleByTitle(title).getLyrics(), "前台读出来的也要是同一份原文");
    }

    @Test
    @DisplayName("⑫ 同一个 sort 下可以有多首；不同 sort 各自有序（不要求 sort 唯一）")
    void createMusic_sameSort_shouldCoexist() throws Exception {
        Long first = createViaApi(musicJson("同序一" + mark, null, "https://example.com/1.mp3",
                null, null, 7, 1));
        Long second = createViaApi(musicJson("同序二" + mark, null, "https://example.com/2.mp3",
                null, null, 7, 1));

        assertEquals(7, rawValue(first, "sort", Integer.class));
        assertEquals(7, rawValue(second, "sort", Integer.class),
                "同一个 sort 下有多首是正常的（它不是唯一键）");
    }

    // ================================================================
    //  三、后台编辑
    // ================================================================

    @Test
    @DisplayName("⑬ 编辑音乐 -> 库里更新；改曲名后前台立刻显示新曲名")
    void updateMusic_shouldPersistNewValues() throws Exception {
        Music music = insertMusic("改前" + mark, 1, Music.STATUS_VISIBLE);

        String newTitle = "改后" + mark;
        mockMvc.perform(put("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(newTitle, "新歌手", "https://example.com/new.mp3",
                                "/new-cover.png", LRC_SAMPLE, 9, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(newTitle, rawValue(music.getId(), "title", String.class));
        assertEquals("新歌手", rawValue(music.getId(), "artist", String.class));
        assertEquals("https://example.com/new.mp3", rawValue(music.getId(), "url", String.class));
        assertEquals("/new-cover.png", rawValue(music.getId(), "cover", String.class));
        assertEquals(LRC_SAMPLE, rawValue(music.getId(), "lyrics", String.class));
        assertEquals(9, rawValue(music.getId(), "sort", Integer.class));

        assertNotNull(findVisibleByTitle(newTitle), "改名后前台应当是新曲名");
        assertNull(findVisibleByTitle("改前" + mark), "旧曲名不该再出现在前台列表里");
    }

    @Test
    @DisplayName("⑭ 清空歌手 / 封面 / 歌词 -> 数据库里真的变成 NULL（守「用 LambdaUpdateWrapper」）")
    void updateMusic_clearOptionalFields_shouldActuallySetNull() throws Exception {
        String title = "要清空" + mark;
        Long id = createViaApi(musicJson(title, "有歌手", "https://example.com/clear.mp3",
                "/has-cover.png", LRC_SAMPLE, 1, 1));
        assertEquals("有歌手", rawValue(id, "artist", String.class), "前置条件：歌手有值");
        assertEquals(LRC_SAMPLE, rawValue(id, "lyrics", String.class), "前置条件：歌词有值");

        // 提交空串（前端清空输入框就长这样）—— 必须真的写成 NULL。
        // 用 updateById 的话 null 字段会被跳过，歌手与歌词原样留着，
        // 用户看到的是"清空之后一刷新又回来了"，而且不会有任何报错
        mockMvc.perform(put("/admin/music/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(title, "", "https://example.com/clear.mp3", "", "", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(rawValue(id, "artist", String.class), "清空歌手必须真的写成 NULL");
        assertNull(rawValue(id, "cover", String.class), "清空封面同理");
        assertNull(rawValue(id, "lyrics", String.class), "清空歌词同理");

        // 显式传 null（字段不出现）也要是同一个结果
        mockMvc.perform(put("/admin/music/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(title, null, "https://example.com/clear.mp3", null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(rawValue(id, "artist", String.class), "不传歌手同样应当保持 NULL");
    }

    @Test
    @DisplayName("⑮ 编辑时 sort / status 缺省 -> 保持原值；显式传 0 才是想改成 0")
    void updateMusic_withoutSortAndStatus_shouldKeepExistingValues() throws Exception {
        Music music = insertMusic("保持原值" + mark, 7, Music.STATUS_HIDDEN);

        mockMvc.perform(put("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + music.getTitle()
                                + "\",\"url\":\"https://example.com/keep.mp3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(7, rawValue(music.getId(), "sort", Integer.class),
                "不传 sort 应当保持原值 7，而不是被重置成 0");
        assertEquals(Music.STATUS_HIDDEN, rawValue(music.getId(), "status", Integer.class),
                "不传 status 应当保持原值（隐藏）");

        mockMvc.perform(put("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + music.getTitle()
                                + "\",\"url\":\"https://example.com/keep.mp3\",\"sort\":0}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0, rawValue(music.getId(), "sort", Integer.class), "显式传 0 就应当真的改成 0");
    }

    @Test
    @DisplayName("⑯ 把曲目改成隐藏 -> 前台立刻消失，后台列表仍看得到")
    void updateMusic_toHidden_shouldDisappearFromPublicList() throws Exception {
        Music music = insertMusic("先显示" + mark, 1, Music.STATUS_VISIBLE);
        assertNotNull(findVisibleByTitle(music.getTitle()), "前置条件：它本来前台可见");

        mockMvc.perform(put("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(music.getTitle(), null, "https://example.com/hide.mp3",
                                null, null, 1, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(findVisibleByTitle(music.getTitle()),
                "改成隐藏之后前台必须立刻看不到（同时验证了写操作会推进缓存版本号）");
        assertNotNull(musicService.listAll().stream()
                        .filter(vo -> music.getTitle().equals(vo.getTitle()))
                        .findFirst().orElse(null),
                "后台列表要含隐藏的，否则管理员改不回来");
    }

    @Test
    @DisplayName("⑰ 编辑不存在的曲目 -> 404（业务错误码，HTTP 仍是 200）")
    void updateMusic_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(put("/admin/music/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson("不存在", null, "https://example.com/nope.mp3",
                                null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("音乐不存在")));
    }

    // ================================================================
    //  四、删除
    // ================================================================

    @Test
    @DisplayName("⑱ 删除曲目 -> 【逻辑删除】：物理行还在、deleted=1、Mapper 与前台都查不到")
    void deleteMusic_shouldSoftDelete() throws Exception {
        Music music = insertMusic("待删" + mark, 1, Music.STATUS_VISIBLE);

        mockMvc.perform(delete("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, countPhysicalRows(music.getId()), "逻辑删除只是 UPDATE，物理行必须还在");
        assertEquals(1, rawValue(music.getId(), "deleted", Integer.class), "deleted 应当被置成 1");
        assertNull(musicMapper.selectById(music.getId()), "带 @TableLogic 的查询应当看不到它");
        assertNull(findVisibleByTitle(music.getTitle()), "删掉之后前台列表里也不该再有它");
    }

    @Test
    @DisplayName("⑲ 删除不存在的曲目 / 重复删除 -> 404")
    void deleteMusic_notExist_shouldReturn404Code() throws Exception {
        mockMvc.perform(delete("/admin/music/{id}", 999999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        Music music = insertMusic("删两次" + mark, 1, Music.STATUS_VISIBLE);
        mockMvc.perform(delete("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/music/{id}", music.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ================================================================
    //  五、权限
    // ================================================================

    @Test
    @DisplayName("⑳ 权限：后台四个接口对游客 403、对无 token 401；前台列表对谁都开放")
    void adminMusicApis_shouldRequireAdmin() throws Exception {
        String body = musicJson("游客偷偷建的", null, "https://example.com/x.mp3", null, null, null, null);

        mockMvc.perform(get("/admin/music/list")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/music")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/music/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/music/{id}", 1L)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/music/list")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/music/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/music/{id}", 1L)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());

        // 前台列表是公开的 —— 这条同时守住"别把 /music/list 顺手改成要登录"
        // （要是改成要登录，前台播放器对未登录访客就永远拿不到曲目，而且报的是 401）
        mockMvc.perform(get("/music/list")).andExpect(status().isOk());
        mockMvc.perform(get("/music/list").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());
    }

    // ================================================================
    //  六、缓存
    // ================================================================

    @Test
    @DisplayName("㉑ 前台列表走缓存：绕过 Service 直插 -> 列表里看不到（说明这次吃的是缓存）")
    void publicList_shouldServeFromCache() {
        musicService.listVisible();
        assertTrue(awaitMusicCacheKeys(1, 2000).size() > 0,
                "第一次调用之后 Redis 里应当出现 music:list 的缓存 key");

        Music sneaky = insertMusic("绕过Service" + mark, 50, Music.STATUS_VISIBLE);

        assertNull(findVisibleByTitle(sneaky.getTitle()),
                "这次应当直接吃缓存，看不到绕过 Service 插进去的数据");
    }

    @Test
    @DisplayName("㉒ 新建 / 编辑 / 删除都会推进版本号 -> 前台列表立刻能看到变化")
    void everyWriteOperation_shouldBumpVersionAndRefreshPublicList() throws Exception {
        musicService.listVisible();

        long beforeCreate = contentVersion();
        String title = "会推进版本" + mark;
        Long id = createViaApi(musicJson(title, null, "https://example.com/v1.mp3", null, null, 1, 1));
        assertTrue(contentVersion() > beforeCreate, "新建之后版本号应当推进");
        assertNotNull(findVisibleByTitle(title), "新建之后前台应当立刻能看到它");

        long beforeUpdate = contentVersion();
        String renamed = title + "-改名后";
        mockMvc.perform(put("/admin/music/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(musicJson(renamed, null, "https://example.com/v2.mp3", null, null, 1, 1)))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeUpdate, "编辑之后版本号应当推进");
        assertNotNull(findVisibleByTitle(renamed), "改名之后前台应当立刻看到新曲名");

        long beforeDelete = contentVersion();
        mockMvc.perform(delete("/admin/music/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(contentVersion() > beforeDelete, "删除之后版本号应当推进");
        assertNull(findVisibleByTitle(renamed), "删除之后前台应当立刻看不到它");
    }

    @Test
    @DisplayName("㉓ 缓存 key 里带当前版本号，且 TTL 落在 [5 分钟, 6 分钟]（基础时长 + 随机抖动）")
    void musicCacheKey_shouldCarryVersionAndJitteredTtl() {
        insertMusic("TTL 验证" + mark, 1, Music.STATUS_VISIBLE);
        clearMusicCache();
        musicService.listVisible();

        Set<String> keys = awaitMusicCacheKeys(1, 2000);
        assertEquals(1, keys.size(), "前台音乐列表只有一份，应当只有一条缓存 key，实际=" + keys);

        String key = keys.iterator().next();
        assertTrue(key.contains(contentCacheVersion.current()),
                "缓存 key 里应当带当前版本号（「写操作立刻失效」的全部原理），实际=" + key);

        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "缓存必须有 TTL 兜底，实际剩余秒数=" + ttl);
        // 基础 5 分钟 = 300 秒，抖动 0~60 秒（见 RedisConfig.TTL_JITTER_MAX_SECONDS）。
        // 【为什么断言区间而不是"每次都不一样"】摇骰子本身带随机性，用它当断言天然不稳
        // （这个取舍的完整说明见 ArticleCacheTest 里那段注释）
        assertTrue(ttl >= 300 && ttl <= 360,
                "TTL 应当落在 300~360 秒（5 分钟 + 0~60 秒抖动），实际=" + ttl);

        // 再写一次（同一条 key 会被重新写入）也要仍然落在同一个区间里 ——
        // 这条同时说明"抖动是每次写入现算的"，而不是某个固定值被缓存住了
        musicService.listVisible();
        Long ttlAgain = redis.getExpire(key);
        assertNotNull(ttlAgain);
        assertTrue(ttlAgain > 0 && ttlAgain <= 360, "重新写入的 TTL 仍在预期区间内，实际=" + ttlAgain);
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private long contentVersion() {
        return Long.parseLong(contentCacheVersion.current());
    }

    /** 走接口新建一条音乐，返回 id */
    private Long createViaApi(String json) throws Exception {
        String body = mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asLong();
    }

    /** 直接写库造一条音乐（准备数据用；绕过 Service，所以也不会推进版本号） */
    private Music insertMusic(String titlePrefix, int sort, int status) {
        Music music = new Music();
        music.setTitle(titlePrefix + "_" + mark);
        music.setUrl("https://example.com/" + mark + ".mp3");
        music.setSort(sort);
        music.setStatus(status);
        music.setDeleted(0);
        musicMapper.insert(music);
        return music;
    }

    /** 从【前台接口/服务】返回的列表里按曲名找一条 */
    private MusicVO findVisibleByTitle(String title) {
        return musicService.listVisible().stream()
                .filter(vo -> title.equals(vo.getTitle()))
                .findFirst()
                .orElse(null);
    }

    /** 从响应体里按曲名找出那一条（用来断言接口真的把字段吐出来了） */
    private com.fasterxml.jackson.databind.JsonNode findNodeByTitle(String responseBody, String title) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode node
                : new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody).get("data")) {
            if (title.equals(node.path("title").asText())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 拼请求体：null 表示"这个字段不出现在 JSON 里"（不传与传空串是两种输入）。
     * 歌词里会有换行与引号，所以统一做一次 JSON 转义 —— 不转义的话
     * 这条用例会在【请求体解析】那里就失败（400），看起来像校验坏了。
     */
    private String musicJson(String title, String artist, String url, String cover,
                             String lyrics, Integer sort, Integer status) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"title\":\"").append(escape(title)).append("\",");
        sb.append("\"url\":\"").append(escape(url)).append("\"");
        if (artist != null) {
            sb.append(",\"artist\":\"").append(escape(artist)).append("\"");
        }
        if (cover != null) {
            sb.append(",\"cover\":\"").append(escape(cover)).append("\"");
        }
        if (lyrics != null) {
            sb.append(",\"lyrics\":\"").append(escape(lyrics)).append("\"");
        }
        if (sort != null) {
            sb.append(",\"sort\":").append(sort);
        }
        if (status != null) {
            sb.append(",\"status\":").append(status);
        }
        return sb.append("}").toString();
    }

    /** JSON 字符串转义：反斜杠必须第一个换，否则会把后面加的转义符再转一次 */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private int countPhysicalRows(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM music WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    /**
     * 直查原生列的值。
     * 【为什么不用 Mapper】@TableLogic 会自动加 deleted = 0，
     * 于是"逻辑删除那一行还在不在、deleted 是几"用 Mapper 查不出来。
     */
    private <T> T rawValue(Long id, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM music WHERE id = ?", type, id);
    }

    private User insertUser(String usernamePrefix, String role) {
        User u = new User();
        u.setUsername(usernamePrefix + "_" + mark);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("音乐测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }

    /** 轮询等缓存 key 出现（Spring Data Redis 的写入是异步发出的，直接断言会偶发变红） */
    private Set<String> awaitMusicCacheKeys(int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = Set.of();
        while (System.currentTimeMillis() < deadline) {
            keys = redis.keys(MUSIC_CACHE_PREFIX + "*");
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
