package com.yigalaxy.yiguixingtu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * =====================================================================
 * 文章列表的索引契约测试（V2__add_article_sort_index.sql）
 *
 * 【这个测试类为什么存在】
 *   索引这种东西的特点是：加错了、被人删了、或者列顺序被调换了，
 *   功能上【完全看不出来】—— 接口照样返回正确数据，只是从 0.11ms 变成 183ms。
 *   没有测试兜着的话，某天有人"清理无用索引"时把它删掉，
 *   全项目 130 多个用例照样全绿，直到线上流量上来才暴露。
 *   所以这里把"索引应当长什么样"钉成断言，让它变成一条【会变红】的约定。
 *
 * 【为什么叫"契约测试"而不是"性能测试"】
 *   因为它断言的是【索引的定义与可用性】，不是【耗时】。
 *   原因很实在：Testcontainers 里的表几乎是空的，数据量小的时候
 *   优化器会理性地选择全表扫描（比走索引还快），
 *   这时候去断言"必须走索引 / 不能有 filesort"必然时绿时红 ——
 *   一个会随机变红的用例比没有用例更糟，因为它会让人开始无视红灯。
 *   真正的耗时对比（20 万行、183ms → 0.11ms）放在
 *   docs/perf/explain-article-list.sql 里，用脚本复现，
 *   数据也写进了 README 的「性能」章节。
 *
 * 【四类断言分别在防什么】
 *   ① V2 迁移真的执行了         —— 防"迁移文件写了但没生效"
 *   ② 新索引的列与顺序正确       —— 防"加了索引但顺序反了"（顺序反了就用不上）
 *   ③ 老索引还在                —— 防"以为新索引能替代老的，顺手删掉"
 *   ④ 两条查询都能用上对应索引    —— 防"索引在，但优化器认为它跟查询无关"
 *
 * 【用到的东西】
 *   · {@link JdbcTemplate} —— Spring 对原生 JDBC 的薄封装，
 *     这里用它跑 information_schema 查询和 EXPLAIN（都不是 MyBatis 的活）
 *   · information_schema.STATISTICS —— MySQL 里"索引长什么样"的权威来源，
 *     一行 = 索引里的一列，SEQ_IN_INDEX 是列在索引里的先后顺序
 *   · EXPLAIN 的 possible_keys 列 —— "优化器认为这条 SQL 可以考虑哪些索引"。
 *     注意它和 key 不是一回事：key 是最终选中的，会随数据量变化；
 *     possible_keys 只取决于【SQL 的形状】和【索引的定义】，
 *     所以在空表上也是稳定的 —— 这正是我们能把它写成断言的原因。
 * =====================================================================
 */
class ArticleIndexTest extends AbstractIntegrationTest {

    /** 表名 / 库名：写死而不是查 current_database()，让断言里的失败信息更直观 */
    private static final String TABLE = "article";

    /**
     * 前台列表的默认排序。这段 SQL 手写出来是为了【故意让测试不依赖 Service 层】：
     * 它模拟的是 MyBatis-Plus 最终发给 MySQL 的形状
     * （deleted = 0 是逻辑删除插件自动加的，列清单来自
     *  ArticleServiceImpl 里 wrapper.select 排除 content 的那段）。
     * 用真实 SQL 而不是"绕过 Service 直接查"，才能证明索引对真实业务查询有效。
     */
    private static final String FRONT_PAGE_DEFAULT_SQL =
            "SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time"
                    + " FROM article WHERE deleted = 0 AND status = 1"
                    + " ORDER BY is_top DESC, create_time DESC LIMIT 0, 10";

    /** 用户在页面上点"按发布时间排序"时的 SQL（没有 is_top 参与排序） */
    private static final String SORT_BY_CREATE_TIME_SQL =
            "SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time"
                    + " FROM article WHERE deleted = 0 AND status = 1"
                    + " ORDER BY create_time DESC LIMIT 0, 10";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =================================================================
    //  ① 迁移本身
    // =================================================================

