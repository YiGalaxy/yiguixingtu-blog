package com.yigalaxy.yiguixingtu.article;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.dto.ArticleArchiveVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleRssVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.common.Result;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文章接口（前台公开，无需登录）。
 *
 * 【这个类和 AdminArticleController 的关系】
 * 用同一张表、同一个 Service，但走不同的方法：
 *   这里全部调 xxxPublished 那一组 -> 只看得见已发布的
 *   后台调 pageAll / getDetail        -> 草稿也看得见
 * 权限边界在 Service 方法名上就写清楚了。
 */
@Slf4j
@Tag(name = "文章（前台）", description = "首页信息流、文章详情，无需登录")
@RestController
@RequestMapping("/article")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /**
     * 前台文章分页列表。
     *
     * 【限流：300 次 / 分钟（配置在 application.properties）】
     *   它是全站最热的读接口、又是公开的：首页、分类页、搜索都打它。
     *   缓存命中时成本很低，所以额度给得宽松；但再宽松也要有上限 ——
     *   万一缓存被穿透（有人拿随机关键词刷），请求会全落到数据库上，
     *   这一层就是防止"缓存失效 + 高频访问"把数据库打死的那道闸。
     *
     *   和登录接口一样，这是【整站】配额而不是按 IP 的配额，
     *   按 IP 那层在 Nginx（见 README 部署章节的 limit_req 配置）。
     */
    @Operation(summary = "已发布文章分页（有限流）")
    @RateLimiter(name = "articlePageRateLimiter")
    @GetMapping("/page")
    public Result<IPage<ArticleVO>> page(ArticleQuery query) {
        return Result.success(articleService.pagePublished(query));
    }

    @Operation(summary = "站点统计（文章数 / 总浏览量 / 分类数）")
    @GetMapping("/stats")
    public Result<ArticleStatsVO> stats() {
        return Result.success(articleService.stats());
    }

    /**
     * 归档：已发布文章按年月分组（最新的月份在前）。
     *
     * 【为什么路径是 /article/archive 而不是 /archive】它属于文章资源的一个"视图"，
     * 和 /article/stats 是同一类东西（都是文章的只读汇总），放在 /article 下面语义最清楚。
     *
     * 【路由顺序提醒】`/article/archive` 是具体字符串，而 `/article/{id}` 是"任意一段"。
     * Spring MVC 会优先匹配更具体的那个，所以归档不会被详情接口抢走
     * —— ArticleStatsTest 里有一条用例专门盯着这件事（/article/stats 不被抢走）。
     */
    @Operation(summary = "归档（按年月分组，仅已发布）")
    @GetMapping("/archive")
    public Result<ArticleArchiveVO> archive() {
        return Result.success(articleService.archive());
    }

    /**
     * RSS 订阅源的数据（最近 20 篇已发布文章的正文）。
     *
     * 【为什么后端只给数据、不直接输出 XML】
     *   XML/RSS 的格式细节属于"怎么呈现"，而站点域名、feed 标题这些只有前端知道
     *   （前端的 sitemap.xml / robots.txt 已经是同一个做法：Node 运行时路由按数据现算）。
     *   两边分工是：**后端给数据，前端拼格式** ——
     *   这样 RSS 的字段和站点信息不会被复制到两个仓库里各维护一份。
     */
    @Operation(summary = "RSS 数据（最近 20 篇，含正文）")
    @GetMapping("/rss")
    public Result<List<ArticleRssVO>> rss() {
        return Result.success(articleService.rssItems());
    }

    @Operation(summary = "文章详情")
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@PathVariable Long id) {
        return Result.success(articleService.getPublishedDetail(id));
    }
}