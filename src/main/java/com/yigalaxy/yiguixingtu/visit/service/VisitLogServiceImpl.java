package com.yigalaxy.yiguixingtu.visit.service;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.web.ClientIp;
import com.yigalaxy.yiguixingtu.common.web.IpRegionResolver;
import com.yigalaxy.yiguixingtu.visit.dto.VisitStatsVO;
import com.yigalaxy.yiguixingtu.visit.dto.VisitorIpVO;
import com.yigalaxy.yiguixingtu.visit.entity.VisitLog;
import com.yigalaxy.yiguixingtu.visit.mapper.VisitLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 记录访问并生成统计报表；上报路径先清除查询串，UV 按 IP 去重。 */
@Slf4j
@Service
public class VisitLogServiceImpl implements VisitLogService {

    private static final int MIN_DAYS = 1;

    private static final int MAX_DAYS = 90;

    /** 限制访客 IP 列表规模；前端需标明列表已截断。 */
    private static final int VISITOR_IP_LIMIT = 200;

    private final VisitLogMapper visitLogMapper;

    private final IpRegionResolver ipRegionResolver;

    public VisitLogServiceImpl(VisitLogMapper visitLogMapper, IpRegionResolver ipRegionResolver) {
        this.visitLogMapper = visitLogMapper;
        this.ipRegionResolver = ipRegionResolver;
    }

    @Override
    public void record(String rawPath, HttpServletRequest request, LoginUser currentUser) {
        String path = VisitRequestParser.normalizePath(rawPath);
        if (path == null) {
            return;
        }

        VisitLog visit = new VisitLog();
        visit.setPath(path);
        visit.setArticleId(VisitRequestParser.articleIdOf(path));

        if (currentUser == null) {
            visit.setVisitorType(VisitLog.VISITOR_GUEST);
        } else {
            boolean admin = "ADMIN".equals(currentUser.getUser().getRole());
            visit.setVisitorType(admin ? VisitLog.VISITOR_ADMIN : VisitLog.VISITOR_USER);
            visit.setUserId(currentUser.getUser().getId());
        }

        visit.setIp(ClientIp.of(request));
        // 归属地写入时解析一次，历史记录的空值在读取时补算。
        visit.setRegion(ipRegionResolver.resolve(visit.getIp()));
        VisitRequestParser.classifyReferer(visit, request);
        visit.setDevice(VisitRequestParser.deviceOf(request));
        visit.setCreateTime(LocalDateTime.now());

        visitLogMapper.insert(visit);
    }

    @Override
    public VisitStatsVO stats(int days) {
        int span = spanOf(days);
        LocalDateTime since = sinceOf(days);

        List<VisitStatsVO.DailyPoint> daily = visitLogMapper.selectDaily(since).stream()
                .map(row -> new VisitStatsVO.DailyPoint(
                        String.valueOf(row.get("d")),
                        asLong(row.get("pv")),
                        asLong(row.get("uv"))))
                .toList();

        return new VisitStatsVO(
                span,
                visitLogMapper.countSince(since),
                visitLogMapper.countDistinctIpSince(since),
                visitLogMapper.countNewVisitorsSince(since),
                daily,
                slicesOf(visitLogMapper.selectGrouped("visitor_type", since)),
                slicesOf(visitLogMapper.selectGrouped("referer_type", since)),
                slicesOf(visitLogMapper.selectGrouped("device", since)),
                slicesOf(visitLogMapper.selectTopPaths(since)),
                visitLogMapper.selectTopArticles(since).stream()
                        .map(row -> new VisitStatsVO.ArticlePoint(
                                asLong(row.get("id")),
                                row.get("title") == null ? null : String.valueOf(row.get("title")),
                                asLong(row.get("v"))))
                        .toList());
    }

    @Override
    public List<VisitorIpVO> visitorIps(int days) {
        LocalDateTime since = sinceOf(days);

        List<VisitorIpVO> visitors = new ArrayList<>();
        for (Map<String, Object> row : visitLogMapper.selectVisitorIps(since, VISITOR_IP_LIMIT)) {
            String ip = row.get("ip") == null ? null : String.valueOf(row.get("ip"));
            Object stored = row.get("region");

            // 兼容 region 字段加入前的历史访问记录，不回写数据库。
            String region = stored == null ? ipRegionResolver.resolve(ip) : String.valueOf(stored);

            visitors.add(new VisitorIpVO(ip, region, asLong(row.get("visits")), asDateTime(row.get("last_visit"))));
        }
        return visitors;
    }

    private int spanOf(int days) {
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    /** 从当天零点起算，今天也计入区间。 */
    private LocalDateTime sinceOf(int days) {
        return LocalDate.now().minusDays(spanOf(days) - 1L).atStartOfDay();
    }

    private List<VisitStatsVO.Slice> slicesOf(List<Map<String, Object>> rows) {
        List<VisitStatsVO.Slice> slices = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("k");
            if (key == null) {
                continue;
            }
            slices.add(new VisitStatsVO.Slice(String.valueOf(key), asLong(row.get("v"))));
        }
        return slices;
    }

    /** 兼容 JDBC 聚合结果的不同数字类型。 */
    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 兼容 JDBC 返回的 LocalDateTime、Timestamp 和字符串。 */
    private LocalDateTime asDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
