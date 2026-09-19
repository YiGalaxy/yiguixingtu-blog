package com.yigalaxy.yiguixingtu.upload;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储管理页里的一个文件（{@link UploadStorageVO} 的元素）。
 *
 * 【为什么把 url 和 key 都给前端】
 *   · {@code url} 给界面用（缩略图、下载按钮直接绑它）
 *   · {@code key} 给删除用（删除接口收的是 key，不是 URL —— 让前端从 URL 里
 *     反解 key 等于把"URL 长什么样"这条知识复制到前端，而它属于存储实现）
 */
public record UploadFileVO(String key,
                           String url,
                           String name,
                           String category,
                           long size,
                           LocalDateTime lastModified,
                           List<UploadReference> references) {

    /**
     * 这个文件还有没有人在用（= 引用清单为空）。
     *
     * 【为什么不把它算成一个字段传过去】它是 references 的函数，
     * 多传一个布尔值就多一处可能与 references 不一致的地方 ——
     * 前端拿着 references 自己判空即可。这个静态方法只是给后端自己用的。
     */
    public boolean isOrphan() {
        return references.isEmpty();
    }
}
