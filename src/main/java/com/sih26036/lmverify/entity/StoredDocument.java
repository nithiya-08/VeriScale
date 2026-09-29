package com.sih26036.lmverify.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A photo or document file stored on disk, attached to an inspection or application. */
@Entity
@Table(name = "documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoredDocument {

    public enum Type { PHOTO, DOC }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. "INSPECTION" or "APPLICATION". */
    @Column(nullable = false)
    private String ownerEntity;

    @Column(nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    private Type type;

    @Column(nullable = false)
    private String path;

    /** SHA-256 of the file bytes; fraud rule F6 detects the same photo reused. */
    private String sha256;

    /** Original file name as uploaded (display only; never used as a path). */
    private String fileName;

    private String contentType;

    /** What the document is, e.g. "Model approval certificate". */
    private String label;

    private Long sizeBytes;

    @Builder.Default
    private Instant uploadedAt = Instant.now();

    private Instant capturedAt;
    private Double gpsLat;
    private Double gpsLng;
}
