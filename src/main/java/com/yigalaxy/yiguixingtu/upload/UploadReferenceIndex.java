package com.yigalaxy.yiguixingtu.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * =====================================================================
 * 「全站引用了哪些上传文件」的反向索引 —— 一次查完，供后台存储管理页展示
 *
 * ============ 为什么需要它（而不是逐个文件去问"谁在用我"）============
 *
 * 【逐个文件问的代价随文件数线性增长】
 *   存储管理页要显示"每个文件被哪些地方引用"。如果按"文件 → 问一次库"来做，
 *   打开一次页面就是 `文件数 × 表数` 次全表扫描（LOCATE 是函数匹配、走不了索引）。
 *   现在站里只有几个文件，看不出问题；但文件数会随时间涨 ——
 *   而**页面变慢的那一刻正好是文件变多、最需要这个页面的时候**。
 *
 * 【所以反过来：一次把"全站所有引用"读出来，建成 key → 引用清单的表】
 *   每张表一条 SQL（15 条，与文件数无关），把含有 {@value #UPLOAD_TOKEN} 的行
 *   取回来，在 Java 里用 {@link UploadedFileCleaner#extractObjectKeys} 抠出 key。
 *   打开页面 = 固定的 15 次查询 + 一次目录遍历。
 *   代价是**把匹配到的行读进内存**（`article.content` 是 longtext），
 *   所以查询里带了 `LOCATE('/uploads/', 列) > 0` 这个前置条件 ——
 *   绝大多数文章根本不含本地上传的图，一条都不会被读出来。
 *   ⚠️ 量级真上去之后（几千篇文章、每篇都嵌图）要改成"后台异步建索引表"，
 *   而不是继续在请求里现算。个人博客的规模离那条线很远。
 *
 * ============ ⚠️ 它不是删除的判据 ============
 *   "能不能删"永远问 {@link UploadReferenceChecker}（它有按 key 精确匹配的 SQL，
 *   也是删文章 / 删歌 / 删附件时实际用的那一套）。这里只是**展示**用的索引。
 *   两者读的是同一批表和列 —— 清单在下面 {@link #SOURCES} 里，
 *   另一份在 UploadReferenceChecker 的注释里，**加列时两处一起加**。
 *   （有一条用例专门钉着"两边对同一组数据结论一致"，见 UploadStorageAdminTest。）
 *
 * 【为什么用 JdbcTemplate 而不是各模块的 Mapper】
 *   15 条查询是**同一个形状**（表 + id 列 + 标题列 + 要扫的列 + 软删条件），
 *   只是参数不同。写成 15 个散在 7 个模块里的 Mapper 方法 + 15 个行记录，
 *   读到的人根本看不出"这是一份清单"，而这份清单的**完整性**恰恰是它的全部价值
 *   （漏一张表 = 某个文件被误报成"没人用"）。
 *   把这些放在一个文件里、由 {@link #SOURCES} 一个常量表驱动，
 *   加表加列时改一行、且一眼能看出总共覆盖了哪些地方。
 * =====================================================================
 */
@Slf4j
@Component
public class UploadReferenceIndex {

    /**
     * 判定"这一列里有没有本站上传文件"的前缀 —— 所有上传地址都含它
     * （绝对地址 {@code http://host/uploads/...} 与站内相对地址 {@code /uploads/...} 都有）。
     *
     * 【为什么用它当 SQL 的过滤条件，而不是拿 key 去精确匹配】
     *   这里要的是"把候选行捞回来"，精确判断交给 Java 里的
     *   {@link UploadedFileCleaner#extractObjectKeys}（它认得绝对/相对两种形态）。
     *   在 SQL 里做精确匹配就得对每个 key 各查一遍 —— 那正是这个方法要避免的。
     */
    private static final String UPLOAD_TOKEN = "/uploads/";

    /**
     * 要扫的「表 × 列」清单 —— **这份清单就是"哪些地方可能引用上传文件"的全部答案**。
     *
     * 【怎么判断一列该不该进来】与 {@link UploadReferenceChecker} 同一条标准：
     *   这一列会不会被当成图片 / 音频 / 下载链接**渲染**出来。
     *   会 → 进来（删了它会裂图或变成死链）；只是一段给人读的文字 → 不进来
     *   （说明文字、公告、评论正文里出现一个路径，删掉文件那些字还是那些字）。
     *
     * 【字段含义】表名 / id 列（前端的跳转要用它）/ 标题列（显示"是谁"）/
     *   要扫的列 / 软删条件（没有就传 null）/ 引用方类型 / 命中时显示的字段名
     */
    private static final List<Source> SOURCES = List.of(
            new Source("article", "id", "title", "cover", "deleted = 0", "ARTICLE", "封面"),
            new Source("article", "id", "title", "content", "deleted = 0", "ARTICLE", "正文"),
            // 自动摘要会把正文开头（含图片地址）抄一份进来，所以它也算一处引用
            new Source("article", "id", "title", "summary", "deleted = 0", "ARTICLE", "摘要"),
            // ⚠️ 附件没有 deleted 列（物理删除），所以没有软删条件
            new Source("article_attachment", "article_id", "name", "url", null, "ATTACHMENT", "附件"),
            new Source("music", "id", "title", "url", "deleted = 0", "MUSIC", "音源"),
            new Source("music", "id", "title", "cover", "deleted = 0", "MUSIC", "封面"),
            new Source("about", "id", "nickname", "avatar", null, "ABOUT", "头像"),
            new Source("about", "id", "nickname", "bio", null, "ABOUT", "简介"),
            new Source("about", "id", "nickname", "github", null, "ABOUT", "GitHub"),
            new Source("friend_link", "id", "name", "avatar", "deleted = 0", "FRIEND_LINK", "头像"),
            new Source("friend_link", "id", "name", "url", "deleted = 0", "FRIEND_LINK", "站点地址"),
            new Source("project", "id", "name", "cover", "deleted = 0", "PROJECT", "封面"),
            new Source("project", "id", "name", "url", "deleted = 0", "PROJECT", "项目地址"),
            new Source("project", "id", "name", "repo", "deleted = 0", "PROJECT", "仓库地址"),
            new Source("favorite", "id", "title", "url", "deleted = 0", "FAVORITE", "收藏地址"));

    private final JdbcTemplate jdbcTemplate;
    private final UploadedFileCleaner uploadedFileCleaner;

    public UploadReferenceIndex(JdbcTemplate jdbcTemplate, UploadedFileCleaner uploadedFileCleaner) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadedFileCleaner = uploadedFileCleaner;
    }

    /**
     * 建索引：objectKey → 引用它的所有地方。
     *
     * @return 可变 Map（调用方自己决定要不要改）。同一个 key 下同一个来源不会重复出现
     *         （同一列里同一张图写两遍只算一处）
     */
    public Map<String, List<UploadReference>> build() {
        Map<String, List<UploadReference>> index = new LinkedHashMap<>();
        for (Source source : SOURCES) {
            collect(index, source);
        }
        log.debug("上传文件引用索引构建完成: 被引用的文件 {} 个", index.size());
        return index;
    }

    /** 扫一列：把这一列里出现的所有上传文件，各自记一条"被这个来源引用了" */
    private void collect(Map<String, List<UploadReference>> index, Source source) {
        // 表名、列名、软删条件都是上面那个常量表里的字面量（不是用户输入），拼进 SQL 安全；
        // 唯一的外部输入是 UPLOAD_TOKEN，它走占位符
        String sql = "SELECT " + source.idColumn() + " AS target_id, "
                + source.labelColumn() + " AS label, "
                + source.textColumn() + " AS text"
                + " FROM " + source.table()
                + " WHERE " + (source.softDeleteCondition() == null ? "" : source.softDeleteCondition() + " AND ")
                + "LOCATE(?, " + source.textColumn() + ") > 0";

        jdbcTemplate.query(sql, rs -> {
            Long targetId = rs.getLong("target_id");
            String label = rs.getString("label");
            String text = rs.getString("text");

            // 【为什么复用 extractObjectKeys 而不是自己写正则】
            //   它已经处理了"绝对地址与相对地址"、"只认本站的上传目录"、
            //   "结果去重"这几件事，而且【与判据那边用的是同一个解析器】——
            //   自己再写一份正则，两份迟早对不上，表现是"列表说没人用、删除时又被拦住"
            Set<String> keys = uploadedFileCleaner.extractObjectKeys(text);
            for (String key : keys) {
                index.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new UploadReference(source.type(), targetId, label, source.field()));
            }
        }, UPLOAD_TOKEN);
    }

    /**
     * 一处"可能引用上传文件的地方"：一张表的一列。
     *
     * @param table               表名（字面量）
     * @param idColumn            引用方的 id 列 —— ⚠️ 附件那张表用的是 {@code article_id}，
     *                            因为"能跳过去的地方"是它所属的那篇文章，而不是附件行本身
     * @param labelColumn         显示用的标题列
     * @param textColumn          要扫的那一列
     * @param softDeleteCondition 软删条件（{@code deleted = 0}）；没有这一列的表传 null
     * @param type                引用方类型（原样给前端，见 {@link UploadReference#type()}）
     * @param field               命中时显示的字段名（"封面" / "正文"……）
     */
    private record Source(String table, String idColumn, String labelColumn, String textColumn,
                          String softDeleteCondition, String type, String field) {
    }
}
