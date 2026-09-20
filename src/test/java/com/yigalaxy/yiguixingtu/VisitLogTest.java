package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import com.yigalaxy.yiguixingtu.visit.entity.VisitLog;
import com.yigalaxy.yiguixingtu.visit.mapper.VisitLogMapper;
import com.yigalaxy.yiguixingtu.visit.task.VisitLogCleanupTask;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 访问日志与统计的用例
 *
 * 【这一组最要紧的四件事】
 *  ① **路径必须先洗干净再入库**：查询串里往往是用户的搜索词，原样存下来
 *     等于顺带记录了用户搜过什么（V17 的注释里写着这条）。而它写错是静默的
 *     —— 表里多几列字符串，没人会看。
 *  ② **IP 的保留期必须真的生效**：这张表存的是个人信息，"一直留着"和
 *     "留 90 天"是完全不同的两件事，而清理任务坏掉不会有任何报错。
 *  ③ **统计接口只有管理员能看**：它列的是全站的访问分布与访客 IP。
 *  ④ **访客 IP 列表的口径**：按 IP 聚合（同一人刷十次是一行，不是十行）、
 *     带归属地，且**老的、region 为空的历史记录也要显示得出归属地**
 *     —— 最后这条覆盖的是"这一列是后加的"这个事实，漏了它历史行会一直是空的。
 *
 * 【⚠️ 上传/评论那套"用真提交的用例要 NOT_SUPPORTED"在这里同样适用】
 *   清理那条用例会真的删数据，所以它是 NOT_SUPPORTED + 自己收拾现场。
 * =====================================================================
 */
