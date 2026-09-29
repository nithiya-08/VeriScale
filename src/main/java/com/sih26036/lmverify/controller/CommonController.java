package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.Certificate;
import com.sih26036.lmverify.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Endpoints shared by all logged-in roles; access is checked per record in the services. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommonController {

    private final CertificateService certificateService;
    private final CertificatePdfService pdfService;
    private final QrService qrService;
    private final OwnerService ownerService;
    private final DocumentService documentService;
    private final CurrentUserService currentUser;

    @GetMapping("/certificates/{certNo}")
    @Transactional(readOnly = true)
    public Views.CertificateView certificate(@PathVariable String certNo) {
        Certificate c = certificateService.getForUser(certNo, currentUser.get());
        return Views.CertificateView.of(c, certificateService.verifyUrl(c));
    }

    @GetMapping("/certificates/{certNo}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String certNo) {
        byte[] pdf = pdfService.render(certNo, currentUser.get());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + certNo + ".pdf\"")
                .body(pdf);
    }

    @GetMapping(value = "/certificates/{certNo}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @Transactional(readOnly = true)
    public byte[] qr(@PathVariable String certNo) {
        Certificate c = certificateService.getForUser(certNo, currentUser.get());
        return qrService.png(certificateService.verifyUrl(c), 360);
    }

    /** Inspection photo or instrument document; access is checked per document. */
    @GetMapping("/documents/{id}")
    public ResponseEntity<byte[]> document(@PathVariable Long id) {
        DocumentService.Download d = documentService.download(id, currentUser.get());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(d.bytes());
    }

    @GetMapping("/instruments/{id}/documents")
    public List<Views.DocumentView> instrumentDocuments(@PathVariable Long id) {
        return documentService.list(currentUser.get(), id);
    }

    @GetMapping("/notifications")
    public List<Views.NotificationView> notifications() {
        return ownerService.notifications(currentUser.get());
    }

    @PostMapping("/notifications/read")
    public void markRead() {
        ownerService.markNotificationsRead(currentUser.get());
    }
}
