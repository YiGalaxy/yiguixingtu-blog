package com.yigalaxy.blog;

import com.yigalaxy.blog.visit.AdminVisitController;
import com.yigalaxy.blog.visit.VisitController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"blog.admin.username=contract-admin", "blog.admin.password=test-only-password"})
class VisitAnalyticsContractTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitController visits;
    @Autowired AdminVisitController reports;

    @Test
    @Transactional
    void reportsCountVisitorsAcrossDaysKeepIpsPrivateAndExpireOldRecords() {
        jdbc.update("DELETE FROM visit_log");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.11");
        request.addHeader("User-Agent", "iPhone Mobile");
        request.addHeader("Origin", "https://example.com");
        assertTrue(visits.record(new VisitController.VisitForm("/about", "https://search.example/result"), request).recorded());
        assertFalse(visits.record(new VisitController.VisitForm("/about", "/"), request).recorded());
        assertTrue(visits.record(new VisitController.VisitForm("/projects", "/about"), request).recorded());
        jdbc.update("INSERT INTO visit_log (path, ip, visitor_type, referer_type, device, visited_at) VALUES ('/', '192.0.2.12', 'ADMIN', 'DIRECT', 'PC', UTC_DATE() - INTERVAL 8 DAY)");
        jdbc.update("INSERT INTO visit_log (path, ip, visitor_type, referer_type, device, visited_at) VALUES ('/', '192.0.2.11', 'GUEST', 'DIRECT', 'PC', UTC_DATE() - INTERVAL 8 DAY)");
        jdbc.update("INSERT INTO visit_log (path, ip, visitor_type, referer_type, device, visited_at) VALUES ('/', '192.0.2.99', 'GUEST', 'DIRECT', 'PC', UTC_DATE() - INTERVAL 91 DAY)");
        var week = reports.report(7);
        assertEquals(2, week.totalPv());
        assertEquals(1, week.totalUv());
        assertEquals(0, week.newVisitors());
        assertEquals(7, week.daily().size());
        assertEquals(2, week.daily().get(6).pv());
        assertEquals(1, week.daily().get(6).uv());
        assertEquals(2, week.devices().stream().filter(item -> item.key().equals("MOBILE")).findFirst().orElseThrow().value());
        assertEquals(1, week.refererTypes().stream().filter(item -> item.key().equals("INTERNAL")).findFirst().orElseThrow().value());
        assertEquals(1, week.refererTypes().stream().filter(item -> item.key().equals("EXTERNAL")).findFirst().orElseThrow().value());
        assertEquals(4, reports.report(30).totalPv());
        assertEquals(2, reports.report(30).totalUv());
        assertEquals(2, reports.report(30).newVisitors());
        assertEquals("192.0.2.11", reports.ips(7).get(0).ip());
        visits.cleanExpiredVisits();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE ip = '192.0.2.99'", Integer.class));
        assertEquals(4, reports.report(90).totalPv());
    }
}
