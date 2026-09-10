package com.yigalaxy.yiguixingtu.category;

import com.yigalaxy.yiguixingtu.category.dto.CategoryVO;
import com.yigalaxy.yiguixingtu.category.service.CategoryService;
import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口（前台公开）。
 *
 * 【注意】这个类上【没有】@PreAuthorize —— 因为它提供的是公开读取能力，
 * 而且 SecurityConfig 里已经把 GET /category/list 放行了。
 * 对比一下 UserController 类上那个 @PreAuthorize("hasRole('ADMIN')")，
 * 就能看出"公开接口"和"后台接口"的区别。
 */
@Slf4j
@Tag(name = "分类（前台）", description = "分类列表，无需登录")
@RestController
@RequestMapping("/category")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "全部分类")
    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listAll());
    }
}
