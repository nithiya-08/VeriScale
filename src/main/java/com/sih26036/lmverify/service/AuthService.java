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

    @Transactional(readOnly = true)
    public Views.LoginResponse login(Requests.Login req) {
        User u = userRepository.findByEmailIgnoreCase(req.email().trim())
                .filter(x -> passwordEncoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        if (!u.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        // Checked only after the password matched, so it cannot be used to probe which emails exist.
        if (req.role() != null && req.role() != u.getRole()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This account is not registered as " + roleLabel(req.role()));
        }
        return new Views.LoginResponse(jwtService.issue(u), Views.UserView.of(u));
    }

    /** Same labels as the "Login as" choice on the login page (translated there). */
    private static String roleLabel(User.Role role) {
        return switch (role) {
            case OWNER -> "Owner";
            case LMO -> "Officer (LMO)";
            case GATC -> "Test centre (GATC)";
            case STATE_ADMIN -> "Admin";
        };
    }

    @Transactional(readOnly = true)
    public Views.UserView me(User u) {
        return Views.UserView.of(userRepository.findById(u.getId()).orElseThrow());
    }
}
