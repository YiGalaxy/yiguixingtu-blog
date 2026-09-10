package com.yigalaxy.yiguixingtu.article.cache;

import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * =====================================================================
 * 「已发布文章详情」的可缓存读取
 *
 * ============ 为什么这个方法要单独放在一个类里 ============
 *
 * Spring Cache 是靠【代理】实现的。代理只能拦住"从外部进来的调用"——
 * 如果 {@code ArticleServiceImpl.getPublishedDetail()} 直接调用自己类里的
 * {@code this.loadFromDb()}，那个调用【根本不经过代理】，注解等于没写：
 * 不报错、也不生效（这是 Spring Cache 最经典的一个坑）。
 *
 * 所以把"要缓存的那一段"单独抽成这个 Bean，由 Service 注入后从外部调用，
 * 代理就必然生效了。这样做的另一个好处是把职责分清楚了：
 *   · 这个类只负责"读出一篇已发布的文章，读不到就报 404"，是【纯读、幂等】的
 *   · 浏览量计数那种带副作用的逻辑留在 Service 里，不进缓存 —— 见下面说明
 *
 * ============ ⚠️ 这里刻意【不含】浏览量，这一点很关键 ============
 *
 *   getPublishedDetail 返回的浏览量 = 库里的快照 + Redis 里还没落库的增量，
 *   而那个增量【每次访问都会变】。如果把最终结果整个缓存起来，
 *   后续请求命中缓存就不会再执行 INCR —— **浏览量直接不再增长**。
 *   这个 bug 很隐蔽：页面照常打开、数字照常显示，只是永远停在那个值上。
 *
 *   所以缓存的边界划在"库里的那份数据"：本类返回的 VO 里，
 *   viewCount 是数据库的快照；增量由 Service 在缓存外面合并上去。
 *
 * ============ 防击穿：sync = true ============
 *
 *   "击穿"指的是：一个热点 key 正好过期，同时涌进来 N 个请求，
 *   它们【全部】发现缓存没了、【全部】去查库 ——
 *   一瞬间对同一行数据发起 N 次查询，等于缓存白做了。
 *
 *   这里的做法是 {@code sync = true}：它让 Spring 走
 *   {@code Cache.get(key, Callable)} 这个"带加载器"的接口。
 *   Redis 那边配合 {@code lockingRedisCacheWriter}（见 RedisConfig），
 *   在缓存未命中时先抢一把 Redis 里的锁，只有抢到的那个去加载，
 *   其余请求等锁释放后再读缓存。
 *
 *   【为什么不是自己写 SETNX + 双重检查】
 *     那正是 LockingRedisCacheWriter 内部在做的事（它就是 SETNX + 超时释放），
 *     只是它已经处理好了锁超时、异常时释放、重试这些细节。
 *     自己写一遍只会多出几个能写错的地方，而且错起来是"偶发多查一次库"，
 *     很难被发现。用框架现成的实现是更稳的选择。
 * =====================================================================
 */
@Component
public class PublishedArticleCache {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;

    public PublishedArticleCache(ArticleMapper articleMapper, CategoryMapper categoryMapper) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * 读一篇已发布的文章（带缓存）。
     *
     * 【key 为什么是 详情版本号 + id】
     *   复用"文章缓存版本号"这套失效机制，但用的是【详情专用】的那个计数器：
     *   文章的写操作会把两个计数器一起 +1（列表和详情一起失效），
     *   而浏览量的定时落库只推动详情这个 —— 因为它改变了详情里的 view_count 快照。
     *   详见 ArticleCacheVersion 里 DETAIL_VERSION_KEY 的注释。
     *
     * @throws BusinessException 文章不存在，或者是草稿（对外一律当作不存在）
     */
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_DETAIL,
            key = "@articleCacheVersion.currentDetail() + ':' + #id",
            sync = true)
    public ArticleVO load(Long id) {
        Article article = articleMapper.selectById(id);

        // 【安全】草稿对外一律当作"不存在"，而不是返回 403 "无权限"。
        // 因为返回 403 等于告诉别人"这里确实有一篇草稿" —— 信息就泄漏了。
        // 对未授权的访问者来说，草稿和不存在应该长得一模一样。
        //
        // 注意：这里抛异常意味着【不会被缓存】。这是有意的：
        //   缓存"不存在"会让刚发布的文章在一段时间内依然 404，
        //   而按主键查一次的成本本来就极低（实测 0.05ms 量级），
        //   没有理由为它引入这种不一致。
        if (article == null || article.getStatus() == null || article.getStatus() != 1) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        return toVO(article, getCategoryName(article.getCategoryId()));
    }

    /**
     * 取分类名。
     *
     * 【这里为什么不做 N+1 优化】
     *   详情页只有一篇文章，也就是最多查一次分类（列表页才需要"批量查完做成 Map"）。
     *   为一次查询引进去重逻辑属于过度设计。
     */
    private String getCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getName();
    }

    /** 实体 -> VO（与 ArticleServiceImpl.toVO 保持同一套字段映射） */
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
}
