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
 * 【三种上传种类（type=image / audio / attachment），规则各自独立】
 *   图片：{@code jpg/jpeg/png/gif/webp} + 10MB + 存到 cover/ 目录
 *   音频：{@code mp3} + 20MB + 存到 music/ 目录
 *   附件：{@code pdf/zip/7z/rar/doc…/mp3/mp4} 共 16 种 + 100MB + 存到 attachment/ 目录
 *   ⚠️ 这三套【不会互相放宽】：给附件开 100MB 不会让图片也能传 100MB，
 *   给附件加 pdf 也不会让图片接口接受 pdf 文件。为什么必须这样，见 {@link UploadType}。
 *   两个方向各有一条用例钉着（UploadAdminTest ⑭ 与 ⑮），另有一条证明"上限真的按 type 分流"（⑰）；
 *   附件那一套同方向的两条在 ArticleAttachmentTest（"type=image 传 pdf 被拒"等）。
 *
 * 【⚠️ 三套上限之外还有一道"总闸"（不是按种类的第四套规则）】
 *   上面三个 maxSize 管的是"一个文件多大"，而整个上传目录还有 5GB 的总量上限
 *   （见 UploadProperties.maxTotalSize）：几十个各 100MB 的附件都是【合法的单个文件】，
 *   单文件那一层永远拦不住它们把 40GB 的磁盘写满。磁盘写满会让
 *   MySQL / Redis / 日志一起写不进去 —— 那是整站级别的故障，
 *   所以这里在上传前先量一次目录占用，超了就明确拒绝。
 *   它的实现与代价见 usedBytesOfStorage 与 LocalFileStorage.usedBytes 的注释。
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
     * 【一次上传要过五道关，顺序不能乱】
     *   ① 空文件 → ② 大小 → ③ 扩展名白名单 → ④ 上传目录总容量 → ⑤ 生成路径并写盘
     *   前两道与类型有关（图片 10MB / 音频 20MB / 附件 100MB），第三道完全按类型分流。
     *
     * 【为什么"总容量"排在扩展名之后】
     *   两者的报错完全不同："只允许上传 pdf / zip / … 格式的附件"是一个
     *   【能自己改】的提示（换个文件就行），而"上传目录满了"需要人去清理旧文件。
     *   如果把容量判断放在前面，那么用户传一个 .exe 时会收到"容量不足"的提示 ——
     *   他会去删自己的文件，而真正的问题是他传错了格式。**报错要指向真正的原因**，
     *   这一条和 UploadType.resolve 里"未知 type 直接报错而不回落"是同一个原则。
     *
     * @param file 上传的文件
     * @param type 上传的种类（{@code image} / {@code audio} / {@code attachment}）；
     *             不传（{@code null} 或空白）时按 {@code image} —— 与这个接口上线以来的行为一致，
     *             不认识的取值会直接报错（理由见 {@link UploadType#resolve}）
     * @return 上传结果（可访问地址 + 展示用文件名 + 字节数），见 {@link UploadResult}
     */
    public UploadResult upload(MultipartFile file, String type) {
        // 先解析种类：参数写错要【立刻】报出来，而不是等文件校验完才发现
        // （顺序错了的话，type 打错时的报错会变成"只允许上传 xxx 格式"，
        //   指向的是文件，而真正的原因是参数 —— 提示指错地方最难查）
        UploadType uploadType = UploadType.resolve(type);

        // 按种类取出这一套规则。取成三个局部变量而不是每处都写 if：
        // 下面几个步骤对三种类型是【同一段代码】，只有这几个值不同
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
        // 框架层按最大的那一类（附件 100MB）兜底，所以一份 30MB 的"图片"
        // 能走到这里 —— 必须由业务层按类型拒掉，否则图片的 10MB 上限等于没了
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

        // ---- ④ 上传目录总容量 ----
        checkTotalCapacity(file.getSize());

        // ---- ⑤ 生成对象路径：{类型前缀}/{年}/{月}/{uuid}.{扩展名} ----
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

        String url = fileStorage.store(content, objectKey, contentType);

        // 名字单独跑一遍"清洗"再返回：前端要拿它去渲染附件列表，
        // 而且它会被存进 article_attachment.name（varchar(100)）
        return new UploadResult(url, sanitizeName(file.getOriginalFilename(), objectKey), file.getSize());
    }

    /**
     * 上传总量护栏：本次文件加上之后，如果超过配置的总上限就拒绝。
     *
     * 【为什么必须在上传【之前】判，而不是写完再判】
     *   写完再判的话文件已经落盘了，只能"再删掉"——那既浪费一次 100MB 的写入，
     *   又会在"删也失败"时留下一个没登记的垃圾文件。事前判断一次，
     *   拒绝时磁盘上什么都没有，语义干净。
     *
     * 【为什么是"当前占用 + 本次大小 > 上限"而不是">= "】
     *   上限的语义是"最多能占到这么多"。当前占用恰好等于上限时，
     *   再传一个 1 字节的文件也该被拒（那才会超）。用 > 表达这条规则。
     *
     * 【⚠️ 这条护栏是"尽力而为"的，不是强一致的事务保证】
     *   两个人同时上传时，他们可能各自看到"还装得下"，于是一起写进去，
     *   实际占用略微超过上限（超出量不超过"并发数 × 单文件上限"）。
     *   这是【刻意接受的】取舍：要做到严格不超就得在上传路径上加一把分布式锁，
     *   而那个锁的代价（等待、超时、Redis 故障时上传全挂）远大于
     *   "偶尔超出几十 MB"的后果 —— 这道闸的目的是别让磁盘爆掉，
     *   不是"把每一字节都算准"。真正兜底的是磁盘本身。
     *
     * @param incomingSize 本次要写入的字节数
     * @throws BusinessException 放不下时（PARAM_ERROR —— 这是用户可以自行处理的问题：
     *                           删掉旧的附件再传）
     */
    private void checkTotalCapacity(long incomingSize) {
        DataSize maxTotal = properties.getMaxTotalSize();
        if (maxTotal == null || maxTotal.toBytes() <= 0) {
            return;     // 配成 0 或负数视为"不限制"（本地调试可能这样配）
        }

        // 真的去量一遍目录（理由见 LocalFileStorage.usedBytes 的注释）
        long used = fileStorage.usedBytes();
        if (used + incomingSize <= maxTotal.toBytes()) {
            return;
        }

        // 提示里要带上"现在用了多少"，否则用户只知道"传不了"，
        // 不知道是该去清理旧文件还是该找管理员扩容
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "上传空间不足：已用 " + formatSize(used) + "，上限 " + formatSize(maxTotal.toBytes())
                        + "。请先删除不再需要的旧附件或联系管理员扩容");
    }

    /** 把字节数格式化成"12.3 MB"这样的文本（护栏的报错文案要用） */
    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return bytes / 1024 + " KB";
    }

    /**
     * 把用户传来的原始文件名清洗成"可以直接存、可以安全展示"的名字。
     *
     * 【为什么原始文件名不能直接用】
     *   它是【不可信输入】：来自客户端，用户可以用脚本随便编。三类具体风险：
     *     · 带路径：{@code C:\Users\me\论文.pdf} 或 {@code ../../etc/passwd} ——
     *       老 IE 就会把整个路径当文件名传上来。存进库里以后再显示，
     *       界面上会出现一串与用户无关的本机路径
     *     · 超长：有的系统允许 255 字节的文件名，而这一列只能放 100 字。
     *       不截断的话，用户上传成功、保存文章时却收到"附件名过长"的 400 ——
     *       而这条错跟他做的事看起来毫无关系（他什么都没填，是文件名太长）
     *     · 空白：只有空格或不可见字符的名字会让前端渲染出一个空白的附件行
     *
     * 【为什么截断到 100 而不是别的数字】
     *   它必须 ≤ article_attachment.name 的列宽（100）与
     *   ArticleAttachmentForm.name 上的 @Size(max = 100) ——
     *   三处取同一个数字，"上传成功就一定存得进文章"这条不变量才成立。
     *
     * 【⚠️ 这里【不】做 HTML 转义】附件名最终会被前端渲染，转义是前端的事
     *   （与评论不同：评论是"入库即成品"，而附件名是要回到用户手里再被编辑的
     *   原始数据，提前转义会让"论文&数据.pdf"变成"论文&amp;数据.pdf"存进库）。
     *   真正的防线是前端的渲染方式（文本插值而不是 v-html），以及
     *   下载响应头上写死的 UUID 文件名（见 UploadResponseHeaderFilter）。
     *
     * @param originalFilename 客户端传来的原始文件名（可能为空、可能带路径）
     * @param objectKey        我们自己生成的对象路径（原始名不可用时拿它的文件名兜底）
     * @return 长度 ≤ 100 的展示名，保证非空
     */
    private String sanitizeName(String originalFilename, String objectKey) {
        String name = originalFilename == null ? "" : originalFilename;

        // 统一分隔符后只保留最后一段（与 extractExtension 同一套处理，理由见那里）
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();

        // 名字为空（客户端没传）时用我们生成的文件名兜底，而不是留一个空字符串：
        // 空名字在前端的附件列表里就是一行留白，用户看不出那是什么
        if (name.isEmpty()) {
            int lastSlash = objectKey.lastIndexOf('/');
            name = lastSlash >= 0 ? objectKey.substring(lastSlash + 1) : objectKey;
        }

        int maxNameLength = 100;
        if (name.length() > maxNameLength) {
            // 截断时【保留扩展名】：前 100 个字符切下去很可能把 ".pdf" 切掉，
            // 那样用户看到的名字就没有类型提示了。所以规则是
            // "后缀尽量留、前面的部分让位"
            String suffix = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 20) {
                suffix = name.substring(dot);
            }
            name = name.substring(0, maxNameLength - suffix.length()) + suffix;
        }
        return name;
    }

    /** 这一套允许的扩展名（图片五种 / 音频 mp3 / 附件十六种，三个配置项各自独立） */
    private List<String> allowedExtensionsOf(UploadType type) {
        return switch (type) {
            case IMAGE -> properties.getAllowedExtensions();
            case AUDIO -> properties.getAudioAllowedExtensions();
            case ATTACHMENT -> properties.getAttachmentAllowedExtensions();
        };
    }

    /** 这一类的大小上限（图片 10MB / 音频 20MB / 附件 100MB，依据见 UploadProperties 的注释） */
    private DataSize maxSizeOf(UploadType type) {
        return switch (type) {
            case IMAGE -> properties.getMaxSize();
            case AUDIO -> properties.getAudioMaxSize();
            case ATTACHMENT -> properties.getAttachmentMaxSize();
        };
    }

    /**
     * 报错文案里的"这一类的名字"（"图片不能超过 10MB" / "音频不能超过 20MB" /
     * "附件不能超过 100MB"）。
     *
     * 【为什么用 switch 而不是像原来那样写成三元表达式】
     *   两种类型时 {@code type == AUDIO ? a : b} 是清楚的；三种及以上就必须写
     *   {@code type == AUDIO ? a : type == ATTACHMENT ? b : c} —— 那既难读，
     *   也把"漏了一种"变成了一个【静默的默认分支】（默认落到图片上，
     *   报错文案会说"图片不能超过 100MB"）。用 switch 表达式之后，
     *   以后再加类型时编译器会直接报"switch 未覆盖全部枚举值"，
     *   不可能漏。下面这几个方法（kindLabelOf / allowedExtensionsOf / maxSizeOf /
     *   prefixOf）都照这个写法。
     */
    private String kindLabelOf(UploadType type) {
        return switch (type) {
            case IMAGE -> "图片";
            case AUDIO -> "音频";
            case ATTACHMENT -> "附件";
        };
    }

    /** 存储目录前缀：图片是 cover（可配置）、音频是 music、附件是 attachment，三者不会撞在一起 */
    private String prefixOf(UploadType type) {
        String prefix = switch (type) {
            case IMAGE -> properties.getKeyPrefix();
            case AUDIO -> properties.getAudioKeyPrefix();
            case ATTACHMENT -> properties.getAttachmentKeyPrefix();
        };
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

    /**
     * 按扩展名猜一个 MIME 类型（客户端没提供 Content-Type 时的兜底）。
     *
     * 【⚠️ 附件为什么没有列进这个 switch】
     *   本地存储只把字节写进磁盘、【不保存 Content-Type】（见 LocalFileStorage），
     *   所以浏览器打开 /uploads/** 时拿到的类型是 Spring 按【文件扩展名】现推的
     *   （ResourceHttpRequestHandler 的行为），与这里无关。
     *   而对附件来说，真正决定"下载还是就地打开"的是响应头
     *   Content-Disposition: attachment（见 UploadResponseHeaderFilter）——
     *   附件永远走下载，猜不猜得准 MIME 都不影响结果。
     *   所以这里只保留"猜错了会有可见后果"的那几个（图片会被当裂图、mp3 会不播放）。
     */
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
