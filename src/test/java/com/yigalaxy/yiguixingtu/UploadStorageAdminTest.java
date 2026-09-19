package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import com.yigalaxy.yiguixingtu.upload.UploadReferenceChecker;
import com.yigalaxy.yiguixingtu.upload.UploadReferenceIndex;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 后台「存储管理」的用例
 *
 * 【这一组最要紧的三条】
 *   ① ★ 删除【只删真的没人用的】：即使前端传上来一个"被引用"的 key，
 *      接口也必须当场重算引用并拒绝 —— 删除不可逆，判据不能信客户端。
 *   ② ★ 索引与判据必须一致：页面展示用的是 UploadReferenceIndex（一次全量扫描），
 *      删除用的是 UploadReferenceChecker（按 key 精确匹配）。这是**同一件事的两份实现**，
 *      漂移的后果是"页面说没人用、点了删除却被拦住"（或更糟的反向）。
 *      下面有一条用例专门拿同一批数据问两边，把结论钉在一起。
 *   ③ 权限：它列的是全站文件清单、能删磁盘文件，绝不能对普通用户开放。
 * =====================================================================
 */
@TestPropertySource(properties = {
        "app.upload.local-dir=${java.io.tmpdir}/ygt-storage-admin-test"
})
class UploadStorageAdminTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 一个最小的合法 PNG（1x1 像素） */
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
    };

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
    private UploadReferenceIndex referenceIndex;

    @Autowired
    private UploadReferenceChecker referenceChecker;

    @Autowired
    private JdbcTemplate jdbc;

    private String mark;
    private String adminToken;
    private String guestToken;

    @BeforeEach
    void setUp() {
        mark = "SA" + SEQ.incrementAndGet() + "x" + (System.nanoTime() % 100000);

        User admin = insertUser("ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        User guest = insertUser("GUEST");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");

        wipeUploadDir();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM article_attachment WHERE article_id IN "
                + "(SELECT id FROM article WHERE title LIKE ?)", "%" + mark + "%");
        jdbc.update("DELETE FROM article WHERE title LIKE ?", "%" + mark + "%");
        jdbc.update("DELETE FROM user WHERE username LIKE ?", "%" + mark + "%");
        wipeUploadDir();
    }

    // ================================================================
    //  一、权限
    // ================================================================

    @Test
    @DisplayName("① 权限：清单与删除都只有管理员能用（无 token 401 / 游客 403）")
    void storageEndpoints_shouldRequireAdmin() throws Exception {
        mockMvc.perform(get("/admin/upload/storage")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/upload/delete-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"cover/x.png\"]}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/upload/storage")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"cover/x.png\"]}"))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    //  二、清单与引用
    // ================================================================

    @Test
    @DisplayName("② 清单：列出磁盘上的文件（名称 / 类型 / 大小 / URL），并标出引用与孤儿")
    void storage_shouldListFilesWithReferences() throws Exception {
        String coverUrl = upload("cover.png", "image/png", TINY_PNG);
        String orphanUrl = upload("orphan.png", "image/png", altOf(TINY_PNG));
        createArticle("存储清单" + mark, "正文", coverUrl);

        Map<String, Object> data = storageData();

        List<Map<String, Object>> files = filesOf(data);
        assertEquals(2, files.size(), "两个上传文件都该出现在清单里");
        assertEquals(1, ((Number) data.get("orphanCount")).intValue(),
                "只有一个文件没有任何地方引用（那个被用作封面的不算孤儿）");

        Map<String, Object> referenced = fileOfUrl(files, coverUrl);
        assertEquals("cover", referenced.get("category"), "类型取自目录前缀（cover）");
        assertTrue(referenced.get("name").toString().endsWith(".png"), "文件名带扩展名");
        assertEquals(TINY_PNG.length, ((Number) referenced.get("size")).intValue());
        assertFalse(referenced.get("url") == null, "要给出可直接展示的 URL");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) referenced.get("references");
        assertEquals(1, refs.size(), "这个封面被一篇文章引用");
        assertEquals("ARTICLE", refs.get(0).get("type"));
        assertEquals("封面", refs.get(0).get("field"), "要能看出命中在哪一列");
        assertTrue(refs.get(0).get("title").toString().contains("存储清单"),
                "要带上引用方的标题，否则站长不知道该去哪找，实际=" + refs.get(0).get("title"));

        Map<String, Object> orphan = fileOfUrl(files, orphanUrl);
        assertEquals(List.of(), orphan.get("references"), "没人用的那个文件引用清单是空的");
    }

    @Test
    @DisplayName("③ 引用能覆盖到别的模块：同一个文件同时被文章封面与音乐封面引用")
    void storage_shouldCollectReferencesFromMultipleTables() throws Exception {
        String url = upload("shared.png", "image/png", TINY_PNG);
        createArticle("跨模块" + mark, "正文", url);

        // 音乐那边也引用同一个文件（站长把同一张图既当文章封面又当曲目封面）
        mockMvc.perform(post("/admin/music")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"曲" + mark + "\",\"artist\":\"歌手\","
                                + "\"url\":\"/uploads/music/2026/09/whatever.mp3\","
                                + "\"cover\":\"" + url + "\",\"sort\":0,\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>)
                fileOfUrl(filesOf(storageData()), url).get("references");

        List<String> types = refs.stream().map(r -> r.get("type").toString()).sorted().toList();
        assertEquals(List.of("ARTICLE", "MUSIC"), types,
                "两处引用都要出现（只查一张表的话，这里会少一个）—— 实际=" + refs);

        jdbc.update("DELETE FROM music WHERE title LIKE ?", "%" + mark + "%");
    }

    @Test
    @DisplayName("④ ★ 索引与判据一致：清单说「没人用」的文件，判据也必须说「没人用」")
    void indexAndChecker_shouldAgree() throws Exception {
        // 【为什么要专门测这个】它们是同一件事的两份实现：
        //   展示用 UploadReferenceIndex（一次全表扫描 + Java 里抠 key），
        //   判据用 UploadReferenceChecker（每张表一条按 key 精确匹配的 SQL）。
        //   漂移的后果很具体：页面显示"无人引用"，站长点删除却被后端拦住
        //   （或者反过来 —— 那个更糟）。这条用例拿同一批数据同时问两边，把结论钉死。
        String referencedUrl = upload("ref.png", "image/png", TINY_PNG);
        String orphanUrl = upload("free.png", "image/png", altOf(TINY_PNG));
        String inlineUrl = upload("inline.png", "image/png", altOf(altOf(TINY_PNG)));
        createArticle("一致性" + mark, "正文 ![配图](" + inlineUrl + ")", referencedUrl);

        Map<String, List<com.yigalaxy.yiguixingtu.upload.UploadReference>> index = referenceIndex.build();

        for (Map<String, Object> file : filesOf(storageData())) {
            String key = file.get("key").toString();
            boolean indexSaysReferenced = index.containsKey(key);
            boolean checkerSaysReferenced = referenceChecker.isReferenced(key);
            assertEquals(checkerSaysReferenced, indexSaysReferenced,
                    "两份实现对同一个文件给出了不同结论: key=" + key
                            + "（索引说 " + indexSaysReferenced + "，判据说 " + checkerSaysReferenced + "）");
        }

        // 顺带确认这两份实现真的读到了东西（都是 false 的话上面那条等于没测）
        assertTrue(index.containsKey(keyOf(referencedUrl)), "被当封面的那个必须被认出来");
        assertTrue(index.containsKey(keyOf(inlineUrl)), "正文里的那个也必须被认出来");
        assertFalse(index.containsKey(keyOf(orphanUrl)), "没人用的那个不该被认出来");

        assertEquals(3, filesOf(storageData()).size(), "三个文件都在磁盘上");
    }

    // ================================================================
    //  三、删除
    // ================================================================

    @Test
    @DisplayName("⑤ 删除：没人引用的文件被真的删掉，报告里如实列出")
    void deleteFiles_shouldRemoveOrphans() throws Exception {
        String orphanUrl = upload("gone.png", "image/png", TINY_PNG);
        String orphanKey = keyOf(orphanUrl);
        assertTrue(Files.exists(localRoot().resolve(orphanKey)), "前置条件：文件已落盘");

        String body = mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteJson(orphanKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted.length()").value(1))
                .andExpect(jsonPath("$.data.skippedReferenced.length()").value(0))
                .andExpect(jsonPath("$.data.failed.length()").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains(orphanKey), "报告里要带上被删掉的 key，实际=" + body);
        assertFalse(Files.exists(localRoot().resolve(orphanKey)), "文件必须真的从磁盘上消失");
    }

    @Test
    @DisplayName("⑥ ★ 删除【不信任前端】：被引用的文件即使被传上来也会被拒绝，且文件还在")
    void deleteFiles_shouldRefuseReferencedFiles() throws Exception {
        // 这一条模拟的是"页面上那份清单已经过期"：站长打开存储页时这个文件还没人用，
        // 他在另一个标签页把它设成了某篇文章的封面，然后回来点了删除。
        // 前端那份 references 是旧的（空的），所以它会把 key 传上来 ——
        // 后端必须当场重算，否则就删掉了正在用的图（而且不可逆）。
        String url = upload("inuse.png", "image/png", TINY_PNG);
        String key = keyOf(url);
        createArticle("删除拒" + mark, "正文", url);

        mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteJson(key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted.length()").value(0))
                .andExpect(jsonPath("$.data.skippedReferenced.length()").value(1));

        assertTrue(Files.exists(localRoot().resolve(key)),
                "★ 被引用的文件绝不能被删（删了那篇文章就裂图，而且文件回不来）");
    }

    @Test
    @DisplayName("⑦ 删除：一次传多个 key 时逐个判定，删的删、跳的跳")
    void deleteFiles_shouldJudgeEachKeySeparately() throws Exception {
        String usedUrl = upload("used.png", "image/png", TINY_PNG);
        String freeUrl = upload("free2.png", "image/png", altOf(TINY_PNG));
        createArticle("逐个判" + mark, "正文", usedUrl);

        mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"" + keyOf(usedUrl) + "\",\"" + keyOf(freeUrl) + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted.length()").value(1))
                .andExpect(jsonPath("$.data.skippedReferenced.length()").value(1));

        assertTrue(Files.exists(localRoot().resolve(keyOf(usedUrl))), "被引用的那个要留着");
        assertFalse(Files.exists(localRoot().resolve(keyOf(freeUrl))), "没人用的那个要删掉");
    }

    @Test
    @DisplayName("⑧ 删除：空列表被拒（@NotEmpty），一次超过 500 个也被拒")
    void deleteFiles_shouldValidateInput() throws Exception {
        mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add("cover/x" + i + ".png");
        }
        String json = "{\"keys\":[" + tooMany.stream().map(k -> "\"" + k + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]}";
        mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑨ 删除：不存在的 key 视为已完成（幂等），不报错")
    void deleteFiles_shouldBeIdempotent() throws Exception {
        // 删除的语义是"让它不存在"——本来就不存在也算达成（与 FileStorage.delete 一致）。
        // 双击删除按钮、或者两个标签页同时清理，都会走到这条路径上
        mockMvc.perform(post("/admin/upload/delete-files")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteJson("cover/ff/not-exist.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted.length()").value(1))
                .andExpect(jsonPath("$.data.failed.length()").value(0));
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 调一次清单接口，取出 data（顺带断言结构正确） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> storageData() throws Exception {
        String body = mockMvc.perform(get("/admin/upload/storage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return (Map<String, Object>) com.jayway.jsonpath.JsonPath.read(body, "$.data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filesOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("files");
    }

    /** 按 URL 找到清单里的那个文件（url 是清单里唯一能直接对应上传结果的东西） */
    private Map<String, Object> fileOfUrl(List<Map<String, Object>> files, String url) {
        String key = keyOf(url);
        return files.stream()
                .filter(f -> f.get("key").equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("清单里找不到这个文件: " + key));
    }

    /** URL（可能是绝对地址）→ objectKey */
    private String keyOf(String url) {
        return url.substring(url.indexOf("/uploads/") + "/uploads/".length());
    }

    private String deleteJson(String key) {
        return "{\"keys\":[\"" + key + "\"]}";
    }

    /** 上传一个文件，返回后端给的 URL */
    private String upload(String filename, String contentType, byte[] content) throws Exception {
        String body = mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", filename, contentType, content))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.url");
    }

    private void createArticle(String title, String content, String cover) throws Exception {
        mockMvc.perform(post("/admin/article")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"cover\":\"" + cover + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private User insertUser(String role) {
        User user = new User();
        user.setUsername("test_" + role.toLowerCase() + "_storage_" + mark + "_" + role);
        user.setPassword(passwordEncoder.encode("x"));
        user.setNickname("存储管理测试用户");
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    /** 复制一份内容并改掉最后一个字节（造"另一个文件"，内容寻址下字节不同才是两个文件） */
    private static byte[] altOf(byte[] source) {
        byte[] copy = java.util.Arrays.copyOf(source, source.length + 1);
        copy[source.length] = 0x42;
        return copy;
    }

    private Path localRoot() {
        return Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize();
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
                    // 清理失败不影响断言
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
