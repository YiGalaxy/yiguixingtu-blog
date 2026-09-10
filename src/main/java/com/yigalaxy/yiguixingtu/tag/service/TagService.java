package com.yigalaxy.yiguixingtu.tag.service;

import com.yigalaxy.yiguixingtu.tag.dto.TagForm;
import com.yigalaxy.yiguixingtu.tag.dto.TagVO;

import java.util.List;
import java.util.Map;

/**
 * 标签服务。
 *
 * 【它同时服务两个方向，所以方法分成两组】
 *   · 标签自身的管理：列表 / 新建 / 编辑 / 删除（后台）
 *   · 标签与文章的关联：给文章打标签、按标签查文章、查文章挂了哪些标签
 *   两组放在同一个 Service 里，是因为它们操作的是同一份数据、遵守同一套
 *   失效规则（任何一处改动都要推进缓存版本号）；拆成两个 Service 反而
 *   会让"改标签要失效哪些缓存"这件事分散在两处。
 */
public interface TagService {

    /**
     * 前台标签列表：只给访客看的形状（带【已发布】文章数），带 Redis 缓存。
     */
    List<TagVO> listPublished();

    /**
     * 后台标签列表：形状一样，但【不走缓存】。
     * 【为什么后台不用缓存】后台是管理员一个人在用的低频页面，
     * 缓存带来的收益几乎没有；而"刚建完标签列表里却没有"这种事会让人怀疑功能坏了。
     */
    List<TagVO> listAll();

    /** 新建标签，返回新标签 id */
    Long create(TagForm form);

    /** 编辑标签（改名 / 改排序） */
    void update(Long id, TagForm form);

    /** 删除标签（物理删除，同时清理它与文章的关联） */
    void delete(Long id);

    /**
     * 批量查"这些文章分别挂了哪些标签"。
     *
     * 【为什么要有这个方法】文章列表一页 10 篇，若每篇都去查一次标签就是
     * 10 次查询（N+1）。这个方法用一条 IN 查询把整页的标签一次拿回来，
     * 调用方（ArticleServiceImpl）拿 Map 直接取。
     *
     * @param articleIds 文章 id 列表，可以为空（空列表直接返回空 Map，不查库）
     * @return key = 文章 id，value = 该文章的标签（按 sort、id 排序）；
     *         没有标签的文章不会出现在 Map 里（调用方用 getOrDefault 兜底）
     */
    Map<Long, List<TagVO>> mapByArticleIds(List<Long> articleIds);

    /**
     * 覆盖式地设置一篇文章的标签。
     *
     * @param articleId 文章 id
     * @param tagIds    标签 id 列表；传 null 或空列表表示"清空标签"
     * @throws com.yigalaxy.yiguixingtu.common.exception.BusinessException
     *         其中有不存在的标签时抛出（宁可报错，也不要静默丢掉用户选的标签）
     */
    void replaceArticleTags(Long articleId, List<Long> tagIds);

    /**
     * 查某个标签下的【全部】文章 id（不过滤状态，由调用方决定）。
     * 返回空列表表示这个标签下没有文章 —— 调用方据此直接返回空页，不必再查库。
     */
    List<Long> listArticleIdsByTagId(Long tagId);
}
