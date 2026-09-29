package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "assignments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Assignment {

    public enum AssignedBy { AUTO, ADMIN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(unique = true)
    private Application application;

    /** The LMO or GATC user doing the inspection. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User officer;

    private LocalDate scheduledDate;

    /** Set when the officer downloads it for offline use, so nobody else edits it. */
    private boolean lockedForOffline;

    @Enumerated(EnumType.STRING)
    private AssignedBy assignedBy;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
