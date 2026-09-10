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

    /**
     * 存储路径的前缀（相当于"目录"），默认 cover。
     * 真正的路径形如 {@code cover/2026/09/{uuid}.jpg}。
     *
     * 【为什么叫 key 而不是"文件名"】它描述的是"存储里的位置"，
     * 换到对象存储语义下就是 object key —— 名字保持中性，
     * 将来实现别的 FileStorage 时不用跟着改。
     */
    private String keyPrefix = "cover";
}
