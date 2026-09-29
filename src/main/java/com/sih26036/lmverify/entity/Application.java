package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A request to verify (NEW) or re-verify an instrument. */
@Entity
@Table(name = "applications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Application {

    public enum Type { NEW, RE_VERIFICATION }

    public enum Status {
        SUBMITTED, ASSIGNED, SCHEDULED, INSPECTED_PENDING_SYNC, INSPECTED,
        PASSED, FAILED, CERTIFIED, DUE_SOON, EXPIRED, REVOKED
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Builder.Default
    private Instant submittedAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
