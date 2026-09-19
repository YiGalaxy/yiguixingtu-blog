package com.yigalaxy.yiguixingtu.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * 后台「存储管理」的业务：把磁盘上的文件与"谁在用它"合到一起，以及删掉没人用的文件
 *
 * 【它和 FileStorage / UploadReferenceIndex / UploadReferenceChecker 的分工】
 *   · {@link FileStorage}          —— 磁盘上有什么（清单、大小、URL、删除）
 *   · {@link UploadReferenceIndex} —— 全站哪些地方引用了哪个文件（展示用）
 *   · {@link UploadReferenceChecker} —— **能不能删**的唯一判据（删除时用）
 *   这个类只负责把三者拼成页面要的形状，自己不碰磁盘也不写 SQL。
 *
 * 【为什么删除的判据不用上面那个索引】
 *   两个理由，缺一不可：
 *     ① 索引是**展示**用的：它的口径是"哪些地方看起来在用这个文件"，
 *        而判据的口径必须是"删掉它会不会让某个页面坏掉"。两者现在恰好一致，
 *        但一致是**要维护**的（加表加列时两处都要动），不能把"删除"压在
 *        一个"展示用"的东西上。
 *     ② 更要紧的是**时机**：删除请求带着的是一份可能已经过期的清单
 *        （页面打开到现在，站长可能刚在另一个标签页里把这张图设成了某篇文章的封面）。
 *        所以删除前必须**当场重算**一遍，用的是和删文章 / 删歌同一套 SQL。
 * =====================================================================
 */
@Slf4j
@Service
public class UploadStorageService {

    private final FileStorage fileStorage;
    private final UploadProperties properties;
    private final UploadReferenceIndex referenceIndex;
    private final UploadReferenceChecker referenceChecker;

    public UploadStorageService(FileStorage fileStorage,
                                UploadProperties properties,
                                UploadReferenceIndex referenceIndex,
                                UploadReferenceChecker referenceChecker) {
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.referenceIndex = referenceIndex;
        this.referenceChecker = referenceChecker;
    }

    /**
     * 存储管理页的整页数据（见 {@link UploadStorageVO}）。
     *
     * 【顺序】先建索引再列文件，这样列文件时顺手就能把引用挂上去，不用二次查找。
     */
    public UploadStorageVO overview() {
        Map<String, List<UploadReference>> index = referenceIndex.build();

        List<UploadFileVO> files = new ArrayList<>();
        int orphanCount = 0;
        for (StoredObject object : fileStorage.listAll()) {
            // 索引里没有 = 全站没有任何地方引用它（删除接口会再确认一遍）
            List<UploadReference> references = index.getOrDefault(object.objectKey(), List.of());
            if (references.isEmpty()) {
                orphanCount++;
            }
            files.add(new UploadFileVO(
                    object.objectKey(),
                    fileStorage.urlOf(object.objectKey()),
                    fileNameOf(object.objectKey()),
                    categoryOf(object.objectKey()),
                    object.size(),
                    // Instant -> LocalDateTime：与接口里其它时间字段同形
                    // （前端统一用 formatDateTime 渲染，见 app/utils/time.ts）
                    LocalDateTime.ofInstant(object.lastModified(), ZoneId.systemDefault()),
                    references));
        }

        long used = fileStorage.usedBytes();
        long max = properties.getMaxTotalSize() == null ? 0L : properties.getMaxTotalSize().toBytes();
        log.info("存储管理: 文件 {} 个（其中无人引用 {} 个），已用 {} 字节",
                files.size(), orphanCount, used);
        return new UploadStorageVO(used, max, files.size(), orphanCount, files);
    }

    /**
     * 删除若干文件 —— **只删真的没人用的那些**。
     *
     * 【为什么"没人用"要在这里重新算一遍】
     *   前端传来的 key 列表是可信的输入（它只能来自管理员的勾选），
     *   但"这个文件没人用"这个**判断**不能信前端：
     *     · 页面打开到点删除之间，站长可能在另一个标签页里刚把它设成某篇文章的封面
     *     · 页面的引用清单本身可能是几分钟前建的
     *   删除是不可逆的，所以判据必须来自"此刻的数据库"，而不是"刚才的页面"。
     *   这也是为什么本方法用的是 {@link UploadReferenceChecker}（与删文章/删歌同一套），
     *   而不是页面用的那个展示索引。
     *
     * 【逐个删、不整体回滚】与 UploadedFileCleaner.deleteAll 同一条态度：
     *   十个文件里有一个删不掉，不该让另外九个也白删一遍（它们本来就是互不相干的记录）。
     *   删不掉的那个进 failed 列表、留日志，界面上照实说。
     *
     * @param keys 要删的 objectKey 列表（前端从清单里勾的）
     */
    public UploadDeleteReport deleteFiles(List<String> keys) {
        List<String> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        if (keys == null || keys.isEmpty()) {
            return new UploadDeleteReport(deleted, skipped, failed);
        }

        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            // ⚠️ 判据：与删文章、删歌、移除附件用的是同一个方法。
            //    它偏保守（宁可认为"还有人用"），代价只是少删一个文件
            if (referenceChecker.isReferenced(key)) {
                log.info("存储管理：文件仍被引用，跳过删除: key={}", key);
                skipped.add(key);
                continue;
            }
            try {
                fileStorage.delete(key);
                deleted.add(key);
                log.info("存储管理：已删除无人引用的文件: key={}", key);
            } catch (RuntimeException e) {
                log.warn("存储管理：删除文件失败，需人工清理: key={}", key, e);
                failed.add(key);
            }
        }

        log.info("存储管理删除完成: 删除 {} 个，跳过 {} 个（仍被引用），失败 {} 个",
                deleted.size(), skipped.size(), failed.size());
        return new UploadDeleteReport(deleted, skipped, failed);
    }

    /**
     * 从 objectKey 里取文件名（{@code cover/2f/2f8e….png} → {@code 2f8e….png}）。
     *
     * 【为什么要单独给前端一个 name】清单里那一列显示的是文件名，
     *   让前端自己 {@code key.split('/').pop()} 等于把 key 的形态知识复制到前端；
     *   而"key 长什么样"是存储实现的事（换存储时它可能压根不是路径）。
     */
    private String fileNameOf(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
    }

    /**
     * 从 objectKey 里取类型（第一段目录：cover / music / attachment）。
     *
     * 【为什么按目录取、而不是按扩展名猜】目录前缀才是这个项目里"这是什么类型"
     *   的权威表达 —— 它同时决定了浏览器是就地打开还是强制下载
     *   （见 UploadResponseHeaderFilter）。按扩展名猜的话，一个 mp3 放在
     *   attachment/ 下会被显示成"音频"，而它其实是被强制下载的附件。
     */
    private String categoryOf(String objectKey) {
        int slash = objectKey.indexOf('/');
        return slash > 0 ? objectKey.substring(0, slash) : "";
    }
}
