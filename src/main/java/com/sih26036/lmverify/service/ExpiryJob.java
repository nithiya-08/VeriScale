package com.sih26036.lmverify.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Triggers for the expiry job. Kept separate so calls go through the transactional proxy. */
@Component
@RequiredArgsConstructor
public class ExpiryJob {

    private final ExpiryService expiryService;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
    public void daily() {
        expiryService.run(null);
    }

    /** Also run once on startup so statuses are correct immediately (e.g. after the laptop was off). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        expiryService.run(null);
    }
}
