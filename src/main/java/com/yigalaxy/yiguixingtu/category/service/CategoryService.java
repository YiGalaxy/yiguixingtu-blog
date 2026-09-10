package com.yigalaxy.yiguixingtu.category.service;

import com.yigalaxy.yiguixingtu.category.dto.CategoryForm;
import com.yigalaxy.yiguixingtu.category.dto.CategoryVO;

import java.util.List;

/**
 * 分类服务。
 *
 * 【读接口是公开的，写接口只有管理员能用】
 *   前台只需要"列出分类"（首页的筛选条要用），所以 SecurityConfig 只放行了
 *   `GET /category/list`；写接口挂在 `/admin/category/**` 上（类级 @PreAuthorize）。
 */
public interface CategoryService {

    /**
     * 查询全部分类（按 sort 升序，再按 id 升序）。
     * 前台/后台共用，走 Redis 缓存（key 里带文章缓存版本号）。
     */
    List<CategoryVO> listAll();

    /** 新建分类，返回新分类 id */
    Long create(CategoryForm form);

    /** 编辑分类（改名 / 改描述 / 改排序） */
    void update(Long id, CategoryForm form);

    /**
     * 删除分类（逻辑删除）。
     *
     * @throws com.yigalaxy.yiguixingtu.common.exception.BusinessException
     *         还有文章在用这个分类时抛出 —— 理由见实现里的说明
     */
    void delete(Long id);
}
