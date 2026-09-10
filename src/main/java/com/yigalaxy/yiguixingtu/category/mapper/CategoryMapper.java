package com.yigalaxy.yiguixingtu.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.category.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类 Mapper。继承 BaseMapper 后，增删改查都是现成的。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}