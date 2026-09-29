package com.sih26036.lmverify.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * ECDSA P-256 signing of certificate payloads. The private key never leaves the server, so a
 * forged QR code cannot carry a valid signature. The key pair is generated on first start and
 * stored in app.signing.key-dir (keep that folder out of git).
 */
@Slf4j
@Service
public class SigningService {

    private static final String ALGORITHM = "SHA256withECDSAinP1363Format";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public SigningService(@Value("${app.signing.key-dir}") String keyDir) {
        try {
            Path dir = Path.of(keyDir);
            Path priv = dir.resolve("cert-signing-private.key");
            Path pub = dir.resolve("cert-signing-public.key");
            KeyFactory kf = KeyFactory.getInstance("EC");
            if (Files.exists(priv) && Files.exists(pub)) {
                privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(priv).trim())));
                publicKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pub).trim())));
            } else {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair kp = gen.generateKeyPair();
                privateKey = kp.getPrivate();
                publicKey = kp.getPublic();
                Files.createDirectories(dir);
                Files.writeString(priv, Base64.getEncoder().encodeToString(privateKey.getEncoded()));
                Files.writeString(pub, Base64.getEncoder().encodeToString(publicKey.getEncoded()));
                log.info("Generated new certificate signing key pair in {}", dir.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialise signing keys", e);
        }
    }

    /** @return base64url signature (no padding) */
    public String sign(String payload) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(privateKey);
            s.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public boolean verify(String payload, String signatureB64Url) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(publicKey);
            s.update(payload.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getUrlDecoder().decode(signatureB64Url));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    /** PEM public key, published so anyone can verify certificates independently. */
    public String publicKeyPem() {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }
}
