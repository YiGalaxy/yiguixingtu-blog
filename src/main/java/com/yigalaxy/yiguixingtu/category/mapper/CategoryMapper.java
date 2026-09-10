package com.yigalaxy.yiguixingtu.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分类 Mapper。继承 BaseMapper 后，增删改查都是现成的。
 *
 * 【为什么这里要多一个手写查询】
 *   删除分类前必须回答"还有几篇文章挂在这个分类下"—— 有文章在用就不该让你删。
 *   这个 COUNT 落在 article 表上，而它是文章模块的表 ——
 *   与其让 category 模块注入 ArticleMapper（模块之间互相依赖，依赖方向就乱了），
 *   不如在这里写一条只读 SQL：**依赖方向保持单向**（文章 → 分类）。
 *   代价是分类模块"知道"了 article 表的存在，但只是一条 COUNT。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 数一下有多少篇文章还在用这个分类。
     *
     * 【⚠️ 手写 SQL 必须自己写 deleted = 0】
     *   本项目的逻辑删除是 MyBatis-Plus 的 @TableLogic 实现的，
     *   它【只作用于 BaseMapper 生成的那几条 SQL】，手写语句它管不到。
     *   漏掉这个条件的话，已被删除的文章也会算进来 ——
     *   结果是"明明没文章了，却告诉我还有 3 篇在用"，只能人工去数据库里查。
     *   （同样的坑在 ArticleMapper 的统计聚合与 TagMapper 里都踩过。）
     */
    @Select("SELECT COUNT(*) FROM article WHERE category_id = #{categoryId} AND deleted = 0")
    long countArticlesByCategoryId(@Param("categoryId") Long categoryId);
}
