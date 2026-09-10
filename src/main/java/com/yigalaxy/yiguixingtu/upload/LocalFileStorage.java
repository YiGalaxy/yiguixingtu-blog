package com.yigalaxy.yiguixingtu.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * =====================================================================
 * 本地磁盘存储 —— 把文件写到服务器/本机的一个目录里
 *
 * 【它现在是 FileStorage 的【唯一】实现】
 *   项目早期还有一套 `OssFileStorage`（对象存储），靠 `app.upload.storage`
 *   这个配置项在两者之间切换；后来定为只用本地磁盘，那一套连同依赖一起删掉了。
 *   所以这里【不再需要 @ConditionalOnProperty】—— 没有第二个候选 Bean 要区分，
 *   留着条件装配只会让人以为"还有别的实现没启用"。
 *
 * 【什么时候用它】一直是它：本地开发、自动化测试、线上部署都是这套。
 *   见 README「文件上传」章节里"为什么不用对象存储"的取舍说明。
 *
 * 【用到的东西】
 *   · {@link Files#createDirectories} + {@link Files#write} —— JDK 自带的 NIO，
 *     不需要引入任何第三方库
 *   · {@code Paths.get(...).resolve(...)} —— 拼路径用 resolve 而不是字符串相加，
 *     它会正确处理不同系统的路径分隔符（Windows 是 \，Linux 是 /）
 *
 * 【⚠️ 安全要点：防止路径穿越】
 *   objectKey 是我们自己生成的（不是用户传的），理论上安全。
 *   但这里仍然做了归一化检查：万一将来有别的调用方把用户输入当 key 传进来，
 *   形如 {@code ../../etc/passwd} 的 key 会把文件写到目录外面去。
 *   下面的 normalize + startsWith 就是挡这个的。
 *
 * 【为什么这个方法会抛 IllegalStateException 而不是业务异常】
 *   写文件失败属于"服务器自己的问题"（磁盘满了、没权限），不是用户输入错，
 *   所以是 500 类错误。用运行时异常让全局异常处理器兜成 500，语义是对的。
 * =====================================================================
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private final UploadProperties properties;

    public LocalFileStorage(UploadProperties properties) {
        this.properties = properties;
    }

    @Override
    public String store(byte[] content, String objectKey, String contentType) {
        Path root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();

        // 【路径穿越防护】拼出来的路径必须仍然在根目录下面。
        // 不检查的话，一个 "../../xxx" 形式的 key 就能把文件写到项目外面。
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的存储路径: " + objectKey);
        }

        try {
            // 父目录可能还不存在（比如第一次上传、或者到了新的月份），先建出来
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            // 转成运行时异常交给全局异常处理器 → 500。
            // 附带说明：这里【不打日志输出文件内容或路径细节到用户可见的地方】，
            // 但服务端日志要记清楚，方便运维排查磁盘问题
            log.error("写入本地文件失败: {}", target, e);
            throw new IllegalStateException("保存文件失败，请稍后重试", e);
        }

        // 返回可访问的 URL。真正的静态资源映射见 WebMvcConfig（/uploads/** → 这个目录）
        String url = trimTrailingSlash(properties.getBaseUrl()) + "/uploads/" + objectKey;
        log.info("文件已保存到本地: {} -> {}", target, url);
        return url;
    }

    /** 去掉 baseUrl 结尾多余的斜杠，避免拼出 "http://x//uploads/..." 这种双斜杠 */
    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
