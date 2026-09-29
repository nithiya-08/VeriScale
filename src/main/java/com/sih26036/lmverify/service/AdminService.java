package com.sih26036.lmverify.service;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** State admin (Controller) operations. Everything is scoped to the admin's own state. */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final List<Application.Status> PENDING = List.of(Application.Status.SUBMITTED,
            Application.Status.ASSIGNED, Application.Status.SCHEDULED, Application.Status.INSPECTED_PENDING_SYNC,
            Application.Status.INSPECTED, Application.Status.PASSED);

    private final ApplicationRepository applicationRepository;
    private final AssignmentRepository assignmentRepository;
    private final CertificateRepository certificateRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentTypeRepository instrumentTypeRepository;
    private final InspectionRepository inspectionRepository;
    private final UserRepository userRepository;
    private final GatcRepository gatcRepository;
    private final JurisdictionRepository jurisdictionRepository;
    private final FraudFlagRepository fraudFlagRepository;
    private final AuditLogRepository auditLogRepository;
    private final AllocationService allocationService;
    private final OwnerService ownerService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.alerts.due-soon-days}")
    private int dueSoonDays;

    @Transactional(readOnly = true)
    public Views.Dashboard dashboard(User admin) {
        String state = admin.getState();
        List<Application> apps = applicationRepository.findByInstrument_Business_Jurisdiction_StateOrderBySubmittedAtDesc(state);
        List<Certificate> certs = certificateRepository.findByInstrument_Business_Jurisdiction_State(state);
        LocalDate today = LocalDate.now(CertificateService.ZONE);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Application.Status s : Application.Status.values()) {
            byStatus.put(s.name(), apps.stream().filter(a -> a.getStatus() == s).count());
        }
        Map<String, Long> certByStatus = new LinkedHashMap<>();
        for (Certificate.Status s : Certificate.Status.values()) {
            certByStatus.put(s.name(), certs.stream().filter(c -> c.getStatus() == s).count());
        }
        long dueSoon = certs.stream().filter(c -> isDueSoon(c, today)).count();

        Map<String, Long> instrumentsByDistrict = instrumentRepository.search(state, "").stream()
                .collect(Collectors.groupingBy(i -> i.getBusiness().getJurisdiction().getDistrict(), Collectors.counting()));
        List<Views.DistrictStats> districts = new ArrayList<>();
        for (Jurisdiction j : jurisdictionRepository.findByStateOrderByDistrict(state)) {
            String d = j.getDistrict();
            long instruments = instrumentsByDistrict.getOrDefault(d, 0L);
            long pending = apps.stream().filter(a -> district(a).equals(d) && PENDING.contains(a.getStatus())).count();
            long valid = certs.stream().filter(c -> district(c).equals(d) && c.getStatus() == Certificate.Status.VALID).count();
            long soon = certs.stream().filter(c -> district(c).equals(d) && isDueSoon(c, today)).count();
            long expired = certs.stream().filter(c -> district(c).equals(d) && c.getStatus() == Certificate.Status.EXPIRED).count();
            districts.add(new Views.DistrictStats(d, instruments, pending, valid, soon, expired));
        }

        return new Views.Dashboard(state, byStatus, certByStatus, dueSoon,
                fraudFlagRepository.countByOfficer_StateAndStatus(state, FraudFlag.Status.OPEN), districts, officers(admin));
    }

    private boolean isDueSoon(Certificate c, LocalDate today) {
        return c.getStatus() == Certificate.Status.VALID && !c.getValidUntil().isAfter(today.plusDays(dueSoonDays));
    }

    private static String district(Application a) {
        return a.getInstrument().getBusiness().getJurisdiction().getDistrict();
    }

    private static String district(Certificate c) {
        return c.getInstrument().getBusiness().getJurisdiction().getDistrict();
    }

    @Transactional(readOnly = true)
    public List<Views.OfficerWorkload> officers(User admin) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<Inspection> recent = inspectionRepository.findByCompletedAtAfter(since);
        return userRepository.findByStateAndRoleInOrderByName(admin.getState(), List.of(User.Role.LMO, User.Role.GATC)).stream()
                .map(u -> {
                    List<Inspection> mine = recent.stream().filter(i -> i.getOfficer().getId().equals(u.getId())).toList();
                    Double pass = mine.isEmpty() ? null
                            : Math.round(1000.0 * mine.stream().filter(i -> i.getResult() == Inspection.Result.PASS).count() / mine.size()) / 10.0;
                    return new Views.OfficerWorkload(u.getId(), u.getName(), u.getRole().name(),
                            u.getJurisdiction() == null ? null : u.getJurisdiction().getDistrict(),
                            assignmentRepository.countByOfficerAndApplication_StatusIn(u, AllocationService.OPEN_STATUSES),
                            mine.size(), pass);
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Views.ApplicationView> applications(User admin, String status) {
        return applicationRepository.findByInstrument_Business_Jurisdiction_StateOrderBySubmittedAtDesc(admin.getState()).stream()
                .filter(a -> status == null || status.isBlank() || a.getStatus().name().equalsIgnoreCase(status))
                .map(ownerService::view).toList();
    }

    @Transactional
    public Views.ApplicationView autoAssign(Long appId, User admin) {
        Application app = appInState(appId, admin);
        Assignment a = allocationService.autoAssign(app, admin)
                .orElseThrow(() -> ApiException.conflict("No eligible LMO or GATC in " + district(app)
                        + " for this instrument category. Add an officer or assign manually."));
        return Views.ApplicationView.of(app, a, null);
    }

    @Transactional(readOnly = true)
    public List<Views.UserView> eligibleOfficers(Long appId, User admin) {
        Application app = appInState(appId, admin);
        return allocationService.eligibleOfficers(app.getInstrument()).stream().map(Views.UserView::of).toList();
    }

    /** Assigns a SUBMITTED application manually, or overrides an existing assignment. Audit-logged. */
    @Transactional
    public Views.ApplicationView assign(Long appId, Requests.Reassign req, User admin) {
        Application app = appInState(appId, admin);
        User officer = userRepository.findById(req.officerId())
                .orElseThrow(() -> ApiException.badRequest("Officer not found"));
        if (req.scheduledDate().isBefore(LocalDate.now(CertificateService.ZONE))) {
            throw ApiException.badRequest("Scheduled date cannot be in the past");
        }
        Optional<Assignment> existing = assignmentRepository.findByApplication(app);
        Assignment a = existing.isPresent()
                ? allocationService.reassign(existing.get(), officer, req.scheduledDate(), admin)
                : allocationService.manualAssign(app, officer, req.scheduledDate(), admin);
        return Views.ApplicationView.of(app, a, null);
    }

    private Application appInState(Long appId, User admin) {
        Application app = applicationRepository.findById(appId)
                .orElseThrow(() -> ApiException.notFound("Application not found"));
        if (!app.getInstrument().getBusiness().getJurisdiction().getState().equals(admin.getState())) {
            throw ApiException.forbidden("Application is outside your state");
        }
        return app;
    }

    @Transactional
    public Views.UserView createOfficer(Requests.CreateOfficer req, User admin) {
        if (req.role() != User.Role.LMO && req.role() != User.Role.GATC) {
            throw ApiException.badRequest("Role must be LMO or GATC");
        }
        Jurisdiction j = jurisdictionRepository.findById(req.jurisdictionId())
                .filter(x -> x.getState().equals(admin.getState()))
                .orElseThrow(() -> ApiException.badRequest("District not found in your state"));
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User u = userRepository.save(User.builder()
                .name(req.name().trim()).email(req.email().trim().toLowerCase()).phone(req.phone())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(req.role()).jurisdiction(j).state(admin.getState()).build());
        if (req.role() == User.Role.GATC) {
            if (req.authorisedTypeIds() == null || req.authorisedTypeIds().isEmpty()) {
                throw ApiException.badRequest("A GATC needs at least one authorised instrument category");
            }
            Set<InstrumentType> types = new HashSet<>(instrumentTypeRepository.findAllById(req.authorisedTypeIds()));
            gatcRepository.save(Gatc.builder()
                    .user(u).name(req.gatcName() == null || req.gatcName().isBlank() ? u.getName() : req.gatcName())
                    .jurisdiction(j).authorisedTypes(types).build());
        }
        auditService.log(admin, "User", u.getId(), "OFFICER_CREATED", null, u.getRole() + " " + j.getDistrict());
        return Views.UserView.of(u);
    }

    @Transactional(readOnly = true)
    public List<Views.FraudFlagView> fraudFlags(User admin) {
        return fraudFlagRepository.findByOfficer_StateOrderByCreatedAtDesc(admin.getState()).stream()
                .map(Views.FraudFlagView::of).toList();
    }

    @Transactional
    public Views.FraudFlagView updateFraudFlag(Long id, String status, User admin) {
        FraudFlag f = fraudFlagRepository.findById(id)
                .filter(x -> admin.getState().equals(x.getOfficer().getState()))
                .orElseThrow(() -> ApiException.notFound("Flag not found"));
        FraudFlag.Status to;
        try {
            to = FraudFlag.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Status must be OPEN, REVIEWED or DISMISSED");
        }
        auditService.log(admin, "FraudFlag", f.getId(), "STATUS_CHANGE", f.getStatus().name(), to.name());
        f.setStatus(to);
        return Views.FraudFlagView.of(f);
    }

    @Transactional(readOnly = true)
    public List<Views.AuditView> audit() {
        return auditLogRepository.findTop200ByOrderByAtDesc().stream().map(Views.AuditView::of).toList();
    }

    /** Find by serial number, owner, business, district or certificate number. */
    @Transactional(readOnly = true)
    public List<Views.SearchRow> search(String q, User admin) {
        String query = q == null ? "" : q.trim();
        Map<Long, Instrument> found = new LinkedHashMap<>();
        instrumentRepository.search(admin.getState(), query).forEach(i -> found.put(i.getId(), i));
        if (!query.isEmpty()) {
            certificateRepository.findByCertNoContainingIgnoreCase(query).stream()
                    .map(Certificate::getInstrument)
                    .filter(i -> i.getBusiness().getJurisdiction().getState().equals(admin.getState()))
                    .forEach(i -> found.putIfAbsent(i.getId(), i));
        }
        return found.values().stream().map(i -> {
            Application latest = applicationRepository.findByInstrumentOrderBySubmittedAtDesc(i).stream().findFirst().orElse(null);
            Certificate cert = certificateRepository.findFirstByInstrumentOrderByIssuedAtDesc(i).orElse(null);
            Business b = i.getBusiness();
            return new Views.SearchRow(i.getId(), i.getSerialNo(), i.getType().getName(), b.getName(),
                    b.getOwner().getName(), b.getOwner().getEmail(), b.getJurisdiction().getDistrict(),
                    latest == null ? null : latest.getStatus().name(),
                    cert == null ? null : cert.getId(), cert == null ? null : cert.getCertNo(),
                    cert == null ? null : cert.getStatus().name(), cert == null ? null : cert.getValidUntil());
        }).toList();
    }

    /** CSV export (opens directly in Excel; no paid library needed). */
    @Transactional(readOnly = true)
    public String exportCsv(String q, User admin) {
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel reads UTF-8
        sb.append("Serial No,Instrument Type,Business,Owner,Owner Email,District,Application Status,Certificate No,Certificate Status,Valid Until\r\n");
        for (Views.SearchRow r : search(q, admin)) {
            sb.append(Arrays.stream(new Object[]{r.serialNo(), r.instrumentType(), r.businessName(), r.ownerName(),
                            r.ownerEmail(), r.district(), r.latestApplicationStatus(), r.certNo(), r.certStatus(), r.validUntil()})
                    .map(AdminService::csv).collect(Collectors.joining(","))).append("\r\n");
        }
        return sb.toString();
    }

    private static String csv(Object o) {
        if (o == null) {
            return "";
        }
        String s = o.toString();
        // Neutralise spreadsheet formula injection.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @Transactional
    public Views.InstrumentTypeView updateInstrumentType(Long id, Requests.UpdateInstrumentType req, User admin) {
        InstrumentType t = instrumentTypeRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Instrument type not found"));
        String old = "validity=" + t.getValidityMonths() + "m, fee=" + t.getFee() + ", mpe%=" + t.getMpePercent();
        t.setValidityMonths(req.validityMonths());
        t.setFee(req.fee());
        if (t.getErrorModel() == InstrumentType.ErrorModel.PERCENT && req.mpePercent() != null) {
            t.setMpePercent(req.mpePercent());
        }
        auditService.log(admin, "InstrumentType", t.getId(), "RULES_UPDATED", old,
                "validity=" + t.getValidityMonths() + "m, fee=" + t.getFee() + ", mpe%=" + t.getMpePercent());
        return Views.InstrumentTypeView.of(t);
    }
}
