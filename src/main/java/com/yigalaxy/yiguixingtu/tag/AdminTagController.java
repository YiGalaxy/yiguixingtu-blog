package com.yigalaxy.yiguixingtu.tag;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.tag.dto.TagForm;
import com.yigalaxy.yiguixingtu.tag.dto.TagVO;
import com.yigalaxy.yiguixingtu.tag.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签管理接口（后台，仅管理员）。
 *
 * 【权限写法与 UserController / AdminArticleController / UploadController 完全一致】
 *   类上直接 {@code @PreAuthorize("hasRole('ADMIN')")}。
 *   写成类级注解的好处是：以后往这个类里加新接口时不会漏加权限注解。
 *   三种身份的表现：
 *     不带 token      → 401（过滤器层拦住，不知道你是谁）
 *     游客带合法 token → 403（知道你是谁，但你不能干这个）
 *     管理员          → 正常返回
 *
 * 【为什么路径是 /admin/tag 而不是 /tag】
 *   前台已经有 GET /tag/list（公开）。把写操作放到 /admin/ 前缀下，
 *   一眼就能看出"这是后台接口"；SecurityConfig 里也不用为它单独写规则
 *   （anyRequest().authenticated() 已经覆盖，禁止访问靠 @PreAuthorize）。
 */
@Slf4j
@Tag(name = "标签（后台）", description = "标签的增删改查，仅管理员")
@RestController
@RequestMapping("/admin/tag")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTagController {

    private final TagService tagService;

    public AdminTagController(TagService tagService) {
        this.tagService = tagService;
    }

    /**
     * 标签列表（后台）。
     * 【它和前台 /tag/list 的区别】形状一样，但后台这一份【不走缓存】——
     * 管理员刚建完标签就应当立刻看到，缓存只会带来"是不是没保存成功"的疑惑。
     */
    @Operation(summary = "标签列表（后台，不走缓存）")
    @GetMapping("/list")
    public Result<List<TagVO>> list() {
        return Result.success(tagService.listAll());
    }

    @Operation(summary = "新建标签")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody TagForm form) {
        return Result.success(tagService.create(form));
    }

    @Operation(summary = "编辑标签")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TagForm form) {
        tagService.update(id, form);
        return Result.success();
    }

    /**
     * 删除标签。
     *
     * 【为什么这个接口不返回"是否删掉了多少关联"】
     *   关联数在审计记录（operation_log 的 DELETE_TAG）里留了，
     *   接口返回它只会让前端多一个不知道怎么用的字段。
     */
    @Operation(summary = "删除标签")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.success();
    }
}
