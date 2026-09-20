package com.yigalaxy.yiguixingtu.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.visit.entity.VisitLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * 访问日志 Mapper
 *
 * 【这个类里全是聚合查询，而且是这个项目里唯一一处"写 SQL 做统计"】
 *   别处的查询都是"取一批记录"（列表、分页），而统计页要的是**分组计数**：
 *   按天分组、按身份分组、按来源分组……这些用 LambdaQueryWrapper 表达不了，
 *   所以这里的每一条都是手写 SQL。
 *
 * 【为什么不用 @TableLogic / 没有 deleted 条件】
 *   这张表只有"写入"和"整段删除（按时间清理）"两种操作，没有软删 ——
 *   访问日志没有"恢复"的语义，也不需要留痕。所以清理任务用的是 DELETE，
 *   这里的查询也就不用过滤 deleted（表里根本没有这一列）。
 *
 * 【为什么返回 Map 而不是定义一堆 DTO】
 *   这些聚合结果的形状是 `(分组键, 计数)` 或 `(日期, pv, uv)`，
 *   为每一种分组定义一个 record 只会让这个类变长，而它们只在
 *   VisitStatsService 里被读一次、立刻转成前端要的形状。
 *   ⚠️ 代价是字段名靠字符串对齐（"k"/"v"/"d"/"pv"/"uv"）——
 *   所以每个 SQL 里都写了显式别名，读的人不会猜。
 * =====================================================================
 */
@Mapper
public interface VisitLogMapper extends BaseMapper<VisitLog> {

    /**
     * 按天聚合：每天有多少次访问（PV）、多少个不同访客（UV）。
     *
     * 【UV 按 IP 去重】这是"有多少人"最接近的近似：同一个 IP 一天来十次算一个人。
     *   ⚠️ 它不是准确值：同一个人的手机与电脑是两个 IP（会多算），
     *   一个公司/小区共用一个出口 IP（会少算）。要更准就得上 cookie 或登录态，
     *   那对"看一眼访问情况"这个用途来说太重了 —— 这里要的是量级与趋势。
     *
     * @param since 只统计这个时间之后的记录
     */
    @Select("""
            SELECT DATE(create_time) AS d, COUNT(*) AS pv, COUNT(DISTINCT ip) AS uv
            FROM visit_log
            WHERE create_time >= #{since}
            GROUP BY DATE(create_time)
            ORDER BY d
            """)
    List<Map<String, Object>> selectDaily(@Param("since") LocalDateTime since);

    /**
     * 按某一列分组计数（身份 / 来源 / 设备三处共用这一条）。
     *
     * ⚠️ {@code column} 是【拼接】进去的，不是占位符 —— 因为列名不能用 ?。
     *   它由调用方传入，而调用方（VisitStatsService）只传下面三个常量之一，
     *   不接受任何外部输入。这一点必须守住：拼接列名是 SQL 注入的经典入口。
     *
     * @param column 只能是 "visitor_type" / "referer_type" / "device"（见常量）
     */
    @Select("""
            SELECT ${column} AS k, COUNT(*) AS v
            FROM visit_log
            WHERE create_time >= #{since}
            GROUP BY ${column}
            ORDER BY v DESC
            """)
    List<Map<String, Object>> selectGrouped(@Param("column") String column,
                                            @Param("since") LocalDateTime since);

    /** 访问最多的页面（路径级，用来发现"除了文章还有人看哪里"） */
    @Select("""
            SELECT path AS k, COUNT(*) AS v
            FROM visit_log
            WHERE create_time >= #{since}
            GROUP BY path
            ORDER BY v DESC
            LIMIT 10
            """)
    List<Map<String, Object>> selectTopPaths(@Param("since") LocalDateTime since);

