package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * =====================================================================
 * 上传的"种类" —— 决定这次上传按哪一套规则校验（扩展名白名单 / 大小上限 / 存到哪）
 *
 * 【为什么需要它，而不是"把所有扩展名和更大的上限加在一起"】
 *   最省事的改法是"白名单里加一个 mp3、上限从 5MB 提到 20MB"——
 *   两行改完，音频就能传了。但那会【悄悄放宽图片的规则】：
 *     · 图片从此允许 20MB（原来的 5MB 上限消失了，而没有人做决定要放宽它）
 *     · 图片接口开始接受 .mp3（一个"封面图"字段可以填音频地址，
 *       坏在很久以后才被发现，而且没有任何报错）
 *   这两条都不会有任何测试变红 —— 除非专门为它们各写一条用例（本项目做了，见 UploadAdminTest）。
 *   ⇒ 所以按"种类"分流：图片有图片那一套（五种扩展名 / 10MB），
 *     音频有音频那一套（mp3 / 20MB），附件有附件那一套（16 种文档与媒体 / 100MB），
 *     三套互不影响。
 *
 * 【2026-09 加了第三种：ATTACHMENT（文章附件）】
 *   它把这套设计的好处当场兑现了一次：附件的白名单里有 mp3、mp4、json、md……
 *   如果当初是"往一个列表里加扩展名、把上限统一调大"，那么加附件这一次就会
 *   顺手把【图片】也放宽到 100MB、并且允许"封面图字段填一个 .json 的地址"——
 *   没有任何报错，也没有任何用例会因此变红。现在只是多一个枚举常量 +
 *   多一套配置项，图片与音频的规则一个字节都没动（UploadAdminTest 里
 *   "type=image 传 pdf 被拒"那条用例就是钉这个方向的）。
 *
 * 【为什么不传 type 时默认成图片】那是这个接口上线以来的既有行为，
 *   改动它等于让所有老调用方（前端上传组件）静默换一套规则。
 *   "不传" 与 "type=image" 必须是同一个结果。
 *
 * 【为什么未知的 type 直接报错，而不是"回落到图片"】
 *   回落会让 {@code type=audo}（打错一个字）表现成"上传 mp3 被拒，提示只允许 jpg…"——
 *   用户看到的是"格式不支持"，而真正的原因是参数名拼错了。报"不支持的类型"才有指向。
 * =====================================================================
 */
public enum UploadType {

    /** 图片（默认）：封面、头像等。扩展名与大小上限沿用接口最初的那一套 */
    IMAGE("image"),

    /** 音频：音乐模块的 mp3。存到与图片不同的子目录（见 UploadProperties.audioKeyPrefix） */
    AUDIO("audio"),

    /**
     * 附件：文章的"可下载文件"（PDF / 压缩包 / Office 文档 / 文本 / 音视频），单个 ≤ 100MB。
     * 存到 uploads/attachment/ 目录（见 UploadProperties.attachmentKeyPrefix）。
     *
     * ⚠️ 它的白名单里【绝对不能】出现 html / htm / svg / xml / js / mjs / css：
     *    这些文件被浏览器打开时会被【当作网页执行】。而 /uploads/** 是本项目的
     *    静态映射（见 WebMvcConfig），也就是说"在自己域名下"执行脚本 ——
     *    等于开了一个存储型 XSS 入口（同源，能读本站的 localStorage / 带 cookie 发请求）。
     *    完整的解释写在 UploadProperties.attachmentAllowedExtensions 上。
     */
    ATTACHMENT("attachment");

    /** 请求参数 {@code type} 的取值（小写比较，避免 {@code type=Audio} 被当成未知类型） */
    private final String code;

    UploadType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 把请求里的 {@code type} 参数翻译成枚举。
     *
     * @param raw 请求参数；{@code null} / 空白表示"没传"→ 图片（保持既有行为）
     * @return 对应的种类
     * @throws BusinessException 传了但不认识（PARAM_ERROR，HTTP 200 + body.code = 400）
     */
    public static UploadType resolve(String raw) {
        if (!StringUtils.hasText(raw)) {
            return IMAGE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (UploadType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        throw new BusinessException(ResultCode.PARAM_ERROR,
                "不支持的上传类型：" + raw.trim() + "（只支持 image / audio / attachment）");
    }
}
