package com.yigalaxy.yiguixingtu.about.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.about.entity.About;
import org.apache.ibatis.annotations.Mapper;

/**
 * 关于页 Mapper。
 *
 * 【它只有一个方法被用到：selectById(1)】
 *   这张表没有列表、没有筛选、没有分页 —— 所有读都是"把那一行取出来"。
 *   所以这里依然一行手写 SQL 都不需要（BaseMapper 的 selectById / insert / update 够用）。
 *   ⚠️ 尤其不能在这里手写"UPDATE about SET ..."那种语句：
 *   本表没有 @TableLogic，看似没风险，但手写语句会让"缩进/换行/字段顺序"这类
 *   与业务无关的细节散落在 SQL 字符串里，而 Service 里已经有一处明确的更新逻辑 ——
 *   一处逻辑、一个地方读得懂（和 V5 里"为什么不用外键"是同一个道理）。
 */
@Mapper
public interface AboutMapper extends BaseMapper<About> {
}
