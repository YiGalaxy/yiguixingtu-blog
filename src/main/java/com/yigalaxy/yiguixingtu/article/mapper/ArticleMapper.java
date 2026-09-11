package com.yigalaxy.yiguixingtu.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 文章 Mapper。
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 统计"已发布文章数"与"总浏览量"。
     *
     * 【为什么写裸 SQL，而不是用 MyBatis-Plus 的条件构造器】
     *   两个聚合值（COUNT 与 SUM）如果分两次查就是两次数据库往返，
     *   而它们扫的是同一批行 —— 合成一条 SQL 只扫一次。
     *   条件构造器擅长的是"动态拼条件"，这里条件固定，直接用 SQL 更清楚。
     *
     * 【为什么必须手写 deleted = 0】
     *   ⚠️ 这是本项目里最容易踩的一个点：`@TableLogic` 的逻辑删除条件是
     *   MyBatis-Plus 在【它自己生成的 SQL】里注入的。手写的 {@code @Select}
     *   不经过那套注入，所以必须显式写上 `deleted = 0` ——
     *   漏了的话，删掉的文章会被算进首页的数字里，而且不会有任何报错。
     *
     * 【为什么要 COALESCE】
     *   一篇已发布的文章都没有时，SUM(view_count) 返回的是 NULL 而不是 0。
     *   映射到 Java 的 Long 就是 null，前端拿到 null 得自己处理；
     *   在 SQL 里兜成 0，调用方永远拿到一个数字，少一个分支。
     *
     * 【为什么要限制 status = 1】
     *   草稿不该出现在对外的统计里（否则首页说 50 篇、游客只数得出 30 篇）。
     *
     * 【性能说明】
     *   COUNT 那条走的是 V3 加的覆盖索引 idx_deleted_status（不回表）；
     *   SUM(view_count) 没有索引可走，要扫全部已发布行 ——
     *   这正是这个接口【必须缓存】的原因，见 ArticleService#stats 的注释。
     *
     * @return 只填了 articleCount 与 viewCount 的统计对象
     */
    @Select("""
            SELECT COUNT(*)                    AS articleCount,
                   COALESCE(SUM(view_count), 0) AS viewCount
            FROM article
            WHERE deleted = 0 AND status = 1
            """)
    ArticleStatsVO selectPublishedAggregate();

    /**
     * 数一数"除了指定文章之外，还有几篇文章引用过这个上传文件"。
     *
     * 【它回答的问题】删文章（或编辑文章移除附件）时，要删的是"这篇文章
     *   独占的文件"。同一个文件完全可能被两篇文章共用（同一张封面图、
     *   同一张正文配图），无脑删会把另一篇文章的图删裂 ——
     *   而文件删除【不可逆】。所以删之前必须先问这一句。
     *   完整的级联逻辑与时机见 ArticleServiceImpl.remove 的中文注释。
     *
     * 【为什么把 cover 和 content 放在同一条 SQL 里】
     *   两者都是"这篇文章在用这个文件"的证据，分开查只是多一次数据库往返。
     *   cover 用 LOCATE 而不是等值比较：封面列里存的也是完整 URL，
     *   而我们要比对的是 key 片段（理由见下面那一段）。
     *
     * 【为什么用 LOCATE 而不是 LIKE】
     *   ① LIKE 的匹配串里 % 与 _ 是通配符，而 URL 里 {@code _} 并不罕见 ——
     *      用它匹配会让"下划线代表任意字符"，把别的文件也算成"被引用"，
     *      判断就不准确了（表现为文件永远清理不掉，且看不出原因）
     *   ② {@code LOCATE(子串, 字符串)} 是纯字符串查找，传什么找什么
     *   需求里说"LIKE 查询即可"，用 LOCATE 是同一思路的更严谨写法。
     *
     * 【为什么比对的是 key（cover/2026/09/xxx.png）而不是整条 URL】
     *   正文里的地址可能是绝对形式（http://host/uploads/...），
     *   也可能是相对形式（/uploads/...），取决于当时 app.upload.base-url 的配置。
     *   用整条 URL 比，一旦两种形式混用就会得出"没人引用"的结论 —— 然后误删。
     *   key 是两种形态共同包含的那一段（见 UploadedFileCleaner.toObjectKey）。
     *
     * 【⚠️ 必须手写 deleted = 0】
     *   与上面那条聚合 SQL 同一条理由（本类注释里已经写得很细）：
     *   @TableLogic 的过滤只注入到 MyBatis-Plus 自己生成的 SQL 里，
     *   手写 @Select 不经过那套注入。漏掉它的后果很具体：
     *   一篇文章被逻辑删除后，它的正文仍然留在库里，
     *   于是"已经删掉的文章"会被算成"还有人引用这张图" ——
     *   文件永远不会被清理，而且没有任何报错。
     *
     * 【性能说明】LOCATE 是函数匹配，走不了索引，这条 SQL 会扫一遍 article 表
     *   （和上面那条 SUM(view_count) 一样）。可接受的依据：它只在删文章时执行，
     *   执行次数 = 这篇文章涉及的文件数（封面 1 + 正文图片几张 + 附件几条），
     *   而个人博客的文章数是几百到几千 —— 见 README「为什么没有给 title 建索引」
     *   里同一条"前置通配符用不上索引"的说明。
     *
     * @param articleId 当前正在处理的文章 id（它自己不算"别人"）
     * @param key       上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 还有几篇文章在用这个文件（0 表示可以安全删除）
     */
    @Select("""
            SELECT COUNT(*)
            FROM article
            WHERE deleted = 0
              AND id <> #{articleId}
              AND (LOCATE(#{key}, cover) > 0 OR LOCATE(#{key}, content) > 0)
            """)
    long countOthersReferencing(@Param("articleId") Long articleId, @Param("key") String key);

    /**
     * 数一数"有几篇【未删除】的文章引用了这个上传文件"（没有"要撇开谁"这一说）。
     *
     * 【它和上面那条的唯一区别：没有 articleId 参数】
     *   {@code countOthersReferencing} 是"删文章"时用的 —— 调用方本身就是一篇文章，
     *   必须把自己排除掉（它马上就要没了，不能算成"还有人在用"）。
     *   而这条的调用方是音乐模块：删一首歌时要问"有没有文章的正文/封面引用了这个 mp3"
     *   （站长完全可能在正文里嵌一段
     *   {@code <audio src="/uploads/music/xxx.mp3">} —— 那是同源可达的）。
     *   那里没有"我自己这篇文章"要排除，所以不能复用上面那条
     *   （给它塞一个 -1 之类的假 id 是能跑，但那是把一个"没有要排除的人"的语义
     *    表达成魔法值，读代码的人永远不知道那个 -1 是什么意思）。
     *
     * 【⚠️ 同样必须手写 deleted = 0（见类注释里那条最容易踩的坑）】
     *   @TableLogic 只注入到 MyBatis-Plus 自己生成的 SQL 里。漏掉它的表现是：
     *   已经删掉的文章被算成"还有人在用"，于是文件永远清理不掉，而且没有任何报错。
     *
     * @param key 上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 有几篇未删除的文章在用这个文件（0 表示文章这边没人用）
     */
    @Select("""
            SELECT COUNT(*)
            FROM article
            WHERE deleted = 0
              AND (LOCATE(#{key}, cover) > 0 OR LOCATE(#{key}, content) > 0)
            """)
    long countReferencing(@Param("key") String key);
}
