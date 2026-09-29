package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {

    Optional<PendingRegistration> findByEmailIgnoreCase(String email);
}
