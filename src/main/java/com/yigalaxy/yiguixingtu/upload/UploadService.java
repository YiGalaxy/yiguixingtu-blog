package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * =====================================================================
 * 文件上传服务 —— 负责【校验】与【生成对象路径】，真正的存储交给 FileStorage
 *
 * 【它和 FileStorage 的分工】
 *   · 这个类管"能不能传"：大小、类型、文件名
 *   · FileStorage 管"存到哪"：本地磁盘还是 OSS
 *   分开的好处是校验逻辑与存储无关，测试时不需要真的往任何地方写文件，
 *   而且换存储不影响校验规则。
 *
 * 【两种上传种类（type=image / type=audio），规则各自独立】
 *   图片：{@code jpg/jpeg/png/gif/webp} + 5MB + 存到 cover/ 目录
 *   音频：{@code mp3} + 20MB + 存到 music/ 目录
 *   ⚠️ 这两套【不会互相放宽】：给音频开 20MB 不会让图片也能传 20MB，
 *   给音频加 mp3 也不会让图片接口接受音频文件。为什么必须这样，见 {@link UploadType}。
 *   两个方向各有一条用例钉着（UploadAdminTest ⑭ 与 ⑮），另有一条证明"上限真的按 type 分流"（⑰）。
 *
 * 【用到的东西】
 *   · {@link MultipartFile} —— Spring MVC 对 multipart/form-data 里单个文件部分的封装，
 *     通过 {@code @RequestParam("file") MultipartFile file} 拿到。
 *     {@code getSize()} 是字节数，{@code getOriginalFilename()} 是客户端传来的原始文件名
 *     （⚠️ 这个文件名【完全不可信】，见下面 sanitize 的说明）
 *   · {@link UUID} —— 生成新文件名
 *   · {@link LocalDate} + {@link DateTimeFormatter} —— 生成"按日期分目录"的路径
 *
 * 【三个安全要点（每一个都对应一类真实攻击）】
 *   ① 扩展名白名单：黑名单列不全（.jsp / .phtml / .svg 里塞脚本……），
 *      只放行确定安全的几种，其余默认拒绝
 *   ② 文件名不采用用户传的：用户可能传 {@code ../../etc/passwd} 或
 *      {@code shell.jsp;.jpg} 这种名字。这里【只用它取扩展名】，
 *      真正的文件名由 UUID 生成，从根本上断掉路径穿越与二次解析
 *   ③ 大小限制：前端也能限制，但前端可以绕过（直接调接口）。
 *      服务端这一层才是真正生效的
 * =====================================================================
 */
@Slf4j
@Service
public class UploadService {

    /** 日期分目录用的格式：2026/09 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private final FileStorage fileStorage;
    private final UploadProperties properties;

    public UploadService(FileStorage fileStorage, UploadProperties properties) {
        this.fileStorage = fileStorage;
        this.properties = properties;
    }

    /**
     * 校验并保存一个上传的文件。
     *
     * 【一次上传要过四道关，顺序不能乱】
     *   ① 空文件 → ② 大小 → ③ 扩展名白名单 → ④ 生成路径并写盘
     *   前两道与类型有关（图片 5MB / 音频 20MB），第三道完全按类型分流。
     *
     * @param file 上传的文件
     * @param type 上传的种类（{@code image} / {@code audio}）；
     *             不传（{@code null} 或空白）时按 {@code image} —— 与这个接口上线以来的行为一致，
     *             不认识的取值会直接报错（理由见 {@link UploadType#resolve}）
     * @return 可直接放进 &lt;img src&gt; / &lt;audio src&gt; 的 URL
     */
    public String upload(MultipartFile file, String type) {
        // 先解析种类：参数写错要【立刻】报出来，而不是等文件校验完才发现
        // （顺序错了的话，type 打错时的报错会变成"只允许上传 xxx 格式"，
        //   指向的是文件，而真正的原因是参数 —— 提示指错地方最难查）
        UploadType uploadType = UploadType.resolve(type);

        // 按种类取出这一套规则。取成三个局部变量而不是每处都写 if：
        // 下面四个步骤对两种类型是【同一段代码】，只有这几个值不同
        List<String> allowedExtensions = allowedExtensionsOf(uploadType);
        DataSize maxSize = maxSizeOf(uploadType);
        String kindLabel = kindLabelOf(uploadType);

        // ---- ① 空文件 ----
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的文件");
        }

