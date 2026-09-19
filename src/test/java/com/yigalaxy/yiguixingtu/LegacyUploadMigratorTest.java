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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 一次性迁移工具（{@code LegacyUploadMigrator}）的用例
 *
 * 【它测的是什么】把磁盘上【UUID 命名的旧文件】改成【内容哈希命名】，
 *   并把库里所有引用一起改写 —— 详见那个类的注释。
 *
 * 【⚠️ 这个测试类与工具本身同寿命】迁移完成后（dryRun 报告"0 个待迁移"），
 *   它们可以一起删掉。在那之前它是必要的：这个工具会改全站的引用数据，
 *   而且删除是【不可逆】的 —— 它必须被测试钉住。
 *
 * 【本类构造"旧数据"的方式】不走上传接口（那个接口现在只会产出哈希命名），
 *   而是【直接往上传目录里写 UUID 命名的文件】，再通过正常的建文章接口
 *   把它的地址写进 cover / content —— 这正是线上存量数据的样子。
 * =====================================================================
 */
@TestPropertySource(properties = {
        "app.upload.local-dir=${java.io.tmpdir}/ygt-migrator-test"
})
class LegacyUploadMigratorTest extends AbstractIntegrationTest {

    /** 内容寻址之后，文件名应当长这样：64 位十六进制（用来断言"迁移真的发生了"） */
    private static final java.util.regex.Pattern HASHED_NAME =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}\\.[a-z0-9]+$");

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
    private JdbcTemplate jdbc;

    @Autowired
    private UploadProperties uploadProperties;

    private String mark;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mark = "MG" + SEQ.incrementAndGet() + "x" + (System.nanoTime() % 100000);

        User admin = new User();
        admin.setUsername("test_admin_migrate_" + mark);
        admin.setPassword(passwordEncoder.encode("x"));
        admin.setNickname("迁移测试用户");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        wipeUploadDir();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM article WHERE title LIKE ?", "%" + mark + "%");
        jdbc.update("DELETE FROM user WHERE username LIKE ?", "%" + mark + "%");
        wipeUploadDir();
    }

    // ================================================================
    //  一、预演（dryRun）：只看，不动
    // ================================================================

    @Test
    @DisplayName("① dryRun 只报告不动手：文件还在原地、库里的引用一个字都没变")
    void dryRun_shouldChangeNothing() throws Exception {
        String key = writeLegacyFile("cover", TINY_PNG);
        long articleId = createArticle("预演-" + mark, "正文", absoluteUrl(key), null);

        String body = callMigrate(true);

        assertEquals(1, jsonNumber(body, "legacyFiles"), "应当报告有 1 个旧文件待迁移，实际=" + body);
        assertEquals(0, jsonNumber(body, "copied"), "预演不该复制任何文件");
        assertEquals(0, jsonNumber(body, "updatedRows"), "预演不该改任何行");

        assertTrue(Files.exists(localRoot().resolve(key)), "预演绝不能动磁盘上的文件");
        assertEquals(absoluteUrl(key), coverOf(articleId), "预演绝不能改库里的引用");
    }

    // ================================================================
    //  二、实跑：改名 + 改写引用
    // ================================================================

    @Test
    @DisplayName("② ★ 实跑：旧文件换成哈希名、内容不变，且库里 cover 与正文里的引用都被改写")
    void migrate_shouldRenameFileAndRewriteReferences() throws Exception {
        String key = writeLegacyFile("cover", TINY_PNG);
        String oldAbsolute = absoluteUrl(key);
        // 正文里用【相对地址】：两种形态（绝对 / 相对）都要能被改写 —— 它们含同一个 key，
        // 而改写正是按 key 做的（见 UploadedFileCleaner.toObjectKey 的注释）
        String oldRelative = "/uploads/" + key;

        long articleId = createArticle("迁移-" + mark,
                "正文 ![配图](" + oldRelative + ") 结束", oldAbsolute, null);

        String body = callMigrate(false);

        assertEquals(1, jsonNumber(body, "legacyFiles"), "实际=" + body);
        assertEquals(1, jsonNumber(body, "copied"), "应当复制出 1 个新文件，实际=" + body);
        assertEquals(1, jsonNumber(body, "deletedFiles"), "旧文件应当被删掉，实际=" + body);
        assertTrue(jsonNumber(body, "updatedRows") >= 1, "至少应当改到 1 行，实际=" + body);

        // ---- 磁盘：旧的没了、新的是哈希名、内容一模一样 ----
        assertFalse(Files.exists(localRoot().resolve(key)), "旧文件必须被删掉（它已经没人引用了）");
        String newKey = findHashedKey("cover");
        assertNotEquals(key, newKey, "文件名必须换成了哈希名");
        assertArrayEqualsForMessage(TINY_PNG, Files.readAllBytes(localRoot().resolve(newKey)));

        // ---- 库：两处引用都指向新地址 ----
        String newCover = coverOf(articleId);
        assertTrue(newCover.endsWith(newKey), "cover 应当被改写成新 key，实际=" + newCover);
        String content = contentOf(articleId);
        assertTrue(content.contains("/uploads/" + newKey),
                "正文里的（相对形式）引用同样要改写，实际=" + content);
        assertFalse(content.contains(key), "正文里不该再出现旧 key，实际=" + content);

        // ---- 端到端：新地址真的取得到（旧的取不到了） ----
        mockMvc.perform(get(pathOf(newKey))).andExpect(status().isOk());
        mockMvc.perform(get(pathOf(key))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("③ ★ 幂等：跑第二遍什么都不做（旧文件已经没了、引用里也没有旧 key 了）")
    void migrate_shouldBeIdempotent() throws Exception {
        String key = writeLegacyFile("music", TINY_MP3);
        long articleId = createArticle("幂等-" + mark, "正文", null, null);
        jdbc.update("UPDATE article SET content = ? WHERE id = ?", "试听 <audio src=\"" + absoluteUrl(key) + "\">", articleId);

        callMigrate(false);
        String secondRun = callMigrate(false);

        assertEquals(0, jsonNumber(secondRun, "legacyFiles"),
                "第二遍不该再有待迁移的文件，实际=" + secondRun);
        assertEquals(0, jsonNumber(secondRun, "deletedFiles"), "第二遍不该删任何东西");
    }

    @Test
    @DisplayName("④ ★ 内容相同的两个旧文件合并成一个：改完之后磁盘上只剩一份")
    void migrate_shouldMergeDuplicateContent() throws Exception {
        // 两个不同 UUID 名、内容完全相同的老文件 —— 这正是改造前"同一张图传两次"留下的形态，
        // 也是这次迁移要顺手消灭的东西
        String keyA = writeLegacyFile("cover", TINY_PNG);
        String keyB = writeLegacyFile("cover", TINY_PNG);

        String body = callMigrate(false);

        assertEquals(2, jsonNumber(body, "legacyFiles"));
        assertEquals(1, jsonNumber(body, "copied"), "两份内容相同，只该复制出一份，实际=" + body);
        assertEquals(1, jsonNumber(body, "mergedDuplicates"), "应当报告合并掉 1 个重复，实际=" + body);
        assertEquals(2, jsonNumber(body, "deletedFiles"), "两个旧文件都该被删掉");

        assertEquals(1, countFiles(), "磁盘上最后只该剩下一个文件");
        assertTrue(HASHED_NAME.matcher(Paths.get(findHashedKey("cover")).getFileName().toString())
                .matches(), "剩下的那个应当是哈希命名");
    }

    @Test
    @DisplayName("⑤ 没有任何引用的旧文件也会被改成哈希名（内容不丢），且报告里能看出它没人用")
    void migrate_shouldAlsoHandleUnreferencedFiles() throws Exception {
        String key = writeLegacyFile("cover", TINY_PNG);
        // 故意不建任何文章引用它 —— 模拟"上传了但没保存"留下的孤儿文件

        String body = callMigrate(false);

        assertEquals(1, jsonNumber(body, "legacyFiles"));
        assertEquals(1, jsonNumber(body, "unreferenced"),
                "报告里要能看出这个文件当时没有任何引用（体检指标），实际=" + body);

        assertFalse(Files.exists(localRoot().resolve(key)), "旧文件同样要改名（内容仍保留在新名下）");
        assertArrayEqualsForMessage(TINY_PNG, Files.readAllBytes(localRoot().resolve(findHashedKey("cover"))));
    }

    @Test
    @DisplayName("⑥ 已经是哈希命名的文件会被跳过（不重命名、也不重复算进待迁移）")
    void migrate_shouldSkipAlreadyHashedFiles() throws Exception {
        // 直接按"哈希命名"的规则造一个文件出来
        byte[] content = TINY_PNG;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String key = "cover/" + hash.substring(0, 2) + "/" + hash + ".png";
        Path path = localRoot().resolve(key);
        Files.createDirectories(path.getParent());
        Files.write(path, content);

        String body = callMigrate(false);

        assertEquals(0, jsonNumber(body, "legacyFiles"),
                "已经是哈希命名的文件不该被当成待迁移，实际=" + body);
        assertTrue(Files.exists(path), "它应当原封不动地留在那里");
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 一个最小的合法 PNG（1x1 像素） */
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
    };

    /** 一个最小的合法 mp3（ID3v2 头 + 一帧静音） */
    private static final byte[] TINY_MP3 = new byte[]{
            'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00
    };

    /**
     * 往上传目录里写一个【UUID 命名】的旧文件，返回它的 object key。
     *
     * 【为什么文件名要多加一个随机后缀】同一个用例里可能造两个"内容相同"的旧文件
     *   （见用例 ④）—— 它们必须有不同的 key，否则就是在写同一个文件。
     */
    private String writeLegacyFile(String category, byte[] content) throws Exception {
        String name = java.util.UUID.randomUUID().toString().replace("-", "")
                + "-" + SEQ.incrementAndGet() + ".png";
        String key = category + "/2026/09/" + name;
        Path path = localRoot().resolve(key);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
        return key;
    }

    /** 调用迁移接口，返回原始响应体（报告在 data 里） */
    private String callMigrate(boolean dryRun) throws Exception {
        return mockMvc.perform(post("/upload/migrate-legacy")
                        .param("dryRun", String.valueOf(dryRun))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long createArticle(String title, String content, String cover, String attachmentsJson) throws Exception {
        StringBuilder json = new StringBuilder("{");
        json.append("\"title\":\"").append(title).append("\"");
        json.append(",\"content\":\"").append(content).append("\"");
        if (cover != null) {
            json.append(",\"cover\":\"").append(cover).append("\"");
        }
        json.append(",\"status\":1");
        if (attachmentsJson != null) {
            json.append(",\"attachments\":").append(attachmentsJson);
        }
        json.append("}");

        String body = mockMvc.perform(post("/admin/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(json.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonNumber(body, "data");
    }

    /** 本站上传地址的绝对形式（与线上存量数据一样，base-url 是配置出来的） */
    private String absoluteUrl(String key) {
        String base = uploadProperties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/uploads/" + key;
    }

    private String coverOf(long articleId) {
        return jdbc.queryForObject("SELECT cover FROM article WHERE id = ?", String.class, articleId);
    }

    private String contentOf(long articleId) {
        return jdbc.queryForObject("SELECT content FROM article WHERE id = ?", String.class, articleId);
    }

    /** 找出某个目录下唯一那个哈希命名的 key（顺带断言"只有一个"） */
    private String findHashedKey(String category) throws Exception {
        try (Stream<Path> paths = Files.walk(localRoot().resolve(category))) {
            var found = paths.filter(Files::isRegularFile)
                    .filter(p -> HASHED_NAME.matcher(p.getFileName().toString()).matches())
                    .toList();
            assertEquals(1, found.size(), "该目录下应当恰好有一个哈希命名的文件，实际=" + found);
            return localRoot().relativize(found.get(0)).toString().replace('\\', '/');
        }
    }

    private Path localRoot() {
        return Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize();
    }

    private String pathOf(String key) {
        return "/uploads/" + key;
    }

    private long countFiles() throws Exception {
        if (!Files.isDirectory(localRoot())) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(localRoot())) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void wipeUploadDir() {
        Path root = localRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响断言（本用例目录是临时目录）
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    private void assertArrayEqualsForMessage(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual),
                "迁移前后的文件内容必须逐字节相同（迁移只改名，不动内容）");
    }

    private String jsonString(String body, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        assertTrue(matcher.find(), "响应里应当有字符串字段 " + key + "，实际=" + body);
        return matcher.group(1);
    }

    private long jsonNumber(String body, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        assertTrue(matcher.find(), "响应里应当有数字字段 " + key + "，实际=" + body);
        return Long.parseLong(matcher.group(1));
    }
}
