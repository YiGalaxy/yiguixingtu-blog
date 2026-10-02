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

/** 前台公开站点设置接口；匿名访问用于渲染全站外壳和备案信息。 */
@Slf4j
@Tag(name = "站点设置（前台）", description = "站点名 / 公告 / 评论开关 / 页脚信息 / 每页条数，无需登录")
@RestController
@RequestMapping("/setting")
public class SettingController {

    private final SettingService settingService;

    public SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    /** 返回整份站点设置。 */
    @Operation(summary = "站点设置（无需登录）")
    @GetMapping
    public Result<SettingVO> get() {
        return Result.success(settingService.get());
    }
}
