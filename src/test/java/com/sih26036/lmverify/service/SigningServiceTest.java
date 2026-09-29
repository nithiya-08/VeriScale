package com.sih26036.lmverify.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SigningServiceTest {

    @TempDir
    Path keyDir;

    private static final String PAYLOAD = "{\"certNo\":\"LM-2026-ABCDEFGH\",\"validUntil\":\"2027-01-01\"}";

    @Test
    void signatureVerifiesForTheExactPayload() {
        SigningService s = new SigningService(keyDir.toString());
        String sig = s.sign(PAYLOAD);
        assertThat(s.verify(PAYLOAD, sig)).isTrue();
    }

    @Test
    void tamperedPayloadFails() {
        SigningService s = new SigningService(keyDir.toString());
        String sig = s.sign(PAYLOAD);
        assertThat(s.verify(PAYLOAD.replace("2027", "2030"), sig)).isFalse();
    }

    @Test
    void tamperedOrGarbageSignatureFails() {
        SigningService s = new SigningService(keyDir.toString());
        String sig = s.sign(PAYLOAD);
        String flipped = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
        assertThat(s.verify(PAYLOAD, flipped)).isFalse();
        assertThat(s.verify(PAYLOAD, "not-base64!!")).isFalse();
        assertThat(s.verify(PAYLOAD, "")).isFalse();
    }

    @Test
    void keysPersistAcrossRestartsSoOldQrCodesStayValid() {
        String sig = new SigningService(keyDir.toString()).sign(PAYLOAD);
        assertThat(Files.exists(keyDir.resolve("cert-signing-private.key"))).isTrue();
        assertThat(new SigningService(keyDir.toString()).verify(PAYLOAD, sig)).isTrue();
    }

    @Test
    void anotherKeyPairCannotForgeASignature(@TempDir Path otherDir) {
        String forged = new SigningService(otherDir.toString()).sign(PAYLOAD);
        assertThat(new SigningService(keyDir.toString()).verify(PAYLOAD, forged)).isFalse();
    }

    @Test
    void publishesPemPublicKey() {
        assertThat(new SigningService(keyDir.toString()).publicKeyPem()).startsWith("-----BEGIN PUBLIC KEY-----");
    }
}
