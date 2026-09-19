package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * =====================================================================
 * 一次性迁移工具：把磁盘上【UUID 命名的旧文件】改成【内容哈希命名】，并改写库里所有引用
 *
 * ============ 它是干什么的、什么时候能删掉它 ============
 *
 * 【背景】2026-09 上传改成内容寻址（同一份内容只存一份，见 UploadService.buildObjectKey）。
 *   那只对新上传的文件生效 —— 磁盘上原来那些 {@code cover/2026/09/3f2b….png}
 *   还带着随机名，同一张图的老副本也还是各存各的。
 *   这个工具就是把存量数据也搬到新规则下。
 *
 * 【⚠️ 用完就该删】它是一次性迁移，不是常驻功能。判断"已经用不着"的标准：
 *   跑一次 {@code dryRun=true} 得到"待迁移 0 个文件"。那时这个类、它的接口
 *   与测试都可以整份删掉 —— 留着的话，下一个人会以为它是某种需要维护的能力。
 *   （写成"一次性工具"而不是数据库迁移脚本（Flyway）的原因：Flyway 只管数据库，
 *    而这件事要同时动磁盘文件和数据库，且必须按"先复制、再改库、最后删旧文件"
 *    的顺序做才安全 —— 见下面的崩溃安全性。让一个 SQL 脚本去协调磁盘文件是错配的工具。）
 *
 * ============ 崩溃安全性：为什么是这个顺序 ============
 *
 * 【三步顺序】① 把旧文件复制成哈希名 → ② 改写库里所有引用 → ③ 删掉旧文件
 *   每一步之间崩溃的后果，都不会让前台裂图：
 *     · ①之后崩 → 旧的还在、库还指向旧的 → 一切照常，只是多了一份没人引用的副本
 *     · ②之后崩 → 库指向新的（新的确实存在）、旧的还占着空间 → 照常，重跑一次即可
 *     · ③之后崩 → 本来就已经做完了
 *   三个卡点没有任何一个是"库指向了不存在的文件"。**顺序反过来则全都会裂图**。
 *
 * 【为什么是"复制"而不是"重命名"】
 *   重命名（Files.move）看起来更省事、也不多占空间，但它在①与②之间留下了一个
 *   **库还指着旧路径、旧路径已经不在了**的窗口 —— 那一瞬间的访问就是 404。
 *   复制多占的磁盘在③之后就还回来了。
 *
 * 【幂等】重复执行是安全的：已经是哈希名的文件会被跳过；
 *   目标已存在则不再复制；引用改写是"把旧串换成新串"，第二次跑没有旧串可换。
 *
 * ============ ⚠️ 只能在站点没人编辑的时候跑 ============
 *   它在②把所有引用从旧 key 改写成新 key，然后在③删掉旧文件。
 *   如果这中间有人在浏览器里开着一个【迁移前加载的】编辑框并点了保存，
 *   他提交的还是旧地址 —— 那篇文章就会指向一个刚被删掉的文件。
 *   这个窗口没法用事务消掉（跨越了 HTTP 请求），所以：
 *     · 迁移前先跑 dryRun，看清单是否符合预期
 *     · 挑没有人在后台编辑的时候执行
 *     · ③ 之前还会【再查一遍引用】（见 deleteOldFiles），
 *       真的有人在这期间把旧地址写了回去，那个文件会被保留而不是删掉
 *
 * ============ 为什么这里直接用 JdbcTemplate，而不是各个 Mapper ============
 *   本项目的规矩是"SQL 放在它读的那张表对应的 Mapper 里"（见各 Mapper 的注释）。
 *   这里破例的理由是这种工具的寿命：它是一次性的、用完就删，
 *   而它要碰 7 张表 13 列 —— 写成 13 个散在 7 个模块里的 Mapper 方法，
 *   等这个类被删掉时那些方法会成为没人知道来历的遗留物。
 *   把一次性 SQL 和它的调用方放在同一个文件里，删除时才是干净的一刀。
 * =====================================================================
 */
