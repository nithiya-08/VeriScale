package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A suspicious pattern for the state admin to review. The system never auto-punishes. */
@Entity
@Table(name = "fraud_flags")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FraudFlag {

    public enum Status { OPEN, REVIEWED, DISMISSED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User officer;

    @ManyToOne(fetch = FetchType.LAZY)
    private Inspection inspection;

    @Column(nullable = false)
    private String ruleCode;

    @Column(length = 1000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.OPEN;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
