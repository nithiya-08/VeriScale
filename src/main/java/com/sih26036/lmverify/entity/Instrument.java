package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "instruments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Instrument {

    public enum InstallationType { FIXED, PORTABLE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private InstrumentType type;

    private String make;
    private String model;

    @Column(nullable = false, unique = true)
    private String serialNo;

    private Double capacityMax;
    private Double capacityMin;

    /** Verification scale interval "e" (same unit as capacity). Used by the OIML_R76 error model. */
    private Double eValue;

    private String modelApprovalNo;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private InstallationType installationType = InstallationType.PORTABLE;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
