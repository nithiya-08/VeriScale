package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoredDocumentRepository extends JpaRepository<StoredDocument, Long> {
    List<StoredDocument> findByOwnerEntityAndOwnerId(String ownerEntity, Long ownerId);

    List<StoredDocument> findByOwnerEntityAndOwnerIdOrderByUploadedAtDesc(String ownerEntity, Long ownerId);

    List<StoredDocument> findBySha256(String sha256);
}
