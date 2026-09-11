package com.yigalaxy.yiguixingtu.link;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkVO;
import com.yigalaxy.yiguixingtu.link.service.FriendLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 友链接口（前台公开）。
 *
 * 【注意这个类上【没有】@PreAuthorize】—— 和 TagController / CategoryController 一样：
 *   它提供的是公开读取能力，而且 SecurityConfig 里已经把 GET /link/list 放行了。
 *   后台的增删改在 {@link AdminLinkController} 里，那里是类级 ADMIN 限制。
 *
 * 【为什么前台只要一个接口】
 *   友链页就是"把能点的卡片列出来"，没有分页（十几个的量级）、
 *   没有筛选（就一份）、也没有详情页（点进去是别人的站点）。
 *   硬加一个分页参数只会让前端多一层无用逻辑 ——
 *   真到了几百条友链的那天，再加分页也不迟（那时接口形状会变，
 *   但那一天来临时这个改动是值得的）。
 */
@Slf4j
@Tag(name = "友链（前台）", description = "友情链接列表，无需登录")
@RestController
@RequestMapping("/link")
public class LinkController {

    private final FriendLinkService friendLinkService;

    public LinkController(FriendLinkService friendLinkService) {
        this.friendLinkService = friendLinkService;
    }

    /**
     * 友链列表（只含"显示"的，按 sort 升序）。
     *
     * 【为什么"只含显示"这件事不能交给前端过滤】
     *   后台把一条友链设成"隐藏"，就是想让它【立刻从前台消失】。
     *   如果接口把隐藏的也返回、由前端决定不渲染，那么：
     *   ① 接口响应里仍然带着"暂时不想公开的站点"，看 DevTools 就能看到；
     *   ② 将来只要有一个消费方（RSS、导出脚本）忘了过滤，隐藏就失效了。
     *   所以过滤写在 SQL 层（status = 1），和"前台评论写死已通过"是同一套做法。
     */
    @Operation(summary = "友链列表（含显示中的，无需登录）")
    @GetMapping("/list")
    public Result<List<FriendLinkVO>> list() {
        return Result.success(friendLinkService.listVisible());
    }
}
