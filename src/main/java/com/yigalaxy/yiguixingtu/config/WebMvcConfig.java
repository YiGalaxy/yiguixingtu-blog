package com.yigalaxy.yiguixingtu.config;

import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * =====================================================================
 * Spring MVC 的定制配置
 *
 * 【这个类为什么存在】
 *   上传的图片被写到了磁盘上的一个目录里（见 LocalFileStorage），
 *   但写进去之后，浏览器还得能通过 URL 访问到它 ——
 *   否则后台上传成功了，前台却是裂图。
 *
 *   所以这里把 {@code /uploads/**} 这个 URL 路径映射到那个磁盘目录：
 *   请求 /uploads/cover/2026/09/xxx.jpg 时，Spring 直接从磁盘读文件返回。
 *
 * 【用到的东西】
 *   · {@link WebMvcConfigurer} —— Spring MVC 的扩展接口（不是"必须继承的基类"，
 *     而是一组可选回调，按需实现其中一两个方法即可，不会影响其它默认行为）
 *   · {@link ResourceHandlerRegistry} —— 注册"URL 路径 → 资源位置"的映射，
 *     由 Spring 的 ResourceHttpRequestHandler 负责读文件、处理 Range 请求、
 *     设置 Content-Type 与缓存头 —— 这些都是现成的，不需要自己写
 *   · {@code file:} 前缀 —— 表示"这是文件系统路径"，相对的是 {@code classpath:}。
 *     路径末尾的斜杠是必须的，否则会被当成单个文件
 *
 * 【⚠️ 生产环境建议把这一步交给 Nginx】
 *   打静态文件这件事 Nginx 比 Java 更擅长（零拷贝、更省内存）。
 *   线上可以让 Nginx 直接 location /uploads/ { alias /srv/uploads/; }，
 *   那时这个映射就闲置了，不影响功能。
 *   （本项目目前的 Nginx 配置是把 /api/uploads/ 反代回后端，见后端 README
 *     「Nginx 反向代理」章节 —— 两种做法都行，反代的好处是"图片与接口同源"，
 *     不额外占用一个公网路径，也就少一处要单独配缓存与限流的入口。）
 * =====================================================================
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;

    public WebMvcConfig(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 目录不存在也不报错：第一次上传时会自动创建（见 LocalFileStorage）
        String dir = Paths.get(uploadProperties.getLocalDir()).toAbsolutePath().normalize().toString();
        // 统一成 / 分隔符并补上结尾斜杠：Windows 上是反斜杠，而 Spring 的资源位置要求用 /
        String location = "file:" + dir.replace('\\', '/') + "/";

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);

        log.info("本地静态资源映射: /uploads/** -> {}", location);
    }
}