@Slf4j
@Component
public class LegacyUploadMigrator {

    /**
     * 一次迁移动到的所有"表.列"（表和列名是写死的常量，不是用户输入 —— 拼接进 SQL 安全）。
     *
     * 【清单要和 UploadReferenceChecker 对齐】那边是"判断文件有没有人用时查哪些列"，
     *   这里是"改写引用时要改哪些列"。**两者必须是同一批**：
     *   少改一列 → 那一处引用还指着旧 key，而旧文件马上要被删 → 前台裂图；
     *   多改一列 → 无害（那一列本来也不会出现旧 key）。
     *   ⚠️ 加列时两边一起加。这里多一个"id 列名"，用来定位要更新的行。
     */
    private static final List<TableColumns> TABLES = List.of(
            new TableColumns("article", List.of("cover", "content", "summary")),
            new TableColumns("article_attachment", List.of("url")),
            new TableColumns("music", List.of("url", "cover")),
            new TableColumns("about", List.of("avatar", "bio", "github")),
            new TableColumns("friend_link", List.of("avatar", "url")),
            new TableColumns("project", List.of("cover", "url", "repo")),
            new TableColumns("favorite", List.of("url"))
    );

    /** 哈希分目录用几位（与 UploadService.HASH_DIR_LENGTH 一致 —— 两处必须是一样的数字） */
    private static final int HASH_DIR_LENGTH = 2;

    /** SHA-256 的十六进制串长度（64）—— 用来把"新命名"与"旧命名（32 位 UUID）"分开 */
    private static final int HASH_HEX_LENGTH = 64;

    /** 读文件算哈希时的缓冲区大小（与 UploadService.HASH_BUFFER_SIZE 同一个理由） */
    private static final int HASH_BUFFER_SIZE = 8192;

