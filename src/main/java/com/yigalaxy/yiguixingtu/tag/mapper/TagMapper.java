package com.yigalaxy.yiguixingtu.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.tag.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 标签 Mapper。
 *
 * 【为什么这里要手写 SQL（不像 Tag CRUD 那样用 BaseMapper 就够了）】
 *   有两个查询是 BaseMapper 表达不了的：
 *     · "每个标签下有几篇【已发布】的文章" —— 需要 JOIN + GROUP BY，
 *       MyBatis-Plus 的条件构造器只管单表条件，聚合得自己写
 *     · "这些文章分别有哪些标签" —— 页面里 10 篇文章就要查 10 次标签，
 *       那就是典型的 N+1；这里用一条 IN 查询拿回来，在 Java 里分组
 *
 * 【⚠️ 手写 SQL 必须自己写 deleted = 0】
 *   本项目的逻辑删除是 MyBatis-Plus 的 @TableLogic 实现的，它只作用于
 *   BaseMapper 生成的那几条 SQL —— 手写 SQL 它管不到！
 *   所以下面凡是查 article 的地方都显式写了 a.deleted = 0。
 *   漏掉它的后果很具体：被删除的文章仍然会被统计进标签的文章数里。
 *   （同样的坑在 ArticleMapper 的统计聚合查询里也踩过一次。）
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 统计每个标签下【已发布】的文章数。
     *
     * @return 每行两个字段：tag_id / total（没有文章数>0 的标签不会出现在结果里）
     *
     * 【为什么是 JOIN 而不是子查询】COUNT 走 idx_tag → article 的主键回表，
     * 数据量小的时候两者差不多，但 JOIN + GROUP BY 的执行计划更稳定，
     * 也不会因为 MySQL 版本不同而退化成相关子查询（那样就 O(n×m) 了）。
     */
    @Select("""
            SELECT at.tag_id AS tagId, COUNT(*) AS total
            FROM article_tag at
            JOIN article a ON a.id = at.article_id
            WHERE a.deleted = 0 AND a.status = 1
            GROUP BY at.tag_id
            """)
    List<Map<String, Object>> countPublishedByTag();

    /**
     * 查"这些文章分别挂了哪些标签"。
     *
     * @param articleIds 文章 id 列表（调用方保证非空 —— 空列表会拼出 IN () 语法错误）
     * @return 每行两个字段：articleId / tagId
     *
     * 【为什么要在 Java 里分组，而不是让 SQL 拼成一行】
     *   MySQL 的 GROUP_CONCAT 能拼成 "1,2,3"，但那样还得在 Java 里 split 回来，
     *   而且 GROUP_CONCAT 有长度上限（默认 1024 字节）会被静默截断 ——
     *   返回原始行、在 Java 里分组，语义最清楚也最不容易出错。
     *
     * 【为什么要按 sort、id 排序】保证同一篇文章每次返回的标签顺序一致，
     *   否则前端标签的排列会随机跳动（缓存命中与未命中还会不一样）。
     */
    @Select("""
            <script>
            SELECT at.article_id AS articleId, t.id AS tagId, t.name AS name, t.sort AS sort
            FROM article_tag at
            JOIN tag t ON t.id = at.tag_id
            WHERE at.article_id IN
            <foreach collection="articleIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY t.sort ASC, t.id ASC
            </script>
            """)
    List<Map<String, Object>> selectTagsByArticleIds(@Param("articleIds") List<Long> articleIds);
}
