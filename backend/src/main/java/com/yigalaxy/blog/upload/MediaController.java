package com.yigalaxy.blog.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class MediaController {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> ATTACHMENT_EXTENSIONS = Set.of("pdf", "txt", "md", "zip", "7z", "rar", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv");
    private static final Set<String> MANAGED_EXTENSIONS = java.util.stream.Stream.of(IMAGE_EXTENSIONS, ATTACHMENT_EXTENSIONS,
            Set.of("mp3", "m4a", "ogg", "wav")).flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final Path uploadDirectory;
    private final JdbcTemplate jdbc;

    public MediaController(@Value("${blog.upload-dir:./uploads}") String uploadDirectory, JdbcTemplate jdbc) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.jdbc = jdbc;
    }

    @PostMapping("/admin/uploads")
    public UploadedMedia upload(@RequestParam("file") MultipartFile file) throws IOException {
        return store(file, IMAGE_EXTENSIONS);
    }

    @PostMapping("/admin/attachments")
    public UploadedMedia uploadAttachment(@RequestParam("file") MultipartFile file) throws IOException {
        return store(file, ATTACHMENT_EXTENSIONS);
    }

    private UploadedMedia store(MultipartFile file, Set<String> extensions) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件为空或超过 50 MB");
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename());
        int dot = original.lastIndexOf('.');
        String extension = dot >= 0 ? original.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!extensions.contains(extension)) throw new IllegalArgumentException("不支持此文件格式");

        Files.createDirectories(uploadDirectory);
        Path target = uploadDirectory.resolve(UUID.randomUUID() + "." + extension).normalize();
        if (!target.startsWith(uploadDirectory)) throw new IllegalArgumentException("文件名无效");
        try (var input = file.getInputStream()) {
            Files.copy(input, target);
        }
        String url = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/media/")
                .path(target.getFileName().toString()).toUriString();
        return new UploadedMedia(target.getFileName().toString(), url, file.getSize(), extension);
    }

    @GetMapping("/media/{filename:.+}")
    public ResponseEntity<FileSystemResource> read(@PathVariable String filename) throws IOException {
        Path file = resolve(filename);
        if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        String contentType = switch (extension(filename)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, (ATTACHMENT_EXTENSIONS.contains(extension(filename))
                        ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(file.getFileName().toString()).build().toString())
                .body(new FileSystemResource(file));
    }

    @GetMapping("/admin/uploads")
    public List<UploadedMedia> list() throws IOException {
        if (!Files.isDirectory(uploadDirectory)) return List.of();
        try (var files = Files.list(uploadDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> MANAGED_EXTENSIONS.contains(extension(path.getFileName().toString())))
                    .map(path -> new UploadedMedia(path.getFileName().toString(), mediaUrl(path.getFileName().toString()), size(path), extension(path.getFileName().toString())))
                    .sorted((left, right) -> left.filename().compareTo(right.filename()))
                    .toList();
        }
    }

    @DeleteMapping("/admin/uploads/{filename:.+}")
    public DeleteResult delete(@PathVariable String filename) throws IOException {
        Path file = resolve(filename);
        if (!Files.isRegularFile(file)) throw new MediaNotFoundException(filename);
        if (isReferenced(filename)) throw new MediaInUseException(filename);
        Files.delete(file);
        return new DeleteResult(filename, true);
    }

    private Path resolve(String filename) throws IOException {
        if (filename.contains("/") || filename.contains("\\") || filename.equals(".") || filename.equals(".."))
            throw new IllegalArgumentException("文件名无效");
        Path normalized = uploadDirectory.resolve(filename).normalize();
        if (!normalized.startsWith(uploadDirectory)) throw new IllegalArgumentException("文件名无效");
        if (Files.exists(normalized) && !normalized.toRealPath().startsWith(uploadDirectory.toRealPath()))
            throw new IllegalArgumentException("文件路径无效");
        return normalized;
    }

    private boolean isReferenced(String filename) {
        String token = "%" + filename + "%";
        Integer refs = jdbc.queryForObject("SELECT "
                + "(SELECT COUNT(*) FROM article WHERE deleted = 0 AND (cover LIKE ? OR content LIKE ?)) + "
                + "(SELECT COUNT(*) FROM project WHERE primary_url LIKE ? OR secondary_url LIKE ? OR metadata LIKE ? OR cover LIKE ?) + "
                + "(SELECT COUNT(*) FROM favorite WHERE primary_url LIKE ? OR secondary_url LIKE ? OR metadata LIKE ?) + "
                + "(SELECT COUNT(*) FROM friend_link WHERE primary_url LIKE ? OR secondary_url LIKE ? OR metadata LIKE ?) + "
                + "(SELECT COUNT(*) FROM about_page WHERE content LIKE ? OR avatar LIKE ?) + "
                + "(SELECT COUNT(*) FROM article_attachment aa JOIN article a ON a.id = aa.article_id WHERE a.deleted = 0 AND aa.url LIKE ?) + "
                + "(SELECT COUNT(*) FROM site_setting WHERE announcement LIKE ?)", Integer.class,
                token, token, token, token, token, token, token, token, token, token, token, token, token, token, token, token);
        return refs != null && refs > 0;
    }

    private String mediaUrl(String filename) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/media/").path(filename).toUriString();
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static long size(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return 0; }
    }

    public record UploadedMedia(String filename, String url, long size, String type) { }
    public record DeleteResult(String filename, boolean deleted) { }
}
