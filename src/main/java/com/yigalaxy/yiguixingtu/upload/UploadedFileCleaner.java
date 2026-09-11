package com.yigalaxy.yiguixingtu.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * =====================================================================
 * 「已上传的文件」这一侧的工具：URL ↔ 对象 key 的互转、从正文里找出引用的文件、按 key 删除
 *
 * 【为什么需要单独一个类，而不是把这些逻辑写进 ArticleServiceImpl】
 *   文章模块要做的只是"删掉这篇文章独占的文件"，而"文件的地址长什么样、
 *   怎么从一段 Markdown 里认出本站的文件、怎么把它从磁盘上删掉"属于
 *   【上传模块的知识】：URL 是 UploadService 拼的（base-url + /uploads/ + key），
 *   规则当然也该由上传模块来解释。
 *   放在这里之后，ArticleServiceImpl 的级联逻辑读起来是业务语义
 *   （"这张图还有没有别人在用？没有就删掉"），而不是一堆字符串处理 ——
 *   将来 URL 规则变了（比如挂上 CDN），只需要改这一个类。
 *
 * 【与 FileStorage 的分工】
 *   · 这个类管"业务地址 ↔ 存储 key"的翻译，以及"从正文里找出有哪些文件"；
 *   · 真正的删除交给 {@link FileStorage}（它才知道文件在磁盘还是对象存储里）。
 *
 * 【⚠️ 这里的方法都是"看起来无害但其实很关键"的】
 *   本类的正则与字符串判断直接决定"删除时会不会误删别人的图"：
 *   少认出一次引用关系，就可能把一个还在被别的文章使用的文件删掉 ——
 *   而删除是不可逆的。所以这两处的规则都写得很保守
 *   （宁可多认、宁可少删），具体理由见各自的方法注释。
 * =====================================================================
 */
@Slf4j
@Component
public class UploadedFileCleaner {

    /**
     * 从正文里找出"本站上传目录里的文件"的正则。
     *
     * 【匹配什么】形如 {@code /uploads/cover/2026/09/3f2b....png} 的一整段。
     *   只要求以 {@code /uploads/} 开头，所以绝对地址
     *   （{@code http://host/uploads/...}）与相对地址（{@code /uploads/...}）
     *   都能被认出来 —— 这是关键：正文里存的是哪种形式取决于当时
     *   {@code app.upload.base-url} 的配置，而那是有可能被改的。
     *
     * 【字符集为什么是这几种】key 里可能出现的是
     *   字母、数字、{@code . _ - /}（UUID + 年月目录 + 扩展名）。
     *   ⚠️ 刻意【不含空格、引号、括号、逗号】：Markdown 里图片写成
     *   {@code ![说明](地址)}、HTML 里写成 {@code <img src="地址">}，
     *   匹配到结构字符才会正确地停在地址末尾。
     *   （我们自己生成的 key 里也不会有这些字符，所以不会漏掉真正的文件。）
     *
     * 【为什么【不】限制成只有图片扩展名】
     *   需求里说的是"正文里引用的图片"，但正文里完全可能出现一行
     *   Markdown 链接指向一个附件（{@code [附录下载](/uploads/attachment/x.pdf)}）。
     *   那同样是"这篇文章在用这个文件"。把范围放宽到"上传目录里的任何文件"，
     *   只会让判断更保守（多认一个引用 = 少删一个文件），
     *   而漏认的后果是不可逆的误删 —— 两个方向的代价不对称，所以偏保守。
     */
    private static final Pattern UPLOADED_FILE_IN_CONTENT =
            Pattern.compile("/uploads/[A-Za-z0-9._/-]+");

    private final FileStorage fileStorage;
    private final UploadProperties properties;

    public UploadedFileCleaner(FileStorage fileStorage, UploadProperties properties) {
        this.fileStorage = fileStorage;
        this.properties = properties;
    }

