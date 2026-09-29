package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.service.CurrentUserService;
import com.sih26036.lmverify.service.InspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/** Field officer (LMO / GATC) endpoints used by the offline PWA. */
@Slf4j
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('LMO','GATC')")
@RequiredArgsConstructor
public class OfficerController {

    private final InspectionService inspectionService;
    private final CurrentUserService currentUser;

    /** Downloads open assignments to the phone and locks them for offline work. */
    @GetMapping("/officer/assignments/today")
    public List<Views.OfficerAssignmentView> assignments() {
        return inspectionService.downloadAssignments(currentUser.get());
    }

    @GetMapping("/officer/inspections")
    public List<Views.InspectionView> history() {
        return inspectionService.history(currentUser.get());
    }

    /**
     * Uploads inspections done offline. Each one is processed in its own transaction, so one bad
     * record does not block the rest; the phone removes only those reported SYNCED or DUPLICATE.
     */
    @PostMapping("/sync/inspections")
    public List<Views.SyncResult> sync(@Valid @RequestBody Requests.SyncBatch batch) {
        User officer = currentUser.get();
        List<Views.SyncResult> results = new ArrayList<>();
        for (Requests.SyncInspection req : batch.inspections()) {
            try {
                results.add(inspectionService.sync(officer, req));
            } catch (ApiException e) {
                results.add(new Views.SyncResult(req.clientUuid(), "REJECTED", null, null, null, e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Sync failed for {}", req.clientUuid(), e);
                results.add(new Views.SyncResult(req.clientUuid(), "ERROR", null, null, null, "Server error, will retry"));
            }
        }
        return results;
    }
}
