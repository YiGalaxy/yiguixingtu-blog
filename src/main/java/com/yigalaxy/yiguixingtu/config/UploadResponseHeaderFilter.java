package com.yigalaxy.yiguixingtu.config;

import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * =====================================================================
 * 给 {@code /uploads/**} 的响应补上"该怎么对待这个文件"的头
 *
 * 【这个过滤器解决什么问题】
 *   /uploads/** 是一个静态资源映射（见 {@link WebMvcConfig}），
 *   它把磁盘上的文件【原样】发给浏览器。而浏览器拿到一个文件之后会做两件事：
 *   ① 猜/按扩展名决定用什么 Content-Type
 *   ② 按这个类型决定【就地渲染】还是【下载】
 *   对于图片这没问题；但对于附件，第 ② 步是危险的 ——
 *   见下面"为什么附件必须下载"。
 *
 * =====================================================================
 * 【为什么图片可以内联、附件必须下载（这是本类存在的全部理由）】
 *
 *   "内联"（inline）= 浏览器在当前标签页里打开这个文件；
 *   "下载"（attachment）= 浏览器把它存到本地，永远不执行、不渲染。
 *
 *   ① 图片/音频【只能被渲染，不能被"执行"】：
 *      jpg / png / gif / webp / mp3 这些格式里没有一个地方能放脚本 ——
 *      浏览器最"坏"的行为也就是把一张图显示出来（或者显示失败成裂图）。
 *      而封面图必须内联：前台几十张卡片都要靠它渲染，如果加了
 *      Content-Disposition: attachment，用户看到的会是一堆下载提醒，
 *      整个博客的图片会全部裂掉。
 *
 *   ② 附件里却可能混进【会被当成网页执行】的东西 —— 这是本类真正要防的：
 *      · 一个 .html / .htm / .svg / .xml 文件被内联打开时，浏览器会把它
 *        当【本站的页面】解析并执行里面的 <script>。而 /uploads/** 与
 *        前台是【同源】的（都是本站域名），于是那段脚本读得到 localStorage
 *        里的 token、能带着 cookie 发请求、能改写这个页面上的一切 ——
 *        这就是存储型 XSS，前端做的所有转义防护在这一步全部作废。
 *      · 一个 .pdf 或 .html 里塞一段"您的登录已过期，请重新登录"的假页面，
 *        地址栏显示的却是【我们自己的域名】—— 这是钓鱼最有效的一种形态
 *        （用户看的正是"是不是我们域名"这件事）。
 *      强制 attachment 之后，这两种攻击都不成立：浏览器只会把字节存下来，
 *      永远不会把它当页面跑。用户即使双击打开也是从自己电脑本地打开，
 *      与我们的域名无关。
 *
 *   ③ 为什么白名单里已经挡掉了 html/svg（见 UploadProperties），这里还要再挡一次：
 *      【纵深防御】。白名单防的是"通过我们的上传接口进来"，
 *      而这个过滤器防的是"任何出现在这个目录里的文件"——
 *      手工放进目录的文件、以前旧版本传进来的文件、
 *      将来接口改动出现的漏网文件（比如有人给附件加了 html）。
 *      两道防线各自独立，任何一道单独失效都还有另一道。
 *
 * =====================================================================
 * 【用到的技术栈与构造】
 *   · {@link OncePerRequestFilter} —— Spring 提供的基类，保证一个请求只执行一次
 *     （请求被 forward/include 时不会重复设置响应头）
 *   · 它【没有加 @Component】：因为 URL 映射要精确限定到 /uploads/*，
 *     而 @Component 的过滤器会被 Boot 自动注册到 /*（所有请求都过一遍，
 *     包括每一个接口请求）。注册方式见 WebMvcConfig 里的 FilterRegistrationBean。
 *   · 响应头在 doFilter 之前设置：这时候响应还没提交（committed），
 *     设置才有效；写在链后面（拿到内容之后）有时已经晚了 ——
 *     静态资源是被流式写出去的。
 *
 * 【判断"是不是附件"用的是【目录】而不是扩展名（一个刻意的选择）】
 *   附件的白名单里有 mp3 / mp4，它们同时也是"可以内联"的媒体格式。
 *   如果按扩展名判断，那么一个 100MB 的 mp3 附件就会被内联播放 ——
 *   对"要下载的资料"来说这不是我们想要的语义。
 *   而【它放在哪个目录】是上传时由我们自己决定的（attachment/ 前缀），
 *   是比扩展名更可靠的归类依据：进 attachment/ 的一律是附件，一律下载。
 *   剩下那些"不在附件目录、扩展名又不在内联白名单里"的文件（人工放进来的），
 *   也按最保守的方式处理 —— 强制下载。
 * =====================================================================
 */
@Slf4j
public class UploadResponseHeaderFilter extends OncePerRequestFilter {

    /** 静态映射的 URL 前缀（见 WebMvcConfig 的 addResourceHandlers） */
    private static final String UPLOADS_PREFIX = "/uploads/";

    /**
     * 【可以内联展示】的扩展名白名单：图片与音频。
     *
     * ⚠️ 这里是白名单不是黑名单：所有不在这份名单里的扩展名都会被强制下载。
     *   名单外的默认行为是"下载"，而不是"内联"—— 这是安全的默认值
     *   （与 UploadProperties 里"扩展名用白名单"是同一条原则）。
     */
    private static final List<String> INLINE_EXTENSIONS = List.of(
            "jpg", "jpeg", "png", "gif", "webp",   // 图片：前台卡片要直接渲染
            "mp3");                                 // 音频：<audio> 要直接播放

    private final UploadProperties properties;

    public UploadResponseHeaderFilter(UploadProperties properties) {
        this.properties = properties;
    }

    /**
     * nosniff。上游是 SecurityConfig 里的 {@code contentTypeOptions}
     * （它本来就给所有响应加了 nosniff），这里【再写一遍】不是为了重复，
     * 而是为了让"上传目录的安全性"不依赖安全过滤链的配置：
     * 万一将来有人把 /uploads/** 从 Security 的过滤链里排除
     * （比如为了让它彻底匿名、少走一层），那个头会跟着一起消失，
     * 而附件目录恰恰是最需要它的地方 —— 静态文件是最容易被"嗅探执行"的。
     */
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    private static final String CONTENT_DISPOSITION = "Content-Disposition";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 无论内联还是下载都带上：禁止浏览器"猜"类型。
        // 没有它的话，一个被当成纯文本的 .md 也可能被浏览器嗅探成 HTML 执行
        response.setHeader(NOSNIFF_HEADER, "nosniff");

        // 取出 /uploads/ 之后的那一段（也就是对象 key，形如 attachment/2026/09/xxx.pdf）。
        // ⚠️ 先减掉 contextPath：本项目的 context-path 是空的，但这个过滤器不该
        // 因为将来加了 context-path 就静默失效（失效的表现是"附件又能被就地打开了"，
        // 而没有任何报错）。
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String objectKey = uri.startsWith(UPLOADS_PREFIX) ? uri.substring(UPLOADS_PREFIX.length()) : "";

        if (mustDownload(objectKey)) {
            // filename 用【对象 key 的文件名】（它是我们生成的 UUID.扩展名），
            // 【不用】用户上传时的原始文件名 —— 两个原因：
            //   ① 原始名是不可信输入，直接拼进响应头就是响应头注入
            //      （名字里带换行/引号就能凭空多出一条头），
            //      而文件名里没有 CR/LF 是 Web 服务器最基本的规矩
            //   ② 原始名可能是中文/空格/特殊字符，要正确表达就得用
            //      RFC 5987 的 filename*=UTF-8''… 形式（老浏览器还不认），
            //      而这里【不需要】那个信息：附件在页面上的显示名来自数据库
            //      （article_attachment.name），下载下来的临时名字用 UUID
            //      完全不影响使用 —— 前端可以在下载时用 <a download="名字"> 覆盖它
            response.setHeader(CONTENT_DISPOSITION,
                    "attachment; filename=\"" + safeFilename(objectKey) + "\"");
        }
        // 内联的那些不设 Content-Disposition：让浏览器按自己的默认行为处理
        // （图片显示、音频播放）。设成 inline 反而可能被个别代理改写成下载

        filterChain.doFilter(request, response);
    }

    /**
     * 判断这个对象要不要强制下载。
     *
     * 规则（两段，顺序有意义）：
     *   ① 在附件目录里的 → 一定下载（不管扩展名，理由见类注释）
     *   ② 其它文件 → 扩展名不在"可内联白名单"里的一律下载（保守的默认值）
     */
    private boolean mustDownload(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        String attachmentPrefix = properties.getAttachmentKeyPrefix();
        if (StringUtils.hasText(attachmentPrefix)
                && objectKey.startsWith(attachmentPrefix + "/")) {
            return true;
        }
        return !INLINE_EXTENSIONS.contains(extensionOf(objectKey));
    }

    /** 取小写扩展名（不含点）；取不到时返回空串 —— 空串不在白名单里，于是走"下载" */
    private String extensionOf(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return "";
        }
        return objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 把 key 的文件名部分清洗成"只含安全字符"的字符串，用于 Content-Disposition。
     *
     * 【为什么要把非 [A-Za-z0-9._-] 的字符换掉】
     *   对象 key 是我们生成的（UUID + 扩展名），正常全是安全字符。
     *   但这个头一旦被构造出来就会进 HTTP 响应，而 HTTP 头是【按行解析】的：
     *   一个 CR/LF 就能注入一条新头（响应头拆分行，
     *   攻击者据此可以做缓存投毒、会话固定等）。所以这里不假设
     *   "key 一定是安全的"——把所有其它字符统统换成下划线，
     *   成本是一次正则匹配，换掉的是整整一类头注入问题。
     */
    private String safeFilename(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        String filename = slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
        if (!StringUtils.hasText(filename)) {
            // 理论上不会发生（key 一定以文件名结尾）；给个兜底值，
            // 免得拼出一个 filename="" 的空头
            filename = "download";
        }
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
