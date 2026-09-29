package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {
    List<FraudFlag> findByOfficer_StateOrderByCreatedAtDesc(String state);

    boolean existsByOfficerAndRuleCodeAndStatus(User officer, String ruleCode, FraudFlag.Status status);

    long countByOfficer_StateAndStatus(String state, FraudFlag.Status status);
}
