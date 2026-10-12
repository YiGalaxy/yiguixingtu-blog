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

/** 分类管理与公开列表查询。 */
@Slf4j
@Service
public class CategoryServiceImpl implements CategoryService {

    /** 分类名长度上限，与 DTO 校验、数据库列长度保持一致 */
    private static final int NAME_MAX_LENGTH = 50;

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

    /** 返回按排序值排列的分类列表。 */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_CATEGORY_LIST, key = "@articleCacheVersion.current()")
    public List<CategoryVO> listAll() {
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
            // 并发请求可能同时通过查重，唯一索引负责最终兜底。
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

        // 保留被文章引用的分类，避免文章失去分类名。
        long inUse = categoryMapper.countArticlesByCategoryId(id);
        if (inUse > 0) {
            throw new BusinessException(ResultCode.CATEGORY_IN_USE,
                    "还有 " + inUse + " 篇文章在用这个分类，请先调整这些文章的分类");
        }

        // 逻辑删除不会释放唯一索引中的名字，先改名再删除。
        Category rename = new Category();
        rename.setId(id);
        rename.setName(exist.getName() + "#deleted#" + id);
        categoryMapper.updateById(rename);

        categoryMapper.deleteById(id);

        articleCacheVersion.bump();

        operationLogRecorder.record(OperationAction.DELETE_CATEGORY, AuditTarget.CATEGORY, id,
                "分类=" + exist.getName());

        log.info("删除分类: id={}, name={}", id, exist.getName());
    }

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

    private String normalizeDescription(String description) {
        return StringUtils.hasText(description) ? description.trim() : null;
    }

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

    private CategoryVO toVO(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setDescription(category.getDescription());
        vo.setSort(category.getSort());
        return vo;
    }
}
