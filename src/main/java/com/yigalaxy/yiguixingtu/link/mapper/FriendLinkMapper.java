package com.yigalaxy.yiguixingtu.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.link.entity.FriendLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 友链 Mapper。
 *
 * 【业务查询一行手写 SQL 都没有】
 *   对比 CategoryMapper（要数"还有几篇文章在用"）和 TagMapper（要算"每个标签几篇文章"）：
 *   那两条手写 SQL 存在的唯一理由是【它们要碰别的表的数据】。
 *   友链是自洽的 —— 所有查询都是"对 friend_link 这一张表的筛选 + 排序"，
 *   而 BaseMapper 生成的那几个方法（selectList / selectById / insert / updateById / deleteById）
 *   加上 LambdaQueryWrapper / LambdaUpdateWrapper 已经完整覆盖。
 *   ⇒ 没有需求就不要多一层手写 SQL：手写语句还得自己记得写 deleted = 0
 *     （@TableLogic 管不到手写语句，这个坑在 CategoryMapper 的注释里写着）。
 *
 * 【2026-09 唯一的例外：下面那条只读的 countReferencing】
 *   它不是业务查询，而是"删除上传文件前的引用检查"（见 UploadReferenceChecker）。
 *   那类 SQL 的归属原则是"放在它读的那张表对应的 Mapper 里"，所以它在下面。
 *   ⚠️ 它手写了 deleted = 0 —— 正是上面提醒过的那个坑。
 *
 * 【@Mapper 是干什么的】
 *   MyBatis 用它来发现这个接口并生成实现类（动态代理）。
 *   项目里 8 个 Mapper 都写了它，保持一致（也可以靠启动类上的 @MapperScan 统一扫，
 *   但显式标注的好处是"哪个接口是 Mapper"在文件里一眼可见）。
 */
@Mapper
public interface FriendLinkMapper extends BaseMapper<FriendLink> {

    /**
     * 友链里有没有引用这个上传文件（logo 头像、站点地址）。
     *
     * 【为什么友链也要查】改造前每条友链的 logo 各有各的 UUID 副本，
     *   与文章封面不可能是同一个文件；改造后同一份内容只存一份，
     *   "这张图既是某篇文章的封面、又是某条友链的 logo"变成常态 ——
     *   不查这里，删那篇文章就会把友链页的 logo 删掉（不可逆）。
     *
     * 【为什么有 url 列】它本该是外链（{@code EXTERNAL_URL} 校验），
     *   但校验只要求 http(s):// 开头，本站上传地址也满足。
     *   多认一次只是少删一个文件，而漏认是不可逆的 —— 两个方向代价不对称。
     *
     * @param key 上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 有几条未删除的友链在用这个文件
     */
    @Select("""
            SELECT COUNT(*)
            FROM friend_link
            WHERE deleted = 0
              AND (LOCATE(#{key}, avatar) > 0 OR LOCATE(#{key}, url) > 0)
            """)
    long countReferencing(@Param("key") String key);
}
