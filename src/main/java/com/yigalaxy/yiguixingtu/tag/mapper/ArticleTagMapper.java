package com.yigalaxy.yiguixingtu.tag.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 文章-标签关联表（article_tag）的 Mapper。
 *
 * 【为什么它不继承 BaseMapper、也没有对应的实体类】
 *   article_tag 是一张纯粹的【关联表】：两列外键 + 一个时间，没有自己的业务字段，
 *   也没有"一条关联"需要被当作对象传来传去的场景。
 *   给它硬造一个 ArticleTag 实体 + BaseMapper，只会多一个只有两个字段的类，
 *   而真正需要的操作（按文章删、按标签删、批量插、按标签查文章）BaseMapper
 *   一个都表达不了 —— 反正都要手写 SQL，那就干脆写成"一张表的 DAO"。
 *
 * 【为什么不用逻辑删除】
 *   见 V5__create_tag_tables.sql：关联行删了就是删了，留着只会让
 *   "这篇文章还有哪些标签"这种查询永远要额外过滤一次，且没有任何历史价值。
 */
@Mapper
public interface ArticleTagMapper {

    /**
     * 覆盖式地写入一篇文章的标签：先删掉旧的，再插新的。
     *
     * 【为什么要拆成"先删后插"而不是"算出差集再增删"】
     *   差集方案看起来更"省"，但要多一次查询去读旧关联，还要在 Java 里做集合运算，
     *   而且并发编辑同一篇文章时两种方案都不完美（都需要悲观锁或版本号才严格）。
     *   这里的取舍是：**用一次 delete + 一次 batch insert 换取实现的确定性**——
     *   一篇文章最多挂几个标签，代价可以忽略；而"结果一定是提交上来的那一份"
     *   这件事，比省两条 SQL 重要得多。
     *
     * 【为什么删和插都在这个 Mapper 里、由 Service 包在一个事务里】
     *   删掉了但插入失败（比如标签被并发删除）就会丢掉原有标签 ——
     *   所以调用方必须加 @Transactional，见 TagServiceImpl 的用法注释。
     */
    @Delete("DELETE FROM article_tag WHERE article_id = #{articleId}")
    int deleteByArticleId(@Param("articleId") Long articleId);

    /**
     * 删除某个标签的全部关联。
     * 【为什么必须做】标签被删除后，它的关联行如果不清理：
     *   · 文章查标签时 JOIN 不到 tag 行（结果里凭空少一个标签，倒还不算错）
     *   · 但标签的文章数统计会统计到一个"已不存在的标签"上，变成查不到的数据
     *   所以删标签时必须连带清理，这也是"标签用物理删除"的代价之一：
     *   删的时候要多做一步（代码里只有一处，见 TagServiceImpl.delete）。
     */
    @Delete("DELETE FROM article_tag WHERE tag_id = #{tagId}")
    int deleteByTagId(@Param("tagId") Long tagId);

    /**
     * 批量插入关联。
     *
     * @param articleId 文章ID
     * @param tagIds    标签ID集合（调用方保证非空 —— 空集合会拼出语法错误的 SQL）
     *
     * 【为什么用 INSERT IGNORE】正常情况下调用前已经 delete 过，不会冲突。
     *   加 IGNORE 是为了兜住"同一批 tagIds 里有重复值"这种情况
     *   （前端传了重复的 id）：否则整条 INSERT 会因为主键冲突而失败，
     *   用户的保存操作就白做了。这里宁可静默去重，也不要让保存失败。
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO article_tag (article_id, tag_id) VALUES
            <foreach collection="tagIds" item="tagId" separator=",">(#{articleId}, #{tagId})</foreach>
            </script>
            """)
    int insertBatch(@Param("articleId") Long articleId, @Param("tagIds") Collection<Long> tagIds);

    /**
     * 查某个标签下的【全部】文章 id（含草稿）。
     *
     * 【为什么要查 id 列表，而不是直接 JOIN 出文章】
     *   调用方（ArticleServiceImpl）要用这些 id 去走它自己那套分页 + 排序 + 缓存逻辑，
     *   所以这里只提供"id 集合"这一件事，让文章查询留在文章模块里 ——
     *   否则标签模块要复制一份"什么算已发布""置顶怎么排"的规则，
     *   两份规则迟早会不一致。
     *
     * 【为什么这里不写 status / deleted 过滤】那是文章的口径，不是标签的口径：
     *   前台要 status=1、后台要全部，两者都要用这个查询，所以过滤交给调用方。
     */
    @Select("SELECT article_id FROM article_tag WHERE tag_id = #{tagId}")
    List<Long> selectArticleIdsByTagId(@Param("tagId") Long tagId);
}
