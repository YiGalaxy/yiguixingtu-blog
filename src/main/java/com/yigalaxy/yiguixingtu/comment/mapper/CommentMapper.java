package com.yigalaxy.yiguixingtu.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yigalaxy.yiguixingtu.comment.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论 Mapper。
 *
 * 【为什么这里一条自定义 SQL 都不需要】
 *   CRUD 用 BaseMapper 就够（insert / selectPage / updateById）；
 *   "按文章 + 状态查已通过评论"这类条件查询用 LambdaQueryWrapper 拼得出来，
 *   而且 @TableLogic 会自动加上 deleted = 0 ——
 *   手写 SQL 反而要自己记得加那个条件（标签那边就踩过这个坑）。
 *
 *   所以这里的"空接口"不是偷懒：**能交给框架生成的 SQL，就不要手写**。
 *   （真需要 JOIN 文章标题的后台列表见 CommentServiceImpl，那里用两次查询代替 JOIN —— 
 *     评论分页一页只有 10 条，拿 id 再查一次文章标题比在 SQL 里 JOIN 更好读，
 *     也避免了"分页 + JOIN"常见的 count 算错问题。）
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
