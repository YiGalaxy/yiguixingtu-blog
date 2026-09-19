package com.yigalaxy.yiguixingtu.visit.service;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.web.ClientIp;
import com.yigalaxy.yiguixingtu.visit.dto.VisitStatsVO;
import com.yigalaxy.yiguixingtu.visit.entity.VisitLog;
import com.yigalaxy.yiguixingtu.visit.mapper.VisitLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * =====================================================================
 * 访问日志：记录与统计
 *
 * 【这个类里最要紧的两条】
 *  ① 路径必须先"洗干净"再入库：去掉查询串、去掉域名、限长。
 *     查询串里往往带着用户输入（搜索词、utm 来源），原样存下来等于顺带记录了
 *     用户搜过什么 —— 那不是这个功能要回答的问题。
 *  ② 统计口径**全部按 IP 去重**（UV）。PV 一个数字很容易自欺欺人
 *     （自己刷新十次就是十个 PV），而"多少个不同的人"才是站长真正想知道的。
 *     它的近似程度写在 VisitLogMapper.selectDaily 的注释里。
 * =====================================================================
 */
@Slf4j
@Service
public class VisitLogServiceImpl implements VisitLogService {

    /** path 列是 varchar(255)，超长要截断（不截断会让 INSERT 直接失败） */
    private static final int PATH_MAX_LENGTH = 255;

    /** referer_host 列是 varchar(255) */
    private static final int HOST_MAX_LENGTH = 255;

    /** 从路径里抠文章 id：{@code /article/123} */
    private static final Pattern ARTICLE_PATH = Pattern.compile("^/article/(\\d+)/?$");

    /** 报表允许的天数范围（下界给 1 天：只看今天也是有意义的） */
    private static final int MIN_DAYS = 1;

    /** 上界 = 保留期能覆盖的最长跨度（保留期默认 90 天，见 application.properties） */
    private static final int MAX_DAYS = 90;

    /** 判断手机 UA 的关键词。宁可只分两档：分得太细（机型/系统/浏览器）没人看，还要引 UA 解析库 */
    private static final String[] MOBILE_UA_KEYWORDS = {"Mobile", "Android", "iPhone", "iPad", "iPod"};

    private final VisitLogMapper visitLogMapper;

    public VisitLogServiceImpl(VisitLogMapper visitLogMapper) {
        this.visitLogMapper = visitLogMapper;
    }

    // =================================================================
    //  一、记录
    // =================================================================

    @Override
    public void record(String rawPath, HttpServletRequest request, LoginUser currentUser) {
        String path = normalizePath(rawPath);
        if (path == null) {
            // 空路径直接不记：它不是一次"页面访问"（可能是前端上报时还没拿到 route）
            return;
        }

        VisitLog visit = new VisitLog();
        visit.setPath(path);
        visit.setArticleId(articleIdOf(path));

        // 身份：三种取值由后端判定，前端说了不算（前端只报路径）
        if (currentUser == null) {
            visit.setVisitorType(VisitLog.VISITOR_GUEST);
        } else {
            boolean admin = "ADMIN".equals(currentUser.getUser().getRole());
            visit.setVisitorType(admin ? VisitLog.VISITOR_ADMIN : VisitLog.VISITOR_USER);
            visit.setUserId(currentUser.getUser().getId());
        }

        visit.setIp(ClientIp.of(request));
        classifyReferer(visit, request);
        visit.setDevice(deviceOf(request));
        // 【为什么显式赋值】与 Comment.createTime 同一条理由：插入后要拿它算聚合，
        // 而 MyBatis-Plus 不会把库生成的值回填到对象上
        visit.setCreateTime(LocalDateTime.now());

        visitLogMapper.insert(visit);
    }

    /**
     * 把前端报上来的路径洗干净：只留站内的路径部分。
     *
     * 【去掉什么】
     *   · 查询串与锚点：{@code /article/13?from=wechat#top} → {@code /article/13}
     *     （查询串里可能是搜索词，不该进统计表）
     *   · 域名：万一前端报了个绝对地址（{@code https://host/a}），取路径部分 ——
     *     否则 path 列里会混进主机名，"最热页面"那张表会被同一个页面拆成好几行
     *   · 超长：截到列长以内
     *
     * @return 干净的路径；不是站内路径（不以 / 开头、或者清洗后为空）时返回 null
     */
    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String path = rawPath.trim();

