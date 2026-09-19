package com.yigalaxy.yiguixingtu.upload;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量删除上传文件的请求体。
 *
 * 【为什么是一个对象而不是直接收 {@code List<String>}】
 *   两个理由：
 *     ① 校验注解没地方放（直接收 List 的话，"不能为空""最多几个"只能写进
 *        方法体里手判，而本项目其余表单都是注解式校验，保持一致更好读）
 *     ② 将来要加参数（比如"连引用它的地方一起清理"这种开关）时，
 *        RequestBody 的形状不用变 —— 加一个字段就行
 *
 * 【上限 500 的依据】它只挡"把整个目录一次性传上来"这种失控请求。
 *   真要删几千个文件，正确的做法是分几批，而不是让一次请求在事务外
 *   挨个删几千个文件（每个都是一次磁盘操作，请求会超时，而超时后
 *   客户端不知道删到哪了）。
 */
public record UploadDeleteForm(
        @NotEmpty(message = "请至少选择一个文件")
        @Size(max = 500, message = "一次最多删除 500 个文件")
        List<String> keys) {
}
