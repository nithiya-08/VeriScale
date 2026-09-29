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
    private final StoredDocumentRepository documentRepository;
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

    /** Fix a mistake in the premises. Locked while an officer visit is in progress there. */
    @Transactional
    public Views.BusinessView updateBusiness(User owner, Long id, Requests.CreateBusiness req) {
        Business b = ownBusiness(owner, id);
        if (applicationRepository.existsByInstrument_BusinessAndStatusIn(b, IN_PROGRESS)) {
            throw ApiException.conflict("An inspection is in progress at these premises. You can edit them after it is finished.");
        }
        Jurisdiction j = jurisdictionRepository.findById(req.jurisdictionId())
                .orElseThrow(() -> ApiException.badRequest("Unknown district"));
        String before = b.getName() + " | " + b.getAddress() + " | " + b.getJurisdiction().getDistrict();
        b.setName(req.name().trim());
        b.setAddress(req.address());
        b.setJurisdiction(j);
        b.setLat(req.lat());
        b.setLng(req.lng());
        auditService.log(owner, "Business", b.getId(), "UPDATED", before,
                b.getName() + " | " + b.getAddress() + " | " + j.getDistrict());
        return Views.BusinessView.of(b);
    }

    /** Only empty premises can be deleted, so no instrument or certificate loses its address. */
    @Transactional
    public void deleteBusiness(User owner, Long id) {
        Business b = ownBusiness(owner, id);
        if (instrumentRepository.existsByBusiness(b)) {
            throw ApiException.conflict("Remove the instruments at these premises first");
        }
        businessRepository.delete(b);
        auditService.log(owner, "Business", b.getId(), "DELETED", b.getName(), null);
    }

    @Transactional(readOnly = true)
    public List<Views.InstrumentView> instruments(User owner) {
        return instrumentRepository.findByBusiness_OwnerOrderByCreatedAtDesc(owner).stream()
                .map(Views.InstrumentView::of).toList();
    }

    @Transactional
    public Views.InstrumentView createInstrument(User owner, Requests.CreateInstrument req) {
        Business b = ownBusiness(owner, req.businessId());
        InstrumentType type = instrumentTypeRepository.findById(req.typeId())
                .orElseThrow(() -> ApiException.badRequest("Unknown instrument type"));
        String serial = req.serialNo().trim().toUpperCase();
        if (instrumentRepository.existsBySerialNoIgnoreCase(serial)) {
            throw ApiException.conflict("An instrument with serial number " + serial + " is already registered");
        }
        validateInstrument(type, req);
        Instrument i = instrumentRepository.save(Instrument.builder()
                .business(b).type(type).make(req.make()).model(req.model()).serialNo(serial)
                .capacityMax(req.capacityMax()).capacityMin(req.capacityMin()).eValue(req.eValue())
                .modelApprovalNo(req.modelApprovalNo())
                .installationType(req.installationType() == null ? Instrument.InstallationType.PORTABLE : req.installationType())
                .build());
        auditService.log(owner, "Instrument", i.getId(), "REGISTERED", null, serial);
        return Views.InstrumentView.of(i);
    }

    /**
     * Fix a mistake in the instrument details. Once a certificate is issued the details are part of a
     * signed record, so they are locked; while an application is open the officer relies on them.
     */
    @Transactional
    public Views.InstrumentView updateInstrument(User owner, Long id, Requests.CreateInstrument req) {
        Instrument i = ownInstrument(owner, id);
        if (applicationRepository.existsByInstrumentAndStatusIn(i, IN_PROGRESS)) {
            throw ApiException.conflict("This instrument has an application in progress, so it cannot be changed now");
        }
        if (certificateRepository.findFirstByInstrumentOrderByIssuedAtDesc(i).isPresent()) {
            throw ApiException.conflict("This instrument already has a certificate, so its details are locked. Contact the Legal Metrology office to correct them.");
        }
        Business b = ownBusiness(owner, req.businessId());
        InstrumentType type = instrumentTypeRepository.findById(req.typeId())
                .orElseThrow(() -> ApiException.badRequest("Unknown instrument type"));
        String serial = req.serialNo().trim().toUpperCase();
        if (!serial.equalsIgnoreCase(i.getSerialNo()) && instrumentRepository.existsBySerialNoIgnoreCase(serial)) {
            throw ApiException.conflict("An instrument with serial number " + serial + " is already registered");
        }
        validateInstrument(type, req);
        String before = i.getSerialNo() + " | " + i.getType().getName();
        i.setBusiness(b);
        i.setType(type);
        i.setSerialNo(serial);
        i.setMake(req.make());
        i.setModel(req.model());
        i.setCapacityMax(req.capacityMax());
        i.setCapacityMin(req.capacityMin());
        i.setEValue(type.getErrorModel() == InstrumentType.ErrorModel.OIML_R76 ? req.eValue() : null);
        i.setModelApprovalNo(req.modelApprovalNo());
        i.setInstallationType(req.installationType() == null ? Instrument.InstallationType.PORTABLE : req.installationType());
        auditService.log(owner, "Instrument", i.getId(), "UPDATED", before, serial + " | " + type.getName());
        return Views.InstrumentView.of(i);
    }

    /** Only instruments never sent for verification can be deleted; the rest are records. */
    @Transactional
    public void deleteInstrument(User owner, Long id) {
        Instrument i = ownInstrument(owner, id);
        if (applicationRepository.existsByInstrument(i)) {
            throw ApiException.conflict("This instrument has verification records, so it cannot be deleted");
        }
        documentRepository.deleteAll(documentRepository.findByOwnerEntityAndOwnerId(DocumentService.INSTRUMENT, i.getId()));
        instrumentRepository.delete(i);
        auditService.log(owner, "Instrument", i.getId(), "DELETED", i.getSerialNo(), null);
    }

    private static void validateInstrument(InstrumentType type, Requests.CreateInstrument req) {
        if (type.getErrorModel() == InstrumentType.ErrorModel.OIML_R76 && (req.eValue() == null || req.eValue() <= 0)) {
            throw ApiException.badRequest("Verification interval (e) is required for weighing instruments");
        }
        if (req.capacityMax() != null && req.capacityMin() != null && req.capacityMin() > req.capacityMax()) {
            throw ApiException.badRequest("Minimum capacity cannot exceed maximum capacity");
        }
    }

    private Business ownBusiness(User owner, Long id) {
        return businessRepository.findById(id)
                .filter(x -> x.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> ApiException.notFound("Business not found"));
    }

    private Instrument ownInstrument(User owner, Long id) {
        return instrumentRepository.findById(id)
                .filter(x -> x.getBusiness().getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> ApiException.notFound("Instrument not found"));
    }

    /** Submits an application and immediately tries to auto-assign an officer. */
    @Transactional
    public Views.ApplicationView apply(User owner, Long instrumentId) {
        Instrument i = ownInstrument(owner, instrumentId);
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
