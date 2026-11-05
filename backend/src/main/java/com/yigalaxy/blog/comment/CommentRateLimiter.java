package com.yigalaxy.blog.comment;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class CommentRateLimiter {
    private static final int MAX_PER_WINDOW = 5;
    private static final long WINDOW_SECONDS = 900;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= WINDOW_SECONDS) {
                window.startedAt = now;
                window.count = 0;
            }
            window.count++;
            if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_SECONDS);
            return window.count <= MAX_PER_WINDOW;
        }
    }

    private static final class Window {
        private long startedAt;
        private int count;
        private Window(long startedAt) { this.startedAt = startedAt; }
    }
}
