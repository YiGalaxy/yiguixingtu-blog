package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.dto.ArticleArchiveVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleRssVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;

import java.util.List;

/** 文章读取与浏览上报接口。公开读取只返回已发布文章。 */
public interface ArticleService {

    IPage<ArticleVO> pagePublished(ArticleQuery query);

    IPage<ArticleVO> pageAll(ArticleQuery query);

    ArticleVO getPublishedDetail(Long id);

    int recordView(Long id);

    ArticleStatsVO stats();

    ArticleArchiveVO archive();

    List<ArticleRssVO> rssItems();

    ArticleVO getDetail(Long id);
}
