package com.yigalaxy.yiguixingtu.favorite;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteForm;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteVO;
import com.yigalaxy.yiguixingtu.favorite.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏管理接口（后台，仅管理员）。
 *
 * 【权限写法与其它后台接口完全一致】类级 {@code @PreAuthorize("hasRole('ADMIN')")}：
 * 无 token → 401（过滤器层），GUEST + 合法 token → 403，ADMIN → 正常返回。
 */
@Slf4j
@Tag(name = "收藏（后台）", description = "收藏的增删改查，仅管理员")
@RestController
@RequestMapping("/admin/favorite")
@PreAuthorize("hasRole('ADMIN')")
public class AdminFavoriteController {

    private final FavoriteService favoriteService;

    public AdminFavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /** 收藏列表（后台，含隐藏的，不走缓存 —— 管理员刚点完"隐藏"就要看到效果） */
    @Operation(summary = "收藏列表（后台，含隐藏，不走缓存）")
    @GetMapping("/list")
    public Result<List<FavoriteVO>> list() {
        return Result.success(favoriteService.listAll());
    }

    @Operation(summary = "新建收藏")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody FavoriteForm form) {
        return Result.success(favoriteService.create(form));
    }

    @Operation(summary = "编辑收藏")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody FavoriteForm form) {
        favoriteService.update(id, form);
        return Result.success();
    }

    @Operation(summary = "删除收藏（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        favoriteService.delete(id);
        return Result.success();
    }
}
