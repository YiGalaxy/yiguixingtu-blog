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
 *   ⇒ 所以按"种类"分流：图片仍是原来的 5MB 与那五种扩展名，
 *     音频是另一套（mp3 / 20MB），两者互不影响。
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
    AUDIO("audio");

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
                "不支持的上传类型：" + raw.trim() + "（只支持 image / audio）");
    }
}
