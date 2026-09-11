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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 文章附件（article_attachment）的集成测试
 *
 * 【它验证的是哪一整条链路】
 *   上传（含白名单 / 大小 / 总容量）→ 随表单保存（整体替换）→ 详情接口读出来
 *   → 下载时的响应头 → 删文章时把"这篇文章独占的文件"清干净（共用的一律不动）。
 *   注意这里【不测"附件单独落库"】：附件本来就不单独落库（见 V14 的注释），
 *   所以所有断言都是"保存文章之后库里/磁盘上是什么样"。
 *
 * =====================================================================
 * 【⚠️ 为什么这个类里【只有几条】用例关了测试事务（NOT_SUPPORTED）】
 *
 *   基类 AbstractIntegrationTest 上是 @Transactional：跑完自动回滚，
 *   大部分用例靠它保持库干净 —— 本类里绝大多数用例也是这样。
 *
 *   但"删物理文件"这个行为是【事务之外】的：它专门设计成"事务提交之后才执行"
 *   （理由见 ArticleServiceImpl.deleteFilesAfterCommit：删除不可逆，
 *     而事务可能回滚，先提交再删文件，最坏只是留下一个垃圾文件）。
 *   于是：
 *     · 跑在测试事务里（永远只回滚）的用例，afterCommit 回调【永远不会触发】，
 *       "文件被删掉了"这条断言就永远是真——因为它压根没被删过。
 *       那种用例是【假绿】：把删除逻辑整段注释掉它也一样过。
 *     · 所以涉及文件删除的用例（⑨⑩⑬⑭）显式声明
 *       {@code @Transactional(propagation = NOT_SUPPORTED)}，让业务真的提交，
 *       测的就是线上那套事务行为。
 *
 *   代价与 OperationLogTest 完全相同：数据真的要自己清理，见 @AfterEach。
 *   （同一个取舍在该类的类注释里写得最细，本类照它办。）
 *
 * 【⚠️ 另一个必须自己负责的清理对象：上传目录】
 *   文件写入不受数据库事务保护 —— 任何用例里的一次上传都会真的落盘。
 *   所以 @BeforeEach 与 @AfterEach 都会把测试专用的上传目录整个删掉：
 *     ① 保证 ⑤ 那条"总容量护栏"用例从 0 字节开始算（否则别的用例留下的文件
 *        会让占用数飘忽不定，测试变成随机红）
 *     ② 不在本机临时目录里堆垃圾
 *
 * 【数据库侧为什么用唯一标记】
 *   所有测试类共用同一个 ApplicationContext 与同一个数据库（README 测试约定 3），
 *   所以每个用例的标题、用户名里都带一个本次用例唯一的标记（mark），
 *   断言与清理都按它圈定范围 —— 绝不依赖"库里此刻有什么"。
 * =====================================================================
 */
@TestPropertySource(properties = {
        // 上传目录指到本类专用的临时路径：与其它测试类互不干扰，
        // 而且"总容量"这条用例可以从一个空目录开始量
        "app.upload.local-dir=${java.io.tmpdir}/ygt-attachment-test",
        // 【为什么把上限调小】不是真去造一个 100MB 的文件，也不是真去写 5GB：
        //   · 附件上限 4KB：一条 5KB 的"超大附件"就能覆盖"超过单文件上限被拒"
        //   · 总容量 6KB：两个 3KB 的附件正好压满，第三个 1 字节的就会被拒
        // 数值本身（100MB / 5GB）由用例 ④ 直接读 application.properties 原文钉住 ——
        // 那才是"配置里到底写了什么"这个事实，与"机制是否生效"是两件事，分开测。
        "app.upload.attachment-max-size=4KB",
        "app.upload.max-total-size=6KB"
})
class ArticleAttachmentTest extends AbstractIntegrationTest {

