package com.yigalaxy.yiguixingtu.upload;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * =====================================================================
 * 上传相关的配置项（对应 application.properties 里的 app.upload.*）
 *
 * 【这个类是干什么的】
 *   把配置文件里的一组 app.upload.* 项，按名字自动绑定成 Java 对象。
 *   这样业务代码里注入 UploadProperties 就能拿到 maxSize、allowedExtensions 等，
 *   不用到处写 @Value("${app.upload.max-size}")。
 *
 * 【用到的东西】
 *   · {@code @ConfigurationProperties(prefix = "app.upload")} ——
 *     Spring Boot 提供的能力：把前缀匹配的配置项一次性绑到字段上。
 *     字段名用驼峰，配置里用中划线/小写（max-size ↔ maxSize），会自动对应。
 *   · {@code @Component} —— 让它成为一个 Bean 才能被注入。
 *     （另一种写法是 @EnableConfigurationProperties 显式注册，这里用 @Component 更省事）
 *   · Lombok 的 {@code @Data} —— 生成 getter/setter。
 *     ⚠️ ConfigurationProperties 的绑定【必须有 setter】，所以这个注解不能省。
 *
 * 【为什么不校验在这里，而在 UploadService 里校验】
 *   这里只管"配置长什么样"，不管"用户上传的东西合不合法"。
 *   校验上传的文件是业务逻辑，放 Service；配置项本身写错了会在启动时报错，
 *   不需要业务代码兜底。
 *
 * 【⚠️ 这份配置里为什么没有任何"对象存储（OSS）"的项】
 *   项目最初留了一套 OSS 实现（`OssFileStorage` + storage=oss 开关 + 一堆 oss-* 配置），
 *   最后定为【只用本地磁盘】，于是把那一整套连依赖一起删掉了。理由与取舍见
 *   README「文件上传」章节：单台 ECS + 个人博客的量级，本地磁盘够用，
 *   少一个外部依赖就少一处会失败的地方。
 *   将来真要上对象存储时，做法是"实现 FileStorage 接口 + 加回它自己的配置项"，
 *   UploadService 一行都不用改（它只依赖接口）——
 *   留着没人用的实现和密钥配置，反而是一种负担：多一份要跟着改的代码、
 *   多一个会过期的密钥、也多一条"配错了但没人发现"的路径。
 * =====================================================================
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /**
     * 文件写到哪个目录（相对或绝对路径都行）。
     *
     * 【线上为什么必须是挂载点里的路径】见 docker-compose.prod.yaml 里
     * {@code UPLOAD_LOCAL_DIR=/app/uploads} 那段的说明：
     * 写成容器自己的可写层，容器一重建图片就全丢了，而且没有任何报错。
     */
    private String localDir = "./uploads";

    /**
     * 访问这些文件的地址前缀。
     * 本地开发是 http://localhost:8082，
     * 线上填"浏览器能访问到后端 /uploads/** 的那个前缀"（本项目是 https://域名/api）。
     *
     * 【为什么它必须和浏览器看到的一致】本地存储返回的 URL 是
     * {@code {base-url}/uploads/xxx}，这个字符串会直接存进文章表并展示给浏览器；
     * 填成内网地址（比如 http://backend:8082）在容器里自己访问得到，
     * 但用户的浏览器访问不到 —— 表现为"后台上传成功、前台图片裂了"。
     */
    private String baseUrl = "http://localhost:8082";

    /**
     * 允许上传的文件扩展名（小写，不含点）—— 【图片】这一套。
     *
     * 【为什么用白名单而不是黑名单】黑名单永远列不全
     * （.jsp、.jspx、.phtml、.svg 里塞脚本……），白名单只放行确定安全的几种，
     * 漏掉的那部分默认被拒，这才是安全的默认值。
     *
     * 【⚠️ 音频走的是另一套（见下面 audioAllowedExtensions）】
     *   往这一个列表里加 "mp3" 是最省事的改法，但那会同时放宽图片的规则 ——
     *   完整推导见 {@link UploadType} 的类注释。
     */
    private List<String> allowedExtensions = List.of("jpg", "jpeg", "png", "gif", "webp");

    /**
     * 单文件大小上限（【图片】这一套）。2026-09 由站长决定从 5MB 提到【10MB】。
     *
     * 【10MB 这个数字的依据】
     *   原来那个 5MB 是"网页时代的封面图"量级（压缩后的 JPEG 通常几百 KB）。
     *   现在封面图的来源大多不是"压缩过的网页图"，而是：
     *     · 手机直出照片：主流机型一张 3~8MB（4800 万像素那类轻松超过 5MB）
     *     · 设计稿/截图导出的 PNG：一张 2~6MB 很常见
     *   也就是说 5MB 会开始【频繁拒绝正常尺寸的封面图】，而站长上传时得到的
     *   只是一个"不能超过 5MB"的提示 —— 只能自己先找工具压一遍。
     *   10MB 覆盖了上面两类来源（并留了一点余量），同时仍然远小于附件那一套的 100MB：
     *   图片是要在前台列表里【直接渲染】的资源，封面图不该是几 MB 的巨物
     *   （那会把首屏流量与内存都吃掉）。真要放超大图，正确做法是先压缩，
     *   而不是把这个上限继续往上调。
     *
     * 【为什么类型是 DataSize 而不是 long】
     *   一开始这里写的是 {@code long maxSize}，配置里写 {@code app.upload.max-size=5MB} ——
     *   结果应用【启动直接失败】：
     *     Failed to convert from type [java.lang.String] to type [long] for value [1KB]
     *   因为 "5MB" 不是数字，绑定不到 long 上。
     *   换成 Spring Boot 自带的 {@link DataSize} 之后，"5MB" / "1KB" / "512" 都能正确解析，
     *   代码里用 {@code toBytes()} 拿字节数。
     *   这也是这类"容量/时长"配置项的推荐写法 —— 用错类型不会编译报错，
     *   只会在启动时才炸，属于很值得记一笔的坑。
     *
     * 【为什么服务端必须限】前端能限制，但前端可以绕过（直接调接口、改 JS）。
     *   服务端这一层才是真正生效的。另外 Spring Boot 自身还有 multipart 的 max-file-size，
     *   两者都要设（见 application.properties），否则请求会在更早的一层被拒，
     *   报出来的错也和业务无关。
     */
    private DataSize maxSize = DataSize.ofMegabytes(10);

    /**
     * 存储路径的前缀（相当于"目录"），默认 cover。
     * 真正的路径形如 {@code cover/2026/09/{uuid}.jpg}。
     *
     * 【为什么叫 key 而不是"文件名"】它描述的是"存储里的位置"，
     * 换到对象存储语义下就是 object key —— 名字保持中性，
     * 将来实现别的 FileStorage 时不用跟着改。
     */
    private String keyPrefix = "cover";

    // =================================================================
    //  音频（type=audio）这一套 —— 与上面图片那一套完全独立
    //
    //  【为什么另起三个配置项，而不是复用上面三个】
    //   上传有两种"种类"，它们的规则【不同且必须能分别调整】：
    //     · 扩展名：图片五种，音频只有 mp3
    //     · 大小：见下面 audioMaxSize 里"20MB 的依据"
    //     · 目录：音频放 uploads/music/，与封面图分开
    //   复用一个配置项的话，"给音频放宽到 20MB"会顺带把图片也放宽 ——
    //   而这三项各自都有对应的用例钉着（见 UploadAdminTest 的音频那一组）。
    // =================================================================

    /**
     * 允许上传的【音频】扩展名，默认只有 mp3。
     *
     * 【为什么只有 mp3，不加 wav / flac / m4a】
     *   这几种的取舍不是"能不能播"，而是"静态托管值不值"：
     *     · wav 是无压缩，一首歌 40–60MB，比下面 20MB 的上限还大 —— 收它就得再放宽上限
     *     · flac 同样是无损大文件，而浏览器里 Safari 之外的支持情况参差不齐
     *     · m4a / aac 浏览器支持很好，但和 mp3 相比没有"必须支持"的理由
     *   而这个项目是"个人博客的背景音乐 / 曲目列表"，浏览器原生的 {@code <audio>}
     *   对 mp3 的支持是最没有疑问的。真要支持无损，做法是往这里加一项（并同步说明大小依据），
     *   而不是先把口子开大再说。
     */
    private List<String> audioAllowedExtensions = List.of("mp3");

    /**
     * 音频单文件大小上限，默认 20MB。
     *
     * 【20MB 这个数字的依据 —— 不是拍的，是算出来的】
     *   · 主流音乐平台的高质量档约 320kbps（≈40KB/s），即 0.32 Mbps
     *   · 一首 5 分钟的歌：320 kbps × 300 秒 ÷ 8 = 12MB
     *   · 再加一点余量（略长的曲目、"码率更高一点点的转码"）取 20MB ——
     *     它足以覆盖 320kbps 下 8 分钟以内的曲子，而一首正常流行歌曲用不到
     *   对比图片的 10MB：音频天然大一个量级（它是有时间维度的媒体），
     *   所以两套上限分开写、各自有依据，而不是"统一调大到 20MB"。
     *
     * 【⚠️ 它必须 ≤ {@code spring.servlet.multipart.max-file-size}】
     *   那一项是框架层的兜底：超过它的请求【根本不会走到业务代码】，
     *   报出来的是一个和业务无关的框架异常（用户看不懂，也没法按提示处理）。
     *   所以 application.properties 里 multipart 的限额按【三类里最大的那个
     *   （附件 100MB）】设置，而"图片只能 10MB""音频只能 20MB"这两条规则
     *   由 {@link UploadService} 按种类判断 ——
     *   框架层管"请求能不能进来"，业务层管"这一类允许多大"，两层各管一段。
     */
    private DataSize audioMaxSize = DataSize.ofMegabytes(20);

    /**
     * 音频的存储路径前缀，默认 music。
     * 真正的路径形如 {@code music/2026/09/{uuid}.mp3}，访问地址是 {@code /uploads/music/...}。
     *
     * 【为什么和图片分开目录】两类文件的生命周期完全不同：
     *   图片是"文章的封面"，文章删了图还在引用关系里；音频是"曲目文件"，通常几十个、
     *   体积大得多。分开之后，备份策略（音频可以不进每日增量）、清理（按目录删旧曲）
     *   和排查（"这个 mp3 是谁传的"）都简单 —— 混在一个目录里就得靠扩展名去筛。
     *   静态映射 {@code /uploads/**} 已经覆盖子目录（见 WebMvcConfig），不用额外配置。
     */
    private String audioKeyPrefix = "music";

    // =================================================================
    //  附件（type=attachment）这一套 —— 文章里可下载的文件
    //
    //  【为什么又是三个独立的配置项，而不是复用上面任何一套】
    //   与音频那一段的理由完全相同，但方向更极端：附件这一套的每个值
    //   都和图片/音频不一样，而"借用"其中任何一个都会污染另一类：
    //     · 扩展名：16 种文档与媒体（图片那一套只有 5 种图片格式）
    //     · 上限：100MB（图片 10MB / 音频 20MB）
    //     · 目录：uploads/attachment/（与封面、音乐分开，便于备份与清理）
    //   完整推导见 {@link UploadType} 的类注释。
    // =================================================================

    /**
     * 允许上传的【附件】扩展名（小写，不含点）。
     *
     * ⚠️⚠️ 【这个白名单里绝对不能出现 html / htm / svg / xml / js / mjs / css】
     *   这几个扩展名的共同点是：**浏览器打开它们时会【执行】里面的内容**。
     *   而 /uploads/** 是本项目的静态映射（见 WebMvcConfig），
     *   也就是说这些文件会被浏览器当成"本站的页面/脚本"来跑 ——
     *   等于在【自己的域名下】开了一个存储型 XSS 入口：
     *     · 攻击者传一个 x.html（里面是一段 JS），把它的地址发给你或者贴进评论区
     *     · 用户点开 → 浏览器执行那段 JS → 它和本站【同源】
     *     · 同源意味着它读得到 localStorage 里的 token、能带着 cookie 发起请求、
     *       能改这个页面看到的一切 —— 前端做的所有 XSS 防护在这一步全部作废
     *   （XML 单独说一句：它不会被当页面执行，但 svg/xml 常被用来绕过"只挡 html"
     *     的黑名单，而这类绕过正是下面"为什么用白名单"要防的东西。）
     *   本项目对下载头也做了第二道防线（见 UploadResponseHeaderFilter）：
     *   附件响应强制 Content-Disposition: attachment，浏览器只会下载、不会就地打开。
     *   但【白名单是第一道、也是最重要的一道】—— 头部能不能带上，
     *   取决于请求真的走了我们的过滤器（将来若把 /uploads 交给 Nginx 直接发，
     *   头部就不会有了），而"文件根本不在盘上"是任何配置都绕不过去的。
     *   两道都留着：安全上的"纵深防御"就是这样叠出来的。
     *
     * 【为什么用白名单而不是黑名单】黑名单永远列不全（.jsp / .jspx / .phtml /
     *   .xhtml / .htaccess / .svgz……），漏掉的那个就是漏洞。白名单只放行
     *   确定安全的那些，其余默认被拒 —— 这才是安全的默认值。
     *
     * 【为什么收 mp3 / mp4（它们和音频模块重合）】
     *   音频模块收的是"背景音乐"，走的是 <audio> 播放（20MB 上限）。
     *   而附件里的 mp3/mp4 是【用户要下载的资料】（讲座录音、演示视频），
     *   100MB 的上限正是为它们准备的。两者用途不同，所以分属两套规则 ——
     *   "同一份 mp3 既能当曲目又能当附件"是正常的，不是重复。
     *   ⚠️ 这两个扩展名也会被 UploadResponseHeaderFilter 判成"可以内联"，
     *   但附件目录里的文件仍然强制下载（见那个过滤器的注释）：
     *   归类以【它放在哪个目录】为准，不以扩展名为准。
     *
     * 【为什么收 json / md / csv / txt】
     *   它们是"资料"的常见形态（配置文件、数据表、说明文档），
     *   都是纯文本，浏览器打开时【不会被当网页执行】，所以是安全的。
     */
    private List<String> attachmentAllowedExtensions = List.of(
            "pdf", "zip", "7z", "rar",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json",
            "mp3", "mp4");

    /**
     * 附件单文件大小上限，默认 100MB。
     *
     * 【100MB 这个数字的依据】
     *   它是"一个人类要下载的文件"的量级上限，而不是"服务器能承受的大小"：
     *     · 一篇论文 PDF / 一份 PPT / 一个数据包：几 MB ~ 几十 MB
     *     · 讲座录音（mp3）：1 小时约 60MB
     *     · 演示视频（mp4）：几分钟的屏幕录制就在 50~100MB
     *   再往上（几百 MB）就不该走"博客附件"这条路了：那是网盘/对象存储的场景，
     *   而本项目是单台 ECS + 本地磁盘（见 README「文件上传」），
     *   让一个附件吃满几百 MB 会立刻把磁盘与带宽都变成瓶颈。
     *
     * 【⚠️ 它必须 ≤ {@code spring.servlet.multipart.max-file-size}】
     *   理由与 audioMaxSize 那一段完全相同（超过框架限额的请求走不到业务代码），
     *   但这里数值最大，所以 application.properties 里的 multipart 限额是
     *   【按它来设的】（105MB：留一点给表单里的其它字段）。
     *
     * 【⚠️ 单文件上限之外还有一道"总闸"】见下面的 maxTotalSize ——
     *   20 个 100MB 的附件就是 2GB，光限制单个文件挡不住"几十个附件把 40GB
     *   磁盘写满"这件事。
     */
    private DataSize attachmentMaxSize = DataSize.ofMegabytes(100);

    /**
     * 附件的存储路径前缀，默认 attachment。
     * 真正的路径形如 {@code attachment/2026/09/{uuid}.pdf}，
     * 访问地址是 {@code /uploads/attachment/...}。
     *
     * 【为什么目录名是 attachment 而不是 file】
     *   它要和"这个目录里装的是什么"对上：进这个目录的只有附件（附件的白名单），
     *   而 file 是个什么都能装的词 —— 半年后打开 uploads/file/ 看到一堆文件时，
     *   没人能立刻说出"这些是谁传的、能不能删"。命名在这里是安全的一部分：
     *   UploadResponseHeaderFilter 正是按这个前缀判断"要不要强制下载"的。
     */
    private String attachmentKeyPrefix = "attachment";

    /**
     * 整个上传目录的【总容量上限】，默认 5GB —— 相当于单文件上限之外的第二道闸。
     *
     * 【为什么单文件 100MB 还不够，必须再有一道总量限制】
     *   两道闸防的是完全不同的两件事：
     *     · 单文件上限防的是"一个文件太大"（传输时间、内存、单次事故的规模）
     *     · 总容量上限防的是"文件【多到】把磁盘写满" —— 40GB 的 ECS 磁盘上，
     *       40 个 100MB 的附件（压缩包、视频，一次上传就几十 MB）就能填满，
     *       而且是【合法的、逐个通过的】上传，单文件那一层永远拦不住它。
     *   磁盘写满的后果比"某次上传失败"严重得多：MySQL 写不进 redo/binlog、
     *   Redis 的 AOF 写不进去、应用本身的日志也写不进去 ——
     *   表现是"整个站点一起挂"，而且看日志只能看到一堆不相干的写入错误。
     *   所以宁可在这里明确拒绝一次上传（并告诉用户"去清理旧附件"），
     *   也不要等到磁盘满了才发现没有闸。
     *
     * 【5GB 这个数字的依据】
     *   · 它是"上传目录"的预算，不是"磁盘大小"：一台 40GB 的 ECS 上，
     *     系统 + 数据库 + 日志大约占一半，剩给上传的余量取 5GB 是安全的
     *   · 按 5GB 算，站点可以放约 50 个 100MB 的大附件、
     *     或几百张封面图 + 上百个常见尺寸的 PDF —— 对个人博客绰绰有余
     *   · 它是【可配置项】（app.upload.max-total-size）：磁盘扩容、
     *     或者接了对象存储之后，改配置即可，不用改代码
     *
     * 【⚠️ 它统计的是"目录里所有文件加起来"，不是"数据库里记录的附件"】
     *   因为磁盘是真的会满的，而数据库里的记录不一定对得上：
     *   删文章会删文件、用户传了没保存会留下孤儿文件、手工往目录里放过东西……
     *   直接量目录才是"磁盘到底还剩多少"这个问题的答案。
     *   统计方式与代价见 {@link UploadService} 里的 usedBytes 调用点。
     */
    private DataSize maxTotalSize = DataSize.ofGigabytes(5);
}
