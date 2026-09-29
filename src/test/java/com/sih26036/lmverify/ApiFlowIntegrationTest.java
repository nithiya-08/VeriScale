package com.sih26036.lmverify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API checks against the seeded demo data (H2, demo profile). Each test uses its own
 * seeded instrument so tests do not depend on order.
 */
@SpringBootTest(properties = {
        "app.signing.key-dir=target/test-keys",
        "app.uploads.dir=target/test-uploads",
})
@AutoConfigureMockMvc
class ApiFlowIntegrationTest {

    private static final String PASSWORD = "Demo@123";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private static MockHttpServletRequestBuilder authGet(String url, String token) {
        return token == null ? get(url) : get(url).header("Authorization", token);
    }

    private JsonNode getJson(String url, String token) throws Exception {
        MvcResult r = mvc.perform(authGet(url, token)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode assignment(String officerToken, String serial) throws Exception {
        JsonNode list = getJson("/api/officer/assignments/today", officerToken);
        return StreamSupport.stream(list.spliterator(), false)
                .filter(a -> a.get("instrument").get("serialNo").asText().equals(serial))
                .findFirst().orElseThrow(() -> new AssertionError("No assignment for " + serial));
    }

    private JsonNode sync(String token, Map<String, Object> inspection) throws Exception {
        MvcResult r = mvc.perform(post("/api/sync/inspections").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("inspections", List.of(inspection)))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get(0);
    }

    private static Map<String, Object> inspection(long assignmentId, List<Map<String, Double>> readings) {
        Instant end = Instant.now();
        return Map.of("clientUuid", UUID.randomUUID().toString(), "assignmentId", assignmentId,
                "startedAt", end.minus(20, ChronoUnit.MINUTES).toString(), "completedAt", end.toString(),
                "gpsLat", 13.0418, "gpsLng", 80.2341, "observations", readings);
    }

    // ---------- auth & RBAC ----------

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@lm.demo\",\"password\":\"nope-nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAreEnforcedByTheApi() throws Exception {
        mvc.perform(authGet("/api/applications", null)).andExpect(status().isUnauthorized());
        mvc.perform(authGet("/api/dashboard/state", login("owner@lm.demo"))).andExpect(status().isForbidden());
        mvc.perform(authGet("/api/officer/assignments/today", login("owner@lm.demo"))).andExpect(status().isForbidden());
        mvc.perform(authGet("/api/businesses", login("lmo.chennai@lm.demo"))).andExpect(status().isForbidden());
    }

    // ---------- offline sync -> certificate -> public verify ----------

    @Test
    void passingInspectionIssuesSignedCertificateAndSyncIsIdempotent() throws Exception {
        String officer = login("lmo.chennai@lm.demo");
        JsonNode a = assignment(officer, "ES-CH-1001");
        Map<String, Object> insp = inspection(a.get("assignmentId").asLong(), List.of(
                Map.of("testLoad", 0.1, "indicatedValue", 0.1),
                Map.of("testLoad", 10.0, "indicatedValue", 10.005),
                Map.of("testLoad", 30.0, "indicatedValue", 29.995)));

        JsonNode first = sync(officer, insp);
        assertThat(first.get("outcome").asText()).isEqualTo("SYNCED");
        assertThat(first.get("result").asText()).isEqualTo("PASS");
        String certNo = first.get("certNo").asText();
        assertThat(certNo).startsWith("LM-");

        // Same clientUuid again (e.g. connection dropped after upload): no duplicate inspection.
        JsonNode again = sync(officer, insp);
        assertThat(again.get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(again.get("certNo").asText()).isEqualTo(certNo);

        // Owner sees the certificate; its QR URL carries the signature.
        JsonNode cert = StreamSupport.stream(getJson("/api/certificates", login("owner@lm.demo")).spliterator(), false)
                .filter(c -> c.get("certNo").asText().equals(certNo)).findFirst().orElseThrow();
        String sig = cert.get("verifyUrl").asText().replaceAll(".*[?&]s=", "");

        JsonNode valid = getJson("/api/public/verify?c=" + certNo + "&s=" + sig, null);
        assertThat(valid.get("verdict").asText()).isEqualTo("VALID");
        assertThat(valid.get("signatureChecked").asBoolean()).isTrue();

        String forgedSig = (sig.charAt(0) == 'A' ? "B" : "A") + sig.substring(1);
        assertThat(getJson("/api/public/verify?c=" + certNo + "&s=" + forgedSig, null).get("verdict").asText()).isEqualTo("FAKE");
        assertThat(getJson("/api/public/verify?c=LM-2026-NOTEXIST", null).get("verdict").asText()).isEqualTo("FAKE");
    }

    @Test
    void serverRecomputesPassFailAndFailedInspectionGetsNoCertificate() throws Exception {
        String officer = login("lmo.chennai@lm.demo");
        JsonNode a = assignment(officer, "FD-CH-2001");
        // 20 L dispensed as 20.5 L is 2.5% error against a 0.5% limit.
        JsonNode r = sync(officer, inspection(a.get("assignmentId").asLong(), List.of(
                Map.of("testLoad", 5.0, "indicatedValue", 5.01),
                Map.of("testLoad", 20.0, "indicatedValue", 20.5))));
        assertThat(r.get("outcome").asText()).isEqualTo("SYNCED");
        assertThat(r.get("result").asText()).isEqualTo("FAIL");
        assertThat(r.hasNonNull("certNo")).isFalse();
    }

    @Test
    void officerCannotSyncSomeoneElsesAssignment() throws Exception {
        // Taken from the admin list so this does not depend on whether another test already inspected it.
        long assignmentId = StreamSupport.stream(getJson("/api/admin/applications", login("admin@lm.demo")).spliterator(), false)
                .filter(x -> x.get("serialNo").asText().equals("ES-CH-1001")).findFirst().orElseThrow().get("assignmentId").asLong();
        JsonNode r = sync(login("lmo.madurai@lm.demo"), inspection(assignmentId,
                List.of(Map.of("testLoad", 1.0, "indicatedValue", 1.0))));
        assertThat(r.get("outcome").asText()).isEqualTo("REJECTED");
    }

    // ---------- allocation rules ----------

    @Test
    void gatcIsEligibleOnlyForItsAuthorisedCategories() throws Exception {
        String admin = login("admin@lm.demo");
        JsonNode apps = getJson("/api/admin/applications", admin);
        long fuelApp = StreamSupport.stream(apps.spliterator(), false)
                .filter(x -> x.get("serialNo").asText().equals("FD-CH-2001")).findFirst().orElseThrow().get("id").asLong();
        long scaleApp = StreamSupport.stream(apps.spliterator(), false)
                .filter(x -> x.get("serialNo").asText().equals("ES-CH-1001")).findFirst().orElseThrow().get("id").asLong();

        assertThat(getJson("/api/admin/applications/" + fuelApp + "/eligible-officers", admin).findValuesAsText("role"))
                .doesNotContain("GATC").contains("LMO");
        assertThat(getJson("/api/admin/applications/" + scaleApp + "/eligible-officers", admin).findValuesAsText("role"))
                .contains("GATC", "LMO");
    }

    // ---------- owner documents ----------

    @Test
    void ownerCanUploadPdfButDisguisedFilesAndOtherOwnersAreBlocked() throws Exception {
        String owner = login("owner@lm.demo");
        long instrumentId = StreamSupport.stream(getJson("/api/instruments", owner).spliterator(), false)
                .filter(i -> i.get("serialNo").asText().equals("ES-CH-1002")).findFirst().orElseThrow().get("id").asLong();

        byte[] pdf = "%PDF-1.4\n% test\n".getBytes();
        MvcResult up = mvc.perform(multipart("/api/instruments/" + instrumentId + "/documents")
                        .file(new MockMultipartFile("file", "approval.pdf", "application/pdf", pdf))
                        .param("label", "Model approval certificate").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn();
        long docId = json.readTree(up.getResponse().getContentAsString()).get("id").asLong();

        // An executable renamed to .pdf is rejected by content sniffing.
        mvc.perform(multipart("/api/instruments/" + instrumentId + "/documents")
                        .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[]{'M', 'Z', 0, 0}))
                        .header("Authorization", owner))
                .andExpect(status().isBadRequest());

        mvc.perform(authGet("/api/documents/" + docId, owner)).andExpect(status().isOk());
        mvc.perform(authGet("/api/documents/" + docId, login("admin@lm.demo"))).andExpect(status().isOk());
        mvc.perform(authGet("/api/documents/" + docId, login("owner2@lm.demo"))).andExpect(status().isForbidden());
        mvc.perform(authGet("/api/documents/" + docId, login("lmo.madurai@lm.demo"))).andExpect(status().isForbidden());
    }

    // ---------- languages ----------

    @Test
    void languageFilesArePublicSoLoginAndVerifyPagesCanBeTranslated() throws Exception {
        for (String lang : List.of("hi", "ta", "te", "kn", "ml", "mr", "bn")) {
            JsonNode dict = getJson("/i18n/" + lang + ".json", null);
            assertThat(dict.has("Apply for verification")).as(lang).isTrue();
        }
    }

    // ---------- API docs ----------

    @Test
    void openApiContractIsPublished() throws Exception {
        JsonNode docs = getJson("/v3/api-docs", null);
        assertThat(docs.get("paths").has("/api/public/verify")).isTrue();
        assertThat(docs.get("paths").has("/api/sync/inspections")).isTrue();
    }
}
