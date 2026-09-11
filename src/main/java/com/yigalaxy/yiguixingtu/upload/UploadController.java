package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * =====================================================================
 * 文件上传接口
 *
 * 【权限设计】类上直接加 {@code @PreAuthorize("hasRole('ADMIN')")}，
 *   和 UserController、AdminArticleController 保持一致：
 *   上传是写操作，而且会消耗存储与流量，不能对普通用户开放。
 *   写成类级注解的好处是——以后往这个类里加新接口时不会漏加权限注解。
 *
 *   三种身份的表现（与项目其它管理接口一致）：
 *     不带 token           → 401（过滤器层拦住，不知道你是谁）
 *     游客带合法 token      → 403（知道你是谁，但你不能干这个）
 *     管理员               → 正常返回
 *
 * 【为什么单独建一个 upload 包，而不是塞进 article】
 *   上传不是"文章"的能力，将来头像、评论配图都要用它。
 *   放在独立模块里，文章模块只依赖"上传后拿到的 URL"，不关心存到哪。
 *
 * 【用到的东西】
 *   · {@code @RequestParam("file") MultipartFile} —— 接收 multipart/form-data
 *     里名为 file 的那一部分。前端用 FormData 拼表单上传即可。
 *   · {@link Result} —— 统一返回包装 {code, message, data}
 * =====================================================================
 */
@Tag(name = "文件上传")
@RestController
@RequestMapping("/upload")
@PreAuthorize("hasRole('ADMIN')")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * 上传一个文件，返回可访问的 URL。
     *
     * 【{@code type} 参数决定按哪一套规则校验】
     *   不传 / {@code type=image} → 图片：{@code jpg/jpeg/png/gif/webp}，上限 5MB，
     *     存到 {@code uploads/cover/yyyy/MM/}（**这是接口上线以来的既有行为，一个字没变**）
     *   {@code type=audio}     → 音频：{@code mp3}，上限 20MB，存到 {@code uploads/music/yyyy/MM/}
     *   其它取值 → 报"不支持的上传类型"（而不是悄悄回落到图片，理由见 UploadType）
     *
     *   ⚠️ 两个方向都【不会】互相放宽：音频不会接受图片扩展名，图片也不会接受 mp3。
     *   各有一条用例钉着（UploadAdminTest ⑭、⑮，⑰ 证明上限也按 type 分流）——
     *   这类"顺手放宽"不会有任何报错，只会让"封面图字段填成音频地址"这种事很久以后才被发现。
     *
     * 【为什么返回一个对象而不是直接返回字符串 URL】
     *   直接返回字符串的话，前端拿到的是 {@code data: "http://..."}；
     *   返回对象（{"url": "..."}）以后想加字段（宽高、时长、文件大小、key）
     *   不用改前端的解析代码 —— 这是接口设计上很划算的一点预留。
     *   ⇒ 加了音频之后返回结构【没有变】，仍然是 {@code {url: "..."}}，
     *     前端的上传组件不用改（它只读 data.url）。
     */
    @Operation(summary = "上传文件（图片；type=audio 时上传音频）")
    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "type", required = false) String type) {
        String url = uploadService.upload(file, type);
        return Result.success(Map.of("url", url));
    }
}
