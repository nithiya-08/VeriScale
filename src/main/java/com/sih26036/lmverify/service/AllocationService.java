package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.AssignmentRepository;
import com.sih26036.lmverify.repository.GatcRepository;
import com.sih26036.lmverify.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Rule-aware allocation: an LMO must be in the instrument's district; a GATC must be in the same
 * district AND authorised for the instrument category (GATC Amendment Rules 2025). Among eligible
 * officers, the one with the fewest open assignments wins, then the earliest free date.
 */
@Service
@RequiredArgsConstructor
public class AllocationService {

    public static final List<Application.Status> OPEN_STATUSES = List.of(
            Application.Status.ASSIGNED, Application.Status.SCHEDULED, Application.Status.INSPECTED_PENDING_SYNC);

    private final UserRepository userRepository;
    private final GatcRepository gatcRepository;
    private final AssignmentRepository assignmentRepository;
    private final WorkflowService workflowService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Value("${app.allocation.max-inspections-per-day}")
    private int maxPerDay;

    /** @return the new assignment, or empty if no eligible officer exists (stays SUBMITTED for manual action). */
    @Transactional
    public Optional<Assignment> autoAssign(Application app, User actor) {
        if (app.getStatus() != Application.Status.SUBMITTED) {
            throw ApiException.conflict("Only SUBMITTED applications can be auto-assigned (current: " + app.getStatus() + ")");
        }
        List<User> eligible = eligibleOfficers(app.getInstrument());
        if (eligible.isEmpty()) {
            auditService.log(actor, "Application", app.getId(), "AUTO_ASSIGN_FAILED", null,
                    "No eligible LMO/GATC in " + app.getInstrument().getBusiness().getJurisdiction().getDistrict());
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(CertificateService.ZONE);
        User best = eligible.stream()
                .min(Comparator.comparingLong((User u) -> assignmentRepository.countByOfficerAndApplication_StatusIn(u, OPEN_STATUSES))
                        .thenComparing(u -> earliestFreeDate(u, today))
                        .thenComparing(User::getId))
                .orElseThrow();
        Assignment a = assignmentRepository.save(Assignment.builder()
                .application(app)
                .officer(best)
                .scheduledDate(earliestFreeDate(best, today))
                .assignedBy(Assignment.AssignedBy.AUTO)
                .build());
        workflowService.transition(app, Application.Status.ASSIGNED, actor);
        workflowService.transition(app, Application.Status.SCHEDULED, actor);
        auditService.log(actor, "Assignment", a.getId(), "AUTO_ASSIGNED", null,
                best.getName() + " on " + a.getScheduledDate());
        notifyScheduled(a);
        return Optional.of(a);
    }

    /** Admin override. Checked against the same eligibility rules and audit-logged. */
    @Transactional
    public Assignment reassign(Assignment a, User officer, LocalDate date, User admin) {
        if (a.isLockedForOffline()) {
            throw ApiException.conflict("The officer has already downloaded this inspection for offline use; it cannot be reassigned now");
        }
        if (!AllocationService.OPEN_STATUSES.contains(a.getApplication().getStatus())) {
            throw ApiException.conflict("Application is no longer open for scheduling");
        }
        if (eligibleOfficers(a.getApplication().getInstrument()).stream().noneMatch(u -> u.getId().equals(officer.getId()))) {
            throw ApiException.badRequest(officer.getName() + " is not eligible (jurisdiction or GATC category limits)");
        }
        String old = a.getOfficer().getName() + " on " + a.getScheduledDate();
        a.setOfficer(officer);
        a.setScheduledDate(date);
        a.setAssignedBy(Assignment.AssignedBy.ADMIN);
        auditService.log(admin, "Assignment", a.getId(), "ADMIN_OVERRIDE", old, officer.getName() + " on " + date);
        notifyScheduled(a);
        return a;
    }

    /** Admin override for an application that was never assigned (e.g. no eligible officer at the time). */
    @Transactional
    public Assignment manualAssign(Application app, User officer, LocalDate date, User admin) {
        if (app.getStatus() != Application.Status.SUBMITTED) {
            throw ApiException.conflict("Application is not waiting for assignment");
        }
        if (eligibleOfficers(app.getInstrument()).stream().noneMatch(u -> u.getId().equals(officer.getId()))) {
            throw ApiException.badRequest(officer.getName() + " is not eligible (jurisdiction or GATC category limits)");
        }
        Assignment a = assignmentRepository.save(Assignment.builder()
                .application(app).officer(officer).scheduledDate(date)
                .assignedBy(Assignment.AssignedBy.ADMIN).build());
        workflowService.transition(app, Application.Status.ASSIGNED, admin);
        workflowService.transition(app, Application.Status.SCHEDULED, admin);
        auditService.log(admin, "Assignment", a.getId(), "ADMIN_ASSIGNED", null, officer.getName() + " on " + date);
        notifyScheduled(a);
        return a;
    }

    public List<User> eligibleOfficers(Instrument instrument) {
        Jurisdiction j = instrument.getBusiness().getJurisdiction();
        List<User> result = new ArrayList<>(userRepository.findByRoleAndJurisdictionAndActiveTrue(User.Role.LMO, j));
        for (Gatc g : gatcRepository.findByJurisdiction(j)) {
            boolean authorised = g.getAuthorisedTypes().stream().anyMatch(t -> t.getId().equals(instrument.getType().getId()));
            if (authorised && g.getUser().isActive()) {
                result.add(g.getUser());
            }
        }
        return result;
    }

    private LocalDate earliestFreeDate(User officer, LocalDate from) {
        LocalDate d = from;
        while (assignmentRepository.countByOfficerAndScheduledDate(officer, d) >= maxPerDay) {
            d = d.plusDays(1);
        }
        return d;
    }

    private void notifyScheduled(Assignment a) {
        Application app = a.getApplication();
        Instrument i = app.getInstrument();
        notificationService.notify(i.getBusiness().getOwner(), "INSPECTION_SCHEDULED",
                "Inspection of " + i.getType().getName() + " (" + i.getSerialNo() + ") is scheduled on "
                        + a.getScheduledDate() + " with " + a.getOfficer().getName() + ".", null);
        notificationService.notify(a.getOfficer(), "NEW_ASSIGNMENT",
                "New inspection on " + a.getScheduledDate() + ": " + i.getBusiness().getName() + ", "
                        + i.getType().getName() + " (" + i.getSerialNo() + ").", null);
    }
}
