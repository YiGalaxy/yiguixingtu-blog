package com.yigalaxy.yiguixingtu.tag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.tag.dto.TagForm;
import com.yigalaxy.yiguixingtu.tag.dto.TagVO;
import com.yigalaxy.yiguixingtu.tag.entity.Tag;
import com.yigalaxy.yiguixingtu.tag.mapper.ArticleTagMapper;
import com.yigalaxy.yiguixingtu.tag.mapper.TagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 标签服务实现
 *
 * 【这个类里三件需要想清楚的事】
 *
 * ① 为什么标签的写操作也要推进【文章】的缓存版本号
 *    标签列表里带着"每个标签下有几篇已发布文章"，这个数会随
 *    "建标签 / 删标签 / 给文章打标签 / 文章发布下架"而变。
 *    这些操作本来就都会推进 article:page:version（文章那边）——
 *    让标签列表也用同一个版本号做 key，就等于"文章一变，标签列表跟着失效"，
 *    不需要再维护第二套失效逻辑。
 *    （少维护一套机制，就少一处"改了这边忘了那边"的可能。）
 *
 * ② 为什么删标签要连带删关联（而且必须在一个事务里）
 *    标签用【物理删除】（理由见 V5 迁移脚本）。如果只删 tag 表那一行、
 *    不清理 article_tag，就会留下"指向不存在标签"的关联行：
 *    统计标签文章数时会统计到一个查不到的标签上。
 *    两步必须在同一个事务里 —— 中间失败就会留下悬空关联。
 *
 * ③ 为什么"标签名重复"要查一次、还要 catch 一次 DuplicateKeyException
 *    先查一次是为了给出友好提示（99% 的情况在这里就拦住了）；
 *    但两个请求同时新建同名标签时，两边都会查到"不存在"，然后一起 INSERT，
 *    其中一个必然撞唯一索引 —— 那是【数据库】给出的最终保证。
 *    把 DuplicateKeyException 翻译成同一个业务错误，
 *    用户看到的就是"标签名已存在"，而不是一个 500。
 *    （这就是"应用层校验 + 数据库兜底"的纵深防御，两边都要有。）
 * =====================================================================
 */
@Slf4j
@Service
public class TagServiceImpl implements TagService {

    /** 标签名的长度上限，与 DTO 校验、数据库列长度保持一致 */
    private static final int NAME_MAX_LENGTH = 30;

    private final TagMapper tagMapper;
    private final ArticleTagMapper articleTagMapper;

    /**
     * 缓存版本号。标签列表的缓存 key 里带着它，
     * 所以任何标签写操作都要 bump 一下（理由见类注释 ①）。
     */
    private final ArticleCacheVersion articleCacheVersion;

    /** 操作审计：标签是后台的写操作，谁建的、谁删的都要留痕 */
    private final OperationLogRecorder operationLogRecorder;

    public TagServiceImpl(TagMapper tagMapper,
                          ArticleTagMapper articleTagMapper,
                          ArticleCacheVersion articleCacheVersion,
                          OperationLogRecorder operationLogRecorder) {
        this.tagMapper = tagMapper;
        this.articleTagMapper = articleTagMapper;
        this.articleCacheVersion = articleCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读
    // =================================================================

    /**
     * 前台标签列表（带已发布文章数）。
     *
     * 【缓存 key 为什么是 {@code @articleCacheVersion.current()}】
     *   bean 名 articleCacheVersion 由 Spring 按类名首字母小写推导；
     *   每次算 key 时现取一次版本号，所以刚 bump 过的版本立刻生效。
     *   这里没有别的查询条件 —— 全站就这一份标签列表。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_TAG_LIST, key = "@articleCacheVersion.current()")
    public List<TagVO> listPublished() {
        return buildList();
    }

    @Override
    public List<TagVO> listAll() {
        return buildList();
    }

    /**
     * 真正的查询：一条查标签、一条统计文章数，然后在内存里合并。
     *
     * 【为什么是两条 SQL 而不是一条 LEFT JOIN】
     *   LEFT JOIN + GROUP BY 也能出结果，但那样每个标签的 name/sort
     *   会在结果里重复 N 遍，还得在 Java 里去重；而两条查询各自都很直白：
     *   一条"标签有哪些"，一条"每个标签有几篇文章"。
     *   标签总数是几十个量级，多一次往返的代价可以忽略。
     */
    private List<TagVO> buildList() {
        List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .orderByAsc(Tag::getSort)
                .orderByAsc(Tag::getId));
        if (tags.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Long> countByTagId = publishedCountByTagId();

        List<TagVO> result = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            TagVO vo = new TagVO();
            vo.setId(tag.getId());
            vo.setName(tag.getName());
            vo.setSort(tag.getSort());
            // 没有文章数的标签要是 0，而不是 null —— 前端可以直接参与排序和展示，
            // 不用再写一层 "?? 0"
            vo.setArticleCount(countByTagId.getOrDefault(tag.getId(), 0L));
            result.add(vo);
        }
        return result;
    }

