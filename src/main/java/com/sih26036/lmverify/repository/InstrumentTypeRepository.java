package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstrumentTypeRepository extends JpaRepository<InstrumentType, Long> {
    List<InstrumentType> findAllByOrderByName();
}
