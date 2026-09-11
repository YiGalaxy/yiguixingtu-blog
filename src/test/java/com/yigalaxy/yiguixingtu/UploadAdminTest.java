package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.upload.FileStorage;
import com.yigalaxy.yiguixingtu.upload.LocalFileStorage;
import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

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
 *   本地磁盘（项目现在只有这一种）—— 见下面 @TestPropertySource 把目录指到一个临时路径。
 *   这样测试【不需要任何云端凭据、不连外网】，但仍然把整条路径跑通了：
 *   上传 → 校验 → 写文件 → 返回 URL → 这个 URL 能被匿名 GET 到。
 *   比 mock 掉存储更有价值：mock 只能证明"我调用了它"，而这里证明"文件真的落盘了"。
 *
 * 【历史上这里有一条"诚实说明"，现在它已经不适用了】
 *   以前写着"{@code OssFileStorage} 没有自动化测试，因为它需要真实凭据与公网"。
 *   那正是后来把整套 OSS 实现删掉的原因之一：**一条没人走过、CI 也验证不了的代码路径**，
 *   它坏了不会有任何测试变红，只会等线上出问题。所以现在存储只有一种实现，
 *   本测试类覆盖的就是线上真正跑的那条路。
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
        // 上限设小一点，这样"超大文件"用例不用真的造一个 10MB 的数组
        // （图片上限 2026-09 从 5MB 提到 10MB；本类只关心"按 type 分流"，数值本身
        //   由 ArticleAttachmentTest ④ 读 application.properties 原文钉住）
        "app.upload.max-size=1KB",
        // 音频的上限也设小（2MB），理由同上：不必为了测"超限"真的造一个 21MB 的数组。
        // ⚠️ 它【必须大于】图片的 1KB —— 下面有一条用例专门证明
        // "同一份 1.5KB 的内容，作为图片被拒、作为音频被接受"，
        // 两个上限如果一样就证明不了"规则真的按 type 分流了"
        "app.upload.audio-max-size=2MB"
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

    /**
     * 直接拿容器，用来数"一共有几个 FileStorage 实现"。
     * 【为什么不用注入 List&lt;FileStorage&gt;】那样在"一个都没有"时会注入失败，
     * 而这条用例恰恰要区分"0 个 / 1 个 / 2 个"三种情况，读容器最直白。
     */
    @Autowired
    private ApplicationContext context;

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

    @Test
    @DisplayName("⑪ 容器里只有一种存储实现（本地磁盘），没有第二个“可切换”的实现")
    void fileStorage_shouldHaveExactlyOneImplementation() {
        // 【这条用例守的是"删干净了"，而不是某个功能】
        //   项目早期有 LocalFileStorage + OssFileStorage 两个实现，靠 app.upload.storage
        //   这个开关选一个。现在定为只用本地磁盘，OSS 那套（实现类 + 依赖 + 一堆
        //   oss-* 配置）已经删掉了 —— 但"删掉"这件事很容易被后人无意中回滚：
        //   比如从旧分支拷回一个 OssFileStorage 文件，那时容器里就有两个 FileStorage
        //   候选 Bean，注入会直接失败（而且报错是启动期的 NoUniqueBeanDefinitionException，
        //   看起来像配置问题）。所以这里把"只有一个实现、且它必须是本地磁盘"钉住。
        //
        //   顺带说明为什么"多一个没人用的实现"值得专门写用例拦：
        //   那条路径没有测试覆盖、也没有人走过，坏了不会有任何用例变红 ——
        //   它只会在某天"顺手切过去试试"时炸在线上。
        Map<String, FileStorage> beans = context.getBeansOfType(FileStorage.class);
        assertEquals(1, beans.size(),
                "存储实现应当只有一个，实际=" + beans.keySet());
        assertTrue(beans.values().iterator().next() instanceof LocalFileStorage,
                "唯一的存储实现必须是本地磁盘：实际=" + beans.values().iterator().next().getClass().getName());
    }

    @Test
    @DisplayName("⑫ 存储路径前缀取自 app.upload.key-prefix（改名后仍然生效）")
    void upload_objectKey_shouldUseConfiguredPrefix() throws Exception {
        // 前缀这个配置项原本叫 oss-key-prefix（跟着 OSS 一起进来的名字），
        // 现在存储只有本地磁盘，名字已经改成 key-prefix。改名属于"只改字符串"的改动，
        // 编译器帮不上忙 —— 配置名写错不会报错，只会让前缀悄悄变成默认值。
        // 所以这里直接读配置对象 + 断言真实上传出来的 URL，两头都钉住。
        assertEquals("cover", uploadProperties.getKeyPrefix(),
                "默认前缀应当是 cover（application.properties 的 app.upload.key-prefix）");

        String url = extractUrl(mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "prefix.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertNotNull(url, "上传应当返回 url");
        assertTrue(url.contains("/uploads/cover/"),
                "URL 里应当带上配置的前缀（形如 /uploads/cover/2026/09/xxx.png），实际=" + url);
    }

    // ================================================================
    //  四、音频上传（type=audio）—— 音乐模块的曲目文件走这条路
    //
    //  【这一组用例要同时钉住【两个方向】】
    //    ① 音频这一套规则真的生效：mp3 能传、存到 uploads/music/、落盘、能匿名取到
    //    ② 图片那一套规则【没有被顺手放宽】：默认（不传 type）仍然拒 mp3、
    //       上限仍是图片的 1KB（不是音频的 2MB）、音频接口也仍然拒 png
    //    ② 才是这组用例里最值钱的部分 —— 放宽一个白名单或改大一个数字，
    //    不会有任何报错，只会让"封面图字段被填成音频地址"这种事很久以后才被发现。
    // ================================================================

    /** 一个最小的合法 mp3（ID3v2 头 + 一帧静音）—— 内容不重要，扩展名与大小才是被测的点 */
    private static final byte[] TINY_MP3 = new byte[]{
            'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00
    };

    @Test
    @DisplayName("⑬ type=audio 上传 mp3 -> 返回 URL、文件真的落在 uploads/music/ 下、且能被匿名取到")
    void audioUpload_shouldWriteFileUnderMusicDirAndBeReadable() throws Exception {
        String body = mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "夜曲.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", "audio")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 返回结构【没有变】：地址仍然在 data.url（前端上传组件只读它）
                .andExpect(jsonPath("$.data.url").isString())
                .andReturn().getResponse().getContentAsString();

        String url = extractUrl(body);
        assertNotNull(url, "返回体里应当有 url，实际=" + body);
        assertTrue(url.contains("/uploads/music/"),
                "音频应当存到与图片【不同】的子目录（/uploads/music/...），实际=" + url);
        assertTrue(url.endsWith(".mp3"), "扩展名应当保留，实际=" + url);
        assertTrue(!url.contains("夜曲"), "文件名应当被 UUID 替换掉（歌名带中文/空格很常见），实际=" + url);

        // 【关键断言】落盘 + 内容一致：只断言 HTTP 200 只能说明接口没报错
        String objectKey = url.substring(url.indexOf("/uploads/") + "/uploads/".length());
        Path saved = Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize().resolve(objectKey);
        assertTrue(Files.exists(saved), "音频文件应当真的写到磁盘上: " + saved);
        assertEquals(TINY_MP3.length, Files.readAllBytes(saved).length, "落盘的内容长度应当与上传的一致");

        // 静态映射 /uploads/** 覆盖子目录 —— 不验证的话"后台上传成功、前台播放器 404"
        mockMvc.perform(get(url.substring(url.indexOf("/uploads/")))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("⑭ type=audio 传 .png -> 拒（音频接口不接受图片，方向一）")
    void audioUpload_withPng_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .param("type", "audio")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("只允许上传 mp3 格式的音频")));
    }

    @Test
    @DisplayName("⑮ 不传 type 传 .mp3 -> 拒（默认仍是图片规则，方向二：音频扩展名没被放宽到图片上）")
    void defaultUpload_withMp3_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", TINY_MP3))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("只允许上传 jpg / jpeg / png / gif / webp 格式的图片")));
    }

    @Test
    @DisplayName("⑯ type=image 显式指定也一样拒 mp3（默认与 type=image 必须是同一个结果）")
    void imageTypeUpload_withMp3_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", "image")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑰ 大小上限按 type 分流：同一份 1.5KB 内容，图片被拒（1KB）、音频被接受（2MB）")
    void sizeLimit_shouldDependOnType() throws Exception {
        // 上限在 @TestPropertySource 里设成：图片 1KB、音频 2MB
        byte[] content = new byte[1536];
        assertEquals(1536, content.length, "前置条件：这份内容比图片上限(1024)大、比音频上限(2MB)小");

        // 作为图片：超限被拒 —— 说明"音频开到了 2MB"没有顺带把图片也放宽
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "big.png", "image/png", content))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("图片不能超过")));

        // 作为音频：同样的字节数被接受 —— 说明音频用的是另一套上限，不是"统一调大了"
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "big.mp3", "audio/mpeg", content))
                        .param("type", "audio")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value(
                        org.hamcrest.Matchers.containsString("/uploads/music/")));
    }

    @Test
    @DisplayName("⑱ 超过音频大小上限（2MB）-> 拒，并且给出用户看得懂的提示")
    void audioUpload_oversize_shouldBeRejected() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024];
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "big.mp3", "audio/mpeg", big))
                        .param("type", "audio")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("音频不能超过")));
    }

    @Test
    @DisplayName("⑲ 不认识的 type（video / audo 打错字）-> 400，提示只支持 image / audio")
    void unknownType_shouldBeRejected() throws Exception {
        // 【为什么未知类型要报错，而不是"回落到图片"】
        //   回落到图片的话，type=audo（打错一个字）的表现是
        //   "上传 mp3 被拒，提示只允许 jpg…" —— 用户看到的是"格式不支持"，
        //   真正的原因却是参数名拼错了。报"不支持的类型"才有指向。
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "a.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", "audo")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("只支持 image / audio")));
    }

    @Test
    @DisplayName("⑳ 音频上传同样只有管理员能干：无 token 401、游客 403")
    void audioUpload_shouldRequireAdmin() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", "audio"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", "audio")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("㉑ type 大小写不敏感、带空格也能认（type=Audio / ' audio '）")
    void audioType_shouldBeCaseInsensitiveAndTrimmed() throws Exception {
        mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", " Audio ")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value(
                        org.hamcrest.Matchers.containsString("/uploads/music/")));
    }

    @Test
    @DisplayName("㉒ 两个前缀各自取自配置：图片 cover、音频 music（改名后仍然生效）")
    void bothPrefixes_shouldComeFromConfiguration() throws Exception {
        assertEquals("cover", uploadProperties.getKeyPrefix(),
                "图片前缀应当来自 app.upload.key-prefix");
        // 音频前缀在 @TestPropertySource 里没有覆盖，取 application.properties 的默认值 music
        assertEquals("music", uploadProperties.getAudioKeyPrefix(),
                "音频前缀应当来自 app.upload.audio-key-prefix");

        String audioUrl = extractUrl(mockMvc.perform(multipart("/upload")
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", TINY_MP3))
                        .param("type", "audio")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertNotNull(audioUrl, "上传应当返回 url");
        assertTrue(audioUrl.contains("/uploads/music/"),
                "音频 URL 里应当带上配置的前缀（形如 /uploads/music/2026/09/xxx.mp3），实际=" + audioUrl);
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
