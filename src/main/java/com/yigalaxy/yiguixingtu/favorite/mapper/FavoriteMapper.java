package com.yigalaxy.yiguixingtu.favorite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.favorite.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
 *
 * 【2026-09 唯一的例外：下面那条只读的 countReferencing】
 *   它不是业务查询，而是"删除上传文件前的引用检查"（见 UploadReferenceChecker）。
 *   那类 SQL 的归属原则是"放在它读的那张表对应的 Mapper 里"，所以它在下面。
 *   ⚠️ 它手写了 deleted = 0 —— 正是上面提醒过的那个坑。
 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /**
     * 收藏里有没有引用这个上传文件（收藏地址）。
     *
     * 【为什么收藏也要查】与项目 / 友链同一条理由：改造后同一份内容只存一份文件，
     *   站长的收藏地址完全可能填成本站上传目录里的某个文件
     *   （校验 {@code EXTERNAL_URL} 只要求 http(s):// 开头，本站地址满足）。
     *   不查这里，删那篇文章就会把这条收藏的地址变成一个 404。
     *
     * 【为什么只查 url 不查 description】description 是人读的说明文字，
     *   页面按纯文本渲染它，里面出现一个路径只是一串字符，删了文件也不会坏
     *   （这条界限的完整说明写在 UploadReferenceChecker 的类注释里）。
     *
     * @param key 上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 有几条未删除的收藏在用这个文件
     */
    @Select("""
            SELECT COUNT(*)
            FROM favorite
            WHERE deleted = 0
              AND LOCATE(#{key}, url) > 0
            """)
    long countReferencing(@Param("key") String key);
}
