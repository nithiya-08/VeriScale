package com.sih26036.lmverify.service;

import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.AssignmentRepository;
import com.sih26036.lmverify.repository.InspectionRepository;
import com.sih26036.lmverify.repository.InstrumentRepository;
import com.sih26036.lmverify.repository.StoredDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Owner documents attached to an instrument (model approval certificate, invoice, repair slip...)
 * and inspection photos. Every download is checked against who is asking.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    public static final String INSTRUMENT = "INSTRUMENT";
    public static final String INSPECTION = "INSPECTION";
    private static final int MAX_DOCS_PER_INSTRUMENT = 10;

    private final StoredDocumentRepository documentRepository;
    private final InstrumentRepository instrumentRepository;
    private final InspectionRepository inspectionRepository;
    private final AssignmentRepository assignmentRepository;
    private final DocumentStorageService storage;
    private final AuditService auditService;

    @Transactional
    public Views.DocumentView upload(User owner, Long instrumentId, MultipartFile file, String label) {
        Instrument i = instrumentRepository.findById(instrumentId)
                .filter(x -> x.getBusiness().getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> ApiException.notFound("Instrument not found"));
        if (documentRepository.findByOwnerEntityAndOwnerId(INSTRUMENT, i.getId()).size() >= MAX_DOCS_PER_INSTRUMENT) {
            throw ApiException.badRequest("At most " + MAX_DOCS_PER_INSTRUMENT + " documents per instrument");
        }
        StoredDocument d = storage.saveUpload(INSTRUMENT, i.getId(), file, label);
        auditService.log(owner, "Instrument", i.getId(), "DOCUMENT_UPLOADED", null, d.getLabel() + " (" + d.getFileName() + ")");
        return Views.DocumentView.of(d);
    }

    @Transactional(readOnly = true)
    public List<Views.DocumentView> list(User user, Long instrumentId) {
        Instrument i = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> ApiException.notFound("Instrument not found"));
        requireInstrumentAccess(user, i);
        return documentRepository.findByOwnerEntityAndOwnerIdOrderByUploadedAtDesc(INSTRUMENT, i.getId()).stream()
                .map(Views.DocumentView::of).toList();
    }

    /** Returns the document and its bytes if the user may see it. */
    @Transactional(readOnly = true)
    public Download download(Long documentId, User user) {
        StoredDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document not found"));
        switch (doc.getOwnerEntity()) {
            case INSTRUMENT -> requireInstrumentAccess(user,
                    instrumentRepository.findById(doc.getOwnerId()).orElseThrow(() -> ApiException.notFound("Document not found")));
            case INSPECTION -> requireInspectionAccess(user,
                    inspectionRepository.findById(doc.getOwnerId()).orElseThrow(() -> ApiException.notFound("Document not found")));
            default -> throw ApiException.notFound("Document not found");
        }
        return new Download(doc.getFileName(), doc.getContentType() == null ? "image/jpeg" : doc.getContentType(),
                storage.read(doc));
    }

    @Transactional
    public void delete(Long documentId, User owner) {
        StoredDocument doc = documentRepository.findById(documentId)
                .filter(d -> INSTRUMENT.equals(d.getOwnerEntity()))
                .orElseThrow(() -> ApiException.notFound("Document not found"));
        Instrument i = instrumentRepository.findById(doc.getOwnerId()).orElseThrow();
        if (!i.getBusiness().getOwner().getId().equals(owner.getId())) {
            throw ApiException.forbidden("You cannot delete this document");
        }
        // The record is kept out of the list but the audit log keeps the trail.
        documentRepository.delete(doc);
        auditService.log(owner, "Instrument", i.getId(), "DOCUMENT_DELETED", doc.getLabel() + " (" + doc.getFileName() + ")", null);
    }

    /** Owner of the instrument, the state admin, or an officer who has been assigned this instrument. */
    private void requireInstrumentAccess(User user, Instrument i) {
        boolean allowed = switch (user.getRole()) {
            case OWNER -> i.getBusiness().getOwner().getId().equals(user.getId());
            case STATE_ADMIN -> i.getBusiness().getJurisdiction().getState().equals(user.getState());
            case LMO, GATC -> assignmentRepository.existsByOfficerAndApplication_Instrument(user, i);
        };
        if (!allowed) {
            throw ApiException.forbidden("You cannot view documents of this instrument");
        }
    }

    /** The inspecting officer or the state admin. */
    private void requireInspectionAccess(User user, Inspection insp) {
        boolean allowed = insp.getOfficer().getId().equals(user.getId())
                || (user.getRole() == User.Role.STATE_ADMIN && user.getState().equals(
                insp.getAssignment().getApplication().getInstrument().getBusiness().getJurisdiction().getState()));
        if (!allowed) {
            throw ApiException.forbidden("You cannot view this photo");
        }
    }

    public record Download(String fileName, String contentType, byte[] bytes) {
    }
}
