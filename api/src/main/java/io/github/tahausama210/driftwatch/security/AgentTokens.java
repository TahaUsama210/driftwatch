package io.github.tahausama210.driftwatch.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Generation and hashing of per-host agent tokens.
 *
 * <p>A token is {@code dwa_} followed by 32 bytes from {@link SecureRandom}, base64url-encoded
 * without padding (43 characters). Only its SHA-256 digest is stored. A fast, unsalted hash is
 * appropriate here, unlike for passwords: the input already carries 256 bits of entropy, so there is
 * nothing for a salt or a slow KDF to protect against.
 */
public final class AgentTokens {

    static final String PREFIX = "dwa_";
    private static final int RANDOM_BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("dwa_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private AgentTokens() {}

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + ENCODER.encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 of the token, as stored in {@code hosts.token_hash}. */
    public static String hash(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    /** Whether {@code token} could have been produced by {@link #generate()}. */
    static boolean hasValidFormat(String token) {
        return FORMAT.matcher(token).matches();
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
