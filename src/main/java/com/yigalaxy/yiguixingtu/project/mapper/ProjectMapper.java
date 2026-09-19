package com.yigalaxy.yiguixingtu.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.project.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
 *
 * 【2026-09 唯一的例外：下面那条只读的 countReferencing】
 *   它不是业务查询，而是"删除上传文件前的引用检查"（见 UploadReferenceChecker）。
 *   那类 SQL 的归属原则是"放在它读的那张表对应的 Mapper 里"，所以它在下面。
 *   ⚠️ 它手写了 deleted = 0 —— 正是上面提醒过的那个坑。
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 项目里有没有引用这个上传文件（封面、项目地址、仓库地址）。
     *
     * 【为什么项目也要查】改造前每个项目的封面各有各的 UUID 副本，
     *   与文章封面不可能是同一个文件；改造后同一份内容只存一份，
     *   站长拿同一张图既当项目封面又当文章封面是很自然的做法 ——
     *   不查这里，删那篇文章就会把项目页的封面删掉（不可逆）。
     *
     * 【为什么有 url / repo 两列】它们本该是外链（{@code EXTERNAL_URL} 校验），
     *   但校验只要求 http(s):// 开头，本站上传地址也满足。
     *   多认一次只是少删一个文件，而漏认不可逆 —— 两个方向代价不对称。
     *
     * @param key 上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 有几个未删除的项目在用这个文件
     */
    @Select("""
            SELECT COUNT(*)
            FROM project
            WHERE deleted = 0
              AND (LOCATE(#{key}, cover) > 0
                   OR LOCATE(#{key}, url) > 0
                   OR LOCATE(#{key}, repo) > 0)
            """)
    long countReferencing(@Param("key") String key);
}
