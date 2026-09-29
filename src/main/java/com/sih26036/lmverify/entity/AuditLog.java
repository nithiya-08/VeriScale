package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Append-only record of every state change: who did what, and when. */
@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when the system acted (scheduler, auto-allocation). */
    private Long actorUserId;
    private String actorName;

    @Column(nullable = false)
    private String entity;
    private Long entityId;

    @Column(nullable = false)
    private String action;

    @Column(length = 1000)
    private String oldValue;
    @Column(length = 1000)
    private String newValue;

    @Builder.Default
    private Instant at = Instant.now();
}
