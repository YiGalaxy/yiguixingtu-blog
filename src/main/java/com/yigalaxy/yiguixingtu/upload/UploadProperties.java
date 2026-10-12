package com.yigalaxy.yiguixingtu.upload;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.List;

/** Upload limits, allowed extensions and storage paths from app.upload.*. */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** Keep this directory on persistent storage in production. */
    private String localDir = "./uploads";

    /** Public URL prefix for files served by /uploads/**. */
    private String baseUrl = "http://localhost:8082";

    private List<String> allowedExtensions = List.of("jpg", "jpeg", "png", "gif", "webp");
    private DataSize maxSize = DataSize.ofMegabytes(10);
    private String keyPrefix = "cover";

    private List<String> audioAllowedExtensions = List.of("mp3");
    private DataSize audioMaxSize = DataSize.ofMegabytes(20);
    private String audioKeyPrefix = "music";

    // Active web formats are excluded; attachment responses are also forced to download.
    private List<String> attachmentAllowedExtensions = List.of(
            "pdf", "zip", "7z", "rar",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json",
            "mp3", "mp4");
    private DataSize attachmentMaxSize = DataSize.ofMegabytes(100);
    private String attachmentKeyPrefix = "attachment";

    /** Total storage cap; each per-file limit must also fit the multipart request limit. */
    private DataSize maxTotalSize = DataSize.ofGigabytes(5);
}
