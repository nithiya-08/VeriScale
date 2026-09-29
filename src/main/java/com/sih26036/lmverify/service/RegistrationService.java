package com.sih26036.lmverify.service;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.PendingRegistration;
import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.PendingRegistrationRepository;
import com.sih26036.lmverify.repository.UserRepository;
import com.sih26036.lmverify.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Owner self-registration with email verification:
 * start (send a 6-digit code) -> verify (code correct -> account created and logged in).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PendingRegistrationRepository pendingRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailFrom;
    @Value("${app.otp.expiry-minutes}")
    private long expiryMinutes;
    @Value("${app.otp.max-attempts}")
    private int maxAttempts;
    @Value("${app.otp.resend-cooldown-seconds}")
    private long resendCooldownSeconds;
    /** Demo only: returns the code in the response when email is not configured. Never enable in production. */
    @Value("${app.otp.expose-code:false}")
    private boolean exposeCode;

    @Transactional
    public Views.RegistrationStarted start(Requests.Register req) {
        String email = req.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        PendingRegistration p = pendingRepository.findByEmailIgnoreCase(email).orElseGet(PendingRegistration::new);
        if (p.getId() != null && p.getSentAt().plusSeconds(resendCooldownSeconds).isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another code");
        }
        p.setEmail(email);
        p.setName(req.name().trim());
        p.setPhone(req.phone());
        p.setPasswordHash(passwordEncoder.encode(req.password()));
        return issueCode(p);
    }

    @Transactional
    public Views.RegistrationStarted resend(String emailRaw) {
        PendingRegistration p = pendingRepository.findByEmailIgnoreCase(emailRaw.trim())
                .orElseThrow(() -> ApiException.notFound("No pending registration for this email. Please register again."));
        long wait = Duration.between(Instant.now(), p.getSentAt().plusSeconds(resendCooldownSeconds)).getSeconds();
        if (wait > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting another code");
        }
        return issueCode(p);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Views.LoginResponse verify(String emailRaw, String code) {
        PendingRegistration p = pendingRepository.findByEmailIgnoreCase(emailRaw.trim())
                .orElseThrow(() -> ApiException.notFound("No pending registration for this email. Please register again."));
        if (p.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("The code has expired. Tap Resend code.");
        }
        if (p.getAttempts() >= maxAttempts) {
            throw ApiException.badRequest("Too many wrong codes. Tap Resend code to get a new one.");
        }
        if (code == null || !passwordEncoder.matches(code.trim(), p.getCodeHash())) {
            p.setAttempts(p.getAttempts() + 1); // kept: noRollbackFor
            throw ApiException.badRequest("Wrong code. Please check the email and try again.");
        }
        if (userRepository.existsByEmailIgnoreCase(p.getEmail())) {
            pendingRepository.delete(p);
            throw ApiException.conflict("An account with this email already exists");
        }
        User u = userRepository.save(User.builder()
                .name(p.getName()).email(p.getEmail()).phone(p.getPhone())
                .passwordHash(p.getPasswordHash()).role(User.Role.OWNER)
                .build());
        pendingRepository.delete(p);
        auditService.log(u, "User", u.getId(), "REGISTERED", null, "OWNER (email verified)");
        return new Views.LoginResponse(jwtService.issue(u), Views.UserView.of(u));
    }

    private Views.RegistrationStarted issueCode(PendingRegistration p) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant now = Instant.now();
        p.setCodeHash(passwordEncoder.encode(code));
        p.setSentAt(now);
        p.setExpiresAt(now.plus(Duration.ofMinutes(expiryMinutes)));
        p.setAttempts(0);
        pendingRepository.save(p);

        boolean emailed = sendCode(p, code);
        if (!emailed) {
            // No SMTP configured: the code is only in the server log (and in the response in demo mode).
            log.info("Email not configured - verification code for {} is {}", p.getEmail(), code);
        }
        return new Views.RegistrationStarted(p.getEmail(), expiryMinutes * 60, resendCooldownSeconds, emailed,
                !emailed && exposeCode ? code : null);
    }

    private boolean sendCode(PendingRegistration p, String code) {
        if (mailFrom == null || mailFrom.isBlank()) {
            return false;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(mailFrom);
            mail.setTo(p.getEmail());
            mail.setSubject("VeriScale verification code: " + code);
            mail.setText("Dear " + p.getName() + ",\n\nYour VeriScale verification code is " + code
                    + ".\nIt is valid for " + expiryMinutes + " minutes. Do not share it with anyone.\n\n"
                    + "If you did not try to register, you can ignore this email.\n\n- VeriScale");
            mailSender.send(mail);
            return true;
        } catch (Exception e) {
            log.warn("Could not email verification code to {}: {}", p.getEmail(), e.getMessage());
            return false;
        }
    }
}
