package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.AuditLog;
import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** @param actor null when the system performed the action. */
    public void log(User actor, String entity, Long entityId, String action, String oldValue, String newValue) {
        auditLogRepository.save(AuditLog.builder()
                .actorUserId(actor == null ? null : actor.getId())
                .actorName(actor == null ? "SYSTEM" : actor.getName() + " (" + actor.getRole() + ")")
                .entity(entity)
                .entityId(entityId)
                .action(action)
                .oldValue(truncate(oldValue))
                .newValue(truncate(newValue))
                .build());
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
