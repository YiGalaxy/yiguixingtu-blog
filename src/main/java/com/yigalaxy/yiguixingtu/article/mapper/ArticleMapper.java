package com.yigalaxy.yiguixingtu.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import org.apache.ibatis.annotations.Mapper;
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
}
