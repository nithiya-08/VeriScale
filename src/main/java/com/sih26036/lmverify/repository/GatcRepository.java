package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GatcRepository extends JpaRepository<Gatc, Long> {
    Optional<Gatc> findByUser(User user);

    List<Gatc> findByJurisdiction(Jurisdiction jurisdiction);
}
