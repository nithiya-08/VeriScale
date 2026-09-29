package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.repository.FraudFlagRepository;
import com.sih26036.lmverify.repository.InspectionRepository;
import com.sih26036.lmverify.repository.StoredDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Anomaly rules on officer activity (context file section 11). Flags go to the state admin
 * for review; the system never punishes anyone automatically. Thresholds are configurable.
 */
@Service
@RequiredArgsConstructor
public class FraudService {

    private final FraudFlagRepository fraudFlagRepository;
    private final InspectionRepository inspectionRepository;
    private final StoredDocumentRepository documentRepository;
    private final AuditService auditService;

    @Value("${app.fraud.max-gps-distance-m}")
    private double maxGpsDistanceM;
    @Value("${app.fraud.min-duration-seconds}")
    private long minDurationSeconds;
    @Value("${app.fraud.max-inspections-per-day}")
    private long maxInspectionsPerDay;
    @Value("${app.fraud.pass-rate-window-days}")
    private long passRateWindowDays;
    @Value("${app.fraud.pass-rate-min-inspections}")
    private long passRateMinInspections;
    @Value("${app.fraud.pass-rate-excess-points}")
    private double passRateExcessPoints;

    /** Runs all rules for a freshly synced inspection. */
    public void evaluate(Inspection insp, List<StoredDocument> photos) {
        User officer = insp.getOfficer();
        Business b = insp.getAssignment().getApplication().getInstrument().getBusiness();

        // F1: GPS far from the business's registered location.
        if (insp.getGpsLat() != null && insp.getGpsLng() != null && b.getLat() != null && b.getLng() != null) {
            double d = distanceMeters(insp.getGpsLat(), insp.getGpsLng(), b.getLat(), b.getLng());
            if (d > maxGpsDistanceM) {
                flag(officer, insp, "F1", String.format(Locale.ROOT,
                        "Inspection GPS is %.0f m from %s (limit %.0f m)", d, b.getName(), maxGpsDistanceM));
            }
        } else if (insp.getGpsLat() == null) {
            flag(officer, insp, "F1", "Inspection has no GPS location");
        }

        // F3: implausibly short inspection.
        long seconds = Duration.between(insp.getStartedAt(), insp.getCompletedAt()).getSeconds();
        if (seconds < minDurationSeconds) {
            flag(officer, insp, "F3", "Inspection took only " + seconds + " s (minimum expected " + minDurationSeconds + " s)");
        }

        // F4: too many inspections by one officer on one day.
        LocalDate day = insp.getCompletedAt().atZone(CertificateService.ZONE).toLocalDate();
        Instant from = day.atStartOfDay(CertificateService.ZONE).toInstant();
        long count = inspectionRepository.countByOfficerAndCompletedAtBetween(officer, from, from.plus(1, ChronoUnit.DAYS));
        if (count > maxInspectionsPerDay) {
            flag(officer, insp, "F4", count + " inspections on " + day + " (limit " + maxInspectionsPerDay + ")");
        }

        // F5: back-dating - inspection claims to start before it was even assigned.
        if (insp.getStartedAt().isBefore(insp.getAssignment().getCreatedAt())) {
            flag(officer, insp, "F5", "Inspection start " + insp.getStartedAt() + " is before the assignment was created "
                    + insp.getAssignment().getCreatedAt());
        }

        // F6: same photo reused in a different inspection.
        for (StoredDocument p : photos) {
            boolean reused = documentRepository.findBySha256(p.getSha256()).stream()
                    .anyMatch(o -> !o.getId().equals(p.getId())
                            && !("INSPECTION".equals(o.getOwnerEntity()) && o.getOwnerId().equals(insp.getId())));
            if (reused) {
                flag(officer, insp, "F6", "A photo in this inspection is identical to a photo from another inspection");
                break;
            }
        }

        checkPassRate(officer);
    }

    /** F2: officer pass rate well above the state average over the window. One open flag per officer. */
    private void checkPassRate(User officer) {
        if (fraudFlagRepository.existsByOfficerAndRuleCodeAndStatus(officer, "F2", FraudFlag.Status.OPEN)) {
            return;
        }
        Instant since = Instant.now().minus(passRateWindowDays, ChronoUnit.DAYS);
        List<Inspection> recent = inspectionRepository.findByCompletedAtAfter(since).stream()
                .filter(i -> officer.getState() != null && officer.getState().equals(i.getOfficer().getState()))
                .toList();
        List<Inspection> mine = recent.stream().filter(i -> i.getOfficer().getId().equals(officer.getId())).toList();
        if (mine.size() < passRateMinInspections || recent.isEmpty()) {
            return;
        }
        double statePct = 100.0 * recent.stream().filter(i -> i.getResult() == Inspection.Result.PASS).count() / recent.size();
        double minePct = 100.0 * mine.stream().filter(i -> i.getResult() == Inspection.Result.PASS).count() / mine.size();
        if (minePct - statePct > passRateExcessPoints) {
            flag(officer, null, "F2", String.format(Locale.ROOT,
                    "Pass rate %.0f%% over %d inspections vs state average %.0f%% (last %d days)",
                    minePct, mine.size(), statePct, passRateWindowDays));
        }
    }

    private void flag(User officer, Inspection insp, String code, String details) {
        FraudFlag f = fraudFlagRepository.save(FraudFlag.builder()
                .officer(officer).inspection(insp).ruleCode(code).details(details).build());
        auditService.log(null, "FraudFlag", f.getId(), "RAISED_" + code, null, details);
    }

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
