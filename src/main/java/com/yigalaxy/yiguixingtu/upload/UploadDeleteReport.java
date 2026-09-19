package com.yigalaxy.yiguixingtu.upload;

import java.util.List;

/**
 * 批量删除上传文件的执行结果（见 {@link UploadStorageService#deleteFiles}）。
 *
 * 【为什么是"报告"而不是"删了/没删"一个布尔】
 *   批量删除里经常是三种结果混在一起：有的删了、有的因为"仍被引用"被跳过了、
 *   有的删失败。只回一个成败的话，界面上只能笼统说一句"操作完成"，
 *   而站长最需要知道的恰恰是**哪几个没删掉、为什么** ——
 *   他刚勾了 10 个文件，回头一看还剩 3 个，没有任何解释。
 *
 * @param deleted           真正删掉的 key
 * @param skippedReferenced 因为【仍被引用】而保留的 key（这是正常结果，不是错误）
 * @param failed            删除时报错的 key（磁盘问题等，需要人工看日志）
 */
public record UploadDeleteReport(List<String> deleted,
                                 List<String> skippedReferenced,
                                 List<String> failed) {
}
