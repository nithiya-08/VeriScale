package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    private String type;

    @Column(length = 1000)
    private String message;

    /** De-duplication key, e.g. "EXPIRY_15:CERT-123", so a reminder is sent only once. */
    @Column(unique = true)
    private String refKey;

    @Builder.Default
    private Instant sentAt = Instant.now();

    @Column(name = "is_read")
    private boolean read;

    private boolean emailed;
}
