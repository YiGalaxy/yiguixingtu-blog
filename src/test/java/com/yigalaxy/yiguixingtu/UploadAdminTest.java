package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 文件上传接口测试（UploadController / UploadService）
 *
 * 【测试用的是哪种存储】
 *   默认的 local（本地磁盘）—— 见下面 @TestPropertySource 把目录指到一个临时路径。
 *   这样测试【不需要任何 OSS 凭据、不连外网】，但仍然把整条路径跑通了：
 *   上传 → 校验 → 写文件 → 返回 URL → 这个 URL 能被匿名 GET 到。
 *   比 mock 掉存储更有价值：mock 只能证明"我调用了它"，而这里证明"文件真的落盘了"。
 *
 * ⚠️ 诚实说明：{@code OssFileStorage} 这一段【没有自动化测试】——
 *    它需要真实的 OSS 凭据与公网，不适合放进 CI。
 *    它里只有"拼 URL + 调官方 SDK"两件事，风险集中在配置（endpoint/bucket/密钥），
 *    所以那部分用启动时校验来兜（缺配置直接启动失败）。
 *
 * 【用到的东西】
 *   · {@link MockMultipartFile} —— 在测试里伪造一个 multipart 上传项，
 *     不用真的发 HTTP 请求，却能走完整的 Spring 绑定与校验链路
 *   · {@code multipart("/upload").file(...)} —— MockMvc 构造 multipart 请求的写法
 *   · {@link TestPropertySource} —— 只覆盖本测试类需要的几项配置
 * =====================================================================
 */
@TestPropertySource(properties = {
        // 上传目录指到临时路径，避免在项目目录里留下测试垃圾文件
        "app.upload.local-dir=${java.io.tmpdir}/ygt-upload-test",
        // 上限设小一点，这样"超大文件"用例不用真的造一个 5MB 的数组
        "app.upload.max-size=1KB"
})
class UploadAdminTest extends AbstractIntegrationTest {

    /** 一个最小的合法 PNG（1x1 像素）—— 内容不重要，扩展名与大小才是被测的点 */
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
    };

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UploadProperties uploadProperties;

    private String adminToken;
    private String guestToken;

    @BeforeEach
    void setUp() {
        User admin = insertUser("test_admin_upload", "ADMIN");
        User guest = insertUser("test_guest_upload", "GUEST");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        guestToken = jwtUtil.generateToken(guest.getId(), guest.getUsername(), "GUEST");
    }

    // ================================================================
    // 一、权限：上传是写操作，只给管理员
    // ================================================================

    @Test
    @DisplayName("① 不带 token 上传 -> 401")
    void noToken_shouldReturn401() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "a.png", "image/png", TINY_PNG)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("② 游客带合法 token 上传 -> 403（登录了但不够格）")
    void guest_shouldReturn403() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "a.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    // ================================================================
    // 二、正常路径：文件真的落盘了，而且返回的 URL 能匿名访问
    // ================================================================

    @Test
    @DisplayName("③ 管理员上传 png -> 返回 URL，且文件真的写到了磁盘上")
    void adminUploadPng_shouldReturnUrlAndWriteFile() throws Exception {
        String body = mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").isString())
                .andReturn().getResponse().getContentAsString();

        // 从返回的 JSON 里取出 URL（不引 JSON 库，直接找 /uploads/ 后面那段）
        String url = extractUrl(body);
        assertNotNull(url, "返回体里应当有 url，实际=" + body);
        assertTrue(url.contains("/uploads/cover/"),
                "URL 应当带上按日期分目录的路径，实际=" + url);

        // 【关键断言】光断言 HTTP 200 是不够的 —— 那只能说明接口没报错。
        // 这里直接去磁盘上确认文件真的存在、内容也对，才证明整条链路真的通了
        String objectKey = url.substring(url.indexOf("/uploads/") + "/uploads/".length());
        Path saved = Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize().resolve(objectKey);
        assertTrue(Files.exists(saved), "文件应当真的写到磁盘上: " + saved);
        assertEquals(TINY_PNG.length, Files.readAllBytes(saved).length, "落盘的内容长度应当与上传的一致");
    }

    @Test
    @DisplayName("④ 上传后的图片能被【匿名】GET 到（否则前台全是裂图）")
    void uploadedFile_shouldBePubliclyReadable() throws Exception {
        String body = mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String url = extractUrl(body);
        String path = url.substring(url.indexOf("/uploads/"));

        // 不带任何 token 去读：封面图是给所有访客看的（包括未登录的游客），
        // 如果这里返回 401，前台就全是裂图 —— 而且这种问题很隐蔽：
        // 后台上传明明成功，前台就是不显示
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("⑤ 文件名会被重命名（不采用用户传来的原始名字）")
    void upload_shouldRenameFile() throws Exception {
        String body = mockMvc.perform(multipart("/upload")
                        // 故意传一个带路径、带怪符号、带中文的文件名
                        .file(new MockMultipartFile("file", "../../我的封面 v2.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String url = extractUrl(body);
        assertTrue(url != null && url.endsWith(".png"), "扩展名应当保留，实际=" + url);
        assertTrue(!url.contains(".."), "返回的 URL 里不能出现路径穿越的片段，实际=" + url);
        assertTrue(!url.contains("我的封面"), "文件名应当被 UUID 替换掉，不该出现原始名字，实际=" + url);
    }

    // ================================================================
    // 三、非法输入：类型白名单与大小限制
    // ================================================================

    @Test
    @DisplayName("⑥ 上传 .exe -> 被扩展名白名单拒绝")
    void upload_executable_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "evil.exe", "application/octet-stream",
                                "MZ".getBytes()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // 按本项目的统一约定：业务类错误返回 HTTP 200 + body.code（见 README「统一返回」）
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只允许上传")));
    }

    @Test
    @DisplayName("⑦ 上传 jsp 之类的可执行脚本 -> 同样被拒（白名单只放行图片）")
    void upload_scriptFile_shouldBeRejected() throws Exception {
        // 用黑名单思路就会漏掉这种：扩展名不是常见的可执行文件，但它能在服务器上被解析执行
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "shell.jsp", "image/jpeg",
                                "<% out.print(1); %>".getBytes()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑧ 超过大小上限 -> 被拒，并且给出用户看得懂的提示")
    void upload_oversize_shouldBeRejected() throws Exception {
        // 上限在 @TestPropertySource 里设成了 1KB，这里给 4KB
        byte[] big = new byte[4096];
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能超过")));
    }

    @Test
    @DisplayName("⑨ 空文件 -> 被拒（不能存一个 0 字节的图）")
    void upload_emptyFile_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑩ 没有扩展名的文件 -> 被拒")
    void upload_noExtension_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "noextension", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 从返回体里抠出 url 字段的值（简单正则，避免为此引入 JSON 解析依赖） */
    private String extractUrl(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private User insertUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("123456"));
        u.setNickname("上传测试用户");
        u.setRole(role);
        u.setStatus(1);
        u.setDeleted(0);
        userMapper.insert(u);
        return u;
    }
}
