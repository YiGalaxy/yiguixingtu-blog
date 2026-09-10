package com.yigalaxy.yiguixingtu.category.service;

import com.yigalaxy.yiguixingtu.category.dto.CategoryVO;

import java.util.List;

/**
 * 分类服务。
 */
public interface CategoryService {

    /**
     * 查询全部分类（按 sort 升序，再按 id 升序）
     */
    List<CategoryVO> listAll();
}