@TestPropertySource(properties = {
        // 保留期调小，便于在用例里造"过期数据"（生产默认 90 天）
        "app.visit.retention-days=7"
})
class VisitLogTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 一个很典型的手机 UA（含 Mobile 关键词） */
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";

    private static final String PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VisitLogMapper visitLogMapper;

    @Autowired
    private VisitLogCleanupTask cleanupTask;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbc;

    private String mark;
    private User admin;
    private String adminToken;
    private User reader;
    private String readerToken;

    @BeforeEach
    void setUp() {
        mark = "V" + SEQ.incrementAndGet() + "x" + (System.nanoTime() % 1000);

        admin = insertUser("ADMIN");
        adminToken = jwtUtil.generateToken(admin.getId(), admin.getUsername(), "ADMIN");
        reader = insertUser("GUEST");
        readerToken = jwtUtil.generateToken(reader.getId(), reader.getUsername(), "GUEST");

        // 从干净的表开始：统计用例最怕"上一个用例留下的行"
        wipeVisits();
    }

    @AfterEach
    void tearDown() {
        wipeVisits();
        jdbc.update("DELETE FROM user WHERE username LIKE ?", "%" + mark + "%");
    }

    // ================================================================
    //  一、上报：路径要洗干净
    // ================================================================

    @Test
    @DisplayName("① 游客上报：路径落库、身份是 GUEST、账号 id 为空")
    void guestVisit_shouldBeRecorded() throws Exception {
        report("/archive", null, null, null);

        VisitLog saved = latest();
        assertEquals("/archive", saved.getPath());
        assertEquals(VisitLog.VISITOR_GUEST, saved.getVisitorType());
        assertNull(saved.getUserId(), "游客没有账号，user_id 必须是 null");
        assertNotNull(saved.getCreateTime(), "访问时间必须落库（曲线按它分组）");
    }

    @Test
    @DisplayName("② ★ 路径必须洗过：去掉查询串（它可能是用户的搜索词）")
    void report_shouldStripQueryString() throws Exception {
        report("/?keyword=" + mark + "&page=2", null, null, null);

        VisitLog saved = latest();
        assertEquals("/", saved.getPath(), "查询串必须被丢掉");
        assertFalse(saved.getPath().contains(mark), "搜索词绝不能进统计表");
    }

    @Test
    @DisplayName("③ 路径洗过的另外三种情况：锚点、绝对地址、超长")
    void report_shouldNormalizeOtherShapes() throws Exception {
        report("/article/13#comments", null, null, null);
        assertEquals("/article/13", latest().getPath(), "锚点要丢掉");

        report("https://www.example.com/archive?x=1", null, null, null);
        assertEquals("/archive", latest().getPath(), "绝对地址只取路径部分");

        report("/" + "a".repeat(400), null, null, null);
        assertTrue(latest().getPath().length() <= 255, "超长路径要截到列宽以内（否则 INSERT 直接失败）");
    }

    @Test
    @DisplayName("④ 不属于站内的路径：不落库，但接口照常返回成功")
    void report_shouldIgnoreNonSitePaths() throws Exception {
        // 不以 / 开头的一律不是站内路径（可能是绝对地址、也可能是乱传的）
        report("不是路径", null, null, null);

        assertEquals(0, countVisits(), "非站内路径不该留下记录");
    }

    @Test
    @DisplayName("⑤ 文章页要顺手记下文章 id（「哪几篇最热」那张表靠它）")
    void articleVisit_shouldRecordArticleId() throws Exception {
        report("/article/13", null, null, null);
        assertEquals(13L, latest().getArticleId());

        report("/article/13/", null, null, null);
        assertEquals(13L, latest().getArticleId(), "带尾斜杠也算文章页");

        report("/archive", null, null, null);
        assertNull(latest().getArticleId(), "非文章页不该有 article_id");
    }

    // ================================================================
    //  二、上报：身份、来源、设备
    // ================================================================

    @Test
    @DisplayName("⑥ 登录用户上报：身份是 USER、记下账号 id；管理员是 ADMIN")
    void loggedInVisit_shouldRecordIdentity() throws Exception {
        report("/", readerToken, null, null);
        assertEquals(VisitLog.VISITOR_USER, latest().getVisitorType());
        assertEquals(reader.getId(), latest().getUserId());

        report("/", adminToken, null, null);
        assertEquals(VisitLog.VISITOR_ADMIN, latest().getVisitorType());
        assertEquals(admin.getId(), latest().getUserId());
    }

    @Test
    @DisplayName("⑦ 来源分类：无 Referer=直接访问；同站=站内跳转；别的站=外链并记下主机名")
    void report_shouldClassifyReferer() throws Exception {
        // 直接访问：没带 Referer
        report("/", null, null, null);
        assertEquals(VisitLog.REFERER_DIRECT, latest().getRefererType());
        assertNull(latest().getRefererHost());

        // 站内跳转：Referer 的主机与本次请求的 Host 相同
        report("/archive", null, "https://blog.test/article/13", null);
        assertEquals(VisitLog.REFERER_INTERNAL, latest().getRefererType());
        assertNull(latest().getRefererHost(), "站内来源不需要记主机名");

        // 外链：主机不同 —— 只记主机名，不记完整地址（地址上可能挂着别人的查询参数）
        report("/", null, "https://www.google.com/search?q=xxx", null);
        assertEquals(VisitLog.REFERER_EXTERNAL, latest().getRefererType());
        assertEquals("www.google.com", latest().getRefererHost());
        assertFalse(latest().getRefererHost().contains("q="), "只存主机名，不带查询串");
    }

    @Test
    @DisplayName("⑧ 设备只分两档：手机 UA 记 MOBILE，其余记 PC（没有 UA 也算 PC）")
    void report_shouldDetectDevice() throws Exception {
        report("/", null, null, MOBILE_UA);
        assertEquals(VisitLog.DEVICE_MOBILE, latest().getDevice());

        report("/", null, null, PC_UA);
        assertEquals(VisitLog.DEVICE_PC, latest().getDevice());

        report("/", null, null, null);
        assertEquals(VisitLog.DEVICE_PC, latest().getDevice(), "没有 UA（脚本/探活）归到电脑档");
    }

    @Test
    @DisplayName("⑨ IP 从 X-Forwarded-For 取第一段（线上是 Nginx 转发过来的）")
    void report_shouldTakeClientIpFromForwardedHeader() throws Exception {
        report("/", null, null, null, "203.0.113.7, 10.0.0.1");
        assertEquals("203.0.113.7", latest().getIp(), "取第一段才是真实客户端");
    }

    // ================================================================
    //  三、上报：永不打扰用户
    // ================================================================

    @Test
    @DisplayName("⑩ 空路径仍然按参数校验拦下（400）——「统计不重要」不等于「放行畸形输入」")
    void report_shouldStillValidateInput() throws Exception {
        // 【这条与 ⑪ 是一对，别混】接口对**运行期失败**（数据库、限流）是吞掉的
        //   （见 VisitController 的说明），但对**畸形参数**仍然按全站统一约定返回 400。
        //   两者不是一回事：前者是"统计没记上"，后者是"调用方发错了请求"。
        //   前端只会在路径非空时才发这个请求（shouldReportVisit），
        //   所以正常路径上不会出现它。
        mockMvc.perform(post("/visit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        assertEquals(0, countVisits(), "被拦下的请求当然不该落库");
    }

    @Test
    @DisplayName("⑪ 上报不需要登录（绝大多数访客都没登录）")
    void report_shouldBeAnonymous() throws Exception {
        mockMvc.perform(post("/visit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ================================================================
    //  四、统计接口
    // ================================================================

    @Test
    @DisplayName("⑫ 统计接口只有管理员能用（它列的是全站访问分布与访客 IP）")
    void stats_shouldRequireAdmin() throws Exception {
        mockMvc.perform(get("/admin/stats/visits")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/stats/visits").header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("⑬ 统计口径：PV 数全部访问、UV 按 IP 去重、构成与排行都对")
    void stats_shouldAggregateCorrectly() throws Exception {
        // 同一个 IP 来两次（PV=2，UV=1），另一个 IP 来一次（PV=3，UV=2）
        report("/article/13", null, null, PC_UA, "198.51.100.1");
        report("/article/13", null, null, MOBILE_UA, "198.51.100.1");
        report("/archive", readerToken, null, PC_UA, "198.51.100.2");

        Map<String, Object> data = statsData(7);

        assertEquals(3L, asLong(data.get("totalPv")), "三次访问");
        assertEquals(2L, asLong(data.get("totalUv")), "两个不同 IP —— PV 会骗人，UV 不会");
        assertEquals(2L, asLong(data.get("newVisitors")), "两个 IP 都是第一次来");

        // 构成：一次注册用户 + 两次游客
        assertEquals(2L, sliceValue(data, "visitorTypes", "GUEST"));
        assertEquals(1L, sliceValue(data, "visitorTypes", "USER"));
        // 设备：两次电脑 + 一次手机（只按 UA 判，与 IP 无关）
        assertEquals(2L, sliceValue(data, "devices", "PC"));
        assertEquals(1L, sliceValue(data, "devices", "MOBILE"));

        // 按天曲线：三次都在今天，所以只有一个点
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) data.get("daily");
        assertEquals(1, daily.size(), "只统计有记录的天（前端补零）");
        assertEquals(3L, asLong(daily.get(0).get("pv")));

        // 最热文章：两篇文章页访问都记在 #13 上
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topArticles = (List<Map<String, Object>>) data.get("topArticles");
        assertEquals(1, topArticles.size());
        assertEquals(13L, asLong(topArticles.get(0).get("id")));
        assertEquals(2L, asLong(topArticles.get(0).get("pv")));
    }

    @Test
    @DisplayName("⑭ 统计区间：只算区间内的记录，days 超范围时被夹住而不是报错")
    void stats_shouldRespectDayRange() throws Exception {
        report("/", null, null, null);
        // 塞一条 10 天前的记录（默认区间 7 天看不到它）
        jdbc.update("INSERT INTO visit_log (path, visitor_type, referer_type, device, create_time)"
                + " VALUES (?,?,?,?,?)", "/old", VisitLog.VISITOR_GUEST,
                VisitLog.REFERER_DIRECT, VisitLog.DEVICE_PC, LocalDateTime.now().minusDays(10));

        Map<String, Object> recent = statsData(7);
        assertEquals(1L, asLong(recent.get("totalPv")), "7 天前的记录不该算进来");

        // days 超出范围（保留期是 7 天，上界是 90）→ 夹住而不是 400
        mockMvc.perform(get("/admin/stats/visits").param("days", "9999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.days").value(90));

        mockMvc.perform(get("/admin/stats/visits").param("days", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.days").value(1));
    }

    // ================================================================
    //  五、归属地与访客 IP 列表（统计页下钻出来的那一层）
    // ================================================================

    @Test
    @DisplayName("⑯ ★ 上报时归属地就落库了（公网 IP 解析得出，内网 IP 是「内网」）")
    void report_shouldStoreRegion() throws Exception {
        report("/", null, null, null, "114.114.114.114");
        VisitLog publicVisit = latest();
        assertNotNull(publicVisit.getRegion(),
                "★ 公网 IP 的归属地必须落库 —— 它是空的说明 IpRegionResolver 没被调、或者 xdb 没打进包");
        assertFalse(publicVisit.getRegion().contains("Reserved"), "公网 IP 不该被当成保留地址");
        assertFalse(publicVisit.getRegion().contains("|"),
                "落库的应当是拼好的显示文本，不是 ip2region 的原始 5 段结果：" + publicVisit.getRegion());

        // 内网地址显示成「内网」而不是 null —— 它是一个信号：
        // 要么本机调试，要么反向代理没把真实 IP 传进来（那会让所有访客变成同一个 IP）
        report("/", null, null, null, "192.168.1.5");
        assertEquals("内网", latest().getRegion());
    }

    @Test
    @DisplayName("⑰ ★ 访客 IP 列表：按 IP 聚合（刷十次是一行）、按次数倒序、带归属地")
    void visitorIps_shouldAggregateByIp() throws Exception {
        // 同一个 IP 来三次，另一个 IP 来一次。
        // ⚠️ 前三次的 region 全是 NULL —— 模拟"这一列存在之前写进来的历史记录"。
        //    这是本用例的重点：接口必须**现算**出归属地，不能把 NULL 原样返回去
        //    （那样所有历史行在列表里永远是空的，而历史行正是最想看的那批）。
        //    198.51.100.x 是 TEST-NET-2 文档专用网段，解析结果必然是「内网」，可预期。
        insertVisit("198.51.100.1", "/old", LocalDateTime.now().minusDays(2), null);
        insertVisit("198.51.100.1", "/b", LocalDateTime.now().minusHours(3), null);
        insertVisit("198.51.100.1", "/c", LocalDateTime.now().minusMinutes(5), null);
        insertVisit("198.51.100.2", "/d", LocalDateTime.now().minusMinutes(1), "内网");

        List<Map<String, Object>> rows = ipsData(7);
        assertEquals(2, rows.size(), "★ 两个不同的 IP 必须是两行 —— 刷十次是一行，不是十行");

        Map<String, Object> top = rows.get(0);
        assertEquals("198.51.100.1", top.get("ip"), "访问次数多的排前面");
        assertEquals(3L, asLong(top.get("visits")));
        assertNotNull(top.get("lastVisit"), "要带上最后一次访问时间（判断一个访客还在不在的依据）");
        assertEquals("内网", top.get("region"),
                "★ region 全为空的历史记录要现算归属地 —— 直接返回 null 的话历史行永远是空的");

        assertEquals(1L, asLong(rows.get(1).get("visits")));
        assertEquals("内网", rows.get(1).get("region"), "落库了 region 的记录直接用它，不用再解析");
    }

    @Test
    @DisplayName("⑱ 访客 IP 列表只有管理员能用（它与统计报表同属后台数据）")
    void visitorIps_shouldRequireAdmin() throws Exception {
        mockMvc.perform(get("/admin/stats/visits/ips")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/stats/visits/ips").header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("⑲ 访客 IP 列表的区间与统计报表同口径（days 超范围时被夹住，不是报错）")
    void visitorIps_shouldRespectDayRange() throws Exception {
        insertVisit("198.51.100.9", "/new", LocalDateTime.now().minusHours(1), "内网");
        insertVisit("198.51.100.8", "/old", LocalDateTime.now().minusDays(10), "内网");

        // 7 天区间：10 天前那个 IP 不该出现
        assertEquals(1, ipsData(7).size(), "区间外的记录不能被算进访客列表");

        // days 超范围：夹住而不是 400（与 GET /admin/stats/visits 同一个约定）
        mockMvc.perform(get("/admin/stats/visits/ips").param("days", "9999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ================================================================
    //  六、保留期清理（存的是 IP，这一条是功能得以存在的前提）
    // ================================================================

    @Test
    @DisplayName("⑮ ★ 清理只删保留期之外的：过期的没了、期内的分毫不动")
    void cleanup_shouldDeleteOnlyExpiredRecords() throws Exception {
        // 保留期是 7 天（见类上的 @TestPropertySource）
        jdbc.update("INSERT INTO visit_log (path, visitor_type, referer_type, device, create_time)"
                        + " VALUES (?,?,?,?,?)", "/expired", VisitLog.VISITOR_GUEST,
                VisitLog.REFERER_DIRECT, VisitLog.DEVICE_PC, LocalDateTime.now().minusDays(8));
        jdbc.update("INSERT INTO visit_log (path, visitor_type, referer_type, device, create_time)"
                        + " VALUES (?,?,?,?,?)", "/kept", VisitLog.VISITOR_GUEST,
                VisitLog.REFERER_DIRECT, VisitLog.DEVICE_PC, LocalDateTime.now().minusDays(6));

        cleanupTask.cleanup();

        List<String> paths = jdbc.queryForList("SELECT path FROM visit_log", String.class);
        assertFalse(paths.contains("/expired"), "★ 超过保留期的记录必须真的被删掉（存的是 IP，不能无限期留着）");
        assertTrue(paths.contains("/kept"), "保留期内的记录不能被误删");
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 调一次上报接口（可选带 token / Referer / UA / XFF） */
    private void report(String path, String token, String referer, String userAgent, String forwardedFor)
            throws Exception {
        var request = post("/visit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + path.replace("\"", "\\\"") + "\"}");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (referer != null) {
            request = request.header("Referer", referer);
            // 站内判定要拿 Referer 的主机与本次请求的 Host 比 ——
            // 用例里两边都用 blog.test，这样才走得到 INTERNAL 那一支
            request = request.header("Host", "blog.test");
        }
        if (userAgent != null) {
            request = request.header("User-Agent", userAgent);
        }
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void report(String path, String token, String referer, String userAgent) throws Exception {
        report(path, token, referer, userAgent, null);
    }

    /** 最近插入的那条记录（按 id 倒序） */
    private VisitLog latest() {
        return visitLogMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VisitLog>()
                .orderByDesc(VisitLog::getId)
                .last("LIMIT 1"));
    }

    private int countVisits() {
        return visitLogMapper.selectCount(null).intValue();
    }

    /** 只清掉本用例造的数据（真实表，不清别人的） */
    private void wipeVisits() {
        jdbc.update("DELETE FROM visit_log");
    }

    /**
     * 直接往表里塞一条访问记录。
     *
     * 【为什么不用 report() 接口而是写 SQL】访客列表这条链路要验的是
     *   "同一 IP 的多次访问会被聚成一行、按次数倒序、带上最后一次时间"，
     *   这需要**控制每条的访问时间**（接口上报只会写"此刻"）；
     *   而且还要造出 region 为 NULL 的历史行 —— 那条路径接口根本走不到
     *   （现在上报时一定会去解析归属地）。
     *
     * @param region 归属地；传 null 就是"这一列存在之前的老记录"
     */
    private void insertVisit(String ip, String path, LocalDateTime time, String region) {
        jdbc.update("INSERT INTO visit_log (path, visitor_type, referer_type, device, ip, region, create_time)"
                        + " VALUES (?,?,?,?,?,?,?)",
                path, VisitLog.VISITOR_GUEST, VisitLog.REFERER_DIRECT, VisitLog.DEVICE_PC, ip, region, time);
    }

    /** 取访客 IP 列表（管理员身份），返回 {@code $.data} 那一层 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ipsData(int days) throws Exception {
        String body = mockMvc.perform(get("/admin/stats/visits/ips").param("days", String.valueOf(days))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statsData(int days) throws Exception {
        String body = mockMvc.perform(get("/admin/stats/visits").param("days", String.valueOf(days))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.data");
    }

    @SuppressWarnings("unchecked")
    private long sliceValue(Map<String, Object> data, String field, String key) {
        List<Map<String, Object>> slices = (List<Map<String, Object>>) data.get(field);
        return slices.stream()
                .filter(s -> key.equals(s.get("key")))
                .mapToLong(s -> asLong(s.get("value")))
                .findFirst()
                .orElse(0L);
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private User insertUser(String role) {
        User user = new User();
        user.setUsername("test_" + role.toLowerCase() + "_visit_" + mark);
        user.setPassword(passwordEncoder.encode("x"));
        user.setNickname("访问统计测试用户");
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }
}
