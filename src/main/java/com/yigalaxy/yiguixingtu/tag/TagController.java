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

/** 前台公开标签接口。 */
@Slf4j
@Tag(name = "标签（前台）", description = "标签列表，无需登录")
@RestController
@RequestMapping("/tag")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /** 返回标签及其已发布文章数。 */
    @Operation(summary = "全部标签（含已发布文章数）")
    @GetMapping("/list")
    public Result<List<TagVO>> list() {
        return Result.success(tagService.listPublished());
    }
}
