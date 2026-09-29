package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    Optional<Assignment> findByApplication(Application application);

    List<Assignment> findByOfficerAndApplication_StatusInOrderByScheduledDate(
            User officer, Collection<Application.Status> statuses);

    long countByOfficerAndApplication_StatusIn(User officer, Collection<Application.Status> statuses);

    long countByOfficerAndScheduledDate(User officer, LocalDate date);

    boolean existsByOfficerAndApplication_Instrument(User officer, Instrument instrument);
}