    /** 一个最小的合法 PNG（1x1 像素）—— 用来造"正文引用的图 / 封面图" */
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
    };

    /** 一个最小的合法 mp3（ID3v2 头 + 一帧静音）—— 用来验证"附件目录里的 mp3 也强制下载" */
    private static final byte[] TINY_MP3 = new byte[]{
            'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00
    };

    /** 附件白名单里【必须】同时包含与【绝不能】包含的扩展名（用例 ③ 用） */
    private static final List<String> EXPECTED_ATTACHMENT_EXTENSIONS = List.of(
            "pdf", "zip", "7z", "rar",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json",
            "mp3", "mp4");

    /**
     * 【绝不能被浏览器执行】的扩展名。
     * 每一个都必须在附件白名单之外，而且上传时必须被拒 —— 两条断言都要有：
     *   · 只断言"上传被拒"：白名单哪天被人顺手加了 svg，上传用例仍然会红（好事），
     *     但如果有人把它加到【别的类型】的白名单里呢？所以还要断言名单本身
     *   · 只断言"名单里没有"：代码里可能有别的分支放行它。所以行为也要测
     */
    private static final List<String> SCRIPT_CAPABLE_EXTENSIONS = List.of(
            "html", "htm", "svg", "xml", "js", "mjs", "css");

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
        // 本次用例的唯一标记：进标题与用户名，用于清理与圈定断言范围
        mark = "AT" + SEQ.incrementAndGet() + "x" + (System.nanoTime() % 100000);

        User admin = insertAdmin();
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");

        // 从"空的上传目录"开始：见类注释里为什么这件事必须做
        wipeUploadDir();
    }

    @AfterEach
    void tearDown() {
        // 【清理真提交的数据】只有 NOT_SUPPORTED 的用例会留下东西，
        // 但对回滚的用例执行这几条也没坏处（它们跑在事务里，随事务一起回滚）
        jdbc.update("DELETE FROM article_attachment WHERE article_id IN "
                + "(SELECT id FROM article WHERE title LIKE ?)", "%" + mark + "%");
        jdbc.update("DELETE FROM article WHERE title LIKE ?", "%" + mark + "%");
        jdbc.update("DELETE FROM user WHERE username LIKE ?", "%" + mark + "%");

        // 上传目录整个删掉（文件不受事务保护，必须自己清）
        wipeUploadDir();
    }

    // ================================================================
    //  一、上传：白名单 / 大小 / 名字 / 目录
    // ================================================================

    @Test
    @DisplayName("① 附件能上传：返回 url+name+size，文件真的落到 uploads/attachment/ 下，且能被匿名取到")
    void attachmentUpload_shouldSucceed() throws Exception {
        byte[] content = new byte[512];
        Arrays.fill(content, (byte) 'P');

        Uploaded uploaded = upload("attachment", "毕业论文 v2.pdf", "application/pdf", content);

        assertNotNull(uploaded.url(), "上传应当返回 url");
        assertTrue(uploaded.url().contains("/uploads/attachment/"),
                "附件应当存到与封面/音乐【不同】的子目录，实际=" + uploaded.url());
        assertTrue(uploaded.url().endsWith(".pdf"), "扩展名应当保留，实际=" + uploaded.url());
        assertEquals("毕业论文 v2.pdf", uploaded.name(), "返回的 name 应当是上传时的原始文件名");
        assertEquals(512L, uploaded.size(), "返回的 size 应当是文件的字节数");

        // 【关键断言】落盘 + 内容一致：只断言 HTTP 200 只能说明接口没报错
        Path saved = fileOf(uploaded.url());
        assertTrue(Files.exists(saved), "附件应当真的写到磁盘上: " + saved);
        assertEquals(512, Files.readAllBytes(saved).length, "落盘的内容长度应当与上传的一致");

        // 静态映射 /uploads/** 覆盖子目录 —— 不验证的话会出现"上传成功、点下载却 404"
        mockMvc.perform(get(pathOf(uploaded.url()))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("①b 图片上传同样返回 name/size（对现有接口的向后兼容扩展，老的 data.url 没变）")
    void imageUpload_shouldAlsoReturnNameAndSize() throws Exception {
        Uploaded uploaded = upload(null, "cover.png", "image/png", TINY_PNG);

        assertTrue(uploaded.url().contains("/uploads/cover/"), "不传 type 仍然按图片规则，实际=" + uploaded.url());
        assertEquals("cover.png", uploaded.name(),
                "图片也要带 name —— 三个字段是同一个返回结构，不能只有附件有");
        assertEquals(TINY_PNG.length, uploaded.size());
    }

    @Test
    @DisplayName("①c 附件名会被清洗：去掉路径、截断到 100 字，且保证扩展名还在")
    void attachmentName_shouldBeSanitizedAndTruncated() throws Exception {
        // 故意传一个带路径、带 .. 、还有 150 个字符的名字：
        // 它会被存进 article_attachment.name（varchar(100)），
        // 不清洗的话用户"上传成功、保存文章时却报名字过长"（而那条错跟他做的事毫无关系）
        String longName = "../../" + "a".repeat(150) + ".pdf";
        Uploaded uploaded = upload("attachment", longName, "application/pdf", new byte[64]);

        assertEquals(100, uploaded.name().length(), "名字应当被截断到 100 字，实际=" + uploaded.name());
        assertTrue(uploaded.name().endsWith(".pdf"),
                "截断要保留扩展名，否则用户看不到文件类型，实际=" + uploaded.name());
        assertFalse(uploaded.name().contains("/"), "路径片段必须被去掉，实际=" + uploaded.name());
        assertFalse(uploaded.name().contains(".."), "路径穿越片段必须被去掉，实际=" + uploaded.name());
    }

    @Test
    @DisplayName("② 超过附件单文件上限（4KB）-> 被拒，且磁盘上不会留下半个文件")
    void attachmentOversize_shouldBeRejected() throws Exception {
        byte[] big = new byte[5 * 1024];

        String message = uploadRejected("attachment", "big.pdf", "application/pdf", big);

        assertTrue(message.contains("附件不能超过"),
                "提示要说明是【附件】这一类超限（而不是笼统的「文件太大」），实际=" + message);
        assertEquals(0, countFiles(), "被拒的上传不该在磁盘上留下任何文件");
    }

    @Test
    @DisplayName("③ 扩展名白名单外的被拒；html/htm/svg/xml/js/mjs/css 逐个被拒（XSS 入口）")
    void attachmentWhitelist_shouldRejectEverythingElse() throws Exception {
        // ① 普通可执行文件：白名单思路必然要挡住的第一类
        String exeMessage = uploadRejected("attachment", "evil.exe", "application/octet-stream", "MZ".getBytes());
        assertTrue(exeMessage.contains("只允许上传") && exeMessage.contains("格式的附件"),
                "提示里要列出允许的格式，实际=" + exeMessage);

        // ② 【本需求最要紧的一条】任何"会被浏览器当网页执行"的扩展名一律拒绝。
        //    它们被就地打开时能读本域的 localStorage（里面有 token）、能改页面 ——
        //    而 /uploads/** 与前台同源，所以这是货真价实的存储型 XSS。
        for (String extension : SCRIPT_CAPABLE_EXTENSIONS) {
            String message = uploadRejected("attachment", "x." + extension,
                    "text/html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
            assertTrue(message.contains("格式的附件"),
                    extension + " 必须被附件白名单拒绝（否则是一个 XSS 入口），实际=" + message);
        }

        // ③ 白名单本身也要断言：光测行为的话，"名单里加了 svg 但有别的分支拦着"
        //    这种状态也能全绿 —— 而那种状态离出问题只差一次重构
        List<String> allowed = uploadProperties.getAttachmentAllowedExtensions();
        for (String extension : SCRIPT_CAPABLE_EXTENSIONS) {
            assertFalse(allowed.contains(extension),
                    "附件白名单里绝不能出现 " + extension + "（浏览器会执行它），实际名单=" + allowed);
        }
        assertEquals(EXPECTED_ATTACHMENT_EXTENSIONS.stream().sorted().toList(),
                allowed.stream().sorted().toList(),
                "附件白名单应当正好是这 16 种（多一种少一种都要在这里显式改一次），实际=" + allowed);

        assertEquals(0, countFiles(), "被拒的上传不该在磁盘上留下任何文件");
    }

    @Test
    @DisplayName("④ 数字钉死：读 application.properties 原文核对三档限额与 multipart 框架层限额")
    void uploadLimits_shouldMatchConfiguredNumbers() throws Exception {
        // 【为什么是"读配置文件原文"而不是断言运行时的值】
        //   本类用 @TestPropertySource 把附件上限与总容量调小了（为了让用例跑得快），
        //   所以运行时读到的不是需求里的 100MB / 5GB。
        //   而"配置里到底写了什么"本身就是要守的事实 ——
        //   与 SiteSettingTest 读 V12 迁移脚本原文、ProfileProdConfigTest
        //   读 application-prod.properties 原文是同一个做法。
        String properties = readClasspathText("application.properties");

        assertTrue(properties.contains("app.upload.max-size=10MB"),
                "图片上限必须是 10MB（本需求：5MB → 10MB），实际配置=" + lineOf(properties, "app.upload.max-size"));
        assertTrue(properties.contains("app.upload.attachment-max-size=100MB"),
                "附件单文件上限必须是 100MB，实际配置=" + lineOf(properties, "app.upload.attachment-max-size"));
        assertTrue(properties.contains("app.upload.max-total-size=5GB"),
                "上传目录总容量上限必须是 5GB，实际配置=" + lineOf(properties, "app.upload.max-total-size"));
        // 框架层的闸门必须【≥ 最大的一类】（附件 100MB），否则超过它的请求根本走不到业务代码，
        // 报出来的是一个与业务无关的框架异常
        assertTrue(properties.contains("spring.servlet.multipart.max-file-size=105MB"),
                "multipart 单文件限额要按附件上限（100MB）再留余量，实际配置="
                        + lineOf(properties, "spring.servlet.multipart.max-file-size"));
        assertTrue(properties.contains("spring.servlet.multipart.max-request-size=110MB"),
                "max-request-size 要再宽一点（表单里还有别的字段），实际配置="
                        + lineOf(properties, "spring.servlet.multipart.max-request-size"));

        // 白名单那一行也要在配置里（16 种都在，一个不少）
        String allowedLine = lineOf(properties, "app.upload.attachment-allowed-extensions");
        for (String extension : EXPECTED_ATTACHMENT_EXTENSIONS) {
            assertTrue(allowedLine != null && allowedLine.contains(extension),
                    "配置里的附件白名单应当包含 " + extension + "，实际=" + allowedLine);
        }
    }

    // ================================================================
    //  二、总容量护栏
    // ================================================================

    @Test
    @DisplayName("⑤ 总容量护栏：压满 6KB 的目录后，再传 1 个字节也拒绝（并且提示里说清了已用多少）")
    void totalCapacityGuard_shouldRejectWhenFull() throws Exception {
        // 上限 6KB（@TestPropertySource）。两个 3KB 正好压满：
        // 第二个是【边界】：3KB + 3KB == 6KB，等于上限应当放行（规则是"不能超过"）
        upload("attachment", "a.pdf", "application/pdf", new byte[3 * 1024]);
        upload("attachment", "b.pdf", "application/pdf", new byte[3 * 1024]);
        assertEquals(2, countFiles(), "前置条件：两个附件都已经落盘");

        // 第三个：只要 1 个字节就会超过上限 —— 必须被拒
        String message = uploadRejected("attachment", "c.pdf", "application/pdf", new byte[1]);

        assertTrue(message.contains("上传空间不足"),
                "要给出「空间不足」这种明确的提示，而不是一句笼统的失败，实际=" + message);
        assertTrue(message.contains("已用"),
                "提示里要带上已经用了多少：用户才知道该去清理旧文件还是找管理员扩容，实际=" + message);
        assertEquals(2, countFiles(), "被拒的上传不该落盘（护栏必须在写文件【之前】判）");
    }

    // ================================================================
    //  三、三套规则互不放宽（附件这一套不会污染图片/音频）
    // ================================================================

    @Test
    @DisplayName("⑥ 附件类型不认识的取值仍然报错，且附件扩展名没有放宽到图片/音频上")
    void attachmentRules_shouldNotLeakIntoOtherTypes() throws Exception {
        // 打错一个字：不能静默回落到图片（否则用户看到的是"格式不支持"，真正的原因是参数名）
        String unknownMessage = uploadRejected("attachmentt", "a.pdf", "application/pdf", new byte[64]);
        assertTrue(unknownMessage.contains("只支持 image / audio / attachment"),
                "未知 type 要报出支持哪几种，实际=" + unknownMessage);

        // 【方向一】附件能传 pdf，图片接口不行 —— 图片的白名单没有被顺手放宽
        String asImage = uploadRejected(null, "a.pdf", "application/pdf", new byte[64]);
        assertTrue(asImage.contains("只允许上传 jpg / jpeg / png / gif / webp 格式的图片"),
                "pdf 不该被图片接口接受，实际=" + asImage);

        // 【方向二】显式 type=image 也一样
        String asImageExplicit = uploadRejected("image", "a.pdf", "application/pdf", new byte[64]);
        assertTrue(asImageExplicit.contains("格式的图片"), "type=image 也要拒 pdf，实际=" + asImageExplicit);

        // 【方向三】音频接口同样不受影响：附件开了 100MB，音频仍然是 20MB、只收 mp3
        String asAudio = uploadRejected("audio", "a.pdf", "application/pdf", new byte[64]);
        assertTrue(asAudio.contains("只允许上传 mp3 格式的音频"),
                "音频这一套完全没有变化，实际=" + asAudio);
    }

    // ================================================================
    //  四、保存文章：附件随表单落库、整体替换
    // ================================================================

    @Test
    @DisplayName("⑦ 保存文章时附件随表单落库；后台详情能读回 attachments")
    void savingArticle_shouldPersistAttachments() throws Exception {
        Uploaded a = upload("attachment", "paper.pdf", "application/pdf", new byte[128]);
        Uploaded b = upload("attachment", "data.zip", "application/zip", new byte[256]);

        long articleId = createArticle(title("带附件"), "正文", null, attachmentArrayJson(a, b));

        List<Map<String, Object>> rows = attachmentRows(articleId);
        assertEquals(2, rows.size(), "两篇文章附件应当落成两行");
        assertEquals("paper.pdf", rows.get(0).get("name"), "附件的显示名要原样存下来");
        assertEquals(a.url(), rows.get(0).get("url"), "附件地址要原样存下来");
        assertEquals(128L, ((Number) rows.get(0).get("size")).longValue(), "大小要按字节存");
        assertEquals("data.zip", rows.get(1).get("name"), "顺序应当与提交顺序一致（前端列表就是这个顺序）");

        // 后台详情要带 attachments（编辑界面靠它回显）
        mockMvc.perform(get("/admin/article/" + articleId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(2))
                .andExpect(jsonPath("$.data.attachments[0].name").value("paper.pdf"))
                .andExpect(jsonPath("$.data.attachments[0].url").value(a.url()))
                .andExpect(jsonPath("$.data.attachments[0].size").value(128));
    }

    @Test
    @DisplayName("⑧ GET /article/{id} 的返回里带 attachments 数组（name / url / size 三个字段）")
    void publicDetail_shouldExposeAttachments() throws Exception {
        Uploaded a = upload("attachment", "slides.pptx", "application/vnd.ms-powerpoint", new byte[300]);

        long articleId = createArticle(title("前台可见附件"), "正文", null, attachmentArrayJson(a));

        // 匿名访问前台详情（不带任何 token —— 读者就是这个身份）
        mockMvc.perform(get("/article/" + articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].name").value("slides.pptx"))
                .andExpect(jsonPath("$.data.attachments[0].url").value(a.url()))
                .andExpect(jsonPath("$.data.attachments[0].size").value(300));
    }

    @Test
    @DisplayName("⑧b 没有附件的文章返回空数组（不是 null —— 前端可以直接遍历）")
    void articleWithoutAttachments_shouldReturnEmptyArray() throws Exception {
        long articleId = createArticle(title("无附件"), "正文", null, null);

        mockMvc.perform(get("/article/" + articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments").isArray())
                .andExpect(jsonPath("$.data.attachments.length()").value(0));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⑨ ★ 整体替换：旧附件行消失、新行出现，而且【被移除的文件真的从磁盘上删了】")
    void updateArticle_shouldReplaceAttachmentsAndDeleteRemovedFiles() throws Exception {
        Uploaded first = upload("attachment", "v1.pdf", "application/pdf", new byte[100]);
        Uploaded second = upload("attachment", "v2.pdf", "application/pdf", new byte[200]);

        long articleId = createArticle(title("整体替换"), "正文", null, attachmentArrayJson(first));
        assertTrue(Files.exists(fileOf(first.url())), "前置条件：第一个附件已经落盘");

        // 换成第二个：旧的那个不但要出库，磁盘上那份也要删掉
        updateArticle(articleId, title("整体替换"), "正文", null, attachmentArrayJson(second));

        List<Map<String, Object>> rows = attachmentRows(articleId);
        assertEquals(1, rows.size(), "整体替换之后应当只剩一行（旧行必须消失，不是追加）");
        assertEquals(second.url(), rows.get(0).get("url"), "留下的应当是这次提交的那个");

        assertFalse(Files.exists(fileOf(first.url())),
                "被移除的附件文件必须真的删掉（否则 +100MB 量级的文件会让磁盘只涨不落）");
        assertTrue(Files.exists(fileOf(second.url())), "这次提交的文件当然要留着");

        // 再提交一次空列表：全部清空（"提交什么就是什么"——空数组 = 没有附件）
        updateArticle(articleId, title("整体替换"), "正文", null, "[]");

        assertEquals(0, attachmentRows(articleId).size(), "空数组应当清空全部附件行");
        assertFalse(Files.exists(fileOf(second.url())), "清空之后，最后一个附件文件也要被删掉");
    }

    // ================================================================
    //  五、校验（数量 / 名字 / 大小 / 地址前缀）
    // ================================================================

    @Test
    @DisplayName("⑩ 附件校验：数量上限 20、name 100 字、size 上限、url 必须是本站上传地址")
    void attachmentValidation_shouldRejectBadInput() throws Exception {
        Uploaded ok = upload("attachment", "ok.pdf", "application/pdf", new byte[64]);

        // 数量：21 条（上限 20）
        String tooMany = "[" + java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> attachmentItemJson(ok)).collect(Collectors.joining(",")) + "]";
        String countMessage = createArticleRejected(title("附件太多"), "正文", tooMany);
        assertTrue(countMessage.contains("附件最多 20 个"), "实际=" + countMessage);

        // name 超长（101 字）—— 这条由 DTO 注解拦（与库列宽度 100 一致）
        String longName = attachmentItemJson("n".repeat(101) + ".pdf", ok.url(), 64);
        String nameMessage = createArticleRejected(title("名字太长"), "正文", "[" + longName + "]");
        assertTrue(nameMessage.contains("附件名最长 100 字"), "实际=" + nameMessage);

        // size 超过配置的附件上限（本类里配成 4KB，线上是 100MB —— 判的是配置值）
        String bigSize = attachmentItemJson("big.pdf", ok.url(), 5 * 1024);
        String sizeMessage = createArticleRejected(title("大小超限"), "正文", "[" + bigSize + "]");
        assertTrue(sizeMessage.contains("超过大小上限"), "实际=" + sizeMessage);

        // url 不在本项目的上传地址前缀之内 —— 外部地址一律拒绝。
        // 不拦的话，任何人都能把 https://别人的站/x.exe 填成"本站文章的附件"，
        // 等于拿我们的域名替对方背书（钓鱼 / 挂马最省事的用法）
        String externalUrl = attachmentItemJson("evil.pdf", "https://evil.example/x.pdf", 64);
        String urlMessage = createArticleRejected(title("外部地址"), "正文", "[" + externalUrl + "]");
        assertTrue(urlMessage.contains("不是本项目的上传地址"), "实际=" + urlMessage);

        // 相对形式的 /uploads/... 地址也是"本站的"，应当放行（它同样是本项目的上传地址）
        Uploaded relativeHost = upload("attachment", "rel.pdf", "application/pdf", new byte[32]);
        String relativeUrl = relativeHost.url().substring(relativeHost.url().indexOf("/uploads/"));
        long articleId = createArticle(title("相对地址"), "正文", null,
                "[" + attachmentItemJson("rel.pdf", relativeUrl, 32) + "]");
        assertEquals(1, attachmentRows(articleId).size(), "站内相对地址应当被接受");
    }

    @Test
    @DisplayName("⑪ 校验先于写入：附件不合法时，编辑请求被拒且原有附件分毫未动")
    void invalidAttachment_shouldNotTouchExistingRows() throws Exception {
        Uploaded good = upload("attachment", "keep.pdf", "application/pdf", new byte[64]);
        long articleId = createArticle(title("校验先于写入"), "正文", null, attachmentArrayJson(good));

        // 用一条外部地址去编辑：整份请求会被拒
        String bad = attachmentItemJson("evil.pdf", "https://evil.example/x.pdf", 64);
        String message = updateArticleRejected(articleId, title("校验先于写入"), "正文", "[" + bad + "]");
        assertTrue(message.contains("不是本项目的上传地址"), "实际=" + message);

        // 【关键断言】原来那一行还在，而且文件没被删 ——
        // 如果"整体替换"是先删后校验，这里就会看到"附件没了"（一次静默的数据丢失）
        List<Map<String, Object>> rows = attachmentRows(articleId);
        assertEquals(1, rows.size(), "校验失败不该动到已有附件");
        assertEquals(good.url(), rows.get(0).get("url"));
        assertTrue(Files.exists(fileOf(good.url())), "校验失败不该删掉任何文件");
    }

    // ================================================================
    //  六、删除文章时的级联清理（本类最要紧的一段）
    // ================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⑫ 删文章 → 附件行、附件文件、封面图、正文引用的图【全都清掉】；文章行仍是逻辑删除")
    void deleteArticle_shouldCascadeToFiles() throws Exception {
        Uploaded attachment = upload("attachment", "doc.pdf", "application/pdf", new byte[128]);
        Uploaded cover = upload(null, "cover.png", "image/png", TINY_PNG);
        Uploaded contentImage = upload(null, "inline.png", "image/png", TINY_PNG);

        String content = "正文 ![配图](" + contentImage.url() + ") 结束";
        long articleId = createArticle(title("级联删除"), content, cover.url(), attachmentArrayJson(attachment));

        assertEquals(1, attachmentRows(articleId).size(), "前置条件：附件行已写入");

        deleteArticle(articleId);

        assertEquals(0, attachmentRows(articleId).size(), "附件行必须随着文章一起删掉（物理删除）");
        assertFalse(Files.exists(fileOf(attachment.url())), "附件文件必须删掉");
        assertFalse(Files.exists(fileOf(cover.url())), "封面图没人再用了，也要删掉");
        assertFalse(Files.exists(fileOf(contentImage.url())), "正文引用的图没人再用了，同样要删掉");

        // 文章本身仍然是【逻辑删除】（历史数据还在，与项目里其它删除一致）：
        // 这里用原生 SQL 直查物理行 —— 带 @TableLogic 的 Mapper 查不出来
        Integer deleted = jdbc.queryForObject(
                "SELECT deleted FROM article WHERE id = ?", Integer.class, articleId);
        assertEquals(1, deleted, "删文章仍然是逻辑删除，不该变成物理删除");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⑬ ★★★ 共用的图不会被误删：两篇文章引用同一张图/同一个封面，删一篇之后另一篇完好")
    void sharedImages_shouldNotBeDeleted() throws Exception {
        // 三张图 + 一个附件，覆盖三种引用关系：
        //   shared     —— 两篇文章的正文都引用它（必须保留）
        //   sharedCover—— 两篇文章都拿它当封面（必须保留）
        //   exclusive  —— 只有被删的那一篇用（必须删掉）
        Uploaded shared = upload(null, "shared.png", "image/png", TINY_PNG);
        Uploaded sharedCover = upload(null, "shared-cover.png", "image/png", TINY_PNG);
        Uploaded exclusive = upload(null, "exclusive.png", "image/png", TINY_PNG);

        // A：正文引用 shared 与 exclusive，封面用 sharedCover
        long articleA = createArticle(title("共用图-A"),
                "A 的正文 ![共用](" + shared.url() + ") ![独占](" + exclusive.url() + ")",
                sharedCover.url(), null);

        // B：正文也引用 shared，封面也是 sharedCover，另外带一个自己的附件
        Uploaded bAttachment = upload("attachment", "b-only.pdf", "application/pdf", new byte[64]);
        long articleB = createArticle(title("共用图-B"),
                "B 的正文 ![共用](" + shared.url() + ")",
                sharedCover.url(), attachmentArrayJson(bAttachment));

        deleteArticle(articleA);

        // 【核心断言】共用的两张图必须还在 —— 无脑删会把 B 的文章页删成裂图
        assertTrue(Files.exists(fileOf(shared.url())),
                "★ 被两篇文章正文共用的图【必须保留】（否则 B 的正文就裂了，而且文件删了就回不来）");
        assertTrue(Files.exists(fileOf(sharedCover.url())),
                "★ 被两篇文章当封面的图【必须保留】");

        // 【反向断言】A 独占的那张图必须真的被删掉。
        // 少了这一条，本用例会变成"永远为真"：一个【根本不删任何文件】的实现
        // 也能让上面两条断言全绿 —— 那就测不出任何东西了
        assertFalse(Files.exists(fileOf(exclusive.url())),
                "只被 A 用的图应当被删掉（说明清理逻辑确实在干活，不是在偷懒）");

        // B 自己的附件不能被 A 的删除牵连
        assertTrue(Files.exists(fileOf(bAttachment.url())), "B 的附件与 A 无关，必须留着");
        assertEquals(1, attachmentRows(articleB).size(), "B 的附件行也不能被清掉");

        // 【端到端】B 的文章页仍然打得开，而且正文与封面都还指着那两张图
        String body = mockMvc.perform(get("/article/" + articleB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains(shared.url()), "B 的正文里仍然引用着那张共用的图，实际=" + body);
        assertTrue(body.contains(sharedCover.url()), "B 的封面仍然是那张共用的图");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⑭ 编辑文章时被移除的附件若仍被另一篇引用，文件不能删（共用附件同理）")
    void removedAttachmentFile_shouldSurviveIfStillUsedByAnotherArticle() throws Exception {
        Uploaded sharedAttachment = upload("attachment", "shared.pdf", "application/pdf", new byte[64]);

        // 两篇文章都挂同一个附件（前端复制了上一篇的附件列表，或用户手工填了同一个 URL ——
        // 这条路径是存在的，因为保存时我们只校验"地址是本项目的"，没有禁止共用）
        long articleA = createArticle(title("共用附件-A"), "A", null, attachmentArrayJson(sharedAttachment));
        long articleB = createArticle(title("共用附件-B"), "B", null, attachmentArrayJson(sharedAttachment));

        // A 把附件全部移除：库里 A 的行没了，但文件还在被 B 用着，不能删
        updateArticle(articleA, title("共用附件-A"), "A", null, "[]");

        assertEquals(0, attachmentRows(articleA).size(), "A 的附件行应当被清空");
        assertEquals(1, attachmentRows(articleB).size(), "B 的附件行不该受影响");
        assertTrue(Files.exists(fileOf(sharedAttachment.url())),
                "★ 这个文件仍被 B 引用，删掉 B 的附件就坏了 —— 判断依据必须包含附件表");

        // 再删掉 B：这时谁都不用了，文件才该被删
        deleteArticle(articleB);
        assertFalse(Files.exists(fileOf(sharedAttachment.url())),
                "最后一个引用者也走了，文件这时才可以删");
    }

    // ================================================================
    //  七、下载响应头（Content-Disposition / nosniff）
    // ================================================================

    @Test
    @DisplayName("⑮ 附件响应带 Content-Disposition: attachment + nosniff；图片仍然内联（不加下载头）")
    void attachmentResponse_shouldForceDownload() throws Exception {
        Uploaded attachment = upload("attachment", "report.pdf", "application/pdf", new byte[64]);

        mockMvc.perform(get(pathOf(attachment.url())))
                .andExpect(status().isOk())
                // 【为什么这条最重要】附件被"就地打开"时，浏览器会把 html/svg/xml 那一类
                // 当【本站的页面】执行 —— 那是存储型 XSS；pdf 那类则能被伪装成
                // "域名正确的钓鱼页"。强制下载之后这两种都不成立
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment; filename=")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // 附件目录里的 mp3 一样要强制下载：归类依据是【它在哪个目录】，
        // 而不是扩展名（否则一个 100MB 的录音会被浏览器就着播放器打开）
        Uploaded audioAttachment = upload("attachment", "talk.mp3", "audio/mpeg", TINY_MP3);
        mockMvc.perform(get(pathOf(audioAttachment.url())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));

        // 图片必须【保持内联】：前台几十张卡片都靠它渲染，
        // 一旦带上 attachment，博客上所有图片都会变成"下载按钮"
        Uploaded image = upload(null, "cover.png", "image/png", TINY_PNG);
        mockMvc.perform(get(pathOf(image.url())))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // 音频（音乐模块的曲目）同理：<audio> 要能直接播放
        Uploaded audio = upload("audio", "song.mp3", "audio/mpeg", TINY_MP3);
        mockMvc.perform(get(pathOf(audio.url())))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 上传结果（与 UploadResult 同形），供测试里拼 JSON 用 */
    private record Uploaded(String url, String name, long size) {
    }

    /** 上传一个文件并断言成功，返回 url/name/size（type 传 null 表示"不传 type"，即图片规则） */
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

    /** 上传一个文件并断言被拒（code 400），返回后端的提示文案 */
    private String uploadRejected(String type, String filename, String contentType, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/upload")
                .file(new MockMultipartFile("file", filename, contentType, content))
                .header("Authorization", "Bearer " + adminToken);
        if (type != null) {
            request = request.param("type", type);
        }

        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                // 本项目的统一约定：业务类错误返回 HTTP 200 + body.code（见 README「统一返回」）
                .andExpect(jsonPath("$.code").value(400))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonString(body, "message");
    }

    /** 建一篇已发布的文章（走真实的 HTTP 接口，顺带验证前端提交的 JSON 形状能被正确绑定） */
    private long createArticle(String title, String content, String cover, String attachmentsJson) throws Exception {
        String body = mockMvc.perform(post("/admin/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(articleJson(title, content, cover, attachmentsJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonNumber(body, "data");
    }

    /** 建文章并断言被拒，返回提示文案 */
    private String createArticleRejected(String title, String content, String attachmentsJson) throws Exception {
        String body = mockMvc.perform(post("/admin/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(articleJson(title, content, null, attachmentsJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonString(body, "message");
    }

    /** 编辑文章（整体替换语义，附件列表原样提交） */
    private void updateArticle(long id, String title, String content, String cover, String attachmentsJson)
            throws Exception {
        mockMvc.perform(put("/admin/article/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(articleJson(title, content, cover, attachmentsJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 编辑文章并断言被拒，返回提示文案 */
    private String updateArticleRejected(long id, String title, String content, String attachmentsJson)
            throws Exception {
        String body = mockMvc.perform(put("/admin/article/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(articleJson(title, content, null, attachmentsJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return jsonString(body, "message");
    }

    private void deleteArticle(long id) throws Exception {
        mockMvc.perform(delete("/admin/article/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 标题里必须带上本次用例的唯一标记，@AfterEach 才能按它把真提交的数据清干净 */
    private String title(String prefix) {
        return prefix + "-" + mark;
    }

    private String articleJson(String title, String content, String cover, String attachmentsJson) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"title\":\"").append(title).append("\"");
        json.append(",\"content\":\"").append(content).append("\"");
        if (cover != null) {
            json.append(",\"cover\":\"").append(cover).append("\"");
        }
        // 建成【已发布】：前台详情接口只对外可见已发布的文章
        json.append(",\"status\":1");
        if (attachmentsJson != null) {
            json.append(",\"attachments\":").append(attachmentsJson);
        }
        json.append("}");
        return json.toString();
    }

    private String attachmentItemJson(Uploaded uploaded) {
        return attachmentItemJson(uploaded.name(), uploaded.url(), uploaded.size());
    }

    private String attachmentItemJson(String name, String url, long size) {
        return "{\"name\":\"" + name + "\",\"url\":\"" + url + "\",\"size\":" + size + "}";
    }

    private String attachmentArrayJson(Uploaded... uploads) {
        return "[" + Arrays.stream(uploads).map(this::attachmentItemJson).collect(Collectors.joining(",")) + "]";
    }

    /** 直接查库看附件行（不走 Mapper：要看到的是库里真实的样子） */
    private List<Map<String, Object>> attachmentRows(long articleId) {
        return jdbc.queryForList(
                "SELECT name, url, size FROM article_attachment WHERE article_id = ? ORDER BY id", articleId);
    }

    /** 上传目录的绝对路径（与 LocalFileStorage 的算法一致） */
    private Path localRoot() {
        return Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize();
    }

    /** 由返回的 URL 反推磁盘上的文件路径（与 UploadAdminTest 是同一套算法） */
    private Path fileOf(String url) {
        return localRoot().resolve(url.substring(url.indexOf("/uploads/") + "/uploads/".length()));
    }

    /** 由完整地址取回"可以拿去 GET 的路径"（只保留 /uploads/... 那一段） */
    private String pathOf(String url) {
        return url.substring(url.indexOf("/uploads/"));
    }

    /** 数一数上传目录里现在有几个文件（护栏用例要用它证明"被拒的上传没有落盘"） */
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

    /** 把测试专用的上传目录整个删掉（目录不存在时什么都不做） */
    private void wipeUploadDir() {
        Path root = localRoot();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            // 倒序删除：先删文件再删目录（正序删目录会报"目录非空"）
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
        admin.setUsername("test_admin_attach_" + mark);
        admin.setPassword(passwordEncoder.encode("123456"));
        admin.setNickname("附件测试管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setDeleted(0);
        userMapper.insert(admin);
        return admin;
    }

    /** 读 classpath 上的一个文本文件（用于核对配置文件原文） */
    private String readClasspathText(String name) throws Exception {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 取得配置原文里以 key 开头的那一行（断言失败时把它打进消息里，方便一眼看出实际值） */
    private String lineOf(String text, String key) {
        return text.lines().filter(line -> line.startsWith(key)).findFirst().orElse("(没有这一行)");
    }

    /** 从 JSON 里取一个字符串字段的值（简单正则，避免为一个测试引入 JSON 解析样板） */
    private String jsonString(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 从 JSON 里取一个数字字段的值 */
    private long jsonNumber(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }
}
