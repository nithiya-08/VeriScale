package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    List<Application> findByInstrument_Business_OwnerOrderBySubmittedAtDesc(User owner);

    List<Application> findByInstrument_Business_Jurisdiction_StateOrderBySubmittedAtDesc(String state);

    List<Application> findByInstrumentOrderBySubmittedAtDesc(Instrument instrument);

    boolean existsByInstrumentAndStatusIn(Instrument instrument, Collection<Application.Status> statuses);

    boolean existsByInstrument(Instrument instrument);

    boolean existsByInstrument_BusinessAndStatusIn(Business business, Collection<Application.Status> statuses);
}
