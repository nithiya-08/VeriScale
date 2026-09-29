package com.sih26036.lmverify.security;

import com.sih26036.lmverify.entity.User;

/** Principal stored in the SecurityContext after the JWT is validated. */
public record AuthUser(Long id, String email, User.Role role) {
}
