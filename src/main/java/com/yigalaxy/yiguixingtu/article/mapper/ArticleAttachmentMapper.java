package com.yigalaxy.yiguixingtu.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.article.entity.ArticleAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 文章附件 Mapper。
 *
 * 【除了 BaseMapper 给的增删改查，这里只有一条手写 SQL】
 *   它回答"这个文件还有没有【别的文章的附件】在用"。
 *   文章删除（或编辑文章移除附件）时要先问清这一点才敢去删物理文件 ——
 *   完整推导见 ArticleServiceImpl.remove 的注释。
 *
 * 【⚠️ 为什么"有没有别人在引用"要分成两条 SQL、放在两个 Mapper 里】
 *   引用关系散在两处：① 文章的正文与封面（在 article 表里）、
 *   ② 文章的附件（在 article_attachment 表里）。
 *   两条各自放在"管着那张表"的 Mapper 里（article 表那条在 ArticleMapper，
 *   与 CategoryMapper 里那条手写 COUNT 是同一个归属原则），
 *   由 Service 把两个计数合起来判断 —— 这样每个 Mapper 只懂自己的表，
 *   不会出现"附件 Mapper 里塞着一条查 article 表的 SQL"这种越界。
 */
@Mapper
public interface ArticleAttachmentMapper extends BaseMapper<ArticleAttachment> {

    /**
     * 数一数"除了指定文章之外，还有几篇文章的【附件】引用了这个文件"。
     *
     * 【为什么必须查它（而不是假设"附件行一定是独占的"）】
     *   附件的 URL 是上传时生成的（UUID 命名），正常情况下不会有两个附件行
     *   指向同一个文件。但【"正常情况"不是保证】：保存文章时我们只校验
     *   url 落在本站上传前缀之内（防外链），并没有禁止两篇文章提交同一个 URL
     *   （前端把上一篇的附件列表复制到这一篇，或用户手工改 URL，都能做到）。
     *   既然这条路径存在，删除时就必须按"可能被共享"处理 ——
     *   猜错的代价是删掉别人文章里的附件，而这个文件【无法恢复】。
     *
     * 【为什么用 LOCATE 而不是 LIKE】
     *   两个具体原因：
     *     ① LIKE 的匹配串里 % 和 _ 是通配符，而 URL 里 {@code _} 并不罕见
     *        （uuid、目录名都可能带）—— 用它匹配等于"这个下划线代表任意字符"，
     *        会匹配到别的文件上，判断变得不准确且完全看不出来
     *     ② {@code LOCATE(子串, 字符串)} 是纯字符串查找，没有通配符语义，
     *        传什么就找什么 —— 正是这里想要的语义
     *   （需求里说"LIKE 查询即可"，这里用 LOCATE 是同一个思路的更严谨写法：
     *    两者都是全表扫描，但 LOCATE 不会因为 URL 里出现下划线而判断错。）
     *
     * 【为什么比对 key（cover/2026/09/xxx.png）而不是整条 URL】
     *   同一张图在不同文章里可能是绝对地址、也可能是相对地址
     *   （取决于当时 app.upload.base-url 的配置），用整条 URL 比会得出
     *   "没人引用"的结论 —— 然后误删。key 是两种形态里都相同的部分，
     *   用它比对才准确（见 UploadedFileCleaner.toObjectKey）。
     *
     * 【这条查询没有索引可用，这是已知的】
     *   LOCATE 是函数匹配（等价于前置通配符的 LIKE），走不了 B+ 树索引。
     *   可以接受的依据：它只在【删文章 / 编辑文章移除附件】时执行，
     *   每次最多执行"这篇文章涉及的文件数"次，而附件表的量级是
     *   "文章数 × 20 以内"，扫一遍是毫秒级。为它建全文索引或倒排表，
     *   是把一件低频的事做成一套要长期维护的机制。
     *
     * 【为什么不用写 deleted = 0】这张表【没有 deleted 列】（物理删除，
     *   理由见 ArticleAttachment 实体注释与 V14），没有可过滤的东西。
     *
     * @param articleId 当前正在处理的文章 id（它自己不算"别人"）
     * @param key       上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 还有几篇文章的附件在用这个文件（0 表示这张表里没人再用它）
     */
    @Select("""
            SELECT COUNT(*)
            FROM article_attachment
            WHERE article_id <> #{articleId}
              AND LOCATE(#{key}, url) > 0
            """)
    long countOthersReferencing(@Param("articleId") Long articleId, @Param("key") String key);
}
