package com.yigalaxy.blog.site;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SiteController {
    @Value("${blog.announcement:}")
    private String announcement;

    @GetMapping("/health")
    public Health health() {
        return new Health("ok");
    }

    @GetMapping("/categories")
    public List<Option> categories() {
        return List.of();
    }

    @GetMapping("/tags")
    public List<Option> tags() {
        return List.of();
    }

    @GetMapping("/site-settings")
    public SiteSettings settings() {
        return new SiteSettings(announcement);
    }

    public record Health(String status) { }
    public record Option(long id, String name) { }
    public record SiteSettings(String announcement) { }
}
