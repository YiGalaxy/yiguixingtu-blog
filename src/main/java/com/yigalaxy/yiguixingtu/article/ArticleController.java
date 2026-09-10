package com.yigalaxy.yiguixingtu.article;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleStatsVO;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "已发布文章分页")
    @GetMapping("/page")
    public Result<IPage<ArticleVO>> page(ArticleQuery query) {
        return Result.success(articleService.pagePublished(query));
    }

    @Operation(summary = "站点统计（文章数 / 总浏览量 / 分类数）")
    @GetMapping("/stats")
    public Result<ArticleStatsVO> stats() {
        return Result.success(articleService.stats());
    }

    @Operation(summary = "文章详情")
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@PathVariable Long id) {
        return Result.success(articleService.getPublishedDetail(id));
    }
}