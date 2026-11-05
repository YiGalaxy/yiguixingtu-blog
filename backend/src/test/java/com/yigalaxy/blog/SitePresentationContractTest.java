package com.yigalaxy.blog;

import java.nio.file.Files;
import java.nio.file.Path;

import com.yigalaxy.blog.site.AdminAboutController;
import com.yigalaxy.blog.site.AdminSettingsController;
import com.yigalaxy.blog.site.SiteCollectionController;
import com.yigalaxy.blog.site.SiteController;
import com.yigalaxy.blog.upload.MediaController;
import com.yigalaxy.blog.upload.MediaInUseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"blog.admin.username=contract-admin", "blog.admin.password=test-only-password"})
class SitePresentationContractTest {
    @Autowired AdminAboutController about;
    @Autowired AdminSettingsController settings;
    @Autowired SiteCollectionController collections;
    @Autowired SiteController site;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path mediaDirectory;

    @Test
    @Transactional
    void presentationFieldsRoundTripAndReferencedImagesCannotBeDeleted() throws Exception {
        String filename = "presentation-contract.png";
        String url = "https://example.com/api/v1/media/" + filename;
        Files.writeString(mediaDirectory.resolve(filename), "pixel");
        var media = new MediaController(mediaDirectory.toString(), jdbc);

        var profile = about.update(new AdminAboutController.AboutForm("个人介绍", "小亿同学", url,
                "reader@example.com", "https://github.com/YiGalaxy", "微信", "12345"));
        assertEquals("小亿同学", profile.nickname());
        assertEquals(url, profile.avatar());
        assertEquals("reader@example.com", profile.email());
        assertThrows(MediaInUseException.class, () -> media.delete(filename));
        about.update(new AdminAboutController.AboutForm("个人介绍", "小亿同学", null, null, null, null, null));

        var project = collections.create("projects", new SiteCollectionController.SiteItemForm(
                "项目封面验收", "项目介绍", "https://example.com", "https://github.com/YiGalaxy",
                "Java, Vue", 0, url));
        assertEquals(url, project.cover());
        assertTrue(collections.list("projects").stream().anyMatch(item -> item.id() == project.id() && url.equals(item.cover())));
        assertThrows(MediaInUseException.class, () -> media.delete(filename));
        collections.delete("projects", project.id());
        assertTrue(media.delete(filename).deleted());

        var favorite = collections.create("favorites", new SiteCollectionController.SiteItemForm(
                "收藏验收", "工具说明", "https://example.com/tool", "开发工具", null, 0, null));
        assertEquals("开发工具", favorite.secondaryUrl());
        var saved = settings.update(new AdminSettingsController.SettingsForm("亿轨星途", null, true,
                6, "通知文字", "版权文字", "蜀ICP备测试号", "川公网安备测试号"));
        assertEquals("通知文字", saved.noticeText());
        assertEquals("版权文字", saved.copyright());
        assertEquals("蜀ICP备测试号", saved.icpNumber());
        assertEquals("川公网安备测试号", saved.policeNumber());
    }
}
