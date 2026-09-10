package com.yigalaxy.yiguixingtu.article;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.article.dto.ArticleVO;
import com.yigalaxy.yiguixingtu.article.service.ArticleService;
import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.common.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
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

    /**
     * 幂等键的作用域。
     * 带上业务名之后，即使别的接口用了同一个 UUID 也不会互相干扰，
     * 而且以后要排查"这个键是哪来的"时，从 Redis key 上就能看出来。
     */
    private static final String SCOPE_CREATE = "article:create";

    private final ArticleService articleService;
    private final IdempotencyService idempotency;

    public AdminArticleController(ArticleService articleService, IdempotencyService idempotency) {
        this.articleService = articleService;
        this.idempotency = idempotency;
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

    /**
     * 新建文章（支持幂等）。
     *
     * 【为什么只有这个接口做幂等，其它写接口不做】
     *   幂等解决的是"同一个【创建】请求被重复发来"的问题 ——
     *   只有 POST 这种"每次都产生一条新数据"的操作才会因此出错。
     *   PUT /{id}（编辑）、PUT /{id}/status（改名状态）、DELETE /{id}
     *   它们本身就是幂等的：执行两次的结果和执行一次一样
     *   （编辑两次结果相同；删两次第二次影响 0 行但也不报错）。
     *   给本来就幂等的接口加幂等键，只是徒增复杂度。
     *
     * 【为什么幂等键是可选的（required = false）】
     *   不带这个头的老请求（比如 Swagger 里手点、或者将来别的前端）
     *   行为必须和以前完全一样 —— 否则就是一次破坏性改动。
     *   带上才启用保护，前端可以平滑升级。
     *
     * 【为什么三步（占位 / 执行 / 写结果）写在 Controller 而不是 Service】
     *   幂等是【接口层】的语义：同一个"请求"只生效一次，
     *   而"请求"这个概念只存在于 HTTP 这一层（靠请求头传递）。
     *   Service 层不该知道幂等键长什么样。
     *   放这里还有个好处：三个步骤一眼可见，不用跳进 Service 才能看全。
     */
    @Operation(summary = "新建文章（支持 Idempotency-Key 幂等头）")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ArticleForm form,
                               @RequestHeader(value = "Idempotency-Key", required = false)
                               String idempotencyKey) {

        // 没带幂等键：老行为，直接创建
        if (!StringUtils.hasText(idempotencyKey)) {
            return Result.success(articleService.create(form, getCurrentUserId()));
        }

        IdempotencyService.Claim claim = idempotency.claim(SCOPE_CREATE, idempotencyKey);

        if (claim.processing()) {
            // 另一个一模一样的请求正在处理：不重复执行，也不假装成功
            throw new BusinessException(ResultCode.DUPLICATE_SUBMIT);
        }
        if (!claim.acquired()) {            // 这个请求之前已经成功处理过了：把上次的结果原样返回。
            // 对调用方来说，重试和第一次成功长得一模一样 —— 这正是"幂等"的定义。
            log.info("幂等命中，直接返回上次创建的文章 id={}（未重复创建）", claim.previousResult());
            return Result.success(Long.valueOf(claim.previousResult()));
        }

        try {
            Long id = articleService.create(form, getCurrentUserId());
            // 先创建成功、再记结果。顺序反了的话，
            // 一旦创建失败就会留下一个"处理过了"的假记录，
            // 用户重试时会被当成重复请求直接返回一个并不存在的 id。
            idempotency.complete(SCOPE_CREATE, idempotencyKey, String.valueOf(id));
            return Result.success(id);
        } catch (RuntimeException e) {
            // 失败必须释放占位，否则用户在占位 TTL 内重试都会得到"正在处理中"，
            // 一个没有原因也无法自救的失败
            idempotency.release(SCOPE_CREATE, idempotencyKey);
            throw e;
        }
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