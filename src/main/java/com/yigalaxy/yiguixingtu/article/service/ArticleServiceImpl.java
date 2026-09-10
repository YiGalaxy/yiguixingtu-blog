package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文章服务实现。
 */
@Slf4j
@Service
public class ArticleServiceImpl implements ArticleService {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;

    public ArticleServiceImpl(ArticleMapper articleMapper, CategoryMapper categoryMapper) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
    }

    // =================================================================
    //  查询
    // =================================================================

    @Override
    public IPage<ArticleVO> pagePublished(ArticleQuery query) {
        // 【安全核心】写死 1。不管前端传什么 status，这里都不理会。
        return doPage(query, 1);
    }

    @Override
    public IPage<ArticleVO> pageAll(ArticleQuery query) {
        // 传 null 表示"不强制状态"，此时才允许用前端传的 status 做筛选
        return doPage(query, null);
    }

    /**
     * 分页查询的公共实现。
     *
     * @param forceStatus 不为 null 时，强制按这个状态过滤（前台传 1）；
     *                    为 null 时，才使用 query 里的 status 条件（后台）
     */
    private IPage<ArticleVO> doPage(ArticleQuery query, Integer forceStatus) {

        // ---- 参数兜底：防止前端传 0、负数或超大 size ----
        long pageNo = (query.getPage() == null || query.getPage() < 1) ? 1L : query.getPage();
        long pageSize = (query.getSize() == null || query.getSize() < 1) ? 10L : Math.min(query.getSize(), 50L);

        Page<Article> page = new Page<>(pageNo, pageSize);

        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                // 前台：强制 status = 1（写死）
                .eq(forceStatus != null, Article::getStatus, forceStatus)
                // 后台：才允许按前端传的 status 筛
                .eq(forceStatus == null && query.getStatus() != null, Article::getStatus, query.getStatus())
                .eq(query.getCategoryId() != null, Article::getCategoryId, query.getCategoryId())
                // 关键词：标题 或 摘要 命中即可。
                // and(...) 是为了把这两个 or 条件包成一组：(title like ? or summary like ?)，
                // 否则它会和前面的 status / categoryId 平级，变成
                //   status=1 AND category_id=2 AND title like ? OR summary like ?
                // 这就错了 —— OR 会把前面的条件全部架空。
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(Article::getTitle, query.getKeyword())
                        .or()
                        .like(Article::getSummary, query.getKeyword()));

        // 列表页不需要正文。只查除 content 以外的列，避免把 longtext 全捞出来。
        // （如果这行在你的 MyBatis-Plus 版本上报错，直接注释掉即可，不影响功能）
        wrapper.select(Article.class, field -> !"content".equals(field.getColumn()));

        applySort(wrapper, query.getSortField(), query.getSortOrder());

        IPage<Article> articlePage = articleMapper.selectPage(page, wrapper);

        // 把实体页转成 VO 页：分页信息（总数/总页数）要保留，只换里面的数据
        Page<ArticleVO> voPage = new Page<>(articlePage.getCurrent(), articlePage.getSize(), articlePage.getTotal());
        voPage.setRecords(toVOList(articlePage.getRecords()));
        return voPage;
    }

    @Override
    public ArticleVO getPublishedDetail(Long id) {
        Article article = articleMapper.selectById(id);

        // 【安全】草稿对外一律当作"不存在"，而不是返回 403 "无权限"。
        // 因为返回 403 等于告诉别人"这里确实有一篇草稿" —— 信息就泄漏了。
        // 对未授权的访问者来说，草稿和不存在应该长得一模一样。
        if (article == null || article.getStatus() == null || article.getStatus() != 1) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        increaseViewCount(id);
        return toVO(article, getCategoryName(article.getCategoryId()));
    }

    @Override
    public ArticleVO getDetail(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }
        return toVO(article, getCategoryName(article.getCategoryId()));
    }

    // =================================================================
    //  写操作
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ArticleForm form, Long authorId) {

        checkCategory(form.getCategoryId());

        Article article = new Article();
        article.setTitle(form.getTitle().trim());
        article.setContent(form.getContent());
        // 摘要留空时，自动从正文里截一段
        article.setSummary(buildSummary(form.getSummary(), form.getContent()));
        article.setCover(form.getCover());
        article.setCategoryId(form.getCategoryId());
        article.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        article.setIsTop(form.getIsTop() == null ? 0 : form.getIsTop());
        article.setViewCount(0);
        article.setAuthorId(authorId);

        articleMapper.insert(article);

        // insert 之后，MyBatis-Plus 会把刚生成的自增主键【回填】到 article 对象里，
        // 所以这里能直接拿到新文章的 ID。
        return article.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleForm form) {

        // 1. 确认文章存在（也顺便防止改到已删除的文章）
        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        checkCategory(form.getCategoryId());

        // 2. 逐字段显式 SET，而不是用 updateById。
        //
        // 【为什么不用 updateById？】
        // updateById 只更新非 null 字段 —— 这本来是个贴心设计，但在这里会出问题：
        // 用户想把封面删掉，提交 cover = null，
        // updateById 会跳过它，封面根本删不掉，用户会以为界面卡了。
        // 用 LambdaUpdateWrapper.set(...) 是显式指定"这几列就要改成这个值"，
        // 传 null 也会老老实实写成 NULL。
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getTitle, form.getTitle().trim())
                .set(Article::getSummary, buildSummary(form.getSummary(), form.getContent()))
                .set(Article::getContent, form.getContent())
                .set(Article::getCover, form.getCover())
                .set(Article::getCategoryId, form.getCategoryId())
                .set(Article::getStatus, form.getStatus() == null ? exist.getStatus() : form.getStatus())
                .set(Article::getIsTop, form.getIsTop() == null ? exist.getIsTop() : form.getIsTop()));
    }

    @Override
    public void updateStatus(Long id, Integer status) {

        // 校验状态值只能是 0 或 1
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 0(草稿) 或 1(已发布)");
        }

        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getStatus, status));
    }

    @Override
    public void remove(Long id) {
        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }
        // @TableLogic 会把它变成 UPDATE article SET deleted = 1 WHERE id = ?
        // （不是真的 DELETE，历史数据还在，误删可以人工恢复）
        articleMapper.deleteById(id);
    }

    // =================================================================
    //  私有工具方法
    // =================================================================

    /**
     * 浏览量 +1。
     *
     * 【为什么用 SQL 自增，而不是"查出来 → 加一 → 写回去"？】
     * 后者在并发下会丢计数：
     *   请求A 读到 100，请求B 也读到 100，
     *   A 写回 101，B 也写回 101 —— 两次访问只增加了 1。
     * 交给数据库执行 view_count = view_count + 1，
     * 这个"读-改-写"是原子的，不会丢。
     */
    private void increaseViewCount(Long id) {
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .setSql("view_count = view_count + 1")
                .eq(Article::getId, id));
    }

    /**
     * 校验分类是否存在（不传分类则跳过）
     */
    private void checkCategory(Long categoryId) {
        if (categoryId == null) {
            return;     // 允许不选分类
        }
        if (categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
    }

    /**
     * 取分类名称（用于详情接口）
     */
    private String getCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getName();
    }

    /**
     * 实体列表 -> VO 列表。
     *
     * 【这里在避免 N+1 查询】
     * 最直白的写法是每转一篇文章就查一次分类名，10 篇文章 = 1 次查文章 + 10 次查分类。
     * 这就是经典的 N+1 问题：数据量一大，数据库往返次数爆炸。
     * 正确做法：先把这一页所有分类ID收集起来 → 一次查完 → 做成 Map 反复用。
     * 于是 10 篇文章只需要 2 次查询。
     */
    private List<ArticleVO> toVOList(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 收集本页出现过的分类ID
        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. 一次性把它们查出来，做成 id -> name 的 Map
        Map<Long, String> categoryNameMap = categoryIds.isEmpty()
                ? Collections.emptyMap()
                : categoryMapper.selectList(
                        new LambdaQueryWrapper<Category>().in(Category::getId, categoryIds))
                .stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        // 3. 逐个转换，分类名直接从 Map 里取，不再查库
        return articles.stream()
                .map(a -> toVO(a, categoryNameMap.get(a.getCategoryId())))
                .collect(Collectors.toList());
    }

    /**
     * 实体 -> VO
     */
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

    /**
     * 生成摘要：
     * 用户填了就用用户的；没填就从正文里剥掉 Markdown 标记、截前 120 字。
     */
    private String buildSummary(String summary, String content) {
        if (StringUtils.hasText(summary)) {
            return summary.trim();
        }
        if (!StringUtils.hasText(content)) {
            return null;
        }

        String plain = content
                .replaceAll("```[\\s\\S]*?```", " ")   // 去掉代码块
                .replaceAll("[#>*`\\[\\]()!-]", " ")   // 去掉常见 Markdown 标记
                .replaceAll("\\s+", " ")               // 连续空白压成一个空格
                .trim();

        return plain.length() <= 120 ? plain : plain.substring(0, 120) + "...";
    }

    /**
     * 应用排序。
     *
     * 【为什么必须用白名单？】
     * 如果直接把前端传的字符串拼进 SQL（"order by " + sortField），
     * 前端传个 id; DELETE FROM article 就可能出事。
     * switch 白名单让前端只能在这几个字段里选，传别的就走默认排序。
     * 跟你在 UserServiceImpl 里写的是同一套思路。
     */
    private void applySort(LambdaQueryWrapper<Article> wrapper, String sortField, String sortOrder) {

        // switch 遇到 null 会抛 NPE，所以先挡一层
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
            // 没传 或 传了非法字段 -> 默认排序
            default -> applyDefaultSort(wrapper);
        }
    }

    /**
     * 默认排序：置顶的排最前，然后按创建时间倒序（新的在前）。
     * 这是博客首页最自然的顺序。
     */
    private void applyDefaultSort(LambdaQueryWrapper<Article> wrapper) {
        wrapper.orderByDesc(Article::getIsTop).orderByDesc(Article::getCreateTime);
    }
}