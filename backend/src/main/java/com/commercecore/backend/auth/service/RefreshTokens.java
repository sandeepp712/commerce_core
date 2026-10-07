package com.commercecore.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class RefreshTokens {
    private static final SecureRandom random = new SecureRandom();
    private static final int BYTE_LENGTH = 32;

    private RefreshTokens() {}

    /** Returns a URL-safe, 43-char random string. This is what the client receives. */
    public static String newRawToken() {
        byte[] bytes = new byte[BYTE_LENGTH];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Deterministic hash so we can look the token up by hash. */
    public static String hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        }catch (NoSuchAlgorithmException e){
            throw new IllegalStateException("SHA-256 algorithm missing",e);
        }
    }


}