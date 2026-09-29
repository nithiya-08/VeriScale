package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JurisdictionRepository extends JpaRepository<Jurisdiction, Long> {
    List<Jurisdiction> findByStateOrderByDistrict(String state);

    List<Jurisdiction> findAllByOrderByStateAscDistrictAsc();

    Optional<Jurisdiction> findByStateAndDistrict(String state, String district);
}