    /**
     * 每个标签下【已发布】的文章数。
     *
     * 【为什么把 Map 的 key 转成 Long】MyBatis 返回的 Map 里，
     * tagId 这一列的类型取决于 JDBC 驱动与 MyBatis 的映射（可能是 Long，
     * 也可能是 Integer/BigInteger）。直接 {@code get(tag.getId())} 有可能因为
     * 类型不同而永远取不到值（Long(1) != Integer(1)）——
     * 这类 bug 的表现是"文章数永远是 0"，而且不报错。
     * 所以统一按字符串转一次，键的类型就固定了。
     */
    private Map<Long, Long> publishedCountByTagId() {
        Map<Long, Long> map = new LinkedHashMap<>();
        for (Map<String, Object> row : tagMapper.countPublishedByTag()) {
            Object tagId = row.get("tagId");
            Object total = row.get("total");
            if (tagId == null || total == null) {
                continue;
            }
            map.put(Long.valueOf(String.valueOf(tagId)), Long.valueOf(String.valueOf(total)));
        }
        return map;
    }

    @Override
    public Map<Long, List<TagVO>> mapByArticleIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 去重：同一篇文章在参数里出现两次没有意义，还会让 IN 列表变长
        List<Long> ids = articleIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        // 【为什么用 LinkedHashMap】保持 SQL 里 ORDER BY 的顺序（sort、id），
        // 让同一篇文章每次拿到的标签顺序稳定 —— 否则标签在页面上会随机换位置
        Map<Long, List<TagVO>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : tagMapper.selectTagsByArticleIds(ids)) {
            Long articleId = toLong(row.get("articleId"));
            Long tagId = toLong(row.get("tagId"));
            if (articleId == null || tagId == null) {
                continue;
            }
            TagVO vo = new TagVO();
            vo.setId(tagId);
            vo.setName((String) row.get("name"));
            vo.setSort(toInteger(row.get("sort")));
            // 这里的 articleCount 刻意留空：文章详情/列表里的标签只需要 id 和名字，
            // 为每个标签再算一次"它下面有几篇文章"要多一条聚合查询，纯属浪费
            result.computeIfAbsent(articleId, k -> new ArrayList<>()).add(vo);
        }
        return result;
    }

    @Override
    public List<Long> listArticleIdsByTagId(Long tagId) {
        if (tagId == null) {
            return Collections.emptyList();
        }
        return articleTagMapper.selectArticleIdsByTagId(tagId);
    }

    // =================================================================
    //  二、写（标签自身）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(TagForm form) {
        String name = normalizeName(form);

        // 先查一次：绝大多数重复在这里就被拦住，能给出清晰的提示
        checkNameDuplicate(name, null);

        Tag tag = new Tag();
        tag.setName(name);
        tag.setSort(form.getSort() == null ? 0 : form.getSort());

        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            // 并发下的兜底：两个请求同时通过上面的检查、一起 INSERT，
            // 其中一个必然撞 uk_name。翻译成业务错误，而不是让用户看到 500
            throw new BusinessException(ResultCode.TAG_NAME_EXISTS);
        }

        // 标签列表的缓存里带着文章数（新标签是 0），要让旧缓存失效
        articleCacheVersion.bump();

        // 记一笔审计：后台的增删改都要留痕
        operationLogRecorder.record(OperationAction.CREATE_TAG, AuditTarget.TAG, tag.getId(),
                "标签=" + name);

        log.info("新建标签: id={}, name={}", tag.getId(), name);
        return tag.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, TagForm form) {
        Tag exist = requireTag(id);
        String name = normalizeName(form);

        checkNameDuplicate(name, id);

        Tag update = new Tag();
        update.setId(id);
        update.setName(name);
        update.setSort(form.getSort() == null ? exist.getSort() : form.getSort());

        try {
            tagMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.TAG_NAME_EXISTS);
        }

        articleCacheVersion.bump();

        // detail 里记下"从什么改成什么"：只记新名字的话，
        // 事后根本看不出这是一次改名还是别的调整（和用户、角色那几条一个道理）
        String detail = name.equals(exist.getName())
                ? "标签=" + name
                : "标签 " + exist.getName() + " → " + name;
        operationLogRecorder.record(OperationAction.UPDATE_TAG, AuditTarget.TAG, id, detail);

        log.info("编辑标签: id={}, {} -> {}", id, exist.getName(), name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Tag exist = requireTag(id);

        // 【顺序很重要】先删关联、再删标签自己。
        // 反过来的话，中间失败就会留下"标签已经没了、关联还在"的悬空数据。
        // 两步在同一个事务里，所以要么都成功要么都回滚。
        int removedLinks = articleTagMapper.deleteByTagId(id);
        tagMapper.deleteById(id);   // 物理删除（tag 表没有 deleted 字段，见 V5）

        articleCacheVersion.bump();

        // detail 里留下标签名：标签是物理删除，删完之后表里再也查不到这个名字，
        // 只有审计记录能回答"当时删的是哪个标签"
        operationLogRecorder.record(OperationAction.DELETE_TAG, AuditTarget.TAG, id,
                "标签=" + exist.getName() + "（同时解除 " + removedLinks + " 条文章关联）");

        log.info("删除标签: id={}, name={}, 解除关联 {} 条", id, exist.getName(), removedLinks);
    }

    // =================================================================
    //  三、写（文章与标签的关联）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceArticleTags(Long articleId, List<Long> tagIds) {

        // 先清空旧关联（覆盖式语义：前端提交什么，最终就是什么）
        articleTagMapper.deleteByArticleId(articleId);

        Set<Long> distinctTagIds = (tagIds == null ? Collections.<Long>emptyList() : tagIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (distinctTagIds.isEmpty()) {
            // 清空标签也是一个合法操作（用户把标签全部取消），到此为止
            articleCacheVersion.bump();
            return;
        }

        // 【为什么要校验标签存在】
        //   前端可能拿着一份过期数据提交（比如某个标签刚被别的管理员删了）。
        //   不校验的话，关联行会指向一个不存在的标签 —— 那行数据永远查不出名字，
        //   看起来就是"标签列表里少了一个"这种莫名其妙的现象。
        //   这里宁可让本次保存失败并提示刷新，也不要写入脏关联。
        long found = tagMapper.selectCount(new LambdaQueryWrapper<Tag>().in(Tag::getId, distinctTagIds));
        if (found != distinctTagIds.size()) {
            throw new BusinessException(ResultCode.TAG_NOT_FOUND, "有标签已不存在，请刷新页面后重试");
        }

        articleTagMapper.insertBatch(articleId, distinctTagIds);

        // 标签的文章数变了（新打上的 +1、去掉的 -1），让标签列表缓存失效
        articleCacheVersion.bump();
    }

    // =================================================================
    //  私有工具
    // =================================================================

    /** 标签名归一化：去掉首尾空格并卡长度 */
    private String normalizeName(TagForm form) {
        String name = form.getName() == null ? "" : form.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标签名不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标签名最长 " + NAME_MAX_LENGTH + " 字");
        }
        return name;
    }

    /**
     * 标签名查重。
     *
     * @param excludeId 编辑时要排除自己，否则"不改名字直接保存"会被自己拦住
     */
    private void checkNameDuplicate(String name, Long excludeId) {
        Tag exist = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (exist != null && !exist.getId().equals(excludeId)) {
            throw new BusinessException(ResultCode.TAG_NAME_EXISTS);
        }
    }

    private Tag requireTag(Long id) {
        Tag tag = id == null ? null : tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(ResultCode.TAG_NOT_FOUND);
        }
        return tag;
    }

    private Long toLong(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private Integer toInteger(Object value) {
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }
}
