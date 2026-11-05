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
@RequestMapping("/api/v1/admin")
public class AdminTaxonomyController {
    private final JdbcTemplate jdbc;

    public AdminTaxonomyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/categories")
    public List<TaxonomyItem> categories() {
        return jdbc.query("SELECT id, name, description, sort FROM category ORDER BY sort, name",
                (rs, row) -> new TaxonomyItem(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getInt("sort")));
    }

    @PostMapping("/categories")
    @Transactional
    public TaxonomyItem createCategory(@Valid @RequestBody TaxonomyForm form) {
        jdbc.update("INSERT INTO category (name, description, sort) VALUES (?, ?, ?)", form.name().trim(), clean(form.description()), form.sort());
        return findCategory(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/categories/{id}")
    public TaxonomyItem updateCategory(@PathVariable long id, @Valid @RequestBody TaxonomyForm form) {
        if (jdbc.update("UPDATE category SET name = ?, description = ?, sort = ? WHERE id = ?",
                form.name().trim(), clean(form.description()), form.sort(), id) == 0) throw new TaxonomyNotFoundException();
        return findCategory(id);
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable long id) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE category_id = ? AND deleted = 0", Integer.class, id);
        if (used != null && used > 0) throw new TaxonomyInUseException("分类仍被文章使用");
        if (jdbc.update("DELETE FROM category WHERE id = ?", id) == 0) throw new TaxonomyNotFoundException();
    }

    @GetMapping("/tags")
    public List<TaxonomyItem> tags() {
        return jdbc.query("SELECT id, name, sort FROM tag ORDER BY sort, name",
                (rs, row) -> new TaxonomyItem(rs.getLong("id"), rs.getString("name"), null, rs.getInt("sort")));
    }

    @PostMapping("/tags")
    @Transactional
    public TaxonomyItem createTag(@Valid @RequestBody TaxonomyForm form) {
        jdbc.update("INSERT INTO tag (name, sort) VALUES (?, ?)", form.name().trim(), form.sort());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new TaxonomyItem(id, form.name().trim(), null, form.sort());
    }

    @PutMapping("/tags/{id}")
    public TaxonomyItem updateTag(@PathVariable long id, @Valid @RequestBody TaxonomyForm form) {
        if (jdbc.update("UPDATE tag SET name = ?, sort = ? WHERE id = ?", form.name().trim(), form.sort(), id) == 0)
            throw new TaxonomyNotFoundException();
        return new TaxonomyItem(id, form.name().trim(), null, form.sort());
    }

    @DeleteMapping("/tags/{id}")
    public void deleteTag(@PathVariable long id) {
        jdbc.update("DELETE FROM article_tag WHERE tag_id = ?", id);
        if (jdbc.update("DELETE FROM tag WHERE id = ?", id) == 0) throw new TaxonomyNotFoundException();
    }

    private TaxonomyItem findCategory(long id) {
        return jdbc.queryForObject("SELECT id, name, description, sort FROM category WHERE id = ?",
                (rs, row) -> new TaxonomyItem(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getInt("sort")), id);
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record TaxonomyForm(@NotBlank @Size(max = 50) String name, @Size(max = 255) String description, int sort) { }
    public record TaxonomyItem(long id, String name, String description, int sort) { }
}
