package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Government Approved Test Centre. Under the GATC Amendment Rules 2025 it may verify only its
 * authorised instrument categories, within its district.
 */
@Entity
@Table(name = "gatcs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Gatc {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Jurisdiction jurisdiction;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "gatc_authorised_types")
    @Builder.Default
    private Set<InstrumentType> authorisedTypes = new HashSet<>();
}
