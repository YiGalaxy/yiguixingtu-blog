package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 删除歌曲时清理音频文件（MusicServiceImpl.delete）的集成测试
 *
 * 【这个类要证明什么】
 *   删歌会连带把磁盘上的 mp3 清掉 —— 但这件事【有前提】：
 *   ① 那个地址必须是本项目上传目录里的（外链一律不碰）
 *   ② 除这一首之外没有别的曲目、别的文章（正文/封面）、别的附件行在用它
 *   这两条各有用例，而且每一条都带【反向断言】——见下面 ③ 与 ④：
 *   只证明"该留的留住了"是不够的（一个"永远不删"的实现也能全绿），
 *   必须同时证明"该删的真的删了"。
 *
 * 【⚠️ 为什么几乎每条用例都要关掉测试事务（NOT_SUPPORTED）】
 *   删文件是【事务之外】的动作，而且刻意安排在提交之后
 *   （见 UploadedFileCleaner.deleteAfterCommit：删除不可逆、事务可能回滚）。
 *   如果用例跑在测试事务里（永远只回滚），afterCommit 回调根本不会触发，
 *   "文件被删掉了吗"这类断言就变成永远为真的假绿 —— 把删除逻辑整段注释掉也一样过。
 *   所以真删文件的那四条用例显式声明 {@code Propagation.NOT_SUPPORTED}，
 *   让业务真的提交（与 OperationLogTest / ArticleAttachmentTest 同一套约定）。
 *   代价是数据要自己清：见 @AfterEach（按唯一标记 + 清空测试上传目录）。
 *
 * 【测试数据全部自造】本类不依赖库里预先有什么、也不依赖执行顺序
 * （所有测试类共用同一个数据库，见 README 测试约定 3）。
 * =====================================================================
 */
@TestPropertySource(properties = {
        // 本类专用的上传目录：与其它测试类互不干扰，且每个用例前后都清空
        "app.upload.local-dir=${java.io.tmpdir}/ygt-music-cleanup-test"
})
class MusicFileCleanupTest extends AbstractIntegrationTest {

    /** 一个最小的合法 mp3（ID3v2 头 + 一帧静音）—— 内容不重要，"文件在不在"才是被测的点 */
    private static final byte[] TINY_MP3 = new byte[]{
            'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00
    };

