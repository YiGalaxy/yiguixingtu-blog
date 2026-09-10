package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;

/**
 * 文章服务。
 *
 * 【接口设计说明】前台和后台分成两组方法，而不是用一个 boolean 参数去区分。
 * 因为"只看已发布"和"全部都能看"是两种完全不同的权限语义，
 * 写成两个名字明确的方法，调用方一眼就知道自己在拿哪一份数据，
 * 也不会出现"传错 true/false 就把草稿漏出去"的低级事故。
 */
public interface ArticleService {

    /** 【前台】只查已发布，草稿永远查不到 */
    IPage<ArticleVO> pagePublished(ArticleQuery query);

    /** 【后台】全部都能查，含草稿 */
    IPage<ArticleVO> pageAll(ArticleQuery query);

    /** 【前台】详情。草稿一律当作"不存在" */
    ArticleVO getPublishedDetail(Long id);

    /** 【后台】详情，草稿也能看 */
    ArticleVO getDetail(Long id);

    /** 新建文章，返回新文章ID */
    Long create(ArticleForm form, Long authorId);

    /** 编辑文章 */
    void update(Long id, ArticleForm form);

    /** 发布 / 下架 */
    void updateStatus(Long id, Integer status);

    /** 删除文章（逻辑删除） */
    void remove(Long id);
}