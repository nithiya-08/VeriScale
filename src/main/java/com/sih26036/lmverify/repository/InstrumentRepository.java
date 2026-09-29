package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    List<Instrument> findByBusiness_OwnerOrderByCreatedAtDesc(User owner);

    Optional<Instrument> findBySerialNoIgnoreCase(String serialNo);

    boolean existsBySerialNoIgnoreCase(String serialNo);

    @Query("""
            select i from Instrument i join i.business b join b.owner o join b.jurisdiction j
            where j.state = :state and (
                lower(i.serialNo) like lower(concat('%', :q, '%'))
                or lower(b.name) like lower(concat('%', :q, '%'))
                or lower(o.name) like lower(concat('%', :q, '%'))
                or lower(o.email) like lower(concat('%', :q, '%'))
                or lower(j.district) like lower(concat('%', :q, '%')))
            order by i.id desc
            """)
    List<Instrument> search(@Param("state") String state, @Param("q") String q);
}
