package com.sih26036.lmverify.controller;

import com.sih26036.lmverify.dto.Requests;
import com.sih26036.lmverify.dto.Views;
import com.sih26036.lmverify.service.AuthService;
import com.sih26036.lmverify.service.CurrentUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUser;

    @PostMapping("/api/auth/register")
    public Views.LoginResponse register(@Valid @RequestBody Requests.Register req) {
        return authService.registerOwner(req);
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
