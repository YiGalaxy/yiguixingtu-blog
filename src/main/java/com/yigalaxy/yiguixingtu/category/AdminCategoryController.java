package com.yigalaxy.yiguixingtu.category;

import com.yigalaxy.yiguixingtu.category.dto.CategoryForm;
import com.yigalaxy.yiguixingtu.category.dto.CategoryVO;
import com.yigalaxy.yiguixingtu.category.service.CategoryService;
import com.yigalaxy.yiguixingtu.common.Result;
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
 * 分类管理接口（后台，仅管理员）。
 *
 * 【为什么分类现在才有写接口】
 *   分类从一开始就有（文章必须属于一个分类），但一直只能靠 SQL 手工加 ——
 *   这在本地开发时无所谓，**部署上线之后就很难受了**：站长写了一篇新文章，
 *   想加一个分类，还得 ssh 上服务器进 MySQL 敲 INSERT。
 *   所以这一批把它补齐（和标签的后台管理是同一套形状）。
 *
 * 【权限写法与其它后台接口一致】类级 {@code @PreAuthorize("hasRole('ADMIN')")}，
 * 以后往这个类里加方法不会漏加注解。
 */
@Slf4j
@Tag(name = "分类（后台）", description = "分类的增删改，仅管理员")
@RestController
@RequestMapping("/admin/category")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 分类列表（后台）。
     * 【为什么不另写一个"不走缓存"的版本】和标签不同：
     *   分类列表本来就带缓存、而且写操作会推进版本号让缓存立刻失效，
     *   所以后台看到的和前台看到的一定是同一份最新数据 ——
     *   再写一个绕过缓存的方法只会多一处要维护的东西。
     */
    @Operation(summary = "分类列表（后台）")
    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listAll());
    }

    @Operation(summary = "新建分类")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CategoryForm form) {
        return Result.success(categoryService.create(form));
    }

    @Operation(summary = "编辑分类")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategoryForm form) {
        categoryService.update(id, form);
        return Result.success();
    }

    /**
     * 删除分类。
     * 【⚠️ 分类下还有文章时会被拒绝（400 + 提示还有几篇）】
     *   这是刻意的：删掉分类后那些文章仍然引用着它的 id，
     *   而分类查询已经看不到它 —— 文章会变成"没有分类名"，且看不出原因。
     *   拒绝并告诉用户先去调整那些文章，比留下这种数据不一致要好。
     */
    @Operation(summary = "删除分类（分类下有文章时会被拒绝）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