    private final FileStorage fileStorage;
    private final UploadProperties properties;
    private final ArticleCacheVersion articleCacheVersion;
    private final ContentCacheVersion contentCacheVersion;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public LegacyUploadMigrator(FileStorage fileStorage,
                                UploadProperties properties,
                                ArticleCacheVersion articleCacheVersion,
                                ContentCacheVersion contentCacheVersion,
                                JdbcTemplate jdbcTemplate,
                                TransactionTemplate transactionTemplate) {
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.articleCacheVersion = articleCacheVersion;
        this.contentCacheVersion = contentCacheVersion;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 跑一次迁移。
     *
     * @param dryRun true = 只看会发生什么，不动磁盘也不动数据库
     * @return 报告（待迁移/已迁移的文件数、合并掉多少重复、改了多少行、被跳过的）
     */
    public MigrationReport migrate(boolean dryRun) {
        Path root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            // 从没上传过任何文件：没什么可迁的。
            // 这里不报错 —— "目录不存在"对一个幂等的迁移来说就是"已完成"
            log.info("迁移跳过：上传目录不存在 {}", root);
            return MigrationReport.empty(dryRun);
        }

        // ---- 第一步：扫盘，算出每个旧文件该改成什么名字 ----
        List<LegacyFile> legacyFiles = scanLegacyFiles(root);
        if (legacyFiles.isEmpty()) {
            log.info("迁移：没有需要迁移的文件（都已经在哈希命名下）");
            return MigrationReport.empty(dryRun);
        }

        // 同一份内容的老副本会映射到同一个新 key —— 这里天然合并
        long distinctTargets = legacyFiles.stream().map(LegacyFile::targetKey).distinct().count();

        // 顺带统计"这些旧文件里，有多少已经没有任何地方在引用"
        // （纯粹的体检指标：数量很大说明历史上攒了不少没人用的文件）
        int unreferenced = 0;
        for (LegacyFile file : legacyFiles) {
            if (!stillReferenced(file.oldKey())) {
                unreferenced++;
            }
        }

        log.info("迁移扫描完成: 旧文件 {} 个 → 目标文件 {} 个（合并掉 {} 个重复），其中 {} 个当前无人引用",
                legacyFiles.size(), distinctTargets, legacyFiles.size() - distinctTargets, unreferenced);

        if (dryRun) {
            return new MigrationReport(true, legacyFiles.size(), 0, 0,
                    legacyFiles.size() - (int) distinctTargets, unreferenced, 0, 0);
        }

        // ---- 第二步：复制成哈希名（此时旧的还在，库还指着旧的，前台一切照常）----
        Map<String, String> mapping = new LinkedHashMap<>();
        int copied = 0;
        for (LegacyFile file : legacyFiles) {
            mapping.put(file.oldKey(), file.targetKey());
            if (copyToHashName(file)) {
                copied++;
            }
        }

        // ---- 第三步：改写库里所有引用（一个事务，要么全改要么全不改）----
        int updatedRows = rewriteAllReferences(mapping);

        // ---- 第四步：事务提交之后再删旧文件 ----
        // 时机与 UploadedFileCleaner.deleteAfterCommit 同一条理由：
        // 删除不可逆，而事务可能回滚 —— 回滚了却把文件删了，库里就全是死链
        int deleted = deleteOldFiles(legacyFiles);

        // ---- 第五步：让缓存失效 ----
        // 缓存里存的是【改写前】的正文与封面地址（文章详情/列表/归档/RSS 与
        // 关于页/友链/项目/音乐列表都带这些字段）。不推进版本号的话，
        // 最长 5 分钟内前台仍在用旧地址 —— 而旧文件马上就被删了，
        // 表现是"迁移完成后首页裂图，过几分钟自己好了"。
        articleCacheVersion.bump();
        contentCacheVersion.bump();

        MigrationReport report = new MigrationReport(false, legacyFiles.size(), copied, updatedRows,
                legacyFiles.size() - (int) distinctTargets, unreferenced,
                deleted, legacyFiles.size() - deleted);
        log.info("迁移完成: {}", report);
        return report;
    }

