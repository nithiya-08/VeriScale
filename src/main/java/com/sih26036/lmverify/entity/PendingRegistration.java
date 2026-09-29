package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An owner sign-up waiting for email verification. The user account is created only after
 * the emailed code is confirmed; until then nothing can log in with these details.
 */
@Entity
@Table(name = "pending_registrations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PendingRegistration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Column(nullable = false)
    private String passwordHash;

    /** BCrypt hash of the 6-digit code; the code itself is never stored. */
    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Wrong codes entered for the current code. */
    private int attempts;

    /** When the current code was sent; used for the resend cooldown. */
    @Column(nullable = false)
    private Instant sentAt;
}
