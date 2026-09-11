package com.yigalaxy.yiguixingtu.setting;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.setting.dto.SettingForm;
import com.yigalaxy.yiguixingtu.setting.service.SettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点设置管理接口（后台，仅管理员）。
 *
 * 【它只有一个接口：PUT /admin/setting】与 AdminAboutController 完全同形：
 *   · 没有"新建"——那一行由 V12 迁移脚本插好，"再建一份站点设置"没有语义
 *   · 没有"删除"——不要了就改回默认值（真删了前台会拿默认值，
 *     而站长又没有任何入口把它建回来）
 *   · 路径上没有 {id} —— 只有一个能改的对象，留一个 id 参数只会多一个
 *     "传错 id 改到别处"的可能
 *
 * 【为什么后台不另开一个 GET】
 *   前台那个 GET /setting 就是这份数据的唯一读法：字段与后台要填的表单一模一样，
 *   而且保存会推进缓存版本号、缓存立刻失效 —— 后台读到的一定是最新的。
 *   再开一个"后台专用读"只是多一处要维护、要写测试的东西
 *   （与 AdminAboutController 的判断一致）。
 *
 * 【权限】类级 {@code @PreAuthorize("hasRole('ADMIN')")}：
 *   无 token → 401（过滤器层），GUEST + 合法 token → 403，ADMIN → 正常返回。
 */
@Slf4j
@Tag(name = "站点设置（后台）", description = "站点设置的保存，仅管理员")
@RestController
@RequestMapping("/admin/setting")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingController {

    private final SettingService settingService;

    public AdminSettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    /**
     * 保存站点设置（单条更新）。
     *
     * 【为什么用 PUT 而不是 POST / PATCH】与 AdminAboutController 同一条理由：
     *   PUT 的语义就是"把这个资源整体替换成我提交的内容"，而后台的表单是
     *   【整份覆盖式提交】（所有字段一起提交，没传的可选字段按清空处理）——
     *   语义完全对得上。POST 会被理解成"新建"，PATCH 则是"部分更新"，
     *   而我们的语义刻意不是部分更新（否则"清空公告"就没有表达方式了）。
     *
     * 【返回 Result&lt;Void&gt;，不返回保存后的对象】
     *   与其他后台保存接口保持一致：前端要回显就再读一次 GET /setting
     *   （缓存已失效，拿到的一定是最新的）。
     */
    @Operation(summary = "保存站点设置（单条更新）")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody SettingForm form) {
        settingService.update(form);
        return Result.success();
    }
}