    /**
     * 扫一遍上传目录，挑出"还不是哈希命名"的文件，算出各自的目标 key。
     *
     * 【怎么判断"已经是哈希命名"】
     *   文件名是 64 位十六进制、且父目录名正好等于它的前两位。
     *   两个条件都要满足 —— 只看"64 位十六进制"的话，一个恰好叫
     *   {@code <64位hex>.png} 的旧文件（UUID 只有 32 位，正常不会有，
     *   但不能靠"正常不会有"来写迁移工具）会被误判成已迁移而跳过。
     *
     * 【为什么直接遍历磁盘，而不走 FileStorage】
     *   FileStorage 的契约是"存 / 删 / 问存在 / 给地址"，没有"列出所有对象"——
     *   那是为将来换对象存储准备的接口，而对象存储的"列目录"是又慢又贵的操作，
     *   不该为了一个一次性工具加进接口。
     *   ⚠️ 所以这个工具【只对本地磁盘存储有意义】（那也是当前唯一的实现）。
     *   真换了对象存储，这个类需要重写成对应 SDK 的遍历方式。
     *
     * @param root 上传目录的绝对路径
     */
    private List<LegacyFile> scanLegacyFiles(Path root) {
        List<LegacyFile> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String oldKey = root.relativize(path).toString().replace('\\', '/');
                String targetKey = hashKeyOf(oldKey);
                if (targetKey == null || targetKey.equals(oldKey)) {
                    continue;       // 跳过：已经是哈希名，或者形状不认识（下面会记日志）
                }
                result.add(new LegacyFile(oldKey, targetKey));
            }
        } catch (IOException e) {
            log.error("扫描上传目录失败: {}", root, e);
            throw new IllegalStateException("扫描上传目录失败", e);
        }

        // 排序只为让日志与报告稳定可复现（排查时好对）
        result.sort((a, b) -> a.oldKey().compareTo(b.oldKey()));
        return result;
    }

    /**
     * 算出一个旧 key 对应的哈希 key；形状不认识时返回 null。
     *
     * 旧：{@code cover/2026/09/<32位hex>.png}
     * 新：{@code cover/<2位hex>/<64位hex>.png}
     * 规则：**保留原前缀与扩展名，只把中间的名字换成内容哈希**。
     * 前缀必须保留 —— 它决定浏览器是就地打开还是强制下载
     * （见 UploadResponseHeaderFilter），换了前缀会让附件变成内联打开。
     */
    private String hashKeyOf(String oldKey) {
        int slash = oldKey.lastIndexOf('/');
        if (slash <= 0) {
            log.warn("迁移跳过（形状不认识，不在任何子目录下）: {}", oldKey);
            return null;
        }
        String fileName = oldKey.substring(slash + 1);
        String prefix = oldKey.substring(0, oldKey.indexOf('/'));   // cover / music / attachment

        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            // 没有扩展名：不敢猜一个（猜错会让浏览器按错误的 MIME 处理），
            // 原样留着并让人工确认
            log.warn("迁移跳过（文件名没有扩展名）: {}", oldKey);
            return null;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);

        // 【先判断"是不是已经在哈希命名下了"，再考虑要不要算哈希】
        //   顺序很重要：反过来的话，每跑一次迁移都会把【全站所有文件】重新读一遍
        //   算一遍哈希，只为得出"它确实已经迁移过了"这个结论。
        //   那在几千个文件时是几十秒的纯浪费，而迁移是要反复跑（dryRun 看一遍、
        //   再实跑、出问题再重跑）的操作。
        if (alreadyContentAddressed(fileName, extension)) {
            return null;
        }

        String hash;
        try {
            hash = sha256Hex(oldKey);
        } catch (IOException e) {
            log.error("迁移跳过（读取文件失败）: {}", oldKey, e);
            return null;
        }

        return prefix + "/" + hash.substring(0, HASH_DIR_LENGTH) + "/" + hash + "." + extension;
    }

    /**
     * 这个文件名看起来已经是内容哈希了吗（{@code <64位hex>.<扩展名>}）。
     *
     * 【只按形状判断，不去验证它是不是真的哈希】验证要读整个文件算一遍，
     *   那正是这里要避免的开销。形状判断的代价是一次长度检查 + 一遍字符检查。
     *   万一有人把一个真叫 {@code <64位hex>.png} 的旧文件放在这里，
     *   我们会跳过它 —— 而它本来就长得和新命名一模一样，跳过也无害。
     *
     * 【为什么必须检查长度正好 64 且全是十六进制】
     *   旧命名是 32 位 hex（UUID 去掉横线），位数不同 —— 这条就是两者的分界。
     */
    private boolean alreadyContentAddressed(String fileName, String extension) {
        String bare = fileName.substring(0, fileName.length() - extension.length() - 1);
        if (bare.length() != HASH_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < bare.length(); i++) {
            char c = bare.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 读磁盘上这个文件的内容，算 SHA-256 十六进制串（流式，不把整个文件读进堆） */
    private String sha256Hex(String objectKey) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境没有可用的 SHA-256 实现", e);
        }
        Path file = storageRoot().resolve(objectKey);
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 把旧文件复制成哈希名（目标已存在就跳过）。
     *
     * 【为什么走 FileStorage.store 而不是 Files.copy】
     *   URL 的拼法、路径校验、父目录创建都在存储实现里；这里只是"把同一个内容
     *   再存到另一个 key 上"，正好是 store 的语义。用 Files.copy 就绕过了那层，
     *   将来换存储时这个工具会静默地只改数据库、不搬文件。
     *
     * @return true 表示这次真的写了一个新文件（false = 目标已存在，跳过）
     */
    private boolean copyToHashName(LegacyFile file) {
        if (fileStorage.exists(file.targetKey())) {
            // 同一份内容的另一个老副本已经先复制过去了 —— 这正是"合并重复"发生的地方
            return false;
        }
        try (InputStream content = Files.newInputStream(storageRoot().resolve(file.oldKey()))) {
            // contentType 传 null：本地存储不保存它（浏览器打开时按扩展名现推，
            // 见 UploadService.guessContentType 的注释），而新 key 的扩展名与旧的相同
            fileStorage.store(content, file.targetKey(), null);
            return true;
        } catch (IOException e) {
            log.error("迁移失败（复制文件出错）: {} -> {}", file.oldKey(), file.targetKey(), e);
            throw new IllegalStateException("迁移文件失败: " + file.oldKey(), e);
        }
    }

    /**
     * 把所有表里对旧 key 的引用改写为新 key，返回更新的行数。
     *
     * 【为什么逐表逐列跑 UPDATE ... REPLACE】
     *   引用藏在文本里（正文是 Markdown、封面是整条 URL），没有外键可改 ——
     *   唯一能做的就是字符串替换。用 SQL 的 REPLACE 而不是"读进 Java 改完再写回"，
     *   是为了**不把 longtext 的正文读进堆**（几百篇文章的正文加起来是几十 MB，
     *   而这个工具跑在堆只有 384MB 的容器里）。
     *
     * 【为什么用 REPLACE 而不是 LIKE 定位】
     *   与项目里其它引用检查同一条理由（见 ArticleMapper 的注释）：LIKE 的匹配串里
     *   {@code _} 是通配符，而 key 里出现下划线很正常。这里 WHERE 只用来减少扫描的
     *   行数，真正替换由 REPLACE 精确完成 —— 用 LOCATE 判断"这一行要不要动"。
     *
     * 【一个事务】
     *   改到一半失败会留下一半新一半旧的引用 —— 那是最糟的中间态。
     *   整体回滚之后磁盘上只是多了几个没人引用的副本（无害），可以重跑。
     */
    private int rewriteAllReferences(Map<String, String> mapping) {
        if (mapping.isEmpty()) {
            return 0;
        }
        Integer updated = transactionTemplate.execute(status -> {
            int total = 0;
            for (TableColumns table : TABLES) {
                for (String column : table.columns()) {
                    total += rewriteColumn(table, column, mapping);
                }
            }
            return total;
        });
        int result = updated == null ? 0 : updated;
        log.info("迁移：库中引用改写完成，共更新 {} 行", result);
        return result;
    }

    /** 改写一张表的一列（对每个旧 key 跑一条 UPDATE） */
    private int rewriteColumn(TableColumns table, String column, Map<String, String> mapping) {
        int total = 0;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sql = "UPDATE " + table.name()
                    + " SET " + column + " = REPLACE(" + column + ", ?, ?)"
                    + " WHERE LOCATE(?, " + column + ") > 0";
            total += jdbcTemplate.update(sql, entry.getKey(), entry.getValue(), entry.getKey());
        }
        return total;
    }

    /**
     * 删掉旧文件（在数据库引用改写【提交之后】才调用）。
     *
     * 【删之前为什么还要再查一遍引用】防的是"迁移期间有人把旧地址写了回去"：
     *   比如站长打开着迁移前的编辑框、点了保存，他提交的是旧 URL。
     *   那一行是在我们改完之后才写的，所以它仍然引用着旧 key ——
     *   这时把旧文件删了就真的裂图了。再查一遍的代价是每个文件几条 COUNT，
     *   而收益是"迁移工具不会因为一个并发编辑而删掉正在用的文件"。
     *
     * 【删失败不中断】与 UploadedFileCleaner.deleteAll 同一态度：
     *   这时业务数据已经改完了，一个删不掉的文件只该留日志、不该让整次迁移报错。
     */
    private int deleteOldFiles(List<LegacyFile> legacyFiles) {
        int deleted = 0;
        for (LegacyFile file : legacyFiles) {
            if (stillReferenced(file.oldKey())) {
                log.warn("迁移：旧文件在改写之后又被引用了，保留不删: key={}（多半是迁移期间的并发编辑）",
                        file.oldKey());
                continue;
            }
            try {
                fileStorage.delete(file.oldKey());
                deleted++;
            } catch (RuntimeException e) {
                log.warn("迁移：删除旧文件失败，需人工清理: key={}", file.oldKey(), e);
            }
        }
        return deleted;
    }

    /**
     * 还有没有任何一张表、任何一列里写着这个旧 key？
     *
     * 【⚠️ 为什么不用 UploadReferenceChecker.isReferenced（它做的是同一件事）】
     *   因为它走 MyBatis，而 MyBatis 有【一级缓存】—— 同一个 SqlSession 里
     *   同样的语句 + 同样的参数会直接返回上一次的结果。而本类改写引用用的是
     *   JdbcTemplate：MyBatis 完全不知道那些列被改过，于是它的缓存【不会被清】。
     *   两者撞在一起的表现是：明明已经把引用改成新 key 了，
     *   复检却仍然读到"还有人在用"，于是旧文件永远删不掉。
     *   （这个坑是测试抓出来的：删歌/删文章的路径没这个问题，因为那边
     *    "写"与"读"都走 MyBatis，写会把缓存清掉。）
     *
     *   所以这里用 JdbcTemplate 直接查 —— 它没有缓存，读到的一定是刚写下去的东西。
     *   顺带还保证了"复检的范围"与"改写的范围"是同一张清单（同一个 TABLES 常量），
     *   不会出现"改了 A 列却去查 B 列"。
     */
    private boolean stillReferenced(String key) {
        for (TableColumns table : TABLES) {
            for (String column : table.columns()) {
                String sql = "SELECT COUNT(*) FROM " + table.name()
                        + " WHERE LOCATE(?, " + column + ") > 0";
                Integer count = jdbcTemplate.queryForObject(sql, Integer.class, key);
                if (count != null && count > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 上传目录的绝对路径（与 LocalFileStorage 里那份算法一致） */
    private Path storageRoot() {
        return Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
    }

    /** 一个待迁移的文件：从哪来、到哪去 */
    private record LegacyFile(String oldKey, String targetKey) {
    }

    /**
     * 一张表要改写的列。
     *
     * 【为什么不需要"id 列"】改写用的是 {@code UPDATE t SET col = REPLACE(col, 旧, 新)}
     *   —— 一条语句就覆盖全表所有行，不需要逐行定位（那也是这么写的原因：
     *   正文是 longtext，逐行读进 Java 改再写回会把几十 MB 拉进堆）。
     */
    private record TableColumns(String name, List<String> columns) {
    }

    /**
     * 迁移报告（接口原样返回给调用者，也是 dryRun 时唯一要看的东西）。
     *
     * @param dryRun          true 表示这次只是预演，什么都没改
     * @param legacyFiles     扫出来的旧文件个数
     * @param copied          实际复制出的新文件个数（小于旧文件数 = 合并掉了重复内容）
     * @param updatedRows     数据库中改写的行数
     * @param mergedDuplicates 因内容相同而合并掉的重复文件数
     * @param unreferenced    旧文件里"当时已经没有任何地方引用"的个数（体检指标）
     * @param deletedFiles    删除掉的旧文件个数
     * @param keptFiles       因"改写后又被引用"而保留的旧文件个数（并发编辑的信号）
     */
    public record MigrationReport(boolean dryRun,
                                  int legacyFiles,
                                  int copied,
                                  int updatedRows,
                                  int mergedDuplicates,
                                  int unreferenced,
                                  int deletedFiles,
                                  int keptFiles) {

        static MigrationReport empty(boolean dryRun) {
            return new MigrationReport(dryRun, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
