package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.article.cache.PublishedArticleCache;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
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
 *
 * =====================================================================
 * 【缓存策略：一处在读，四处失效】
 *
 * 读：{@link #pagePublished} 加了 {@code @Cacheable}，
 *     前台文章列表走 Redis 缓存（配置见 {@link RedisConfig}）。
 *
 * 写：新增 / 编辑 / 改状态 / 删除 这四个操作末尾都会调用
 *     {@link ArticleCacheVersion#bump()}，让之前缓存的列表【立刻】失效。
 *
 * 【为什么不按"改哪条就更新哪条"来维护缓存】
 *   列表缓存的 key 是按（页码、每页条数、分类、关键词、排序）拼出来的，
 *   一篇文章会同时出现在很多条缓存里（第 1 页、第 2 页、按某分类筛的那一页、
 *   按某关键词搜到的那一页……），而且一改就可能跨页移动。
 *   想把"该更新哪几条"算清楚，成本远高于直接全部作废重查；
 *   列表接口本来就是"读多写少"，写一次让后面几次重新读库完全可以接受。
 *   这是缓存一致性里很常见的一处取舍：**宁可作废得粗一点，也不要出现脏数据**。
 *
 * 【为什么不是 @CacheEvict(allEntries = true) —— 这里踩过一个真坑】
 *   最直观的写法是 {@code @CacheEvict(allEntries = true)}：把缓存名下所有 key 删掉。
 *   实测发现 Spring Data Redis 的 {@code RedisCache.clear()}【是异步的】——
 *   方法返回时还没删完，删除在后台继续跑，于是"写完之后立刻读"依然读到旧数据，
 *   业务上的表现就是"发布文章后刷新前台，有时看得到有时看不到"。
 *   （显式指定 BatchStrategies.keys() 也不能把它变同步，详见 ArticleCacheVersion 的类注释）
 *
 *   所以改成【版本号】方案：读缓存的 key 里带一个版本号，
 *   写操作只把版本号 INCR 一下 —— INCR 是原子且同步的，
 *   版本一变，旧 key 就再也拼不出来，等于瞬间全部作废，而一条数据都不用删。
 *
 * 【为什么注解放在 Service 而不是 Controller】
 *   Spring Cache 是靠【代理】实现的（AOP）。代理要生效，调用必须"从外部进来"——
 *   同类内部方法互相调用（this.xxx()）会绕过代理、注解直接失效。
 *   放在 Service 的对外方法上，正好是 Controller 从外部调用的入口，代理必然生效。
 * =====================================================================
 */
@Slf4j
@Service
public class ArticleServiceImpl implements ArticleService {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleCacheVersion articleCacheVersion;

    /** 浏览量计数器：详情页只写它（Redis），落库交给 ViewCountSyncTask */
    private final ArticleViewCounter viewCounter;

    /**
     * 详情页"库里的那份数据"的可缓存读取。
     * 单独一个 Bean 是为了让 @Cacheable 的代理生效（同类内部调用不走代理），
     * 详见该类的注释。
     */
    private final PublishedArticleCache publishedArticleCache;

    /**
     * 操作审计记录器。
     * 业务代码只调它一行，剩下的（用户/IP/traceId 的采集、事务提交后异步落库）
     * 都在 audit 包里，见 OperationLogRecorder 的类注释。
     */
    private final OperationLogRecorder operationLogRecorder;

    /**
     * 标签服务。
     * 文章与标签是多对多：关联的读写都在标签模块里（它管着 article_tag 表与失效规则），
     * 文章这边只负责"在正确的时候调它"——这样"标签变了要失效哪些缓存"就只有一处实现。
     */
    private final com.yigalaxy.yiguixingtu.tag.service.TagService tagService;

    public ArticleServiceImpl(ArticleMapper articleMapper,
                              CategoryMapper categoryMapper,
                              ArticleViewCounter viewCounter,
                              ArticleCacheVersion articleCacheVersion,
                              PublishedArticleCache publishedArticleCache,
                              OperationLogRecorder operationLogRecorder,
                              com.yigalaxy.yiguixingtu.tag.service.TagService tagService) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
        this.viewCounter = viewCounter;
        this.articleCacheVersion = articleCacheVersion;
        this.publishedArticleCache = publishedArticleCache;
        this.operationLogRecorder = operationLogRecorder;
        this.tagService = tagService;
    }

    // =================================================================
    //  查询
    // =================================================================

    /**
     * 前台已发布文章列表 —— 走 Redis 缓存。
     *
     * 【key 为什么长这样】
     *   {@code @articleCacheVersion.current() + ':' + #query.toCacheKey()}
     *   · 前半段是【缓存版本号】：写操作（发文/改文/删文）会把版本号 +1，
     *     版本号一变，之前所有缓存 key 就再也拼不出来 = 全部立刻失效。
     *     为什么不用 allEntries 清缓存，见 ArticleCacheVersion 的类注释
     *     （结论：Spring 的 RedisCache.clear() 是异步的，写后立刻读会读到旧数据）
     *   · 后半段是【查询条件的归一化字符串】，见 ArticleQuery.toCacheKey()
     *     （把默认值、被夹取的 size、空关键词都归一化，避免同一份数据存很多份）
     *
     * 【SpEL 里的 @ 是什么意思】
     *   {@code @articleCacheVersion} 表示"从 Spring 容器里按 bean 名取这个对象"，
     *   后面直接跟方法调用。bean 名默认就是类名首字母小写，即 ArticleCacheVersion
     *   → articleCacheVersion。所以每次算缓存 key 时都会现取一次版本号，
     *   保证刚被 bump 过的版本立刻生效。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_PAGE,
            key = "@articleCacheVersion.current() + ':' + #query.toCacheKey()")
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
        // 默认值与上限都取 ArticleQuery 上的常量，而不是就地写 10 / 50：
        // 因为 ArticleQuery.toCacheKey() 生成缓存 key 时也要用同一套规则
        // （把 size 归一化成"实际生效值"，否则 ?size=51 / 52 / 99 会各自
        //  变成不同的 key、每次都未命中，缓存就白做了）。
        // 两处共用常量，就不会出现"只改了一边"导致 key 与真实查询对不上的情况。
        long pageNo = (query.getPage() == null || query.getPage() < 1) ? 1L : query.getPage();
        long pageSize = (query.getSize() == null || query.getSize() < 1)
                ? ArticleQuery.DEFAULT_PAGE_SIZE
                : Math.min(query.getSize(), ArticleQuery.MAX_PAGE_SIZE);

        // ---- 标签筛选：先查出"这个标签下有哪些文章" ----
        //
        // 【为什么要分成两步，而不是写一条 JOIN 的子查询】
        //   关联表在标签模块里，文章模块不该去拼它的 SQL（那是把两个模块的
        //   表结构焊在一起）；而且"用 IN (id, id, ...)"这种写法让数据库
        //   直接用主键索引取文章，执行计划比 EXISTS 子查询更好预测。
        //
        // 【空结果的处理很关键】标签下没有文章时直接返回空页，
        //   不去查库、更不能把空集合拼进 IN ()（那是 SQL 语法错误）。
        List<Long> taggedArticleIds = null;
        if (query.getTagId() != null) {
            taggedArticleIds = tagService.listArticleIdsByTagId(query.getTagId());
            if (taggedArticleIds.isEmpty()) {
                return new Page<>(pageNo, pageSize, 0);
            }
        }

        Page<Article> page = new Page<>(pageNo, pageSize);

        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                // 前台：强制 status = 1（写死）
                .eq(forceStatus != null, Article::getStatus, forceStatus)
                // 后台：才允许按前端传的 status 筛
                .eq(forceStatus == null && query.getStatus() != null, Article::getStatus, query.getStatus())
                .eq(query.getCategoryId() != null, Article::getCategoryId, query.getCategoryId())
                // 标签筛选：限定在这批文章 id 里
                .in(taggedArticleIds != null, Article::getId, taggedArticleIds)
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

        // 【第一步：先确认"这篇文章对外可见"，再谈计数】
        //   库里的那份数据走缓存（含"草稿一律 404"的判断，见 PublishedArticleCache）。
        //   ⚠️ 这一步【必须排在 INCR 前面】：草稿和不存在的文章会在这里抛 404，
        //   如果先 INCR 再校验，那么别人拿 id 挨个探测草稿也会留下浏览痕迹 ——
        //   有一条用例专门盯着这件事（ArticleViewCountTest 的"草稿不该被计数"）。
        //   （第一版把它排在后面，全量测试立刻变红，正是这条用例抓出来的。）
        ArticleVO vo = publishedArticleCache.load(id);

        // 【第二步：记一次浏览（只记 Redis，不写库）】
        //   原来这里是一句 UPDATE article SET view_count = view_count + 1，
        //   也就是"详情页是读接口，却在每次请求里写一次库"。
        //   后果是热门文章被频繁打开时，这条 UPDATE 成为最热的写语句，
        //   而且并发访问同一篇会在同一行上排队等锁 —— 看文章被写操作拖慢。
        //   现在只做一次 Redis INCR（内存操作、无行锁），由定时任务批量落库。
        //
        // ⚠️ 【这行必须在缓存外面】
        //   它是带副作用的写操作，而缓存只能缓存"纯读且幂等"的结果。
        //   如果把它连同下面的合并一起缓起来，命中缓存的请求就不会再 INCR，
        //   浏览量会永远停在某个值上 —— 页面照常打开、数字照常显示，只是不再增长。
        viewCounter.increment(id);

        // 【第三步：合并"库里的快照 + Redis 里还没落库的增量"再返回】
        //   只返回库里的值的话，用户会看到"我刷新了但数字不动"——
        //   因为最新的计数还没到落库时间。
        //   顺序上"先 INCR 再读增量"，所以返回的数字【包含本次访问】，
        //   用户刷新能看到自己这一下被算进去了。
        //
        // 【这里直接改 vo 会不会污染缓存】
        //   不会。缓存用的是 Redis + JSON 序列化，每次读出来都是一个【新对象】，
        //   改它不影响缓存里的那份。
        //   ⚠️ 但如果将来有人把缓存换成进程内的（比如 Caffeine），
        //      读出来的就是同一个对象引用，这行赋值会把缓存里的值改掉。
        //      换缓存实现时记得先复制一份再改。
        long dbCount = vo.getViewCount() == null ? 0L : vo.getViewCount();
        vo.setViewCount((int) (dbCount + viewCounter.pending(id)));
        return vo;
    }

    @Override
    public ArticleVO getDetail(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }
        // 后台详情（含草稿）也带上标签：管理员的编辑界面要用它回显多选框
        return withTags(toVO(article, getCategoryName(article.getCategoryId())));
    }

    /**
     * 给单个 VO 补上标签。
     * 【为什么单个也要走 mapByArticleIds】那个方法内部是"IN + 内存分组"，
     * 传一个 id 时就是一次普通查询，没必要为单篇再写一套逻辑
     * （两处逻辑迟早会不一致，而这里的不一致表现是"列表有标签、详情没有"）。
     */
    private ArticleVO withTags(ArticleVO vo) {
        List<com.yigalaxy.yiguixingtu.tag.dto.TagVO> tags =
                tagService.mapByArticleIds(List.of(vo.getId())).get(vo.getId());
        if (tags != null) {
            vo.setTags(tags);
        }
        return vo;
    }

    /**
     * 站点统计（首页那三个数字）。
     *
     * 【为什么这个接口必须缓存】
     *   其中"总浏览量"是 SUM(view_count)，没有索引能走，得把全部已发布行扫一遍。
     *   首页又是访问量最大的页面 —— 不缓存的话，等于每次有人打开首页就扫一次全表。
     *   这是典型的"计算便宜但基数大"的聚合，正是适合缓存的形状。
     *
     * 【key 为什么只用版本号，不带别的条件】
     *   这个结果没有查询条件 —— 不管谁来问，答案都是全站那一份。
     *   所以 key 只需要区分"数据版本"：版本号一变，结果就作废。
     *   复用文章列表那套版本号（ArticleCacheVersion）而不是新造一个，
     *   是因为两者的失效时机完全一致（都是文章被写），
     *   共用之后"写文章"只需要 INCR 一次，两个缓存一起失效。
     *
     * 【TTL 为什么只有 60 秒，而列表缓存是 5 分钟】
     *   浏览量是【异步落库】的（见 ViewCountSyncTask，每 5 分钟批量写回库）。
     *   落库这件事不会推进缓存版本号（它不是"文章被写"），
     *   所以这个数字天然会有一段时间的滞后。
     *   把 TTL 定成 60 秒，等于"最多滞后一分钟"，用户几乎察觉不到；
     *   而列表缓存的内容（标题、摘要）只有作者发文时才会变，给 5 分钟完全够。
     *   两类数据的"变化频率"不同，所以 TTL 也不同 —— 这不是随手填的数字。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_STATS,
            key = "@articleCacheVersion.current()")
    public ArticleStatsVO stats() {
        // 一条 SQL 同时拿到"文章数"和"总浏览量"（两个聚合扫的是同一批行，不该分两次查）
        ArticleStatsVO aggregate = articleMapper.selectPublishedAggregate();

        // 分类表很小（本项目是个位数），单独 count 一次即可。
        // 传 null 表示不加额外条件，@TableLogic 会自动补上 deleted = 0。
        Long categoryCount = categoryMapper.selectCount(null);

        return new ArticleStatsVO(
                aggregate == null ? 0L : aggregate.getArticleCount(),
                aggregate == null ? 0L : aggregate.getViewCount(),
                categoryCount == null ? 0L : categoryCount);
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

        // 【写标签关联】必须在 insert 之后：MyBatis-Plus 这时候才把自增主键
        // 回填到 article 对象里，关联表的 article_id 要用它。
        // 同一个事务里，所以"文章建了、标签没写上"这种半成品不会出现。
        tagService.replaceArticleTags(article.getId(), form.getTagIds());

        // 【让列表缓存失效】把版本号 +1，之前缓存的列表瞬间全部作废
        // （为什么不是 @CacheEvict(allEntries = true)，见 ArticleCacheVersion 的类注释）
        articleCacheVersion.bump();

        // 【记一笔审计】放在这里而不是 Controller 里，有两个原因：
        //   ① 这个方法是 @Transactional 的，事件会绑在事务上 ——
        //      事务回滚时这条记录根本不会落库（'没真正发生的事不该被记'）
        //   ② 此刻 insert 已经执行完，拿得到新文章的 id
        operationLogRecorder.record(OperationAction.CREATE_ARTICLE, AuditTarget.ARTICLE,
                article.getId(), "标题=" + article.getTitle());

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

        // 改完内容要让列表缓存失效（标题/摘要/分类/置顶都可能变，列表显示会跟着变）
        articleCacheVersion.bump();

        // 3. 覆盖式地重写标签关联（把旧的全部清掉，再写入这次提交的那些）。
        //    标签不存在时它会先校验再抛异常（校验发生在任何写入之前，
        //    见 TagServiceImpl.replaceArticleTags 的注释），所以"保存失败"不会
        //    让用户丢掉原有标签；再加上同一个事务，内容改动也会一起撤销 ——
        //    不会出现"标题改了、标签没改"的半成品。
        tagService.replaceArticleTags(id, form.getTagIds());

        // 记一笔审计。detail 里带上"改成了什么标题"——
        // 只记"谁在什么时候改了哪篇"的话，事后想查"标题是被谁改成这样的"还是得去翻日志
        operationLogRecorder.record(OperationAction.UPDATE_ARTICLE, AuditTarget.ARTICLE, id,
                "标题=" + form.getTitle().trim());
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

        // 发布/下架会直接影响前台列表能不能看到这篇，必须让缓存失效
        articleCacheVersion.bump();

        // 记一笔审计。发布和下架都走这个方法，用 detail 区分开具体是哪个动作
        operationLogRecorder.record(OperationAction.UPDATE_ARTICLE_STATUS, AuditTarget.ARTICLE, id,
                status == 1 ? "发布" : "下架");
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

        // 【顺手清掉标签关联】文章的关联行留着没有任何意义：
        //   · 标签的文章数统计已经是 JOIN article 且过滤 deleted，不会算错
        //   · 但那些行会永远堆在关联表里（每删一篇文章就多几条垃圾），
        //     而且万一将来有人"恢复"一篇文章，会带出它删除时那一刻的旧标签 ——
        //     那不是恢复，是"穿越"。
        // 标签模块负责这件事的语义（它管着 article_tag 表），这里只调用。
        tagService.clearArticleTags(id);

        // 删掉的不能再出现在列表里
        articleCacheVersion.bump();

        // 记一笔审计。detail 里把标题也记下来：
        // 文章被逻辑删除之后，按 id 已经查不到标题了，只有快照能追溯"删的是哪一篇"
        operationLogRecorder.record(OperationAction.DELETE_ARTICLE, AuditTarget.ARTICLE, id,
                "标题=" + exist.getTitle());
    }

    // =================================================================
    //  私有工具方法
    // =================================================================

    // 【这里原来有一个 increaseViewCount(private)，已经删掉】
    //   它每次访问详情都会执行一条 UPDATE ... SET view_count = view_count + 1。
    //   那个 SQL 本身是对的（用数据库自增而不是"读出来加一写回去"，并发不丢计数），
    //   问题在于"这个写操作出现在了一个读接口里"。
    //   现在改成 ArticleViewCounter.increment()（只写 Redis，内存操作无行锁），
    //   由 ViewCountSyncTask 每 5 分钟批量落库 —— 写库的原子性由那边的
    //   setSql("view_count = view_count + ?") 保证，思路是一样的。

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
        List<ArticleVO> voList = articles.stream()
                .map(a -> toVO(a, categoryNameMap.get(a.getCategoryId())))
                .collect(Collectors.toList());

        // 4. 标签同理，也是"避免 N+1"：10 篇文章各查一次标签 = 10 次 SQL，
        //    这里用一条 IN 查询把整页的标签一次取回来，再在内存里按文章分组。
        //    （这段刻意放在分类之后：分类是一对一，标签是多对多，
        //      多对多必须走"批量查 + 内存分组"这条路，语义上更值得注释一句。）
        List<Long> articleIds = articles.stream().map(Article::getId).collect(Collectors.toList());
        Map<Long, List<com.yigalaxy.yiguixingtu.tag.dto.TagVO>> tagMap =
                tagService.mapByArticleIds(articleIds);
        for (ArticleVO vo : voList) {
            List<com.yigalaxy.yiguixingtu.tag.dto.TagVO> tags = tagMap.get(vo.getId());
            // 没有标签时保持 VO 里那个空的 ArrayList（而不是塞 null），前端可以直接遍历
            if (tags != null) {
                vo.setTags(tags);
            }
        }
        return voList;
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