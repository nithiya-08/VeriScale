package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.repository.InstrumentTypeRepository;
import com.sih26036.lmverify.repository.JurisdictionRepository;
import com.sih26036.lmverify.service.CertificateService;
import com.sih26036.lmverify.service.SigningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Endpoints that need no login: QR verification and reference data. */
@RestController
@RequiredArgsConstructor
public class PublicController {

    private final CertificateService certificateService;
    private final SigningService signingService;
    private final JurisdictionRepository jurisdictionRepository;
    private final InstrumentTypeRepository instrumentTypeRepository;

    /** Called by verify.html after a QR scan (c = certificate number, s = signature). */
    @GetMapping("/api/public/verify")
    public Views.VerifyResult verify(@RequestParam("c") String certNo,
                                     @RequestParam(value = "s", required = false) String signature) {
        return certificateService.publicVerify(certNo, signature);
    }

    /** Public key so anyone can check a certificate signature independently. */
    @GetMapping(value = "/api/public/signing-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public String signingKey() {
        return signingService.publicKeyPem();
    }

    @GetMapping("/api/meta/jurisdictions")
    @Transactional(readOnly = true)
    public List<Views.JurisdictionView> jurisdictions() {
        return jurisdictionRepository.findAllByOrderByStateAscDistrictAsc().stream().map(Views.JurisdictionView::of).toList();
    }

    @GetMapping("/api/meta/instrument-types")
    @Transactional(readOnly = true)
    public List<Views.InstrumentTypeView> instrumentTypes() {
        return instrumentTypeRepository.findAllByOrderByName().stream().map(Views.InstrumentTypeView::of).toList();
    }
}