    @Test
    @DisplayName("Flyway 应当把 V2 / V3 两个迁移都标记为执行成功")
    void flywaySchemaHistory_shouldContainSuccessfulV2AndV3() {
        // 【为什么值得单独测"迁移跑没跑"】
        //   这个项目真的踩过：pom 里少一个 spring-boot-flyway 模块时，
        //   应用【编译通过、启动成功、什么都不报】，
        //   但所有迁移脚本一个都没执行 —— 建表是"静默失效"的。
        //   所以"没有报错"不能作为"迁移生效"的证据，
        //   必须去 flyway_schema_history 里看到那一行。
        //   两个版本都断言：以后新加 V4 时照抄这一条即可，
        //   免得"加了迁移但没生效"再发生一次却没人发现。
        for (String version : List.of("2", "3")) {
            Integer applied = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = 1",
                    Integer.class, version);

            assertEquals(1, applied,
                    "flyway_schema_history 里应当有一条 version=" + version + " 且 success=1 的记录；"
                            + "如果没有，说明该迁移没有真正执行（先检查 pom 里 spring-boot-flyway 还在不在）");
        }
    }

    // =================================================================
    //  ② 新索引的定义
    // =================================================================

    @Test
    @DisplayName("article 上应当有 idx_status_top_create，且列顺序是 (status, is_top, create_time)")
    void article_shouldHaveStatusTopCreateIndexInOrder() {
        List<String> columns = indexColumns("idx_status_top_create");

        // 顺序就是全部意义所在：
        //   status 必须第一 —— 它是等值条件，用它把范围切小；
        //   is_top 必须在 create_time 前面 —— ORDER BY 是 is_top DESC, create_time DESC，
        //   索引里也必须按这个先后排，才能"倒着扫"直接取到有序结果。
        // 如果写成 (status, create_time, is_top)，status 定住之后
        // create_time 先有序，is_top 就失去了有序性 —— 排序仍然要靠 filesort，
        // 索引等于白加。这条断言就是防这个的。
        assertEquals(List.of("status", "is_top", "create_time"), columns,
                "idx_status_top_create 的列顺序必须是 (status, is_top, create_time)，"
                        + "顺序不对的话这个索引对默认排序完全无效");
    }

    // =================================================================
    //  ②b 覆盖索引（V3）：给分页的 COUNT 用
    // =================================================================

    @Test
    @DisplayName("article 上应当有 idx_deleted_status，且列顺序是 (deleted, status)")
    void article_shouldHaveDeletedStatusIndexInOrder() {
        List<String> columns = indexColumns("idx_deleted_status");

        // 【这个索引的意义和上面那个完全不同，别混了】
        //   上面 idx_status_top_create 解决的是"取 10 行数据"；
        //   这个解决的是"数一共有多少条"—— 分页组件每次都要的 total。
        //   实测：没有它的时候，COUNT 要在索引里定位到 8 万条、
        //   再【回表】8 万次去确认 deleted=0，占掉一次分页请求
        //   99.8% 的耗时；有了它，整个 COUNT 都在索引里完成，不需要回表。
        //
        //   为什么 deleted 在前而不是 status 在前：
        //     两种顺序的 COUNT 一样快（实测 21.7ms vs 21.4ms），
        //     但 (deleted, status) 还能服务"只按 deleted 数总数"的后台列表；
        //     (status, deleted) 因为最左前缀是 status，对那种查询用不上。
        assertEquals(List.of("deleted", "status"), columns,
                "idx_deleted_status 的列顺序必须是 (deleted, status)");
    }

    @Test
    @DisplayName("分页用的 COUNT 应当能用上覆盖索引（Covering index）")
    void explainCountQuery_shouldUseCoveringIndex() {
        // 这条 SQL 就是 MyBatis-Plus 分页时自动发的那条计数语句的形状
        Map<String, Object> plan = explain(
                "SELECT COUNT(*) FROM article WHERE deleted = 0 AND status = 1");

        // 和上面两条一样看 possible_keys：它只取决于 SQL 形状与索引定义，
        // 空表上也稳定（key 会随数据量变化，不能当断言）。
        assertTrue(Objects.toString(plan.get("possible_keys"), "").contains("idx_deleted_status"),
                "COUNT(*) 应当被认为能用上 idx_deleted_status（覆盖索引），"
                        + "实际 possible_keys=" + plan.get("possible_keys") + "，key=" + plan.get("key"));
    }


    @Test
    @DisplayName("idx_status_create 应当保留：它服务的是'按发布时间排序'那条路径")
    void article_shouldKeepStatusCreateIndex() {
        // 【这条断言防的是"想当然的清理"】
        //   看到 (status, is_top, create_time) 之后，很容易觉得
        //   (status, create_time) 是它的"前缀"、属于重复索引可以删。
        //   其实不是：复合索引只能用【最左前缀】，
        //   (status, is_top, create_time) 里 status 后面跟的是 is_top，
        //   所以它无法支撑"status=1 且按 create_time 排序"这条 SQL。
        //   实测：删掉之后那条 SQL 会重新变成 filesort。
        assertEquals(List.of("status", "create_time"), indexColumns("idx_status_create"),
                "idx_status_create 必须保留，否则按发布时间排序会退化成 filesort");
    }

    // =================================================================
    //  ④ 索引对两条真实查询都可用
    // =================================================================

    @Test
    @DisplayName("前台默认列表：优化器应当认为 idx_status_top_create 可用")
    void explain_frontPageDefaultQuery_shouldConsiderTopCreateIndex() {
        Map<String, Object> plan = explain(FRONT_PAGE_DEFAULT_SQL);

        // 看 possible_keys 而不是 key：key 是"最终选中"，会随数据量变化；
        // possible_keys 是"这条 SQL 形状下可以考虑的索引"，空表上也稳定。
        assertTrue(Objects.toString(plan.get("possible_keys"), "").contains("idx_status_top_create"),
                "默认排序（is_top DESC, create_time DESC）应当被认为能用上 idx_status_top_create，"
                        + "实际 possible_keys=" + plan.get("possible_keys") + "，key=" + plan.get("key"));
    }

    @Test
    @DisplayName("按发布时间排序：优化器应当认为 idx_status_create 可用")
    void explain_sortByCreateTimeQuery_shouldConsiderStatusCreateIndex() {
        Map<String, Object> plan = explain(SORT_BY_CREATE_TIME_SQL);

        assertTrue(Objects.toString(plan.get("possible_keys"), "").contains("idx_status_create"),
                "按 create_time 排序应当被认为能用上 idx_status_create，"
                        + "实际 possible_keys=" + plan.get("possible_keys") + "，key=" + plan.get("key"));
    }

    // =================================================================
    //  工具方法
    // =================================================================

    /**
     * 取某个索引的列清单（按索引内的先后顺序）。
     *
     * 【为什么按 SEQ_IN_INDEX 排序是必须的】
     *   information_schema.STATISTICS 里一行是"索引里的一列"，
     *   MySQL 并不保证返回顺序。不显式 ORDER BY 的话，
     *   拿到的可能是 (create_time, status, is_top) 这种乱序，
     *   断言就会因为"顺序不对"而误报 —— 但索引其实是对的。
     *   所以顺序一定要自己排出来。
     *
     * 【为什么还要断言"取到了列"】
     *   索引不存在时这个查询会返回空列表。空列表和"列顺序错了"
     *   是两种完全不同的故障，失败信息里区分开能省很多排查时间。
     */
    private List<String> indexColumns(String indexName) {
        List<String> columns = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?"
                        + " ORDER BY SEQ_IN_INDEX",
                String.class, TABLE, indexName));

        assertTrue(!columns.isEmpty(),
                "索引 " + indexName + " 在 " + TABLE + " 表上不存在（或者名字写错了）");
        return columns;
    }

    /**
     * 跑一次 EXPLAIN，返回第一行，并把列名统一转成小写。
     *
     * 【为什么要转小写】
     *   MySQL 8.0.32 之后 EXPLAIN 的输出列名变成了大写
     *   （POSSIBLE_KEYS / KEY / TYPE ...），而更早的版本是小写。
     *   直接 row.get("possible_keys") 会在新版本上返回 null，
     *   表现是"断言失败但真实原因只是列名大小写"——
     *   排查起来很浪费时间。统一转小写就与版本无关了。
     *
     * 【EXPLAIN 为什么可以直接拼字符串】
     *   这里拼的是【测试代码里写死的常量 SQL】，没有任何外部输入，
     *   不存在注入问题。（业务代码里绝对不允许这样拼 SQL，
     *   这也是 ArticleServiceImpl.applySort 要用白名单的原因。）
     */
    private Map<String, Object> explain(String sql) {
        Map<String, Object> row = jdbcTemplate.queryForMap("EXPLAIN " + sql);

        Map<String, Object> normalized = new HashMap<>();
        row.forEach((k, v) -> normalized.put(k.toLowerCase(Locale.ROOT), v));
        return normalized;
    }
}
