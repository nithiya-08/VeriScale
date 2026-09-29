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
    void loginAsChoiceMustMatchTheAccountRole() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@lm.demo\",\"password\":\"" + PASSWORD + "\",\"role\":\"STATE_ADMIN\"}"))
                .andExpect(status().isOk());
        MvcResult wrong = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@lm.demo\",\"password\":\"" + PASSWORD + "\",\"role\":\"STATE_ADMIN\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).contains("not registered as Admin");
    }

    private MvcResult postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void ownerSignUpNeedsTheEmailedCode() throws Exception {
        String email = "new.owner." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MvcResult started = postJson("/api/auth/register",
                Map.of("name", "New Owner", "email", email, "phone", "9876500000", "password", "Secret@123"));
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        JsonNode s = json.readTree(started.getResponse().getContentAsString());
        String code = s.get("demoCode").asText();   // demo profile, no mail server
        assertThat(code).matches("[0-9]{6}");
        assertThat(s.has("token")).isFalse();

        // No account until verified.
        assertThat(postJson("/api/auth/login", Map.of("email", email, "password", "Secret@123"))
                .getResponse().getStatus()).isEqualTo(401);
        // Asking again straight away is rate-limited.
        assertThat(postJson("/api/auth/register/resend", Map.of("email", email)).getResponse().getStatus()).isEqualTo(429);

        String wrong = code.equals("000000") ? "111111" : "000000";
        MvcResult bad = postJson("/api/auth/register/verify", Map.of("email", email, "code", wrong));
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("Wrong code");

        MvcResult ok = postJson("/api/auth/register/verify", Map.of("email", email, "code", code));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(ok.getResponse().getContentAsString()).get("user").get("role").asText()).isEqualTo("OWNER");

        assertThat(postJson("/api/auth/login", Map.of("email", email, "password", "Secret@123"))
                .getResponse().getStatus()).isEqualTo(200);
        // The code is single-use and the email is now taken.
        assertThat(postJson("/api/auth/register/verify", Map.of("email", email, "code", code)).getResponse().getStatus()).isNotEqualTo(200);
        assertThat(postJson("/api/auth/register",
                Map.of("name", "Again", "email", email, "phone", "9876500000", "password", "Secret@123"))
                .getResponse().getStatus()).isNotEqualTo(200);
    }

    private MvcResult send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req = req.header("Authorization", token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req).andReturn();
    }

    @Test
    void ownerCanFixMistakesUntilRecordsExist() throws Exception {
        String owner = login("owner@lm.demo");
        String other = login("owner2@lm.demo");
        long district = getJson("/api/meta/jurisdictions", owner).get(0).get("id").asLong();
        JsonNode type = StreamSupport.stream(getJson("/api/meta/instrument-types", owner).spliterator(), false)
                .filter(x -> x.get("errorModel").asText().equals("OIML_R76")).findFirst().orElseThrow();

        MvcResult biz = send(post("/api/businesses"), owner, Map.of("name", "Typo Stroes", "address", "1 Main Rd", "jurisdictionId", district));
        long bizId = json.readTree(biz.getResponse().getContentAsString()).get("id").asLong();
        MvcResult fixed = send(put("/api/businesses/" + bizId), owner, Map.of("name", "Typo Stores", "address", "1 Main Road", "jurisdictionId", district));
        assertThat(fixed.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixed.getResponse().getContentAsString()).contains("Typo Stores");
        // Another owner cannot touch it.
        assertThat(send(put("/api/businesses/" + bizId), other, Map.of("name", "Mine", "jurisdictionId", district)).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(delete("/api/businesses/" + bizId), other, null).getResponse().getStatus()).isEqualTo(404);

        String serial = "FIX-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> inst = new java.util.HashMap<>(Map.of("businessId", bizId, "typeId", type.get("id").asLong(),
                "serialNo", serial, "capacityMax", 30, "eValue", 0.005));
        MvcResult created = send(post("/api/instruments"), owner, inst);
        long instId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        inst.put("serialNo", serial + "-B");
        inst.put("make", "Essae");
        MvcResult edited = send(put("/api/instruments/" + instId), owner, inst);
        assertThat(edited.getResponse().getStatus()).isEqualTo(200);
        assertThat(edited.getResponse().getContentAsString()).contains(serial.toUpperCase() + "-B").contains("Essae");

        // Premises with an instrument cannot be deleted; the empty ones can.
        assertThat(send(delete("/api/businesses/" + bizId), owner, null).getResponse().getContentAsString())
                .contains("Remove the instruments at these premises first");
        assertThat(send(delete("/api/instruments/" + instId), owner, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(delete("/api/businesses/" + bizId), owner, null).getResponse().getStatus()).isEqualTo(200);

        // A certified instrument is a signed record: locked for edit and delete.
        JsonNode cert = getJson("/api/certificates", owner).get(0);
        JsonNode certified = StreamSupport.stream(getJson("/api/instruments", owner).spliterator(), false)
                .filter(x -> x.get("id").asLong() == cert.get("instrumentId").asLong()).findFirst().orElseThrow();
        Map<String, Object> same = new java.util.HashMap<>(Map.of("businessId", certified.get("businessId").asLong(),
                "typeId", certified.get("type").get("id").asLong(), "serialNo", "CHANGED-1", "eValue", 1));
        assertThat(send(put("/api/instruments/" + certified.get("id").asLong()), owner, same).getResponse().getStatus()).isEqualTo(409);
        assertThat(send(delete("/api/instruments/" + certified.get("id").asLong()), owner, null).getResponse().getStatus()).isEqualTo(409);
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

    // ---------- certificate PDF ----------

    @Test
    void certificatePdfNamesTheOwnerStoreAndValidityPeriod() throws Exception {
        String owner = login("owner@lm.demo");
        String certNo = StreamSupport.stream(getJson("/api/certificates", owner).spliterator(), false)
                .filter(c -> c.get("serialNo").asText().equals("FD-CH-2002")).findFirst().orElseThrow().get("certNo").asText();
        byte[] pdf = mvc.perform(authGet("/api/certificates/" + certNo + "/pdf", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.write(java.nio.file.Path.of("target/sample-certificate.pdf"), pdf);
        String text = new com.lowagie.text.pdf.parser.PdfTextExtractor(new com.lowagie.text.pdf.PdfReader(pdf)).getTextFromPage(1);
        assertThat(text).contains("Murugan K", "Balaji Fuels", "VALIDITY PERIOD", "From", "To", "CERTIFIED TO");
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
