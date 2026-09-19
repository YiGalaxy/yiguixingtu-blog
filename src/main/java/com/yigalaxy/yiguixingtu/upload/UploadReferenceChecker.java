package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.about.mapper.AboutMapper;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleAttachmentMapper;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.favorite.mapper.FavoriteMapper;
import com.yigalaxy.yiguixingtu.link.mapper.FriendLinkMapper;
import com.yigalaxy.yiguixingtu.music.mapper.MusicMapper;
import com.yigalaxy.yiguixingtu.project.mapper.ProjectMapper;
import org.springframework.stereotype.Component;

/**
 * =====================================================================
 * 「删这个文件安全吗」的唯一判断入口 —— 查遍所有可能引用上传文件的表
 *
 * ============ 它解决什么问题 ============
 *
 * 【改造前后，"共用同一个文件"的常见程度完全不同】
 *   2026-09 之前，每次上传都生成一个随机 UUID 文件名，所以"两处引用同一个文件"
 *   基本只可能是**手工把地址复制过去**（站长把某张封面图的 URL 贴给另一篇文章）。
 *   那时只查三张表（article / article_attachment / music）就够 ——
 *   项目封面、友链 logo、关于页头像都是各自独立上传的副本，不可能撞上。
 *
 *   改成内容寻址之后（同一份内容只存一份，见 {@code UploadService.buildObjectKey}），
 *   **共用变成了默认行为**：站长把同一张图传给文章封面、又传给项目封面、
 *   又当友链 logo，磁盘上只有一份文件、三处引用它。
 *   这时"少查一张表"的后果不再是"多留一个垃圾文件"，而是
 *   **删掉别人正在用的图，页面当场裂开，而且不可逆**。
 *
 * 【所以这里把"哪些表算引用"收成一个清单】
 *   下面 7 张表、11 列。清单只在这一处维护 —— 以前是"每个调用点各写一遍"，
 *   两个调用点 × 三张表就已经容易改漏一处；两张表变七张之后，
 *   散着写的版本几乎必然会漏（而漏的表现是静默的误删）。
 *
 * 【⚠️ 新增一张会存上传地址的表时，必须来这里加一行】
 *   加的不只是这里 —— 那类 SQL 的归属原则是"放在它读的那张表对应的 Mapper 里"
 *   （见各个 Mapper 上 countReferencing 的注释）。两处都要动。
 *   **判断标准**：这一列会不会被当成图片 / 音频 / 下载链接渲染出来。
 *   会 → 算引用（删了它会裂图或变成死链）；只是一段给人读的文字 → 不算
 *   （用户说明文字、公告、评论正文里出现一个路径，删掉文件那些字还是那些字）。
 *   这条界限是有意划的，不是漏的：把纯文本列也算进来的话，
 *   一条提到某张图的评论就能让那个文件永远删不掉。
 *
 * ============ 用到的东西 ============
 *   · 七个 Mapper 各一个 countReferencing —— 每个都是
 *     {@code SELECT COUNT(*) ... WHERE LOCATE(#{key}, 列) > 0}。
 *     为什么用 LOCATE 不用 LIKE、为什么比对 key 而不是整条 URL（绝对地址与
 *     站内相对地址是同一个 key），写在 ArticleMapper 与 MusicMapper 的注释里。
 * =====================================================================
 */
@Component
public class UploadReferenceChecker {

    private final ArticleMapper articleMapper;
    private final ArticleAttachmentMapper articleAttachmentMapper;
    private final MusicMapper musicMapper;
    private final AboutMapper aboutMapper;
    private final FriendLinkMapper friendLinkMapper;
    private final ProjectMapper projectMapper;
    private final FavoriteMapper favoriteMapper;

    public UploadReferenceChecker(ArticleMapper articleMapper,
                                  ArticleAttachmentMapper articleAttachmentMapper,
                                  MusicMapper musicMapper,
                                  AboutMapper aboutMapper,
                                  FriendLinkMapper friendLinkMapper,
                                  ProjectMapper projectMapper,
                                  FavoriteMapper favoriteMapper) {
        this.articleMapper = articleMapper;
        this.articleAttachmentMapper = articleAttachmentMapper;
        this.musicMapper = musicMapper;
        this.aboutMapper = aboutMapper;
        this.friendLinkMapper = friendLinkMapper;
        this.projectMapper = projectMapper;
        this.favoriteMapper = favoriteMapper;
    }

    /**
     * 除了一篇文章自己之外，还有没有别的地方在用这个文件（删文章 / 移除附件时用）。
     *
     * 【为什么要"除掉一篇文章自己"】
     *   这个方法是在【准备删除这篇文章】时调的，而它的正文 / 封面 / 附件此刻
     *   仍然写着这个 key。不排除自己的话，每一次判断都会得到"还有人用"
     *   ——文件永远删不掉，而且不会有任何报错（只是磁盘一直涨）。
     *
     * 【⚠️ 调用它的时机与事务的关系】
     *   它读的是数据库【当前】的状态。所以：
     *     · 在 UPDATE 之后调用：本文的新内容已经在库里，但本文被整个排除掉了，
     *       结果不受影响
     *     · 在 UPDATE 之前调用：本文还是旧内容，同样被排除，结果也不受影响
     *   两条路都对 —— 因为排除的粒度是"整行"，不是"某个字段"。
     *   但**别的表**（关于页 / 友链 / 项目 / 收藏）必须已经是提交后的稳定状态，
     *   这一点由调用方保证（它们都在同一个请求里被改）。
     *
     * @param key       上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @param articleId 正在处理的文章 id（它自己不算"别人"）
     * @return true 表示还有别处引用它，**不能删**
     */
    public boolean isReferencedBesidesArticle(String key, Long articleId) {
        // 先查"很可能命中"的两张自己人的表：一次删除通常连着好几个 key，
        // 早一点命中就早一点返回，省掉后面五条没人用的查询
        if (articleMapper.countOthersReferencing(articleId, key) > 0) {
            return true;
        }
        if (articleAttachmentMapper.countOthersReferencing(articleId, key) > 0) {
            return true;
        }
        return isReferencedElsewhere(key);
    }

