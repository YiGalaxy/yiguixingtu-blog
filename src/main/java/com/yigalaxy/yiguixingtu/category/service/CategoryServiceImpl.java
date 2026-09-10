package com.yigalaxy.yiguixingtu.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.category.dto.CategoryForm;
import com.yigalaxy.yiguixingtu.category.dto.CategoryVO;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 分类服务实现
 *
 * 【分类与标签的差别，决定了这里的两处不同】
 *   ① 分类是【一对一】（一篇文章属于一个分类，article.category_id），
 *      标签是【多对多】（靠 article_tag 关联表）。
 *      所以删分类时要检查"还有几篇文章在用"，而删标签只需清理关联行。
 *   ② category 表【有】唯一索引 uk_name，tag 表也有 ——
 *      于是两者都要处理"逻辑删除 + 唯一索引"这个坑。
 *      处理方式不同是有原因的：
 *        · 标签选择【物理删除】（名字会被污染成 "技术#deleted#7"，不值得）
 *        · 分类沿用【删除时改名】（和 user 表同一套解法）——
 *          因为它已经有 deleted 列、实体上也有 @TableLogic，
 *          而且分类数量很少（十几个），改名带来的噪音有限；
 *          换成物理删除要动表结构与实体，收益不成正比
 *      两处的选择都写在各自迁移脚本/实体的注释里，免得后人以为"不一致是忘了"。
 *
 * 【为什么分类列表要缓存】
 *   前端首页改成 SSR 之后，每次服务端渲染都会请求一次 /category/list；
 *   而这个列表【只在分类被增删改时才变】—— 正是"读多写极少"的完美缓存对象。
 *   缓存 key 里带的是文章缓存版本号（写操作会推进它），
 *   和标签列表共用同一套失效机制，不需要再维护第二份逻辑。
 * =====================================================================
 */
@Slf4j
@Service
public class CategoryServiceImpl implements CategoryService {

    /** 分类名长度上限，与 DTO 校验、数据库列长度保持一致 */
    private static final int NAME_MAX_LENGTH = 50;

    /** 分类描述长度上限，与数据库列长度保持一致 */
    private static final int DESCRIPTION_MAX_LENGTH = 255;

    private final CategoryMapper categoryMapper;

    /** 缓存版本号：分类改名会让文章列表里的分类名变旧，所以要一起失效 */
    private final ArticleCacheVersion articleCacheVersion;

    private final OperationLogRecorder operationLogRecorder;

