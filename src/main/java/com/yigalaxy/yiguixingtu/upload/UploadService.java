package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Validates uploads and assigns content-addressed keys; FileStorage owns persistence. */
@Slf4j
@Service
public class UploadService {

    private final FileStorage fileStorage;
    private final UploadProperties properties;

    public UploadService(FileStorage fileStorage, UploadProperties properties) {
        this.fileStorage = fileStorage;
        this.properties = properties;
    }

    /** Validates the file, reuses identical content, and stores new content when needed. */
    public UploadResult upload(MultipartFile file, String type) {
        UploadType uploadType = UploadType.resolve(type);

        UploadRules rules = rulesOf(uploadType);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的文件");
        }

        if (file.getSize() > rules.maxSize().toBytes()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    rules.label() + "不能超过 " + rules.maxSize().toMegabytes() + "MB");
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!rules.allowedExtensions().contains(extension)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "只允许上传 " + String.join(" / ", rules.allowedExtensions()) + " 格式的" + rules.label());
        }

        // Hash before writing so identical content resolves to the same key.
        String hash = sha256Hex(file);

        String prefix = StringUtils.hasText(rules.prefix()) ? rules.prefix() : uploadType.getCode();
        String objectKey = ContentAddressedKeys.objectKey(prefix, hash, extension);

        boolean alreadyStored = fileStorage.exists(objectKey);

        // Reusing an existing object adds no bytes to storage.
        if (!alreadyStored) {
            checkTotalCapacity(file.getSize());
        }

        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : guessContentType(extension);

        String url;
        if (alreadyStored) {
            log.info("上传内容已存在，复用已有文件: key={}, 原始文件名={}",
                    objectKey, file.getOriginalFilename());
            url = fileStorage.urlOf(objectKey);
        } else {
            // The second stream pass writes the content without loading it into memory.
            try (InputStream content = file.getInputStream()) {
                url = fileStorage.store(content, objectKey, contentType);
            } catch (IOException e) {
                log.error("读取上传文件失败", e);
                throw new BusinessException(ResultCode.ERROR, "读取文件失败，请重试");
            }
        }

        return new UploadResult(url, sanitizeName(file.getOriginalFilename(), objectKey), file.getSize());
    }

    /** Best-effort capacity guard; concurrent uploads may pass against the same snapshot. */
    private void checkTotalCapacity(long incomingSize) {
        DataSize maxTotal = properties.getMaxTotalSize();
        if (maxTotal == null || maxTotal.toBytes() <= 0) {
            return;
        }

        long used = fileStorage.usedBytes();
        if (incomingSize <= maxTotal.toBytes() - used) {
            return;
        }

        throw new BusinessException(ResultCode.PARAM_ERROR,
                "上传空间不足：已用 " + formatSize(used) + "，上限 " + formatSize(maxTotal.toBytes())
                        + "。请先删除不再需要的旧附件或联系管理员扩容");
    }

    /** 把字节数格式化成"12.3 MB"这样的文本（护栏的报错文案要用） */
    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return bytes / 1024 + " KB";
    }

    /** Keeps a safe display name within the attachment column limit, preserving its extension. */
    private String sanitizeName(String originalFilename, String objectKey) {
        String name = originalFilename == null ? "" : originalFilename;

        // Only keep the filename, never a client-supplied path.
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();

        if (name.isEmpty()) {
            int lastSlash = objectKey.lastIndexOf('/');
            name = lastSlash >= 0 ? objectKey.substring(lastSlash + 1) : objectKey;
        }

        int maxNameLength = 100;
        if (name.length() > maxNameLength) {
            // Preserve the extension when shortening a display name.
            String suffix = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 20) {
                suffix = name.substring(dot);
            }
            name = name.substring(0, maxNameLength - suffix.length()) + suffix;
        }
        return name;
    }

    private UploadRules rulesOf(UploadType type) {
        return switch (type) {
            case IMAGE -> new UploadRules(properties.getAllowedExtensions(), properties.getMaxSize(),
                    "图片", properties.getKeyPrefix());
            case AUDIO -> new UploadRules(properties.getAudioAllowedExtensions(), properties.getAudioMaxSize(),
                    "音频", properties.getAudioKeyPrefix());
            case ATTACHMENT -> new UploadRules(properties.getAttachmentAllowedExtensions(),
                    properties.getAttachmentMaxSize(), "附件", properties.getAttachmentKeyPrefix());
        };
    }

    private record UploadRules(List<String> allowedExtensions, DataSize maxSize, String label, String prefix) {
    }

    /** Hashes the stream without loading the entire upload into memory. */
    private String sha256Hex(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return ContentAddressedKeys.sha256Hex(content);
        } catch (IOException e) {
            log.error("读取上传文件失败（计算内容哈希时）", e);
            throw new BusinessException(ResultCode.ERROR, "读取文件失败，请重试");
        }
    }

    /** Reads only the extension from the untrusted client filename. */
    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Fallback MIME type when the client leaves Content-Type empty. */
    private String guessContentType(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            // Browsers recognize audio/mpeg, not audio/mp3.
            case "mp3" -> "audio/mpeg";
            default -> "image/jpeg";
        };
    }
}