    /**
     * 除了一首曲目自己之外，还有没有别的地方在用这个文件（删歌时用）。
     *
     * 与上面那条是同一个形状，区别只在"要排除的是哪张表的哪一行"。
     * 之所以不做成一个带"我是谁"参数的通用方法：那样调用方要传
     * "我的表名 + 我的 id"，而表名是字符串，拼错了编译器不会拦。
     * 两个方法各自把要排除的行写死，读起来是"排除我这一首"。
     *
     * @param key     上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @param musicId 正在删除的曲目 id（它自己不算"别人"）
     * @return true 表示还有别处引用它，**不能删**
     */
    public boolean isReferencedBesidesMusic(String key, Long musicId) {
        if (musicMapper.countOthersReferencing(musicId, key) > 0) {
            return true;
        }
        // ⚠️【文章那两张表必须在这里显式查，它们不在 isReferencedElsewhere 里】
        //   漏掉它们的后果被测试抓过一次：删一首歌时，某篇文章的正文里嵌着这段
        //   {@code <audio src="/uploads/music/xxx.mp3">} —— 那是"还有人要用"的
        //   直接证据，而检查却会得出"没人用"的结论，然后把文件删掉：
        //   歌还在（在文章里），点开播不了，文件还回不来。
        //   （MusicFileCleanupTest 的"文章引用音频时不能删"那条用例正钉着这个方向。）
        if (articleMapper.countReferencing(key) > 0) {
            return true;
        }
        if (articleAttachmentMapper.countReferencing(key) > 0) {
            return true;
        }
        return isReferencedElsewhere(key);
    }

    /**
     * 有没有【任何】地方在用它（不排除任何行，含"当前这条记录自己"）。
     *
     * ============================================================
     * 【⚠️ 什么时候该用它、什么时候该用上面那两个"排除自己"的方法】
     *   判据只有一条：**调用这一刻，数据库里那行数据是【改动后】的还是【改动前】的。**
     *
     *   · 编辑文章（换了封面 / 从正文里删了图 / 移除了附件）时用它。
     *     因为那时候文章的 UPDATE 与附件行的整体替换都已经执行过了，
     *     库里就是"保存之后的样子" —— 本文自己那行如果还在引用这个 key，
     *     说明这篇新内容仍然需要它，本来就该留着。
     *     （2026-09 顺带修掉的一个老问题：编辑前这里排除掉了整篇文章，
     *      于是"附件地址同时写在正文里、用户把附件移除了"会误删正文还在用的文件。
     *      那时的检查看不到"本文新正文仍在引用"这件事。）
     *
     *   · 删文章时【必须】用带排除的那个方法。
     *     因为删文章时数据库那行还是【改动前】的（逻辑删除在检查之后才执行），
     *     它的正文/封面仍然写着这个 key —— 不排除自己的话，
     *     每一次判断都是"还有人用"，文件永远删不掉，而且不会有任何报错。
     *
     *   两者用反了的后果都是静默的：一边是误删（不可逆），
     *   一边是永远不删（磁盘只涨不落）。
     * ============================================================
     *
     * 【它还被谁用】一次性迁移工具（LegacyUploadMigrator）拿它出报告：
     *   "磁盘上这些文件，有多少已经没有任何页面在引用了"。
     *
     * @param key 上传目录里的对象 key
     * @return true 表示至少有一处引用
     */
    public boolean isReferenced(String key) {
        if (isReferencedElsewhere(key)) {
            return true;
        }
        // isReferencedElsewhere 里已经查了 music，这里补上 article 那两张表
        // （不带排除参数，也就是"连它自己算上"）
        return articleMapper.countReferencing(key) > 0
                || articleAttachmentMapper.countReferencing(key) > 0;
    }

    /**
     * 查那五张"没有'我自己'要排除"的表：曲目、关于页、友链、项目、收藏。
     *
     * 【这个方法存在的意义是把清单写在一处】上面两个"排除自己"的方法
     * 共用它 —— 将来加表时只改这里一处，两个调用点自动都覆盖到。
     *
     * 【顺序】没什么讲究，按"改动的可能性"排：音乐与关于页是站长最常动的。
     * 想加早退优化的话，把最可能命中的放前面即可，不影响正确性。
     */
    private boolean isReferencedElsewhere(String key) {
        if (musicMapper.countReferencing(key) > 0) {
            return true;
        }
        if (aboutMapper.countReferencing(key) > 0) {
            return true;
        }
        if (friendLinkMapper.countReferencing(key) > 0) {
            return true;
        }
        if (projectMapper.countReferencing(key) > 0) {
            return true;
        }
        return favoriteMapper.countReferencing(key) > 0;
    }
}
