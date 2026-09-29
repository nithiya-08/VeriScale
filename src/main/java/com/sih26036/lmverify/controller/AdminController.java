package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** State admin (Controller) endpoints. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('STATE_ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final CertificateService certificateService;
    private final InspectionService inspectionService;
    private final ExpiryService expiryService;
    private final CurrentUserService currentUser;

    @GetMapping("/dashboard/state")
    public Views.Dashboard dashboard() {
        return adminService.dashboard(currentUser.get());
    }

    @GetMapping("/admin/applications")
    public List<Views.ApplicationView> applications(@RequestParam(required = false) String status) {
        return adminService.applications(currentUser.get(), status);
    }

    @PostMapping("/assignments/auto/{appId}")
    public Views.ApplicationView autoAssign(@PathVariable Long appId) {
        return adminService.autoAssign(appId, currentUser.get());
    }

    @GetMapping("/admin/applications/{appId}/eligible-officers")
    public List<Views.UserView> eligibleOfficers(@PathVariable Long appId) {
        return adminService.eligibleOfficers(appId, currentUser.get());
    }

    /** Manual assignment or override of the auto-allocation. Audit-logged. */
    @PutMapping("/admin/applications/{appId}/assignment")
    public Views.ApplicationView assign(@PathVariable Long appId, @Valid @RequestBody Requests.Reassign req) {
        return adminService.assign(appId, req, currentUser.get());
    }

    @GetMapping("/admin/officers")
    public List<Views.OfficerWorkload> officers() {
        return adminService.officers(currentUser.get());
    }

    @PostMapping("/admin/officers")
    public Views.UserView createOfficer(@Valid @RequestBody Requests.CreateOfficer req) {
        return adminService.createOfficer(req, currentUser.get());
    }

    @GetMapping("/fraud-flags")
    public List<Views.FraudFlagView> fraudFlags() {
        return adminService.fraudFlags(currentUser.get());
    }

    @PutMapping("/fraud-flags/{id}")
    public Views.FraudFlagView updateFraudFlag(@PathVariable Long id, @Valid @RequestBody Requests.UpdateFraudFlag req) {
        return adminService.updateFraudFlag(id, req.status(), currentUser.get());
    }

    @GetMapping("/admin/inspections/{id}")
    public Views.InspectionView inspection(@PathVariable Long id) {
        return inspectionService.forAdmin(id, currentUser.get());
    }

    @PostMapping("/certificates/{id}/revoke")
    public void revoke(@PathVariable Long id, @Valid @RequestBody Requests.Revoke req) {
        certificateService.revoke(id, req.reason().trim(), currentUser.get());
    }

    @GetMapping("/admin/audit")
    public List<Views.AuditView> audit() {
        return adminService.audit();
    }

    @GetMapping("/admin/search")
    public List<Views.SearchRow> search(@RequestParam(defaultValue = "") String q) {
        return adminService.search(q, currentUser.get());
    }

    @GetMapping("/admin/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String q) {
        byte[] body = adminService.exportCsv(q, currentUser.get()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lm-records-" + LocalDate.now() + ".csv\"")
                .body(body);
    }

    @PutMapping("/admin/instrument-types/{id}")
    public Views.InstrumentTypeView updateType(@PathVariable Long id, @Valid @RequestBody Requests.UpdateInstrumentType req) {
        return adminService.updateInstrumentType(id, req, currentUser.get());
    }

    /** Runs the daily expiry/reminder job now (useful in a demo). */
    @PostMapping("/admin/run-expiry-job")
    public Map<String, Integer> runExpiryJob() {
        return expiryService.run(currentUser.get());
    }
}
