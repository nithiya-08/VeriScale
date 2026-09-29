package com.sih26036.lmverify.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.CertificateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CertificateService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final String CERT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertificateRepository certificateRepository;
    private final SigningService signingService;
    private final WorkflowService workflowService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    /**
     * Issues a signed certificate for a passed inspection. Any earlier valid certificate for the
     * same instrument is superseded. Never called offline: the signing key lives only on the server.
     */
    @Transactional
    public Certificate issue(Application app, Inspection inspection, User actor, Instant issuedAt) {
        Instrument instrument = app.getInstrument();
        for (Certificate old : certificateRepository.findByInstrumentAndStatusIn(instrument, List.of(Certificate.Status.VALID))) {
            old.setStatus(Certificate.Status.EXPIRED);
            old.setRevokedReason("Superseded by re-verification");
            auditService.log(actor, "Certificate", old.getId(), "SUPERSEDED", "VALID", "EXPIRED");
        }

        LocalDate validUntil = issuedAt.atZone(ZONE).toLocalDate()
                .plusMonths(instrument.getType().getValidityMonths()).minusDays(1);
        String certNo = newCertNo(issuedAt);
        String issuer = inspection.getOfficer().getRole() == User.Role.GATC
                ? "GATC: " + inspection.getOfficer().getName()
                : "Legal Metrology Officer: " + inspection.getOfficer().getName();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("certNo", certNo);
        payload.put("instrumentSerial", instrument.getSerialNo());
        payload.put("instrumentType", instrument.getType().getName());
        payload.put("businessName", instrument.getBusiness().getName());
        payload.put("issuedAt", issuedAt.toString());
        payload.put("validUntil", validUntil.toString());
        payload.put("issuer", issuer);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }

        Certificate cert = certificateRepository.save(Certificate.builder()
                .certNo(certNo)
                .instrument(instrument)
                .inspection(inspection)
                .application(app)
                .issuedAt(issuedAt)
                .validUntil(validUntil)
                .status(Certificate.Status.VALID)
                .payloadJson(payloadJson)
                .signature(signingService.sign(payloadJson))
                .build());
        auditService.log(actor, "Certificate", cert.getId(), "ISSUED", null, certNo);
        workflowService.transition(app, Application.Status.CERTIFIED, actor);
        notificationService.notify(instrument.getBusiness().getOwner(), "CERTIFICATE_ISSUED",
                "Certificate " + certNo + " issued for " + instrument.getType().getName() + " (" + instrument.getSerialNo()
                        + "). Valid until " + validUntil + ".", "ISSUED:" + certNo);
        return cert;
    }

    private String newCertNo(Instant issuedAt) {
        String certNo;
        do {
            StringBuilder sb = new StringBuilder("LM-").append(issuedAt.atZone(ZONE).getYear()).append('-');
            for (int i = 0; i < 8; i++) {
                sb.append(CERT_ALPHABET.charAt(RANDOM.nextInt(CERT_ALPHABET.length())));
            }
            certNo = sb.toString();
        } while (certificateRepository.existsByCertNo(certNo));
        return certNo;
    }

    /** URL encoded in the QR code: public verify page + certificate number + signature. */
    public String verifyUrl(Certificate c) {
        return publicBaseUrl + "/verify.html?c=" + URLEncoder.encode(c.getCertNo(), StandardCharsets.UTF_8)
                + "&s=" + c.getSignature();
    }

    /**
     * Public verification. FAKE when the certificate does not exist or the signature does not match;
     * otherwise the live status from the database decides VALID / EXPIRED / REVOKED.
     */
    @Transactional(readOnly = true)
    public Views.VerifyResult publicVerify(String certNo, String signature) {
        String c = certNo == null ? "" : certNo.trim().toUpperCase();
        Certificate cert = certificateRepository.findByCertNo(c).orElse(null);
        if (cert == null) {
            return fake(c, "NOT_FOUND", "No certificate with this number exists in the Legal Metrology records.");
        }
        // The stored payload must still match its signature (detects tampering in the database).
        if (!signingService.verify(cert.getPayloadJson(), cert.getSignature())) {
            return fake(c, "BAD_RECORD", "Certificate record failed its digital signature check.");
        }
        boolean sigChecked = signature != null && !signature.isBlank();
        if (sigChecked && !(signature.trim().equals(cert.getSignature())
                && signingService.verify(cert.getPayloadJson(), signature.trim()))) {
            return fake(c, "BAD_QR", "The QR code signature does not match. This QR code is forged or altered.");
        }

        Instrument i = cert.getInstrument();
        String verdict;
        String message;
        LocalDate today = LocalDate.now(ZONE);
        if (cert.getStatus() == Certificate.Status.REVOKED) {
            verdict = "REVOKED";
            message = "This certificate was revoked" + (cert.getRevokedReason() == null ? "." : ": " + cert.getRevokedReason());
        } else if (cert.getStatus() == Certificate.Status.EXPIRED || cert.getValidUntil().isBefore(today)) {
            verdict = "EXPIRED";
            message = "This certificate is genuine but no longer valid. The instrument must be re-verified.";
        } else {
            verdict = "VALID";
            message = "Genuine certificate. This instrument is verified and within its validity period.";
        }
        return new Views.VerifyResult(verdict, message, cert.getCertNo(), i.getType().getName(), i.getSerialNo(),
                i.getMake(), i.getModel(), i.getBusiness().getName(), i.getBusiness().getJurisdiction().getDistrict(),
                i.getBusiness().getJurisdiction().getState(), cert.getIssuedAt(), cert.getValidUntil(), sigChecked, null);
    }

    private static Views.VerifyResult fake(String certNo, String reason, String message) {
        return new Views.VerifyResult("FAKE", message, certNo, null, null, null, null, null, null, null, null, null, false, reason);
    }

    @Transactional
    public void revoke(Long certId, String reason, User actor) {
        Certificate cert = certificateRepository.findById(certId)
                .orElseThrow(() -> ApiException.notFound("Certificate not found"));
        requireSameState(cert, actor);
        if (cert.getStatus() == Certificate.Status.REVOKED) {
            throw ApiException.conflict("Certificate is already revoked");
        }
        String old = cert.getStatus().name();
        cert.setStatus(Certificate.Status.REVOKED);
        cert.setRevokedReason(reason);
        auditService.log(actor, "Certificate", cert.getId(), "REVOKED", old, "REVOKED: " + reason);
        if (cert.getApplication() != null) {
            workflowService.transition(cert.getApplication(), Application.Status.REVOKED, actor);
        }
        notificationService.notify(cert.getInstrument().getBusiness().getOwner(), "CERTIFICATE_REVOKED",
                "Certificate " + cert.getCertNo() + " for " + cert.getInstrument().getSerialNo()
                        + " was revoked. Reason: " + reason + ". Please apply for re-verification.",
                "REVOKED:" + cert.getCertNo());
    }

    @Transactional(readOnly = true)
    public List<Views.CertificateView> mine(User owner) {
        return certificateRepository.findByInstrument_Business_OwnerOrderByIssuedAtDesc(owner).stream()
                .map(c -> Views.CertificateView.of(c, verifyUrl(c))).toList();
    }

    /** Loads a certificate the user is allowed to see (owner, state admin, or the inspecting officer). */
    @Transactional(readOnly = true)
    public Certificate getForUser(String certNo, User user) {
        Certificate cert = certificateRepository.findByCertNo(certNo)
                .orElseThrow(() -> ApiException.notFound("Certificate not found"));
        boolean allowed = switch (user.getRole()) {
            case OWNER -> cert.getInstrument().getBusiness().getOwner().getId().equals(user.getId());
            case STATE_ADMIN -> cert.getInstrument().getBusiness().getJurisdiction().getState().equals(user.getState());
            case LMO, GATC -> cert.getInspection() != null && cert.getInspection().getOfficer().getId().equals(user.getId());
        };
        if (!allowed) {
            throw ApiException.forbidden("You cannot access this certificate");
        }
        return cert;
    }

    private static void requireSameState(Certificate cert, User admin) {
        if (!cert.getInstrument().getBusiness().getJurisdiction().getState().equals(admin.getState())) {
            throw ApiException.forbidden("Certificate is outside your state");
        }
    }
}
