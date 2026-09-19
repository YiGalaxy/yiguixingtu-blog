package com.yigalaxy.yiguixingtu.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
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
 * 【⚠️ 2026-09 起：key 是内容哈希，不是随机 UUID】
 *   文件名由 {@code UploadService.buildObjectKey} 按文件内容的 SHA-256 生成，
 *   所以"同一个 key 已经有文件"= "要写的内容与磁盘上那份逐字节相同"。
 *   这条性质在这里有两处直接后果：
 *     · {@link #exists} 成了上传去重的判断依据（命中就不写盘）
 *     · {@link #store} 把并发撞名（FileAlreadyExistsException）当【成功】处理，
 *       而不是失败 —— 详细推导写在 store 的 catch 里
 *   ⚠️ 若将来 key 改回随机命名，上面第二条必须一起改回去。
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
    public String store(InputStream content, String objectKey, String contentType) {
        Path target = resolveWithinRoot(objectKey);

        try {
            // 父目录可能还不存在（比如第一次上传、或者到了新的月份），先建出来
            Files.createDirectories(target.getParent());

            // 【为什么是 copy(流) 而不是 write(byte[])】
            //   write 要求整个文件已经在内存里；copy 是流式的，堆里只有一个
            //   固定大小的缓冲区 —— 上传 100MB 附件时，两者的差别就是
            //   "多占 100MB 堆"和"几乎不占堆"。完整推导见 FileStorage.store 的参数说明。
            //
            // ⚠️ 这里【不关闭】content：它是调用方（UploadService）传进来的，
            //    那边用 try-with-resources 管着。这里关掉会变成二次关闭。
            //   ⚠️ 也【不带 REPLACE_EXISTING】：带上的话，"同一个 key 已经有文件"
            //    会变成"先截断再重写"，而此刻可能正好有请求在读那个文件，
            //    它会读到半截内容（图片裂开、mp3 播到一半断）。见下面的 catch。
            Files.copy(content, target);
        } catch (FileAlreadyExistsException e) {
            // ============================================================
            // 【竞态：这个 key 已经有文件了 —— 在内容寻址下这是"命中"，不是错误】
            //   key 是内容哈希（见 UploadService.buildObjectKey），所以"同一个 key
            //   上已经有文件"意味着磁盘上那份与这次要写的内容【逐字节相同】。
            //   成因是检查与写入之间的并发：同一张图被两个请求同时上传，
            //   两边都问过 exists（都说没有），然后一起写。
            //
            //   为什么当作成功而不是失败：
            //     · 要写的内容已经在磁盘上了，调用方要的结果【已经达成】
            //     · 抛出去会让其中一个用户看到 500 —— 而他上传的图其实好好的，
            //       这种"报错但其实成功了"最难解释
            //   ⚠️ 这个"当成功"成立的前提是【key 必须是内容哈希】。
            //      如果哪天又回到随机命名，这里就会把"两个不同文件撞名"
            //      静默地变成"后传的丢了"—— 那时必须改成抛异常。
            // ============================================================
            log.info("目标文件已存在（并发上传同一内容），按命中处理: {}", target);
        } catch (IOException e) {
            // 转成运行时异常交给全局异常处理器 → 500。
            // 附带说明：这里【不打日志输出文件内容或路径细节到用户可见的地方】，
            // 但服务端日志要记清楚，方便运维排查磁盘问题
            log.error("写入本地文件失败: {}", target, e);
            throw new IllegalStateException("保存文件失败，请稍后重试", e);
        }

        // 返回可访问的 URL。真正的静态资源映射见 WebMvcConfig（/uploads/** → 这个目录）
        String url = urlOf(objectKey);
        log.info("文件已保存到本地: {} -> {}", target, url);
        return url;
    }

    /**
     * 看这个 key 上是不是已经有文件了（上传去重要用，见 {@link FileStorage#exists}）。
     *
     * 【为什么用 isRegularFile 而不是 exists】
     *   {@code Files.exists} 对目录也返回 true。虽然 key 里带扩展名、正常不会
     *   指向一个目录，但真发生那种情况时，"存在的目录"会被当成"文件已经在"，
     *   于是上传被跳过一次写入、前台拿到一个 404 的地址。
     *   isRegularFile 把判断收窄成"确实是一个文件"。
     *
     * 【为什么不吞异常】只读一次元数据，抛出的 IOException 属于"磁盘出问题了"，
     *   应当照常变成 500，而不是在这里猜一个默认值。
     */
    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolveWithinRoot(objectKey));
    }

    /** 去掉 baseUrl 结尾多余的斜杠，避免拼出 "http://x//uploads/..." 这种双斜杠 */
    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 一个 key 对应的对外访问地址（见 {@link FileStorage#urlOf}）。
     *
     * 【⚠️ 它只拼字符串，【不碰磁盘】】所以"文件其实不存在"它也会照常返回地址。
     *   这是有意的：调用它的场景有两处 ——
     *     · store 写完之后（文件刚写完，一定存在）
     *     · 上传命中已有文件时（那个文件的存在性由 UploadService 先问过 exists）
     *   再查一次盘既多余，也会让"存在性判断"出现两个真相来源。
     *   真的拿到一个 404 地址，那是调用方跳过了 exists，问题在那边。
     *
     * 【这里仍然做路径校验】虽然不落盘、穿不出目录，但一个
     * {@code ../../} 形式的 key 会拼出一个指向站外的 URL 返回给前端。
     * 与 store / delete 用同一把尺子，成本是一次字符串比较。
     */
    @Override
    public String urlOf(String objectKey) {
        resolveWithinRoot(objectKey);
        return trimTrailingSlash(properties.getBaseUrl()) + "/uploads/" + objectKey;
    }

    /**
     * 把 objectKey 解析成根目录下的绝对路径，并确认它没有跑到根目录外面去。
     *
     * 【为什么抽出来】store / delete / exists / urlOf 四处原本各写一遍这三行
     *   （取根目录、resolve、startsWith 检查）。它们是【安全边界】——
     *   正是那种"各处抄一遍、哪天有人只改一处"最容易出事的地方
     *   （删除比写入更不可逆：一个 "../../xxx" 的 key 能删掉目录外面的文件）。
     *   收在一个方法里之后，"路径必须落在根目录内"这条规则只有一个实现。
     *
     * @throws IllegalArgumentException key 会落到根目录之外时
     */
    private Path resolveWithinRoot(String objectKey) {
        Path root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的存储路径: " + objectKey);
        }
        return target;
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
        Path target = resolveWithinRoot(objectKey);

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
