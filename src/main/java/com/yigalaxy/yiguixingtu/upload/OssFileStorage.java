package com.yigalaxy.yiguixingtu.upload;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Date;

/**
 * =====================================================================
 * 阿里云 OSS 存储 —— 生产环境用这个
 *
 * 【什么时候用它】{@code app.upload.storage=oss}。
 *
 * 【用到的东西（都是官方 SDK，没有自己实现任何协议）】
 *   · {@link OSSClientBuilder} —— OSS 官方 SDK 的客户端构造器
 *   · {@link OSS} —— 官方客户端，线程安全，全应用共用一个即可
 *     （每次上传都 new 一个客户端是常见错误：它内部维护连接池，
 *      频繁创建会不断新建连接，既慢又浪费）
 *   · {@link PutObjectRequest} —— 一次上传请求
 *   · {@link ObjectMetadata} —— 对象的元信息，这里主要用来设置 Content-Type
 *   · {@code generatePresignedUrl} —— 生成带签名的临时 URL（私有 Bucket 用）
 *   · {@code @PreDestroy} —— Bean 销毁前钩子，用来关闭客户端、释放连接池
 *
 * 【为什么客户端在构造时就创建、并且在这里校验配置】
 *   配置缺了就【启动直接失败】，而不是等用户第一次上传时才发现。
 *   这与 application-prod.properties 里"凭据不给默认值"是同一个取向：
 *   部署问题要在部署时暴露，不要拖到线上运行中。
 *
 * 【⚠️ 凭证从哪来】
 *   必须用 RAM 子账号的 AccessKey，并只授权这一个 Bucket 的读写。
 *   不要用主账号密钥（理由见 UploadProperties.ossAccessKeyId 的注释）。
 * =====================================================================
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "oss")
public class OssFileStorage implements FileStorage {

    private final UploadProperties properties;
    private final OSS ossClient;

    public OssFileStorage(UploadProperties properties) {
        this.properties = properties;

        // ---- 启动时就校验配置，缺什么直接报清楚 ----
        requireText(properties.getOssEndpoint(), "app.upload.oss-endpoint");
        requireText(properties.getOssBucket(), "app.upload.oss-bucket");
        requireText(properties.getOssAccessKeyId(), "app.upload.oss-access-key-id");
        requireText(properties.getOssAccessKeySecret(), "app.upload.oss-access-key-secret");

        // OSS 客户端是线程安全的，构造一次、全局复用
        this.ossClient = new OSSClientBuilder().build(
                properties.getOssEndpoint(),
                properties.getOssAccessKeyId(),
                properties.getOssAccessKeySecret());

        log.info("对象存储已启用: OSS bucket={}, endpoint={}",
                properties.getOssBucket(), properties.getOssEndpoint());
    }

    @Override
    public String store(byte[] content, String objectKey, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        // 【Content-Type 必须设】不设的话 OSS 默认给 application/octet-stream，
        // 浏览器点开图片会变成"下载"而不是直接显示
        metadata.setContentType(contentType);

        PutObjectRequest request = new PutObjectRequest(
                properties.getOssBucket(),
                objectKey,
                new ByteArrayInputStream(content),
                metadata);

        ossClient.putObject(request);

        String url = properties.isOssSignedUrl() ? buildSignedUrl(objectKey) : buildPublicUrl(objectKey);
        log.info("文件已上传到 OSS: bucket={}, key={}", properties.getOssBucket(), objectKey);
        return url;
    }

    /**
     * 公有读 Bucket 的固定地址：
     * {@code https://bucket.endpoint-host/objectKey}
     *
     * 【为什么要把 endpoint 里的协议头切掉再拼】
     *   Endpoint 配的是 {@code https://oss-cn-hangzhou.aliyuncs.com}，
     *   而 URL 里 bucket 是拼在【主机名最前面】的：
     *   {@code https://my-bucket.oss-cn-hangzhou.aliyuncs.com/xxx}。
     *   直接字符串相加会得到 {@code https://my-bucket.https://oss-...} 这种废地址。
     *   所以这里用 URI 解析出协议与主机，分别处理。
     */
    private String buildPublicUrl(String objectKey) {
        URI uri = URI.create(properties.getOssEndpoint());
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
        return scheme + "://" + properties.getOssBucket() + "." + uri.getHost() + "/" + objectKey;
    }

    /**
     * 私有读 Bucket 的签名地址：在 URL 后面拼上临时签名，过期即失效。
     * 签名由官方 SDK 生成（自己算签名极易出错，且算法会变）。
     */
    private String buildSignedUrl(String objectKey) {
        Date expiration = new Date(System.currentTimeMillis()
                + properties.getOssSignedUrlExpireSeconds() * 1000L);
        return ossClient.generatePresignedUrl(
                properties.getOssBucket(), objectKey, expiration).toString();
    }

    /** Bean 销毁前关闭客户端，释放内部连接池。不关的话在频繁重启的开发环境里会堆积连接 */
    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OSS 客户端已关闭");
        }
    }

    /** 校验一项必填配置，缺失时抛出【说清楚缺哪一项】的异常 */
    private void requireText(String value, String keyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "启用了 OSS 存储但缺少配置项 " + keyName + "，请检查环境变量或配置文件");
        }
    }
}
