package com.sih26036.lmverify.service;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.UserRepository;
import com.sih26036.lmverify.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    /** Public self-registration is for instrument owners only; officers are created by the state admin. */
    @Transactional
    public Views.LoginResponse registerOwner(Requests.Register req) {
        String email = req.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User u = userRepository.save(User.builder()
                .name(req.name().trim())
                .email(email)
                .phone(req.phone())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(User.Role.OWNER)
                .build());
        auditService.log(u, "User", u.getId(), "REGISTERED", null, "OWNER");
        return new Views.LoginResponse(jwtService.issue(u), Views.UserView.of(u));
    }

    @Transactional(readOnly = true)
    public Views.LoginResponse login(Requests.Login req) {
        User u = userRepository.findByEmailIgnoreCase(req.email().trim())
                .filter(x -> passwordEncoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        if (!u.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        return new Views.LoginResponse(jwtService.issue(u), Views.UserView.of(u));
    }

    @Transactional(readOnly = true)
    public Views.UserView me(User u) {
        return Views.UserView.of(userRepository.findById(u.getId()).orElseThrow());
    }
}