        // 绝对地址的情况：借 URI 解析一次拿 path（失败就退回字符串处理，不因为一个畸形值丢掉整条记录）
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                String parsed = URI.create(path).getPath();
                path = parsed == null ? "/" : parsed;
            } catch (IllegalArgumentException e) {
                int schemeEnd = path.indexOf("//");
                int slash = path.indexOf('/', schemeEnd + 2);
                path = slash < 0 ? "/" : path.substring(slash);
            }
        }

        // 去掉查询串与锚点（注意顺序无所谓：谁在前面就切谁）
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }

        if (!path.startsWith("/")) {
            // 不是站内路径 —— 统计表里只该有站内页面
            return null;
        }
        if (path.length() > PATH_MAX_LENGTH) {
            path = path.substring(0, PATH_MAX_LENGTH);
        }
        return path;
    }

    private int indexOfAny(String text, char first, char second) {
        int a = text.indexOf(first);
        int b = text.indexOf(second);
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    /** 路径是文章页时抠出文章 id，其它页面返回 null */
    private Long articleIdOf(String path) {
        Matcher matcher = ARTICLE_PATH.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            // 形如 /article/99999999999999999999（超出 long）—— 不是有效 id，当作普通页面
            return null;
        }
    }

    /**
     * 判来源：站内跳转 / 外链 / 直接访问。
     *
     * 【怎么算"站内"】拿 Referer 的主机名和本次请求的 Host 比。
     *   前者是"用户从哪个页面点过来的"，后者是"他访问的是哪个站点"——
     *   两者相同就是站内跳转（这在这类统计里通常要单独算，因为它不代表新读者）。
     *
     * 【为什么只存主机名】完整地址上可能挂着别人的查询参数（utm、分享码），
     *   存下来既没用又是多余的留存；而"从哪个站来的"这个问题的答案就是主机名。
     */
    private void classifyReferer(VisitLog visit, HttpServletRequest request) {
        String referer = request == null ? null : request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            // 书签、直接输地址、从客户端（微信内置浏览器等）打开 —— 都归这里
            visit.setRefererType(VisitLog.REFERER_DIRECT);
            return;
        }

        String refererHost = hostOf(referer);
        String requestHost = request.getHeader("Host");
        if (refererHost != null && requestHost != null && refererHost.equalsIgnoreCase(stripPort(requestHost))) {
            visit.setRefererType(VisitLog.REFERER_INTERNAL);
            return;
        }

        visit.setRefererType(VisitLog.REFERER_EXTERNAL);
        visit.setRefererHost(truncateHost(refererHost));
    }

    /** 从 Referer 里取主机名；解析不了时返回 null（那就只记 EXTERNAL，不记主机） */
    private String hostOf(String referer) {
        try {
            URI uri = URI.create(referer);
            String host = uri.getHost();
            return host == null ? null : stripPort(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 去掉主机名后面的端口（{@code localhost:3000} → {@code localhost}） */
    private String stripPort(String host) {
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private String truncateHost(String host) {
        if (host == null) {
            return null;
        }
        return host.length() > HOST_MAX_LENGTH ? host.substring(0, HOST_MAX_LENGTH) : host;
    }

    /**
     * 判设备：只分电脑与手机两档。
     *
     * 【为什么用关键词而不是引 UA 解析库】这个站要回答的问题只有一个：
     *   "手机上看着方不方便"。为此引一个几百 KB、还带一份设备库的依赖不划算。
     *   关键词匹配的误判是"某个冷门机型被算成电脑"，对趋势判断毫无影响。
     *   ⚠️ 真要做"按设备优化体验"时，前端用窗口宽度判断比 UA 准得多。
     */
    private String deviceOf(HttpServletRequest request) {
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            // 没有 UA 的请求（脚本、监控探活）：归到电脑档 —— 它们本来也不是手机用户
            return VisitLog.DEVICE_PC;
        }
        for (String keyword : MOBILE_UA_KEYWORDS) {
            if (ua.contains(keyword)) {
                return VisitLog.DEVICE_MOBILE;
            }
        }
        return VisitLog.DEVICE_PC;
    }

    // =================================================================
    //  二、统计
    // =================================================================

    @Override
    public VisitStatsVO stats(int days) {
        int span = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        // 【为什么要按天取整】"最近 7 天"从今天零点算起才符合直觉：
        //   按"此刻往前 168 小时"算的话，曲线最左边那天是残缺的半天，
        //   看起来像一个突然的下跌
        LocalDateTime since = LocalDate.now().minusDays(span - 1L).atStartOfDay();

        List<VisitStatsVO.DailyPoint> daily = visitLogMapper.selectDaily(since).stream()
                .map(row -> new VisitStatsVO.DailyPoint(
                        String.valueOf(row.get("d")),
                        asLong(row.get("pv")),
                        asLong(row.get("uv"))))
                .toList();

        return new VisitStatsVO(
                span,
                visitLogMapper.countSince(since),
                visitLogMapper.countDistinctIpSince(since),
                visitLogMapper.countNewVisitorsSince(since),
                daily,
                slicesOf(visitLogMapper.selectGrouped("visitor_type", since)),
                slicesOf(visitLogMapper.selectGrouped("referer_type", since)),
                slicesOf(visitLogMapper.selectGrouped("device", since)),
                slicesOf(visitLogMapper.selectTopPaths(since)),
                visitLogMapper.selectTopArticles(since).stream()
                        .map(row -> new VisitStatsVO.ArticlePoint(
                                asLong(row.get("id")),
                                row.get("title") == null ? null : String.valueOf(row.get("title")),
                                asLong(row.get("v"))))
                        .toList());
    }

    /** 把 {@code (k, v)} 的查询结果转成 Slice（顺带丢掉分组键为空的行） */
    private List<VisitStatsVO.Slice> slicesOf(List<Map<String, Object>> rows) {
        List<VisitStatsVO.Slice> slices = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("k");
            if (key == null) {
                continue;
            }
            slices.add(new VisitStatsVO.Slice(String.valueOf(key), asLong(row.get("v"))));
        }
        return slices;
    }

    /**
     * 把 JDBC 回来的计数转成 long。
     *
     * 【为什么要这么一层】不同驱动/不同函数返回的数字类型不一样
     *   （COUNT 回来是 Long，DATE 聚合里可能是 BigInteger），直接强转会在
     *   某一条 SQL 上抛 ClassCastException —— 而那是运行时才炸、还挑数据。
     *   统一走 Number.longValue() 就没有这个隐患。
     */
    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
