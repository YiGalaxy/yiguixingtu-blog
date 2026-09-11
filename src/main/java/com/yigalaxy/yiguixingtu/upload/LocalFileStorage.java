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

    /**
     * 删除磁盘上的一个文件（文章删除、或编辑时移除附件时调用）。
     *
     * 【为什么用 deleteIfExists 而不是 delete】
     *   删除是【幂等】操作：调用方要的是"这个文件不在磁盘上"这个结果。
     *   用 delete 的话，"文件本来就不存在"会抛 NoSuchFileException ——
     *   于是每一次"重复清理"都会变成一次失败（日志里一堆吓人的 ERROR，
     *   而实际上什么都没坏）。deleteIfExists 把这件事表达对了：
     *   存在就删，不存在就当作已经达成目标。
     *
     * 【路径处理的规则与 store 完全一致】同样先归一化、再检查它还在根目录下面。
     *   这里更要紧：删除比写入更不可逆，一个 "../../xxx" 形式的 key
     *   能把项目目录外面的文件删掉。key 是我们自己生成的、理论安全，
     *   但这道检查的成本是一次字符串比较，没有理由省。
     *
     * 【为什么这个方法不吞异常】
     *   真正删不掉（没权限、文件被别的进程占用）说明磁盘状态和我们的认知不一致，
     *   应当让人知道，所以转成运行时异常往上抛（与 store 失败的语义一致）。
     *   至于"上传/删除业务已经成功、文件清理失败"该怎么办，是调用方的取舍 ——
     *   ArticleServiceImpl 那边选择记日志并继续（理由写在它的级联注释里）。
     */
    @Override
    public void delete(String objectKey) {
        Path root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();

        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的存储路径: " + objectKey);
        }

        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("已删除本地文件: {}", target);
            } else {
                // 不存在不是错误（删除是幂等的），但要留下痕迹：
                // 频繁出现这一行说明"数据库里有引用、磁盘上没文件"，
                // 那是另一类问题（比如有人手工清了目录），值得被看见
                log.info("待删除的文件本来就不存在（视为已完成）: {}", target);
            }
        } catch (IOException e) {
            log.error("删除本地文件失败: {}", target, e);
            throw new IllegalStateException("删除文件失败", e);
        }
    }

    /**
     * 统计上传目录里所有文件加起来占了多少字节（上传总量护栏要用）。
     *
     * 【为什么真的去遍历目录，而不是在 Redis/数据库里维护一个计数器】
     *   计数器方案更快，但它必须与每一次写入、每一次删除、以及
     *   【任何在应用之外发生的文件变动】（运维手工清理、备份脚本、
     *   容器重建时卷的变化）保持同步 —— 只要有一处对不上，
     *   计数就会永远偏掉，而且没有任何机制能自动纠正它。
     *   而遍历目录是"磁盘此刻真实的状态"，永远不会骗人。
     *   本项目是个人博客量级（上传目录通常几百到几千个文件），
     *   一次遍历是毫秒级；换来的是"护栏的数字一定是对的"。
     *   ⚠️ 量级真上去之后（几万个文件、或换成对象存储）再改成计数器，
     *   那时见 FileStorage.usedBytes 的注释：这是一个需要重新想的决定。
     *
     * 【为什么用 Files.walk 而不是 Files.list】
     *   uploads 下面是 {cover,music,attachment}/yyyy/MM 这样的多层目录，
     *   只列一层会把子目录整个漏掉（护栏就形同虚设）。
     *   try-with-resources 是必须的：Stream 持有目录句柄，
     *   不关会在文件多时耗尽句柄（这在 Windows 上尤其明显）。
     *
     * 【为什么只统计普通文件、并且吞掉单个文件读不到的错误】
     *   size() 对普通文件是元数据读取，不会真的读内容，很便宜；
     *   但目录本身也算 size（几百字节的元数据），所以用 isRegularFile 过滤掉。
     *   某个文件恰好被并发删掉时会抛 NoSuchFileException ——
     *   那正是"它不再占空间"的情形，忽略即可，不该让一次上传报错。
     */
    @Override
    public long usedBytes() {
        Path root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            // 目录还不存在（从没上传过）＝ 占用为 0。
            // 这里不能去创建目录：统计占用不该有副作用
            return 0L;
        }

        long total = 0L;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                try {
                    if (Files.isRegularFile(path)) {
                        total += Files.size(path);
                    }
                } catch (IOException e) {
                    // 单个文件读不到（并发删除、权限问题）：跳过它，不因为一个文件
                    // 让整个上传接口失败。占用会被略微低估 —— 这是可接受的偏差，
                    // 而"上传直接报 500"不可接受
                    log.warn("统计上传目录占用时跳过读不到的文件: {}", path, e);
                }
            }
        } catch (IOException e) {
            log.error("统计上传目录占用失败: {}", root, e);
            throw new IllegalStateException("统计上传目录占用失败", e);
        }
        return total;
    }
}