    /**
     * 把一个对外 URL 翻译成存储里的对象 key（形如 {@code cover/2026/09/xxx.png}）。
     *
     * 【什么时候返回 null（= "这不是本项目上传的文件，别碰它"）】
     *   · 空/null
     *   · 不含 {@code /uploads/}：说明它不是上传目录里的东西。
     *     正文里的图片完全可能是外链（别人的图床、CDN），
     *     那种地址对应的文件【不在我们的磁盘上】，当然也不能去"删"
     *   · 只允许两种形态：以配置的 base-url 开头（本项目生成的绝对地址），
     *     或者以 {@code /uploads/} 开头（站内相对地址）。其它形态
     *     （比如 {@code https://evil.example/uploads/x.png}）一律拒绝 ——
     *     不然一个"看起来像本站地址"的外链就能让我们去删本地文件
     *     （虽然 key 拼不出来也删不掉什么，但"未经确认就不动手"才是这里的正确态度）
     *   · key 里含 {@code ..}：路径穿越，直接拒绝
     *     （LocalFileStorage 还有一道同样的检查，两层都要有）
     *
     * 【为什么用 indexOf 而不是 startsWith(baseUrl) 然后 substring】
     *   因为正文里的地址可能是绝对形式也可能是相对形式，
     *   而两种形式里 {@code /uploads/} 之后的那一段是【同一个字符串】——
     *   先找它、再取后半段，两种形态就归一处理了。
     *   这也正是下面"查引用"时用 key（而不是整条 URL）去比对的原因：
     *   同一张图在 A 文里存的是绝对地址、在 B 文里存的是相对地址，
     *   用整条 URL 比会得出"没人引用"的结论 —— 然后误删。
     */
    public String toObjectKey(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        int index = url.indexOf("/uploads/");
        if (index < 0) {
            return null;
        }

        boolean ours = url.startsWith("/uploads/")
                || url.startsWith(trimTrailingSlash(properties.getBaseUrl()) + "/uploads/");
        if (!ours) {
            return null;
        }

        String key = url.substring(index + "/uploads/".length());
        if (key.isEmpty() || key.contains("..")) {
            return null;
        }
        return key;
    }

