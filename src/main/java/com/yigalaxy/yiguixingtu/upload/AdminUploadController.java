package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * =====================================================================
 * 后台「存储管理」接口：看磁盘上有什么、谁在用它、删掉没人用的
 *
 * 【为什么单独一个 Controller，而不是塞进 UploadController】
 *   两者管的是两件事：
 *     · {@link UploadController}（{@code /upload}）—— 编辑器在用的**动作**接口：
 *       "把这份文件存下来"，返回一个地址。它服务于"正在写文章"这一刻。
 *     · 这里（{@code /admin/upload}）—— 管理员的**运维**页面：
 *       看占用、查引用、清理垃圾。它服务于"回头打理这个站"这一刻。
 *   权限要求相同（都是 ADMIN），但会话场景、返回的数据量、失败的影响面都不一样
 *   （一个失败是"这张图传不上"，另一个失败是"删错了文件"）。
 *   混在一个类里的话，那个类会同时长成"上传接口"和"运维控制台"两个方向。
 *
 * 【路径前缀为什么是 /admin/upload】与项目里其它后台接口一致
 *   （/admin/article、/admin/project……）：一眼能看出"这是后台的、不是公开的"。
 *   ⚠️ 它和 {@code /uploads/**}（图片的公开访问地址）没有关系，别混：
 *   那个是静态资源映射，写在 WebMvcConfig 里，实测也在这里的鉴权链之外。
 * =====================================================================
 */
@Slf4j
@Tag(name = "存储管理", description = "后台上传文件管理（仅管理员）")
@RestController
@RequestMapping("/admin/upload")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUploadController {

    private final UploadStorageService uploadStorageService;

    public AdminUploadController(UploadStorageService uploadStorageService) {
        this.uploadStorageService = uploadStorageService;
    }

    /**
     * 存储清单：占用概览 + 每个文件的大小 / 类型 / 修改时间 / **被哪些地方引用**。
     *
     * 【为什么不做分页】见 {@link UploadStorageVO} 的注释：引用清单是一次全量构建的，
     * 分页反而要多一套"索引跨请求缓存"的状态，而文件数受上传总量护栏约束。
     */
    @Operation(summary = "存储清单（含每个文件的引用来源）")
    @GetMapping("/storage")
    public Result<UploadStorageVO> storage() {
        return Result.success(uploadStorageService.overview());
    }

    /**
     * 删除若干文件 —— **只删此刻确实没人引用的那些**。
     *
     * 【为什么在这里再确认一遍"没人用"】接口收的是"要删哪些 key"，
     *   但"这些 key 没人用"这件事必须由服务端当场重算：页面上的引用清单是
     *   打开那一刻建的，而删除是不可逆的（详见 UploadStorageService.deleteFiles 的注释）。
     *
     * 返回的是**逐类的报告**：删掉的、因为仍被引用而跳过的、失败的 —— 见 {@link UploadDeleteReport}。
     */
    @Operation(summary = "删除上传文件（仅限无人引用的）")
    @PostMapping("/delete-files")
    public Result<UploadDeleteReport> deleteFiles(@Valid @RequestBody UploadDeleteForm form) {
        return Result.success(uploadStorageService.deleteFiles(form.keys()));
    }
}
