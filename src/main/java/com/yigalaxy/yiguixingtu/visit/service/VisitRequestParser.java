package com.yigalaxy.yiguixingtu.visit.service;

import com.yigalaxy.yiguixingtu.visit.entity.VisitLog;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 清洗访问上报的路径、来源和设备信息。 */
final class VisitRequestParser {

    private static final int PATH_MAX_LENGTH = 255;
    private static final int HOST_MAX_LENGTH = 255;
    private static final Pattern ARTICLE_PATH = Pattern.compile("^/article/(\\d+)/?$");
    private static final String[] MOBILE_UA_KEYWORDS = {"Mobile", "Android", "iPhone", "iPad", "iPod"};

    private VisitRequestParser() {
    }

    static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String path = rawPath.trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                String parsed = URI.create(path).getPath();
                path = parsed == null ? "/" : parsed;
            } catch (IllegalArgumentException e) {
                int schemeEnd = path.indexOf("//");
                int slash = path.indexOf('/', schemeEnd + 2);
                path = slash < 0 ? "/" : path.substring(slash);
            }
        }
        int query = path.indexOf('?');
        int hash = path.indexOf('#');
        int cut = query < 0 ? hash : hash < 0 ? query : Math.min(query, hash);
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        if (!path.startsWith("/")) {
            return null;
        }
        return path.length() > PATH_MAX_LENGTH ? path.substring(0, PATH_MAX_LENGTH) : path;
    }

    static Long articleIdOf(String path) {
        Matcher matcher = ARTICLE_PATH.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void classifyReferer(VisitLog visit, HttpServletRequest request) {
        String referer = request == null ? null : request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            visit.setRefererType(VisitLog.REFERER_DIRECT);
            return;
        }

        String refererHost = hostOf(referer);
        String requestHost = request.getHeader("Host");
        if (refererHost != null && requestHost != null
                && refererHost.equalsIgnoreCase(stripPort(requestHost))) {
            visit.setRefererType(VisitLog.REFERER_INTERNAL);
            return;
        }

        visit.setRefererType(VisitLog.REFERER_EXTERNAL);
        visit.setRefererHost(truncateHost(refererHost));
    }

    private static String hostOf(String referer) {
        try {
            String host = URI.create(referer).getHost();
            return host == null ? null : stripPort(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripPort(String host) {
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static String truncateHost(String host) {
        if (host == null) {
            return null;
        }
        return host.length() > HOST_MAX_LENGTH ? host.substring(0, HOST_MAX_LENGTH) : host;
    }

    static String deviceOf(HttpServletRequest request) {
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return VisitLog.DEVICE_PC;
        }
        for (String keyword : MOBILE_UA_KEYWORDS) {
            if (ua.contains(keyword)) {
                return VisitLog.DEVICE_MOBILE;
            }
        }
        return VisitLog.DEVICE_PC;
    }
}
