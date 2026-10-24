package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.cache.PublishedArticleCache;
import com.yigalaxy.yiguixingtu.article.dto.ArticleArchiveVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleAttachmentVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleRssVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.tag.service.TagService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 文章读取与浏览上报入口；公开列表和详情只返回已发布内容。 */
@Service
public class ArticleServiceImpl implements ArticleService {

    private static final int ARCHIVE_MAX_ARTICLES = 500;
    private static final int RSS_MAX_ITEMS = 20;

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleAttachmentService articleAttachmentService;
    private final ArticleViewCounter viewCounter;
    private final PublishedArticleCache publishedArticleCache;
    private final TagService tagService;

    public ArticleServiceImpl(ArticleMapper articleMapper,
                              CategoryMapper categoryMapper,
                              ArticleAttachmentService articleAttachmentService,
                              ArticleViewCounter viewCounter,
                              PublishedArticleCache publishedArticleCache,
                              TagService tagService) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
        this.articleAttachmentService = articleAttachmentService;
        this.viewCounter = viewCounter;
        this.publishedArticleCache = publishedArticleCache;
        this.tagService = tagService;
    }

    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_PAGE,
            key = "@articleCacheVersion.current() + ':' + #query.toCacheKey()")
    public IPage<ArticleVO> pagePublished(ArticleQuery query) {
        return doPage(query, 1);
    }

    @Override
    public IPage<ArticleVO> pageAll(ArticleQuery query) {
        return doPage(query, null);
    }

    private IPage<ArticleVO> doPage(ArticleQuery query, Integer forceStatus) {

        long pageNo = (query.getPage() == null || query.getPage() < 1) ? 1L : query.getPage();
        long pageSize = (query.getSize() == null || query.getSize() < 1)
                ? ArticleQuery.DEFAULT_PAGE_SIZE
                : Math.min(query.getSize(), ArticleQuery.MAX_PAGE_SIZE);

        List<Long> taggedArticleIds = null;
        if (query.getTagId() != null) {
            taggedArticleIds = tagService.listArticleIdsByTagId(query.getTagId());
            if (taggedArticleIds.isEmpty()) {
                return new Page<>(pageNo, pageSize, 0);
            }
        }

        Page<Article> page = new Page<>(pageNo, pageSize);

        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .eq(forceStatus != null, Article::getStatus, forceStatus)
                .eq(forceStatus == null && query.getStatus() != null, Article::getStatus, query.getStatus())
                .eq(query.getCategoryId() != null, Article::getCategoryId, query.getCategoryId())
                .in(taggedArticleIds != null, Article::getId, taggedArticleIds)
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(Article::getTitle, query.getKeyword())
                        .or()
                        .like(Article::getSummary, query.getKeyword()));

        wrapper.select(Article.class, field -> !"content".equals(field.getColumn()));

        applySort(wrapper, query.getSortField(), query.getSortOrder());
        // 主排序值相同时以唯一 ID 打破平局，保证分页顺序稳定。
        if (!"id".equals(query.getSortField())) {
            wrapper.orderByDesc(Article::getId);
        }

        IPage<Article> articlePage = articleMapper.selectPage(page, wrapper);

        Page<ArticleVO> voPage = new Page<>(articlePage.getCurrent(), articlePage.getSize(), articlePage.getTotal());
        voPage.setRecords(toVOList(articlePage.getRecords()));
        return voPage;
    }

    @Override
    public ArticleVO getPublishedDetail(Long id) {

        ArticleVO vo = publishedArticleCache.load(id);

        long dbCount = vo.getViewCount() == null ? 0L : vo.getViewCount();
        vo.setViewCount((int) (dbCount + viewCounter.pending(id)));
        return vo;
    }

    @Override
    public int recordView(Long id) {
        ArticleVO vo = publishedArticleCache.load(id);

        long pending = viewCounter.increment(id);
        if (pending < 0) {
            pending = viewCounter.pending(id);
        }

        long dbCount = vo.getViewCount() == null ? 0L : vo.getViewCount();
        return (int) (dbCount + pending);
    }

    @Override
    public ArticleVO getDetail(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }
        return withAttachments(withTags(toVO(article, getCategoryName(article.getCategoryId()))));
    }

    private ArticleVO withAttachments(ArticleVO vo) {
        vo.setAttachments(ArticleAttachmentVO.fromEntities(articleAttachmentService.listByArticleId(vo.getId())));
        return vo;
    }

    private ArticleVO withTags(ArticleVO vo) {
        List<com.yigalaxy.yiguixingtu.tag.dto.TagVO> tags =
                tagService.mapByArticleIds(List.of(vo.getId())).get(vo.getId());
        if (tags != null) {
            vo.setTags(tags);
        }
        return vo;
    }

    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_STATS,
            key = "@articleCacheVersion.current()")
    public ArticleStatsVO stats() {
        ArticleStatsVO aggregate = articleMapper.selectPublishedAggregate();

        Long categoryCount = categoryMapper.selectCount(null);

        return new ArticleStatsVO(
                aggregate == null ? 0L : aggregate.getArticleCount(),
                aggregate == null ? 0L : aggregate.getViewCount(),
                categoryCount == null ? 0L : categoryCount);
    }

    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_ARCHIVE,
            key = "@articleCacheVersion.current()")
    public ArticleArchiveVO archive() {

        List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getTitle, Article::getCreateTime)
                .eq(Article::getStatus, 1)
                .orderByDesc(Article::getCreateTime)
                .orderByDesc(Article::getId)
                .last("LIMIT " + ARCHIVE_MAX_ARTICLES));

        ArticleArchiveVO result = new ArticleArchiveVO();
        result.setTotal((long) articles.size());

        ArticleArchiveVO.ArchiveMonth currentMonth = null;
        String currentKey = null;

        for (Article article : articles) {
            LocalDateTime time = article.getCreateTime();
            if (time == null) {
                continue;
            }

            String key = time.getYear() + "-" + time.getMonthValue();
            if (!key.equals(currentKey)) {
                currentMonth = new ArticleArchiveVO.ArchiveMonth();
                currentMonth.setYear(time.getYear());
                currentMonth.setMonth(time.getMonthValue());
                currentMonth.setCount(0);
                result.getMonths().add(currentMonth);
                currentKey = key;
            }

            ArticleArchiveVO.ArchiveArticle item = new ArticleArchiveVO.ArchiveArticle();
            item.setId(article.getId());
            item.setTitle(article.getTitle());
            item.setCreateTime(time);
            currentMonth.getArticles().add(item);
            currentMonth.setCount(currentMonth.getCount() + 1);
        }

        return result;
    }

    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_RSS,
            key = "@articleCacheVersion.current()")
    public List<ArticleRssVO> rssItems() {
        List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getTitle, Article::getSummary,
                        Article::getContent, Article::getCreateTime)
                .eq(Article::getStatus, 1)
                .orderByDesc(Article::getCreateTime)
                .orderByDesc(Article::getId)
                .last("LIMIT " + RSS_MAX_ITEMS));

        List<ArticleRssVO> items = new ArrayList<>(articles.size());
        for (Article article : articles) {
            ArticleRssVO vo = new ArticleRssVO();
            vo.setId(article.getId());
            vo.setTitle(article.getTitle());
            vo.setSummary(article.getSummary());
            vo.setContent(article.getContent());
            vo.setCreateTime(article.getCreateTime());
            items.add(vo);
        }
        return items;
    }

    private String getCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getName();
    }

    private List<ArticleVO> toVOList(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> categoryNameMap = categoryIds.isEmpty()
                ? Collections.emptyMap()
                : categoryMapper.selectList(
                        new LambdaQueryWrapper<Category>().in(Category::getId, categoryIds))
                .stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        List<ArticleVO> voList = articles.stream()
                .map(a -> toVO(a, categoryNameMap.get(a.getCategoryId())))
                .collect(Collectors.toList());

        List<Long> articleIds = articles.stream().map(Article::getId).collect(Collectors.toList());
        Map<Long, List<com.yigalaxy.yiguixingtu.tag.dto.TagVO>> tagMap =
                tagService.mapByArticleIds(articleIds);
        for (ArticleVO vo : voList) {
            List<com.yigalaxy.yiguixingtu.tag.dto.TagVO> tags = tagMap.get(vo.getId());
            if (tags != null) {
                vo.setTags(tags);
            }
        }
        return voList;
    }

    private ArticleVO toVO(Article article, String categoryName) {
        ArticleVO vo = new ArticleVO();
        vo.setId(article.getId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setContent(article.getContent());
        vo.setCover(article.getCover());
        vo.setCategoryId(article.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setIsTop(article.getIsTop());
        vo.setAuthorId(article.getAuthorId());
        vo.setCreateTime(article.getCreateTime());
        vo.setUpdateTime(article.getUpdateTime());
        return vo;
    }

    private void applySort(LambdaQueryWrapper<Article> wrapper, String sortField, String sortOrder) {

        if (!StringUtils.hasText(sortField)) {
            applyDefaultSort(wrapper);
            return;
        }

        boolean asc = "asc".equalsIgnoreCase(sortOrder);

        switch (sortField) {
            case "id" -> {
                if (asc) wrapper.orderByAsc(Article::getId);
                else wrapper.orderByDesc(Article::getId);
            }
            case "title" -> {
                if (asc) wrapper.orderByAsc(Article::getTitle);
                else wrapper.orderByDesc(Article::getTitle);
            }
            case "status" -> {
                if (asc) wrapper.orderByAsc(Article::getStatus);
                else wrapper.orderByDesc(Article::getStatus);
            }
            case "viewCount" -> {
                if (asc) wrapper.orderByAsc(Article::getViewCount);
                else wrapper.orderByDesc(Article::getViewCount);
            }
            case "isTop" -> {
                if (asc) wrapper.orderByAsc(Article::getIsTop);
                else wrapper.orderByDesc(Article::getIsTop);
            }
            case "createTime" -> {
                if (asc) wrapper.orderByAsc(Article::getCreateTime);
                else wrapper.orderByDesc(Article::getCreateTime);
            }
            case "updateTime" -> {
                if (asc) wrapper.orderByAsc(Article::getUpdateTime);
                else wrapper.orderByDesc(Article::getUpdateTime);
            }
            default -> applyDefaultSort(wrapper);
        }
    }

    private void applyDefaultSort(LambdaQueryWrapper<Article> wrapper) {
        wrapper.orderByDesc(Article::getIsTop).orderByDesc(Article::getCreateTime);
    }
}
