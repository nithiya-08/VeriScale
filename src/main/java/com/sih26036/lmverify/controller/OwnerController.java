package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.service.CertificateService;
import com.sih26036.lmverify.service.CurrentUserService;
import com.sih26036.lmverify.service.DocumentService;
import com.sih26036.lmverify.service.OwnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;
    private final CertificateService certificateService;
    private final DocumentService documentService;
    private final CurrentUserService currentUser;

    @GetMapping("/businesses")
    public List<Views.BusinessView> businesses() {
        return ownerService.businesses(currentUser.get());
    }

    @PostMapping("/businesses")
    public Views.BusinessView createBusiness(@Valid @RequestBody Requests.CreateBusiness req) {
        return ownerService.createBusiness(currentUser.get(), req);
    }

    @PutMapping("/businesses/{id}")
    public Views.BusinessView updateBusiness(@PathVariable Long id, @Valid @RequestBody Requests.CreateBusiness req) {
        return ownerService.updateBusiness(currentUser.get(), id, req);
    }

    /** Only premises with no instruments. */
    @DeleteMapping("/businesses/{id}")
    public void deleteBusiness(@PathVariable Long id) {
        ownerService.deleteBusiness(currentUser.get(), id);
    }

    @GetMapping("/instruments")
    public List<Views.InstrumentView> instruments() {
        return ownerService.instruments(currentUser.get());
    }

    @PostMapping("/instruments")
    public Views.InstrumentView createInstrument(@Valid @RequestBody Requests.CreateInstrument req) {
        return ownerService.createInstrument(currentUser.get(), req);
    }

    /** Locked once a certificate exists or while an application is in progress. */
    @PutMapping("/instruments/{id}")
    public Views.InstrumentView updateInstrument(@PathVariable Long id, @Valid @RequestBody Requests.CreateInstrument req) {
        return ownerService.updateInstrument(currentUser.get(), id, req);
    }

    /** Only instruments never sent for verification. */
    @DeleteMapping("/instruments/{id}")
    public void deleteInstrument(@PathVariable Long id) {
        ownerService.deleteInstrument(currentUser.get(), id);
    }

    @GetMapping("/applications")
    public List<Views.ApplicationView> applications() {
        return ownerService.applications(currentUser.get());
    }

    @PostMapping("/applications")
    public Views.ApplicationView apply(@Valid @RequestBody Requests.CreateApplication req) {
        return ownerService.apply(currentUser.get(), req.instrumentId());
    }

    @GetMapping("/certificates")
    public List<Views.CertificateView> certificates() {
        return certificateService.mine(currentUser.get());
    }

    /** Upload a PDF / JPEG / PNG (max 5 MB), e.g. the model approval certificate. */
    @PostMapping(value = "/instruments/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Views.DocumentView uploadDocument(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "label", required = false) String label) {
        return documentService.upload(currentUser.get(), id, file, label);
    }

    @DeleteMapping("/documents/{id}")
    public void deleteDocument(@PathVariable Long id) {
        documentService.delete(id, currentUser.get());
    }
}
