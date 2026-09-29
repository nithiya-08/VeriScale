package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {
    Optional<Inspection> findByClientUuid(String clientUuid);

    List<Inspection> findByOfficerOrderByCompletedAtDesc(User officer);

    long countByOfficerAndCompletedAtBetween(User officer, Instant from, Instant to);

    List<Inspection> findByCompletedAtAfter(Instant from);

    Optional<Inspection> findFirstByAssignment_ApplicationOrderByIdDesc(Application application);
}
