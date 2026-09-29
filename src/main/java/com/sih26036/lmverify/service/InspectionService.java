package com.sih26036.lmverify.service;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InspectionService {

    private static final int MAX_OBSERVATIONS = 50;
    private static final int MAX_PHOTOS = 6;

    private final AssignmentRepository assignmentRepository;
    private final InspectionRepository inspectionRepository;
    private final CertificateRepository certificateRepository;
    private final StoredDocumentRepository documentRepository;
    private final DocumentStorageService documentStorageService;
    private final CertificateService certificateService;
    private final WorkflowService workflowService;
    private final FraudService fraudService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /**
     * Open assignments for the officer, downloaded to the phone for offline work. Each one is
     * marked lockedForOffline so an admin cannot reassign it while the officer holds it.
     */
    @Transactional
    public List<Views.OfficerAssignmentView> downloadAssignments(User officer) {
        List<Assignment> list = assignmentRepository.findByOfficerAndApplication_StatusInOrderByScheduledDate(
                officer, List.of(Application.Status.ASSIGNED, Application.Status.SCHEDULED));
        for (Assignment a : list) {
            if (!a.isLockedForOffline()) {
                a.setLockedForOffline(true);
                auditService.log(officer, "Assignment", a.getId(), "LOCKED_FOR_OFFLINE", null, null);
            }
        }
        return list.stream().map(a -> Views.OfficerAssignmentView.of(a,
                documentRepository.findByOwnerEntityAndOwnerIdOrderByUploadedAtDesc(
                                DocumentService.INSTRUMENT, a.getApplication().getInstrument().getId()).stream()
                        .map(Views.DocumentView::of).toList())).toList();
    }

    /**
     * Uploads one inspection recorded on the phone. Idempotent on clientUuid: re-sending the same
     * inspection (e.g. after a dropped connection) returns the original result instead of a duplicate.
     * Pass/fail is recomputed here; the phone's verdict is never trusted.
     */
    @Transactional
    public Views.SyncResult sync(User officer, Requests.SyncInspection req) {
        var existing = inspectionRepository.findByClientUuid(req.clientUuid());
        if (existing.isPresent()) {
            Inspection i = existing.get();
            if (!i.getOfficer().getId().equals(officer.getId())) {
                throw ApiException.forbidden("This inspection belongs to another officer");
            }
            String certNo = certificateRepository.findByApplication(i.getAssignment().getApplication())
                    .map(Certificate::getCertNo).orElse(null);
            return new Views.SyncResult(req.clientUuid(), "DUPLICATE", i.getId(), i.getResult().name(), certNo,
                    "Already synced earlier");
        }

        Assignment a = assignmentRepository.findById(req.assignmentId())
                .orElseThrow(() -> ApiException.notFound("Assignment not found"));
        if (!a.getOfficer().getId().equals(officer.getId())) {
            throw ApiException.forbidden("This assignment is not yours");
        }
        Application app = a.getApplication();
        if (app.getStatus() != Application.Status.SCHEDULED && app.getStatus() != Application.Status.ASSIGNED) {
            throw ApiException.conflict("Application is " + app.getStatus() + " and cannot take an inspection");
        }
        if (req.completedAt().isBefore(req.startedAt())) {
            throw ApiException.badRequest("Completion time is before start time");
        }
        if (req.completedAt().isAfter(Instant.now().plus(Duration.ofMinutes(10)))) {
            throw ApiException.badRequest("Completion time is in the future; check the phone clock");
        }
        if (req.observations().size() > MAX_OBSERVATIONS) {
            throw ApiException.badRequest("Too many readings");
        }
        List<Requests.Photo> photos = req.photos() == null ? List.of() : req.photos();
        if (photos.size() > MAX_PHOTOS) {
            throw ApiException.badRequest("At most " + MAX_PHOTOS + " photos per inspection");
        }

        Instrument instrument = app.getInstrument();
        Inspection insp = Inspection.builder()
                .clientUuid(req.clientUuid())
                .assignment(a)
                .officer(officer)
                .startedAt(req.startedAt())
                .completedAt(req.completedAt())
                .gpsLat(req.gpsLat())
                .gpsLng(req.gpsLng())
                .remarks(req.remarks())
                .syncedAt(Instant.now())
                .build();
        boolean allWithin = true;
        for (Requests.Reading r : req.observations()) {
            double permissible = ErrorCalculator.permissibleError(instrument, r.testLoad());
            double error = ErrorCalculator.error(r.testLoad(), r.indicatedValue());
            boolean ok = ErrorCalculator.withinLimit(error, permissible);
            allWithin &= ok;
            insp.getObservations().add(Observation.builder()
                    .inspection(insp).testLoad(r.testLoad()).indicatedValue(r.indicatedValue())
                    .error(error).permissibleError(permissible).withinLimit(ok).build());
        }
        insp.setResult(allWithin ? Inspection.Result.PASS : Inspection.Result.FAIL);
        insp = inspectionRepository.save(insp);

        List<StoredDocument> saved = new ArrayList<>();
        for (Requests.Photo p : photos) {
            saved.add(documentStorageService.savePhoto("INSPECTION", insp.getId(), p.dataUrl(), p.capturedAt(), p.lat(), p.lng()));
        }

        auditService.log(officer, "Inspection", insp.getId(), "SYNCED", null,
                insp.getResult() + ", " + insp.getObservations().size() + " readings, " + saved.size() + " photos");
        workflowService.transition(app, Application.Status.INSPECTED, officer);
        fraudService.evaluate(insp, saved);

        String certNo = null;
        if (insp.getResult() == Inspection.Result.PASS) {
            workflowService.transition(app, Application.Status.PASSED, officer);
            certNo = certificateService.issue(app, insp, officer, Instant.now()).getCertNo();
        } else {
            workflowService.transition(app, Application.Status.FAILED, officer);
            notificationService.notify(instrument.getBusiness().getOwner(), "VERIFICATION_FAILED",
                    instrument.getType().getName() + " (" + instrument.getSerialNo() + ") failed verification: readings were "
                            + "outside the permissible error. Please get it repaired and apply again.",
                    "FAILED:" + insp.getClientUuid());
        }
        return new Views.SyncResult(req.clientUuid(), "SYNCED", insp.getId(), insp.getResult().name(), certNo,
                insp.getResult() == Inspection.Result.PASS ? "Passed. Certificate issued." : "Failed. Owner notified.");
    }

    @Transactional(readOnly = true)
    public List<Views.InspectionView> history(User officer) {
        return inspectionRepository.findByOfficerOrderByCompletedAtDesc(officer).stream().map(this::view).toList();
    }

    Views.InspectionView view(Inspection i) {
        Application app = i.getAssignment().getApplication();
        List<Long> photoIds = documentRepository.findByOwnerEntityAndOwnerId(DocumentService.INSPECTION, i.getId()).stream()
                .map(StoredDocument::getId).toList();
        String certNo = certificateRepository.findByApplication(app).map(Certificate::getCertNo).orElse(null);
        return new Views.InspectionView(i.getId(), i.getClientUuid(), app.getId(), app.getInstrument().getSerialNo(),
                app.getInstrument().getBusiness().getName(), i.getStartedAt(), i.getCompletedAt(), i.getGpsLat(),
                i.getGpsLng(), i.getResult().name(), i.getRemarks(), i.getSyncedAt(),
                i.getObservations().stream().map(Views.ObservationView::of).toList(), photoIds, certNo);
    }

    @Transactional(readOnly = true)
    public Views.InspectionView forAdmin(Long inspectionId, User admin) {
        Inspection i = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> ApiException.notFound("Inspection not found"));
        if (!admin.getState().equals(i.getAssignment().getApplication().getInstrument().getBusiness().getJurisdiction().getState())) {
            throw ApiException.forbidden("Inspection is outside your state");
        }
        return view(i);
    }
}
