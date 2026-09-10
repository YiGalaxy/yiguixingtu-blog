package com.yigalaxy.yiguixingtu.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论实体，对应数据库表 comment。
 *
 * 【状态机只有三个值，但它决定了"这条评论谁会看到"】
 *   0 待审核 —— 刚提交，只有管理员看得到（默认值）
 *   1 已通过 —— 前台可见
 *   2 已拒绝 —— 前台不可见，但记录留着（方便判断"是不是同一个人反复发"）
 *
 * 【为什么不做"删除即拒绝"】拒绝要留记录：同一个人连续发三条垃圾评论，
 *   如果每次都是"删掉"，后台就失去了"这个人一直在发"这个信号。
 */
@Data
@Schema(description = "文章评论")
@TableName("comment")
public class Comment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评论状态：待审核（新建时的默认值） */
    public static final int STATUS_PENDING = 0;

    /** 评论状态：已通过（前台唯一可见的状态） */
    public static final int STATUS_APPROVED = 1;

    /** 评论状态：已拒绝（前台不可见，记录保留） */
    public static final int STATUS_REJECTED = 2;

    @Schema(description = "评论ID")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属文章ID")
    private Long articleId;

    @Schema(description = "评论者昵称")
    private String nickname;

    /** 邮箱不返回给前端（VO 里没有这个字段），只给管理员看 */
    @Schema(description = "邮箱（不对外展示）", hidden = true)
    private String email;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "状态：0待审核 1已通过 2已拒绝")
    private Integer status;

    /** 来源 IP：不返回给前端，被刷评论时后台用来判断来源 */
    @Schema(description = "来源IP", hidden = true)
    private String ip;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}