    /**
     * 访问最多的文章（带标题）。
     *
     * 【为什么用 LEFT JOIN 而不是 INNER JOIN】文章被逻辑删除之后，
     *   它的历史访问记录还在（那是事实），只是标题查不到了 —— 这时标题为 null，
     *   前端显示成"（文章已删除）"。用 INNER JOIN 的话这些访问会凭空消失，
     *   统计数字就会比实际少。
     */
    @Select("""
            SELECT v.article_id AS id, a.title AS title, COUNT(*) AS v
            FROM visit_log v
            LEFT JOIN article a ON a.id = v.article_id
            WHERE v.create_time >= #{since} AND v.article_id IS NOT NULL
            GROUP BY v.article_id, a.title
            ORDER BY v DESC
            LIMIT 10
            """)
    List<Map<String, Object>> selectTopArticles(@Param("since") LocalDateTime since);

    /**
     * 访客 IP 列表：按 IP 聚合成"一个人"，按访问次数倒序。
     *
     * 【MAX(region) 而不是裸 region】
     *   同一个 IP 可能既有本列存在之前的老记录（region 为 NULL）、
     *   也有新记录（region 有值）。直接 SELECT region 时，MySQL 会在分组内
     *   任取一个值 —— 有可能取到 NULL，于是"这个 IP 有归属地却显示成空"。
     *   MAX() 会忽略 NULL，正好是这里想要的行为。
     *
     * 【为什么不排除 ip 为 NULL 的行】WHERE 里排掉了：ip 为空的行根本
     *   不是一个"访客"（正常路径上不会出现，只有 ClientIp 取不到值时才有），
     *   把它们聚成一行 key 为 null 的"访客"没有意义。
     *
     * @param since 只统计这个时间之后的记录
     * @param limit 最多返回多少个 IP（见 VisitLogService.visitorIps 里对上限的说明）
     */
    @Select("""
            SELECT ip, MAX(region) AS region, COUNT(*) AS visits, MAX(create_time) AS last_visit
            FROM visit_log
            WHERE create_time >= #{since} AND ip IS NOT NULL
            GROUP BY ip
            ORDER BY visits DESC, last_visit DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectVisitorIps(@Param("since") LocalDateTime since,
                                               @Param("limit") int limit);

    /**
     * 区间内的总 PV。
     *
     * 【为什么要单独查一次，而不是把 selectDaily 的结果加起来】
     *   日聚合是按"有记录的那些天"分组的，加起来确实等于总数 ——
     *   但那是把"统计逻辑"藏在了调用方的一句 sum 里。单独一条 COUNT 更直白，
     *   而且它和下面两条用的是同一个区间定义（少一个"两边口径不一致"的可能）。
     */
    @Select("SELECT COUNT(*) FROM visit_log WHERE create_time >= #{since}")
    long countSince(@Param("since") LocalDateTime since);

    /** 区间内的 UV（不同 IP 数） */
    @Select("SELECT COUNT(DISTINCT ip) FROM visit_log WHERE create_time >= #{since}")
    long countDistinctIpSince(@Param("since") LocalDateTime since);

    /**
     * 区间内的【新访客】数：第一次出现就在这个区间里的 IP。
     *
     * 【为什么子查询里没有时间条件】"新"是相对【全表历史】说的 ——
     *   只在这个区间里找 MIN(create_time) 的话，每个 IP 的首次出现都落在区间内，
     *   结果会等于 UV（"所有访客都是新访客"这种没意义的答案）。
     *   ⚠️ 代价是全表扫描（哪怕只看 7 天，也要扫完 90 天的历史）。
     *   表本身是有界的（保留 90 天），量级是几万行，可以接受。
     */
    @Select("""
            SELECT COUNT(*) FROM (
                SELECT ip FROM visit_log
                WHERE ip IS NOT NULL
                GROUP BY ip
                HAVING MIN(create_time) >= #{since}
            ) t
            """)
    long countNewVisitorsSince(@Param("since") LocalDateTime since);

    /**
     * 删掉这个时间之前的记录（保留期到期清理，见 VisitLogCleanupTask）。
     *
     * 【为什么是物理删除而不是逻辑删除】访问日志没有"恢复"的语义，
     *   而保留期的**目的就是让数据真的消失**（IP 是个人信息）。
     *   逻辑删除在这里等于"假装删了"。
     *
     * @return 删掉的行数（日志里要记，用来确认保留期真的在生效）
     */
    @Delete("DELETE FROM visit_log WHERE create_time < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
