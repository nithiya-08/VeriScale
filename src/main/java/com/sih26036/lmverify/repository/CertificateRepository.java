package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {
    Optional<Certificate> findByCertNo(String certNo);

    Optional<Certificate> findByApplication(Application application);

    boolean existsByCertNo(String certNo);

    List<Certificate> findByStatus(Certificate.Status status);

    List<Certificate> findByInstrument_Business_OwnerOrderByIssuedAtDesc(User owner);

    List<Certificate> findByInstrumentAndStatusIn(Instrument instrument, Collection<Certificate.Status> statuses);

    Optional<Certificate> findFirstByInstrumentOrderByIssuedAtDesc(Instrument instrument);

    List<Certificate> findByInstrument_Business_Jurisdiction_State(String state);

    List<Certificate> findByCertNoContainingIgnoreCase(String q);
}
