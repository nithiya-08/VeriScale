package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A shop, petrol pump or trader premises owned by an instrument user. */
@Entity
@Table(name = "businesses")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Business {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    private String address;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Jurisdiction jurisdiction;

    /** Registered location, used by fraud rule F1 (GPS distance). */
    private Double lat;
    private Double lng;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
