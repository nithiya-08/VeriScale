package com.sih26036.lmverify.repository;

import com.sih26036.lmverify.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findTop50ByUserOrderBySentAtDesc(User user);

    boolean existsByRefKey(String refKey);

    long countByUserAndReadFalse(User user);
}
