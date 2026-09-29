package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.service.AuthService;
import com.sih26036.lmverify.service.CurrentUserService;
import com.sih26036.lmverify.service.RegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;
    private final CurrentUserService currentUser;

    /** Owner sign-up, step 1: emails a 6-digit code. No account exists until it is verified. */
    @PostMapping("/api/auth/register")
    public Views.RegistrationStarted register(@Valid @RequestBody Requests.Register req) {
        return registrationService.start(req);
    }

    /** Owner sign-up, step 2: correct code creates the account and logs the owner in. */
    @PostMapping("/api/auth/register/verify")
    public Views.LoginResponse verify(@Valid @RequestBody Requests.VerifyEmail req) {
        return registrationService.verify(req.email(), req.code());
    }

    @PostMapping("/api/auth/register/resend")
    public Views.RegistrationStarted resend(@Valid @RequestBody Requests.ResendCode req) {
        return registrationService.resend(req.email());
    }

    @PostMapping("/api/auth/login")
    public Views.LoginResponse login(@Valid @RequestBody Requests.Login req) {
        return authService.login(req);
    }

    @GetMapping("/api/me")
    public Views.UserView me() {
        return authService.me(currentUser.get());
    }
}
