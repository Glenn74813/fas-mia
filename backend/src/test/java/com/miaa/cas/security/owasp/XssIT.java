package com.miaa.cas.security.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cross-site scripting (MS §9, §18; OWASP A03). The API only ever answers JSON (or a download with an explicit type
 * and attachment disposition), never HTML, with nosniff and a restrictive CSP; stored markup comes back as inert
 * data; active content (HTML, SVG, scripts) cannot be uploaded. The React client renders text, never HTML
 * (frontend xss.test.tsx).
 */
class XssIT extends AbstractIntegrationTest {

    static final String PAYLOAD = "<script>alert('xss')</script><img src=x onerror=alert(1)><svg onload=alert(2)>";

    @Test
    void storedMarkupIsReturnedAsInertJson() throws Exception {
        Session user = login(createUser("xss", "ACCOUNTANT"));
        String category = jdbc.queryForObject("SELECT id::text FROM ticket_categories WHERE code = 'PLC-OTHER'", String.class);
        MvcResult created = mvc.perform(post("/api/v1/tickets").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("ticketType", "INCIDENT", "priority", "P4", "severity", "S4", "categoryId", category,
                        "title", PAYLOAD, "description", PAYLOAD)))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = body(created).get("id").asText();

        MvcResult r = mvc.perform(get("/api/v1/tickets/" + id).header("Authorization", user.bearer())).andReturn();
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self'").contains("object-src 'none'").contains("frame-ancestors 'none'").doesNotContain("unsafe-eval");
        assertThat(csp.substring(csp.indexOf("script-src"))).startsWith("script-src 'self';");
        // Stored verbatim as data: the client is responsible for rendering it as text, which React does.
        assertThat(body(r).get("title").asText()).isEqualTo(PAYLOAD);
    }

    @Test
    void errorResponsesNeverReflectInputAsHtml() throws Exception {
        Session user = login(createUser("xsserr", "ACCOUNTANT"));
        MvcResult r = mvc.perform(get("/api/v1/tickets/<script>alert(1)</script>").header("Authorization", user.bearer())).andReturn();
        assertThat(r.getResponse().getStatus()).isBetween(400, 404);
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        MvcResult bad = mvc.perform(post("/api/v1/tickets").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketType\":\"" + "<img src=x onerror=alert(1)>" + "\"}")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentType()).startsWith("application/problem+json");
    }

    @Test
    void activeContentCannotBeUploadedAndDownloadsAreAttachments() throws Exception {
        Session user = login(createUser("xssup", "ACCOUNTANT"));
        String category = jdbc.queryForObject("SELECT id::text FROM ticket_categories WHERE code = 'PLC-OTHER'", String.class);
        JsonNode t = body(mvc.perform(post("/api/v1/tickets").header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("ticketType", "INCIDENT", "priority", "P4", "severity", "S4", "categoryId", category,
                        "title", "Upload test", "description", "Upload test")))).andReturn());
        String id = t.get("id").asText();
        byte[] html = ("<html><body>" + PAYLOAD + "</body></html>").getBytes(StandardCharsets.UTF_8);
        byte[] svg = ("<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>").getBytes(StandardCharsets.UTF_8);
        for (MockMultipartFile f : new MockMultipartFile[] {
                new MockMultipartFile("file", "page.html", "text/html", html),
                new MockMultipartFile("file", "logo.svg", "image/svg+xml", svg),
                new MockMultipartFile("file", "run.js", "application/javascript", "alert(1)".getBytes(StandardCharsets.UTF_8)),
                // An HTML file renamed to .pdf fails the signature check.
                new MockMultipartFile("file", "invoice.pdf", "application/pdf", html)}) {
            int status = mvc.perform(multipart("/api/v1/tickets/" + id + "/documents").file(f).header("Authorization", user.bearer()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(f.getOriginalFilename()).isBetween(400, 499);
        }

        byte[] pdf = "%PDF-1.4\n% x\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        JsonNode withPdf = body(mvc.perform(multipart("/api/v1/tickets/" + id + "/documents")
                .file(new MockMultipartFile("file", "<script>x</script>.pdf", "application/pdf", pdf)).header("Authorization", user.bearer())).andReturn());
        JsonNode doc = withPdf.get("documents").get(0);
        assertThat(doc.get("fileName").asText()).doesNotContain("<").doesNotContain(">");
        MvcResult download = mvc.perform(get("/api/v1/documents/" + doc.get("id").asText() + "/content").header("Authorization", user.bearer()))
                .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
