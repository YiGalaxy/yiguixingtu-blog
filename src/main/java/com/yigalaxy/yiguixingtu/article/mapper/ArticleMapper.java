package com.yigalaxy.yiguixingtu.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章 Mapper。
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}