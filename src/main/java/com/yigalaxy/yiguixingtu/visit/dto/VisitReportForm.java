package com.yigalaxy.yiguixingtu.visit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 访问上报的请求体。
 *
 * 【为什么前端只报一个路径，别的都由后端自己取】
 *   IP、Referer、User-Agent、登录身份这四样**请求本身就有** ——
 *   让前端报上来等于把"我是什么身份""我从哪来"交给了调用方自己声明：
 *   任何人改一下请求体就能把自己记成"从百度来的管理员"。
 *   前端唯一知道、而后端不知道的只有一件事：**用户在看哪个页面**
 *   （SPA 站内跳转不经过后端，见 VisitController 的类注释）。
 *
 * 【为什么会话级的字段一个都没有】不做 session/visitor id：
 *   那属于追踪用户，与"看个访问趋势"的用途不成比例。这里的 UV 就按 IP 去重。
 */
public record VisitReportForm(
        /**
         * 页面的路径，形如 {@code /article/13}。
         * 长度上限 500 只是拦掉"明显不是路径"的垃圾输入 ——
         * 真正入库前还会被洗一遍并截到 255（列宽），见 VisitLogServiceImpl。
         */
        @NotBlank(message = "path 不能为空")
        @Size(max = 500, message = "path 过长")
        String path) {
}
