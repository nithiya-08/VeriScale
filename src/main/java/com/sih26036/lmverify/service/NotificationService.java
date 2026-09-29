package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.Notification;
import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * In-app notifications, plus free email via Gmail SMTP when GMAIL_USERNAME is configured.
 * SMS is intentionally left out to keep the project zero-cost; add a gateway here later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    /**
     * @param refKey optional de-duplication key; if a notification with this key exists, nothing is sent.
     * @return true if a new notification was created
     */
    public boolean notify(User user, String type, String message, String refKey) {
        if (refKey != null && notificationRepository.existsByRefKey(refKey)) {
            return false;
        }
        Notification n = Notification.builder()
                .user(user).type(type).message(message).refKey(refKey)
                .build();
        n.setEmailed(sendEmail(user, type, message));
        notificationRepository.save(n);
        return true;
    }

    private boolean sendEmail(User user, String type, String message) {
        if (mailFrom == null || mailFrom.isBlank() || user.getEmail() == null) {
            return false;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(mailFrom);
            mail.setTo(user.getEmail());
            mail.setSubject("Legal Metrology: " + type.replace('_', ' '));
            mail.setText("Dear " + user.getName() + ",\n\n" + message + "\n\n- Legal Metrology Verification System");
            mailSender.send(mail);
            return true;
        } catch (Exception e) {
            // Email is best-effort; the in-app notification is still stored.
            log.warn("Email to {} failed: {}", user.getEmail(), e.getMessage());
            return false;
        }
    }
}
