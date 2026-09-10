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
 * =====================================================================
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /**
     * 存储方式：local（存本地磁盘，默认）/ oss（存阿里云 OSS）。
     *
     * 【为什么要做成可切换的，而不是直接写死 OSS】
     *   1. 本地开发和测试不应该依赖真实 OSS 凭据与外网 ——
     *      否则 clone 下来的人根本没法验证上传功能，CI 也跑不了
     *   2. 上线初期可以先不配 OSS，用本地存储把站点跑起来，
     *      等 OSS 配好再改一个配置切过去，不需要改代码、不需要重新构建
     */
    private String storage = "local";

    /** 本地存储时，文件写到哪个目录（相对或绝对路径都行） */
    private String localDir = "./uploads";

    /**
     * 本地存储时，访问这些文件的地址前缀。
     * 本地开发是 http://localhost:8082/uploads，
     * 线上如果 Nginx 也把 /uploads 反代到后端，就填 https://你的域名/uploads。
     */
    private String baseUrl = "http://localhost:8082";

    /**
     * 允许上传的文件扩展名（小写，不含点）。
     *
     * 【为什么用白名单而不是黑名单】黑名单永远列不全
     * （.jsp、.jspx、.phtml、.svg 里塞脚本……），白名单只放行确定安全的几种，
     * 漏掉的那部分默认被拒，这才是安全的默认值。
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

    // ---------------- 以下是 OSS 专用配置（storage=oss 时才需要） ----------------

    /** OSS 的 Endpoint，形如 https://oss-cn-hangzhou.aliyuncs.com */
    private String ossEndpoint;

    /** Bucket 名称 */
    private String ossBucket;

    /**
     * 访问密钥 ID。
     *
     * ⚠️ 【生产环境务必用 RAM 子账号的密钥，不要用主账号 AccessKey】
     *   主账号密钥权限是整个账号（能改账单、能删所有资源），
     *   一旦泄漏后果不可控。RAM 子账号可以只授权这一个 Bucket 的读写，
     *   就算泄漏，损失也被限制在一个桶里 —— 这就是"最小权限原则"。
     */
    private String ossAccessKeyId;

    /** 访问密钥 Secret（同样来自 RAM 子账号） */
    private String ossAccessKeySecret;

    /**
     * 对象 key 的前缀（相当于 OSS 里的目录），默认 cover。
     * 真正的 key 形如 cover/2026/09/xxxx.jpg
     */
    private String ossKeyPrefix = "cover";

    /**
     * 是否给返回的图片地址附带签名（私有 Bucket 才需要）。
     *
     * 【公有读 vs 私有读，怎么选】
     *   · 公有读：图片地址固定、能被 CDN 直接缓存，博客封面图一般这么用
     *   · 私有读：必须带签名才能访问，适合不对外公开的文件；
     *     签名 URL 有有效期，过期就失效
     *   本项目博客封面是公开内容，默认 false（公有读）；需要时配置成 true 即可。
     */
    private boolean ossSignedUrl = false;

    /** 签名 URL 的有效期（秒），仅 ossSignedUrl=true 时生效 */
    private long ossSignedUrlExpireSeconds = 3600L;
}
