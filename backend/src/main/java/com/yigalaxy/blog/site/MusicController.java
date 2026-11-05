package com.yigalaxy.blog.site;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MusicController {
    private final JdbcTemplate jdbc;

    public MusicController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/music")
    public List<Track> list() {
        return jdbc.query("SELECT id, title, artist, audio_url, cover_url, lyrics, sort FROM music_track WHERE enabled = 1 ORDER BY sort, id",
                (rs, row) -> new Track(rs.getLong("id"), rs.getString("title"), rs.getString("artist"),
                        rs.getString("audio_url"), rs.getString("cover_url"), rs.getString("lyrics"), rs.getInt("sort"), true));
    }

    @GetMapping("/admin/music")
    public List<Track> adminList() {
        return jdbc.query("SELECT id, title, artist, audio_url, cover_url, lyrics, sort, enabled FROM music_track ORDER BY sort, id",
                (rs, row) -> new Track(rs.getLong("id"), rs.getString("title"), rs.getString("artist"),
                        rs.getString("audio_url"), rs.getString("cover_url"), rs.getString("lyrics"), rs.getInt("sort"), rs.getBoolean("enabled")));
    }

    @PostMapping("/admin/music")
    @Transactional
    public Track create(@Valid @RequestBody TrackForm form) {
        validateMediaUrl(form.audioUrl());
        validateMediaUrl(form.coverUrl());
        jdbc.update("INSERT INTO music_track (title, artist, audio_url, cover_url, lyrics, sort, enabled) VALUES (?, ?, ?, ?, ?, ?, ?)",
                form.title().trim(), clean(form.artist()), form.audioUrl().trim(), clean(form.coverUrl()), clean(form.lyrics()), form.sort(), form.enabled());
        return find(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/admin/music/{id}")
    public Track update(@PathVariable long id, @Valid @RequestBody TrackForm form) {
        validateMediaUrl(form.audioUrl());
        validateMediaUrl(form.coverUrl());
        int count = jdbc.update("UPDATE music_track SET title = ?, artist = ?, audio_url = ?, cover_url = ?, lyrics = ?, sort = ?, enabled = ? WHERE id = ?",
                form.title().trim(), clean(form.artist()), form.audioUrl().trim(), clean(form.coverUrl()), clean(form.lyrics()), form.sort(), form.enabled(), id);
        if (count == 0) throw new SiteItemNotFoundException(id);
        return find(id);
    }

    @DeleteMapping("/admin/music/{id}")
    public void delete(@PathVariable long id) {
        if (jdbc.update("DELETE FROM music_track WHERE id = ?", id) == 0) throw new SiteItemNotFoundException(id);
    }

    private Track find(long id) {
        return jdbc.queryForObject("SELECT id, title, artist, audio_url, cover_url, lyrics, sort, enabled FROM music_track WHERE id = ?",
                (rs, row) -> new Track(rs.getLong("id"), rs.getString("title"), rs.getString("artist"),
                        rs.getString("audio_url"), rs.getString("cover_url"), rs.getString("lyrics"), rs.getInt("sort"), rs.getBoolean("enabled")), id);
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void validateMediaUrl(String value) {
        if (value == null || value.isBlank()) return;
        String url = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!url.startsWith("https://") && !url.startsWith("http://") && !url.startsWith("/api/v1/media/"))
            throw new IllegalArgumentException("媒体地址仅支持 http(s) 或站内媒体路径");
    }

    public record Track(long id, String title, String artist, String audioUrl, String coverUrl, String lyrics, int sort, boolean enabled) { }
    public record TrackForm(@NotBlank @Size(max = 160) String title, @Size(max = 120) String artist,
                            @NotBlank @Size(max = 500) String audioUrl, @Size(max = 500) String coverUrl,
                            @Size(max = 100000) String lyrics, int sort, boolean enabled) { }
}
