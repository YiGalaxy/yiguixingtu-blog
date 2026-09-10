package com.yigalaxy.yiguixingtu.upload;

/**
 * =====================================================================
 * 文件存储的抽象接口 —— "把一段内容存到一个 key 上，并返回可访问的 URL"
 *
 * 【为什么要抽这一层，而不是业务代码直接调 OSS SDK】
 *   因为"存哪里"是会变的，而且【本地开发和线上本来就不一样】：
 *     · 本地开发 / 测试：存本地磁盘，不需要任何 OSS 凭据与公网
 *     · 生产环境：存阿里云 OSS
 *   如果 UploadService 里直接 new OSSClient，那么：
 *     · 测试必须有真实 OSS 凭据，CI 根本跑不起来
 *     · 将来换存储（七牛 / MinIO / S3）要改业务代码
 *   抽一层接口之后，业务只依赖"能存能返回地址"这个契约，
 *   具体实现由配置（app.upload.storage）决定注入哪一个。
 *
 * 【它算不算"自研轮子"】
 *   不算。这里没有自己实现任何存储能力（没有手写 HTTP 上传、没有自己算签名），
 *   OSS 那部分完全交给官方 SDK。这一层只是【依赖倒置】——
 *   业务依赖抽象而不是依赖某一家厂商的 SDK，是标准做法。
 *
 * 【实现类】
 *   · {@link LocalFileStorage} —— 写本地磁盘（默认，dev/test 用）
 *   · {@link OssFileStorage}   —— 上传到阿里云 OSS（生产用）
 * =====================================================================
 */
public interface FileStorage {

    /**
     * 保存一个文件。
     *
     * @param content     文件内容（已读过大小限制校验）
     * @param objectKey   对象在存储里的唯一路径，形如 {@code cover/2026/09/xxxx.jpg}。
     *                    由调用方（UploadService）统一生成，
     *                    这样"文件放在哪"这件事对存储实现是透明的
     * @param contentType 文件的 MIME 类型，例如 image/png。
     *                    OSS 靠它决定浏览器打开时的行为，不传的话可能被当成下载
     * @return 可以直接放进 &lt;img src&gt; 的完整 URL
     */
    String store(byte[] content, String objectKey, String contentType);
}
