package com.yigalaxy.yiguixingtu.about;

import com.yigalaxy.yiguixingtu.about.dto.AboutVO;
import com.yigalaxy.yiguixingtu.about.service.AboutService;
import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关于页接口（前台公开）。
 *
 * 【路径是 /about 而不是 /about/list】这一份数据只有一条，
 * "list"会让人以为它是个列表。接口形状要跟数据的形状一致 ——
 * 同理它也没有分页参数和 id 参数（只有一个"读它"的动作）。
 *
 * 【类上没有 @PreAuthorize，SecurityConfig 里放行了 GET /about】
 *   位置与写法与 /tag/list、/category/list、/link/list 等完全一致。
 *   后台的保存是 {@link AdminAboutController} 的 PUT（类级 ADMIN）。
 */
@Slf4j
@Tag(name = "关于（前台）", description = "关于页信息，无需登录")
@RestController
@RequestMapping("/about")
public class AboutController {

    private final AboutService aboutService;

    public AboutController(AboutService aboutService) {
        this.aboutService = aboutService;
    }

    /**
     * 关于页信息（昵称 / 头像 / 自我介绍 / 联系方式）。
     *
     * 【为什么返回对象而不是数组】数据只有一份，前端拿到的应当是
     * {@code data: {...}} 而不是 {@code data: [{...}]} ——
     * 让"只有一条"这件事由接口形状直接表达，前端就不用写 data[0] 这种
     * "万一为空就崩"的代码。
     *
     * 【永远 200】即使那一行被人手工删了，这里也会返回一个内容为空的壳
     * （公开页面不该因为"某条数据缺失"变成错误页），见 AboutServiceImpl 的注释。
     */
    @Operation(summary = "关于页信息（无需登录）")
    @GetMapping
    public Result<AboutVO> get() {
        return Result.success(aboutService.get());
    }
}
