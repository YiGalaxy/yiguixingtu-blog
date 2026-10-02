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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 文章公开接口；所有读取只返回已发布内容。 */
@Slf4j
@Tag(name = "文章（前台）", description = "首页信息流、文章详情，无需登录")
@RestController
@RequestMapping("/article")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /** 分页查询已发布文章；限流保护公开读接口。 */
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

    /** 按年月返回已发布文章归档。 */
    @Operation(summary = "归档（按年月分组，仅已发布）")
    @GetMapping("/archive")
    public Result<ArticleArchiveVO> archive() {
        return Result.success(articleService.archive());
    }

    /** 返回最近 20 篇已发布文章的 RSS 数据，由前端路由负责输出 XML。 */
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

    @Operation(summary = "记一次浏览（详情页挂载后上报）")
    @PostMapping("/{id}/view")
    public Result<Integer> view(@PathVariable Long id) {
        return Result.success(articleService.recordView(id));
    }
}