    /** 一个最小的合法 PNG（1x1 像素）—— 文章封面用 */
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
    };

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UploadProperties uploadProperties;

    @Autowired
    private JdbcTemplate jdbc;

    private String mark;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mark = "MU" + SEQ.incrementAndGet() + "x" + (System.nanoTime() % 100000);

        User admin = insertAdmin();
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        wipeUploadDir();
    }

    @AfterEach
    void tearDown() {
        // 真提交的用例留下的数据要自己清（回滚的用例执行这几条也无害）
        jdbc.update("DELETE FROM article_attachment WHERE article_id IN "
                + "(SELECT id FROM article WHERE title LIKE ?)", "%" + mark + "%");
        jdbc.update("DELETE FROM article WHERE title LIKE ?", "%" + mark + "%");
        // 音乐是逻辑删除：这里按标题物理删掉本次用例造的行，不留垃圾
        jdbc.update("DELETE FROM music WHERE title LIKE ?", "%" + mark + "%");
        jdbc.update("DELETE FROM user WHERE username LIKE ?", "%" + mark + "%");

        wipeUploadDir();
    }

    // ================================================================
    //  一、外链：不碰任何本地文件
    // ================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("① url 是 http(s) 外链 -> 删歌不报错，本地一个字节都不动（只在存自己的文件上动手）")
    void externalUrl_shouldNotTouchAnyLocalFile() throws Exception {
        // 先在本地上传一个文件并造一首"正常的歌"，用来证明"没被牵连"
        Uploaded local = uploadAudio("keep-me.mp3");
        long otherId = createMusic(title("本地曲目"), local.url());

        // 再建一首 url 指向外部 CDN 的歌（音源放自己的对象存储/CDN 是常见做法）
        long externalId = createMusic(title("外链曲目"), "https://cdn.example.com/audio/x.mp3");

        long filesBefore = countFiles();
        deleteMusic(externalId);

        assertEquals(1, musicPhysicalRowCount(externalId),
                "外链那首歌的行仍然是逻辑删除（行还在，deleted = 1）");
        assertEquals(filesBefore, countFiles(),
                "外链地址不属于本项目的上传目录，删歌不该动本地任何文件");
        assertTrue(Files.exists(fileOf(local.url())),
                "另一首歌的文件当然不能被牵连（这是另一条记录的文件）");

        // 反向断言：删掉那首"正常的歌"，它的文件必须真的没了 ——
        // 少了这一条，一个"从不删任何文件"的实现也能让上面两条断言全绿
        deleteMusic(otherId);
        assertFalse(Files.exists(fileOf(local.url())),
                "本地那首歌被删之后，文件应当真的从磁盘上消失（说明清理逻辑确实在干活）");
    }

    // ================================================================
    //  二、独占的文件：删歌即删文件
    // ================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("② 删歌 -> 文件从磁盘上删掉，而数据库行仍然是逻辑删除（deleted = 1）")
    void deleteMusic_shouldRemoveItsOwnFile() throws Exception {
        Uploaded audio = uploadAudio("song.mp3");
        long id = createMusic(title("独占文件"), audio.url());
        assertTrue(Files.exists(fileOf(audio.url())), "前置条件：文件已经落盘");

        deleteMusic(id);

        assertFalse(Files.exists(fileOf(audio.url())), "这首歌独占的文件应当被删掉");
        // 数据库行不是物理删除：历史还在，只是查询看不到（与其它模块的逻辑删除一致）
        assertEquals(1, musicPhysicalRowCount(id), "行应当还在，只是 deleted = 1");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM music WHERE id = ? AND deleted = 0", Integer.class, id),
                "被删的行不该再被 @TableLogic 的查询看到");
    }

    // ================================================================
    //  三、★ 共用同一个文件：只有最后一个引用者离开时才删
    // ================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("③ ★ 两首歌共用一个 mp3 -> 删一首文件还在，删到最后一首才真删")
    void sharedAudioFile_shouldSurviveUntilLastReferenceGone() throws Exception {
        Uploaded audio = uploadAudio("shared.mp3");
        long first = createMusic(title("共用-A"), audio.url());
        long second = createMusic(title("共用-B"), audio.url());

        deleteMusic(first);

        assertTrue(Files.exists(fileOf(audio.url())),
                "★ 文件仍被另一首歌用着，必须保留（否则 B 一刷新就变成播不出来的哑巴，且文件回不来）");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM music WHERE id = ? AND deleted = 0", Integer.class, first),
                "A 这行确实已经被删了（说明删除动作发生了，只是文件被保住了）");

        // 最后一个引用者离开 —— 这时才轮到删文件。
        // 这一步是【必须的】：它同时证明了上一条断言不是因为"从来不删"才通过
        deleteMusic(second);
        assertFalse(Files.exists(fileOf(audio.url())),
                "最后一个引用它的歌也删了，文件这时才该被删掉");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("④ ★ 文章的正文/封面引用了这个 mp3 -> 删歌时文件保留；文章删掉后文件才被清")
    void audioFileReferencedByArticle_shouldNotBeDeleted() throws Exception {
        // 音频地址与封面都在本站上传目录下，文章正文里嵌一段 <audio>（或直接写地址）
        // 是很自然的做法 —— 那这个文件就不再是"音乐专属"的了
        Uploaded audio = uploadAudio("in-article.mp3");
        Uploaded cover = uploadImage("cover.png");

        long musicId = createMusic(title("被文章引用"), audio.url());

        String content = "这篇的配乐是 " + audio.url() + " 这一首";
        long articleId = createArticle(title("引用音频的文章"), content, cover.url());

        deleteMusic(musicId);
        assertTrue(Files.exists(fileOf(audio.url())),
                "★ 文章正文还引用着它，删歌不能把这个文件删掉（否则文章页里的音频就 404 了）");

        // 反向断言：文章被删掉之后，这个文件就没人用了 —— 这时才该被清掉。
        // 这一条同时说明"文章那侧的级联清理"与"音乐这侧的引用检查"是串起来的
        deleteArticle(articleId);
        assertFalse(Files.exists(fileOf(audio.url())),
                "最后一个引用者（文章）也删了，文件应当被清掉");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⑤ ★ 反向也要成立：歌还在、文章删掉 -> 文件不能被文章的级联清理删掉")
    void audioFileReferencedByLiveMusic_shouldSurviveArticleDeletion() throws Exception {
        // 这一条盯的是【从另一侧删】的情形：同一份音频既在音乐列表里、
        // 又被某篇文章的正文引用（从音乐列表复制地址最自然）。
        // 删文章时如果只查文章自己的两张表，就会得出"没人用"的结论，
        // 然后把那首歌的音频删掉：歌还在、点开播不了，而且文件回不来。
        Uploaded audio = uploadAudio("both-sides.mp3");
        Uploaded cover = uploadImage("article-only-cover.png");

        long musicId = createMusic(title("两侧都用的歌"), audio.url());
        long articleId = createArticle(title("两侧都用的文章"),
                "配乐 " + audio.url() + " 来自音乐列表", cover.url());

        deleteArticle(articleId);

        // ① 歌还在用这个文件：必须保留
        assertTrue(Files.exists(fileOf(audio.url())),
                "★ 音乐列表里那首歌还在用这个文件，删文章绝不能把它删掉");
        // ② 反向断言：这张文章独占的封面确实被清了 —— 说明删除逻辑真的执行了，
        //    上面那条断言不是因为"整段清理没跑"才通过
        assertFalse(Files.exists(fileOf(cover.url())),
                "这篇文章独占的封面该被删掉（这说明级联清理确实跑了，不是没执行）");

        // 再删歌：最后一个引用者走了，文件这时才该消失
        deleteMusic(musicId);
        assertFalse(Files.exists(fileOf(audio.url())), "最后一个引用者也删了，文件才该被删");
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 上传结果里我们需要的那点信息 */
    private record Uploaded(String url, String name, long size) {
    }

    /** 用音频上传接口造一个真实文件（返回的地址形如 /uploads/music/2026/09/{uuid}.mp3） */
    private Uploaded uploadAudio(String filename) throws Exception {
        return upload("audio", filename, "audio/mpeg", TINY_MP3);
    }

    /** 用图片上传接口造一个真实文件（文章封面要用） */
    private Uploaded uploadImage(String filename) throws Exception {
        return upload(null, filename, "image/png", TINY_PNG);
    }

    private Uploaded upload(String type, String filename, String contentType, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/upload")
                .file(new MockMultipartFile("file", filename, contentType, content))
                .header("Authorization", "Bearer " + adminToken);
        if (type != null) {
            request = request.param("type", type);
        }

        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Uploaded(jsonString(body, "url"), jsonString(body, "name"), jsonNumber(body, "size"));
    }

    /** 建一首歌（走真实的后台接口，顺带验证前端提交的 JSON 形状能被正确绑定） */
    private long createMusic(String title, String url) throws Exception {
        String body = mockMvc.perform(post("/admin/music")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"title\":\"" + title + "\",\"url\":\"" + url + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonNumber(body, "data");
    }

    /** 删歌（走真实接口） */
    private void deleteMusic(long id) throws Exception {
        mockMvc.perform(delete("/admin/music/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 建一篇已发布的文章（正文里可以嵌音频地址） */
    private long createArticle(String title, String content, String cover) throws Exception {
        String body = mockMvc.perform(post("/admin/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"cover\":\"" + cover + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonNumber(body, "data");
    }

    private void deleteArticle(long id) throws Exception {
        mockMvc.perform(delete("/admin/article/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 标题里带本次用例的唯一标记，@AfterEach 才能按它把真提交的数据清干净 */
    private String title(String prefix) {
        return prefix + "-" + mark;
    }

    /**
     * 这一行还在不在（含被逻辑删除的行）。
     * 【为什么用原生 SQL】@TableLogic 会把 deleted = 1 的行藏起来，
     * 用 Mapper 查是查不出来的 —— 而"删除是逻辑删除"这件事恰恰只能这样验证
     * （README 测试约定 1 里写的就是这一条）。
     */
    private int musicPhysicalRowCount(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM music WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private Path localRoot() {
        return Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize();
    }

    /** 由返回的 URL 反推磁盘上的文件路径 */
    private Path fileOf(String url) {
        return localRoot().resolve(url.substring(url.indexOf("/uploads/") + "/uploads/".length()));
    }

    private long countFiles() {
        Path root = localRoot();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            throw new IllegalStateException("统计上传目录失败", e);
        }
    }

    private void wipeUploadDir() {
        Path root = localRoot();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> all = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            throw new IllegalStateException("清理测试上传目录失败: " + root, e);
        }
    }

    private User insertAdmin() {
        User admin = new User();
        admin.setUsername("test_admin_music_clean_" + mark);
        admin.setPassword(passwordEncoder.encode("123456"));
        admin.setNickname("音乐清理测试管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setDeleted(0);
        userMapper.insert(admin);
        return admin;
    }

    private String jsonString(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private long jsonNumber(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }
}
