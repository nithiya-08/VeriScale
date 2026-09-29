package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.Application;
import com.sih26036.lmverify.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** The only place application status changes, so every transition is audit-logged. */
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final AuditService auditService;

    public void transition(Application app, Application.Status to, User actor) {
        Application.Status from = app.getStatus();
        if (from == to) {
            return;
        }
        app.setStatus(to);
        app.setUpdatedAt(Instant.now());
        auditService.log(actor, "Application", app.getId(), "STATUS_CHANGE",
                from == null ? null : from.name(), to.name());
    }
}
