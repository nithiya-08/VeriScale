package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Instrument category with its verification rules. Validity, fee and error limits are stored
 * here (admin-configurable) and never hardcoded, because they change with amendments.
 */
@Entity
@Table(name = "instrument_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstrumentType {

    /**
     * OIML_R76: non-automatic weighing instrument; permissible error is 0.5e / 1e / 1.5e
     *           depending on load and accuracy class.
     * PERCENT:  permissible error is a fixed percentage of the test quantity.
     */
    public enum ErrorModel { OIML_R76, PERCENT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String category;

    /** For OIML_R76: I, II, III or IIII. */
    private String accuracyClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ErrorModel errorModel;

    /** For PERCENT model: maximum permissible error in percent. */
    private Double mpePercent;

    /** Measurement unit shown to the officer, e.g. kg, g, L. */
    private String unit;

    @Column(nullable = false)
    private Integer validityMonths;

    @Column(nullable = false)
    private Integer fee;
}