        // ---- ② 大小 ----
        // 这里【也】要判，因为 multipart 的 max-file-size 是框架层的兜底，
        // 它的报错信息对用户不友好（是一个框架异常），而且两者的限额未必一致：
        // 框架层按最大的那一类（音频 20MB）兜底，所以一份 8MB 的"图片"
        // 能走到这里 —— 必须由业务层按类型拒掉，否则图片的 5MB 上限等于没了
        if (file.getSize() > maxSize.toBytes()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    kindLabel + "不能超过 " + maxSize.toMegabytes() + "MB");
        }

        // ---- ③ 扩展名白名单 ----
        String extension = extractExtension(file.getOriginalFilename());
        if (!allowedExtensions.contains(extension)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "只允许上传 " + String.join(" / ", allowedExtensions) + " 格式的" + kindLabel);
        }

        // ---- ④ 生成对象路径：{类型前缀}/{年}/{月}/{uuid}.{扩展名} ----
        String objectKey = buildObjectKey(extension, prefixOf(uploadType));

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ResultCode.ERROR, "读取文件失败，请重试");
        }

        // Content-Type 优先用客户端声明的；没声明就按扩展名猜一个。
        // 它对本地存储的意义是"浏览器打开这个文件时是显示/播放还是下载"——
        // 缺了它（或猜成 application/octet-stream）浏览器会直接把文件当附件下载下来
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : guessContentType(extension);

        return fileStorage.store(content, objectKey, contentType);
    }

    /** 这一套允许的扩展名（图片五种 / 音频 mp3，两个配置项各自独立） */
    private List<String> allowedExtensionsOf(UploadType type) {
        return type == UploadType.AUDIO
                ? properties.getAudioAllowedExtensions()
                : properties.getAllowedExtensions();
    }

    /** 这一类的大小上限（图片 5MB / 音频 20MB，依据见 UploadProperties 的注释） */
    private DataSize maxSizeOf(UploadType type) {
        return type == UploadType.AUDIO ? properties.getAudioMaxSize() : properties.getMaxSize();
    }

    /** 报错文案里的"这一类的名字"（"图片不能超过 5MB" / "音频不能超过 20MB"） */
    private String kindLabelOf(UploadType type) {
        return type == UploadType.AUDIO ? "音频" : "图片";
    }

    /** 存储目录前缀：图片是 cover（可配置），音频是 music，两者不会撞在一起 */
    private String prefixOf(UploadType type) {
        String prefix = type == UploadType.AUDIO
                ? properties.getAudioKeyPrefix() : properties.getKeyPrefix();
        return StringUtils.hasText(prefix) ? prefix : type.getCode();
    }

    /**
     * 构造存储路径，形如 {@code cover/2026/09/3f2b....jpg}（音频则是 {@code music/2026/09/...mp3}）。
     *
     * 【为什么要按年月分目录】
     *   1. 一个目录下文件太多时，浏览、备份、排查都会变慢
     *     （本地文件系统里几万个文件挤在一个目录，`ls` 和备份都很难受；
     *      将来换成对象存储也一样 —— 它的控制台按 key 里的 / 展示成目录树）
     *   2. 方便按时间清理历史文件
     *
     * 【为什么文件名用 UUID 而不是保留原名】
     *   · 原名可能重复 → 后传的覆盖先传的（真实事故）
     *   · 原名可能带路径（../../）、可能带双扩展名（shell.jpg.jsp）
     *   · 原名可能含中文、空格、特殊字符，进 URL 还要编码，纯属自找麻烦
     *   音频这一点更明显：歌名里带空格、括号、中文的很常见
     *   （"周杰伦 - 夜曲 (Live).mp3"），直接当文件名只会给 URL 编码找麻烦。
     *
     * @param extension 小写扩展名（不含点）
     * @param prefix    目录前缀（图片 cover / 音频 music），由调用方按种类传进来
     */
    private String buildObjectKey(String extension, String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "/" + LocalDate.now().format(DATE_DIR) + "/" + uuid + "." + extension;
    }

    /**
     * 从原始文件名里取出小写扩展名。
     *
     * 【为什么只取扩展名、其余一律丢掉】
     *   客户端传来的文件名属于【不可信输入】。这里只把它当作"用户想传什么格式"的提示，
     *   真正的文件名由我们自己生成。这样即使传来 {@code ../../../etc/passwd}
     *   或者 {@code a.jsp;.jpg}，也不会影响我们拼出来的路径。
     *
     * @return 小写扩展名（不含点）；取不到时返回空串，后面会被白名单拒掉
     */
    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }
        // 有些客户端会把整个路径带过来（老 IE 就是这样），先取最后一段
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按扩展名猜一个 MIME 类型（客户端没提供 Content-Type 时的兜底） */
    private String guessContentType(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            // 音频只有一个扩展名（见 UploadProperties.audioAllowedExtensions）。
            // ⚠️ 必须是 audio/mpeg 而不是 audio/mp3 —— 后者不是注册过的 MIME 类型，
            // 浏览器拿到它多半会当成未知类型去下载而不是播放
            case "mp3" -> "audio/mpeg";
            default -> "image/jpeg";
        };
    }
}
