package com.yigalaxy.yiguixingtu.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.project.entity.Project;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目 Mapper。
 *
 * 【为什么这里没有一行手写 SQL】
 *   所有查询都是"对 project 这一张表的筛选 + 排序"，BaseMapper 提供的方法
 *   加上 LambdaQueryWrapper 已经够用。对比一下需要手写 SQL 的两个 Mapper：
 *   CategoryMapper 要数 article 表的行（跨表），TagMapper 要 JOIN article_tag 做聚合 ——
 *   手写 SQL 的正当理由永远是"要碰别的表"。这里没有，所以不写。
 *
 *   少写一条手写语句，就少一次"忘了自己写 deleted = 0"的机会
 *   （@TableLogic 只管 BaseMapper 生成的 SQL，这个坑在 CategoryMapper 里有完整说明）。
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
