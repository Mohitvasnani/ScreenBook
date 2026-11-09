package com.mohit.screenbook.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One definition of how a high-entropy token is stored.
 *
 * SHA-256, not BCrypt, and the distinction matters. BCrypt is deliberately slow
 * because passwords are low-entropy and worth brute-forcing. A 256-bit random
 * value has no dictionary to run against it, so slowness buys nothing and costs
 * a table scan: the row has to be found by its hash rather than by hashing every
 * candidate row in turn.
 *
 * Passwords do not come through here. They go through BCrypt.
 */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}