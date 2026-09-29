package com.sih26036.lmverify.dto;

import com.sih26036.lmverify.entity.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response shapes. Entities are never returned directly, so lazy relations and password hashes
 * cannot leak. Mapping must happen inside a transaction.
 */
public final class Views {
    private Views() {
    }

    public record UserView(Long id, String name, String email, String phone, String role,
                           String district, String state) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getRole().name(),
                    u.getJurisdiction() == null ? null : u.getJurisdiction().getDistrict(), u.getState());
        }
    }

    public record LoginResponse(String token, UserView user) {
    }

    /**
     * Owner sign-up step 1 result. demoCode is filled only in the demo profile when email is not
     * configured, so the flow can be shown without a mail server.
     */
    public record RegistrationStarted(String email, long expiresInSeconds, long resendAfterSeconds,
                                      boolean emailSent, String demoCode) {
    }

    public record JurisdictionView(Long id, String state, String district) {
        public static JurisdictionView of(Jurisdiction j) {
            return new JurisdictionView(j.getId(), j.getState(), j.getDistrict());
        }
    }

    public record InstrumentTypeView(Long id, String name, String category, String accuracyClass,
                                     String errorModel, Double mpePercent, String unit,
                                     Integer validityMonths, Integer fee) {
        public static InstrumentTypeView of(InstrumentType t) {
            return new InstrumentTypeView(t.getId(), t.getName(), t.getCategory(), t.getAccuracyClass(),
                    t.getErrorModel().name(), t.getMpePercent(), t.getUnit(), t.getValidityMonths(), t.getFee());
        }
    }

    public record BusinessView(Long id, String name, String address, Long jurisdictionId, String district,
                               String state, Double lat, Double lng) {
        public static BusinessView of(Business b) {
            return new BusinessView(b.getId(), b.getName(), b.getAddress(), b.getJurisdiction().getId(),
                    b.getJurisdiction().getDistrict(), b.getJurisdiction().getState(), b.getLat(), b.getLng());
        }
    }

    public record InstrumentView(Long id, Long businessId, String businessName, String district,
                                 InstrumentTypeView type, String make, String model, String serialNo,
                                 Double capacityMax, Double capacityMin, Double eValue,
                                 String modelApprovalNo, String installationType) {
        public static InstrumentView of(Instrument i) {
            return new InstrumentView(i.getId(), i.getBusiness().getId(), i.getBusiness().getName(),
                    i.getBusiness().getJurisdiction().getDistrict(), InstrumentTypeView.of(i.getType()),
                    i.getMake(), i.getModel(), i.getSerialNo(), i.getCapacityMax(), i.getCapacityMin(),
                    i.getEValue(), i.getModelApprovalNo(),
                    i.getInstallationType() == null ? null : i.getInstallationType().name());
        }
    }

    public record ApplicationView(Long id, String type, String status, Instant submittedAt, Instant updatedAt,
                                  Long instrumentId, String serialNo, String instrumentType, Integer fee,
                                  String businessName, String ownerName, String district,
                                  Long assignmentId, String officerName, String officerRole,
                                  LocalDate scheduledDate, boolean lockedForOffline, String assignedBy,
                                  String certNo) {
        public static ApplicationView of(Application a, Assignment asg, Certificate cert) {
            Instrument i = a.getInstrument();
            Business b = i.getBusiness();
            return new ApplicationView(a.getId(), a.getType().name(), a.getStatus().name(), a.getSubmittedAt(),
                    a.getUpdatedAt(), i.getId(), i.getSerialNo(), i.getType().getName(), i.getType().getFee(),
                    b.getName(), b.getOwner().getName(), b.getJurisdiction().getDistrict(),
                    asg == null ? null : asg.getId(),
                    asg == null ? null : asg.getOfficer().getName(),
                    asg == null ? null : asg.getOfficer().getRole().name(),
                    asg == null ? null : asg.getScheduledDate(),
                    asg != null && asg.isLockedForOffline(),
                    asg == null || asg.getAssignedBy() == null ? null : asg.getAssignedBy().name(),
                    cert == null ? null : cert.getCertNo());
        }
    }

    public record CertificateView(Long id, String certNo, Long instrumentId, String serialNo, String instrumentType,
                                  String businessName, String district, Instant issuedAt, LocalDate validUntil,
                                  String status, String revokedReason, String verifyUrl) {
        public static CertificateView of(Certificate c, String verifyUrl) {
            Instrument i = c.getInstrument();
            return new CertificateView(c.getId(), c.getCertNo(), i.getId(), i.getSerialNo(), i.getType().getName(),
                    i.getBusiness().getName(), i.getBusiness().getJurisdiction().getDistrict(), c.getIssuedAt(),
                    c.getValidUntil(), c.getStatus().name(), c.getRevokedReason(), verifyUrl);
        }
    }

    /** Everything the officer needs to finish an inspection with no network. */
    public record OfficerAssignmentView(Long assignmentId, Long applicationId, String applicationType,
                                        String applicationStatus, LocalDate scheduledDate,
                                        BusinessView business, InstrumentView instrument,
                                        List<DocumentView> documents) {
        public static OfficerAssignmentView of(Assignment a, List<DocumentView> documents) {
            Application app = a.getApplication();
            return new OfficerAssignmentView(a.getId(), app.getId(), app.getType().name(), app.getStatus().name(),
                    a.getScheduledDate(), BusinessView.of(app.getInstrument().getBusiness()),
                    InstrumentView.of(app.getInstrument()), documents);
        }
    }

    public record ObservationView(double testLoad, double indicatedValue, double error,
                                  double permissibleError, boolean withinLimit) {
        public static ObservationView of(Observation o) {
            return new ObservationView(o.getTestLoad(), o.getIndicatedValue(), o.getError(),
                    o.getPermissibleError(), o.isWithinLimit());
        }
    }

    public record InspectionView(Long id, String clientUuid, Long applicationId, String serialNo,
                                 String businessName, Instant startedAt, Instant completedAt,
                                 Double gpsLat, Double gpsLng, String result, String remarks, Instant syncedAt,
                                 List<ObservationView> observations, List<Long> photoIds, String certNo) {
    }

    /** Per-inspection outcome of an offline sync upload. */
    public record SyncResult(String clientUuid, String outcome, Long inspectionId, String result,
                             String certNo, String message) {
    }

    public record DocumentView(Long id, String label, String fileName, String contentType, Long sizeBytes, Instant uploadedAt) {
        public static DocumentView of(StoredDocument d) {
            return new DocumentView(d.getId(), d.getLabel(), d.getFileName(), d.getContentType(), d.getSizeBytes(), d.getUploadedAt());
        }
    }

    public record NotificationView(Long id, String type, String message, Instant sentAt, boolean read) {
        public static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getMessage(), n.getSentAt(), n.isRead());
        }
    }

    public record FraudFlagView(Long id, String ruleCode, String details, String status, Instant createdAt,
                                Long officerId, String officerName, Long inspectionId) {
        public static FraudFlagView of(FraudFlag f) {
            return new FraudFlagView(f.getId(), f.getRuleCode(), f.getDetails(), f.getStatus().name(),
                    f.getCreatedAt(), f.getOfficer().getId(), f.getOfficer().getName(),
                    f.getInspection() == null ? null : f.getInspection().getId());
        }
    }

    public record AuditView(Long id, String actorName, String entity, Long entityId, String action,
                            String oldValue, String newValue, Instant at) {
        public static AuditView of(AuditLog a) {
            return new AuditView(a.getId(), a.getActorName(), a.getEntity(), a.getEntityId(), a.getAction(),
                    a.getOldValue(), a.getNewValue(), a.getAt());
        }
    }

    /**
     * Public QR verification result. Only non-sensitive fields. {@code reason} is a stable code
     * (NOT_FOUND, BAD_RECORD, BAD_QR) so the page can show the message in the viewer's language.
     */
    public record VerifyResult(String verdict, String message, String certNo, String instrumentType,
                               String serialNo, String make, String model, String businessName,
                               String district, String state, Instant issuedAt, LocalDate validUntil,
                               boolean signatureChecked, String reason) {
    }

    public record OfficerWorkload(Long id, String name, String role, String district, long openAssignments,
                                  long inspectionsLast30Days, Double passRatePercent) {
    }

    public record DistrictStats(String district, long instruments, long pending, long validCertificates,
                                long dueSoon, long expired) {
    }

    public record Dashboard(String state, java.util.Map<String, Long> applicationsByStatus,
                            java.util.Map<String, Long> certificatesByStatus, long dueSoon,
                            long openFraudFlags, List<DistrictStats> districts,
                            List<OfficerWorkload> officers) {
    }

    public record SearchRow(Long instrumentId, String serialNo, String instrumentType, String businessName,
                            String ownerName, String ownerEmail, String district, String latestApplicationStatus,
                            Long certificateId, String certNo, String certStatus, LocalDate validUntil) {
    }
}
