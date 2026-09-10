package com.yigalaxy.yiguixingtu.article;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 文章管理接口（后台专用）。
 * 类上的 @PreAuthorize 表示"这个类里所有接口都得是管理员"，
 * 和 UserController 完全一致。
 */
@Slf4j
@Tag(name = "文章管理", description = "后台文章管理接口（仅管理员）")
@RestController
@RequestMapping("/admin/article")
@PreAuthorize("hasRole('ADMIN')")
public class AdminArticleController {

    private final ArticleService articleService;

    public AdminArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @Operation(summary = "文章分页（含草稿）")
    @GetMapping("/page")
    public Result<IPage<ArticleVO>> page(ArticleQuery query) {
        return Result.success(articleService.pageAll(query));
    }

    @Operation(summary = "文章详情（含草稿）")
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@PathVariable Long id) {
        return Result.success(articleService.getDetail(id));
    }

    @Operation(summary = "新建文章")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ArticleForm form) {
        Long id = articleService.create(form, getCurrentUserId());
        return Result.success(id);
    }

    @Operation(summary = "编辑文章")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ArticleForm form) {
        articleService.update(id, form);
        return Result.success();
    }

    @Operation(summary = "发布 / 下架")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        articleService.updateStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "删除文章")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        articleService.remove(id);
        return Result.success();
    }

    /** 取当前登录用户ID，用作文章作者 */
    private Long getCurrentUserId() {
        LoginUser loginUser = (LoginUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return loginUser.getUser().getId();
    }
}