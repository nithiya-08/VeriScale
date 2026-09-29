package com.sih26036.lmverify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Login is JWT-based, so Spring's default in-memory user (and its generated password) is not needed.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class LmVerifyApplication {
    public static void main(String[] args) {
        SpringApplication.run(LmVerifyApplication.class, args);
    }
}
