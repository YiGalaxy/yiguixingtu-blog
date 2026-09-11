package com.yigalaxy.yiguixingtu.about;

import com.yigalaxy.yiguixingtu.about.dto.AboutForm;
import com.yigalaxy.yiguixingtu.about.service.AboutService;
import com.yigalaxy.yiguixingtu.common.Result;
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
 * 关于页管理接口（后台，仅管理员）。
 *
 * 【它只有一个接口：PUT /admin/about】
 *   关于页的数据只有一份：
 *     · 没有"新建"——那一行由 V10 迁移脚本插好，而且"再建一条关于信息"没有语义
 *     · 没有"删除"——不要了就清空字段（真删了前台会打开一个空页面，
 *       而站长又没有任何入口把它建回来）
 *     · 路径上没有 {id} —— 只有一个能改的对象，留一个 id 参数只会多一个
 *       "传错 id 改到别人"的可能
 *
 * 【为什么后台不另开一个 GET】
 *   前台那个 GET /about 就是这份数据的唯一读法：它返回的字段与后台要填的表单一模一样，
 *   而且保存会推进缓存版本号、缓存立刻失效 —— 后台读到的一定是最新的。
 *   再开一个"后台专用读"只是多一处要维护、要写测试的东西。
 *   （和分类后台列表"不另写一个绕过缓存的版本"是同一个判断。）
 *
 * 【权限】类级 {@code @PreAuthorize("hasRole('ADMIN')")}：
 *   无 token → 401（过滤器层），GUEST + 合法 token → 403，ADMIN → 正常返回。
 */
@Slf4j
@Tag(name = "关于（后台）", description = "关于页信息的保存，仅管理员")
@RestController
@RequestMapping("/admin/about")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAboutController {

    private final AboutService aboutService;

    public AdminAboutController(AboutService aboutService) {
        this.aboutService = aboutService;
    }

    /**
     * 保存关于页信息（单条更新）。
     *
     * 【为什么用 PUT 而不是 POST / PATCH】
     *   PUT 的语义就是"把这个资源整体替换成我提交的内容"，而前台的表单是
     *   【整份覆盖式提交】（所有字段一起提交，没传的字段按清空处理）——
     *   语义完全对得上。POST 会被理解成"新建"，PATCH 则是"部分更新"，
     *   而我们的语义刻意不是部分更新（见 AboutForm 的注释）。
     *
     * 【返回 Result&lt;Void&gt;，不返回保存后的对象】
     *   和其他后台保存接口（标签 / 分类 / 友链 / 项目 / 收藏）保持一致：
     *   前端要回显就再读一次 GET /about（缓存已失效，拿到的一定是最新的）。
     *   为这一个接口单独返回对象，会让前端的处理方式多出一种分支。
     */
    @Operation(summary = "保存关于页信息（单条更新）")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody AboutForm form) {
        aboutService.update(form);
        return Result.success();
    }
}
