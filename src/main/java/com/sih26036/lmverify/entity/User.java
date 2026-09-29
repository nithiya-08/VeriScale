package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    public enum Role { OWNER, LMO, GATC, STATE_ADMIN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** District for LMO / GATC. Null for owners and state admins. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Jurisdiction jurisdiction;

    /** State the user belongs to. A STATE_ADMIN sees everything in this state. */
    private String state;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
