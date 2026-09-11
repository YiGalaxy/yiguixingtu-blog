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
     * 单文件大小上限。默认 5MB。
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
    private DataSize maxSize = DataSize.ofMegabytes(5);

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
     *   对比图片的 5MB：音频天然大一个量级（它是有时间维度的媒体），
     *   所以两套上限分开写、各自有依据，而不是"统一调大到 20MB"。
     *
     * 【⚠️ 它必须 ≤ {@code spring.servlet.multipart.max-file-size}】
     *   那一项是框架层的兜底：超过它的请求【根本不会走到业务代码】，
     *   报出来的是一个和业务无关的框架异常（用户看不懂，也没法按提示处理）。
     *   所以 application.properties 里 multipart 的限额按"音频上限"设置，
     *   而"图片只能 5MB"这条规则由 {@link UploadService} 按种类判断 ——
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
}
