package com.sih26036.lmverify.service;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OwnerService {

    /** An instrument with an application in one of these states cannot get another one. */
    private static final List<Application.Status> IN_PROGRESS = List.of(
            Application.Status.SUBMITTED, Application.Status.ASSIGNED, Application.Status.SCHEDULED,
            Application.Status.INSPECTED_PENDING_SYNC, Application.Status.INSPECTED, Application.Status.PASSED);

    private final BusinessRepository businessRepository;
    private final JurisdictionRepository jurisdictionRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentTypeRepository instrumentTypeRepository;
    private final ApplicationRepository applicationRepository;
    private final AssignmentRepository assignmentRepository;
    private final CertificateRepository certificateRepository;
    private final NotificationRepository notificationRepository;
    private final AllocationService allocationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Views.BusinessView> businesses(User owner) {
        return businessRepository.findByOwnerOrderByName(owner).stream().map(Views.BusinessView::of).toList();
    }

    @Transactional
    public Views.BusinessView createBusiness(User owner, Requests.CreateBusiness req) {
        Jurisdiction j = jurisdictionRepository.findById(req.jurisdictionId())
                .orElseThrow(() -> ApiException.badRequest("Unknown district"));
        Business b = businessRepository.save(Business.builder()
                .owner(owner).name(req.name().trim()).address(req.address()).jurisdiction(j)
                .lat(req.lat()).lng(req.lng()).build());
        auditService.log(owner, "Business", b.getId(), "CREATED", null, b.getName());
        return Views.BusinessView.of(b);
    }

    @Transactional(readOnly = true)
    public List<Views.InstrumentView> instruments(User owner) {
        return instrumentRepository.findByBusiness_OwnerOrderByCreatedAtDesc(owner).stream()
                .map(Views.InstrumentView::of).toList();
    }

    @Transactional
    public Views.InstrumentView createInstrument(User owner, Requests.CreateInstrument req) {
        Business b = businessRepository.findById(req.businessId())
                .filter(x -> x.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> ApiException.badRequest("Business not found"));
        InstrumentType type = instrumentTypeRepository.findById(req.typeId())
                .orElseThrow(() -> ApiException.badRequest("Unknown instrument type"));
        String serial = req.serialNo().trim().toUpperCase();
        if (instrumentRepository.existsBySerialNoIgnoreCase(serial)) {
            throw ApiException.conflict("An instrument with serial number " + serial + " is already registered");
        }
        if (type.getErrorModel() == InstrumentType.ErrorModel.OIML_R76 && (req.eValue() == null || req.eValue() <= 0)) {
            throw ApiException.badRequest("Verification interval (e) is required for weighing instruments");
        }
        if (req.capacityMax() != null && req.capacityMin() != null && req.capacityMin() > req.capacityMax()) {
            throw ApiException.badRequest("Minimum capacity cannot exceed maximum capacity");
        }
        Instrument i = instrumentRepository.save(Instrument.builder()
                .business(b).type(type).make(req.make()).model(req.model()).serialNo(serial)
                .capacityMax(req.capacityMax()).capacityMin(req.capacityMin()).eValue(req.eValue())
                .modelApprovalNo(req.modelApprovalNo())
                .installationType(req.installationType() == null ? Instrument.InstallationType.PORTABLE : req.installationType())
                .build());
        auditService.log(owner, "Instrument", i.getId(), "REGISTERED", null, serial);
        return Views.InstrumentView.of(i);
    }

    /** Submits an application and immediately tries to auto-assign an officer. */
    @Transactional
    public Views.ApplicationView apply(User owner, Long instrumentId) {
        Instrument i = instrumentRepository.findById(instrumentId)
                .filter(x -> x.getBusiness().getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> ApiException.notFound("Instrument not found"));
        if (applicationRepository.existsByInstrumentAndStatusIn(i, IN_PROGRESS)) {
            throw ApiException.conflict("This instrument already has an application in progress");
        }
        boolean hadCertificate = certificateRepository.findFirstByInstrumentOrderByIssuedAtDesc(i).isPresent();
        Application app = applicationRepository.save(Application.builder()
                .instrument(i)
                .type(hadCertificate ? Application.Type.RE_VERIFICATION : Application.Type.NEW)
                .status(Application.Status.SUBMITTED)
                .build());
        auditService.log(owner, "Application", app.getId(), "SUBMITTED", null, app.getType().name());
        Assignment a = allocationService.autoAssign(app, null).orElse(null);
        return Views.ApplicationView.of(app, a, null);
    }

    @Transactional(readOnly = true)
    public List<Views.ApplicationView> applications(User owner) {
        return applicationRepository.findByInstrument_Business_OwnerOrderBySubmittedAtDesc(owner).stream()
                .map(this::view).toList();
    }

    Views.ApplicationView view(Application a) {
        Assignment asg = assignmentRepository.findByApplication(a).orElse(null);
        Certificate cert = certificateRepository.findByApplication(a).orElse(null);
        return Views.ApplicationView.of(a, asg, cert);
    }

    @Transactional(readOnly = true)
    public List<Views.NotificationView> notifications(User user) {
        return notificationRepository.findTop50ByUserOrderBySentAtDesc(user).stream()
                .map(Views.NotificationView::of).toList();
    }

    @Transactional
    public void markNotificationsRead(User user) {
        notificationRepository.findTop50ByUserOrderBySentAtDesc(user).forEach(n -> n.setRead(true));
    }
}
