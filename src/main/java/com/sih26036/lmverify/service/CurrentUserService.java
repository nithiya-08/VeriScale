package com.sih26036.lmverify.service;

import com.sih26036.lmverify.entity.User;
import com.sih26036.lmverify.exception.ApiException;
import com.sih26036.lmverify.repository.UserRepository;
import com.sih26036.lmverify.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public User get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser au)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        User user = userRepository.findById(au.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User no longer exists"));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        return user;
    }
}
