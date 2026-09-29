package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "certificates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Certificate {

    public enum Status { VALID, EXPIRED, REVOKED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String certNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Instrument instrument;

    @OneToOne(fetch = FetchType.LAZY)
    private Inspection inspection;

    @OneToOne(fetch = FetchType.LAZY)
    private Application application;

    private Instant issuedAt;
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    private Status status;

    /** Exact JSON string that was signed. Verification re-checks the signature over this. */
    @Column(length = 2000, nullable = false)
    private String payloadJson;

    /** Base64url ECDSA P-256 signature of payloadJson. */
    @Column(length = 200, nullable = false)
    private String signature;

    private String revokedReason;
}
