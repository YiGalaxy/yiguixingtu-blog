package com.yigalaxy.yiguixingtu.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.music.entity.Music;

import org.apache.ibatis.annotations.Mapper;

/**
 * 音乐 Mapper。
 *
 * 【和 F5 那几个模块一样，这里没有一行手写 SQL】
 *   唯一的查询是"按 status 过滤 + 按 sort/id 排序"，BaseMapper 加 LambdaQueryWrapper 足够。
 *   手写 SQL 的正当理由永远是"要碰别的表"（见 CategoryMapper / TagMapper），
 *   音乐只碰自己这一张表，所以不写 —— 少一条手写语句就少一次"忘了自己写 deleted = 0"的机会
 *   （@TableLogic 会自动补上这个条件，手写 SQL 不会）。
 */
@Mapper
public interface MusicMapper extends BaseMapper<Music> {
}
