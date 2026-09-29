package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "inspections")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Inspection {

    public enum Result { PASS, FAIL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Generated on the phone; makes offline sync idempotent. */
    @Column(nullable = false, unique = true)
    private String clientUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Assignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User officer;

    private Instant startedAt;
    private Instant completedAt;
    private Double gpsLat;
    private Double gpsLng;

    @Enumerated(EnumType.STRING)
    private Result result;

    @Column(length = 1000)
    private String remarks;

    private Instant syncedAt;

    @OneToMany(mappedBy = "inspection", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @Builder.Default
    private List<Observation> observations = new ArrayList<>();
}
