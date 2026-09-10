package com.yigalaxy.yiguixingtu.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计记录（对应 operation_log 表）。
 *
 * 【⚠️ 注意它没有 @TableLogic 逻辑删除字段】
 *   这是刻意的，不是漏了。审计记录的价值就在"发生过的事不能被抹掉"，
 *   给它加逻辑删除等于给了"把痕迹藏起来"的操作空间。
 *   表结构的注释里也写了这一点，两边保持一致免得以后有人"顺手补上"。
 *
 * 【为什么 username 和 user_id 都存】
 *   user_id 用来关联查询；username 是【快照】——
 *   用户改名、或者被删除（本项目的删除会把用户名改写成 原名#deleted#id）
 *   之后，靠 id 已经追不回"当时是谁"了，只有快照能。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人ID（可能是 null：比如将来有定时任务触发的写操作） */
    private Long userId;

    /** 操作人用户名快照 */
    private String username;

    /** 操作类型，取值见 {@link OperationAction} */
    private String action;

    /** 操作对象类型（ARTICLE / USER） */
    private String targetType;

    private Long targetId;

    private String detail;

    private String ip;

    /** 链路追踪ID —— 有了它才能从"谁改了什么"追到"这次请求的完整日志" */
    private String traceId;

    private LocalDateTime createTime;
}
