package com.yigalaxy.yiguixingtu.about.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.about.entity.About;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 关于页 Mapper。
 *
 * 【业务读写只有一个方法：selectById(1)】
 *   这张表没有列表、没有筛选、没有分页 —— 所有读都是"把那一行取出来"。
 *   所以业务侧一行手写 SQL 都不需要（BaseMapper 的 selectById / insert / update 够用）。
 *   ⚠️ 尤其不能在这里手写"UPDATE about SET ..."那种语句：
 *   本表没有 @TableLogic，看似没风险，但手写语句会让"缩进/换行/字段顺序"这类
 *   与业务无关的细节散落在 SQL 字符串里，而 Service 里已经有一处明确的更新逻辑 ——
 *   一处逻辑、一个地方读得懂（和 V5 里"为什么不用外键"是同一个道理）。
 *
 * 【2026-09 唯一的例外：下面那条只读的 countReferencing】
 *   它不是业务查询，而是"删除上传文件前的引用检查"（见 UploadReferenceChecker）。
 *   引用检查这类 SQL 的归属原则是"放在它读的那张表对应的 Mapper 里"，
 *   所以它出现在这里，而不是被塞进某个业务类里当字符串拼。
 */
@Mapper
public interface AboutMapper extends BaseMapper<About> {

    /**
     * 关于页里有没有引用这个上传文件（头像、简介正文、GitHub 链接）。
     *
     * 【为什么关于页也要查】改造前每张图各有各的 UUID 副本，"关于页的头像"
     *   与"某篇文章的封面"不可能是同一个文件，所以那时不查这张表也没事。
     *   改造后同一份内容只存一份，站长把同一张图既当关于页头像又当文章封面
     *   是很自然的做法 —— 不查这里，删那篇文章就会把关于页的头像删掉。
     *
     * 【为什么有三列】
     *   · {@code avatar} —— 头像，直接进 {@code <img src>}
     *   · {@code bio}    —— 简介，允许写 Markdown，里面可以嵌图
     *   · {@code github} —— 站长的 GitHub 地址。它本该是外链，
     *     但没有理由排除"本站上传目录里的某个文件"这种填法
     *     （校验只要求 http(s):// 开头），多认一次只是少删一个文件。
     *
     * 【为什么没有 deleted = 0】本表没有 @TableLogic（只有一行数据，
     *   见 About 的类注释），没有"已删除的行"这种东西可过滤。
     *
     * @param key 上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 关于页引用这个文件的处数（0 表示关于页没在用它）
     */
    @Select("""
            SELECT COUNT(*)
            FROM about
            WHERE LOCATE(#{key}, avatar) > 0
               OR LOCATE(#{key}, bio) > 0
               OR LOCATE(#{key}, github) > 0
            """)
    long countReferencing(@Param("key") String key);
}
