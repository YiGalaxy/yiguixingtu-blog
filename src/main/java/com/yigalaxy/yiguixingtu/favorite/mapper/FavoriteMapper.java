package com.yigalaxy.yiguixingtu.favorite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.favorite.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏 Mapper。
 *
 * 【依然没有一行手写 SQL】
 *   唯一的查询是"按 status 过滤 + 按 sort/id 排序"，BaseMapper 加 LambdaQueryWrapper 足够。
 *   分组统计（每个分组有几条）也是【故意的没有】：收藏总量几十条，
 *   前端拿到列表自己 groupBy 一次就够了，为它加一条手写 GROUP BY
 *   只会让"分组名是自由文本"这件事多一处要跟的地方
 *   （比如分组名带首尾空格时，SQL 的分组和前端的分组结果会不一致）。
 *
 *   手写 SQL 的正当理由永远是"要碰别的表"（见 CategoryMapper / TagMapper），
 *   这里没有，所以不写 —— 少一条手写语句就少一次"忘了自己写 deleted = 0"的机会。
 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {
}
