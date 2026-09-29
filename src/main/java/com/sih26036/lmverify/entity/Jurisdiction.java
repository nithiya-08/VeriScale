package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

/** A district within a state. LMOs, GATCs and businesses belong to one jurisdiction. */
@Entity
@Table(name = "jurisdictions", uniqueConstraints = @UniqueConstraint(columnNames = {"state", "district"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Jurisdiction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String district;
}
