package com.yigalaxy.yiguixingtu.upload;

import java.time.Instant;

/**
 * 存储里的一个对象（{@link FileStorage#listAll()} 的返回元素）。
 *
 * 【为什么单独一个 record，而不是复用别的类型】
 *   它要回答的是"磁盘上此刻有什么"，字段只有三个：key、大小、修改时间。
 *   与 {@link UploadResult}（一次上传的返回值：url / 原始文件名 / 大小）不是一回事 ——
 *   后者面向"这次上传发生了什么"，前者面向"存储里现在躺着什么"。
 *   合并成一个类型的话，做清单页时得先构造出一个假的 UploadResult（name 填什么？），
 *   那种"为了复用而编字段"的写法比多一个 record 贵得多。
 *
 * @param objectKey    存储里的相对路径，形如 {@code cover/2f/2f8e….png}（可以直接喂给
 *                     {@link FileStorage#urlOf} 或 {@link FileStorage#delete}）
 * @param size         字节数
 * @param lastModified 最后修改时间（清单页显示用；⚠️ 它是【文件系统的时间】，
 *                     不是"上传时间"—— 内容寻址之后同一个 key 只会被写一次，
 *                     所以两者在这个项目里实际上是一回事）
 */
public record StoredObject(String objectKey, long size, Instant lastModified) {
}
