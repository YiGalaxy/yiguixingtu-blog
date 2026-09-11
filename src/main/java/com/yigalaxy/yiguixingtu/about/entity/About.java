package com.yigalaxy.yiguixingtu.about.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 关于页实体，对应数据库表 about。
 *
 * 【全站唯一一张"只有一行"的表，三处与别的实体不同】
 *  ① 主键是 {@link IdType#INPUT} 而不是 AUTO
 *     这张表永远只有 id = 1 那一行，id 由代码显式指定。
 *     用 INPUT 的语义是"主键由我给"；用 AUTO 反而依赖"插入时把 id 也带上、
 *     不被数据库自增覆盖"这种细节 —— 单条表没必要赌这个。
 *
 *  ② 【没有 @TableLogic / deleted】
 *     它是唯一一张没有逻辑删除的业务表（operation_log 也没有，但那是审计，
 *     理由正好相反：审计是"不许抹掉痕迹"）。
 *     这里是因为"删掉关于页"这个需求根本不存在 —— 不要了就清空字段。
 *     代价与兜底（有人手工删了那一行怎么办）写在 V10 迁移脚本与 AboutServiceImpl 里。
 *
 *  ③ 【没有 createTime】
 *     单条记录没有"创建"的语义：它从建站起就在那里，只会被改。只留 updateTime。
 *
 * 【bio 允许写 Markdown】后端不做任何转换（存原文、原样返回）：
 *   渲染是前端的事，和文章正文完全一致 —— 同一份 Markdown 只能有一套渲染规则，
 *   否则关于页和文章页的显示效果会不一样，而且改起来要改两处。
 */
@Data
@Schema(description = "关于页信息")
@TableName("about")
public class About implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 这一行固定的主键值。
     * 写成常量而不是散落的字面量 1：Service 里每处都要用它，
     * 哪天要改成别的值（比如支持多语言各一条）也只改这一个地方。
     */
    public static final long SINGLE_ROW_ID = 1L;

    @Schema(description = "固定为 1（这张表只有一行）")
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 昵称，最长 50（与 DTO 校验、数据库列长度三处一致） */
    @Schema(description = "昵称", example = "别太在亿啦")
    private String nickname;

    /** 头像地址，可为空；允许绝对地址或 / 开头的站内路径 */
    @Schema(description = "头像地址")
    private String avatar;

    /** 自我介绍（长文本，可写 Markdown），最长 5000 字（DTO 层限制） */
    @Schema(description = "自我介绍（长文本，可写 Markdown）")
    private String bio;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "GitHub 地址")
    private String github;

    /** 微信：不做格式校验（它只是文字，不会被渲染成链接） */
    @Schema(description = "微信")
    private String wechat;

    /** QQ：同上，不做格式校验 */
    @Schema(description = "QQ")
    private String qq;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
