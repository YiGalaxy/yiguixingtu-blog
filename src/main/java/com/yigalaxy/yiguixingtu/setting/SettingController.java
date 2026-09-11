package com.yigalaxy.yiguixingtu.setting;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.setting.dto.SettingVO;
import com.yigalaxy.yiguixingtu.setting.service.SettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点设置接口（前台公开）。
 *
 * 【路径是 /setting 而不是 /setting/list】这一份数据只有一条，
 * "list"会让人以为它是个列表（与 /about 同理：接口形状要跟数据的形状一致）。
 *
 * 【类上没有 @PreAuthorize，SecurityConfig 里放行了 GET /setting】
 *   位置与写法与 /about、/music/list、/link/list 等完全一致。
 *   后台的保存是 {@link AdminSettingController} 的 PUT（类级 ADMIN）。
 *
 * 【为什么它必须能被【匿名】读到 —— 这一条和别的公开接口不太一样】
 *   其余公开接口是"某个页面要用"，而这个接口的数据驱动的是【整站的外壳】：
 *   页眉的站点名、页脚的版权与备案号、首页的公告与每页条数。
 *   也就是说每一页在渲染时都要读它，包括：
 *     · 未登录访客打开的首页 / 文章页 / 归档页
 *     · 前端的 404 与错误页（error.vue 上也写着站点名）
 *   如果它要求登录，"没登录的人看到的页脚是空的"—— 而页脚里的备案号
 *   是【合规要求】，恰恰最不能让未登录访客看不到。
 *
 * 【永远 200】即使那一行被手工删了，这里也返回一份内置默认值
 * （行为类字段给安全默认值、展示类字段留 null），见 SettingServiceImpl 的注释。
 */
@Slf4j
@Tag(name = "站点设置（前台）", description = "站点名 / 公告 / 评论开关 / 页脚信息 / 每页条数，无需登录")
@RestController
@RequestMapping("/setting")
public class SettingController {

    private final SettingService settingService;

    public SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    /**
     * 站点设置（整份返回）。
     *
     * 【为什么一次返回全部、而不是按字段拆成几个接口】
     *   这些字段虽然互相独立，但读它们的场合是【同一批】：
     *   页眉页脚 + 首页 + 文章页都要用其中至少一项。
     *   拆开之后首页要发三四个请求才能把一屏渲染完，而收益只是"少传几十字节"。
     *   量级上这是一条几十字节的记录，合并返回明显更划算。
     */
    @Operation(summary = "站点设置（无需登录）")
    @GetMapping
    public Result<SettingVO> get() {
        return Result.success(settingService.get());
    }
}
