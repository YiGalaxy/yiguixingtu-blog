package com.yigalaxy.yiguixingtu.favorite;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteVO;
import com.yigalaxy.yiguixingtu.favorite.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏接口（前台公开）。
 *
 * 【类上没有 @PreAuthorize，SecurityConfig 里放行了 GET /favorite/list】
 *   位置与写法与 /tag/list、/category/list、/link/list、/project/list 完全一致。
 *   后台的增删改在 {@link AdminFavoriteController}（类级 ADMIN）。
 *
 * 【为什么一次返回全部（不分页、不按分组查）】
 *   收藏是几十条的量级，一次返回最省事；分组由前端做（后端不 GROUP BY 的理由
 *   见 FavoriteMapper 的注释）。等真到了几百条再谈分页 —— 那时接口形状会变，
 *   但"先简单、按需演进"比"提前设计一个没人用的分页参数"更划算。
 */
@Slf4j
@Tag(name = "收藏（前台）", description = "收藏列表，无需登录")
@RestController
@RequestMapping("/favorite")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /** 收藏列表（只含"显示"的，按 sort 升序；返回里带分组名，前端自行分组） */
    @Operation(summary = "收藏列表（含显示中的，无需登录）")
    @GetMapping("/list")
    public Result<List<FavoriteVO>> list() {
        return Result.success(favoriteService.listVisible());
    }
}
