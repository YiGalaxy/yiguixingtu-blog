package com.yigalaxy.yiguixingtu.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.link.entity.FriendLink;
import org.apache.ibatis.annotations.Mapper;

/**
 * 友链 Mapper。
 *
 * 【为什么这个 Mapper 里一行代码都没有（没有哪怕一条手写 SQL）】
 *   对比 CategoryMapper（要数"还有几篇文章在用"）和 TagMapper（要算"每个标签几篇文章"）：
 *   那两条手写 SQL 存在的唯一理由是【它们要碰别的表的数据】。
 *   友链是自洽的 —— 所有查询都是"对 friend_link 这一张表的筛选 + 排序"，
 *   而 BaseMapper 生成的那几个方法（selectList / selectById / insert / updateById / deleteById）
 *   加上 LambdaQueryWrapper / LambdaUpdateWrapper 已经完整覆盖。
 *   ⇒ 没有需求就不要多一层手写 SQL：手写语句还得自己记得写 deleted = 0
 *     （@TableLogic 管不到手写语句，这个坑在 CategoryMapper 的注释里写着）。
 *
 * 【@Mapper 是干什么的】
 *   MyBatis 用它来发现这个接口并生成实现类（动态代理）。
 *   项目里 8 个 Mapper 都写了它，保持一致（也可以靠启动类上的 @MapperScan 统一扫，
 *   但显式标注的好处是"哪个接口是 Mapper"在文件里一眼可见）。
 */
@Mapper
public interface FriendLinkMapper extends BaseMapper<FriendLink> {
}
