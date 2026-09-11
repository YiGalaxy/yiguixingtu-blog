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
     * 上传一个文件，返回 {@code {url, name, size}}。
     *
     * 【{@code type} 参数决定按哪一套规则校验】
     *   不传 / {@code type=image} → 图片：{@code jpg/jpeg/png/gif/webp}，上限 10MB，
     *     存到 {@code uploads/cover/yyyy/MM/}
     *   {@code type=audio}     → 音频：{@code mp3}，上限 20MB，存到 {@code uploads/music/yyyy/MM/}
     *   {@code type=attachment} → 附件：PDF / 压缩包 / Office 文档 / 文本 / mp3 / mp4 共 16 种，
     *     上限 100MB，存到 {@code uploads/attachment/yyyy/MM/}（文章附件用它）
     *   其它取值 → 报"不支持的上传类型"（而不是悄悄回落到图片，理由见 UploadType）
     *
     *   ⚠️ 三套规则【不会】互相放宽：附件不会接受 png，图片也不会接受 pdf。
     *   各有用例钉着（UploadAdminTest ⑭、⑮，⑰ 证明上限也按 type 分流；
     *   附件方向的两条在 ArticleAttachmentTest）——
     *   这类"顺手放宽"不会有任何报错，只会让"封面图字段填成一个 pdf 地址"这种事很久以后才被发现。
     *
     * 【为什么返回一个对象而不是直接返回字符串 URL】
     *   直接返回字符串的话，前端拿到的是 {@code data: "http://..."}；
     *   返回对象（{"url": "..."}）以后想加字段（宽高、时长、文件大小、key）
     *   不用改前端的解析代码 —— 这是接口设计上很划算的一点预留。
     *
     * 【2026-09：真的一次加了两个字段，而且确实没改解析代码】
     *   文章附件要在列表里显示"文件名 + 大小"，所以返回值加上了
     *   {@code name}（上传时的原始文件名）与 {@code size}（字节数）。
     *   这【不是破坏性改动】：{@code data.url} 的位置与含义一个字都没变，
     *   老的前端上传组件（只读 data.url）不用改一行 ——
     *   结构从原来的 {@code Map<String,String>} 换成 {@link UploadResult}
     *   （一个 record），只是为了给"只会返回 url"这个写法一个正经的类型。
     *   ⚠️ 注意 name 是【清洗过】的原始文件名（去路径、截断到 100 字），
     *   理由见 UploadService.sanitizeName：它要能直接存进 article_attachment.name。
     */
    @Operation(summary = "上传文件（图片；type=audio 音频；type=attachment 文章附件）")
    @PostMapping
    public Result<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "type", required = false) String type) {
        return Result.success(uploadService.upload(file, type));
    }
}
