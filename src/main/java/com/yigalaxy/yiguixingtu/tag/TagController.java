package com.yigalaxy.yiguixingtu.tag;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.tag.dto.TagVO;
import com.yigalaxy.yiguixingtu.tag.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签接口（前台公开）。
 *
 * 【注意这个类上【没有】@PreAuthorize】—— 和 CategoryController 一样，
 * 它提供的是公开读取能力，而且 SecurityConfig 里已经把 GET /tag/list 放行了。
 * 后台的增删改在 {@link AdminTagController} 里，那里是类级 ADMIN 限制。
 *
 * 【类名 TagController 与下面 import 的 @Tag 注解会不会冲突】
 *   不会。这里的 {@code @Tag} 是 springdoc（OpenAPI）用来给接口分组的注解，
 *   而项目里的标签【实体类】叫 tag.entity.Tag —— 本类不引用那个实体，
 *   返回的是 TagVO，所以没有同名冲突。
 *   （真要在同一个文件里同时用到两者，得写全限定名 —— 这里刻意避开了这件事，
 *     因为 Controller 本来就不该直接暴露实体。）
 */
@Slf4j
@Tag(name = "标签（前台）", description = "标签列表，无需登录")
@RestController
@RequestMapping("/tag")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /**
     * 全部标签（带每个标签下【已发布】的文章数）。
     *
     * 【为什么带文章数】标签云要按热度展示、也可能显示数量；
     * 前端要为每个标签单独发一次请求去数是典型的 N+1，
     * 这里一条 GROUP BY 就全都算好了（见 TagMapper.countPublishedByTag）。
     */
    @Operation(summary = "全部标签（含已发布文章数）")
    @GetMapping("/list")
    public Result<List<TagVO>> list() {
        return Result.success(tagService.listPublished());
    }
}
