package com.yigalaxy.yiguixingtu.config;

import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
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
 *
 * 【2026-09 起它还负责第二件事：注册"附件强制下载"的过滤器】
 *   "把文件发出去"和"告诉浏览器该怎么对待这个文件"是同一件事的两半，
 *   所以下载头过滤器（{@link UploadResponseHeaderFilter}）也在这里注册，
 *   见下面那个 @Bean 的注释。
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

    /**
     * 把"附件强制下载"的响应头过滤器只挂在 {@code /uploads/*} 上。
     *
     * 【为什么这里要显式注册，而不是给过滤器加 @Component】
     *   一个 @Component 的 Filter Bean 会被 Spring Boot【自动注册到 /*】——
     *   于是每一个接口请求（包括所有 JSON 接口）都要多走一层过滤器，
     *   而它对我们毫无意义：那里根本没有 /uploads/ 路径。
     *   用 {@link FilterRegistrationBean} 明确写死 URL 映射，
     *   既省掉这些无用开销，也让"这个过滤器只管静态文件"这件事在配置上一眼可见。
     *
     * 【为什么放在 WebMvcConfig 里】
     *   它和上面那个静态映射是同一件事的两半：一个负责"把文件发出去"，
     *   一个负责"告诉浏览器该怎么对待这个文件"。放在一起读，
     *   就不会出现"改了目录却忘了改下载头"这种不一致
     *   （这也是为什么过滤器要读 UploadProperties 里的前缀，而不是写死 "attachment"）。
     *
     * 【顺序】默认是最低的（LOWEST_PRECEDENCE）—— 它只是设响应头，
     *   不需要抢在谁前面；而服务器最终以最后一次 setHeader 为准，
     *   不会和 Security 的 nosniff 互相覆盖成两个值。
     */
    @Bean
    public FilterRegistrationBean<UploadResponseHeaderFilter> uploadResponseHeaderFilter() {
        FilterRegistrationBean<UploadResponseHeaderFilter> registration =
                new FilterRegistrationBean<>(new UploadResponseHeaderFilter(uploadProperties));
        registration.addUrlPatterns("/uploads/*");
        registration.setName("uploadResponseHeaderFilter");
        return registration;
    }
}