    /**
     * 从一段 Markdown 正文里找出所有"本站上传文件"的对象 key。
     *
     * 【为什么要去正文里找】正文（article.content）是一段 Markdown 源码，
     *   里面插的图片就是"这篇文章在用这个文件"的全部证据。
     *   文章被删除时，如果只删封面图，正文里的图片就永远留在磁盘上
     *   （前台已经没有任何页面引用它们了），uploads 目录只会越来越大。
     *
     * 【返回值为什么用 LinkedHashSet】
     *   同一张图在正文里出现多次（或者既是封面又在正文里）是常态，
     *   去重可以避免同一个文件被检查/删除多次。
     *   用 Linked 版本是为了让"删除的顺序"稳定可复现（排查日志时好对）。
     */
    public Set<String> extractObjectKeys(String content) {
        Set<String> keys = new LinkedHashSet<>();
        if (!StringUtils.hasText(content)) {
            return keys;
        }
        Matcher matcher = UPLOADED_FILE_IN_CONTENT.matcher(content);
        while (matcher.find()) {
            // 匹配到的是 "/uploads/xxx"，统一走 toObjectKey 做一次同样的"是不是我们的"判断，
            // 保证"提取"与"翻译"两处规则完全一致（两套规则迟早会不一致，
            // 而这里的不一致表现是"提取到了却删不掉"或"提取漏了导致垃圾文件"）
            String key = toObjectKey(matcher.group());
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 删除若干对象。
     *
     * 【为什么一个个删、并且【吞掉】异常】
     *   这个方法的调用点（文章删除 / 附件替换 / 歌曲删除）都发生在业务已经确定成功之后
     *   （具体时机见 deleteAfterCommit 的注释）。那时候抛异常没有任何人能补救：
     *   事务已经提交、接口已经返回。
     *   让一个"清理旧文件"的失败把整次删除操作变成 500，是明显的本末倒置 ——
     *   用户会以为没删掉，重试一次，然后收到"不存在"。
     *   所以这里的原则是：**尽力删，删不掉就留下清楚的日志**，
     *   日志里带上 key，运维可以照着手工清理（文件是 UUID 命名，不会有歧义）。
     */
    public void deleteAll(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        for (String objectKey : objectKeys) {
            try {
                fileStorage.delete(objectKey);
            } catch (RuntimeException e) {
                log.warn("删除已上传文件失败（保留日志，需人工清理）: objectKey={}", objectKey, e);
            }
        }
    }

    /**
     * 在【当前事务提交之后】删除这些文件；当前没有事务时立刻删。
     *
     * 【这个方法为什么存在（而不是各处自己调 deleteAll）】
     *   "什么时候才允许动磁盘"是一条规则，不是某处的实现细节：删文章要删文件、
     *   编辑文章移除附件要删文件、删歌曲要删文件 —— 三处的时机判断完全一样，
     *   而判断错的方向是【不可逆的】（文件删了就回不来）。
     *   把它收在这一个方法里，三处调用点读起来都是"把这些文件清理掉"，
     *   而"为什么必须等提交"只需要在一个地方读懂。
     *
     * 【为什么必须等事务提交】删除文件不可逆，而数据库事务可能回滚：
     *   如果删文件写在事务里面，后面任何一步失败（换字段出错、清理关联出错）
     *   都会让数据库"退回去"、文件却已经没了 —— 页面照常打开但资源全是 404，
     *   而且再也找不回来。反过来，先让事务提交成功、再删文件，
     *   最坏的结果只是"提交成功但文件没删掉"（留个垃圾文件，日志里有记录），
     *   两害相权取轻。
     *
     * 【为什么用 TransactionSynchronizationManager 而不是 @TransactionalEventListener】
     *   项目里的操作审计用的是"发事件 + AFTER_COMMIT 监听器"（见 audit 包），
     *   那套更解耦，但它有两个这里不需要的属性：
     *     ① 它是异步的（@Async）：审计晚几毫秒没关系，而文件删除要的是
     *        "确定发生过"，同步执行才好断言、出错也好记日志
     *     ② 事件的接收方是按类型广播的：这里只是"提交后干一件事"，
     *        没必要为此定义事件类型 + 监听器两个类
     *   TransactionSynchronization 是同一机制的更轻形式（同样在提交后回调），
     *   代码就在调用点旁边，读起来是连贯的。
     *
     * 【为什么有"没有事务就立刻删"这个分支】
     *   调用它的三个 Service 方法都标了 @Transactional，正常不会走到它。
     *   但"方法被非事务地调用"是完全可能的（将来有人去掉注解、
     *   或者有别的入口直接调 Service）。没有这个分支的话，那种情况下文件
     *   就永远不会被删，而且是【静默】的 —— 判断标准很简单：
     *   有事务同步就注册（事务语义优先），没有就当场做（总比什么都不做强）。
     *
     * @param objectKeys 要删除的对象 key（可为空/为 null，方法会自己兜住）
     */
    public void deleteAfterCommit(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        // 复制一份：回调是在事务提交那一刻执行的，那时入参集合可能已经被复用或清空。
        // 这个集合是调用方的局部变量、不会跨请求共享，但"传给延迟执行的代码前先复制"
        // 是一条值得坚持的习惯（IdempotencyService 里也是同样的处理）
        List<String> snapshot = List.copyOf(objectKeys);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteAll(snapshot);
                }
            });
        } else {
            log.info("当前没有活动事务，立即删除文件（本批 {} 个）", snapshot.size());
            deleteAll(snapshot);
        }
    }

    /** 去掉地址结尾多余的斜杠，避免拼出 "http://host//uploads/..." 这种双斜杠（与 LocalFileStorage 同一处理） */
    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
