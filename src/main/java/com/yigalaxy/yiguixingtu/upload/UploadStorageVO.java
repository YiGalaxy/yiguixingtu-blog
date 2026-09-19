package com.yigalaxy.yiguixingtu.upload;

import java.util.List;

/**
 * 后台「存储管理」页的整页数据：占用概览 + 文件清单（含每个文件被谁引用）。
 *
 * 【为什么一次性把清单全返回，而不是分页】
 *   分页的前提是"列表这一侧可以按页取"，但这里的每一行都要带上引用清单，
 *   而引用清单是**一次全量构建**出来的（见 {@link UploadReferenceIndex}）。
 *   既然索引已经在内存里了，再按页切反而要多一套"索引跨请求缓存"的状态。
 *   文件数受上传总量护栏约束（默认 5GB），个人博客量级下几百到几千个 ——
 *   一次几万个文件名不可能出现（那得是几十 KB 的图片堆到 5GB）。
 *   ⚠️ 真到了几千个文件要分页时，正确的改法是先把索引落成一张表，
 *   而不是给这个接口加 page 参数（那样每翻一页都要重建一次索引）。
 *
 * @param usedBytes     已占用字节数（与上传护栏读的是同一个数）
 * @param maxTotalBytes 上传总量上限（0 或负数表示不限制，见 UploadProperties.maxTotalSize）
 * @param fileCount     文件总数
 * @param orphanCount   其中"没有任何地方引用"的个数 —— 这个数字是这一页存在的意义：
 *                      它涨起来就说明磁盘在被白白占着
 * @param files         文件清单（按 objectKey 排序，稳定可复现）
 */
public record UploadStorageVO(long usedBytes,
                              long maxTotalBytes,
                              int fileCount,
                              int orphanCount,
                              List<UploadFileVO> files) {
}
