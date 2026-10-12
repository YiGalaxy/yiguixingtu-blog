package com.yigalaxy.yiguixingtu.visit.service;

import com.yigalaxy.yiguixingtu.visit.entity.VisitLog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VisitRequestParserTest {

    @Test
    void normalizesOnlyThePagePath() {
        assertEquals("/article/13", VisitRequestParser.normalizePath("https://blog.test/article/13?q=secret#reply"));
        assertEquals("/archive", VisitRequestParser.normalizePath(" /archive#top?query "));
        assertEquals(255, VisitRequestParser.normalizePath("/" + "a".repeat(400)).length());
        assertNull(VisitRequestParser.normalizePath("not-a-page"));
        assertNull(VisitRequestParser.normalizePath(" "));
    }

    @Test
    void identifiesOnlyValidArticlePaths() {
        assertEquals(13L, VisitRequestParser.articleIdOf("/article/13/"));
        assertNull(VisitRequestParser.articleIdOf("/articles/13"));
        assertNull(VisitRequestParser.articleIdOf("/article/999999999999999999999999"));
    }

    @Test
    void classifiesVisitSourcesWithoutSavingFullReferers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        VisitLog visit = new VisitLog();
        VisitRequestParser.classifyReferer(visit, request);
        assertEquals(VisitLog.REFERER_DIRECT, visit.getRefererType());

        request.addHeader("Host", "blog.test:443");
        request.addHeader("Referer", "https://blog.test/article/13?secret=private");
        visit = new VisitLog();
        VisitRequestParser.classifyReferer(visit, request);
        assertEquals(VisitLog.REFERER_INTERNAL, visit.getRefererType());
        assertNull(visit.getRefererHost());

        request = new MockHttpServletRequest();
        request.addHeader("Host", "blog.test");
        request.addHeader("Referer", "https://other.test/search?q=private");
        visit = new VisitLog();
        VisitRequestParser.classifyReferer(visit, request);
        assertEquals(VisitLog.REFERER_EXTERNAL, visit.getRefererType());
        assertEquals("other.test", visit.getRefererHost());
    }

    @Test
    void classifiesDeviceWithExistingTwoValueRule() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(VisitLog.DEVICE_PC, VisitRequestParser.deviceOf(request));
        request.addHeader("User-Agent", "Mozilla/5.0 (iPhone; Mobile)");
        assertEquals(VisitLog.DEVICE_MOBILE, VisitRequestParser.deviceOf(request));
    }
}
