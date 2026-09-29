package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

/** One test reading: the officer applies a known test load and records what the instrument shows. */
@Entity
@Table(name = "observations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Observation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Inspection inspection;

    private double testLoad;
    private double indicatedValue;
    private double error;
    private double permissibleError;
    private boolean withinLimit;
}
