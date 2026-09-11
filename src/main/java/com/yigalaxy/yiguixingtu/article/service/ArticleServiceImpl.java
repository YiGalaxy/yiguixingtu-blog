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
import com.yigalaxy.yiguixingtu.article.dto.ArticleArchiveVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleAttachmentForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleAttachmentVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleRssVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.cache.ArticleViewCounter;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.entity.ArticleAttachment;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleAttachmentMapper;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import com.yigalaxy.yiguixingtu.upload.UploadedFileCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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

    /**
     * 归档最多取多少篇。
     * 【为什么是常量而不是配置】它是一道"别把接口撑爆"的保险，不是业务参数；
     * 500 篇对个人博客等于全部，真到不够用的时候应当改成分年加载，而不是把这个数字调大。
     */
    private static final int ARCHIVE_MAX_ARTICLES = 500;

    /**
     * RSS 一次给多少篇。
     * 【为什么是 20】RSS 的通行惯例；正文是 longtext，给太多会让响应体和缓存条目
     * 膨胀到几 MB，而阅读器也不会翻到那么靠后。
     */
    private static final int RSS_MAX_ITEMS = 20;

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleCacheVersion articleCacheVersion;

    /**
     * 附件 Mapper：附件表的读写。
     * 附件不单独落库（保存文章时才写，见 V14 迁移脚本里"为什么不在上传时插一行"），
     * 所以它的入口只有本类里的"整体替换"与"级联删除"两处。
     */
    private final ArticleAttachmentMapper articleAttachmentMapper;

    /**
     * "已上传文件"这一侧的工具（URL ↔ 对象 key、从正文里找出引用的文件、按 key 删除）。
     * ⚠️ 它属于 upload 模块：文章的级联逻辑只表达业务语义
     * （"这个文件还有没有别人在用"），字符串处理与磁盘操作都在那个类里。
     */
    private final UploadedFileCleaner uploadedFileCleaner;

    /**
     * 上传配置：校验附件 URL 是否落在本项目的上传地址之内、附件大小上限，都要读它。
     * （base-url 与 attachment-max-size 都是可配置的，所以这类判断只可能在 Service 里，
     *   注解表达不了"跟着配置走"的规则 —— 见 ArticleAttachmentForm 的类注释。）
     */
    private final UploadProperties uploadProperties;

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
                              ArticleAttachmentMapper articleAttachmentMapper,
                              UploadedFileCleaner uploadedFileCleaner,
                              UploadProperties uploadProperties,
                              com.yigalaxy.yiguixingtu.tag.service.TagService tagService) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
        this.viewCounter = viewCounter;
        this.articleCacheVersion = articleCacheVersion;
        this.publishedArticleCache = publishedArticleCache;
        this.operationLogRecorder = operationLogRecorder;
        this.articleAttachmentMapper = articleAttachmentMapper;
        this.uploadedFileCleaner = uploadedFileCleaner;
        this.uploadProperties = uploadProperties;
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
        // 后台详情（含草稿）也带上标签与附件：
        // 管理员的编辑界面要用标签回显多选框，用附件回显那份可下载文件列表。
        // ⚠️ 附件只在这里填 —— 列表接口的 toVOList 刻意不填（N+1 且列表用不到，
        //    理由见 ArticleVO.attachments 的注释）
        return withAttachments(withTags(toVO(article, getCategoryName(article.getCategoryId()))));
    }

    /**
     * 给单个 VO 补上附件。
     *
     * 【为什么只给"单个"用】列表页要的是"一次 IN 查询把整页的标签取回来"
     * （见 toVOList），而附件只在详情里出现，所以只需要"按一篇文章查一次"。
     * 真要哪天列表也要显示附件，做法是仿照标签加一个批量方法，
     * 而不是在这里循环调用（那正是 N+1）。
     */
    private ArticleVO withAttachments(ArticleVO vo) {
        vo.setAttachments(ArticleAttachmentVO.fromEntities(listAttachments(vo.getId())));
        return vo;
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

    /**
     * 归档：已发布的文章按年月分组。
     *
     * 【为什么在 Java 里分组，而不是让 MySQL 用 DATE_FORMAT 分组】
     *   SQL 分组当然也能做（GROUP BY DATE_FORMAT(create_time, '%Y-%m')），
     *   但那样只能拿到"每个月几篇"，拿不到每个月里的【文章列表】——
     *   要列表就得 GROUP_CONCAT 拼字符串再在 Java 里拆开，很别扭。
     *   这里的做法是：一条查询按时间倒序取出这些文章（只要 id/title/create_time
     *   三个字段），在 Java 里顺序扫一遍分组 —— 顺序取出来之后，
     *   同月的文章天然是连续的，分组只是一次简单的遍历。
     *
     * 【为什么要有条数上限】归档页是"一眼看全部"，但它不该变成"一次拉全站"。
     *   上限 500 篇对个人博客来说等于"全部"，同时避免了哪天文章破万之后
     *   这个接口把内存和响应体撑爆（真到那个规模，归档页该改成分年加载）。
     *
     * 【缓存】和站点统计共用同一个版本号：归档内容只在文章被增删改时变，
     *   而那些操作都会推进版本号，所以不需要额外维护失效逻辑。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_ARCHIVE,
            key = "@articleCacheVersion.current()")
    public ArticleArchiveVO archive() {

        // 只取三个字段：归档页不需要摘要/封面/正文（几百篇的话差别就是几十 KB）
        List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getTitle, Article::getCreateTime)
                .eq(Article::getStatus, 1)
                .orderByDesc(Article::getCreateTime)
                .orderByDesc(Article::getId)
                .last("LIMIT " + ARCHIVE_MAX_ARTICLES));

        ArticleArchiveVO result = new ArticleArchiveVO();
        result.setTotal((long) articles.size());

        // 按"年-月"分组。因为 SQL 已经按时间倒序取出来了，
        // 同一个月的文章必然是连续的，所以只需要跟"上一个月的 key"比一次，
        // 不需要 Map + 排序那一套。
        ArticleArchiveVO.ArchiveMonth currentMonth = null;
        String currentKey = null;

        for (Article article : articles) {
            LocalDateTime time = article.getCreateTime();
            // 【create_time 可能为 null 吗】表上有默认值 CURRENT_TIMESTAMP，
            // 正常写入不会是 null；但历史数据/人工插入有可能是。
            // 这里直接跳过而不是崩掉：归档页少一篇文章，比整个接口 500 好得多。
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

    /**
     * RSS 订阅源的数据：最近若干篇已发布文章（含正文）。
     *
     * 【为什么固定取 20 篇】这是 RSS 的通行惯例（阅读器一次也消化不了更多），
     *   而且正文是 longtext —— 取 100 篇的话响应体和缓存条目会到几 MB 量级，
     *   而读者根本翻不到那么后面。
     *
     * 【为什么带缓存】RSS 阅读器会按固定间隔（常见 30 分钟～1 小时）来拉，
     *   而它取的是"最近 20 篇的正文"—— 不缓存的话每次都要把 20 个 longtext 从库里读出来。
     *   key 仍是文章缓存版本号：发文/改文/下架都会推进它，所以内容不会旧。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_RSS,
            key = "@articleCacheVersion.current()")
    public List<ArticleRssVO> rssItems() {
        // 只查需要的列：列表页要用的 cover/view_count/is_top 等在这里都没用
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

    // =================================================================
    //  写操作
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ArticleForm form, Long authorId) {

        checkCategory(form.getCategoryId());

        // 【附件先校验，再写入】与标签同一个原则（见 TagServiceImpl.replaceArticleTags）：
        // 校验跑在任何写库动作之前，"保存失败"就不会留下半成品。
        // 这里拿到的是一个已经归一化好的列表（null → 空列表），下面直接用。
        List<ArticleAttachmentForm> attachments = validateAttachments(form.getAttachments());

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

        // 【写附件】同样必须在 insert 之后：附件的 article_id 要用刚刚回填的自增主键。
        // 新建时没有"旧附件"，所以这次替换实际只是"插入"（不需要删任何文件）。
        replaceAttachments(article.getId(), attachments);

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

        // 【附件同样先校验】顺序与 create 一致：所有能提前判的都在写库之前判掉。
        // 这里多一层意义 —— 编辑时的"整体替换"会先删掉旧附件行，
        // 如果校验放到删除之后，一次不合法的提交就成了"先破坏再检查"
        // （虽然事务会回滚，但代码读起来就是那个意思，将来有人去掉事务注解就是真丢数据）
        List<ArticleAttachmentForm> attachments = validateAttachments(form.getAttachments());

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

        // 4. 覆盖式地重写附件（整体替换：库里剩下的必须正好等于这次提交的那些）。
        //    与标签那一步是同一套语义，区别只在于附件还要顺带把
        //    "这次没再提交的文件"从磁盘上删掉 —— 那段推理在 replaceAttachments 里。
        replaceAttachments(id, attachments);

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

    /**
     * 删除文章（逻辑删除），并级联清理它"独占"的文件。
     *
     * =====================================================================
     * 【这一段是本类里最微妙的一段逻辑，逐步说明为什么这么做】
     *
     * 一、要清理的东西有三类，它们的"归属"完全不同
     *   ① 附件行 + 附件的物理文件 —— 附件是"这篇文章的表的一部分"
     *      （存在 article_attachment 里，靠 article_id 归属），
     *      文章删了，这些行留着没有任何意义（谁也查不到它们了），
     *      行对应的文件也就成了永远没人能再访问到的垃圾。
     *   ② 正文里引用的图片（Markdown 里的 {@code ![](地址)}）——
     *      它们不在任何表里，唯一的"引用证据"就是正文那串 Markdown。
     *   ③ 封面图（article.cover）—— 是文章的一个字段。
     *   ②③ 与 ① 的本质区别：**同一个文件可能被别的文章共用**。
     *   站长为两篇文章选同一张封面、或者把同一张配图放进两篇文章，
     *   都是完全正常的事。所以 ②③ 属于"可能共享"，删之前必须先查清楚。
     *
     * 二、★ 怎么判断"一张图还有没有别的文章在用"（这是全篇的核心）
     *   把 URL 里 {@code /uploads/} 之后的那一段取出来（叫它 key，
     *   形如 {@code cover/2026/09/3f2b....png}），然后去问两件事：
     *     · article 表里（deleted = 0 且 id <> 本文）还有没有别的行的
     *       cover 或 content 里出现这段 key？
     *     · article_attachment 表里（article_id <> 本文）还有没有别的行的
     *       url 里出现这段 key？
     *   两个计数都为 0，才认为"这个文件是这篇文章独占的"，可以删。
     *
     *   为什么比对 key 而不是整条 URL：正文里的地址可能是绝对形式，
     *   也可能是相对形式（取决于当时 app.upload.base-url 的配置），
     *   用整条 URL 比会在两种形式混用时得出"没人引用"的结论 —— 然后误删。
     *   为什么用 LOCATE 而不是 LIKE：LIKE 的匹配串里 % 和 _ 是通配符，
     *   而 URL 里出现下划线很正常，那会让判断变得不准确。
     *   两条 SQL 分别在 ArticleMapper 与 ArticleAttachmentMapper 上，注释更细。
     *
     *   为什么"宁可多认、不可漏认"：判断错的代价是不对称的 ——
     *   多认一次引用 = 少删一个文件（浪费点磁盘，谁都发现不了），
     *   漏认一次 = 把别人文章里正在用的图删掉（页面裂图，文件不可恢复）。
     *   所以两处都用"任何形态的字符串包含"来判定，宁可保守。
     *
     * 三、为什么物理文件要在【事务提交之后】才删
     *   删除文件是【不可逆】的，而数据库事务可能回滚 ——
     *   如果把删文件写在事务里面，一次后面的写入失败（比如审计、标签清理出错）
     *   就会让文章"回来了"、图却没了：文章页上留着一堆裂图，
     *   而磁盘上再也找不回那些文件。反过来，先让事务提交成功、
     *   再删文件，最坏的结果只是"提交成功但文件没删掉"（留个垃圾文件，
     *   日志里有记录），两害相权取轻。
     *   实现见 deleteFilesAfterCommit：有事务就注册 afterCommit 回调，
     *   没有事务（方法被非事务地调用）就立刻删。
     *
     * 四、删除顺序（为什么是这个顺序）
     *   ① 先查文章 → 不存在直接报 404（不往下走任何清理）
     *   ② 先算出"这篇文章涉及哪些文件"（附件 + 正文图片 + 封面），
     *      ⚠️ 必须在删附件行【之前】查出来 —— 行删了就查不到 url 了
     *   ③ 逐个判断引用关系，收集"可以删的文件"
     *   ④ 删附件行、逻辑删除文章、清标签关联、失效缓存、记审计（都在事务里）
     *   ⑤ 事务提交后再删文件（见第三点）
     *
     * 五、与 music 模块的差别（免得读者以为是漏了）
     *   music 删歌时【刻意不删】磁盘上的 mp3（见 Music 实体注释），
     *   理由同样是"文件可能被共用 + 删除不可逆"。附件这里反过来做，
     *   是因为附件的文件是【一次性】的（UUID 命名，只由那次上传产生），
     *   而"不断编辑 + 100MB 量级"会让磁盘只涨不落 ——
     *   但前提仍然是"先确认没有别人在用"，也就是上面第二点。
     * =====================================================================
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        // ---- 第一步：把"这篇文章可能独占的文件"都收集起来 ----
        // 用 LinkedHashSet 去重（同一张图既是封面又在正文里是常态），
        // 顺序稳定方便排查。
        // ⚠️ 附件的 url 必须现在读出来：下面删完行就再也拿不到了
        List<ArticleAttachment> attachments = listAttachments(id);

        Set<String> candidateKeys = new LinkedHashSet<>();
        candidateKeys.addAll(uploadedFileCleaner.extractObjectKeys(exist.getContent()));
        String coverKey = uploadedFileCleaner.toObjectKey(exist.getCover());
        if (coverKey != null) {
            candidateKeys.add(coverKey);
        }
        for (ArticleAttachment attachment : attachments) {
            String key = uploadedFileCleaner.toObjectKey(attachment.getUrl());
            if (key != null) {
                candidateKeys.add(key);
            }
        }

        // ---- 第二步：逐个判断"还有没有别的文章在用"，只留下独占的 ----
        List<String> removableKeys = new ArrayList<>();
        for (String key : candidateKeys) {
            if (!referencedByOtherArticles(key, id)) {
                removableKeys.add(key);
            } else {
                // 留下来是有意为之，不是漏删 —— 日志要能证明这一点，
                // 否则将来站长问"为什么删了文章磁盘没变小"时无从回答
                log.info("文件仍被其它文章引用，跳过删除: key={}, 被删文章id={}", key, id);
            }
        }

        // ---- 第三步：删附件行（物理删除：这张表没有逻辑删除，理由见 V14）----
        deleteAttachments(id);

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
                "标题=" + exist.getTitle() + attachmentAuditSuffix(attachments));

        // ---- 第四步：提交之后再删物理文件（时机与理由见上面第三点）----
        deleteFilesAfterCommit(removableKeys);
    }

    /** 审计 detail 的附件片段：让"删掉的文章带了几个附件"也留在审计里（没有附件时为空串） */
    private String attachmentAuditSuffix(List<ArticleAttachment> attachments) {
        return attachments.isEmpty() ? "" : "，附件数=" + attachments.size();
    }

    // =================================================================
    //  附件（article_attachment）—— 校验 / 整体替换 / 查询 / 级联清理
    // =================================================================

    /**
     * 校验表单里的附件列表，并归一化成"可以直接写库"的列表。
     *
     * 【为什么要归一化（null → 空列表）】表单里不传 attachments 与传空数组
     * 在业务上是同一件事（这篇文章没有附件，见 ArticleForm.attachments 的注释），
     * 在调用处统一成"一个可能为空的列表"，后面的代码就只需要处理一种情况。
     *
     * 【三条规则的判断依据，逐条说明为什么不在 DTO 注解里做】
     *   ① 数量 ≤ 20：这条其实注解里也写了（@Size(max = 20)，为了让 Swagger
     *      与前端能看到限制、也为了在 Controller 那一层就失败），这里【再判一次】
     *      是因为 Service 是"所有调用路径的必经之地" —— 将来若有脚本/导入工具
     *      直接调 Service，注解那一层就绕过去了（项目里对"至少填一个地址"
     *      那条规则用的是同一个理由，见 ProjectServiceImpl）。
     *   ② size ≤ app.upload.attachment-max-size：必须读配置，
     *      注解是编译期常量，写死就会与配置变成两处真相（详见 ArticleAttachmentForm 注释）。
     *   ③ url 必须落在本项目的上传地址前缀之内：同样依赖配置（base-url）。
     *      ⚠️ 这条是安全规则，不是格式校验：不判的话，任何人都能把
     *      {@code https://别人的站/x.exe} 填成"本站文章的附件"，
     *      它会以前台文章的名义展示给读者 —— 等于拿我们的域名替对方背书。
     *      实现直接复用 UploadedFileCleaner.toObjectKey：它对"是不是本项目的
     *      上传地址"的判断规则（绝对地址以 base-url 开头 / 站内相对地址）与上传时
     *      完全一致，两处只留一份规则就不会出现"上传不进去、却能保存成功"的怪状态。
     *
     * 【为什么一条不合法就整份拒绝，而不是"跳过坏的那条"】
     *   跳过的话，用户看到的是"保存成功、但附件少了一个" ——
     *   这种"部分成功"最难排查（用户不知道是哪个、为什么）。
     *   整份拒绝 + 明确提示"第几个附件哪里不对"，用户改一下就能过。
     *   错误信息里带上序号，是因为一次提交最多 20 条，只说"附件地址不合法"
     *   用户要自己一条条对。
     *
     * @param attachments 表单里的附件列表（可为 null）
     * @return 归一化后的列表（不可为 null；顺序保持提交顺序 —— 前端列表的顺序就是用户看到的顺序）
     * @throws BusinessException 任何一条不合法时（PARAM_ERROR，HTTP 200 + body.code = 400）
     */
    private List<ArticleAttachmentForm> validateAttachments(List<ArticleAttachmentForm> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Collections.emptyList();
        }

        final int maxCount = 20;
        if (attachments.size() > maxCount) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "附件最多 " + maxCount + " 个，当前 " + attachments.size() + " 个");
        }

        long maxSize = uploadProperties.getAttachmentMaxSize().toBytes();

        for (int i = 0; i < attachments.size(); i++) {
            ArticleAttachmentForm item = attachments.get(i);
            // 注解校验（@Valid 的嵌套校验）先跑过了一遍，但 Service 被直接调用时没有那一层，
            // 所以这里对 null 也兜一手：不然下面读 item.getUrl() 会 NPE（500，
            // 而正确答案是 400 "第 N 个附件不合法"）
            if (item == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "第 " + (i + 1) + " 个附件不合法");
            }

            if (!StringUtils.hasText(item.getName()) || item.getName().trim().length() > 100) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "第 " + (i + 1) + " 个附件的名称不能为空且最长 100 字");
            }

            if (item.getSize() == null || item.getSize() < 0) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "第 " + (i + 1) + " 个附件的大小不合法");
            }
            if (item.getSize() > maxSize) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "第 " + (i + 1) + " 个附件超过大小上限（"
                                + uploadProperties.getAttachmentMaxSize().toMegabytes() + "MB）");
            }

            if (uploadedFileCleaner.toObjectKey(item.getUrl()) == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "第 " + (i + 1) + " 个附件的地址不是本项目的上传地址，请先通过上传接口获取");
            }
        }

        return attachments;
    }

    /** 查一篇文章的附件（按 id 升序 = 提交顺序，因为 id 是自增的） */
    private List<ArticleAttachment> listAttachments(Long articleId) {
        return articleAttachmentMapper.selectList(new LambdaQueryWrapper<ArticleAttachment>()
                .eq(ArticleAttachment::getArticleId, articleId)
                .orderByAsc(ArticleAttachment::getId));
    }

    /** 删掉一篇文章的全部附件行（物理删除，理由见 V14 与 ArticleAttachment 的注释） */
    private void deleteAttachments(Long articleId) {
        articleAttachmentMapper.delete(new LambdaQueryWrapper<ArticleAttachment>()
                .eq(ArticleAttachment::getArticleId, articleId));
    }

    /**
     * 【整体替换】一篇文章的附件：库里剩下的 = 这次提交的那些。
     *
     * 【为什么是"先全删再全插"，而不是"对比差异后增删改"】
     *   与标签的覆盖式语义同一条理由（见 ArticleForm.attachments 的注释）：
     *   用户看到的列表就是最终状态，最不容易出错。
     *   而且附件这一行【没有任何字段是可改的】：name/url/size 三个值都来自
     *   那一次上传，改了任何一个都意味着"换了一个文件"——
     *   所以"差异对比"在这里根本没有意义：能对比的只有 url 是否相同，
     *   而 url 相同就意味着整行相同。既然如此，全删全插反而更简单、更不会有 bug。
     *   （代价是每次保存都会换一批 id。附件的 id 不对外暴露 —— VO 里没有它，
     *   所以外部看不到任何变化。）
     *
     * 【被移除的文件什么时候删、怎么判】见方法末尾那一段与 remove 的长注释。
     *
     * @param articleId   文章 id
     * @param attachments 已经过 validateAttachments 的列表（不可为 null）
     */
    private void replaceAttachments(Long articleId, List<ArticleAttachmentForm> attachments) {

        // ---- 第一步：先把旧行读出来 ----
        // ⚠️ 必须【先读后删】：等删完行，那些 url 就查不出来了，
        // 也就无法判断"哪个文件这次没再提交、可以删掉"——
        // 那样每次编辑都会在磁盘上留下一份没人引用的旧附件（100MB 量级，很痛）
        List<ArticleAttachment> oldAttachments = listAttachments(articleId);
        List<String> oldKeys = new ArrayList<>();
        for (ArticleAttachment old : oldAttachments) {
            String key = uploadedFileCleaner.toObjectKey(old.getUrl());
            if (key != null) {
                oldKeys.add(key);
            }
        }

        // ---- 第二步：删旧行、插新行（同一个事务里，不会出现"半份附件列表"）----
        deleteAttachments(articleId);
        for (ArticleAttachmentForm item : attachments) {
            ArticleAttachment entity = new ArticleAttachment();
            entity.setArticleId(articleId);
            // 名字再 trim 一次：校验放行了"前后有空格"的名字（长度判断用的就是 trim 后的长度），
            // 存进库里也应当是 trim 后的 —— 否则前端显示时会出现看不见的空格缩进
            entity.setName(item.getName().trim());
            entity.setUrl(item.getUrl());
            entity.setSize(item.getSize());
            // createTime 不赋值：数据库列有 DEFAULT CURRENT_TIMESTAMP，
            // 而 MyBatis-Plus 默认不把 null 字段写进 INSERT，默认值就会生效
            articleAttachmentMapper.insert(entity);
        }

        // ---- 第三步：算出"这次没再提交的旧文件"，确认没人共用后删掉 ----
        Set<String> newKeys = new LinkedHashSet<>();
        for (ArticleAttachmentForm item : attachments) {
            String key = uploadedFileCleaner.toObjectKey(item.getUrl());
            if (key != null) {
                newKeys.add(key);
            }
        }

        List<String> removableKeys = new ArrayList<>();
        for (String oldKey : new LinkedHashSet<>(oldKeys)) {
            // 这次仍然提交了的：留着（用户只是保存了一次，附件没动）
            if (newKeys.contains(oldKey)) {
                continue;
            }
            // 别的文章还在用同一个文件：绝不能删（比如两篇文章共用了同一个附件）
            if (referencedByOtherArticles(oldKey, articleId)) {
                log.info("被移除的附件仍被其它文章引用，跳过删除: key={}, 文章id={}", oldKey, articleId);
                continue;
            }
            removableKeys.add(oldKey);
        }

        // 删除时机与 remove 里完全一致：事务提交之后才动磁盘
        // （理由见 remove 的长注释第三点：删除不可逆，而事务可能回滚）
        deleteFilesAfterCommit(removableKeys);
    }

    /**
     * 这个文件还有没有【别的文章】在用？
     *
     * 两张表各查一次（文章的正文/封面 +文章的附件），
     * 任何一个计数大于 0 就算"还有人用"。
     * 两条 SQL 的写法、为什么用 LOCATE、为什么比对 key 而不是整条 URL，
     * 都写在 ArticleMapper.countOthersReferencing 与
     * ArticleAttachmentMapper.countOthersReferencing 上。
     *
     * ⚠️ 判断偏保守（宁可认为"还有人用"）：任何形态的字符串包含都算引用。
     *   理由见 remove 的长注释第二点 —— 误删不可逆，而多留一个文件只是浪费磁盘。
     *
     * @param key       上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @param articleId 当前正在处理的文章 id（它自己不算"别人"）
     */
    private boolean referencedByOtherArticles(String key, Long articleId) {
        long inArticles = articleMapper.countOthersReferencing(articleId, key);
        if (inArticles > 0) {
            return true;
        }
        return articleAttachmentMapper.countOthersReferencing(articleId, key) > 0;
    }

    /**
     * 在【当前事务提交之后】删除这些物理文件；没有事务时立刻删。
     *
     * 【为什么要等提交】见 remove 的长注释第三点：
     *   删除文件不可逆，而数据库事务可能回滚。先提交、再删文件，
     *   最坏的结果只是"留下一个没被引用的文件"（日志里有记录、可以人工清理）；
     *   反过来则可能出现"文章还在、图却没了"的裂图，而且无法恢复。
     *
     * 【为什么用 TransactionSynchronizationManager 而不是 @TransactionalEventListener】
     *   项目里的操作审计用的是"发事件 + @TransactionalEventListener(AFTER_COMMIT)"
     *   （见 audit 包），那套更解耦，但它有两个这里不需要的属性：
     *     ① 它是异步的（@Async）：审计晚几毫秒没关系，但文件删除要的是
     *        "确定发生过"，同步执行才好断言、出错也好记日志
     *     ② 事件的接收方是按类型广播的：这里只是"提交后干一件事"，
     *        没必要为此定义事件类型 + 监听器两个类
     *   TransactionSynchronization 是 Spring 提供的同一个机制的更轻形式，
     *   语义完全一致（提交后回调），代码就在调用点旁边，读起来是连贯的。
     *
     * 【为什么有"没有事务就立刻删"这个分支】
     *   本类的 create/update/remove 都标了 @Transactional，正常不会走到它。
     *   但"方法被非事务地调用"是完全可能的（将来有人把注解去掉、
     *   或者有别的入口直接调 Service）。如果没有这个分支，
     *   那种情况下文件就永远不会被删，而且是【静默】的 ——
     *   判断标准很简单：有事务同步就注册（事务语义优先），
     *   没有就当场做（总比什么都不做强）。
     *
     * 【失败了会怎样】uploadedFileCleaner.deleteAll 会逐个 try-catch，
     *   删不掉的只记日志、不往上抛：这时候事务已经提交、接口也已经返回，
     *   抛出去没有任何人能补救，只会把一次成功的操作变成 500
     *   （用户以为没删成功，重试一次收到"文章不存在"）。
     *
     * @param objectKeys 要删除的对象 key（可为空/为 null，方法会自己兜住）
     */
    private void deleteFilesAfterCommit(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        // 复制一份：回调是在事务提交那一刻执行的，那时入参列表可能已经被复用/清空。
        // 这个列表是本地变量、不会跨请求共享，但"传给异步/延迟执行的代码前先复制"
        // 是一条值得坚持的习惯（项目里 IdempotencyService 也是同样的处理）
        List<String> snapshot = List.copyOf(objectKeys);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    uploadedFileCleaner.deleteAll(snapshot);
                }
            });
        } else {
            log.info("当前没有活动事务，立即删除文件（本次共 {} 个）", snapshot.size());
            uploadedFileCleaner.deleteAll(snapshot);
        }
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