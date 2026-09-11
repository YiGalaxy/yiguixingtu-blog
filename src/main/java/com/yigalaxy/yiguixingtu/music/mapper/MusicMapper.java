package com.yigalaxy.yiguixingtu.music.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.music.entity.Music;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 音乐 Mapper。
 *
 * 【和 F5 那几个模块一样，这里只有一条手写 SQL —— 它碰的还是自己这张表】
 *   其余的查询是"按 status 过滤 + 按 sort/id 排序"，BaseMapper 加 LambdaQueryWrapper 足够。
 *   2026-09 加的那一条手写 COUNT（{@link #countOthersReferencing}）是删歌时用的：
 *   它要问"除了这一首，还有没有别的曲目用着同一个上传文件"，
 *   而那个判断没法用 LambdaQueryWrapper 表达（要拿 key 片段去 url 里做子串匹配）。
 *   ⚠️ 手写 SQL 就必须自己写 `deleted = 0`：@TableLogic 只会注入到
 *   MyBatis-Plus 自己生成的语句里（这是本项目最容易踩的一个坑，
 *   漏了的表现是"已删除的行被算成还有人在用"，于是文件永远清理不掉、且没有报错）。
 *
 * 【为什么要查别的表时，SQL 不在这个 Mapper 里】
 *   "文章正文/封面有没有引用这个文件"、"附件行有没有引用这个文件"这两条
 *   各自属于文章模块的两张表，它们写在 ArticleMapper / ArticleAttachmentMapper 上
 *   （与 CategoryMapper 里那条查 article 表的 COUNT 同一个归属原则：
 *    一条 SQL 放在"它读的那张表"所对应的 Mapper 里）。
 *   MusicServiceImpl 把三个计数合起来判断 —— 见那个类的 delete 方法。
 */
@Mapper
public interface MusicMapper extends BaseMapper<Music> {

    /**
     * 数一数"除了这一首之外，还有几条【未删除】的曲目用了同一个上传文件"。
     *
     * 【为什么这条判断必须存在（而不是"一首歌一个文件，直接删就行"）】
     *   音乐的 url 没有唯一约束，站长完全可能把同一个 mp3 填进两条记录
     *   （比如"夜曲"和"夜曲 (Live)"共用一个音源，或者复制一条记录改名字时忘了换地址）。
     *   删掉其中一首就把文件删了，另一首会立刻变成播不出来的哑巴，
     *   而文件【不可恢复】——所以这里按"可能被共享"处理，只有确认没人再用才删。
     *
     * 【为什么用 LOCATE 而不是 LIKE】
     *   LIKE 的匹配串里 % 与 _ 是通配符，而 URL/目录名里出现下划线很正常，
     *   用它匹配等于"这个下划线代表任意字符"，会把别的文件也算成"被引用"
     *   （判断不准确，且完全看不出来）。LOCATE(子串, 字符串) 是纯字符串查找，
     *   传什么就找什么。
     *
     * 【为什么比对 key（music/2026/09/xxx.mp3）而不是整条 url】
     *   url 可能是绝对地址（http://host/uploads/music/...）也可能是站内相对地址
     *   （/uploads/music/...，取决于当时 app.upload.base-url 的配置），
     *   用整条 URL 比会在两种形态混用时得出"没人引用"的错误结论 —— 然后误删。
     *   key 是两种形态共同包含的那一段（见 UploadedFileCleaner.toObjectKey）。
     *
     * 【性能】LOCATE 是函数匹配（等价于前置通配符的 LIKE），走不了索引，会扫一遍 music 表。
     *   这张表是"几十首"的量级、而且只在删歌时执行一次，可以接受。
     *
     * @param musicId 当前正在删除的曲目 id（它自己不算"别人"）
     * @param key     上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 还有几条未删除的曲目在用这个文件（0 表示音乐这边没人再用它）
     */
    @Select("""
            SELECT COUNT(*)
            FROM music
            WHERE deleted = 0
              AND id <> #{musicId}
              AND LOCATE(#{key}, url) > 0
            """)
    long countOthersReferencing(@Param("musicId") Long musicId, @Param("key") String key);

    /**
     * 数一数"有几条【未删除】的曲目用了这个上传文件"（没有"要撇开谁"这一说）。
     *
     * 【它是给谁用的】删【文章】（或编辑文章移除附件）时的引用检查 ——
     *   ArticleServiceImpl.remove 会去问"这个文件还有没有别的地方在用"。
     *   那里没有"我自己这首歌"要排除，所以用不了上面那条（参数里塞个假 id 只会让人看不懂）。
     *
     * 【为什么删文章要查音乐表】这是一条容易被忽略、后果却很难看的路径：
     *   音频地址是同源的 {@code /uploads/music/...}，站长完全可能既把它录进音乐列表、
     *   又在某篇文章的正文里嵌一段 {@code <audio src="...">}（从音乐列表复制地址最自然）。
     *   删那篇文章时如果只查文章自己的两张表，就会得出"没人用这个文件"的结论 ——
     *   然后把音乐列表里那首歌的音频删掉：歌还在、点开播不了，文件还回不来。
     *   所以两侧（删歌 / 删文章）都必须查全部三张表，规则才是对称且不漏的。
     *
     * @param key 上传目录里的对象 key（不含 base-url 与 /uploads/ 前缀）
     * @return 有几条未删除的曲目在用这个文件（0 表示音乐这边没人用）
     */
    @Select("""
            SELECT COUNT(*)
            FROM music
            WHERE deleted = 0
              AND LOCATE(#{key}, url) > 0
            """)
    long countReferencing(@Param("key") String key);
}