    public CategoryServiceImpl(CategoryMapper categoryMapper,
                               ArticleCacheVersion articleCacheVersion,
                               OperationLogRecorder operationLogRecorder) {
        this.categoryMapper = categoryMapper;
        this.articleCacheVersion = articleCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    /**
     * 全部分类（带缓存）。
     *
     * 【缓存 key 为什么只有版本号】这个结果没有查询条件，
     * 不管谁来问答案都是同一份；所以 key = 版本号就够，
     * 而版本号一变（任何写操作）旧 key 就再也拼不出来，等于瞬间失效。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_CATEGORY_LIST, key = "@articleCacheVersion.current()")
    public List<CategoryVO> listAll() {
        // 排序规则：先按 sort 升序（人工指定的优先级），
        //           sort 相同时再按 id 升序（保证结果稳定，不会每次查出来顺序乱跳）
        List<Category> list = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .orderByAsc(Category::getSort)
                        .orderByAsc(Category::getId));

        return list.stream().map(this::toVO).collect(Collectors.toList());
    }

    // =================================================================
    //  写操作（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategoryForm form) {
        String name = normalizeName(form);
        checkNameDuplicate(name, null);

        Category category = new Category();
        category.setName(name);
        category.setDescription(normalizeDescription(form.getDescription()));
        category.setSort(form.getSort() == null ? 0 : form.getSort());

        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException e) {
            // 并发下的兜底：两个请求同时通过上面的查重、一起 INSERT，
            // 其中一个必然撞 uk_name。翻译成业务错误，而不是让用户看到 500
            // （和 TagServiceImpl 里同一个处理，见那边的注释）
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS);
        }

        articleCacheVersion.bump();
        operationLogRecorder.record(OperationAction.CREATE_CATEGORY, AuditTarget.CATEGORY,
                category.getId(), "分类=" + name);

        log.info("新建分类: id={}, name={}", category.getId(), name);
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategoryForm form) {
        Category exist = requireCategory(id);
        String name = normalizeName(form);
        checkNameDuplicate(name, id);

        Category update = new Category();
        update.setId(id);
        update.setName(name);
        update.setDescription(normalizeDescription(form.getDescription()));
        update.setSort(form.getSort() == null ? exist.getSort() : form.getSort());

        try {
            categoryMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS);
        }

        // 【分类改名必须让缓存失效】文章列表的 VO 里带着 categoryName，
        // 那是从分类表里读出来拼进去的 —— 不推进版本号的话，
        // 前台会一直显示旧名字（直到 TTL 到期），用户会以为"改名没生效"
        articleCacheVersion.bump();

        String detail = name.equals(exist.getName())
                ? "分类=" + name
                : "分类 " + exist.getName() + " → " + name;
        operationLogRecorder.record(OperationAction.UPDATE_CATEGORY, AuditTarget.CATEGORY, id, detail);

        log.info("编辑分类: id={}, {} -> {}", id, exist.getName(), name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Category exist = requireCategory(id);

        // 【关键一步：还有文章在用就不许删】
        //   分类被逻辑删除之后，那些文章仍然引用着它的 id（article.category_id），
        //   而分类查询已经看不到它了 —— 结果就是文章在列表里"没有分类名"，
        //   文章自己还查不出原因。这种数据不一致只能靠人去数据库里对照，非常难查。
        //   所以这里直接拒绝，并告诉用户"还有几篇文章在用"，
        //   让他先把那些文章改到别的分类（或删掉）再回头删分类。
        long inUse = categoryMapper.countArticlesByCategoryId(id);
        if (inUse > 0) {
            throw new BusinessException(ResultCode.CATEGORY_IN_USE,
                    "还有 " + inUse + " 篇文章在用这个分类，请先调整这些文章的分类");
        }

        // 【逻辑删除 + 唯一索引的坑：删除时必须把名字让出来】
        //   这一行和 UserServiceImpl.removeUser 里的处理是同一个道理：
        //   删掉的行物理上还在，uk_name 仍然占着这个名字 ——
        //   于是"删掉分类 技术 再新建一个 技术"会直接撞 Duplicate entry，
        //   而带 @TableLogic 的查重语句又看不见那行已删除的数据。
        //   解决办法：删除时把名字改写成 原名#deleted#id，既保留历史，又释放名字。
        //   （标签当初选了物理删除，所以没有这个问题 —— 两处的取舍见类注释。）
        Category rename = new Category();
        rename.setId(id);
        rename.setName(exist.getName() + "#deleted#" + id);
        categoryMapper.updateById(rename);

        categoryMapper.deleteById(id);   // @TableLogic：UPDATE category SET deleted = 1

        articleCacheVersion.bump();

        // detail 里留下删除前的名字快照：改名之后，从 category 表里已经看不出
        // 这个分类原来叫什么了（和删除用户时留用户名快照同一个考虑）
        operationLogRecorder.record(OperationAction.DELETE_CATEGORY, AuditTarget.CATEGORY, id,
                "分类=" + exist.getName());

        log.info("删除分类: id={}, name={}", id, exist.getName());
    }

    // =================================================================
    //  私有工具
    // =================================================================

    /** 分类名归一化：去首尾空格 + 卡长度 */
    private String normalizeName(CategoryForm form) {
        String name = form.getName() == null ? "" : form.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分类名不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分类名最长 " + NAME_MAX_LENGTH + " 字");
        }
        return name;
    }

    /**
     * 描述归一化。
     * 【为什么空串要变成 null】描述是可选的，前端很可能把空输入框提交成 ""。
     * 存 null 和存 "" 在数据库里是两个值，展示上的区别却是"没有描述"——
     * 统一成 null 可以少一种状态，也让"有没有填"这件事没有歧义。
     */
    private String normalizeDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.length() > DESCRIPTION_MAX_LENGTH
                ? trimmed.substring(0, DESCRIPTION_MAX_LENGTH)
                : trimmed;
    }

    /** 分类名查重（编辑时要排除自己，否则"不改名字直接保存"会被自己拦住） */
    private void checkNameDuplicate(String name, Long excludeId) {
        Category exist = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, name));
        if (exist != null && !exist.getId().equals(excludeId)) {
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS);
        }
    }

    private Category requireCategory(Long id) {
        Category category = id == null ? null : categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    /** 实体 -> VO */
    private CategoryVO toVO(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setDescription(category.getDescription());
        vo.setSort(category.getSort());
        return vo;
    }
}
