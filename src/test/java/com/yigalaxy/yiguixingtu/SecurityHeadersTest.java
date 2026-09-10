package com.yigalaxy.yiguixingtu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================
 * 安全响应头测试
 *
 * 【为什么一个 JSON 接口也要管这些头】
 *   这些头是给【浏览器】看的，用来约束浏览器怎么对待我们的响应。
 *   本项目虽然是前后端分离的接口服务，但：
 *     · 同一台机器上挂着前端页面（会被浏览器当成"同源"访问）
 *     · 而且很可能有人把接口地址直接贴进浏览器打开
 *   多几行配置的成本几乎为零，却能挡掉几类最常见的网页攻击：
 *     XSS 的内容嗅探、点击劫持、Referer 泄漏、HTTPS 降级。
 *
 * 【为什么必须用断言盯着，而不是"配了就算完"】
 *   安全配置最危险的失败方式是【静默失效】：
 *   写了配置但被别的东西覆盖、或者写错了 API 名，
 *   页面照样能打开，谁也不会发现 —— 直到真被人利用。
 *   所以这里逐个头断言。
 *
 * 【一个容易误解的点：HSTS 在本地看不到】
 *   浏览器【只在 HTTPS 响应上】认 Strict-Transport-Security。
 *   本地开发是 http://localhost，所以用 curl 看不到它 ——
 *   这是对的，不是配置没生效。
 *   所以下面用 {@code .secure(true)} 模拟一个 HTTPS 请求来验证它。
 * =====================================================================
 */
class SecurityHeadersTest extends AbstractIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    @DisplayName("① X-Content-Type-Options: nosniff —— 禁止浏览器猜内容类型")
    void shouldSendContentTypeOptions() throws Exception {
        mockMvc.perform(get("/article/page"))
                .andExpect(status().isOk())
                // 没有它的话，浏览器可能把一个被上传的文本文件当 HTML 解析执行，
                // 这是上传功能最典型的一类连带风险（XSS）
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("② X-Frame-Options: DENY —— 禁止被 iframe 嵌套（防点击劫持）")
    void shouldSendFrameOptionsDeny() throws Exception {
        mockMvc.perform(get("/article/page"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("③ Referrer-Policy: no-referrer —— 接口地址不泄漏给第三方站点")
    void shouldSendReferrerPolicy() throws Exception {
        mockMvc.perform(get("/article/page"))
                // 我们的接口地址可能带查询参数（比如 ?keyword=xxx），
                // 默认策略下这些会跟着 Referer 发给第三方站点
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("④ HTTPS 请求上带 HSTS（并包含子域名、有效期一年）")
    void shouldSendHstsOnSecureRequest() throws Exception {
        mockMvc.perform(get("/article/page").secure(true))
                // includeSubDomains 与 max-age 都要在，缺一个都会削弱防护
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=31536000"),
                                org.hamcrest.Matchers.containsString("includeSubDomains"))));
    }

    @Test
    @DisplayName("⑤ HTTP 请求上【不该】带 HSTS（浏览器不认，带上只是噪音）")
    void shouldNotSendHstsOnPlainHttp() throws Exception {
        // 这条不是吹毛求疵：它把"HSTS 只对 HTTPS 有意义"这条规则钉住了。
        // 如果哪天有人把它改成无条件发送，这条用例会提醒他：
        // 在明文请求上发 HSTS 是没有意义的（而且容易让人误以为"HTTP 也安全了"）
        mockMvc.perform(get("/article/page"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @DisplayName("⑥ 错误响应也带安全头（401/404 这些路径同样不能漏）")
    void errorResponses_shouldAlsoHaveSecurityHeaders() throws Exception {
        // 安全头最容易漏的地方就是"出错的那条路径" ——
        // 因为大家往往只测了正常返回。这里用一个必然 401 的请求来验证
        mockMvc.perform(get("/user/page"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("⑦ 上传后的图片响应也带 nosniff（这条最要紧：上传是 XSS 的高发区）")
    void uploadedFileResponse_shouldHaveNosniff() throws Exception {
        // 用户上传的文件是最可能被塞进恶意内容的地方。
        // 只要响应里有 nosniff，浏览器就不会"自作主张"把它当成 HTML 去执行。
        //
        // 这里用一个不存在的图片路径：它会走到静态资源处理（404），
        // 但安全头是由 Security 过滤链统一加的，所以照样应该在。
        // 真正上传图片的路径已经在 UploadAdminTest 里覆盖过了。
        mockMvc.perform(get("/uploads/cover/2026/09/not-exist.png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
