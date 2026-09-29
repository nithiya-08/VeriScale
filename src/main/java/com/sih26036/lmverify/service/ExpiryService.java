package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.Application;
import com.sih26036.lmverify.entity.Certificate;
import com.sih26036.lmverify.entity.Instrument;
import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.repository.CertificateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Daily job: marks certificates due soon / expired and sends reminders at 30, 15 and 7 days. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiryService {

    private final CertificateRepository certificateRepository;
    private final WorkflowService workflowService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Value("${app.alerts.due-soon-days}")
    private int dueSoonDays;

    @Value("${app.alerts.reminder-days}")
    private String reminderDaysCsv;

    /** @param actor the admin who triggered a manual run, or null for the scheduler. */
    @Transactional
    public Map<String, Integer> run(User actor) {
        LocalDate today = LocalDate.now(CertificateService.ZONE);
        List<Integer> reminderDays = Arrays.stream(reminderDaysCsv.split(","))
                .map(String::trim).map(Integer::parseInt).sorted().toList();
        int expired = 0, dueSoon = 0, reminders = 0;

        for (Certificate c : certificateRepository.findByStatus(Certificate.Status.VALID)) {
            Instrument i = c.getInstrument();
            User owner = i.getBusiness().getOwner();
            Application app = c.getApplication();
            long daysLeft = ChronoUnit.DAYS.between(today, c.getValidUntil());

            if (daysLeft < 0) {
                c.setStatus(Certificate.Status.EXPIRED);
                auditService.log(actor, "Certificate", c.getId(), "EXPIRED", "VALID", "EXPIRED");
                if (app != null && (app.getStatus() == Application.Status.CERTIFIED || app.getStatus() == Application.Status.DUE_SOON)) {
                    workflowService.transition(app, Application.Status.EXPIRED, actor);
                }
                notificationService.notify(owner, "CERTIFICATE_EXPIRED",
                        "Certificate " + c.getCertNo() + " for " + i.getType().getName() + " (" + i.getSerialNo()
                                + ") expired on " + c.getValidUntil() + ". Using it for trade is not permitted until it is re-verified.",
                        "EXPIRED:" + c.getCertNo());
                expired++;
                continue;
            }

            if (daysLeft <= dueSoonDays && app != null && app.getStatus() == Application.Status.CERTIFIED) {
                workflowService.transition(app, Application.Status.DUE_SOON, actor);
                dueSoon++;
            }
            // Send only the most urgent threshold crossed, once.
            for (int d : reminderDays) {
                if (daysLeft <= d) {
                    boolean sent = notificationService.notify(owner, "EXPIRY_REMINDER",
                            "Certificate " + c.getCertNo() + " for " + i.getType().getName() + " (" + i.getSerialNo()
                                    + ") expires on " + c.getValidUntil() + " (" + daysLeft + " days left). Apply for re-verification.",
                            "EXPIRY_" + d + ":" + c.getCertNo());
                    reminders += sent ? 1 : 0;
                    break;
                }
            }
        }
        log.info("Expiry job: {} expired, {} marked due soon, {} reminders sent", expired, dueSoon, reminders);
        return Map.of("expired", expired, "dueSoon", dueSoon, "remindersSent", reminders);
    }
}
